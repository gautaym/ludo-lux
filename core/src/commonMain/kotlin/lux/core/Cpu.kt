package lux.core

import kotlin.random.Random

enum class CpuLevel { Easy, Medium, Hard }

/**
 * CPU move selection. It only ever picks among the engine's legal moves and never touches the dice, so it
 * plays by exactly the same rules and luck as a human.
 * Easy: mostly random with an occasional obvious capture/finish.
 * Medium: sound heuristics with noise.
 * Hard: the same heuristics without noise, plus a lookahead on how exposed each option leaves the board.
 */
object Cpu {
    fun chooseMove(s: GameState, seat: Int, level: CpuLevel, rng: Random): Int {
        val legal = Engine.legalMoves(s, seat, s.lastRoll, collapseBase = true)
        check(legal.isNotEmpty()) { "No legal moves." }
        if (legal.size == 1) return legal[0].piece
        return when (level) {
            // 35% of the time take the best-looking move, otherwise pick at random.
            CpuLevel.Easy -> if (rng.nextDouble() < 0.35) best(s, seat, legal, 0.0, rng, false) else legal[rng.nextInt(legal.size)].piece
            CpuLevel.Medium -> best(s, seat, legal, 6.0, rng, false)
            CpuLevel.Hard -> best(s, seat, legal, 0.0, rng, true)
        }
    }

    private fun best(s: GameState, seat: Int, legal: List<Move>, noise: Double, rng: Random, deep: Boolean): Int {
        var best = legal[0].piece
        var bestScore = Double.NEGATIVE_INFINITY
        for (m in legal) {
            val score = score(s, seat, m, deep) + if (noise > 0) rng.nextDouble() * noise else 0.0
            if (score > bestScore) { bestScore = score; best = m.piece }
        }
        return best
    }

    private fun score(s: GameState, seat: Int, m: Move, deep: Boolean): Double {
        var score = 0.0
        val color = s.seatColor[seat]
        // Danger the piece is in now vs. after the move (only matters on the shared track).
        val riskBefore = if (Board.isOnTrack(m.from)) risk(s, seat, m.from) else 0.0

        val after = s.copy()
        val res = Engine.move(after, seat, m.piece)
        var finished = false
        for (e in res.events) {
            if (e.type == EventType.PieceCaptured) score += 55 + e.from * 1.2 // worth more the further the victim had got
            else if (e.type == EventType.PieceReachedHome) finished = true
        }
        if (finished) score += 70
        if (m.from == 0) score += 32                     // getting a piece out is almost always good
        score += (m.to - m.from) * 0.6                   // raw progress
        if (m.to >= Board.HOME_LANE_START && m.to < Board.FINISH) score += 22 // safe in the home lane

        if (Board.isOnTrack(m.to)) {
            val g = Board.globalIndex(color, m.to)
            if (Board.isSafeSquare(g)) score += 18
            else if (Engine.countAt(after, seat, g) >= 2 && s.rules.stacks != StackRule.None) score += 14
            else if ((0 until s.seatCount).any { o -> o != seat && after.isActive(o) && Engine.countAt(after, o, g) >= 2 } && s.rules.stacks == StackRule.StackSafe)
                score -= risk(after, seat, m.to) * (if (deep) 70 else 45) * (1 + m.to / 51.0) * 0.5 // sharing a pair's square: still exposed to others
            else score -= risk(after, seat, m.to) * (if (deep) 70 else 45) * (1 + m.to / 51.0)
        }
        // Reward escaping a threatened square.
        if (riskBefore > 0 && !(Board.isOnTrack(m.from) && Board.isSafeSquare(Board.globalIndex(color, m.from))))
            score += riskBefore * if (deep) 40 else 25

        if (deep) {
            // Total exposure of everything I own after this move; discourages leaving several pieces hanging.
            var exposure = 0.0
            for (p in 0 until Board.PIECES) {
                val pr = after.progress[seat][p]
                if (Board.isOnTrack(pr) && p != m.piece) exposure += risk(after, seat, pr) * (1 + pr / 51.0)
            }
            score -= exposure * 12
        }
        return score
    }

    /**
     * Rough probability (0..1) that one of the opponents' pieces can reach the square my piece stands on
     * with a single roll of 1..6. Safe squares and stacks are handled by the caller.
     */
    private fun risk(s: GameState, seat: Int, progress: Int): Double {
        val g = Board.globalIndex(s.seatColor[seat], progress)
        if (Board.isSafeSquare(g)) return 0.0
        var reach = 0 // bit n = roll n lets someone hit me
        for (o in 0 until s.seatCount) {
            if (o == seat || !s.isActive(o)) continue
            for (pr in s.progress[o]) {
                if (!Board.isOnTrack(pr)) continue
                for (d in 1..6) {
                    val to = pr + d
                    if (to > Board.LAST_TRACK) break
                    if (Board.globalIndex(s.seatColor[o], to) == g) reach = reach or (1 shl d)
                }
            }
        }
        return reach.countOneBits() / 6.0
    }
}
