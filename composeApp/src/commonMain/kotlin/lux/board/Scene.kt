package lux.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import lux.game.AwardSprite
import lux.game.DK
import lux.game.Decal
import lux.game.DieSprite
import lux.game.G
import lux.game.Geo
import lux.game.PK
import lux.game.Particle
import lux.game.PieceSprite
import lux.game.Stage
import lux.theme.BoardStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How far up the screen one cell of height moves a thing (a gentle 2.5D: pieces and the die stand up off the board). */
const val LIFT = 0.55f
private const val DEG = PI.toFloat() / 180f

/** Maps board grid points (and heights) to pixels for a board drawn at [left],[top] with [cell] px cells, turned [turns] quarter turns. */
class View(val left: Float, val top: Float, val cell: Float, val turns: Int) {
    val size get() = cell * ART_CELLS
    fun ground(p: G): Offset {
        val r = Geo.rotate(p, turns)
        return Offset(left + (FRAME + r.x) * cell, top + (FRAME + r.y) * cell)
    }
    fun at(p: G, z: Float) = ground(p).let { Offset(it.x, it.y - z * cell * LIFT) }
    /** Screen pixel back to an (unrotated) board point. */
    fun toGrid(s: Offset): G = Geo.rotate(G((s.x - left) / cell - FRAME, (s.y - top) / cell - FRAME), -turns)
    /** A board-space direction as seen on screen. */
    fun screenDir(d: G) = Geo.rotateVec(d, turns)
}

class SceneFonts(val measurer: TextMeasurer, val display: FontFamily)

// ====================================================================== the whole table

fun DrawScope.drawScene(stage: Stage, view: View, board: ImageBitmap, fonts: SceneFonts) {
    val st = stage.style
    val kind = stage.app.settings.pieceStyle
    val cell = view.cell
    val boardSize = view.size
    val zoom = 1f + stage.focusAmount + stage.punchZoom
    val fp = view.ground(stage.focusPoint)
    val pivot = Offset(view.left + boardSize / 2, view.top + boardSize / 2)
    // Lean the camera towards the focus point, but only part of the way, so the board never leaves the screen.
    val pull = Offset((fp.x - pivot.x) * stage.focusAmount * 0.6f, (fp.y - pivot.y) * stage.focusAmount * 0.6f)
    withTransform({
        translate(stage.shake.x * cell - pull.x, stage.shake.y * cell - pull.y)
        scale(zoom, zoom, pivot)
    }) {
        // Board, turned so the local player sits bottom-left.
        softShadow(Rect(view.left, view.top, view.left + boardSize, view.top + boardSize), cell * 0.62f, cell * 0.9f, 0.55f, cell * 0.12f, cell * 0.3f)
        rotate(stage.viewTurns * 90f, pivot) {
            drawImage(board, IntOffset.Zero, IntSize(board.width, board.height), dstOffset = IntOffset(view.left.toInt(), view.top.toInt()), dstSize = IntSize(board.width, board.height))
        }
        if (stage.spotAlpha > 0.005f) {
            val c = view.ground(stage.spotAt)
            clipPath(Path().apply { addRect(Rect(view.left, view.top, view.left + boardSize, view.top + boardSize)) }) {
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent, 0.14f to Color.Transparent, 0.45f to Color.Black.copy(alpha = stage.spotAlpha),
                        1f to Color.Black.copy(alpha = stage.spotAlpha), center = c, radius = cell * 9f,
                    ),
                    Offset(view.left, view.top), Size(boardSize, boardSize),
                )
            }
        }

        // Ground effects.
        for (d in stage.decals) if (d.started && d.kind in groundKinds) drawDecal(d, view, st, stage.time)

        // Shadows first, then everything standing on the board from back to front.
        val die = stage.die
        for (t in stage.targets) drawTarget(t, view, st, stage.time, stage.time - stage.targetsFlashAt < 1.4f, kind)
        for (p in stage.pieces) if (p.visible) shadow(view.ground(p.pos), cell * 0.36f * PIECE_SIZE * p.scale, p.z, p.alpha * (1f - p.dissolve))
        if (die.visible) shadow(view.ground(die.pos), cell * 0.62f, die.z, 1f)
        val order = stage.pieces.filter { it.visible }.sortedBy { view.ground(it.pos).y }
        var dieDrawn = !die.visible
        for (p in order) {
            if (!dieDrawn && view.ground(die.pos).y < view.ground(p.pos).y) { drawDie(die, view, st, stage.time); dieDrawn = true }
            drawPiece(p, view, st, stage.time, kind)
        }
        if (!dieDrawn) drawDie(die, view, st, stage.time)
        stage.overlay?.invoke(this, view)

        for (pt in stage.particles) drawParticle(pt, view, st, kind)
        for (d in stage.decals) if (d.started && d.kind !in groundKinds) drawDecal(d, view, st, stage.time)
        for (a in stage.awards) drawAward(a, view, fonts)
    }
}

private val groundKinds = setOf(DK.Ring, DK.Rune, DK.Scorch, DK.Rays)

/**
 * A landing marker: a pulsing ring in the piece colour, with a centre dot, on the square it would move to. Tapping it moves
 * exactly that piece. After an unclear tap the markers blink brighter to show where to tap.
 */
private fun DrawScope.drawTarget(t: lux.game.Target, view: View, st: BoardStyle, time: Float, flashing: Boolean, kind: Int) {
    val cell = view.cell
    val g = view.ground(t.pos)
    val pulse = 0.5f + 0.5f * sin(time * 6f)
    val strong = if (flashing) 0.6f + 0.4f * abs(sin(time * 9f)) else 0.55f + 0.2f * pulse
    drawCircle(st.pieceSeat[t.color].copy(alpha = 0.22f * strong), cell * 0.46f, g)
    drawCircle(Color.White.copy(alpha = 0.9f * strong), cell * (0.42f + 0.04f * pulse), g, style = Stroke(cell * 0.06f))
    drawCircle(st.pieceSeat[t.color].copy(alpha = strong), cell * (0.42f + 0.04f * pulse), g, style = Stroke(cell * 0.035f))
    // A bright centre dot, like a target: "put it here".
    drawCircle(Color.White.copy(alpha = 0.95f * strong), cell * 0.14f, g)
    drawCircle(st.pieceSeat[t.color].copy(alpha = strong), cell * 0.09f, g)
}


private fun DrawScope.shadow(at: Offset, r: Float, z: Float, alpha: Float) {
    val spread = 1f + z * 0.25f
    val a = 0.32f * alpha / (1f + z * 0.8f)
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = a), Color.Black.copy(alpha = a * 0.6f), Color.Transparent), Offset(at.x, at.y + r * 0.05f), r * spread * 1.15f),
        Offset(at.x - r * spread * 1.15f, at.y - r * spread * 0.55f + r * 0.08f), Size(r * spread * 2.3f, r * spread * 1.1f),
    )
}

// ====================================================================== pieces

/** Unit pawn silhouette with its base at (0,0), height ~1.06, built once. */
private val pawnBody = Path().apply {
    moveTo(-0.31f, -0.1f)
    cubicTo(-0.30f, -0.30f, -0.12f, -0.40f, -0.115f, -0.62f)
    lineTo(0.115f, -0.62f)
    cubicTo(0.12f, -0.40f, 0.30f, -0.30f, 0.31f, -0.1f)
    cubicTo(0.20f, -0.02f, -0.20f, -0.02f, -0.31f, -0.1f)
    close()
}

/** Pieces are drawn this much bigger than one square-unit (pawn about 0.95 of a square wide, coin about 1.0). */
const val PIECE_SIZE = 1.2f

/** Piece designs: 0 = pawn, 1 = coin. */
const val PIECE_PAWN = 0
const val PIECE_COIN = 1
val PIECE_NAMES = listOf("Pawn", "Coin")

/** Height of the body's middle above the base, in units (spins and sword cuts pivot here). */
fun bodyCentre(kind: Int) = if (kind == PIECE_COIN) -0.27f else -0.45f

fun DrawScope.drawPiece(p: PieceSprite, view: View, st: BoardStyle, time: Float, kind: Int = PIECE_PAWN) {
    val cell = view.cell
    val base = view.ground(p.pos)
    var z = p.z
    if (p.selectable) z += 0.12f * abs(sin(time * 5.5f))
    val u = cell * PIECE_SIZE * p.scale * (1f + p.z * 0.1f)
    // Pieces are centred on their square: the base sits a little below the square's centre.
    val drop = if (kind == PIECE_COIN) 0.24f else 0.3f
    val foot = Offset(base.x + p.shake.x * cell, base.y + u * drop - z * cell * LIFT + p.shake.y * cell)
    if (p.selectable) {
        // "Pick me": a bright ring with a ripple, big enough to read at a glance.
        val k = (time * 1.6f) % 1f
        drawCircle(st.accent.copy(alpha = 0.35f), cell * 0.6f, base)
        drawCircle(Color.White.copy(alpha = 0.95f * (1f - k)), cell * (0.58f + 0.35f * k), base, style = Stroke(cell * 0.08f))
        drawCircle(st.accent, cell * 0.6f, base, style = Stroke(cell * 0.07f))
    }
    val c = p.color
    val alpha = p.alpha * (1f - p.dissolve)
    if (alpha <= 0.01f) return
    if (p.halo > 0.01f) {
        val r = u * 0.4f
        val a = p.halo * alpha
        drawCircle(st.pieceSeat[c].copy(alpha = 0.45f * a), r, base)
        drawCircle(Color.White.copy(alpha = a), r, base, style = Stroke(u * 0.075f))
        drawCircle(lerp(st.pieceDeep[c], Color.Black, 0.3f).copy(alpha = a), r + u * 0.05f, base, style = Stroke(u * 0.03f))
    }
    val cut = p.cut
    if (cut == null) {
        pawn(foot, u, st.pieceSeat[c], st.pieceDeep[c], st.pieceLight[c], st.collar, alpha, p.flash, p.squash, p.spin, p.flip, p.lean, p.joy, time, kind)
    } else {
        // Sword cut: two halves split along the blade's line, parting and tipping away from each other.
        val alphaCut = alpha * (1f - cut.fade)
        for (side in listOf(1f, -1f)) {
            val off = Offset(cut.nx * side * cut.apart * cell, cut.ny * side * cut.apart * cell)
            val half = Path().apply {
                // Half-plane through the body centre, as a big polygon in pawn-local units.
                val cx = 0f; val cy = bodyCentre(kind)
                val tx = -cut.ny; val ty = cut.nx
                moveTo(cx + tx * 3, cy + ty * 3); lineTo(cx + tx * 3 + cut.nx * side * 3, cy + ty * 3 + cut.ny * side * 3)
                lineTo(cx - tx * 3 + cut.nx * side * 3, cy - ty * 3 + cut.ny * side * 3); lineTo(cx - tx * 3, cy - ty * 3); close()
            }
            withTransform({
                translate(foot.x + off.x, foot.y + off.y)
                rotate(cut.tilt * side * 0.5f, Offset.Zero)
                scale(u, u, Offset.Zero)
            }) {
                clipPath(half) {
                    pawnLocal(st.pieceSeat[c], st.pieceDeep[c], st.pieceLight[c], st.collar, alphaCut, p.flash, u, kind)
                }
                if (cut.glow > 0.01f) {
                    val tx = -cut.ny; val ty = cut.nx
                    drawLine(lerp(st.seat[c], Color(1f, 0.95f, 0.8f), 0.6f).copy(alpha = cut.glow * alphaCut), Offset(tx * 0.5f, bodyCentre(kind) + ty * 0.5f), Offset(-tx * 0.5f, bodyCentre(kind) - ty * 0.5f), 0.06f, StrokeCap.Round, blendMode = BlendMode.Plus)
                }
            }
        }
    }
}

/** A glossy pawn standing at [foot], [u] pixels per unit. */
fun DrawScope.pawn(
    foot: Offset, u: Float, seat: Color, deep: Color, light: Color, collar: Color, alpha: Float,
    flash: Float = 0f, squash: Float = 0f, spin: Float = 0f, flip: Float = 1f, lean: Float = 0f, joy: Float = 0f, time: Float = 0f,
    kind: Int = PIECE_PAWN,
) {
    val mid = bodyCentre(kind)
    val sy = (1f + squash * 0.22f) * flip
    val sx = 1f - squash * 0.1f
    withTransform({
        translate(foot.x, foot.y)
        if (lean != 0f) rotate(lean, Offset.Zero)
        if (spin != 0f) rotate(spin, Offset(0f, mid * u))
        if (joy > 0f) rotate(sin(time * 18f) * 8f * joy, Offset.Zero)
        scale(sx * u, sy * u, Offset(0f, if (flip < 0.999f) mid * u else 0f))
    }) {
        pawnLocal(seat, deep, light, collar, alpha, flash, u, kind)
    }
}

/** Draws the pawn in unit coordinates (base at origin). [u] is only used to keep hairlines a sensible pixel width. */
private fun DrawScope.pawnLocal(seat: Color, deep: Color, light: Color, collar: Color, alpha: Float, flash: Float, u: Float, kind: Int = PIECE_PAWN) {
    if (kind == PIECE_COIN) { coinLocal(seat, deep, light, collar, alpha, flash, u); return }
    val edge = lerp(deep, Color.Black, 0.45f).copy(alpha = alpha)
    val line = max(0.045f, 1.6f / u)
    // Plinth: a thin white ring round a dark base, so every piece has a crisp edge on any square.
    drawOval(edge, Offset(-0.39f, -0.25f), Size(0.78f, 0.32f))
    drawOval(Color.White.copy(alpha = alpha), Offset(-0.36f, -0.235f), Size(0.72f, 0.28f))
    drawOval(Brush.horizontalGradient(listOf(light, seat, deep), -0.33f, 0.33f), Offset(-0.33f, -0.22f), Size(0.66f, 0.24f), alpha = alpha)
    // Body.
    drawPath(pawnBody, Brush.horizontalGradient(0f to light, 0.35f to seat, 1f to deep, startX = -0.31f, endX = 0.31f), alpha = alpha)
    drawPath(pawnBody, Brush.verticalGradient(listOf(Color.Transparent, deep.copy(alpha = 0.45f)), -0.45f, -0.05f), alpha = alpha)
    drawPath(pawnBody, edge, style = Stroke(line))
    // Collar.
    drawOval(lerp(collar, Color.Black, 0.35f).copy(alpha = alpha), Offset(-0.2f, -0.705f), Size(0.4f, 0.14f))
    drawOval(Brush.horizontalGradient(listOf(Color.White, collar, lerp(collar, Color.Black, 0.25f)), -0.18f, 0.18f), Offset(-0.18f, -0.7f), Size(0.36f, 0.11f), alpha = alpha)
    // Head.
    val hc = Offset(0f, -0.86f)
    drawCircle(edge, 0.235f, hc)
    drawCircle(Brush.radialGradient(0f to lerp(light, Color.White, 0.5f), 0.45f to seat, 1f to deep, center = Offset(-0.07f, -0.93f), radius = 0.3f), 0.21f, hc, alpha = alpha)
    drawOval(Color.White.copy(alpha = 0.75f * alpha), Offset(-0.13f, -1.0f), Size(0.11f, 0.075f))
    if (flash > 0.01f) {
        val f = Color.White.copy(alpha = (flash * 0.85f * alpha).coerceAtMost(1f))
        drawPath(pawnBody, f)
        drawCircle(f, 0.21f, hc)
        drawOval(f, Offset(-0.36f, -0.235f), Size(0.72f, 0.28f))
    }
}

/**
 * The coin: a thick round token seen at an angle, with a white ring and a star, like the classic wooden Ludo tokens.
 * Lower than the pawn (it hides less of the board) and wide on top (easy to see and tap). Base at the origin.
 */
private fun DrawScope.coinLocal(seat: Color, deep: Color, light: Color, collar: Color, alpha: Float, flash: Float, u: Float) {
    val edge = lerp(deep, Color.Black, 0.45f).copy(alpha = alpha)
    val line = max(0.04f, 1.6f / u)
    val rx = 0.43f; val ry = 0.2f
    val topY = -0.36f; val botY = -0.17f
    // Side band: bottom ellipse plus the strip up to the top face.
    val side = Path().apply {
        addOval(Rect(-rx, botY - ry, rx, botY + ry))
        addRect(Rect(-rx, topY, rx, botY))
    }
    drawOval(edge, Offset(-rx - line, botY - ry - line), Size(2 * (rx + line), 2 * (ry + line)))
    drawPath(side, Brush.horizontalGradient(0f to lerp(seat, light, 0.3f), 0.4f to seat, 1f to lerp(deep, Color.Black, 0.2f), startX = -rx, endX = rx), alpha = alpha)
    // A white stripe round the rim: the token's thin white ring.
    drawLine(Color.White.copy(alpha = 0.9f * alpha), Offset(-rx, (topY + botY) / 2 + 0.01f), Offset(rx, (topY + botY) / 2 + 0.01f), 0.025f)
    drawLine(edge, Offset(-rx, topY), Offset(-rx, botY), line)
    drawLine(edge, Offset(rx, topY), Offset(rx, botY), line)
    // Top face.
    val top = Rect(-rx, topY - ry, rx, topY + ry)
    drawOval(Brush.radialGradient(0f to lerp(light, Color.White, 0.35f), 0.55f to seat, 1f to lerp(seat, deep, 0.5f), center = Offset(-0.12f, topY - 0.09f), radius = 0.55f), top.topLeft, top.size, alpha = alpha)
    drawOval(edge, top.topLeft, top.size, style = Stroke(line))
    // Inner white ring around a raised centre disc with a glossy dot (no star: that would look like a safe square).
    drawOval(Color.White.copy(alpha = 0.92f * alpha), Offset(-0.3f, topY - 0.14f), Size(0.6f, 0.28f), style = Stroke(0.035f))
    val inner = Rect(-0.2f, topY - 0.095f, 0.2f, topY + 0.095f)
    drawOval(lerp(deep, Color.Black, 0.2f).copy(alpha = alpha), Offset(inner.left, inner.top + 0.02f), inner.size)
    drawOval(Brush.radialGradient(0f to lerp(light, Color.White, 0.25f), 1f to seat, center = Offset(-0.06f, topY - 0.05f), radius = 0.24f), inner.topLeft, inner.size, alpha = alpha)
    drawOval(edge, inner.topLeft, inner.size, style = Stroke(line * 0.8f))
    drawOval(Color.White.copy(alpha = 0.8f * alpha), Offset(-0.1f, topY - 0.07f), Size(0.09f, 0.045f))
    // Glossy highlight.
    drawArc(Color.White.copy(alpha = 0.7f * alpha), 195f, 70f, false, Offset(-rx * 0.85f, topY - ry * 0.85f), Size(rx * 1.7f, ry * 1.7f), style = Stroke(0.035f, cap = StrokeCap.Round))
    if (flash > 0.01f) {
        val f = Color.White.copy(alpha = (flash * 0.85f * alpha).coerceAtMost(1f))
        drawPath(side, f)
        drawOval(f, top.topLeft, top.size)
    }
}

// ====================================================================== the die: a real cube, projected

private val pipLayout = mapOf(
    1 to listOf(0f to 0f),
    2 to listOf(-0.5f to -0.5f, 0.5f to 0.5f),
    3 to listOf(-0.5f to -0.5f, 0f to 0f, 0.5f to 0.5f),
    4 to listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f),
    5 to listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0f to 0f, -0.5f to 0.5f, 0.5f to 0.5f),
    6 to listOf(-0.5f to -0.55f, 0.5f to -0.55f, -0.5f to 0f, 0.5f to 0f, -0.5f to 0.55f, 0.5f to 0.55f),
)

/** Face value, normal, and in-face axes (u, v) in die body space: x right, y up, z towards the viewer. */
private class Face(val value: Int, val n: FloatArray, val u: FloatArray, val v: FloatArray)
private val faces = listOf(
    Face(1, floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, -1f, 0f)),
    Face(6, floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f)),
    Face(2, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
    Face(5, floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
    Face(3, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, -1f, 0f)),
    Face(4, floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, -1f, 0f)),
)

/** Rotation matrix (row-major 3x3): camera tilt * Rz * Ry * Rx. */
private fun dieMatrix(rx: Float, ry: Float, rz: Float): FloatArray {
    fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    fun x(a: Float) = floatArrayOf(1f, 0f, 0f, 0f, cos(a), -sin(a), 0f, sin(a), cos(a))
    fun y(a: Float) = floatArrayOf(cos(a), 0f, sin(a), 0f, 1f, 0f, -sin(a), 0f, cos(a))
    fun z(a: Float) = floatArrayOf(cos(a), -sin(a), 0f, sin(a), cos(a), 0f, 0f, 0f, 1f)
    // The camera looks slightly from the front, so a sliver of the die's near side shows: it reads as a solid cube.
    return mul(x(-24f * DEG), mul(z(rz * DEG), mul(y(ry * DEG), x(rx * DEG))))
}

private fun FloatArray.apply3(v: FloatArray) = floatArrayOf(
    this[0] * v[0] + this[1] * v[1] + this[2] * v[2],
    this[3] * v[0] + this[4] * v[1] + this[5] * v[2],
    this[6] * v[0] + this[7] * v[1] + this[8] * v[2],
)

fun DrawScope.drawDie(d: DieSprite, view: View, st: BoardStyle, time: Float) {
    val cell = view.cell
    val ground = view.ground(d.pos)
    val s = cell * 0.62f * d.scale * (1f - d.squash * 0.04f)
    val centre = Offset(ground.x, ground.y - d.z * cell * LIFT - s * 0.1f)
    val glow = d.pulse(time)
    if (d.glow > 0.01f) {
        drawCircle(Brush.radialGradient(listOf(d.accent.copy(alpha = 0.55f * d.glow), Color.Transparent), ground, cell * (1.25f + 0.25f * glow)), cell * (1.25f + 0.25f * glow), ground)
        drawCircle(Color.White.copy(alpha = 0.85f * d.glow), cell * (0.95f + 0.12f * glow), ground, style = Stroke(cell * 0.07f))
    }
    drawCube(centre, s, d.rx, d.ry, d.rz, st.diceBody, st.dicePip)
}

/** Draws a die cube of half-size [h] pixels centred at [c]. */
fun DrawScope.drawCube(c: Offset, h: Float, rx: Float, ry: Float, rz: Float, body: Color, pip: Color) {
    val m = dieMatrix(rx, ry, rz)
    // Silhouette (fills the seams between the rounded faces).
    val corners = ArrayList<Offset>(8)
    for (ix in listOf(-1f, 1f)) for (iy in listOf(-1f, 1f)) for (iz in listOf(-1f, 1f)) {
        val p = m.apply3(floatArrayOf(ix * 0.93f, iy * 0.93f, iz * 0.93f))
        corners += Offset(c.x + p[0] * h, c.y - p[1] * h)
    }
    val hull = convexHull(corners)
    val edge = lerp(body, Color.Black, 0.55f)
    drawPath(Path().apply { hull.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }, lerp(body, Color.Black, 0.3f))
    val light = floatArrayOf(-0.45f, 0.6f, 0.66f)
    val visible = faces.map { f -> f to m.apply3(f.n) }.filter { it.second[2] > 0.01f }.sortedBy { it.second[2] }
    for ((f, n) in visible) {
        val u = m.apply3(f.u); val v = m.apply3(f.v)
        val centre = Offset(c.x + n[0] * h, c.y - n[1] * h)
        // Affine map from face-local (s, t) in [-1, 1] to the screen.
        val mat = Matrix()
        mat.values[Matrix.ScaleX] = u[0] * h; mat.values[Matrix.SkewY] = -u[1] * h
        mat.values[Matrix.SkewX] = v[0] * h; mat.values[Matrix.ScaleY] = -v[1] * h
        mat.values[Matrix.TranslateX] = centre.x; mat.values[Matrix.TranslateY] = centre.y
        val lambert = (n[0] * light[0] + n[1] * light[1] + n[2] * light[2]).coerceIn(0f, 1f)
        val shade = 0.62f + 0.38f * lambert
        val face = Color(body.red * shade, body.green * shade, body.blue * shade)
        withTransform({ transform(mat) }) {
            drawRoundRect(face, Offset(-0.93f, -0.93f), Size(1.86f, 1.86f), CornerRadius(0.32f))
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f * n[2]), Color.Transparent), Offset(-1f, -1f), Offset(0.6f, 0.6f)), Offset(-0.93f, -0.93f), Size(1.86f, 1.86f), CornerRadius(0.32f))
            for ((px, py) in pipLayout.getValue(f.value)) {
                val r = if (f.value == 1) 0.24f else 0.17f
                drawCircle(lerp(pip, Color.Black, 0.3f), r, Offset(px, py + 0.03f))
                drawCircle(pip, r * 0.9f, Offset(px, py))
            }
        }
    }
    drawPath(Path().apply { hull.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }, edge, style = Stroke(max(1.5f, h * 0.05f)))
}

private fun convexHull(pts: List<Offset>): List<Offset> {
    val p = pts.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.size - 1); lower += q }
    val upper = ArrayList<Offset>()
    for (q in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.size - 1); upper += q }
    lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
    return lower + upper
}

// ====================================================================== particles

private fun DrawScope.drawParticle(p: Particle, view: View, st: BoardStyle, kind: Int) {
    val cell = view.cell
    val k = p.age / p.life
    val at = view.at(G(p.x, p.y), p.z)
    val size = (p.size + (p.endSize - p.size) * k) * cell
    when (p.kind) {
        PK.Glow -> {
            val a = p.color.alpha * (1f - k)
            drawCircle(Brush.radialGradient(listOf(p.color.copy(alpha = a), Color.Transparent), at, size), size, at, blendMode = BlendMode.Plus)
        }
        PK.Ember -> {
            val a = p.color.alpha * (1f - k * k)
            drawCircle(Brush.radialGradient(listOf(p.color.copy(alpha = a * 0.7f), Color.Transparent), at, size * 1.8f), size * 1.8f, at, blendMode = BlendMode.Plus)
            drawCircle(lerp(p.color, Color.White, 0.6f).copy(alpha = a), size * 0.45f, at, blendMode = BlendMode.Plus)
        }
        PK.Sparkle -> {
            val a = p.color.alpha * (1f - k)
            val s = size * (1f - k * 0.5f)
            rotate(p.rot, at) {
                drawPath(starPath(at.x, at.y, s, s * 0.22f, 4), p.color.copy(alpha = a), blendMode = BlendMode.Plus)
            }
            drawCircle(Color.White.copy(alpha = a), s * 0.18f, at, blendMode = BlendMode.Plus)
        }
        PK.Streak -> {
            val a = p.color.alpha * (1f - k)
            val sd = view.screenDir(G(p.vx, p.vy))
            val tail = Offset(at.x - sd.x * cell * 0.045f, at.y - sd.y * cell * 0.045f + p.vz * cell * LIFT * 0.045f)
            drawLine(p.color.copy(alpha = a), at, tail, size * 0.5f, StrokeCap.Round, blendMode = BlendMode.Plus)
        }
        PK.Confetti -> {
            val a = if (k > 0.75f) (1f - k) * 4f else 1f
            val w = size * abs(cos(p.rot * DEG * 2f)) + size * 0.15f
            rotate(p.rot, at) { drawRect(p.color.copy(alpha = a), Offset(at.x - w / 2, at.y - size * 0.3f), Size(w, size * 0.6f)) }
        }
        PK.Smoke, PK.Dust -> {
            val a = p.color.alpha * (1f - k) * min(1f, k * 8f)
            drawCircle(Brush.radialGradient(listOf(p.color.copy(alpha = a), p.color.copy(alpha = a * 0.4f), Color.Transparent), at, size), size, at)
        }
        PK.Shard -> {
            val a = if (k > 0.7f) (1f - k) / 0.3f else 1f
            rotate(p.rot, at) {
                val tri = Path().apply { moveTo(at.x, at.y - size); lineTo(at.x + size * 0.8f, at.y + size * 0.6f); lineTo(at.x - size * 0.7f, at.y + size * 0.5f); close() }
                drawPath(tri, p.color.copy(alpha = a))
                drawPath(tri, lerp(p.color, Color.Black, 0.5f).copy(alpha = a), style = Stroke(max(1f, size * 0.12f)))
            }
        }
        PK.Ghost -> {
            val a = p.color.alpha * (1f - k)
            val c = p.tag
            val foot = Offset(at.x, at.y + cell * 0.3f)
            pawn(foot, cell * PIECE_SIZE, lerp(st.pieceSeat[c], p.color, 0.35f), st.pieceDeep[c], st.pieceLight[c], st.collar, a, kind = kind)
        }
    }
}

// ====================================================================== decals

private fun envelope(k: Float, fadeIn: Float = 0.15f, fadeOut: Float = 0.35f) =
    min(1f, k / fadeIn).coerceAtLeast(0f) * min(1f, (1f - k) / fadeOut).coerceAtLeast(0f)

private fun DrawScope.drawDecal(d: Decal, view: View, st: BoardStyle, time: Float) {
    val cell = view.cell
    val k = d.k
    val g = view.ground(d.pos)
    when (d.kind) {
        DK.Ring -> {
            val e = 1f - (1f - k) * (1f - k)
            val r = (d.r0 + (d.r1 - d.r0) * e) * cell
            drawCircle(d.color.copy(alpha = d.alpha * (1f - k)), r, g, style = Stroke(d.width * cell * (1f - k * 0.6f) + 1f), blendMode = BlendMode.Plus)
        }
        DK.Flash -> {
            val at = view.at(d.pos, d.z)
            val r = (d.r0 + (d.r1 - d.r0) * min(1f, k * 3f)) * cell * 0.5f
            val a = d.alpha * (1f - k) * (1f - k)
            drawCircle(Brush.radialGradient(listOf(d.color.copy(alpha = a), d.color.copy(alpha = a * 0.35f), Color.Transparent), at, r), r, at, blendMode = BlendMode.Plus)
        }
        DK.Rays -> {
            val a = d.alpha * envelope(k)
            val r = (d.r0 + (d.r1 - d.r0) * smoothK(k * 2f)) * cell
            rotate(d.spin * d.age, g) {
                for (i in 0 until 12) {
                    val a0 = i * 30f * DEG
                    val wedge = Path().apply { moveTo(g.x, g.y); lineTo(g.x + cos(a0 - 0.12f) * r, g.y + sin(a0 - 0.12f) * r); lineTo(g.x + cos(a0 + 0.12f) * r, g.y + sin(a0 + 0.12f) * r); close() }
                    drawPath(wedge, Brush.radialGradient(listOf(d.color.copy(alpha = a), Color.Transparent), g, r), blendMode = BlendMode.Plus)
                }
            }
        }
        DK.Pillar -> {
            val a = d.alpha * envelope(k, 0.1f, 0.5f)
            val w = (d.r0 + (d.r1 - d.r0) * k) * cell
            val h = cell * 6f
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, d.color.copy(alpha = a * 0.7f), d.color.copy(alpha = a)), g.y - h, g.y), Offset(g.x - w, g.y - h), Size(2 * w, h), blendMode = BlendMode.Plus)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = a * 0.7f)), g.y - h * 0.8f, g.y), Offset(g.x - w * 0.3f, g.y - h * 0.8f), Size(w * 0.6f, h * 0.8f), blendMode = BlendMode.Plus)
            drawOval(Brush.radialGradient(listOf(d.color.copy(alpha = a), Color.Transparent), g, w * 1.6f), Offset(g.x - w * 1.6f, g.y - w * 0.7f), Size(w * 3.2f, w * 1.4f), blendMode = BlendMode.Plus)
        }
        DK.Rune -> {
            val a = d.alpha * envelope(k, 0.2f, 0.4f)
            val r = (d.r0 + (d.r1 - d.r0) * smoothK(k * 2.5f)) * cell
            rotate(d.spin * d.age, g) {
                val col = d.color.copy(alpha = a)
                drawCircle(col, r, g, style = Stroke(cell * 0.05f), blendMode = BlendMode.Plus)
                drawCircle(col, r * 0.78f, g, style = Stroke(cell * 0.03f), blendMode = BlendMode.Plus)
                for (i in 0 until 12) {
                    val an = i * 30f * DEG
                    drawLine(col, Offset(g.x + cos(an) * r * 0.8f, g.y + sin(an) * r * 0.8f), Offset(g.x + cos(an) * r * 0.97f, g.y + sin(an) * r * 0.97f), cell * 0.03f, blendMode = BlendMode.Plus)
                }
                drawPath(starPath(g.x, g.y, r * 0.74f, r * 0.42f, 6), col, style = Stroke(cell * 0.03f), blendMode = BlendMode.Plus)
            }
        }
        DK.Scorch -> {
            val a = d.alpha * (1f - k)
            val r = (d.r0 + (d.r1 - d.r0) * min(1f, k * 6f)) * cell * 0.5f
            drawCircle(Brush.radialGradient(listOf(Color(0.08f, 0.06f, 0.06f, a * 0.8f), Color(0.1f, 0.08f, 0.08f, a * 0.35f), Color.Transparent), g, r), r, g)
        }
        DK.StarBurst -> {
            val at = view.at(d.pos, 0.4f)
            val r = (d.r0 + (d.r1 - d.r0) * min(1f, k * 2.5f)) * cell * 0.6f
            val a = d.alpha * (1f - k)
            drawPath(starPath(at.x, at.y, r, r * 0.16f, 4, 0f), d.color.copy(alpha = a), blendMode = BlendMode.Plus)
            drawPath(starPath(at.x, at.y, r * 0.6f, r * 0.1f, 4, PI.toFloat() / 4), Color.White.copy(alpha = a), blendMode = BlendMode.Plus)
        }
        DK.Bolt -> {
            val pts = d.data ?: return
            val a = d.alpha * (1f - k) * (1f - k)
            val path = Path()
            val n = pts.size / 3
            for (i in 0 until n) {
                val o = view.at(G(pts[i * 3], pts[i * 3 + 1]), pts[i * 3 + 2])
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            val w = d.width * cell
            drawPath(path, d.color.copy(alpha = a * 0.25f), style = Stroke(w * 4f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            drawPath(path, d.color.copy(alpha = a * 0.7f), style = Stroke(w * 1.6f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            drawPath(path, Color.White.copy(alpha = a), style = Stroke(w * 0.5f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            // Forks off some of the joints.
            val branches = d.r1.toInt()
            for (b in 0 until branches) {
                val i = 2 + (b * 5 + 3) % max(1, n - 4)
                val o = view.at(G(pts[i * 3], pts[i * 3 + 1]), pts[i * 3 + 2])
                val ang = ((b * 73 + 19) % 360) * DEG
                val len = cell * (0.5f + (b % 3) * 0.25f)
                val mid = Offset(o.x + cos(ang) * len * 0.5f + sin(ang) * len * 0.2f, o.y + sin(ang) * len * 0.5f)
                val end = Offset(o.x + cos(ang) * len, o.y + sin(ang) * len)
                val fork = Path().apply { moveTo(o.x, o.y); lineTo(mid.x, mid.y); lineTo(end.x, end.y) }
                drawPath(fork, d.color.copy(alpha = a * 0.6f), style = Stroke(w * 0.8f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            }
        }
        DK.Shield -> {
            val p = d.follow
            val centreG = p?.pos ?: d.pos
            val base = view.ground(centreG)
            val c = Offset(base.x, base.y - cell * 0.15f)
            val wob = 1f + sin(d.age * 16f) * 0.05f * (1f - k)
            val r = d.r1 * cell * wob * (0.6f + 0.4f * smoothK(k * 4f))
            val a = d.alpha * envelope(k, 0.12f, 0.45f)
            drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.65f to d.color.copy(alpha = a * 0.2f), 1f to d.color.copy(alpha = a * 0.75f), center = c, radius = r), r, c)
            drawCircle(Color.White.copy(alpha = a * 0.8f), r, c, style = Stroke(cell * 0.035f))
            drawArc(Color.White.copy(alpha = a * 0.7f), 200f, 60f, false, Offset(c.x - r * 0.75f, c.y - r * 0.75f), Size(r * 1.5f, r * 1.5f), style = Stroke(cell * 0.05f, cap = StrokeCap.Round))
        }
        DK.Sword -> drawSword(d, view)
        DK.Spot, DK.Slash, DK.Medal -> {}
    }
}

private fun smoothK(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

/** The sword hangs in screen space above the victim; data = [angle, scale, alpha, trailFromAngle]. */
private fun DrawScope.drawSword(d: Decal, view: View) {
    val data = d.data ?: return
    val cell = view.cell
    val angle = data[0]; val scale = data[1]; val alpha = data[2]; val trailFrom = data[3]
    if (alpha <= 0.01f) return
    val g = view.ground(d.pos)
    val hilt = Offset(g.x + cell * 0.2f, g.y - cell * 2.2f)
    val len = cell * 2.9f * scale
    // The blade sweeps along a "/" diagonal on screen; angle 0 points straight down at the victim.
    fun dirFor(deg: Float): Offset {
        val r = deg * DEG
        val sx = 0.82f; val sy = -0.57f
        return Offset(sin(r) * sx, cos(r) + sin(r) * sy).let { val l = it.getDistance(); Offset(it.x / l, it.y / l) }
    }
    // Slash trail: a fan between where the blade was and where it is.
    if (abs(trailFrom - angle) > 2f) {
        val fan = Path().apply {
            moveTo(hilt.x, hilt.y)
            val steps = 12
            for (i in 0..steps) {
                val dd = dirFor(trailFrom + (angle - trailFrom) * i / steps)
                lineTo(hilt.x + dd.x * len, hilt.y + dd.y * len)
            }
            close()
        }
        drawPath(fan, Brush.radialGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 0.85f to Color(0.6f, 0.85f, 1f, 0.55f * alpha), 1f to Color.White.copy(alpha = 0.9f * alpha), center = hilt, radius = len), blendMode = BlendMode.Plus)
    }
    val dir = dirFor(angle)
    val nrm = Offset(-dir.y, dir.x)
    val w = cell * 0.13f * scale
    val tip = Offset(hilt.x + dir.x * len, hilt.y + dir.y * len)
    val bladeStart = Offset(hilt.x + dir.x * cell * 0.15f * scale, hilt.y + dir.y * cell * 0.15f * scale)
    val blade = Path().apply {
        moveTo(bladeStart.x + nrm.x * w, bladeStart.y + nrm.y * w)
        lineTo(tip.x - dir.x * w * 2f + nrm.x * w, tip.y - dir.y * w * 2f + nrm.y * w)
        lineTo(tip.x, tip.y)
        lineTo(tip.x - dir.x * w * 2f - nrm.x * w, tip.y - dir.y * w * 2f - nrm.y * w)
        lineTo(bladeStart.x - nrm.x * w, bladeStart.y - nrm.y * w)
        close()
    }
    drawPath(blade, Brush.linearGradient(listOf(Color(0.95f, 0.97f, 1f), Color(0.62f, 0.68f, 0.78f)), Offset(bladeStart.x + nrm.x * w, bladeStart.y + nrm.y * w), Offset(bladeStart.x - nrm.x * w, bladeStart.y - nrm.y * w)), alpha = alpha)
    drawLine(Color.White.copy(alpha = alpha), bladeStart, tip, w * 0.25f)
    drawPath(blade, Color(0.25f, 0.28f, 0.35f).copy(alpha = alpha), style = Stroke(max(1.2f, cell * 0.025f)))
    drawPath(blade, Color(0.6f, 0.85f, 1f).copy(alpha = 0.3f * alpha), style = Stroke(cell * 0.12f), blendMode = BlendMode.Plus)
    // Guard, grip and pommel.
    val gw = cell * 0.36f * scale
    drawLine(Color(0.55f, 0.36f, 0.08f).copy(alpha = alpha), Offset(hilt.x + nrm.x * gw, hilt.y + nrm.y * gw), Offset(hilt.x - nrm.x * gw, hilt.y - nrm.y * gw), cell * 0.12f * scale, StrokeCap.Round)
    drawLine(Color(1f, 0.8f, 0.32f).copy(alpha = alpha), Offset(hilt.x + nrm.x * gw, hilt.y + nrm.y * gw), Offset(hilt.x - nrm.x * gw, hilt.y - nrm.y * gw), cell * 0.07f * scale, StrokeCap.Round)
    val pommel = Offset(hilt.x - dir.x * cell * 0.5f * scale, hilt.y - dir.y * cell * 0.5f * scale)
    drawLine(Color(0.32f, 0.14f, 0.08f).copy(alpha = alpha), hilt, pommel, cell * 0.1f * scale, StrokeCap.Round)
    drawCircle(Color(1f, 0.8f, 0.32f).copy(alpha = alpha), cell * 0.09f * scale, pommel)
}

// ====================================================================== medals and the Loser sign

private fun DrawScope.drawAward(a: AwardSprite, view: View, fonts: SceneFonts) {
    val cell = view.cell
    val g = view.ground(a.at)
    val t = a.age
    // Drops in from above with a bounce, then sways gently.
    val drop = if (t < 0.45f) { val k = t / 0.45f; 1f - k * k } else 0f
    val bounce = if (t in 0.45f..0.85f) sin((t - 0.45f) / 0.4f * PI.toFloat()) * 0.12f else 0f
    val pop = 1f + bounce
    val sway = sin(t * 2.2f) * 4f
    val c = Offset(g.x, g.y - drop * cell * 6f)
    val alpha = min(1f, t * 5f)
    if (a.loser) {
        withTransform({ rotate(sway * 1.6f - 6f, Offset(c.x, c.y - cell * 1.6f)); scale(pop, pop, c) }) {
            // Rope and nail.
            drawLine(Color(0.45f, 0.32f, 0.18f).copy(alpha = alpha), Offset(c.x - cell * (if (a.soft) 1.35f else 1.1f), c.y - cell * 0.35f), Offset(c.x, c.y - cell * 1.5f), cell * 0.06f)
            drawLine(Color(0.45f, 0.32f, 0.18f).copy(alpha = alpha), Offset(c.x + cell * (if (a.soft) 1.35f else 1.1f), c.y - cell * 0.35f), Offset(c.x, c.y - cell * 1.5f), cell * 0.06f)
            drawCircle(Color(0.3f, 0.3f, 0.32f).copy(alpha = alpha), cell * 0.1f, Offset(c.x, c.y - cell * 1.5f))
            val w = cell * (if (a.soft) 3.2f else 2.6f); val h = cell * 1.05f
            val r = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
            softShadow(r, cell * 0.15f, cell * 0.3f, 0.5f * alpha, cell * 0.05f, cell * 0.12f)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0.78f, 0.55f, 0.32f), Color(0.58f, 0.38f, 0.2f)), r.top, r.bottom), r.topLeft, r.size, CornerRadius(cell * 0.15f), alpha = alpha)
            for (i in 1..2) drawLine(Color(0.45f, 0.28f, 0.14f).copy(alpha = 0.6f * alpha), Offset(r.left + cell * 0.1f, r.top + h * i / 3), Offset(r.right - cell * 0.1f, r.top + h * i / 3), cell * 0.025f)
            drawRoundRect(Color(0.3f, 0.18f, 0.08f).copy(alpha = alpha), r.topLeft, r.size, CornerRadius(cell * 0.15f), style = Stroke(cell * 0.06f))
            val text = fonts.measurer.measure(if (a.soft) "NEXT ROUND!" else "LOSER", TextStyle(color = Color(0.98f, 0.95f, 0.88f), fontFamily = fonts.display, fontSize = (cell * (if (a.soft) 0.45f else 0.62f) / density).sp, fontWeight = FontWeight.Bold))
            drawText(text, topLeft = Offset(c.x - text.size.width / 2f + cell * 0.03f, c.y - text.size.height / 2f + cell * 0.04f), color = Color(0.25f, 0.13f, 0.05f), alpha = alpha)
            drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f), alpha = alpha)
        }
        return
    }
    val (hi, mid, lo) = when (a.rank) {
        1 -> Triple(Color(1f, 0.93f, 0.55f), Color(0.96f, 0.74f, 0.16f), Color(0.66f, 0.45f, 0.05f))
        2 -> Triple(Color(0.98f, 0.98f, 1f), Color(0.76f, 0.79f, 0.85f), Color(0.48f, 0.5f, 0.56f))
        else -> Triple(Color(1f, 0.8f, 0.6f), Color(0.8f, 0.5f, 0.28f), Color(0.5f, 0.28f, 0.12f))
    }
    withTransform({ rotate(sway, Offset(c.x, c.y - cell * 1.4f)); scale(pop, pop, c) }) {
        // Ribbon in the winner's colour.
        val rib = Path().apply {
            moveTo(c.x - cell * 0.75f, c.y - cell * 1.55f); lineTo(c.x - cell * 0.25f, c.y - cell * 1.55f); lineTo(c.x + cell * 0.18f, c.y - cell * 0.3f); lineTo(c.x - cell * 0.32f, c.y - cell * 0.3f); close()
            moveTo(c.x + cell * 0.75f, c.y - cell * 1.55f); lineTo(c.x + cell * 0.25f, c.y - cell * 1.55f); lineTo(c.x - cell * 0.18f, c.y - cell * 0.3f); lineTo(c.x + cell * 0.32f, c.y - cell * 0.3f); close()
        }
        drawPath(rib, Brush.horizontalGradient(listOf(lerp(a.color, Color.Black, 0.25f), a.color, lerp(a.color, Color.Black, 0.25f)), c.x - cell * 0.75f, c.x + cell * 0.75f), alpha = alpha)
        drawPath(rib, Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(cell * 0.03f))
        val r = cell * 0.95f
        softShadow(Rect(c.x - r, c.y - r, c.x + r, c.y + r), r, cell * 0.3f, 0.45f * alpha, cell * 0.05f, cell * 0.15f)
        drawCircle(lo.copy(alpha = alpha), r, c)
        drawCircle(Brush.linearGradient(listOf(hi, mid, lo), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r * 0.9f, c, alpha = alpha)
        drawCircle(lo.copy(alpha = alpha), r * 0.72f, c, style = Stroke(cell * 0.05f))
        // A slow shine sweeping across.
        val sweep = ((t * 0.5f) % 1.6f) - 0.3f
        clipPath(Path().apply { addOval(Rect(c.x - r * 0.9f, c.y - r * 0.9f, c.x + r * 0.9f, c.y + r * 0.9f)) }) {
            rotate(30f, c) { drawRect(Color.White.copy(alpha = 0.35f * alpha), Offset(c.x - r + sweep * 2 * r, c.y - r * 1.5f), Size(r * 0.28f, r * 3f)) }
        }
        val text = fonts.measurer.measure("${a.rank}", TextStyle(color = Color.White, fontFamily = fonts.display, fontSize = (cell * 0.95f / density).sp, fontWeight = FontWeight.Bold))
        drawText(text, topLeft = Offset(c.x - text.size.width / 2f + cell * 0.04f, c.y - text.size.height / 2f + cell * 0.05f), color = lo, alpha = alpha)
        drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f), alpha = alpha)
        // Twinkles.
        for (i in 0 until 3) {
            val an = t * 1.3f + i * 2.1f
            val s = cell * (0.12f + 0.06f * sin(t * 5f + i))
            val o = Offset(c.x + cos(an) * r * 1.15f, c.y + sin(an) * r * 1.15f)
            drawPath(starPath(o.x, o.y, s, s * 0.25f, 4), Color.White.copy(alpha = 0.9f * alpha), blendMode = BlendMode.Plus)
        }
    }
}

// ====================================================================== the table behind the board

fun DrawScope.drawBackdrop(st: BoardStyle, time: Float) {
    val w = size.width; val h = size.height
    if (st.skyBackdrop) {
        drawRect(Brush.verticalGradient(listOf(st.backCentre, st.backEdge), 0f, h))
        val sun = Offset(w * 0.82f, h * 0.07f)
        drawCircle(Brush.radialGradient(listOf(st.backPattern.copy(alpha = 0.55f), st.backPattern.copy(alpha = 0.12f), Color.Transparent), sun, w * 0.55f), w * 0.55f, sun)
        drawCircle(Color(1f, 0.97f, 0.88f), w * 0.07f, sun)
        // Soft clouds drifting slowly.
        for (i in 0 until 5) {
            val x = ((i * 0.27f + time * 0.004f * (1 + i % 2)) % 1.3f - 0.15f) * w
            val y = h * (0.12f + 0.17f * i)
            for (j in 0 until 4) {
                val o = Offset(x + j * w * 0.07f, y + (if (j % 2 == 0) 0f else -h * 0.012f))
                val r = w * (0.07f + 0.02f * (j % 3))
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), o, r), r, o)
            }
        }
    } else {
        val c = Offset(w / 2, h * 0.45f)
        drawRect(Brush.radialGradient(listOf(st.backCentre, st.backEdge), c, max(w, h) * 0.75f))
        // Faint diamond lattice (busy backgrounds hurt older readers, so it stays very quiet).
        if (st.backPattern.alpha > 0.001f) {
            val step = w / 9f
            var i = -h / step - 2
            while (i < w / step + 2) {
                val x0 = i * step
                drawLine(st.backPattern, Offset(x0, 0f), Offset(x0 + h, h), 1.5f)
                drawLine(st.backPattern, Offset(x0 + h, 0f), Offset(x0, h), 1.5f)
                i += 1f
            }
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), c, max(w, h) * 0.8f))
    }
}
