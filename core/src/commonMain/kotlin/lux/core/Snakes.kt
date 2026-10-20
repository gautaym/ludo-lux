package lux.core

import kotlin.random.Random

/**
 * Snake & Ladders: 100 squares, climbed in a zig-zag from the bottom-left. Land at the foot of a ladder and climb it;
 * land on a snake's head and slide down to its tail. First to square 100 wins; play goes on until everyone but one
 * has finished, so every place (and every series point) is decided. No choices are made by the players: the dice
 * decide everything, which makes it the gentlest game on the platform.
 */
object SnakesBoard {
    const val LAST = 100

    /** Foot -> top. An original layout: eight ladders spread over the board, the longest in the middle rows. */
    val ladders: Map<Int, Int> = mapOf(3 to 22, 8 to 30, 20 to 41, 28 to 55, 36 to 57, 51 to 72, 63 to 81, 71 to 92)

    /** Head -> tail. Eight snakes; the long ones wait near the top, so a game is never over until it's over. */
    val snakes: Map<Int, Int> = mapOf(16 to 6, 47 to 26, 49 to 11, 62 to 19, 64 to 43, 87 to 24, 95 to 56, 98 to 78)

    /** Where landing on [cell] really takes you (a ladder's top, a snake's tail), or null for a plain square. */
    fun jump(cell: Int): Int? = ladders[cell] ?: snakes[cell]

    /** Column (0 = left) and row (0 = bottom) of [cell] 1..100, boustrophedon: row 0 runs left to right, row 1 back. */
    fun colRow(cell: Int): Pair<Int, Int> {
        val i = cell - 1
        val row = i / 10
        val col = if (row % 2 == 0) i % 10 else 9 - i % 10
        return col to row
    }
}

/** House rules (the common Indian ones by default). */
data class SnakesRules(
    /** Square 100 needs the exact number; a bigger roll means you wait. Off: you bounce back by the extra. */
    val exactFinish: Boolean = true,
    /** A six gives another roll (three sixes in a row and the turn passes anyway). */
    val sixAgain: Boolean = true,
    /** Off the board until a six (or a one) is rolled. Off by default: everyone starts straight away. */
    val needSixToStart: Boolean = false,
)

enum class SnakesEventType { Rolled, Moved, Waiting, Ladder, Snake, Bounced, Finished, ExtraTurn, ThreeSixes, TurnPassed, MatchEnded }

/** One thing that happened. [from]/[to] are squares (0 = not on the board yet); [value] is the roll or the place. */
data class SnakesEvent(val type: SnakesEventType, val seat: Int, val from: Int = 0, val to: Int = 0, val value: Int = 0)

/** The whole game, small enough to save after every roll. Seats are in colour order, like Ludo's. */
class SnakesState(
    val seatColor: IntArray,
    val rules: SnakesRules = SnakesRules(),
    startSeat: Int = 0,
) {
    val seatCount get() = seatColor.size
    /** Square per seat (0 = not on the board yet, 100 = finished). */
    val pos = IntArray(seatColor.size)
    /** Finishing place per seat (0 = still playing). */
    val rank = IntArray(seatColor.size)
    var current = startSeat
    var sixes = 0
    var lastRoll = 0
    /** Bumped by every roll (a stale or repeated command is refused). */
    var sequence = 0L
    /** Rolls made so far (for statistics). */
    var rolls = 0

    val finishedCount get() = rank.count { it > 0 }
    val isOver get() = finishedCount >= seatCount - 1 && seatCount > 1 || finishedCount == seatCount
    fun isActive(seat: Int) = rank[seat] == 0

    fun copy(): SnakesState = SnakesState(seatColor.copyOf(), rules, current).also {
        pos.copyInto(it.pos); rank.copyInto(it.rank); it.sixes = sixes; it.lastRoll = lastRoll; it.sequence = sequence; it.rolls = rolls
    }

    /** Plain text, so a saved game survives app updates. */
    fun encode(): String = "N1|" + listOf(
        seatColor.joinToString(","), pos.joinToString(","), rank.joinToString(","),
        "$current,$sixes,$lastRoll,$sequence,$rolls",
        "${if (rules.exactFinish) 1 else 0},${if (rules.sixAgain) 1 else 0},${if (rules.needSixToStart) 1 else 0}",
    ).joinToString("|")

    companion object {
        fun decode(text: String): SnakesState? = runCatching {
            val p = text.split('|')
            require(p[0] == "N1")
            fun ints(s: String) = s.split(',').map { it.toInt() }
            val colors = ints(p[1]).toIntArray()
            val r = ints(p[5])
            val st = SnakesState(colors, SnakesRules(r[0] == 1, r[1] == 1, r[2] == 1))
            ints(p[2]).forEachIndexed { i, v -> st.pos[i] = v.coerceIn(0, SnakesBoard.LAST) }
            ints(p[3]).forEachIndexed { i, v -> st.rank[i] = v }
            val m = ints(p[4])
            st.current = m[0]; st.sixes = m[1]; st.lastRoll = m[2]; st.sequence = m[3].toLong(); st.rolls = m[4]
            require(st.current in colors.indices)
            st
        }.getOrNull()
    }
}

/** The rules. Only [roll] changes a game; it never asks anyone anything. */
object SnakesEngine {
    /** Plays one roll of [value] for the current seat and returns what happened, in order. */
    fun roll(s: SnakesState, value: Int): List<SnakesEvent> {
        require(value in 1..6) { "A die shows 1 to 6." }
        check(!s.isOver) { "The game is over." }
        val seat = s.current
        val ev = ArrayList<SnakesEvent>()
        s.sequence++; s.rolls++
        s.lastRoll = value
        ev += SnakesEvent(SnakesEventType.Rolled, seat, value = value)
        val from = s.pos[seat]
        var finished = false
        if (from == 0 && s.rules.needSixToStart && value != 6 && value != 1) {
            ev += SnakesEvent(SnakesEventType.Waiting, seat, from, from, value)
        } else {
            var to = from + value
            if (to > SnakesBoard.LAST) {
                if (s.rules.exactFinish) {
                    ev += SnakesEvent(SnakesEventType.Waiting, seat, from, from, value)
                    to = from
                } else {
                    ev += SnakesEvent(SnakesEventType.Moved, seat, from, SnakesBoard.LAST, value)
                    val back = SnakesBoard.LAST - (to - SnakesBoard.LAST)
                    ev += SnakesEvent(SnakesEventType.Bounced, seat, SnakesBoard.LAST, back)
                    to = back
                }
            } else {
                ev += SnakesEvent(SnakesEventType.Moved, seat, from, to, value)
            }
            if (to != from || ev.last().type == SnakesEventType.Bounced) {
                SnakesBoard.ladders[to]?.let { top -> ev += SnakesEvent(SnakesEventType.Ladder, seat, to, top); to = top }
                    ?: SnakesBoard.snakes[to]?.let { tail -> ev += SnakesEvent(SnakesEventType.Snake, seat, to, tail); to = tail }
            }
            s.pos[seat] = to
            if (to == SnakesBoard.LAST) {
                finished = true
                s.rank[seat] = s.finishedCount + 1
                ev += SnakesEvent(SnakesEventType.Finished, seat, value = s.rank[seat])
                // The last one still playing takes the last place.
                if (s.finishedCount == s.seatCount - 1) {
                    val lastSeat = (0 until s.seatCount).first { s.rank[it] == 0 }
                    s.rank[lastSeat] = s.seatCount
                    ev += SnakesEvent(SnakesEventType.Finished, lastSeat, value = s.seatCount)
                }
            }
        }
        if (s.isOver) {
            ev += SnakesEvent(SnakesEventType.MatchEnded, seat)
            return ev
        }
        if (value == 6 && s.rules.sixAgain && !finished) {
            s.sixes++
            if (s.sixes < 3) { ev += SnakesEvent(SnakesEventType.ExtraTurn, seat); return ev }
            ev += SnakesEvent(SnakesEventType.ThreeSixes, seat)
        }
        s.sixes = 0
        var next = seat
        do { next = (next + 1) % s.seatCount } while (s.rank[next] != 0)
        s.current = next
        ev += SnakesEvent(SnakesEventType.TurnPassed, next)
        return ev
    }
}

/**
 * Snake & Ladders dice: fair, except the same number three times running is made rare (it feels rigged to people
 * even though it's random). Every seat gets exactly the same treatment.
 */
class SnakesDice(private val rng: Random = Random.Default) {
    constructor(seed: Int) : this(Random(seed))
    private var a = 0
    private var b = 0
    private val forced = ArrayDeque<Int>()

    /** Queue specific rolls (tests, the tutorial). */
    fun enqueue(vararg v: Int) { forced.addAll(v.toList()) }

    fun next(): Int {
        var v = forced.removeFirstOrNull() ?: rng.nextInt(1, 7)
        if (forced.isEmpty() && v == a && v == b) v = rng.nextInt(1, 7)
        b = a; a = v
        return v
    }
}
