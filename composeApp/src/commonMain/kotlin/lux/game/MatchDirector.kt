package lux.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import lux.app.CaptureStyle
import lux.app.Haptic
import lux.audio.SfxId
import lux.core.BalancedDice
import lux.core.Board
import lux.core.Command
import lux.core.Cpu
import lux.core.CpuLevel
import lux.core.DiceSource
import lux.core.Engine
import lux.core.EventType
import lux.core.FairDice
import lux.core.GameEvent
import lux.core.GameState
import lux.core.MatchSession
import lux.core.MatchSetup
import lux.core.Phase
import lux.core.RuleOptions
import lux.core.net.Cmd
import lux.core.net.ErrorMsg
import lux.core.net.GameStarted
import lux.core.net.ResultMsg
import lux.core.net.ServerMsg
import lux.core.net.Snapshot
import lux.core.net.StateCodec
import lux.core.net.TurnTimer
import lux.net.RoomLink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SeatConfig(val color: Int, val cpu: Boolean, val level: CpuLevel = CpuLevel.Medium, val name: String, val badge: String = "")

/** Dice that let tests and the effects reel queue specific rolls; otherwise balanced or fair dice. */
class QueueDice(balanced: Boolean) : DiceSource {
    private val forced = ArrayDeque<Int>()
    private val inner: DiceSource = if (balanced) BalancedDice() else FairDice()
    fun enqueue(vararg v: Int) { forced.addAll(v.toList()) }
    override fun next(state: GameState, seat: Int) = forced.removeFirstOrNull() ?: inner.next(state, seat)
}

class Toast(val text: String, val color: Color, val seconds: Float, val id: Long)

/**
 * Runs an offline match: owns the authoritative [MatchSession], feeds it commands from humans and CPUs,
 * and turns the resulting engine events into animation on the [Stage]. Rules are never decided here.
 */
class MatchDirector(val stage: Stage) {
    private val app get() = stage.app
    private val style get() = stage.style

    var session: MatchSession? = null
        private set
    /** Online games have no local session: this mirrors the server's state after each result. */
    private var mirror: GameState? = null
    val state get() = session?.state ?: mirror
    /** Seats played on this phone (online); null offline, where every human seat is local. */
    var localColors: Set<Int>? = null
        private set
    fun isLocal(seat: Int) = seats.getOrNull(seat)?.let { !it.cpu && (localColors?.contains(it.color) ?: true) } ?: false
    /** Online: whose turn timer is running, and the stage time it runs out (for the plate countdown). */
    var timerColor by mutableIntStateOf(-1)
        private set
    var timerEndsAt by mutableStateOf(0f)
        private set
    var seats: List<SeatConfig> = emptyList()
        private set
    private var sprites: List<List<PieceSprite>> = emptyList()
    private val finishedCount = IntArray(4)
    private var lastCapturedBy = intArrayOf(-1, -1, -1, -1)
    private var captureStyle = CaptureStyle.Boom
    private var surprise = false
    /** Capture effects Surprise may use (the ones this player has). */
    var allowedCaptures: List<CaptureStyle> = CaptureStyle.entries
    private val cpuRng = Random(Random.nextLong())
    lateinit var dice: QueueDice
        private set

    // ---- observable state for the HUD
    var current by mutableIntStateOf(0)
        private set
    var hint by mutableStateOf("")
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set
    var over by mutableStateOf(false)
        private set
    var showResults by mutableStateOf(false)
    var showcase by mutableStateOf(false)
        private set
    /** Bumped when ranks change so plates refresh. */
    var rankVersion by mutableIntStateOf(0)
        private set
    /** Seconds the "big moment" effects added (budgeted to stay a small share of match time). */
    var showcaseSeconds = 0f
        private set
    private var matchStart = 0f
    private var toastId = 0L

    // ---- input
    private var rollRequested = false
    private var pickRequested = -1
    private var tap: G? = null

    fun requestRoll() { rollRequested = true }
    fun requestPick(piece: Int) { pickRequested = piece }
    /** A tap on the board, in board grid coordinates (unrotated). */
    fun tapBoard(g: G) { tap = g }

    fun colorOf(seat: Int) = style.seat[seats[seat].color]

    // ================================================================ lifecycle

    /** Called after every accepted command (used to save a series game so it can be resumed). */
    var onProgress: ((GameState) -> Unit)? = null
    /** In the middle of a series, last place gets a friendly "Next round!" sign instead of "Loser". */
    var softLastPlace = false

    /**
     * Starts a game. [startSeat] rolls first; [resume] continues a saved position instead of a fresh board
     * (its rules win over [rules]).
     */
    fun start(
        configs: List<SeatConfig>, rules: RuleOptions, capture: Int, balanced: Boolean, startSeat: Int = 0, resume: GameState? = null,
        /** Online: the server's state; there is no local session and [localColors] are the seats played on this phone. */
        online: GameState? = null, localColors: Set<Int>? = null,
    ) {
        stage.clear()
        val sorted = configs.sortedBy { it.color }
        seats = sorted
        surprise = capture >= CaptureStyle.SURPRISE
        captureStyle = if (surprise) allowedCaptures.ifEmpty { CaptureStyle.entries }.random(stage.rng) else CaptureStyle.entries[capture]
        lastCapturedBy = intArrayOf(-1, -1, -1, -1)
        finishedCount.fill(0)
        awarded.fill(false)
        over = false; showResults = false; hint = ""; toast = null
        showcaseSeconds = 0f
        matchStart = stage.time
        dice = QueueDice(balanced)
        val restored = (online ?: resume)?.takeIf { r -> r.seatCount == sorted.size && (0 until r.seatCount).all { r.seatColor[it] == sorted[it].color } && !r.isOver }
        mirror = online
        this.localColors = localColors
        session = if (online != null) null
        else if (restored != null) MatchSession(restored, dice)
        else MatchSession(MatchSetup(sorted.map { it.color }, rules, startSeat.coerceIn(0, sorted.size - 1)), dice)
        sprites = sorted.mapIndexed { s, cfg ->
            List(4) { p -> PieceSprite(s, p, cfg.color, Geo.baseSpot(cfg.color, p)).also { stage.pieces += it } }
        }
        if (restored != null) layoutFrom(restored)
        // Face the first seat played on this phone (the local player sits bottom-left: blue's corner).
        val face = localColors?.let { lc -> sorted.firstOrNull { it.color in lc } }?.color ?: sorted.firstOrNull { !it.cpu }?.color ?: sorted[0].color
        stage.viewTurns = ((3 - face) % 4 + 4) % 4
        stage.die.visible = true
        stage.die.pos = Geo.baseCentre(sorted[state!!.current].color)
        stage.die.scale = DIE_SCALE
        stage.die.value = 6
        stage.die.rx = 0f; stage.die.ry = 0f; stage.die.rz = 0f
        current = state!!.current
        timerColor = -1
        rankVersion++
    }

    /** Puts every piece exactly where [s] has it (resuming a saved game, or lining up with the server again). */
    private fun layoutFrom(s: GameState) {
        for (seat in seats.indices) {
            finishedCount[seat] = s.progress[seat].count { it == Board.FINISH }
            for (p in 0 until 4) {
                val pc = sprites[seat][p]
                pc.busy = false; pc.cut = null; pc.spin = 0f; pc.dissolve = 0f; pc.visible = !s.resigned[seat]
                pc.place(Geo.piece(seats[seat].color, p, s.progress[seat][p]))
            }
            if (s.rank[seat] > 0 && !s.resigned[seat]) giveAward(seat, s.rank[seat])
        }
        relayout()
        for (pc in stage.pieces) { pc.pos = pc.restPos; pc.scale = pc.restScale }
    }

    // ================================================================ online

    /**
     * Plays an online game: the server is the authority. Results arrive in order on [link] and are animated exactly
     * as offline ones. On this phone's turns, the player's tap is sent as a command - the server's answer (not the tap)
     * moves the piece. If the turn timer ran out the server plays for them, and that result simply arrives instead.
     * Returns a message that belongs to the next game (if one arrived), for the caller to start it.
     */
    suspend fun runOnline(link: RoomLink, localColors: Set<Int>): ServerMsg? {
        stage.wait(0.2f)
        var chimed = -1L
        while (true) {
            val s = state ?: return null
            if (s.isOver) break
            current = s.current
            val cfg = seats[s.current]
            val mine = cfg.color in localColors
            var msg: ServerMsg? = link.incoming.tryReceive().getOrNull()
            if (msg == null && s.phase == Phase.WaitingForRoll) {
                moveDie(Geo.baseCentre(cfg.color))
                stage.die.accent = style.seat[cfg.color]
            }
            if (msg == null && mine) {
                if (s.phase == Phase.WaitingForRoll) {
                    if (chimed != s.sequence) { chimed = s.sequence; app.sfx(SfxId.TurnPing, 0.5f) }
                    stage.die.waiting = true
                    hint = "Tap the dice!"
                    rollRequested = false; tap = null
                    while (!rollRequested && !tapOnDie(cfg.color) && link.incoming.isEmpty) stage.frame()
                    stage.die.waiting = false
                    hint = ""
                    if (link.incoming.isEmpty) {
                        rollRequested = false
                        app.sfx(SfxId.DiceShake, 0.8f)
                        link.send(Cmd(Random.nextLong(1, Long.MAX_VALUE), "roll", expected = s.sequence))
                    }
                } else {
                    val legal = Engine.legalMoves(s, s.current, s.lastRoll, collapseBase = true)
                    val pieces = sprites[s.current]
                    var pick = -1
                    if (legal.size == 1) {
                        pick = legal[0].piece
                        pieces[pick].selectable = true
                        stage.wait(0.12f)
                        pieces[pick].selectable = false
                    } else {
                        val options = options(s, pieces, legal, cfg.color)
                        for (o in options) for (pc in o.pieces) pc.selectable = true
                        stage.targets.clear()
                        for (o in options) stage.targets += Target(o.dest, cfg.color)
                        hint = "Pick a piece"
                        pickRequested = -1; tap = null
                        while (pick < 0 && link.incoming.isEmpty) { pick = resolvePick(s, options); if (pick < 0) stage.frame() }
                        pieces.forEach { it.selectable = false }
                        stage.targets.clear()
                        hint = ""
                    }
                    if (pick >= 0 && link.incoming.isEmpty) {
                        app.sfx(SfxId.Select, 0.5f)
                        link.send(Cmd(Random.nextLong(1, Long.MAX_VALUE), "move", pick, s.sequence))
                    }
                }
            }
            msg = msg ?: link.incoming.receive()
            when (msg) {
                is ResultMsg -> {
                    timerColor = -1
                    if (msg.byCpu && mine) say("Time's up - the computer helped", Color.White, 1.2f)
                    mirror = StateCodec.decode(msg.state)
                    play(msg.events)
                    relayout()
                }
                is Snapshot -> { mirror = StateCodec.decode(msg.state); layoutFrom(mirror!!) }
                is TurnTimer -> { timerColor = msg.color; timerEndsAt = stage.time + msg.seconds }
                is GameStarted -> return msg
                is ErrorMsg -> {}
                else -> {}
            }
        }
        over = true
        timerColor = -1
        stage.wait(0.4f)
        app.sfx(SfxId.Victory)
        showResults = true
        return null
    }

    /** Pieces each seat has brought home in the current game. */
    fun piecesHome(): IntArray = state?.let { s -> IntArray(s.seatCount) { seat -> s.progress[seat].count { it == Board.FINISH } } } ?: IntArray(0)

    fun announce(text: String, color: Color = style.accent, seconds: Float = 1.6f) = say(text, color, seconds)

    fun rankOf(seat: Int) = state?.rank?.getOrNull(seat) ?: 0

    private fun say(text: String, color: Color = style.accent, seconds: Float = 1.1f) {
        toast = Toast(text, color, seconds, ++toastId)
    }

    // ================================================================ the match

    suspend fun run() {
        val session = session ?: return
        stage.wait(0.2f)
        while (!session.state.isOver) {
            val s = session.state
            current = s.current
            val cfg = seats[s.current]
            val mine = sprites[s.current]
            if (s.phase == Phase.WaitingForRoll) {
                moveDie(Geo.baseCentre(cfg.color))
                stage.die.accent = style.seat[cfg.color]
                app.sfx(SfxId.TurnPing, 0.5f)
                if (cfg.cpu) {
                    stage.wait(0.25f)
                } else {
                    stage.die.waiting = true
                    hint = "Tap the dice!"
                    rollRequested = false; tap = null
                    while (!rollRequested && !tapOnDie(cfg.color)) stage.frame()
                    rollRequested = false; tap = null
                    stage.die.waiting = false
                    hint = ""
                }
                app.sfx(SfxId.DiceShake, 0.8f)
                val res = session.execute(Command.roll(s.current, s.sequence))
                if (!res.ok) { stage.frame(); continue }
                onProgress?.invoke(session.state)
                play(res.events)
            } else {
                val legal = Engine.legalMoves(s, s.current, s.lastRoll, collapseBase = true)
                val chosen: Int
                if (legal.size == 1) {
                    chosen = legal[0].piece
                    mine[chosen].selectable = !cfg.cpu
                    stage.wait(0.12f)
                    mine[chosen].selectable = false
                } else if (cfg.cpu) {
                    stage.wait(0.2f)
                    chosen = Cpu.chooseMove(s, s.current, cfg.level, cpuRng)
                } else {
                    val options = options(s, mine, legal, cfg.color)
                    for (o in options) for (pc in o.pieces) pc.selectable = true
                    stage.targets.clear()
                    for (o in options) stage.targets += Target(o.dest, cfg.color)
                    hint = "Pick a piece"
                    pickRequested = -1; tap = null
                    var pick = -1
                    while (pick < 0) { pick = resolvePick(s, options); if (pick < 0) stage.frame() }
                    mine.forEach { it.selectable = false }
                    stage.targets.clear()
                    hint = ""
                    chosen = pick
                }
                app.sfx(SfxId.Select, 0.5f)
                val res = session.execute(Command.move(s.current, chosen, s.sequence))
                if (!res.ok) { stage.frame(); continue }
                onProgress?.invoke(session.state)
                play(res.events)
                relayout()
            }
        }
        over = true
        stage.wait(0.4f)
        app.sfx(SfxId.Victory)
        showResults = true
    }

    /**
     * Did the player tap their own side of the screen? A generous target for older players: with 3 or 4 players it is
     * the whole quarter of the screen round their yard (out to the screen's edges); with 2 it is their half
     * (top or bottom, or left or right if the two sit side by side).
     */
    private fun tapOnDie(color: Int): Boolean {
        val t = tap ?: return false
        tap = null
        val turns = stage.viewTurns
        // Everything in screen orientation, so "top half" means the top of the phone.
        val p = Geo.rotate(t, turns)
        val me = Geo.rotate(Geo.baseCentre(color), turns)
        fun left(g: G) = g.x < 7.5f
        fun upper(g: G) = g.y < 7.5f
        val others = seats.map { Geo.rotate(Geo.baseCentre(it.color), turns) }.filter { left(it) != left(me) || upper(it) != upper(me) }
        if (seats.size == 2 && others.size == 1) {
            val o = others[0]
            return if (upper(o) != upper(me)) upper(p) == upper(me) else left(p) == left(me)
        }
        return left(p) == left(me) && upper(p) == upper(me)
    }

    /** One distinct choice: the move, the pieces that stand for it (a whole stack, or every piece in the yard), and where it lands. */
    private class Option(val piece: Int, val pieces: List<PieceSprite>, val dest: G)

    private fun options(s: GameState, mine: List<PieceSprite>, legal: List<lux.core.Move>, color: Int): List<Option> =
        legal.distinctBy { it.from }.map { m ->
            val stand = (0 until 4).filter { s.progress[s.current][it] == m.from }.map { mine[it] }
            val dest = if (m.to == Board.FINISH) Geo.finishSpot(color, finishedCount[s.current]) else Geo.piece(color, m.piece, m.to)
            Option(m.piece, stand, dest)
        }

    /**
     * Turns a tap into a move - but never guesses. Tapping a landing marker always means that move. Tapping a piece
     * picks it only when it is clearly the nearest movable piece; if two are about equally close, nothing moves, both
     * bounce and the player is asked to tap where it should go.
     */
    private fun resolvePick(s: GameState, options: List<Option>): Int {
        if (pickRequested >= 0) {
            val want = pickRequested
            pickRequested = -1
            options.firstOrNull { o -> o.pieces.any { it.id == want } }?.let { return it.piece }
        }
        val t = tap ?: return -1
        tap = null
        // Pieces stand up from their square, so the body (above the base on screen) counts as well as the base.
        val up = Geo.rotateVec(G(0f, -0.55f), -stage.viewTurns)
        fun pieceDist(o: Option) = o.pieces.minOf { minOf(dist(t, it.pos), dist(t, it.pos + up)) }
        // 1. A landing marker, unless another choice's piece is standing right there too.
        val byDest = options.minByOrNull { dist(t, it.dest) }!!
        if (dist(t, byDest.dest) < 0.55f && options.none { it !== byDest && pieceDist(it) < 0.55f }) return byDest.piece
        // 2. A piece: generous reach (about 1.8 squares) for older fingers, but it must be clearly the nearest.
        val near = options.map { it to pieceDist(it) }.filter { it.second < 1.8f }.sortedBy { it.second }
        if (near.isEmpty()) return -1
        if (near.size >= 2 && near[1].second - near[0].second < 0.5f) {
            hint = "Tap where it should go"
            stage.targetsFlashAt = stage.time
            for ((o, _) in near.take(2)) o.pieces.forEach { it.impulse(9f) }
            app.sfx(SfxId.Select, 0.4f, 0.8f)
            return -1
        }
        return near[0].first.piece
    }

    // ================================================================ dice

    private suspend fun moveDie(to: G) {
        val d = stage.die
        val from = d.pos
        if (dist(from, to) < 0.05f) return
        stage.tween(0.25f) { k ->
            val e = smooth(k)
            d.pos = lerp(from, to, e)
            d.z = 0.5f * 4f * e * (1f - e)
        }
        d.z = 0f
    }

    /** Throws the die: it leaps, tumbles in 3D and settles showing [value]. */
    suspend fun rollDie(value: Int) {
        val d = stage.die
        val (tx, ty) = when (value) { 1 -> 0f to 0f; 6 -> 180f to 0f; 2 -> 90f to 0f; 5 -> -90f to 0f; 3 -> 0f to -90f; else -> 0f to 90f }
        val tz = stage.rnd(-12f, 12f)
        val turnsX = 360f * (1 + stage.rng.nextInt(2)) * if (stage.chance(0.5f)) 1 else -1
        val turnsY = 360f * (1 + stage.rng.nextInt(2)) * if (stage.chance(0.5f)) 1 else -1
        val sx = d.rx; val sy = d.ry; val sz = d.rz
        val ex = tx + turnsX; val ey = ty + turnsY; val ez = tz + 180f * if (stage.chance(0.5f)) 1 else -1
        val home = d.pos
        val dur = 0.62f
        var bounced = false
        stage.tween(dur) { k ->
            // Big leap then one small bounce; spin decelerates into the final face.
            val e = 1f - (1f - k) * (1f - k) * (1f - k)
            d.rx = mix(sx, ex, e); d.ry = mix(sy, ey, e); d.rz = mix(sz, ez, e)
            d.z = if (k < 0.72f) { val q = k / 0.72f; 1.6f * 4f * q * (1f - q) } else { val q = (k - 0.72f) / 0.28f; 0.3f * 4f * q * (1f - q) }
            d.scale = DIE_SCALE + d.z * 0.14f
            if (!bounced && k >= 0.72f) {
                bounced = true
                d.impulse(-8f)
                app.sfx(SfxId.DiceBounce, 0.7f)
                stage.landingDust(home, Color.White)
            }
        }
        d.rx = tx; d.ry = ty; d.rz = tz
        d.z = 0f; d.scale = DIE_SCALE
        d.value = value
        d.impulse(-6f)
        app.sfx(SfxId.DiceBounce, 0.6f)
        app.haptic(Haptic.Light)
        if (value == 6) {
            stage.ring(home, 0.3f, 1.8f, 0.4f, style.accent, 0.9f)
            stage.burst(PK.Sparkle, home, 0.6f, 12, 1.6f, 2.2f, 0.3f, 0.6f, style.accent.lighten(0.4f), 3f)
            app.sfx(SfxId.DiceSix, 0.7f)
        }
        stage.wait(0.12f)
    }

    // ================================================================ events -> animation

    private suspend fun play(evs: List<GameEvent>) {
        var i = 0
        while (i < evs.size) {
            val e = evs[i]
            when (e.type) {
                EventType.LuckySix -> say("Lucky six!")
                EventType.Rolled -> rollDie(e.value)
                EventType.SixPenalty -> { say("Three sixes! Turn lost"); app.sfx(SfxId.Penalty, 0.8f); stage.wait(0.6f) }
                EventType.NoLegalMoves -> { app.sfx(SfxId.NoMove, 0.6f); stage.die.impulse(-5f); stage.wait(0.35f) }
                EventType.PieceEntered, EventType.PieceMoved -> {
                    // Gather the captures that follow this move.
                    val captures = ArrayList<GameEvent>()
                    var home = false
                    var j = i + 1
                    while (j < evs.size) {
                        val f = evs[j]
                        if (f.type == EventType.PieceCaptured && f.seat == e.seat && f.piece == e.piece) captures += f
                        else if (f.type == EventType.PieceReachedHome) home = true
                        else break
                        j++
                    }
                    animateMove(e, captures, home)
                    i = j - 1
                }
                EventType.SeatFinished -> {
                    val cfg = seats[e.seat]
                    say(if (e.value == 1) "${cfg.name} wins!" else "${cfg.name} is #${e.value}!", style.seat[cfg.color], 1.4f)
                    rankVersion++
                    giveAward(e.seat, e.value)
                    allHome(e.seat, e.value == 1)
                    showcaseSeconds += ALL_HOME_SECONDS * HOME_TEMPO - 0.8f
                }
                EventType.MatchEnded -> {
                    rankVersion++
                    stage.die.visible = false
                    if (awardRemaining()) stage.wait(1.6f)
                }
                EventType.SeatResigned -> {
                    say("${seats[e.seat].name} left the match", Color.White)
                    sprites[e.seat].forEach { it.visible = false }
                    rankVersion++
                }
                else -> {}
            }
            i++
        }
    }

    private suspend fun animateMove(e: GameEvent, captures: List<GameEvent>, reachedHome: Boolean) {
        val pc = sprites[e.seat][e.piece]
        val color = seats[e.seat].color
        val col = style.seat[color]
        pc.busy = true
        pc.scale = pc.restScale
        if (e.type == EventType.PieceEntered) {
            val to = Geo.piece(color, e.piece, 1)
            app.sfx(SfxId.Entered, 0.8f)
            stage.release(pc.pos, col)
            if (captures.isNotEmpty()) captureMove(pc, to, captures)
            else stage.hop(pc, to, 0.3f, 1.0f, {
                stage.landingDust(to, col); stage.cheer(pc, col); app.sfx(SfxId.Land, 0.9f); app.haptic(Haptic.Light)
            }, anticipate = true, scale = 1f, flip = 360f)
        } else {
            val finishSlot = if (reachedHome) finishedCount[e.seat] else 0
            // The second-to-last piece home is a big moment: "one more to go!" (the last one gets the full dance).
            val oneToGo = reachedHome && finishedCount[e.seat] == Board.PIECES - 2
            for (p in e.from + 1..e.to) {
                val last = p == e.to
                val to = Geo.piece(color, e.piece, p, finishSlot)
                val scale = if (p == Board.FINISH) FINISHED_SCALE else 1f
                if (p == Board.HOME_LANE_START) stage.laneEntry(to, col)
                if (last && captures.isNotEmpty()) {
                    captureMove(pc, to, captures)
                } else if (last && oneToGo) {
                    // A slow, soaring final leap with the camera leaning in.
                    stage.focus(to, 0.16f)
                    app.sfx(SfxId.Riser, 0.8f)
                    stage.lightPillar(to, col, 0.9f)
                    stage.hop(pc, to, 0.5f, 1.6f, { stage.landingDust(to, col); app.sfx(SfxId.Land, 0.9f); app.haptic(Haptic.Medium) }, anticipate = true, scale = scale)
                    showcaseSeconds += 0.5f - HOP_LAST
                } else {
                    val safe = last && Board.isOnTrack(p) && Board.isSafeSquare(Board.globalIndex(color, p))
                    app.hop(p - e.from - 1)
                    stage.hop(pc, to, if (last) HOP_LAST else HOP_STEP, if (last) HOP_LAST_HEIGHT else HOP_HEIGHT, {
                        if (last) {
                            stage.landingDust(to, col)
                            if (safe) stage.shieldBubble(pc, col)
                            app.sfx(SfxId.Land, 0.7f); app.haptic(Haptic.Light)
                        }
                    }, anticipate = p == e.from + 1, scale = scale)
                }
            }
            if (reachedHome) {
                finishedCount[e.seat]++
                val spot = Geo.finishSpot(color, finishSlot)
                // Coming home: half as long again, and bigger, than it used to be (the walk there stays brisk).
                stage.celebrate(HOME_TEMPO, HOME_FX) {
                    stage.homeCelebration(spot, col)
                    stage.lightPillar(spot, col)
                    pc.joy = 1f
                    if (oneToGo) {
                        say("3 home! One to go!", col)
                        stage.fireworks(spot, listOf(col, Gold, Color.White), 3, 1.6f)
                        stage.wait(0.65f)
                        stage.unfocus()
                        showcaseSeconds += 0.35f * HOME_TEMPO
                    } else {
                        say("Home!", col, 1.0f)
                        stage.fireworks(spot, listOf(col, Gold), 1, 1.0f)
                        stage.wait(0.3f)
                    }
                }
            }
        }
        pc.busy = false
        relayout()
    }

    private suspend fun captureMove(attacker: PieceSprite, landing: G, captures: List<GameEvent>) {
        // The first victim gets the full scene; any extra victims are blasted home in parallel.
        val first = captures[0]
        val victim = sprites[first.otherSeat][first.otherPiece]
        val vcolor = seats[first.otherSeat].color
        val ctx = CaptureContext(
            attacker, victim, landing, Geo.baseSpot(vcolor, first.otherPiece),
            style.seat[attacker.color], style.seat[vcolor], vcolor, first.otherPiece, first.from,
        )
        for (k in 1 until captures.size) {
            val ev = captures[k]
            val other = sprites[ev.otherSeat][ev.otherPiece]
            val oc = seats[ev.otherSeat].color
            stage.launch { stage.returnHome(other, landing, Geo.baseSpot(oc, ev.otherPiece)) }
        }
        val atk = attacker.seat
        val revenge = lastCapturedBy[atk] >= 0 && lastCapturedBy[atk] == first.otherSeat
        for (ev in captures) lastCapturedBy[ev.otherSeat] = atk
        if (revenge) lastCapturedBy[atk] = -1
        // Only the rare moments get a word; ordinary captures speak for themselves.
        if (captures.size > 1) say("Double cut!", style.seat[attacker.color])
        else if (revenge) say("Revenge!", style.seat[attacker.color])
        if (surprise) {
            // A different capture each time, never the same one twice running.
            val pool = allowedCaptures.ifEmpty { CaptureStyle.entries }.filter { it != captureStyle }
            if (pool.isNotEmpty()) captureStyle = pool[stage.rng.nextInt(pool.size)]
        }
        stage.playCapture(captureStyle, ctx)
    }

    // ================================================================ all four home

    /**
     * The big one: all four pieces home. They rise out of the centre, circle it under spinning light rays while
     * fireworks go off in waves, then settle back. The one place a match deliberately slows down (~3 s).
     */
    private suspend fun allHome(seat: Int, winner: Boolean) = stage.celebrate(HOME_TEMPO, ALL_HOME_FX) { allHomeScene(seat, winner) }

    private suspend fun allHomeScene(seat: Int, winner: Boolean) {
        val pcs = sprites[seat]
        val c = style.seat[seats[seat].color]
        val centre = G.Centre
        val pal = listOf(c, c.lighten(0.5f), Gold, Color.White)
        val homeSpots = pcs.map { it.restPos }
        pcs.forEach { it.busy = true }
        stage.focus(centre, 0.12f)
        app.sfx(SfxId.Riser, 0.9f)
        app.sfx(SfxId.HomeEnter, 0.9f)
        stage.decal(DK.Rays, centre, 1.5f, 7.5f, ALL_HOME_SECONDS, lerp(c, Color(1f, 0.9f, 0.6f), 0.5f), 0.55f, 35f)
        stage.ring(centre, 0.3f, 6f, 0.9f, c.lighten(0.5f), 0.9f)
        stage.lightPillar(centre, c, 1.2f)
        app.haptic(Haptic.Heavy)
        val rise = 0.5f; val orbit = 1.75f; val back = 0.45f
        val radius = 1.25f; val height = 1.6f
        fun ringAt(i: Int, turn: Float): G {
            val a = (45f + i * 90f + turn * 360f) * PI.toFloat() / 180f
            return centre + G(cos(a), sin(a)) * radius
        }
        stage.tween(rise) { k ->
            val e = smooth(k)
            for (i in 0 until 4) {
                pcs[i].pos = lerp(homeSpots[i], ringAt(i, 0f), e)
                pcs[i].z = height * e + sin(e * PI.toFloat()) * 0.6f
                pcs[i].scale = mix(0.8f, 1.15f, e)
            }
        }
        pcs.forEach { it.trail = true; it.flash = 0.8f }
        var wave = 0
        var t = 0f
        // The orbit is a hand-run loop, so it stretches itself with the celebration's tempo.
        val orbitLen = orbit * stage.tempo
        while (t < orbitLen) {
            val turn = smooth(t / orbitLen) * 1.25f
            for (i in 0 until 4) {
                pcs[i].pos = ringAt(i, turn)
                pcs[i].z = height + sin(t * 9f + i * 1.57f) * 0.18f
            }
            if (wave < 4 && t >= wave * 0.55f * stage.tempo) { stage.fireworks(centre, pal, if (winner) 4 else 3, 3.2f); wave++ }
            if (stage.chance(0.5f)) stage.emit(PK.Sparkle, ringAt(stage.rng.nextInt(4), turn), height, 0f, 0f, -0.5f, c.lighten(0.5f), 0.3f, 0.6f)
            t += stage.frame()
        }
        val from = pcs.map { it.pos }
        val fromZ = pcs.map { it.z }
        stage.tween(back) { k ->
            for (i in 0 until 4) {
                pcs[i].pos = lerp(from[i], homeSpots[i], k * k)
                pcs[i].z = mix(fromZ[i], 0f, k * k)
                pcs[i].scale = mix(1.15f, 0.8f, k)
            }
        }
        for (i in 0 until 4) {
            pcs[i].pos = homeSpots[i]; pcs[i].z = 0f; pcs[i].scale = 0.8f
            pcs[i].trail = false; pcs[i].impulse(10f); pcs[i].busy = false
        }
        stage.ring(centre, 0.2f, 3f, 0.5f, Color.White, 0.8f)
        stage.burst(PK.Dust, centre, 0.05f, 10, 2f, 0.6f, 0.3f, 0.6f, Color(1f, 0.95f, 0.85f, 0.4f))
        app.sfx(SfxId.Land)
        stage.punch(1.6f)
        stage.unfocus()
        stage.wait(ALL_HOME_SECONDS - rise - orbit - back)
    }

    // ================================================================ medals

    private val awarded = BooleanArray(4)

    /** Gold / silver / bronze for places 1-3; last place (with 2+ players) gets the Loser sign. */
    private fun giveAward(seat: Int, rank: Int) {
        val s = state ?: return
        if (awarded[seat] || rank <= 0) return
        val loser = rank == s.seatCount
        if (!loser && rank > 3) return
        awarded[seat] = true
        val color = seats[seat].color
        stage.awards += AwardSprite(Geo.baseCentre(color), rank, loser, style.seat[color], soft = loser && softLastPlace)
        app.sfx(if (loser) SfxId.NoMove else SfxId.HomeEnter, 0.6f)
    }

    private fun awardRemaining(): Boolean {
        val s = state ?: return false
        var any = false
        for (i in 0 until s.seatCount) if (!awarded[i] && !s.resigned[i] && s.rank[i] > 0) { giveAward(i, s.rank[i]); any = any || awarded[i] }
        return any
    }

    // ================================================================ stack layout

    private fun relayout() {
        val s = state ?: return
        val groups = LinkedHashMap<Long, MutableList<PieceSprite>>()
        for (si in seats.indices) {
            if (s.resigned[si]) continue
            for (p in 0 until 4) {
                val prog = s.progress[si][p]
                val key: Long = when {
                    prog == 0 -> -1000L - (si * 4 + p)
                    prog == Board.FINISH -> -2000L - (si * 4 + p)
                    Board.isOnTrack(prog) -> Board.globalIndex(s.seatColor[si], prog).toLong()
                    else -> 1000L + si * 100 + prog
                }
                groups.getOrPut(key) { ArrayList() } += sprites[si][p]
            }
        }
        for (list in groups.values) {
            val n = list.size
            list.forEachIndexed { i, pc ->
                val prog = s.progress[pc.seat][pc.id]
                val slot = if (prog == Board.FINISH) (0 until pc.id).count { s.progress[pc.seat][it] == Board.FINISH } else 0
                var pos = Geo.piece(pc.color, pc.id, prog, slot)
                var scale = if (prog == Board.FINISH) FINISHED_SCALE else 1f
                if (n > 1) {
                    val a = i * PI.toFloat() * 2f / n + 0.6f
                    pos += G(cos(a), sin(a)) * if (n == 2) 0.24f else 0.3f
                    scale *= if (n <= 2) 0.82f else 0.7f
                }
                if (!pc.busy) { pc.restPos = pos; pc.restScale = scale }
            }
        }
    }

    // ================================================================ effects reel

    /** Plays every capture effect and big moment on a demo board (nothing is scored), then calls [done]. */
    suspend fun runShowcase(done: () -> Unit) {
        showcase = true
        val names = listOf("Red", "Green", "Yellow", "Blue")
        start(List(4) { SeatConfig(it, cpu = true, name = names[it]) }, RuleOptions(), 0, false)
        stage.viewTurns = 0
        val red = sprites[0]; val green = sprites[1]; val yellow = sprites[2]; val blue = sprites[3]
        val st = stage
        suspend fun caption(text: String) { say(text, style.accent, 1.25f); st.wait(1.0f) }
        suspend fun hops(pc: PieceSprite, color: Int, piece: Int, from: Int, to: Int, onLast: ((G) -> Unit)? = null) {
            for (p in from + 1..to) {
                val last = p == to
                val at = Geo.piece(color, piece, p)
                if (p == Board.HOME_LANE_START) st.laneEntry(at, style.seat[color])
                app.hop(p - from - 1)
                st.hop(pc, at, if (last) HOP_LAST else HOP_STEP, if (last) HOP_LAST_HEIGHT else HOP_HEIGHT, {
                    if (last) { st.landingDust(at, style.seat[color]); app.sfx(SfxId.Land, 0.7f); onLast?.invoke(at) }
                }, anticipate = p == from + 1)
            }
        }
        st.wait(0.4f)
        // 1. Every capture, on the same square.
        for (cs in CaptureStyle.entries) {
            red[0].place(Geo.piece(0, 0, 20))
            yellow[0].place(Geo.piece(2, 0, 49))
            caption(cs.title)
            hops(red[0], 0, 0, 20, 22)
            val ctx = CaptureContext(red[0], yellow[0], Geo.piece(0, 0, 23), Geo.piece(2, 0, 0), style.seat[0], style.seat[2], 2, 0, 49)
            st.playCapture(cs, ctx)
            st.wait(0.8f)
            red[0].place(Geo.piece(0, 0, 0))
        }
        // 2. A six brings a piece out.
        caption("A six! Out of the yard")
        moveDie(Geo.baseCentre(0))
        rollDie(6)
        app.sfx(SfxId.Entered, 0.8f)
        st.release(red[1].pos, style.seat[0])
        val start = Geo.piece(0, 1, 1)
        st.hop(red[1], start, 0.3f, 1.0f, { st.landingDust(start, style.seat[0]); st.cheer(red[1], style.seat[0]); app.sfx(SfxId.Land, 0.9f) }, true, 1f, 360f)
        st.wait(0.6f)
        // 3. Safe star square.
        caption("Safe on a star")
        hops(red[1], 0, 1, 1, 9) { st.shieldBubble(red[1], style.seat[0]) }
        st.wait(0.8f)
        // 4. Turning into the home lane.
        caption("Into the home lane")
        green[0].place(Geo.piece(1, 0, 47))
        hops(green[0], 1, 0, 47, 53)
        st.wait(0.8f)
        // 5. Third piece home: one to go.
        caption("3 home - one to go!")
        blue[0].place(Geo.piece(3, 0, Board.FINISH, 0), 0.8f)
        blue[1].place(Geo.piece(3, 1, Board.FINISH, 1), 0.8f)
        blue[2].place(Geo.piece(3, 2, 54))
        hops(blue[2], 3, 2, 54, 56)
        val home = Geo.piece(3, 2, Board.FINISH, 2)
        st.focus(home, 0.16f)
        app.sfx(SfxId.Riser, 0.8f)
        st.lightPillar(home, style.seat[3], 0.9f)
        st.hop(blue[2], home, 0.5f, 1.6f, { st.landingDust(home, style.seat[3]); app.sfx(SfxId.Land, 0.9f) }, true, 0.8f)
        st.homeCelebration(home, style.seat[3])
        st.lightPillar(home, style.seat[3])
        st.fireworks(home, listOf(style.seat[3], Gold), 2, 1.5f)
        st.wait(0.65f)
        st.unfocus()
        st.wait(0.8f)
        // 6. Last piece home: the dance.
        caption("All four home!")
        blue[3].place(Geo.piece(3, 3, 55))
        hops(blue[3], 3, 3, 55, Board.FINISH)
        blue[3].place(Geo.piece(3, 3, Board.FINISH, 3), 0.8f)
        st.homeCelebration(Geo.piece(3, 3, Board.FINISH, 3), style.seat[3])
        allHome(3, true)
        // 7. Medals, and the Loser sign.
        caption("Medals")
        st.die.visible = false
        for ((seat, rank) in listOf(3 to 1, 1 to 2, 2 to 3, 0 to 4)) {
            st.awards += AwardSprite(Geo.baseCentre(seat), rank, rank == 4, style.seat[seat])
            app.sfx(if (rank == 4) SfxId.NoMove else SfxId.HomeEnter, 0.6f)
            st.wait(0.9f)
        }
        st.wait(2.2f)
        say("That's everything!", style.accent, 1.4f)
        st.wait(1.6f)
        showcase = false
        done()
    }

    /** Stages a position for tests and demos (never used for real matches). */
    fun debugSetProgress(seat: Int, piece: Int, progress: Int) {
        val s = state ?: return
        s.progress[seat][piece] = progress
        sprites[seat][piece].place(Geo.piece(seats[seat].color, piece, progress, 0))
        relayout()
    }

    companion object {
        const val ALL_HOME_SECONDS = 3.1f
        /** The die is drawn 20% bigger than one board unit's standard size (easier to see). */
        const val DIE_SCALE = 1.2f
        /** Home celebrations run this much longer than the rest of the game (bigger by [HOME_FX] / [ALL_HOME_FX]). */
        const val HOME_TEMPO = 1.5f
        const val HOME_FX = 1.4f
        const val ALL_HOME_FX = 1.3f
        /** Size of a finished piece in the centre triangle (pieces are drawn larger now, so these shrink more). */
        const val FINISHED_SCALE = 0.66f
        /** Seconds per square while moving, and for the final square. */
        const val HOP_STEP = 0.15f
        const val HOP_LAST = 0.2f
        /** Jump height in squares (drawn at LIFT, so about 0.4 of a square up on screen). */
        const val HOP_HEIGHT = 0.7f
        const val HOP_LAST_HEIGHT = 0.85f
    }
}
