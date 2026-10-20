package lux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lux.core.Catalog
import lux.core.net.Entitlements
import lux.net.LuxClient
import lux.net.NetError

/**
 * What this player may use, kept on the phone so it works offline (the server is the source of truth and is asked
 * again whenever the shop or the online screens open). Everything sold is cosmetic.
 */
class ShopState(private val prefs: Prefs, private val net: LuxClient) {
    var owned by mutableStateOf(prefs.getString("owned", "").split(',').filter { it.isNotEmpty() }.toSet())
        private set
    var coveredBy by mutableStateOf(prefs.getString("coveredBy", ""))
        private set
    var passActive by mutableStateOf(prefs.getInt("passActive", 0) == 1)
        private set
    var passGroupId by mutableStateOf(prefs.getString("passGroup", ""))
        private set
    /** Local prices from Google Play (e.g. "₹49.00"), when known. */
    var prices by mutableStateOf<Map<String, String>>(emptyMap())

    fun unlocked(item: String) = Catalog.unlocked(item, owned, coveredBy.isNotEmpty())
    fun price(p: Catalog.Product) = prices[p.id] ?: "₹${p.rupees}"

    /** The board to actually use: the chosen one if unlocked, otherwise the default. */
    fun board(id: String) = if (unlocked(Catalog.boardItem(id))) id else "royal"
    /** The capture setting to actually use (a locked effect falls back to Surprise among the free ones). */
    fun capture(ordinal: Int) = if (unlocked(Catalog.captureItem(ordinal))) ordinal else CaptureStyle.SURPRISE
    /** Capture effects Surprise may pick from. */
    fun allowedCaptures() = CaptureStyle.entries.filter { unlocked(Catalog.captureItem(it.ordinal)) }

    fun apply(e: Entitlements) {
        owned = e.owned.toSet(); coveredBy = e.coveredBy; passActive = e.passActive; passGroupId = e.passGroupId
        prefs.putString("owned", owned.joinToString(",")); prefs.putString("coveredBy", coveredBy)
        prefs.putInt("passActive", if (passActive) 1 else 0); prefs.putString("passGroup", passGroupId)
    }

    suspend fun refresh() { if (net.signedIn) runCatching { apply(net.entitlements()) } }

    /**
     * Buys [product]: Google Play takes the payment, then the server confirms it with Google before unlocking.
     * Returns false if the player cancelled. Throws [NetError] with a friendly message otherwise.
     */
    suspend fun buy(product: Catalog.Product, platform: Platform): Boolean {
        if (!net.google) throw NetError("Please save your account with Google first, so your purchase is never lost.")
        val token = platform.purchase(product.id, product.subscription) ?: return false
        apply(net.purchase(product.id, token))
        return true
    }
}
