package lux.core

enum class Phase { WaitingForRoll, WaitingForMove, Finished }

/**
 * Complete authoritative state of one match. Mutated only by [Engine].
 * Serializes to a compact, versioned binary snapshot for joining and reconnecting (same layout as the Unity build).
 */
class GameState(
    val seatCount: Int,
    val seatColor: IntArray,
    var rules: RuleOptions,
) {
    /** Incremented by every accepted command. Commands carry the value they were issued against. */
    var sequence = 0L
    /** Incremented whenever the turn passes to another seat. */
    var turnNumber = 0

    /** [seat][piece] progress 0..57. */
    val progress: Array<IntArray> = Array(seatCount) { IntArray(Board.PIECES) }
    /** Finishing position per seat (1 = winner), 0 while still in play. */
    val rank = IntArray(seatCount)
    val resigned = BooleanArray(seatCount)
    /** Per seat: consecutive non-six rolls while every unfinished piece was in base (drives the lucky-six rule). */
    val drought = IntArray(seatCount)
    var finishedCount = 0

    var current = 0
    var phase = Phase.WaitingForRoll
    var lastRoll = 0
    var consecutiveSixes = 0

    val isOver get() = phase == Phase.Finished
    fun isActive(seat: Int) = rank[seat] == 0 && !resigned[seat]
    val activeSeatCount get() = (0 until seatCount).count { isActive(it) }

    fun copy(): GameState {
        val c = GameState(seatCount, seatColor.copyOf(), rules)
        c.sequence = sequence; c.turnNumber = turnNumber
        for (i in 0 until seatCount) progress[i].copyInto(c.progress[i])
        rank.copyInto(c.rank); resigned.copyInto(c.resigned); drought.copyInto(c.drought)
        c.finishedCount = finishedCount; c.current = current; c.phase = phase
        c.lastRoll = lastRoll; c.consecutiveSixes = consecutiveSixes
        return c
    }

    // ---------------- snapshots ----------------

    fun serialize(): ByteArray {
        val w = Bytes()
        w.u32(MAGIC); w.u8(SNAPSHOT_VERSION); w.i64(sequence); w.i32(turnNumber); w.u8(seatCount)
        for (s in 0 until seatCount) {
            w.u8(seatColor[s])
            for (p in 0 until Board.PIECES) w.u8(progress[s][p])
            w.u8(rank[s]); w.bool(resigned[s]); w.u8(drought[s])
        }
        w.u8(finishedCount); w.u8(current); w.u8(phase.ordinal); w.u8(lastRoll); w.u8(consecutiveSixes)
        w.u8(rules.entryRollMask); w.u8(rules.stacks.ordinal); w.bool(rules.sixGrantsExtraTurn)
        w.bool(rules.captureGrantsExtraTurn); w.bool(rules.finishGrantsExtraTurn); w.u8(rules.sixPenaltyCount)
        w.bool(rules.endOnFirstWinner); w.u8(rules.sixPityTurns)
        return w.toByteArray()
    }

    /** FNV-1a over the snapshot, used to verify two clients agree on the position. */
    fun checksum(): UInt {
        var h = 2166136261u
        for (b in serialize()) { h = h xor b.toUByte().toUInt(); h *= 16777619u }
        return h
    }

    companion object {
        const val SNAPSHOT_VERSION = 2
        private const val MAGIC = 0x314F444CL // "LDO1"

        /** Returns the state, or throws [IllegalArgumentException] with a message if the data is malformed. */
        fun deserialize(data: ByteArray): GameState {
            val r = Reader(data)
            require(r.u32() == MAGIC) { "Not a Ludo snapshot." }
            val ver = r.u8()
            require(ver == SNAPSHOT_VERSION) { "Unsupported snapshot version $ver." }
            val seq = r.i64(); val turn = r.i32()
            val n = r.u8()
            require(n in 2..4) { "Bad seat count." }
            val colors = IntArray(n)
            val prog = Array(n) { IntArray(Board.PIECES) }
            val rank = IntArray(n); val res = BooleanArray(n); val dr = IntArray(n)
            var prev = -1
            for (s in 0 until n) {
                colors[s] = r.u8()
                require(colors[s] < Board.COLORS && colors[s] > prev) { "Bad colours." }
                prev = colors[s]
                for (p in 0 until Board.PIECES) { prog[s][p] = r.u8(); require(prog[s][p] <= Board.FINISH) { "Bad progress." } }
                rank[s] = r.u8(); require(rank[s] <= n) { "Bad rank." }
                res[s] = r.bool(); dr[s] = r.u8()
            }
            val finished = r.u8(); val cur = r.u8()
            require(cur < n) { "Bad current seat." }
            val ph = r.u8(); require(ph < Phase.entries.size) { "Bad phase." }
            val last = r.u8(); val sixes = r.u8()
            val mask = r.u8(); val st = r.u8(); require(st < StackRule.entries.size) { "Bad rules." }
            val rules = RuleOptions(
                entryRollMask = mask, stacks = StackRule.entries[st], sixGrantsExtraTurn = r.bool(),
                captureGrantsExtraTurn = r.bool(), finishGrantsExtraTurn = r.bool(), sixPenaltyCount = r.u8(),
                endOnFirstWinner = r.bool(), sixPityTurns = r.u8(),
            )
            require(rules.isValid()) { "Bad rules." }
            return GameState(n, colors, rules).apply {
                sequence = seq; turnNumber = turn
                for (s in 0 until n) { prog[s].copyInto(progress[s]); this.rank[s] = rank[s]; resigned[s] = res[s]; drought[s] = dr[s] }
                finishedCount = finished; current = cur; phase = Phase.entries[ph]; lastRoll = last; consecutiveSixes = sixes
            }
        }
    }
}

/** Little-endian writer matching .NET's BinaryWriter, so snapshots are compatible with the Unity build. */
internal class Bytes {
    private val buf = ArrayList<Byte>(64)
    fun u8(v: Int) { buf.add(v.toByte()) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun i32(v: Int) { for (i in 0 until 4) u8(v ushr (8 * i)) }
    fun u32(v: Long) = i32(v.toInt())
    fun i64(v: Long) { for (i in 0 until 8) u8((v ushr (8 * i)).toInt()) }
    fun toByteArray() = buf.toByteArray()
}

internal class Reader(private val d: ByteArray) {
    private var i = 0
    fun u8(): Int { require(i < d.size) { "Truncated snapshot." }; return d[i++].toInt() and 0xFF }
    fun bool() = u8() != 0
    fun i32(): Int { var v = 0; for (k in 0 until 4) v = v or (u8() shl (8 * k)); return v }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long { var v = 0L; for (k in 0 until 8) v = v or (u8().toLong() shl (8 * k)); return v }
}
