package lux.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import lux.core.SnakesBoard
import lux.game.G
import lux.theme.BoardStyle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Snake & Ladders geometry in the same board units the Ludo scene uses (the whole art is [ART_CELLS] units square),
 * so pieces, the die, particles and effects are shared unchanged. One square is [SQ] units.
 */
object SnakesGeo {
    /** Frame round the 10 x 10 squares, in squares. */
    const val MARGIN = 0.18f
    val SQ = ART_CELLS / (10 + 2 * MARGIN)

    /** Board unit -> fraction of the art (0..1). */
    fun frac(v: Float) = (v + FRAME) / ART_CELLS

    /** Centre of square [cell] (1..100). */
    fun centre(cell: Int): G {
        val (col, row) = SnakesBoard.colRow(cell)
        return G(-FRAME + (MARGIN + col + 0.5f) * SQ, -FRAME + (MARGIN + (9 - row) + 0.5f) * SQ)
    }

    /** Below the board, left: where pieces wait before their first roll. */
    fun startPad(seat: Int, seats: Int): G = G(-FRAME + SQ * (0.55f + seat * 0.62f), ART_CELLS - FRAME + SQ * 0.55f)

    /** The die's place: in the tray under the board, on the right. */
    val dieSpot get() = G(ART_CELLS - FRAME - SQ * 1.25f, ART_CELLS - FRAME + SQ * 0.75f)

    /** Pieces sharing a square stand in a little cluster so all of them show. */
    fun spot(cell: Int, slot: Int, together: Int): G {
        val c = centre(cell)
        if (together <= 1) return c
        val d = SQ * 0.2f
        val offs = listOf(G(-d, -d * 0.6f), G(d, -d * 0.6f), G(-d, d * 0.6f), G(d, d * 0.6f))
        return c + offs[slot % 4]
    }

    /**
     * A snake's body from head to tail, as points in board units: a gentle S-curve whose waves depend on its length.
     * The painter and the slide animation both follow exactly these points.
     */
    fun spine(head: Int, tail: Int, n: Int = 64): List<G> {
        val a = centre(head); val b = centre(tail)
        val d = b - a
        val len = d.length()
        val nrm = G(-d.y, d.x).normalized()
        val waves = max(1f, (len / (SQ * 2.2f)).toInt().toFloat()) + 0.5f
        val amp = SQ * 0.42f * (if (head % 2 == 0) 1f else -1f)
        return List(n + 1) { i ->
            val k = i / n.toFloat()
            // No wiggle at the very head and tail, the biggest in the middle.
            // (sin(pi) comes out a hair below zero in floating point: clamp it, or the tail point is NaN.)
            val env = sqrt(sin(k * PI.toFloat()).coerceAtLeast(0f))
            a + d * k + nrm * (amp * env * sin(k * waves * 2f * PI.toFloat()))
        }
    }

    /** The two rails of a ladder: (left foot, left top, right foot, right top). */
    fun rails(foot: Int, top: Int): List<G> {
        val a = centre(foot); val b = centre(top)
        val n = G(-(b - a).y, (b - a).x).normalized() * (SQ * 0.24f)
        return listOf(a + n, b + n, a - n, b - n)
    }
}

private val snakeColors = listOf(
    Color(0xFF2E9E4F) to Color(0xFF145A2A), Color(0xFFD9483B) to Color(0xFF7A1E17), Color(0xFF8E54C9) to Color(0xFF45206B),
    Color(0xFFE48B1F) to Color(0xFF8A4A06), Color(0xFF1E9AA8) to Color(0xFF0B4D55), Color(0xFFC9437F) to Color(0xFF6A1640),
    Color(0xFF6B8E23) to Color(0xFF34470D), Color(0xFF3D6FD1) to Color(0xFF173777),
)

/** Paints the Snake & Ladders board for [st] into a [px] square bitmap (numbers need [measurer]). */
fun paintSnakesBoard(st: BoardStyle, px: Int, measurer: TextMeasurer, font: FontFamily): ImageBitmap {
    val img = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(px.toFloat(), px.toFloat())) { drawSnakesArt(st, measurer, font) }
    return img
}

@Composable
fun rememberSnakesBoard(st: BoardStyle, px: Int, font: FontFamily): ImageBitmap {
    val m = rememberTextMeasurer()
    return remember(st.id, px) { paintSnakesBoard(st, px, m, font) }
}

private fun DrawScope.drawSnakesArt(st: BoardStyle, measurer: TextMeasurer, font: FontFamily) {
    val total = size.minDimension
    val unit = total / ART_CELLS
    fun p(g: G) = Offset((g.x + FRAME) * unit, (g.y + FRAME) * unit)
    val sq = SnakesGeo.SQ * unit
    val m = SnakesGeo.MARGIN * sq

    // Frame.
    drawRoundRect(Brush.verticalGradient(listOf(lerp(st.frame, Color.White, 0.12f), st.frame, lerp(st.frame, Color.Black, 0.25f))), cornerRadius = CornerRadius(sq * 0.3f))
    drawRoundRect(st.frameTrim, Offset(m * 0.35f, m * 0.35f), Size(total - m * 0.7f, total - m * 0.7f), CornerRadius(sq * 0.24f), style = Stroke(m * 0.18f))

    // Squares: pale tints of the four seat colours on the diagonals, so the zig-zag rows read clearly.
    for (cell in 1..SnakesBoard.LAST) {
        val (col, row) = SnakesBoard.colRow(cell)
        val x = m + col * sq; val y = m + (9 - row) * sq
        val tint = pale(st.seat[(col + row) % 4], if ((col + row) % 2 == 0) 0.62f else 0.78f)
        drawRect(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.25f), tint), y, y + sq), Offset(x, y), Size(sq, sq))
        drawRect(lerp(tint, Color.Black, 0.18f), Offset(x, y), Size(sq, sq), style = Stroke(max(1f, sq * 0.02f)))
    }
    // Start and finish.
    run {
        val c100 = p(SnakesGeo.centre(100))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1B8), Color(0xFFFFC94A), Color(0x00FFC94A)), c100, sq * 0.62f), sq * 0.62f, c100)
        drawPath(starPath(c100.x, c100.y + sq * 0.06f, sq * 0.3f, sq * 0.13f, 5), Color(0xFFE08A12))
        drawPath(starPath(c100.x, c100.y + sq * 0.06f, sq * 0.24f, sq * 0.1f, 5), Color(0xFFFFE27A))
    }

    // Numbers, small in each square's corner (where pieces don't stand).
    val numStyle = TextStyle(fontFamily = font, fontSize = (sq * 0.22f).sp, color = Color(0xFF3B2A1A))
    for (cell in 1..SnakesBoard.LAST) {
        val (col, row) = SnakesBoard.colRow(cell)
        val x = m + col * sq; val y = m + (9 - row) * sq
        val t = measurer.measure("$cell", numStyle.copy(color = if (cell == 100) Color(0xFF7A3E00) else numStyle.color))
        drawText(t, topLeft = Offset(x + sq * 0.07f, y + sq * 0.03f), alpha = 0.8f)
    }
    run {
        val c1 = p(SnakesGeo.centre(1))
        val t = measurer.measure("START", TextStyle(fontFamily = font, fontSize = (sq * 0.2f).sp, color = Color(0xFF14532D)))
        drawText(t, topLeft = Offset(c1.x - t.size.width / 2f, c1.y + sq * 0.12f))
    }

    // Ladders first (snakes lie on top of them).
    for ((foot, top) in SnakesBoard.ladders) {
        val r = SnakesGeo.rails(foot, top).map { p(it) }
        val wood = Color(0xFFC98B4A); val woodDeep = Color(0xFF7A4A1E); val woodLight = Color(0xFFF2C58A)
        val w = sq * 0.085f
        val len = (r[1] - r[0]).getDistance()
        val rungs = max(2, (len / (sq * 0.42f)).toInt())
        // Shadow, then rungs, then rails.
        val sh = Offset(sq * 0.05f, sq * 0.07f)
        for (i in listOf(0, 2)) drawLine(Color.Black.copy(alpha = 0.22f), r[i] + sh, r[i + 1] + sh, w * 1.2f, StrokeCap.Round)
        for (k in 1 until rungs) {
            val t = k / rungs.toFloat()
            val a = lerp(r[0], r[1], t); val b = lerp(r[2], r[3], t)
            drawLine(Color.Black.copy(alpha = 0.18f), a + sh, b + sh, w * 0.8f, StrokeCap.Round)
            drawLine(woodDeep, a, b, w * 0.8f, StrokeCap.Round)
            drawLine(wood, a, b, w * 0.5f, StrokeCap.Round)
        }
        for (i in listOf(0, 2)) {
            drawLine(woodDeep, r[i], r[i + 1], w * 1.25f, StrokeCap.Round)
            drawLine(wood, r[i], r[i + 1], w * 0.85f, StrokeCap.Round)
            drawLine(woodLight, r[i], r[i + 1], w * 0.3f, StrokeCap.Round)
        }
    }

    // Snakes.
    for ((i, entry) in SnakesBoard.snakes.entries.withIndex()) {
        val (head, tail) = entry
        val pts = SnakesGeo.spine(head, tail).map { p(it) }
        val (body, deep) = snakeColors[i % snakeColors.size]
        val n = pts.size
        // Width tapers from the neck to the tail tip.
        fun width(k: Int) = sq * (0.26f * (1f - k / n.toFloat()) + 0.05f)
        // Shadow.
        for (k in 0 until n - 1) drawLine(Color.Black.copy(alpha = 0.2f), pts[k] + Offset(sq * 0.05f, sq * 0.08f), pts[k + 1] + Offset(sq * 0.05f, sq * 0.08f), width(k) * 1.1f, StrokeCap.Round)
        // Outline, body, belly highlight.
        for (k in 0 until n - 1) drawLine(lerp(deep, Color.Black, 0.3f), pts[k], pts[k + 1], width(k) * 1.12f, StrokeCap.Round)
        for (k in 0 until n - 1) drawLine(body, pts[k], pts[k + 1], width(k) * 0.95f, StrokeCap.Round)
        for (k in 0 until n - 1) drawLine(lerp(body, Color.White, 0.35f), pts[k] + Offset(-width(k) * 0.12f, -width(k) * 0.14f), pts[k + 1] + Offset(-width(k) * 0.12f, -width(k) * 0.14f), width(k) * 0.28f, StrokeCap.Round)
        // Diamonds down the back.
        for (k in 4 until n - 4 step 4) {
            val a = pts[k]; val b = pts[k + 1]
            val ang = atan2(b.y - a.y, b.x - a.x)
            val s = width(k) * 0.32f
            val dia = Path().apply {
                moveTo(a.x + cos(ang) * s * 1.4f, a.y + sin(ang) * s * 1.4f); lineTo(a.x - sin(ang) * s, a.y + cos(ang) * s)
                lineTo(a.x - cos(ang) * s * 1.4f, a.y - sin(ang) * s * 1.4f); lineTo(a.x + sin(ang) * s, a.y - cos(ang) * s); close()
            }
            drawPath(dia, lerp(deep, Color.Black, 0.1f))
            drawPath(dia, Color(0xFFFFE9A8).copy(alpha = 0.55f), style = Stroke(max(1f, sq * 0.015f)))
        }
        // Head: an oval pointing back along the neck, with eyes and a little forked tongue.
        val h = pts[0]; val neck = pts[3]
        val ang = atan2(h.y - neck.y, h.x - neck.x)
        val hw = sq * 0.27f; val hl = sq * 0.36f
        val fx = cos(ang); val fy = sin(ang)
        val headPath = Path().apply {
            val steps = 24
            for (s in 0..steps) {
                val t = s / steps.toFloat() * 2f * PI.toFloat()
                val lx = cos(t) * hl * (if (cos(t) > 0) 1f else 0.8f); val ly = sin(t) * hw
                val x = h.x + lx * fx - ly * fy; val y = h.y + lx * fy + ly * fx
                if (s == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val tip = Offset(h.x + fx * hl, h.y + fy * hl)
        drawLine(Color(0xFFD62828), tip, Offset(tip.x + fx * sq * 0.16f, tip.y + fy * sq * 0.16f), sq * 0.03f, StrokeCap.Round)
        for (sgn in listOf(-1f, 1f)) {
            val base = Offset(tip.x + fx * sq * 0.16f, tip.y + fy * sq * 0.16f)
            drawLine(Color(0xFFD62828), base, Offset(base.x + (fx * 0.07f - fy * 0.05f * sgn) * sq, base.y + (fy * 0.07f + fx * 0.05f * sgn) * sq), sq * 0.025f, StrokeCap.Round)
        }
        drawPath(headPath, lerp(deep, Color.Black, 0.3f), style = Stroke(sq * 0.05f, join = StrokeJoin.Round))
        drawPath(headPath, Brush.radialGradient(listOf(lerp(body, Color.White, 0.25f), body, deep), h, hl))
        for (sgn in listOf(-1f, 1f)) {
            val e = Offset(h.x + fx * hl * 0.35f - fy * hw * 0.5f * sgn, h.y + fy * hl * 0.35f + fx * hw * 0.5f * sgn)
            drawCircle(Color.White, sq * 0.075f, e)
            drawCircle(Color(0xFF111111), sq * 0.04f, Offset(e.x + fx * sq * 0.015f, e.y + fy * sq * 0.015f))
            drawCircle(Color.White, sq * 0.014f, Offset(e.x - sq * 0.012f, e.y - sq * 0.014f))
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
