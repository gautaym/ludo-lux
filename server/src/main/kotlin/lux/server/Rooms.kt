package lux.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lux.core.BalancedDice
import lux.core.Board
import lux.core.Command
import lux.core.Cpu
import lux.core.CpuLevel
import lux.core.EventType
import lux.core.GameEvent
import lux.core.MatchSession
import lux.core.MatchSetup
import lux.core.Phase
import lux.core.RuleOptions
import lux.core.Series
import lux.core.SeriesSeat
import lux.core.net.Cmd
import lux.core.net.ClientMsg
import lux.core.net.ErrorMsg
import lux.core.net.GameStarted
import lux.core.net.NextGame
import lux.core.net.Ping
import lux.core.net.Pong
import lux.core.net.ResultMsg
import lux.core.net.RoomInfo
import lux.core.net.RoomStatus
import lux.core.net.RoomUpdate
import lux.core.net.SeatInfo
import lux.core.net.ServerMsg
import lux.core.net.SetCpu
import lux.core.net.Snapshot
import lux.core.net.StartGame
import lux.core.net.StateCodec
import lux.core.net.TakeSeat
import lux.core.net.TurnTimer
import lux.core.net.Wire
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Server settings. [pace] scales the waits that let clients finish animating (0 in tests). */
data class Config(
    val publicUrl: String = "http://localhost:8080",
    val turnSeconds: Int = 45,
    val pace: Double = 1.0,
    val cpuLevel: CpuLevel = CpuLevel.Medium,
    /** How often each room checks timers and computer turns. */
    val tickMs: Long = 200,
)

/** One live connection of a user to a room. [send] must never block the room. */
class Conn(val user: User, val send: (ServerMsg) -> Unit)

/** All live rooms, by code. */
class Rooms(private val store: Store, private val cfg: Config, private val scope: CoroutineScope) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val rng = SecureRandom()

    fun get(code: String) = rooms[code]
    fun inGroup(groupId: String) = rooms.values.filter { it.groupId == groupId && it.status != RoomStatus.Done }

    fun newCode(taken: (String) -> Boolean): String {
        while (true) {
            val c = String(CharArray(Wire.CODE_LENGTH) { Wire.CODE_ALPHABET[rng.nextInt(Wire.CODE_ALPHABET.length)] })
            if (!taken(c)) return c
        }
    }

    fun create(groupId: String, host: User, players: Int, seriesGames: Int, board: String, capture: Int): Room {
        val code = newCode { rooms.containsKey(it) || store.codeTaken(it) }
        val room = Room(code, groupId, host.id, players, seriesGames, board, capture, store, cfg)
        rooms[code] = room
        scope.launch { room.run() }
        return room
    }

    /** Drops finished or abandoned rooms. Called periodically. */
    fun sweep(now: Long = System.currentTimeMillis()) {
        for ((code, r) in rooms) if (r.expired(now)) { r.close(); rooms.remove(code) }
    }
}

/**
 * One live room: lobby, then a series of games. Everything that touches the room's state goes through [inbox] and is
 * handled by [run] one at a time, so there are no races between players, timers and the computer.
 *
 * The room is the authority: it rolls the dice (balanced dice), checks every command, and broadcasts each accepted
 * result with the full state. It also keeps the game moving: computer seats play on their own, and a human who is
 * away or takes longer than the turn timer gets a computer move made for them.
 */
class Room(
    val code: String,
    val groupId: String,
    val hostId: String,
    val players: Int,
    val seriesGames: Int,
    val board: String,
    val capture: Int,
    private val store: Store,
    private val cfg: Config,
) {
    private val log = LoggerFactory.getLogger("room.$code")

    sealed interface Act
    class Join(val conn: Conn) : Act
    class Leave(val conn: Conn) : Act
    class Msg(val conn: Conn, val msg: ClientMsg) : Act
    data object Tick : Act

    val inbox = Channel<Act>(Channel.UNLIMITED)

    private class Seat(val color: Int, var userId: String? = null, var name: String = "", var cpu: Boolean = false)

    private val seats = MatchSetup.default(players).colors.map { Seat(it) }
    private val conns = LinkedHashMap<String, MutableSet<Conn>>()
    @Volatile var status = RoomStatus.Lobby
        private set
    private var series: Series? = null
    private var session: MatchSession? = null
    /** Until this time clients are still animating the last result; nobody is hurried before it. */
    private var busyUntil = 0L
    private var deadline = 0L
    private var lastActivity = System.currentTimeMillis()

    // ---------------------------------------------------------------- loop

    suspend fun run() = kotlinx.coroutines.coroutineScope {
        val ticker = launch { while (isActive) { delay(cfg.tickMs); inbox.trySend(Tick) } }
        try {
            for (act in inbox) {
                try { handle(act, System.currentTimeMillis()) } catch (e: Exception) { log.error("room error", e) }
            }
        } finally {
            ticker.cancel()
        }
    }

    fun close() { inbox.close() }

    fun expired(now: Long): Boolean {
        val idle = now - lastActivity
        val nobody = conns.values.all { it.isEmpty() }
        return when (status) {
            RoomStatus.Done -> nobody && idle > 10 * 60_000
            else -> nobody && idle > 30 * 60_000
        }
    }

    private fun handle(act: Act, now: Long) {
        when (act) {
            is Join -> join(act.conn)
            is Leave -> { conns[act.conn.user.id]?.remove(act.conn); lastActivity = now; broadcastRoom() }
            is Msg -> { lastActivity = now; message(act.conn, act.msg, now) }
            Tick -> tick(now)
        }
    }

    // ---------------------------------------------------------------- people

    private fun join(c: Conn) {
        lastActivity = System.currentTimeMillis()
        conns.getOrPut(c.user.id) { LinkedHashSet() } += c
        // Seat the newcomer in the lobby (first free seat); players already seated keep theirs when they come back.
        if (status == RoomStatus.Lobby && seats.none { it.userId == c.user.id }) {
            seats.firstOrNull { it.userId == null && !it.cpu }?.let { it.userId = c.user.id; it.name = c.user.name }
        }
        seats.filter { it.userId == c.user.id }.forEach { it.name = c.user.name }
        broadcastRoom()
        val s = session
        if (s != null && (status == RoomStatus.Playing)) c.send(Snapshot(series!!.gameIndex, StateCodec.encode(s.state)))
    }

    private fun connected(userId: String?) = userId != null && conns[userId]?.isNotEmpty() == true

    private fun info() = RoomInfo(
        code, groupId, hostId, players, seriesGames, board, capture, status,
        seats.map { SeatInfo(it.color, it.userId, it.name, it.cpu, connected(it.userId)) },
        series?.encode() ?: "", "${cfg.publicUrl}/r/$code",
    )

    fun snapshotInfo() = info()

    private fun broadcast(m: ServerMsg) { for (set in conns.values) for (c in set) c.send(m) }
    private fun broadcastRoom() = broadcast(RoomUpdate(info()))

    // ---------------------------------------------------------------- messages

    private fun message(c: Conn, m: ClientMsg, now: Long) {
        val me = c.user.id
        when (m) {
            Ping -> c.send(Pong)
            is SetCpu -> {
                if (status != RoomStatus.Lobby || me != hostId) return c.send(ErrorMsg("Only the host can change seats before the game."))
                val seat = seats.firstOrNull { it.color == m.color } ?: return
                if (seat.userId != null) return c.send(ErrorMsg("That seat has a player."))
                seat.cpu = m.cpu; seat.name = if (m.cpu) "Computer" else ""
                broadcastRoom()
            }
            is TakeSeat -> {
                if (status != RoomStatus.Lobby) return
                val seat = seats.firstOrNull { it.color == m.color } ?: return
                if (seat.userId != null || seat.cpu) return c.send(ErrorMsg("That seat is taken."))
                seats.filter { it.userId == me }.forEach { it.userId = null; it.name = "" }
                seat.userId = me; seat.name = c.user.name
                broadcastRoom()
            }
            StartGame -> {
                if (status != RoomStatus.Lobby) return
                if (me != hostId) return c.send(ErrorMsg("Only the host can start."))
                // Empty seats become computer players.
                for (s in seats) if (s.userId == null) { s.cpu = true; s.name = "Computer" }
                series = Series(seriesGames, seats.map { SeriesSeat(it.color, it.cpu, cfg.cpuLevel.ordinal, it.name) })
                startGame(now)
            }
            NextGame -> if (status == RoomStatus.BetweenGames && seats.any { it.userId == me }) startGame(now)
            is Cmd -> command(c, m, now)
            else -> {}
        }
    }

    private fun startGame(now: Long) {
        val sr = series ?: return
        val game = sr.gameIndex
        session = MatchSession(MatchSetup(seats.map { it.color }, RuleOptions(), sr.startSeat(game)), BalancedDice())
        status = RoomStatus.Playing
        broadcastRoom()
        broadcast(GameStarted(game, StateCodec.encode(session!!.state)))
        busyUntil = now + paced(1.5)
        deadline = 0L
        log.info("game ${game + 1}/${sr.games} started")
    }

    private fun command(c: Conn, m: Cmd, now: Long) {
        val s = session ?: return
        if (status != RoomStatus.Playing) return
        val seatIdx = s.state.current
        if (seats[seatIdx].userId != c.user.id) return c.send(ErrorMsg("It is not your turn."))
        val cmd = when (m.kind) {
            "roll" -> Command(m.id, lux.core.CommandType.Roll, seatIdx, expectedSequence = m.expected)
            "move" -> Command(m.id, lux.core.CommandType.Move, seatIdx, m.piece, m.expected)
            else -> return c.send(ErrorMsg("Unknown command."))
        }
        val res = s.execute(cmd)
        if (!res.ok) {
            // Out of step (e.g. a double tap): resend the exact position so the client lines up again.
            c.send(ErrorMsg("Not accepted: ${res.reason}"))
            c.send(Snapshot(series!!.gameIndex, StateCodec.encode(s.state)))
            return
        }
        accepted(res.events, byCpu = false, now)
    }

    // ---------------------------------------------------------------- keeping the game moving

    private fun tick(now: Long) {
        val s = session ?: return
        if (status != RoomStatus.Playing || now < busyUntil) return
        val idx = s.state.current
        val seat = seats[idx]
        val human = seat.userId != null && !seat.cpu
        when {
            !human -> cpuPlay(now, byCpu = false)
            !connected(seat.userId) -> cpuPlay(now, byCpu = true)    // away: the computer keeps their place warm
            deadline == 0L -> {
                deadline = now + cfg.turnSeconds * 1000L
                broadcast(TurnTimer(seat.color, cfg.turnSeconds))
            }
            now >= deadline -> cpuPlay(now, byCpu = true)
        }
    }

    private fun cpuPlay(now: Long, byCpu: Boolean) {
        val s = session ?: return
        val st = s.state
        val cmd = if (st.phase == Phase.WaitingForRoll) Command.roll(st.current, st.sequence)
        else Command.move(st.current, Cpu.chooseMove(st, st.current, cfg.cpuLevel, kotlin.random.Random), st.sequence)
        val res = s.execute(cmd)
        if (res.ok) accepted(res.events, byCpu, now)
    }

    private fun accepted(events: List<GameEvent>, byCpu: Boolean, now: Long) {
        val s = session!!
        broadcast(ResultMsg(s.state.sequence, events, StateCodec.encode(s.state), byCpu))
        deadline = 0L
        // Computer players also "think" a little before acting, so their turns can be followed.
        val think = if (seats[s.state.current].cpu || s.state.isOver) 0.45 else 0.0
        busyUntil = now + paced(animationSeconds(events) + think)
        if (s.state.isOver) finishGame()
    }

    private fun finishGame() {
        val s = session!!.state
        val sr = series!!
        val pieces = IntArray(s.seatCount) { seat -> s.progress[seat].count { it == Board.FINISH } }
        sr.record(s.rank.copyOf(), pieces)
        // Group standings use the plain per-game points (no final-game doubling), so every game counts the same.
        val results = seats.indices.filter { seats[it].userId != null && !seats[it].cpu }.map { i ->
            PlayerResult(seats[i].userId!!, s.rank[i], pieces[i], Series.placementPoints(s.seatCount, s.rank[i]) + pieces[i])
        }
        if (results.isNotEmpty()) store.recordGame(groupId, code, results)
        if (sr.isOver) {
            val champ = sr.standings().first()
            seats[champ].userId?.takeIf { !seats[champ].cpu }?.let { store.recordSeriesWin(groupId, it) }
            status = RoomStatus.Done
        } else status = RoomStatus.BetweenGames
        session = null
        broadcastRoom()
        log.info("game ${sr.gameIndex}/${sr.games} finished")
    }

    private fun paced(seconds: Double) = (seconds * 1000 * cfg.pace).toLong()

    companion object {
        /**
         * How long the app takes to animate these events (slightly generous). The server waits this long before the
         * computer acts or the turn timer starts, so everyone sees every move.
         */
        fun animationSeconds(events: List<GameEvent>): Double {
            var t = 0.0
            for (e in events) t += when (e.type) {
                EventType.Rolled -> 0.8
                EventType.NoLegalMoves -> 0.4
                EventType.SixPenalty -> 0.7
                EventType.PieceEntered -> 1.1
                EventType.PieceMoved -> 0.25 + 0.16 * (e.to - e.from)
                EventType.PieceCaptured -> 2.0
                EventType.PieceReachedHome -> 0.6
                EventType.SeatFinished -> 3.4
                EventType.MatchEnded -> 1.8
                else -> 0.0
            }
            return t
        }
    }
}
