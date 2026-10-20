package lux.core

/**
 * The ad platform's shared rules: what can be bought, where, for how long, for how much, and what is never allowed.
 * Ads are like TV or billboard ads: a picture shown to everyone playing in an area for a fixed time, at a fixed
 * price. No clicks, no links, no tracking of who saw what - only total counts for the advertiser's report.
 * Every game on the platform (Ludo now, more later) shows the same spots.
 */
object Ads {
    /** Where an ad appears. Pictures are cropped (centred) to [w] x [h] by the server. */
    enum class Spot(val title: String, val description: String, val w: Int, val h: Int, val nationalPerDay: Int) {
        Top("Top banner", "A strip above the board during every game", 1200, 200, 1500),
        Bottom("Bottom banner", "A strip below the board during every game", 1200, 200, 1000),
        Break("Between-games card", "A full card for 4 seconds between the games of a series, like a TV break", 1080, 1080, 2000);

        val ratio get() = w.toFloat() / h
    }

    /** All of India, or one state / union territory (ISO 3166-2 codes). */
    data class Area(val code: String, val name: String) { val national get() = code == NATIONAL }

    const val NATIONAL = "IN"

    val areas: List<Area> = listOf(Area(NATIONAL, "All India")) + listOf(
        "AN" to "Andaman and Nicobar", "AP" to "Andhra Pradesh", "AR" to "Arunachal Pradesh", "AS" to "Assam", "BR" to "Bihar",
        "CH" to "Chandigarh", "CT" to "Chhattisgarh", "DH" to "Dadra, Nagar Haveli, Daman and Diu", "DL" to "Delhi", "GA" to "Goa",
        "GJ" to "Gujarat", "HR" to "Haryana", "HP" to "Himachal Pradesh", "JK" to "Jammu and Kashmir", "JH" to "Jharkhand",
        "KA" to "Karnataka", "KL" to "Kerala", "LA" to "Ladakh", "LD" to "Lakshadweep", "MP" to "Madhya Pradesh",
        "MH" to "Maharashtra", "MN" to "Manipur", "ML" to "Meghalaya", "MZ" to "Mizoram", "NL" to "Nagaland", "OR" to "Odisha",
        "PY" to "Puducherry", "PB" to "Punjab", "RJ" to "Rajasthan", "SK" to "Sikkim", "TN" to "Tamil Nadu", "TG" to "Telangana",
        "TR" to "Tripura", "UP" to "Uttar Pradesh", "UT" to "Uttarakhand", "WB" to "West Bengal",
    ).map { (c, n) -> Area("IN-$c", n) }

    fun area(code: String): Area? = areas.firstOrNull { it.code == code }

    /** Two bookings of the same spot clash on a day if they cover the same place (All India covers every state). */
    fun overlaps(a: String, b: String) = a == b || a == NATIONAL || b == NATIONAL

    /** What an ad is for. The blocked ones are refused outright (Indian law and the ASCI ad code). */
    enum class Category(val title: String, val blocked: Boolean = false, val why: String = "") {
        Food("Food and restaurants"), Shop("Shops and products"), Education("Education and coaching"), Health("Clinics and health services"),
        Services("Local services"), Events("Events and festivals"), Brand("Brand awareness"), Public("Public service message"), Other("Something else"),
        Tobacco("Tobacco, pan masala, vaping", true, "Tobacco ads are banned by law (COTPA)."),
        Alcohol("Alcohol (including surrogate ads)", true, "Alcohol ads are banned by law."),
        Betting("Betting, gambling, real-money games", true, "Betting and real-money gaming ads are banned by law."),
        Adult("Adult content or dating", true, "Children play Ludo Lux too."),
        Weapons("Weapons", true, "Not suitable for a family game."),
        Finance("Crypto, trading tips, loans, investment schemes", true, "Financial promotions need regulatory approvals we don't handle."),
        Political("Political parties and candidates", true, "Not accepted for now (election rules need special handling)."),
    }

    val allowedCategories get() = Category.entries.filter { !it.blocked }

    // ---- booking limits
    const val MAX_DAYS = 30
    /** Bookings start tomorrow at the earliest, so every ad can be reviewed first. */
    const val MIN_LEAD_DAYS = 1
    const val MAX_LEAD_DAYS = 90
    val DAY_CHOICES = listOf(1, 3, 7, 14, 30)
    /** How long the between-games card stays up. */
    const val BREAK_SECONDS = 4
    const val GST_PERCENT = 18
    /** Largest picture upload accepted (bytes). */
    const val MAX_IMAGE_BYTES = 3_000_000

    /** Price of one day in [area] for [spot], in rupees (before GST). States are priced by size, with a floor. */
    fun dayPrice(spot: Spot, area: Area): Int =
        if (area.national) spot.nationalPerDay
        else ((spot.nationalPerDay * stateShare(area.code)).toInt() / 10 * 10).coerceAtLeast(spot.nationalPerDay / 15 / 10 * 10)

    /** Rough share of the country's players by state (population-based until real player counts replace it). */
    private fun stateShare(code: String): Float = when (code) {
        "IN-UP" -> 0.16f; "IN-MH" -> 0.10f; "IN-BR" -> 0.09f; "IN-WB" -> 0.075f; "IN-MP", "IN-TN", "IN-RJ" -> 0.06f
        "IN-KA", "IN-GJ" -> 0.05f; "IN-AP", "IN-OR", "IN-TG" -> 0.035f; "IN-KL", "IN-JH", "IN-AS", "IN-PB" -> 0.025f
        "IN-HR", "IN-CT", "IN-DL" -> 0.02f
        else -> 0.01f
    }

    data class Quote(val perDay: Int, val days: Int, val price: Int) {
        val gst get() = (price * GST_PERCENT + 50) / 100
        val total get() = price + gst
    }

    fun quote(spot: Spot, area: Area, days: Int): Quote {
        val perDay = dayPrice(spot, area)
        // Longer bookings get a little off, like TV slots: 7+ days 10%, 14+ days 15%, 30 days 25%.
        val off = when { days >= 30 -> 25; days >= 14 -> 15; days >= 7 -> 10; else -> 0 }
        return Quote(perDay, days, perDay * days * (100 - off) / 100)
    }

    /**
     * The centred crop of a [w] x [h] picture that fits [spot]'s shape: (x, y, width, height).
     * The app shows exactly this in its preview, and the server cuts exactly this.
     */
    fun crop(spot: Spot, w: Int, h: Int): IntArray {
        val target = spot.ratio
        return if (w.toFloat() / h > target) {
            val cw = (h * target).toInt().coerceIn(1, w)
            intArrayOf((w - cw) / 2, 0, cw, h)
        } else {
            val ch = (w / target).toInt().coerceIn(1, h)
            intArrayOf(0, (h - ch) / 2, w, ch)
        }
    }

    /** Status of a booking. Live/finished is worked out from the dates once a booking is [Scheduled]. */
    enum class Status(val title: String) {
        Review("Waiting for review"), Payment("Approved - waiting for payment"), Scheduled("Paid and scheduled"),
        Rejected("Not approved"), Cancelled("Cancelled"),
    }
}
