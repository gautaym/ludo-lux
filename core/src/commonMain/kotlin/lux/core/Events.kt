package lux.core

import kotlinx.serialization.Serializable

enum class EventType {
    /** Seat rolled value. */
    Rolled,
    /** The lucky-six rule turned this roll into a six (emitted just before Rolled). */
    LuckySix,
    /** Seat rolled its penalty number of consecutive sixes; the turn is forfeited and the roll is not played. */
    SixPenalty,
    /** Seat rolled but has no legal move; the turn passes. */
    NoLegalMoves,
    /** Piece left base: from = 0, to = 1. */
    PieceEntered,
    /** Piece moved along its path from -> to (progress values). Presentation hops through each square. */
    PieceMoved,
    /**
     * seat/piece captured otherSeat/otherPiece. value = shared-track square of the capture,
     * from = the victim's progress just before it was sent back to base.
     */
    PieceCaptured,
    /** Piece reached the finish (progress 57). */
    PieceReachedHome,
    /** All four pieces home. value = rank (1 = winner). */
    SeatFinished,
    /** Seat keeps the turn. value: 1 = rolled six, 2 = capture, 3 = piece finished. */
    ExtraTurn,
    /** Turn passed to seat. */
    TurnChanged,
    SeatResigned,
    /** Match is over; final standings are in the state's rank array. */
    MatchEnded,
}

/** Plain fact about something that happened. Presentation animates events; it never decides outcomes. */
@Serializable
data class GameEvent(
    val type: EventType,
    val seat: Int = 0,
    val piece: Int = 0,
    val from: Int = 0,
    val to: Int = 0,
    val value: Int = 0,
    val otherSeat: Int = 0,
    val otherPiece: Int = 0,
)

data class Move(val piece: Int, val from: Int, val to: Int)

class CommandResult(
    val ok: Boolean,
    val reason: RejectReason = RejectReason.None,
    val events: MutableList<GameEvent> = ArrayList(8),
) {
    /** Sequence number of the state after this command. */
    var sequence: Long = 0

    companion object {
        fun reject(reason: RejectReason, sequence: Long) = CommandResult(false, reason).also { it.sequence = sequence }
    }
}
