package lux.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import lux.core.Board
import lux.game.G
import lux.game.Geo
import lux.theme.BoardStyle
import lux.theme.Ornament
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Width of the frame round the 15x15 board, in cells. The whole art is (15 + 2 * FRAME) cells square. */
const val FRAME = 0.3f
const val ART_CELLS = 15f + 2 * FRAME

/** A light tint of a seat colour: every surface a piece can stand on is kept pale so full-colour pieces stand out. */
fun pale(c: Color, towardWhite: Float) = lerp(c, Color(1f, 0.995f, 0.98f), towardWhite)

/** The crisp outline drawn round every pale surface of a seat colour, so shapes read by their edges. */
fun edgeOf(st: BoardStyle, k: Int) = lerp(st.seatDeep[k], Color.Black, 0.12f)

/** Paints the board once into a bitmap of exactly [px] pixels, so it is pin-sharp and costs nothing per frame. */
fun paintBoard(st: BoardStyle, px: Int): ImageBitmap {
    val img = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(px.toFloat(), px.toFloat())) { drawBoardArt(st) }
    return img
}

@Composable
fun rememberBoard(st: BoardStyle, px: Int): ImageBitmap = remember(st.id, px) { paintBoard(st, px) }

fun starPath(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, rot: Float = -PI.toFloat() / 2): Path {
    val p = Path()
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = rot + i * PI.toFloat() / points
        if (i == 0) p.moveTo(cx + cos(a) * r, cy + sin(a) * r) else p.lineTo(cx + cos(a) * r, cy + sin(a) * r)
    }
    p.close()
    return p
}

/** Soft shadow under a rounded rectangle, built from a few feathered layers. */
fun DrawScope.softShadow(r: Rect, radius: Float, blur: Float, alpha: Float, dx: Float, dy: Float) {
    val layers = 6
    for (i in 0 until layers) {
        val grow = blur * (i + 1) / layers
        drawRoundRect(
            Color.Black.copy(alpha = alpha / layers), Offset(r.left + dx - grow / 2, r.top + dy - grow / 2),
            Size(r.width + grow, r.height + grow), CornerRadius(radius + grow / 2),
        )
    }
}

fun DrawScope.drawBoardArt(st: BoardStyle) {
    val total = size.minDimension
    val cell = total / ART_CELLS
    val o = FRAME * cell
    /** Unity art pixels (2048 across the 15 cells) to our pixels. */
    fun px(v: Float) = v * 15f * cell / 2048f
    fun X(col: Float) = o + col * cell
    fun Y(row: Float) = o + row * cell
    fun at(g: G) = Offset(X(g.x), Y(g.y))
    val neon = st.glow > 0f
    val edgeW = px(4.2f) * 2

    // ---- base: grout, so every gap reads as the same grid line
    val boardRect = Rect(X(0f), Y(0f), X(15f), Y(15f))
    drawRect(st.grout, boardRect.topLeft, boardRect.size)

    // ---- yards
    for (k in 0 until 4) {
        val c = at(Geo.rotate(G(3f, 3f), k))
        val seat = st.seat[k]; val deep = st.seatDeep[k]; val light = st.seatLight[k]
        val half = cell * 3f - px(4f)
        val outer = Rect(c.x - half, c.y - half, c.x + half, c.y + half)
        val rOuter = cell * 0.42f
        if (neon) {
            for (w in 1..4) drawRoundRect(seat.copy(alpha = 0.14f * st.glow), outer.topLeft, outer.size, CornerRadius(rOuter), style = Stroke(px(6f) * w))
            drawRoundRect(lerp(st.tile, deep, 0.35f), outer.topLeft, outer.size, CornerRadius(rOuter))
            drawRoundRect(seat, outer.topLeft, outer.size, CornerRadius(rOuter), style = Stroke(px(8f)))
        } else {
            softShadow(outer, rOuter, px(18f), 0.4f, px(5f), px(8f))
            drawRoundRect(
                Brush.linearGradient(listOf(seat, lerp(deep, seat, 0.25f)), Offset(c.x - half, c.y - half), Offset(c.x + half, c.y + half)),
                outer.topLeft, outer.size, CornerRadius(rOuter),
            )
            // A soft top-left sheen gives the coloured slab a little body.
            drawRoundRect(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(c.x - half, c.y - half), Offset(c.x, c.y)),
                outer.topLeft, outer.size, CornerRadius(rOuter),
            )
            drawRoundRect(edgeOf(st, k), outer.topLeft, outer.size, CornerRadius(rOuter), style = Stroke(edgeW))
        }

        val ih = cell * 2.35f
        val inner = Rect(c.x - ih, c.y - ih, c.x + ih, c.y + ih)
        val rInner = cell * 0.3f
        val base = lerp(st.yardInner, light, st.yardTint)
        drawRoundRect(base, inner.topLeft, inner.size, CornerRadius(rInner))
        // Gentle darkening towards the rim (the yard reads as a shallow tray).
        val rim = deep.copy(alpha = if (neon) 0.05f else 0.075f)
        for (i in 1..4) {
            val inset = cell * 0.08f * i
            drawRoundRect(rim, Offset(inner.left + inset / 2, inner.top + inset / 2), Size(inner.width - inset, inner.height - inset), CornerRadius(rInner), style = Stroke(cell * 0.08f))
        }
        if (neon) drawRoundRect(seat, inner.topLeft, inner.size, CornerRadius(rInner), style = Stroke(px(6f)))
        else {
            drawRoundRect(edgeOf(st, k), inner.topLeft, inner.size, CornerRadius(rInner), style = Stroke(edgeW + px(2f)))
            val t = px(8f)
            drawRoundRect(st.trim, Offset(inner.left + t, inner.top + t), Size(inner.width - 2 * t, inner.height - 2 * t), CornerRadius(rInner - t), style = Stroke(px(3f)))
        }
        drawOrnament(st, k, c, ih, cell, ::px)

        for (id in 0 until 4) {
            val w = at(Geo.baseSpot(k, id))
            val wr = cell * 0.54f
            if (neon) {
                for (g in 1..3) drawCircle(seat.copy(alpha = 0.18f), wr + px(2f) + g * px(5f), w, style = Stroke(px(8f)))
                drawCircle(seat, wr + px(6f), w)
                drawCircle(lerp(deep, Color.Black, 0.25f), wr, w)
                drawCircle(lerp(seat, deep, 0.3f), wr - px(9f), Offset(w.x + px(6f), w.y + px(6f)))
            } else {
                drawCircle(st.wellRim, wr + px(6f), w)
                // Light, softly hollowed wells: a full-colour piece stands out clearly against them.
                val wellC = lerp(seat, Color.White, 0.55f)
                drawCircle(
                    Brush.radialGradient(listOf(lerp(wellC, Color.White, 0.45f), wellC, lerp(wellC, deep, 0.18f)), Offset(w.x + wr * 0.25f, w.y + wr * 0.3f), wr * 1.25f),
                    wr, w,
                )
                drawCircle(edgeOf(st, k), wr + px(7f), w, style = Stroke(px(4.4f)))
                drawCircle(lerp(edgeOf(st, k), seat, 0.4f), wr, w, style = Stroke(px(3.2f)))
            }
        }
    }

    // ---- tiles
    val th = cell * 0.5f - px(st.tileGapPx)
    val tr = cell * st.tileRadius
    fun tile(cx: Float, cy: Float, fill: Color, edge: Color? = null) {
        val r = Rect(cx - th, cy - th, cx + th, cy + th)
        if (neon) drawRoundRect(st.tileOutline.copy(alpha = 0.3f * st.glow), Offset(r.left - px(4f), r.top - px(4f)), Size(r.width + px(8f), r.height + px(8f)), CornerRadius(tr + px(4f)), style = Stroke(px(6f)))
        else softShadow(r, tr, px(7f), 0.45f, px(2f), px(3f))
        if (st.tileSheen > 0f) drawRoundRect(Brush.verticalGradient(listOf(lerp(fill, Color.White, st.tileSheen), fill), r.top, r.bottom), r.topLeft, r.size, CornerRadius(tr))
        else drawRoundRect(fill, r.topLeft, r.size, CornerRadius(tr))
        if (edge != null && !neon) drawRoundRect(edge, r.topLeft, r.size, CornerRadius(tr), style = Stroke(edgeW))
        else if (st.outlinePx > 0f) drawRoundRect(st.tileOutline, r.topLeft, r.size, CornerRadius(tr), style = Stroke(px(st.outlinePx)))
    }

    for (g in 0 until Board.TRACK_LENGTH) {
        val cr = Board.trackCell(g)
        val cx = X(cr.col + 0.5f); val cy = Y(cr.row + 0.5f)
        if (g % Board.SEAT_SPACING == 0) {
            val k = g / Board.SEAT_SPACING
            tile(cx, cy, if (neon) lerp(st.tile, st.seat[k], 0.55f) else pale(st.seat[k], 0.6f), edgeOf(st, k))
            drawPath(starPath(cx, cy, cell * 0.34f, cell * 0.145f, 5), if (neon) st.startStar else st.seat[k])
            if (!neon) drawPath(starPath(cx, cy, cell * 0.34f, cell * 0.145f, 5), edgeOf(st, k), style = Stroke(px(2.4f)))
        } else {
            tile(cx, cy, st.tile)
            if (Board.isSafeSquare(g)) {
                if (neon) drawPath(starPath(cx, cy, cell * 0.38f, cell * 0.17f, 5), st.star.copy(alpha = 0.35f), style = Stroke(px(10f)))
                drawPath(starPath(cx, cy, cell * 0.35f, cell * 0.15f, 5), st.star)
                if (!neon) drawPath(starPath(cx, cy, cell * 0.35f, cell * 0.15f, 5), lerp(st.star, Color.Black, 0.3f), style = Stroke(px(2f)))
            }
        }
    }

    for (k in 0 until 4) for (pr in Board.HOME_LANE_START..56) {
        val cr = Board.homeLaneCell(k, pr)
        val cx = X(cr.col + 0.5f); val cy = Y(cr.row + 0.5f)
        val t = (pr - Board.HOME_LANE_START) / 4f
        tile(cx, cy, if (neon) lerp(st.tile, st.seat[k], 0.45f + t * 0.15f) else pale(st.seat[k], 0.62f - t * 0.1f), edgeOf(st, k))
        // Chevron pointing towards the centre.
        val dir = Geo.rotateVec(G(1f, 0f), k)
        val s = cell * 0.18f
        val tip = Offset(cx + dir.x * s, cy + dir.y * s)
        val a = Offset(cx - dir.x * s - dir.y * s, cy - dir.y * s + dir.x * s)
        val b = Offset(cx - dir.x * s + dir.y * s, cy - dir.y * s - dir.x * s)
        val chev = if (neon) Color.White.copy(alpha = 0.85f) else lerp(st.seat[k], st.seatDeep[k], 0.35f)
        drawLine(chev, a, tip, px(10f), StrokeCap.Round)
        drawLine(chev, b, tip, px(10f), StrokeCap.Round)
    }

    // ---- centre
    val centre = Offset(X(7.5f), Y(7.5f))
    for (k in 0 until 4) {
        val pa = at(Geo.rotate(G(6f, 6f), k)); val pb = at(Geo.rotate(G(6f, 9f), k))
        val tri = Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(centre.x, centre.y); close() }
        val mid = Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
        val brush = if (neon) Brush.linearGradient(listOf(st.seat[k], lerp(st.seat[k], st.seatLight[k], 0.6f)), mid, centre)
        else Brush.linearGradient(listOf(pale(st.seat[k], 0.5f), pale(st.seat[k], 0.7f)), mid, centre)
        drawPath(tri, brush)
        if (!neon) {
            // Inset outline so each colour's home triangle has a clear, dark edge of its own.
            val cen = Offset((pa.x + pb.x + centre.x) / 3, (pa.y + pb.y + centre.y) / 3)
            fun inset(p: Offset): Offset { val d = cen - p; val l = d.getDistance(); return p + d * (px(9f) / l) }
            val ia = inset(pa); val ib = inset(pb); val ic = inset(centre)
            drawPath(Path().apply { moveTo(ia.x, ia.y); lineTo(ib.x, ib.y); lineTo(ic.x, ic.y); close() }, edgeOf(st, k), style = Stroke(edgeW))
        }
        drawLine(st.trim, pa, centre, px(7f))
    }
    drawCircle(st.trim, cell * 0.32f, centre)
    drawPath(starPath(centre.x, centre.y, cell * 0.24f, cell * 0.1f, 8), if (neon) st.star else lerp(st.trim, Color.Black, 0.45f))
    if (st.doubleBorder) {
        val h = cell * 1.5f
        drawRect(st.trim, Offset(centre.x - h, centre.y - h), Size(2 * h, 2 * h), style = Stroke(px(4.4f)))
    }

    // ---- frame: a solid moulded ring whose inner corners match the yards; it overlaps the board edge slightly.
    val outerR = RoundRect(0f, 0f, total, total, CornerRadius(cell * 0.62f))
    val innerR = RoundRect(X(0.06f), Y(0.06f), X(14.94f), Y(14.94f), CornerRadius(cell * 0.47f))
    val ring = Path().apply { fillType = PathFillType.EvenOdd; addRoundRect(outerR); addRoundRect(innerR) }
    drawPath(ring, Brush.verticalGradient(listOf(lerp(st.frame, Color.White, 0.14f), st.frame, lerp(st.frame, Color.Black, 0.25f)), 0f, total))
    val midInset = (FRAME * cell) * 0.5f
    drawRoundRect(st.frameTrim, Offset(midInset, midInset), Size(total - 2 * midInset, total - 2 * midInset), CornerRadius(cell * 0.55f), style = Stroke(cell * 0.045f))
    if (st.frameGlow > 0f) for (w in 1..3) drawRoundRect(st.frameTrim.copy(alpha = 0.18f * st.frameGlow), Offset(midInset, midInset), Size(total - 2 * midInset, total - 2 * midInset), CornerRadius(cell * 0.55f), style = Stroke(cell * 0.045f + w * px(10f)))
    // Fine dark lines either side of the frame so the board edge is crisp against any table.
    drawPath(Path().apply { addRoundRect(innerR) }, lerp(st.frame, Color.Black, 0.55f), style = Stroke(px(4f)))
    drawPath(Path().apply { addRoundRect(outerR) }, lerp(st.frame, Color.Black, 0.55f), style = Stroke(px(5f)))
}

/** Decoration inside a yard's inner square (kept clear of the four wells and the die spot). */
private fun DrawScope.drawOrnament(st: BoardStyle, k: Int, c: Offset, inner: Float, cell: Float, px: (Float) -> Float) {
    val seat = st.seat[k]
    when (st.ornament) {
        Ornament.Filigree -> {
            // Gold quarter-arcs in the corners and a fine rosette in the middle.
            val gold = st.trim.copy(alpha = 0.75f)
            val clip = Path().apply { addRoundRect(RoundRect(c.x - inner + px(6f), c.y - inner + px(6f), c.x + inner - px(6f), c.y + inner - px(6f), CornerRadius(cell * 0.3f))) }
            val o = inner - cell * 0.32f
            clipPath(clip) {
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f))
                    drawCircle(gold, cell * 0.38f, Offset(c.x + sx * o, c.y + sy * o), style = Stroke(px(3.2f)))
            }
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f))
                drawCircle(gold, px(4.5f), Offset(c.x + sx * (inner - cell * 0.16f), c.y + sy * (inner - cell * 0.16f)))
            val rosette = starPath(c.x, c.y, cell * 0.62f, cell * 0.28f, 8, -PI.toFloat() / 2 + PI.toFloat() / 8)
            drawPath(rosette, seat.copy(alpha = 0.10f))
            drawPath(rosette, st.trim.copy(alpha = 0.45f), style = Stroke(px(2.8f)))
        }
        Ornament.Dots -> {
            val dot = st.seatLight[k].copy(alpha = 0.35f)
            val step = cell * 0.42f
            var x = c.x - inner + step * 0.5f
            while (x < c.x + inner) {
                var y = c.y - inner + step * 0.5f
                while (y < c.y + inner) {
                    val alt = (kotlin.math.round((x - c.x) / step) + kotlin.math.round((y - c.y) / step)).toInt() % 2 == 0
                    if (alt) drawCircle(dot, cell * 0.06f, Offset(x, y))
                    y += step
                }
                x += step
            }
        }
        Ornament.Grid -> {
            val line = seat.copy(alpha = 0.18f)
            val step = cell * 0.5f
            var x = c.x - inner + step
            while (x < c.x + inner - 1f) { drawLine(line, Offset(x, c.y - inner + px(8f)), Offset(x, c.y + inner - px(8f)), px(1.8f)); x += step }
            var y = c.y - inner + step
            while (y < c.y + inner - 1f) { drawLine(line, Offset(c.x - inner + px(8f), y), Offset(c.x + inner - px(8f), y), px(1.8f)); y += step }
        }
        Ornament.None -> {}
    }
}
