package lux.game

import lux.core.Board
import lux.core.Cell

/** A point on the board in grid units: x = column, y = row (row 0 at the top), both 0..15. */
data class G(val x: Float, val y: Float) {
    operator fun plus(o: G) = G(x + o.x, y + o.y)
    operator fun minus(o: G) = G(x - o.x, y - o.y)
    operator fun times(k: Float) = G(x * k, y * k)
    fun length() = kotlin.math.sqrt(x * x + y * y)
    fun normalized(): G { val l = length(); return if (l < 1e-5f) G(0f, 0f) else G(x / l, y / l) }

    companion object { val Centre = G(7.5f, 7.5f) }
}

fun lerp(a: G, b: G, t: Float) = G(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Maps the engine's board (grid cells, progress values) to grid positions. Ported from the Unity BoardGeometry. */
object Geo {
    /** Turns a point 90 degrees clockwise about the board centre [times] times (colour k's corner is k turns from red's). */
    fun rotate(p: G, times: Int): G {
        var q = p
        repeat(((times % 4) + 4) % 4) { q = G(15f - q.y, q.x) }
        return q
    }

    fun rotateVec(v: G, times: Int): G {
        var q = v
        repeat(((times % 4) + 4) % 4) { q = G(-q.y, q.x) }
        return q
    }

    fun cellCentre(c: Cell) = G(c.col + 0.5f, c.row + 0.5f)

    private val baseSpots = listOf(G(1.9f, 1.9f), G(4.1f, 1.9f), G(1.9f, 4.1f), G(4.1f, 4.1f))

    fun baseSpot(color: Int, piece: Int) = rotate(baseSpots[piece], color)
    fun baseCentre(color: Int) = rotate(G(3f, 3f), color)

    /** Resting place of a finished piece inside its colour's centre triangle. */
    fun finishSpot(color: Int, slot: Int) = G.Centre + rotateVec(G(-0.85f, (slot - 1.5f) * 0.34f), color)

    /** Position of a piece at [progress] (0 = base spot, 57 = finish slot). */
    fun piece(color: Int, piece: Int, progress: Int, finishSlot: Int = 0): G = when {
        progress == 0 -> baseSpot(color, piece)
        progress >= Board.FINISH -> finishSpot(color, finishSlot)
        else -> cellCentre(Board.cellFor(color, progress))
    }
}
