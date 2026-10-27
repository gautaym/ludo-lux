package lux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lux.app.Haptic
import lux.app.Nav
import lux.app.Platform
import lux.app.Prefs
import lux.audio.SfxId
import lux.audio.Synth
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import kotlin.math.roundToInt

/**
 * iPhone and iPad services: NSUserDefaults for settings, AVAudioPlayer for the synthesised sounds and the menu tune,
 * the Taptic Engine for vibration, and the system share sheet.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlatform : Platform {
    private val defaults = NSUserDefaults.standardUserDefaults
    override val prefs = object : Prefs {
        override fun getInt(key: String, default: Int) = if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default
        override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), key)
        override fun getString(key: String, default: String) = defaults.stringForKey(key) ?: default
        override fun putString(key: String, value: String) = defaults.setObject(value, key)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // "Ambient": game sounds mix with the person's music and stay quiet when the silent switch is on.
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryAmbient, null)
            session.setActive(true, null)
        }
    }

    // ---------------------------------------------------------------- sounds

    /** Every sound's variants as raw samples, built once in the background (the same synthesis as on Android). */
    private var raw: Map<SfxId, List<FloatArray>> = emptyMap()
    /** WAV bytes per (sound, variant, pitch): AVAudioPlayer has no pitch control, so pitched copies are made once. */
    private val pitched = HashMap<String, NSData>()
    /** Players still sounding (AVAudioPlayer stops if nothing holds on to it). */
    private val playing = ArrayList<AVAudioPlayer>()

    init {
        scope.launch {
            raw = withContext(Dispatchers.Default) { SfxId.entries.associateWith { Synth.buildRaw(it) } }
        }
    }

    override fun play(id: SfxId, volume: Float, pitch: Float) {
        val set = raw[id] ?: return
        if (set.isEmpty()) return
        val v = set.indices.random()
        val p = (pitch.coerceIn(0.5f, 2f) * 100).roundToInt()
        val key = "${id.name}/$v/$p"
        val data = pitched.getOrPut(key) { Synth.wav(resample(set[v], p / 100f)).toNSData() }
        playing.removeAll { !it.playing }
        val player = AVAudioPlayer(data, null)
        player.volume = (volume * 0.9f).coerceIn(0f, 1f)
        player.prepareToPlay()
        if (player.play()) playing += player
    }

    /** Plays the samples [pitch] times faster (higher and shorter, as Android's SoundPool does). */
    private fun resample(d: FloatArray, pitch: Float): FloatArray {
        if (pitch == 1f) return d
        val n = (d.size / pitch).toInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val x = i * pitch
            val k = x.toInt().coerceAtMost(d.size - 2)
            val f = x - k
            d[k] * (1 - f) + d[k + 1] * f
        }
    }

    // ---------------------------------------------------------------- menu music

    private var music: AVAudioPlayer? = null
    private var musicWanted = false

    override fun music(on: Boolean) {
        musicWanted = on
        if (!on) { music?.pause(); return }
        val m = music
        if (m != null) { if (!m.playing) m.play(); return }
        scope.launch {
            val wav = withContext(Dispatchers.Default) { Synth.music() }
            if (!musicWanted || music != null) return@launch
            val p = AVAudioPlayer(wav.toNSData(), null)
            p.numberOfLoops = -1
            p.volume = 0.32f
            p.prepareToPlay()
            music = p
            p.play()
        }
    }

    // ---------------------------------------------------------------- vibration, sharing

    override fun vibrate(strength: Haptic) {
        val style = when (strength) {
            Haptic.Light -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            Haptic.Medium -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
            Haptic.Heavy -> UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
        }
        UIImpactFeedbackGenerator(style).impactOccurred()
    }

    override fun share(text: String) {
        val root = topController() ?: return
        val sheet = UIActivityViewController(listOf(text), null)
        // iPad shows the sheet as a popover, which needs somewhere to point.
        sheet.popoverPresentationController?.sourceView = root.view
        root.presentViewController(sheet, true, null)
    }

    private fun topController(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .mapNotNull { (it as? UIWindowScene)?.windows }
            .flatten().mapNotNull { it as? UIWindow }
            .firstOrNull { it.isKeyWindow() }
        var c = window?.rootViewController
        while (c?.presentedViewController != null) c = c.presentedViewController
        return c
    }

    // ---------------------------------------------------------------- app in the background

    /** Pauses the game and the music when the app goes to the background, and brings the music back after. */
    fun watchLifecycle(nav: Nav) {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            music?.pause()
            nav.onBackground?.invoke()
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (musicWanted) music?.play()
        }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
