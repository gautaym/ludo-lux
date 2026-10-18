package lux.core

import kotlin.random.Random

data class Command(
    val id: Long,
    val type: CommandType,
    val seat: Int,
    /** Piece to move (Move only). */
    val piece: Int = 0,
    /** State sequence the issuer last saw. Must equal the authority's current sequence. */
    val expectedSequence: Long,
) {
    companion object {
        private fun newId() = Random.nextLong().let { if (it == 0L) 1L else it }
        fun roll(seat: Int, expected: Long) = Command(newId(), CommandType.Roll, seat, expectedSequence = expected)
        fun move(seat: Int, piece: Int, expected: Long) = Command(newId(), CommandType.Move, seat, piece, expected)
        fun resign(seat: Int, expected: Long) = Command(newId(), CommandType.Resign, seat, expectedSequence = expected)
    }
}

/**
 * The single authority over one match: owns the state and the dice, and is the only place commands are accepted.
 * Used by the server, by a LAN host and by offline play, so all three behave identically.
 * Commands are rejected when duplicated, stale, out of turn, in the wrong phase or illegal.
 */
class MatchSession(val state: GameState, private val dice: DiceSource) {
    constructor(setup: MatchSetup, dice: DiceSource) : this(Engine.create(setup), dice)

    private val seen = HashSet<Long>()
    private val seenOrder = ArrayDeque<Long>()

    fun execute(cmd: Command): CommandResult {
        if (cmd.id == 0L) return CommandResult.reject(RejectReason.InvalidCommand, state.sequence)
        if (cmd.id in seen) return CommandResult.reject(RejectReason.Duplicate, state.sequence)
        if (cmd.expectedSequence != state.sequence) return CommandResult.reject(RejectReason.StaleSequence, state.sequence)

        val res = when (cmd.type) {
            CommandType.Roll -> {
                // Validate before drawing so a rejected command can never consume a die roll.
                val bad = probe(cmd.seat)
                when {
                    bad != RejectReason.None -> CommandResult.reject(bad, state.sequence)
                    Engine.luckySixDue(state, cmd.seat) -> Engine.roll(state, cmd.seat, 6, lucky = true)
                    else -> Engine.roll(state, cmd.seat, dice.next(state, cmd.seat))
                }
            }
            CommandType.Move -> Engine.move(state, cmd.seat, cmd.piece)
            CommandType.Resign -> Engine.resign(state, cmd.seat)
        }
        if (res.ok) remember(cmd.id)
        return res
    }

    private fun probe(seat: Int) = when {
        state.isOver -> RejectReason.MatchOver
        seat !in 0 until state.seatCount -> RejectReason.InvalidSeat
        seat != state.current -> RejectReason.OutOfTurn
        state.phase != Phase.WaitingForRoll -> RejectReason.WrongPhase
        else -> RejectReason.None
    }

    private fun remember(id: Long) {
        seen += id
        seenOrder.addLast(id)
        while (seenOrder.size > REMEMBERED) seen -= seenOrder.removeFirst()
    }

    private companion object { const val REMEMBERED = 256 }
}
