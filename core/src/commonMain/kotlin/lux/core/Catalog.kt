package lux.core

/**
 * What can be bought, and what it unlocks. Everything sold is cosmetic: nothing ever changes the dice, the rules or
 * anyone's chances. The game itself (home, family online, ranked) and the easiest-to-see boards are always free.
 *
 * Items are identified by kind + id, e.g. "board:neon", "capture:sword", "piece:coin".
 */
object Catalog {
    enum class Kind { Board, Capture, Piece }

    class Product(
        /** Google Play product id. */
        val id: String,
        val title: String,
        val description: String,
        /** Fallback price shown before Google Play's local price arrives (in rupees). */
        val rupees: Int,
        /** Items it unlocks. Empty for the passes (they unlock everything). */
        val unlocks: Set<String>,
        val subscription: Boolean = false,
    )

    const val UNLOCK_ALL = "unlock_all"
    const val FAMILY_PASS = "family_pass_yearly"
    /** How many members of the chosen family group a Family Pass covers (the buyer included). */
    const val FAMILY_PASS_SEATS = 5

    /** Always free. Sunny and Bold are the clearest boards for weak eyes, so they must never be behind a price. */
    val free: Set<String> = setOf(
        "board:royal", "board:sunny", "board:classic", "board:bold",
        "piece:pawn", "piece:coin",
        "capture:boom", "capture:confetti", "capture:smoke", "capture:zap", "capture:surprise",
    )

    val products = listOf(
        Product("board_neon", "Neon board", "Glowing arcade lights", 49, setOf("board:neon")),
        Product("captures_action", "Action captures", "Sword, Kick and Rewind", 49, setOf("capture:sword", "capture:kick", "capture:rewind")),
        Product(UNLOCK_ALL, "Unlock everything", "Every board, piece and effect - now and in the future", 199, emptySet()),
        Product(FAMILY_PASS, "Family Pass", "Everything for up to $FAMILY_PASS_SEATS people in one family group, for a year", 299, emptySet(), subscription = true),
    )

    fun product(id: String) = products.firstOrNull { it.id == id }

    /** Everything that can be unlocked at all. */
    val allItems: Set<String> get() = free + products.flatMap { it.unlocks }

    /** Whether [item] is usable with these owned products (and whether a Family Pass covers this person). */
    fun unlocked(item: String, owned: Set<String>, familyCovered: Boolean = false): Boolean =
        item in free || familyCovered || UNLOCK_ALL in owned || FAMILY_PASS in owned || owned.any { product(it)?.unlocks?.contains(item) == true }

    /** The product to offer for a locked [item] (the cheapest that unlocks it). */
    fun productFor(item: String): Product? = products.filter { item in it.unlocks }.minByOrNull { it.rupees } ?: product(UNLOCK_ALL)

    fun boardItem(id: String) = "board:$id"
    fun pieceItem(kind: Int) = if (kind == 1) "piece:coin" else "piece:pawn"
    /** Capture setting ordinal (0..6, 7 = surprise) to item id. */
    fun captureItem(ordinal: Int) = "capture:" + listOf("boom", "confetti", "smoke", "zap", "sword", "kick", "rewind", "surprise")[ordinal.coerceIn(0, 7)]
}
