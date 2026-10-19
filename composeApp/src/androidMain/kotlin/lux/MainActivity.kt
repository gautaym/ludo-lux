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
        platform = AndroidPlatform(this, this)
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
class AndroidPlatform(context: Context, private val activity: android.app.Activity) : Platform {
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

    override fun share(text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
        // WhatsApp directly when it is installed (that is where families share); otherwise the normal share sheet.
        val whatsapp = android.content.Intent(send).setPackage("com.whatsapp")
        val ok = runCatching { activity.startActivity(whatsapp) }.isSuccess
        if (!ok) runCatching { activity.startActivity(android.content.Intent.createChooser(send, "Share invite")) }
    }

    override suspend fun googleIdToken(): String? {
        val clientId = activity.getString(lux.app.R.string.google_web_client_id)
        if (clientId.isBlank()) throw lux.net.NetError("Google sign-in is not set up in this test version yet. Use \"Play as guest\" for now.")
        val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(clientId).build()
        val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val res = androidx.credentials.CredentialManager.create(activity).getCredential(activity, request)
            val c = res.credential
            if (c is androidx.credentials.CustomCredential && c.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(c.data).idToken
            else throw lux.net.NetError("Google sign-in did not work. Please try again.")
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            null
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            throw lux.net.NetError("No Google account on this phone. Add one in the phone's Settings, or play as a guest.")
        } catch (e: androidx.credentials.exceptions.GetCredentialException) {
            throw lux.net.NetError("Google sign-in did not work. Please try again.")
        }
    }

    fun release() = pool.release()
}
