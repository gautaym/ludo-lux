package lux.core

/** How stacked pieces behave. The default is [StackSafe]. */
enum class StackRule {
    /** Stacks have no special effect. Any lone or stacked pieces on a non-safe square are captured. */
    None,

    /**
     * Two or more pieces of one colour on the same square are a safe pair: they can never be captured. Opponents may
     * pass it and may also land on it - they simply share the square and nothing is captured. (Default.)
     */
    StackSafe,

    /** Two or more pieces of one colour on a track square form a blockade: opponents can neither land on it nor pass it. */
    Blockade,
}

/** Rule options for a match. Every field has a documented default. */
data class RuleOptions(
    /** Rolls that bring a piece out of base. Bit n set = roll n (1..6) enters. Default: only a 6. */
    val entryRollMask: Int = 1 shl 6,
    val stacks: StackRule = StackRule.StackSafe,
    /** Rolling a 6 grants another roll. */
    val sixGrantsExtraTurn: Boolean = true,
    val captureGrantsExtraTurn: Boolean = true,
    val finishGrantsExtraTurn: Boolean = true,
    /** The Nth consecutive six forfeits the turn (the six is not played). 0 disables the penalty. Default 3. */
    val sixPenaltyCount: Int = 3,
    /**
     * "Lucky six": when a seat has every unfinished piece still in base and has rolled this many times in a row
     * without a six, its next roll is a six. Applies equally to every seat and is announced. 0 disables it.
     */
    val sixPityTurns: Int = 4,
    /** true: the match ends as soon as one seat finishes. false (default): play on for a full ranking. */
    val endOnFirstWinner: Boolean = false,
) {
    fun entersOn(roll: Int) = roll in 1..6 && (entryRollMask and (1 shl roll)) != 0

    fun isValid() =
        (entryRollMask and 0b0111_1110) != 0 && (entryRollMask and 0b0111_1110.inv()) == 0 &&
            sixPenaltyCount in 0..6 && sixPityTurns in 0..20
}

/** Which colours play, in clockwise turn order, plus rule options. */
data class MatchSetup(
    /** Distinct colours 0..3 for each seat, increasing. Turn order is increasing colour order. */
    val colors: List<Int>,
    val rules: RuleOptions = RuleOptions(),
    /** Seat index that rolls first. */
    val startSeat: Int = 0,
) {
    fun validate(): String? {
        if (colors.size !in 2..4) return "A match needs 2-4 seats."
        var prev = -1
        for (c in colors) {
            if (c !in 0 until Board.COLORS) return "Invalid colour."
            if (c <= prev) return "Colours must be distinct and in clockwise order."
            prev = c
        }
        if (!rules.isValid()) return "Invalid rule options."
        if (startSeat !in colors.indices) return "Invalid start seat."
        return null
    }

    companion object {
        fun default(players: Int) = MatchSetup(
            when (players) {
                2 -> listOf(0, 2)       // opposite corners
                3 -> listOf(0, 1, 2)
                4 -> listOf(0, 1, 2, 3)
                else -> throw IllegalArgumentException("players")
            },
        )
    }
}

enum class CommandType { Roll, Move, Resign }

enum class RejectReason {
    None, MatchOver, InvalidSeat, OutOfTurn, WrongPhase, IllegalMove, StaleSequence, Duplicate, InvalidCommand,
}
