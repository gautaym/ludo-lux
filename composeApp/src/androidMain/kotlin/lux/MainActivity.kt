package lux

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lux.app.AppContext
import lux.app.Haptic
import lux.app.Nav
import lux.app.Platform
import lux.app.Prefs
import lux.audio.SfxId
import lux.audio.Synth
import lux.ui.LuxApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        platform = AndroidPlatform(this)
        val app = AppContext(platform)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!nav.back()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        setContent { LuxApp(app, nav) }
    }

    override fun onPause() {
        super.onPause()
        nav.onBackground?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        platform.release()
    }
}

/** Android services: SharedPreferences, a SoundPool of recorded + synthesised sounds, and the vibrator. */
class AndroidPlatform(context: Context) : Platform {
    private val sp = context.getSharedPreferences("lux", Context.MODE_PRIVATE)
    override val prefs = object : Prefs {
        override fun getInt(key: String, default: Int) = sp.getInt(key, default)
        override fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
        override fun getString(key: String, default: String) = sp.getString(key, default) ?: default
        override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
    }

    private val pool = SoundPool.Builder().setMaxStreams(10)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val sounds = ConcurrentHashMap<SfxId, IntArray>()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    init {
        // Recorded CC0 samples (assets/audio/<SfxId>__*.ogg) win; anything without samples is synthesised once and cached.
        val assets = context.assets
        val files = assets.list("audio").orEmpty()
        val cache = File(context.cacheDir, "sfx_v1").apply { mkdirs() }
        thread(name = "sfx-load") {
            for (id in SfxId.entries) {
                val recorded = files.filter { it.startsWith(id.name + "__") }
                val ids = if (recorded.isNotEmpty()) recorded.map { pool.load(assets.openFd("audio/$it"), 1) }
                else Synth.build(id).mapIndexed { i, wav ->
                    val f = File(cache, "${id.name}_$i.wav")
                    if (!f.exists()) f.writeBytes(wav)
                    pool.load(f.path, 1)
                }
                sounds[id] = ids.toIntArray()
            }
        }
    }

    override fun play(id: SfxId, volume: Float, pitch: Float) {
        val set = sounds[id] ?: return
        if (set.isEmpty()) return
        val v = (volume * 0.9f).coerceIn(0f, 1f)
        pool.play(set.random(), v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    override fun vibrate(strength: Haptic) {
        val v = vibrator ?: return
        val (ms, amp) = when (strength) { Haptic.Light -> 12L to 60; Haptic.Medium -> 24L to 140; Haptic.Heavy -> 55L to 255 }
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    }

    fun release() = pool.release()
}
