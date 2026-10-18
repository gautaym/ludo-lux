package lux.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Where die rolls come from. [next] may look at the position (balanced dice); fair dice ignore it. */
fun interface DiceSource {
    fun next(state: GameState, seat: Int): Int
}

/** A fair die. Seed it for tests and replays. */
class FairDice(private val rng: Random = Random.Default) : DiceSource {
    constructor(seed: Int) : this(Random(seed))
    override fun next(state: GameState, seat: Int) = rng.nextInt(1, 7)
}

/** Scripted dice for tests. */
class ScriptedDice(vararg rolls: Int) : DiceSource {
    private val values = ArrayDeque(rolls.toList())
    override fun next(state: GameState, seat: Int) = values.removeFirst()
}

/**
 * "Balanced" dice: every roll is still random, but the odds are shaped so the game stays lively.
 * It only decides the number on the die - how pieces move is entirely up to the player (or the CPU).
 * The same rules apply to every seat, human or computer.
 *
 * 1. Sixes come in time. The chance of a six starts a little below 1-in-6 right after a six and grows with every
 *    roll without one (10%, 13%, 18%, 23%, 30%, 37%, 46% ... capped at 70%).
 * 2. Nobody stays stuck. Each roll that left the seat with no legal move makes the numbers that WOULD move
 *    something more likely (x1.5, x2, x2.5 ... up to x4).
 * 3. It looks random. The same number three times running is rare, a six is unlikely right after a six, and a
 *    third six in a row (which would forfeit the turn) is rare.
 * 4. A gentle catch-up. A seat far behind the leader gets slightly better six odds (at most +35%).
 * 5. A little drama. Numbers that allow a capture (x1.15) or bring a piece exactly home (x1.25) are a bit more likely.
 */
class BalancedDice(private val rng: Random = Random.Default) : DiceSource {
    constructor(seed: Int) : this(Random(seed))

    private val sinceSix = IntArray(Board.COLORS)
    private val wasted = IntArray(Board.COLORS)
    private var last1 = 0
    private var last2 = 0   // the last two numbers shown on the table, whoever rolled them
    private val w = DoubleArray(7)

    override fun next(state: GameState, seat: Int): Int {
        val v = pick(state, seat)
        sinceSix[seat] = if (v == 6) 0 else sinceSix[seat] + 1
        wasted[seat] = if (Engine.hasLegalMove(state, seat, v)) 0 else wasted[seat] + 1
        last2 = last1
        last1 = v
        return v
    }

    /** The probability of each face (index 1..6) this seat would roll now. For tests and tuning. */
    fun odds(s: GameState, seat: Int): DoubleArray {
        weigh(s, seat)
        val sum = (1..6).sumOf { w[it] }
        return DoubleArray(7) { if (it == 0) 0.0 else w[it] / sum }
    }

    private fun pick(s: GameState, seat: Int): Int {
        weigh(s, seat)
        var x = rng.nextDouble() * (1..6).sumOf { w[it] }
        for (v in 1..6) { x -= w[v]; if (x < 0) return v }
        return 6
    }

    private fun weigh(s: GameState, seat: Int) {
        val stuck = wasted[seat]
        val help = 1 + 0.5 * min(stuck, 6)   // x1 .. x4

        // Faces 1-5: equal, except for repeats, usefulness when stuck, and a little drama.
        var rest = 0.0
        for (v in 1..5) {
            var x = 1.0
            if (v == last1) x *= if (v == last2) 0.25 else 0.75
            rest += x
            val f = look(s, seat, v)
            if (stuck > 0 && f.moves) x *= help
            x *= drama(f)
            w[v] = x
        }

        // Six: a chance that grows the longer the seat has waited.
        var p6 = sixChance(sinceSix[seat]) * (1 + 0.35 * behind(s, seat))
        val six = look(s, seat, 6)
        if (!six.moves) p6 = min(p6, 1.0 / 6)
        val chain = s.consecutiveSixes > 0
        if (s.rules.sixPenaltyCount > 0 && s.consecutiveSixes >= s.rules.sixPenaltyCount - 1) p6 = min(p6, 0.04)
        else if (chain) p6 = min(p6, SIX_FLOOR)
        p6 = min(p6, SIX_CAP)
        w[6] = p6 / (1 - p6) * rest
        if (!chain) {
            if (stuck > 0 && six.moves) w[6] *= help
            w[6] *= drama(six)
        }
    }

    private class Facts(val moves: Boolean, val captures: Boolean, val finishes: Boolean)

    /** What rolling [v] would make possible (not what the player will choose). */
    private fun look(s: GameState, seat: Int, v: Int): Facts {
        val moves = Engine.legalMoves(s, seat, v)
        val color = s.seatColor[seat]
        var captures = false
        var finishes = false
        for (m in moves) {
            if (m.to == Board.FINISH) finishes = true
            if (!Board.isOnTrack(m.to)) continue
            val g = Board.globalIndex(color, m.to)
            if (Board.isSafeSquare(g)) continue
            if ((0 until s.seatCount).any { o -> o != seat && s.isActive(o) && Engine.capturableAt(s, o, g) }) captures = true
        }
        return Facts(moves.isNotEmpty(), captures, finishes)
    }

    companion object {
        const val SIX_FLOOR = 0.10
        const val SIX_CAP = 0.70
        const val CAPTURE_BOOST = 1.15
        const val HOME_BOOST = 1.25

        /** Chance of a six after [rollsSinceSix] rolls without one (before other adjustments). */
        fun sixChance(rollsSinceSix: Int): Double {
            val g = rollsSinceSix.toDouble()
            return min(SIX_CAP, SIX_FLOOR + 0.03 * g + 0.005 * g * g)
        }

        private fun drama(f: Facts) = (if (f.captures) CAPTURE_BOOST else 1.0) * (if (f.finishes) HOME_BOOST else 1.0)

        /** 0 when level with (or ahead of) the leader, 1 when two whole laps of progress behind. */
        private fun behind(s: GameState, seat: Int): Double {
            var mine = 0
            var best = 0
            for (o in 0 until s.seatCount) {
                if (!s.isActive(o)) continue
                val t = s.progress[o].sum()
                if (o == seat) mine = t
                if (t > best) best = t
            }
            return min(1.0, max(0, best - mine) / (2.0 * Board.FINISH))
        }
    }
}
