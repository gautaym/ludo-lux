package lux.core

/** A square on the 15x15 grid. [col] = x, [row] = y (row 0 at the top). */
data class Cell(val col: Int, val row: Int)

/**
 * Ludo board geometry, independent of any rendering. 15x15 grid.
 * Colours run clockwise: 0 Red (top-left), 1 Green (top-right), 2 Yellow (bottom-right), 3 Blue (bottom-left).
 *
 * A piece's progress: 0 = in base, 1..51 = shared track (1 is the colour's own start square),
 * 52..56 = private home lane, 57 = finished.
 */
object Board {
    const val COLORS = 4
    const val PIECES = 4
    const val TRACK_LENGTH = 52
    const val SEAT_SPACING = 13
    const val LAST_TRACK = 51
    const val HOME_LANE_START = 52
    const val FINISH = 57

    private val track: List<Cell> = buildList {
        for (c in 1..5) add(Cell(c, 6))
        for (r in 5 downTo 0) add(Cell(6, r))
        add(Cell(7, 0)); add(Cell(8, 0))
        for (r in 1..5) add(Cell(8, r))
        for (c in 9..14) add(Cell(c, 6))
        add(Cell(14, 7)); add(Cell(14, 8))
        for (c in 13 downTo 9) add(Cell(c, 8))
        for (r in 9..14) add(Cell(8, r))
        add(Cell(7, 14)); add(Cell(6, 14))
        for (r in 13 downTo 9) add(Cell(6, r))
        for (c in 5 downTo 0) add(Cell(c, 8))
        add(Cell(0, 7)); add(Cell(0, 6))
    }

    fun isOnTrack(progress: Int) = progress in 1..LAST_TRACK

    /** Index 0..51 of the shared-track square a piece of [color] occupies at [progress] (1..51). */
    fun globalIndex(color: Int, progress: Int) = (color * SEAT_SPACING + progress - 1) % TRACK_LENGTH

    /** Safe squares are every colour's start square and the star square 8 steps after it. */
    fun isSafeSquare(globalIndex: Int) = globalIndex % SEAT_SPACING == 0 || globalIndex % SEAT_SPACING == 8

    fun trackCell(globalIndex: Int) = track[globalIndex]

    fun homeLaneCell(color: Int, progress: Int): Cell {
        // Red's lane is row 7, columns 1..5; the other colours are 90-degree rotations of it.
        var c = Cell(progress - 51, 7)
        repeat(color) { c = Cell(14 - c.row, c.col) }
        return c
    }

    /** Cell for any progress 1..56; finished pieces are placed by the presentation layer. */
    fun cellFor(color: Int, progress: Int): Cell =
        if (progress <= LAST_TRACK) trackCell(globalIndex(color, progress)) else homeLaneCell(color, progress)
}
