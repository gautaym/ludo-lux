package lux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lux.core.Ads
import lux.core.net.AdCreative
import lux.core.net.AdsNow
import lux.net.LuxClient
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The ads this phone should show right now, asked from the server when the app opens and before each game. Only
 * pictures and a sponsor name come back - nothing is clickable, and nothing about the player is sent except the rough
 * area (and their account, so Family Pass holders get no ads). Offline means no ads.
 */
class AdsState(private val prefs: Prefs, private val net: LuxClient) {
    /** "" = automatic (from the internet connection); otherwise the area the player chose in Settings. */
    var chosenArea by mutableStateOf(prefs.getString("adArea", ""))
        private set
    var now by mutableStateOf<AdsNow?>(null)
        private set
    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val reported = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A random id for this installation (not linked to the phone or the person), so reach counts players, not visits. */
    val installId: String = prefs.getString("installId", "").ifEmpty {
        (1..20).map { "abcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(36)] }.joinToString("").also { prefs.putString("installId", it) }
    }

    fun chooseArea(code: String) { chosenArea = code; prefs.putString("adArea", code) }

    /** The ad for [spot] with its picture, once the picture has arrived (null = nothing to show). */
    fun ad(spot: Ads.Spot): Pair<AdCreative, ImageBitmap>? {
        val n = now ?: return null
        if (n.adFree) return null
        val c = n.ads.firstOrNull { it.spot == spot.name } ?: return null
        return images[c.booking]?.let { c to it }
    }

    suspend fun refresh() {
        val n = runCatching { net.adsNow(chosenArea, installId) }.getOrNull() ?: return
        for (c in n.ads) if (c.booking !in images) {
            runCatching { images[c.booking] = net.bytes(c.image).decodeToImageBitmap() }
        }
        now = n
    }

    fun refreshSoon() { scope.launch { refresh() } }

    /** Tells the server an ad was really on screen (once per app session; the server counts each player once a day). */
    fun shown(c: AdCreative) {
        if (!reported.add(c.booking)) return
        scope.launch { runCatching { net.adsSeen(listOf(c.booking), installId) } }
    }
}
