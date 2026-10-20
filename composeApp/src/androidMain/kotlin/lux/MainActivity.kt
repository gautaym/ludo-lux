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
import com.android.billingclient.api.queryProductDetails
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
    // The system photo picker: the person chooses one picture; the app needs no storage permission.
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { platform.onPicked(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        platform = AndroidPlatform(this, this) {
            picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
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
class AndroidPlatform(context: Context, private val activity: android.app.Activity, private val launchPicker: () -> Unit = {}) : Platform {
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
        // Debug builds without a client id use a test identity (accepted only by a server in AUTH_MODE=test).
        if (clientId.isBlank() && testStore) return "test:" + prefs.getString("testGoogleId", "").ifEmpty { kotlin.random.Random.nextLong(1, Long.MAX_VALUE).toString().also { prefs.putString("testGoogleId", it) } } + ":Player"
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

    // ---------------------------------------------------------------- Google Play Billing

    /** Debug builds (installed from a file) use the test store, so the shop can be tried at home before Play is set up. */
    private val testStore = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var pending: kotlinx.coroutines.CompletableDeferred<String?>? = null
    private val billing by lazy {
        com.android.billingclient.api.BillingClient.newBuilder(activity)
            .setListener { result, purchases ->
                val done = pending ?: return@setListener
                pending = null
                when (result.responseCode) {
                    com.android.billingclient.api.BillingClient.BillingResponseCode.OK ->
                        done.complete(purchases?.firstOrNull { it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED }?.purchaseToken
                            ?: purchases?.firstOrNull()?.let { "" })
                    com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED -> done.complete(null)
                    else -> done.completeExceptionally(lux.net.NetError("The purchase did not go through. You were not charged."))
                }
            }
            .enablePendingPurchases(com.android.billingclient.api.PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    private suspend fun connected(): Boolean {
        if (billing.isReady) return true
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
        billing.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(r: com.android.billingclient.api.BillingResult) { ready.complete(r.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) }
            override fun onBillingServiceDisconnected() { if (!ready.isCompleted) ready.complete(false) }
        })
        return ready.await()
    }

    private suspend fun details(ids: List<String>, type: String): List<com.android.billingclient.api.ProductDetails> {
        val params = com.android.billingclient.api.QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            com.android.billingclient.api.QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
        }).build()
        return billing.queryProductDetails(params).productDetailsList.orEmpty()
    }

    override suspend fun prices(): Map<String, String> {
        if (testStore || !connected()) return emptyMap()
        val subs = lux.core.Catalog.products.filter { it.subscription }.map { it.id }
        val inapp = lux.core.Catalog.products.filter { !it.subscription }.map { it.id }
        val out = HashMap<String, String>()
        for (d in details(inapp, com.android.billingclient.api.BillingClient.ProductType.INAPP)) d.oneTimePurchaseOfferDetails?.formattedPrice?.let { out[d.productId] = it }
        for (d in details(subs, com.android.billingclient.api.BillingClient.ProductType.SUBS))
            d.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice?.let { out[d.productId] = it }
        return out
    }

    override suspend fun purchase(productId: String, subscription: Boolean): String? {
        if (testStore) return "test:" + productId + "-" + kotlin.random.Random.nextLong(1, Long.MAX_VALUE)
        if (!connected()) throw lux.net.NetError("Google Play is not available on this phone.")
        val type = if (subscription) com.android.billingclient.api.BillingClient.ProductType.SUBS else com.android.billingclient.api.BillingClient.ProductType.INAPP
        val d = details(listOf(productId), type).firstOrNull() ?: throw lux.net.NetError("This item is not in the shop yet.")
        val pp = com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        if (subscription) pp.setOfferToken(d.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: throw lux.net.NetError("This pass is not available yet."))
        val flow = com.android.billingclient.api.BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pp.build())).build()
        val result = kotlinx.coroutines.CompletableDeferred<String?>()
        pending = result
        val launch = billing.launchBillingFlow(activity, flow)
        if (launch.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) { pending = null; throw lux.net.NetError("The shop could not open. Please try again.") }
        val token = result.await() ?: return null
        // An empty token means a pending payment (e.g. cash at a store); it unlocks once it completes.
        if (token.isEmpty()) throw lux.net.NetError("Your payment is pending. The item unlocks as soon as it completes.")
        return token
    }

    // ---------------------------------------------------------------- choosing a picture (for an ad)

    private var picked: kotlinx.coroutines.CompletableDeferred<android.net.Uri?>? = null

    fun onPicked(uri: android.net.Uri?) { picked?.complete(uri); picked = null }

    override suspend fun pickImage(maxSide: Int): ByteArray? {
        val wait = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
        picked = wait
        launchPicker()
        val uri = wait.await() ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cr = activity.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) throw lux.net.NetError("That picture could not be read. Please try another.")
            // Decode at about the size needed (phone photos are huge), then shrink to maxSide.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            var bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw lux.net.NetError("That picture could not be read. Please try another.")
            // Photos taken sideways carry their rotation separately: apply it so the ad is upright.
            val turn = cr.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            } ?: 0f
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            if (turn != 0f || scale < 1f) {
                val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(turn) }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    }

    fun release() = pool.release()
}
