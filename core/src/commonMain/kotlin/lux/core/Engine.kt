package lux.core

/**
 * The rules of Ludo. Pure, deterministic and free of rendering/network concerns.
 * Every mutating call validates first and leaves the state untouched when it rejects.
 */
object Engine {
    fun create(setup: MatchSetup): GameState {
        setup.validate()?.let { throw IllegalArgumentException(it) }
        return GameState(setup.colors.size, setup.colors.toIntArray(), setup.rules).also { it.current = setup.startSeat }
    }

    // ---------------- legal moves ----------------

    /**
     * The legal moves for [seat] after rolling [roll].
     * With [collapseBase], interchangeable pieces still in base are listed once.
     */
    fun legalMoves(s: GameState, seat: Int, roll: Int, collapseBase: Boolean = false): List<Move> {
        val moves = ArrayList<Move>(4)
        var baseListed = false
        for (piece in 0 until Board.PIECES) {
            val from = s.progress[seat][piece]
            if (from == Board.FINISH) continue
            val to: Int
            if (from == 0) {
                if (!s.rules.entersOn(roll)) continue
                if (collapseBase && baseListed) continue
                to = 1
            } else {
                to = from + roll
                if (to > Board.FINISH) continue // exact roll needed to finish
            }
            if (blocked(s, seat, from, to)) continue
            if (from == 0) baseListed = true
            moves.add(Move(piece, from, to))
        }
        return moves
    }

    fun hasLegalMove(s: GameState, seat: Int, roll: Int) = legalMoves(s, seat, roll).isNotEmpty()

    private fun blocked(s: GameState, seat: Int, from: Int, to: Int): Boolean {
        val color = s.seatColor[seat]
        return when (s.rules.stacks) {
            // Nothing may land on or pass an opponent stack.
            StackRule.Blockade -> (from + 1..minOf(to, Board.LAST_TRACK)).any { opponentStackAt(s, seat, Board.globalIndex(color, it)) }
            // A pair can be passed and landed on; it just cannot be captured (see captureAt).
            StackRule.StackSafe, StackRule.None -> false
        }
    }

    private fun opponentStackAt(s: GameState, seat: Int, g: Int) =
        (0 until s.seatCount).any { o -> o != seat && s.isActive(o) && countAt(s, o, g) >= 2 }

    /**
     * True if [seat]'s pieces on shared-track square [g] would be captured by an opponent landing there: there are some,
     * the square is not safe, and (under the pair rule) they are a single piece rather than a safe pair.
     */
    fun capturableAt(s: GameState, seat: Int, g: Int): Boolean {
        if (Board.isSafeSquare(g)) return false
        val n = countAt(s, seat, g)
        return n == 1 || (n > 1 && s.rules.stacks == StackRule.None)
    }

    /** Number of [seat]'s pieces on shared-track square [g]. */
    fun countAt(s: GameState, seat: Int, g: Int): Int {
        val color = s.seatColor[seat]
        return s.progress[seat].count { Board.isOnTrack(it) && Board.globalIndex(color, it) == g }
    }

    /** True when the lucky-six rule entitles [seat] to a six on its next roll. */
    fun luckySixDue(s: GameState, seat: Int) =
        s.rules.sixPityTurns > 0 && allInBase(s, seat) && s.drought[seat] >= s.rules.sixPityTurns

    private fun allInBase(s: GameState, seat: Int) = s.progress[seat].all { it == 0 || it == Board.FINISH }

    // ---------------- commands ----------------

    /**
     * Applies a roll of [value]. The value must come from a trusted source:
     * [MatchSession] draws it on the authority; clients never supply it.
     */
    fun roll(s: GameState, seat: Int, value: Int, lucky: Boolean = false): CommandResult {
        validateTurn(s, seat, Phase.WaitingForRoll).let { if (it != RejectReason.None) return CommandResult.reject(it, s.sequence) }
        if (value !in 1..6) return CommandResult.reject(RejectReason.InvalidCommand, s.sequence)

        val res = CommandResult(true)
        s.sequence++
        s.lastRoll = value
        s.consecutiveSixes = if (value == 6) s.consecutiveSixes + 1 else 0
        if (value == 6 || !allInBase(s, seat)) s.drought[seat] = 0
        else if (s.drought[seat] < 255) s.drought[seat]++
        if (lucky) res.events += GameEvent(EventType.LuckySix, seat = seat, value = 6)
        res.events += GameEvent(EventType.Rolled, seat = seat, value = value)

        if (value == 6 && s.rules.sixPenaltyCount > 0 && s.consecutiveSixes >= s.rules.sixPenaltyCount) {
            res.events += GameEvent(EventType.SixPenalty, seat = seat, value = s.consecutiveSixes)
            passTurn(s, res)
        } else if (!hasLegalMove(s, seat, value)) {
            res.events += GameEvent(EventType.NoLegalMoves, seat = seat, value = value)
            passTurn(s, res)
        } else {
            s.phase = Phase.WaitingForMove
        }
        res.sequence = s.sequence
        return res
    }

    fun move(s: GameState, seat: Int, piece: Int): CommandResult {
        validateTurn(s, seat, Phase.WaitingForMove).let { if (it != RejectReason.None) return CommandResult.reject(it, s.sequence) }
        if (piece !in 0 until Board.PIECES) return CommandResult.reject(RejectReason.InvalidCommand, s.sequence)
        val mv = legalMoves(s, seat, s.lastRoll).firstOrNull { it.piece == piece }
            ?: return CommandResult.reject(RejectReason.IllegalMove, s.sequence)

        val res = CommandResult(true)
        s.sequence++
        s.progress[seat][piece] = mv.to
        res.events += GameEvent(
            if (mv.from == 0) EventType.PieceEntered else EventType.PieceMoved,
            seat = seat, piece = piece, from = mv.from, to = mv.to, value = s.lastRoll,
        )

        var captured = false
        val reachedHome = mv.to == Board.FINISH
        if (Board.isOnTrack(mv.to)) {
            val g = Board.globalIndex(s.seatColor[seat], mv.to)
            if (!Board.isSafeSquare(g)) captured = captureAt(s, seat, piece, g, res)
        }
        if (reachedHome) res.events += GameEvent(EventType.PieceReachedHome, seat = seat, piece = piece, from = mv.from, to = mv.to)

        if (s.progress[seat].all { it == Board.FINISH }) {
            s.rank[seat] = ++s.finishedCount
            res.events += GameEvent(EventType.SeatFinished, seat = seat, value = s.rank[seat])
            if (s.rules.endOnFirstWinner || s.activeSeatCount <= 1) {
                finishMatch(s, res); res.sequence = s.sequence; return res
            }
            passTurn(s, res)
        } else {
            val reason = when {
                s.lastRoll == 6 && s.rules.sixGrantsExtraTurn -> 1
                captured && s.rules.captureGrantsExtraTurn -> 2
                reachedHome && s.rules.finishGrantsExtraTurn -> 3
                else -> 0
            }
            if (reason != 0) {
                res.events += GameEvent(EventType.ExtraTurn, seat = seat, value = reason)
                s.phase = Phase.WaitingForRoll
            } else passTurn(s, res)
        }
        res.sequence = s.sequence
        return res
    }

    /** A seat leaves the match (quit, abandonment). Its pieces leave the board. */
    fun resign(s: GameState, seat: Int): CommandResult {
        if (s.isOver) return CommandResult.reject(RejectReason.MatchOver, s.sequence)
        if (seat !in 0 until s.seatCount || !s.isActive(seat)) return CommandResult.reject(RejectReason.InvalidSeat, s.sequence)

        val res = CommandResult(true)
        s.sequence++
        val resignedAlready = s.resigned.count { it }
        s.resigned[seat] = true
        s.rank[seat] = s.seatCount - resignedAlready // earliest quitter ranks last
        s.progress[seat].fill(0)
        res.events += GameEvent(EventType.SeatResigned, seat = seat, value = s.rank[seat])

        if (s.activeSeatCount <= 1) finishMatch(s, res)
        else if (seat == s.current) passTurn(s, res)
        res.sequence = s.sequence
        return res
    }

    // ---------------- helpers ----------------

    private fun validateTurn(s: GameState, seat: Int, expected: Phase) = when {
        s.isOver -> RejectReason.MatchOver
        seat !in 0 until s.seatCount -> RejectReason.InvalidSeat
        seat != s.current -> RejectReason.OutOfTurn
        s.phase != expected -> RejectReason.WrongPhase
        else -> RejectReason.None
    }

    private fun captureAt(s: GameState, seat: Int, piece: Int, g: Int, res: CommandResult): Boolean {
        var any = false
        for (o in 0 until s.seatCount) {
            // A safe pair (StackSafe) shares the square untouched; Blockade never gets here (the move is refused).
            if (o == seat || !s.isActive(o) || !capturableAt(s, o, g)) continue
            for (p in 0 until Board.PIECES) {
                val pr = s.progress[o][p]
                if (!Board.isOnTrack(pr) || Board.globalIndex(s.seatColor[o], pr) != g) continue
                s.progress[o][p] = 0
                any = true
                res.events += GameEvent(EventType.PieceCaptured, seat = seat, piece = piece, otherSeat = o, otherPiece = p, value = g, from = pr)
            }
        }
        return any
    }

    private fun passTurn(s: GameState, res: CommandResult) {
        s.consecutiveSixes = 0
        var next = s.current
        for (i in 0 until s.seatCount) {
            next = (next + 1) % s.seatCount
            if (s.isActive(next)) break
        }
        s.current = next
        s.turnNumber++
        s.phase = Phase.WaitingForRoll
        res.events += GameEvent(EventType.TurnChanged, seat = next)
    }

    private fun finishMatch(s: GameState, res: CommandResult) {
        // Seats still playing are ranked by how far along they are, filling the places between finishers and quitters.
        var rank = s.finishedCount
        (0 until s.seatCount).filter { s.isActive(it) }.sortedByDescending { s.progress[it].sum() }
            .forEach { s.rank[it] = ++rank }
        s.phase = Phase.Finished
        res.events += GameEvent(EventType.MatchEnded)
    }
}
