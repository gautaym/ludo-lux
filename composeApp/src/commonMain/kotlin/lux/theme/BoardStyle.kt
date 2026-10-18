package lux.theme

import androidx.compose.ui.graphics.Color

/** Decoration painted inside each yard. */
enum class Ornament { None, Filigree, Dots, Grid }

/**
 * Everything that makes one board look different from another. Sizes ending in "Px" are pixels on a 2048 px board
 * (the Unity art scale) and are scaled to the real board size when drawing.
 */
class BoardStyle(
    val id: String,
    val name: String,
    val tagline: String,
    val seat: List<Color>,
    val seatDeep: List<Color>,
    val seatLight: List<Color>,
    val tile: Color,
    val tileOutline: Color,
    val grout: Color,
    val trim: Color,
    val star: Color,
    val startStar: Color,
    val yardInner: Color,
    val wellRim: Color,
    val tileRadius: Float,
    val tileGapPx: Float,
    val outlinePx: Float,
    val glow: Float = 0f,
    val yardTint: Float,
    val ornament: Ornament,
    val doubleBorder: Boolean = false,
    val tileSheen: Float,
    val frame: Color,
    val frameTrim: Color,
    val frameGlow: Float = 0f,
    /** Table behind the board: dark, low-saturation, never sharing a hue with a seat colour (see the Unity notes). */
    val backCentre: Color,
    val backEdge: Color,
    val backPattern: Color,
    val skyBackdrop: Boolean = false,
    val collar: Color,
    val diceBody: Color,
    val dicePip: Color,
    val accent: Color,
    val plate: Color,
    val fancyTitle: Boolean = false,
) {
    /**
     * Piece colours: about 20% darker than the seat colours painted on the board, so a piece always stands out
     * from its own yard, start square and home lane. The light tone is the board's seat colour (for the shine).
     */
    val pieceSeat = seat.map { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.2f) }
    val pieceDeep = seatDeep.map { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.25f) }
    val pieceLight = seat

    /** True for boards whose squares are dark (text and pips on them must be light). */
    val darkTiles get() = tile.luminanceApprox() < 0.4f
}

fun Color.luminanceApprox() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun c(r: Float, g: Float, b: Float, a: Float = 1f) = Color(r, g, b, a)
private val W = Color.White

object BoardStyles {
    val royal = BoardStyle(
        id = "royal", name = "Royal", tagline = "Jewel tones and gold",
        seat = listOf(c(0.84f, 0.10f, 0.16f), c(0.04f, 0.60f, 0.32f), c(1.00f, 0.80f, 0.06f), c(0.12f, 0.34f, 0.88f)),
        seatDeep = listOf(c(0.48f, 0.04f, 0.08f), c(0.02f, 0.32f, 0.17f), c(0.72f, 0.48f, 0.02f), c(0.05f, 0.15f, 0.48f)),
        seatLight = listOf(c(1.00f, 0.42f, 0.42f), c(0.36f, 0.95f, 0.60f), c(1.00f, 0.95f, 0.50f), c(0.50f, 0.72f, 1.00f)),
        tile = c(0.98f, 0.95f, 0.86f), tileOutline = c(0.62f, 0.45f, 0.16f), grout = c(0.22f, 0.12f, 0.06f),
        trim = c(0.95f, 0.76f, 0.30f), star = c(0.93f, 0.68f, 0.16f), startStar = c(1f, 0.98f, 0.92f),
        yardInner = c(1f, 0.98f, 0.94f), wellRim = c(0.95f, 0.76f, 0.30f),
        tileRadius = 0.14f, tileGapPx = 6f, outlinePx = 3.6f, yardTint = 0.2f, ornament = Ornament.Filigree,
        doubleBorder = true, tileSheen = 0.1f,
        frame = c(0.30f, 0.16f, 0.08f), frameTrim = c(0.98f, 0.80f, 0.36f),
        backCentre = c(0.09f, 0.29f, 0.31f), backEdge = c(0.025f, 0.10f, 0.115f), backPattern = c(0.75f, 0.95f, 0.9f, 0.045f),
        collar = c(1f, 0.78f, 0.34f), diceBody = c(0.97f, 0.94f, 0.86f), dicePip = c(0.55f, 0.05f, 0.09f),
        accent = c(1f, 0.82f, 0.35f), plate = c(0.10f, 0.06f, 0.08f, 0.94f), fancyTitle = true,
    )

    /** Bright, friendly and very high contrast. Easiest to read; great for kids and older players. */
    val sunny = BoardStyle(
        id = "sunny", name = "Sunny", tagline = "Bright and easy to read",
        seat = listOf(c(0.89f, 0.27f, 0.25f), c(0.20f, 0.65f, 0.33f), c(0.97f, 0.75f, 0.17f), c(0.20f, 0.47f, 0.87f)),
        seatDeep = listOf(c(0.62f, 0.10f, 0.11f), c(0.07f, 0.42f, 0.19f), c(0.74f, 0.48f, 0.03f), c(0.07f, 0.25f, 0.60f)),
        seatLight = listOf(c(1.00f, 0.62f, 0.58f), c(0.58f, 0.90f, 0.64f), c(1.00f, 0.92f, 0.58f), c(0.58f, 0.76f, 0.98f)),
        tile = c(0.99f, 0.975f, 0.935f), tileOutline = c(0.36f, 0.43f, 0.53f), grout = c(0.76f, 0.84f, 0.92f),
        trim = W, star = c(1f, 0.62f, 0.08f), startStar = W, yardInner = W, wellRim = W,
        tileRadius = 0.2f, tileGapPx = 7f, outlinePx = 4.6f, yardTint = 0.12f, ornament = Ornament.Dots, tileSheen = 0.07f,
        frame = c(0.96f, 0.93f, 0.86f), frameTrim = c(0.90f, 0.52f, 0.14f),
        backCentre = c(0.42f, 0.63f, 0.84f), backEdge = c(0.69f, 0.81f, 0.91f), backPattern = c(1f, 0.90f, 0.70f), skyBackdrop = true,
        collar = W, diceBody = W, dicePip = c(0.10f, 0.10f, 0.14f),
        accent = c(1f, 0.78f, 0.15f), plate = c(0.07f, 0.18f, 0.40f, 0.94f),
    )

    /** Dark arcade board with glowing neon outlines. */
    val neon = BoardStyle(
        id = "neon", name = "Neon", tagline = "Glowing arcade lights",
        seat = listOf(c(1.00f, 0.20f, 0.42f), c(0.25f, 1.00f, 0.45f), c(1.00f, 0.90f, 0.20f), c(0.20f, 0.70f, 1.00f)),
        seatDeep = listOf(c(0.55f, 0.04f, 0.20f), c(0.05f, 0.50f, 0.20f), c(0.65f, 0.50f, 0.02f), c(0.04f, 0.28f, 0.60f)),
        seatLight = listOf(c(1.00f, 0.55f, 0.72f), c(0.65f, 1.00f, 0.75f), c(1.00f, 0.97f, 0.62f), c(0.60f, 0.88f, 1.00f)),
        tile = c(0.10f, 0.10f, 0.20f), tileOutline = c(0.30f, 0.95f, 1f), grout = c(0.03f, 0.03f, 0.08f),
        trim = c(0.30f, 0.95f, 1f), star = c(1f, 0.35f, 0.85f), startStar = W,
        yardInner = c(0.07f, 0.07f, 0.15f), wellRim = c(0.9f, 0.95f, 1f),
        tileRadius = 0.2f, tileGapPx = 9f, outlinePx = 2.6f, glow = 0.85f, yardTint = 0.12f, ornament = Ornament.Grid, tileSheen = 0f,
        frame = c(0.06f, 0.06f, 0.13f), frameTrim = c(0.30f, 0.95f, 1f), frameGlow = 1f,
        backCentre = c(0.08f, 0.06f, 0.20f), backEdge = c(0.01f, 0.01f, 0.05f), backPattern = c(0.6f, 0.5f, 1f, 0.06f),
        collar = c(0.85f, 0.9f, 1f), diceBody = c(0.12f, 0.12f, 0.22f), dicePip = c(0.30f, 0.95f, 1f),
        accent = c(0.30f, 0.95f, 1f), plate = c(0.05f, 0.04f, 0.12f, 0.94f),
    )

    /** Traditional look: ivory board, muted primaries, cherry-wood frame on a neutral slate table. */
    val classic = BoardStyle(
        id = "classic", name = "Classic", tagline = "Like the board at home",
        seat = listOf(c(0.80f, 0.16f, 0.15f), c(0.13f, 0.52f, 0.24f), c(0.95f, 0.72f, 0.10f), c(0.13f, 0.32f, 0.72f)),
        seatDeep = listOf(c(0.50f, 0.06f, 0.06f), c(0.05f, 0.30f, 0.12f), c(0.66f, 0.44f, 0.02f), c(0.05f, 0.15f, 0.44f)),
        seatLight = listOf(c(0.98f, 0.55f, 0.52f), c(0.52f, 0.85f, 0.58f), c(1.00f, 0.90f, 0.52f), c(0.52f, 0.68f, 0.96f)),
        tile = c(0.97f, 0.94f, 0.86f), tileOutline = c(0.42f, 0.32f, 0.22f), grout = c(0.36f, 0.26f, 0.19f),
        trim = c(0.97f, 0.92f, 0.80f), star = c(0.30f, 0.30f, 0.32f), startStar = W,
        yardInner = c(0.98f, 0.96f, 0.90f), wellRim = c(0.97f, 0.92f, 0.80f),
        tileRadius = 0.08f, tileGapPx = 5f, outlinePx = 3.8f, yardTint = 0.15f, ornament = Ornament.None, tileSheen = 0.05f,
        frame = c(0.42f, 0.14f, 0.09f), frameTrim = c(0.97f, 0.88f, 0.66f),
        backCentre = c(0.22f, 0.24f, 0.26f), backEdge = c(0.09f, 0.10f, 0.11f), backPattern = c(1f, 1f, 1f, 0.035f),
        collar = c(0.97f, 0.92f, 0.80f), diceBody = c(0.98f, 0.96f, 0.90f), dicePip = c(0.12f, 0.10f, 0.10f),
        accent = c(1f, 0.80f, 0.36f), plate = c(0.10f, 0.10f, 0.12f, 0.94f), fancyTitle = true,
    )

    /** Maximum contrast for weak eyesight: white squares, thick black lines, deep solid colours. Nothing decorative. */
    val bold = BoardStyle(
        id = "bold", name = "Bold", tagline = "Clearest for weak eyes",
        seat = listOf(c(0.86f, 0.08f, 0.10f), c(0.00f, 0.55f, 0.24f), c(1.00f, 0.76f, 0.00f), c(0.05f, 0.28f, 0.85f)),
        seatDeep = listOf(c(0.45f, 0.02f, 0.04f), c(0.00f, 0.28f, 0.11f), c(0.55f, 0.38f, 0.00f), c(0.01f, 0.10f, 0.42f)),
        seatLight = listOf(c(1.00f, 0.55f, 0.55f), c(0.45f, 0.88f, 0.58f), c(1.00f, 0.92f, 0.45f), c(0.48f, 0.66f, 1.00f)),
        tile = W, tileOutline = c(0.05f, 0.05f, 0.06f), grout = c(0.08f, 0.08f, 0.09f),
        trim = W, star = c(0.1f, 0.1f, 0.12f), startStar = W, yardInner = W, wellRim = c(0.08f, 0.08f, 0.09f),
        tileRadius = 0.06f, tileGapPx = 7f, outlinePx = 6.5f, yardTint = 0.1f, ornament = Ornament.None, tileSheen = 0f,
        frame = c(0.06f, 0.06f, 0.07f), frameTrim = W,
        backCentre = c(0.17f, 0.18f, 0.20f), backEdge = c(0.07f, 0.07f, 0.08f), backPattern = c(1f, 1f, 1f, 0f),
        collar = W, diceBody = W, dicePip = c(0.05f, 0.05f, 0.06f),
        accent = c(1f, 0.82f, 0.2f), plate = c(0.06f, 0.06f, 0.07f, 0.96f),
    )

    val all = listOf(royal, sunny, neon, classic, bold)

    fun get(id: String) = all.firstOrNull { it.id == id } ?: royal
}
