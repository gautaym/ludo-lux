package lux.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import lux.app.Haptic
import lux.audio.SfxId
import lux.board.SnakeBite
import lux.board.SnakesGeo
import lux.board.drawSnakeBite
import lux.core.SnakesBoard
import lux.core.SnakesDice
import lux.core.SnakesEngine
import lux.core.SnakesEvent
import lux.core.SnakesEventType
import lux.core.SnakesRules
import lux.core.SnakesState
import kotlin.math.abs
import kotlin.math.sin

/**
 * Runs a Snake & Ladders game on the [Stage]: rolls for whoever's turn it is (humans tap, the computer just rolls),
 * and turns the engine's events into animation. Like Ludo's director, it never decides rules itself.
 */
class SnakesDirector(val stage: Stage) {
    private val app get() = stage.app
    private val style get() = stage.style

    var state: SnakesState? = null
        private set
    var seats: List<SeatConfig> = emptyList()
        private set
    private var sprites: List<PieceSprite> = emptyList()
    val dice = SnakesDice()

    var current by mutableIntStateOf(0)
        private set
    var hint by mutableStateOf("")
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set
    var over by mutableStateOf(false)
        private set
    var showResults by mutableStateOf(false)
    var rankVersion by mutableIntStateOf(0)
        private set
    private var toastId = 0L
    private var rollRequested = false
    /** The snake eating someone right now, if any. */
    private var bite: SnakeBite? = null

    /** Pieces stand this big on their own square (a piece is about 0.85 of a square wide), smaller when sharing. */
    private val solo = 1.65f
    private val shared = 1.2f

    /** Saved after every roll (so a series game can be continued later). */
    var onProgress: ((SnakesState) -> Unit)? = null
    /** Mid-series, last place gets "Next round!" instead of "Last place". */
    var softLastPlace = false

    fun requestRoll() { rollRequested = true }
    fun isLocal(seat: Int) = seats.getOrNull(seat)?.cpu == false
    fun rankOf(seat: Int) = state?.rank?.getOrNull(seat) ?: 0

    fun start(configs: List<SeatConfig>, rules: SnakesRules, startSeat: Int = 0, resume: SnakesState? = null) {
        stage.clear()
        stage.viewTurns = 0
        bite = null
        stage.overlay = { v -> bite?.let { drawSnakeBite(it, v) } }
        val sorted = configs.sortedBy { it.color }
        seats = sorted
        over = false; showResults = false; hint = ""; toast = null
        val colors = sorted.map { it.color }.toIntArray()
        val s = resume?.takeIf { r -> r.seatColor.contentEquals(colors) && !r.isOver }
            ?: SnakesState(colors, rules, startSeat.coerceIn(0, sorted.size - 1))
        state = s
        sprites = sorted.mapIndexed { i, cfg -> PieceSprite(i, 0, cfg.color, SnakesGeo.startPad(i, sorted.size)).also { stage.pieces += it } }
        relayout(snap = true)
        stage.die.visible = true
        stage.die.pos = SnakesGeo.dieSpot
        stage.die.value = 6
        stage.die.scale = 1.6f
        stage.die.rx = 0f; stage.die.ry = 0f; stage.die.rz = 0f
        current = s.current
        rankVersion++
    }

    private fun say(text: String, color: Color = style.accent, seconds: Float = 1.1f) {
        toast = Toast(text, color, seconds, ++toastId)
    }

    /** Where every piece should stand: on its square (sharing squares in a cluster) or on the start pad. */
    private fun relayout(snap: Boolean = false) {
        val s = state ?: return
        val groups = (0 until s.seatCount).filter { s.pos[it] > 0 }.groupBy { s.pos[it] }
        for (seat in 0 until s.seatCount) {
            val pc = sprites[seat]
            val cell = s.pos[seat]
            pc.restPos = if (cell == 0) SnakesGeo.startPad(seat, s.seatCount) else {
                val g = groups[cell]!!
                SnakesGeo.spot(cell, g.indexOf(seat), g.size)
            }
            pc.restScale = if (cell == 0) 1.05f else if (groups[cell]!!.size > 1) shared else solo
            pc.halo = if (cell == 0) 0f else 1f
            if (snap) { pc.pos = pc.restPos; pc.scale = pc.restScale }
        }
    }

    suspend fun run() {
        val s = state ?: return
        stage.wait(0.2f)
        while (!s.isOver) {
            current = s.current
            val cfg = seats[s.current]
            stage.die.accent = style.seat[cfg.color]
            app.sfx(SfxId.TurnPing, 0.5f)
            if (cfg.cpu) {
                stage.wait(0.3f)
            } else {
                stage.die.waiting = true
                hint = "Tap the dice!"
                rollRequested = false
                while (!rollRequested) stage.frame()
                rollRequested = false
                stage.die.waiting = false
                hint = ""
            }
            app.sfx(SfxId.DiceShake, 0.8f)
            val events = SnakesEngine.roll(s, dice.next())
            onProgress?.invoke(s)
            play(events)
        }
        over = true
        stage.wait(0.5f)
        app.sfx(SfxId.Victory)
        showResults = true
    }

    // ================================================================ events -> animation

    private suspend fun play(evs: List<SnakesEvent>) {
        for (e in evs) {
            val pc = sprites.getOrNull(e.seat)
            val name = seats.getOrNull(e.seat)?.name.orEmpty()
            val col = seats.getOrNull(e.seat)?.let { style.seat[it.color] } ?: Color.White
            when (e.type) {
                SnakesEventType.Rolled -> rollDie(e.value)
                SnakesEventType.Moved -> walk(pc!!, e.from, e.to, col)
                SnakesEventType.Bounced -> { say("Too far - back you go!", Color.White, 0.9f); walk(pc!!, e.from, e.to, col, back = true) }
                SnakesEventType.Waiting -> {
                    app.sfx(SfxId.NoMove, 0.6f)
                    pc!!.impulse(-6f)
                    say(if (e.from == 0) "A six to start!" else "Needs exactly ${SnakesBoard.LAST - e.from}", Color.White, 0.9f)
                    stage.wait(0.3f)
                }
                SnakesEventType.Ladder -> climb(pc!!, e.from, e.to, col)
                SnakesEventType.Snake -> slide(pc!!, e.from, e.to, col)
                SnakesEventType.Finished -> {
                    rankVersion++
                    if (e.value == seats.size) {
                        // The last one left: no fanfare, just their place on the plate.
                    } else {
                        say(if (e.value == 1) "$name wins!" else "$name is #${e.value}!", col, 1.4f)
                        val top = SnakesGeo.centre(SnakesBoard.LAST)
                        stage.homeCelebration(top, col)
                        stage.lightPillar(top, col, 1.0f)
                        stage.fireworks(top + G(SnakesGeo.SQ * 2f, SnakesGeo.SQ * 1.5f), listOf(col, Gold, Color.White), if (e.value == 1) 4 else 2, 2.2f)
                        app.haptic(Haptic.Medium)
                        stage.wait(if (e.value == 1) 1.4f else 0.8f)
                    }
                }
                SnakesEventType.ExtraTurn -> { say("Six! Roll again", style.accent, 0.9f); stage.wait(0.15f) }
                SnakesEventType.ThreeSixes -> { say("Three sixes! Next player", Color.White, 1.0f); app.sfx(SfxId.Penalty, 0.6f); stage.wait(0.5f) }
                SnakesEventType.TurnPassed -> stage.wait(0.1f)
                SnakesEventType.MatchEnded -> { rankVersion++; stage.die.visible = false }
            }
        }
        relayout()
    }

    /** Hops square by square ([back] = bouncing back down from 100). */
    private suspend fun walk(pc: PieceSprite, from: Int, to: Int, col: Color, back: Boolean = false) {
        val steps = if (back) (from - 1 downTo to).toList() else (from + 1..to).toList()
        pc.busy = true
        for ((i, cell) in steps.withIndex()) {
            val last = i == steps.lastIndex
            val dest = SnakesGeo.centre(cell)
            app.hop(i)
            stage.hop(pc, dest, if (last) 0.24f else 0.17f, if (last) 1.2f else 0.85f, {
                if (last) { stage.landingDust(dest, col); app.sfx(SfxId.Land, 0.7f); app.haptic(Haptic.Light) }
            }, anticipate = i == 0, scale = solo)
        }
        pc.busy = false
    }

    /** Up a ladder: rung by rung, sparkling, with a rising sound. */
    private suspend fun climb(pc: PieceSprite, foot: Int, top: Int, col: Color) {
        val a = SnakesGeo.centre(foot); val b = SnakesGeo.centre(top)
        val rungs = ((b - a).length() / (SnakesGeo.SQ * 0.42f)).toInt().coerceAtLeast(2)
        app.sfx(SfxId.Riser, 0.8f)
        say("Ladder! Up to $top", Color(0xFF86EFAC), 1.0f)
        stage.ring(a, 0.2f, 1.6f, 0.4f, Color(0xFFFDE68A), 0.9f)
        pc.busy = true
        stage.tween(0.4f + rungs * 0.07f) { k ->
            val e = smooth(k)
            pc.pos = lerp(a, b, e)
            pc.z = 0.35f * abs(sin(e * rungs * kotlin.math.PI.toFloat())) + 0.3f
            if (stage.chance(0.5f)) stage.emit(PK.Sparkle, pc.pos, pc.z + 0.6f, stage.rnd(-0.6f, 0.6f), stage.rnd(-0.6f, 0.6f), stage.rnd(0.5f, 1.5f), Color(0xFFFDE68A), stage.rnd(0.15f, 0.28f), 0.5f, gravity = 2f)
        }
        pc.pos = b; pc.z = 0f; pc.restPos = b
        pc.impulse(8f); pc.joy = 1f
        stage.homeCelebration(b, col)
        app.haptic(Haptic.Medium)
        pc.busy = false
        stage.wait(0.25f)
    }

    /**
     * Down a snake: the head rears up and opens its mouth, the piece shrinks into it, a bulge in the piece's colour
     * runs down the body, and the piece pops out of the tail.
     */
    private suspend fun slide(pc: PieceSprite, head: Int, tail: Int, col: Color) {
        val b = SnakeBite(head, tail, col)
        val path = b.spine
        bite = b
        say("Snake! Down to $tail", Color(0xFFFCA5A5), 1.3f)
        app.sfx(SfxId.Penalty, 0.7f)
        pc.busy = true
        val start = pc.pos
        val mouth = path[0]
        // Rear up and open wide; the piece trembles.
        stage.shudder(pc, 0.35f, 0.1f)
        stage.tween(0.4f) { k -> val e = smooth(k); b.rear = e; b.open = e }
        // Snap: the piece is pulled into the mouth and the jaws shut on it.
        app.sfx(SfxId.Whoosh, 0.7f)
        val scale0 = pc.scale
        stage.tween(0.22f) { k ->
            val e = k * k
            pc.pos = lerp(start, mouth, e)
            pc.scale = scale0 * (1f - 0.8f * e)
            pc.alpha = 1f - e
            pc.halo = 1f - e
        }
        pc.visible = false
        stage.tween(0.12f) { k -> b.open = 1f - k }
        app.haptic(Haptic.Heavy)
        stage.shake(0.06f, 0.15f)
        // The gulp travels down the body; the head settles back.
        val dur = 0.9f + path.size * 0.008f + (head - tail) * 0.006f
        stage.tween(dur) { k ->
            val e = smooth(k)
            b.gulp = e
            b.rear = (1f - k * 3f).coerceAtLeast(0f)
            val f = e * (path.size - 1)
            val i = f.toInt().coerceAtMost(path.size - 2)
            pc.pos = lerp(path[i], path[i + 1], f - i)
        }
        bite = null
        // Out of the tail.
        val end = path.last()
        pc.pos = end; pc.z = 0f; pc.restPos = end
        pc.visible = true; pc.alpha = 1f; pc.halo = 1f
        pc.scale = solo * 0.4f
        pc.impulse(-9f)
        stage.landingDust(end, col)
        stage.ring(end, 0.2f, 1.4f, 0.35f, col, 0.8f)
        app.sfx(SfxId.Land, 0.8f)
        pc.busy = false
        stage.wait(0.35f)
    }

    /** Throws the die in the tray: it leaps, tumbles in 3D and settles showing [value] (as in Ludo). */
    private suspend fun rollDie(value: Int) {
        val d = stage.die
        val (tx, ty) = when (value) { 1 -> 0f to 0f; 6 -> 180f to 0f; 2 -> 90f to 0f; 5 -> -90f to 0f; 3 -> 0f to -90f; else -> 0f to 90f }
        val tz = stage.rnd(-12f, 12f)
        val turnsX = 360f * (1 + stage.rng.nextInt(2)) * if (stage.chance(0.5f)) 1 else -1
        val turnsY = 360f * (1 + stage.rng.nextInt(2)) * if (stage.chance(0.5f)) 1 else -1
        val sx = d.rx; val sy = d.ry; val sz = d.rz
        val ex = tx + turnsX; val ey = ty + turnsY; val ez = tz + 180f * if (stage.chance(0.5f)) 1 else -1
        val home = d.pos
        var bounced = false
        stage.tween(0.62f) { k ->
            val e = 1f - (1f - k) * (1f - k) * (1f - k)
            d.rx = mix(sx, ex, e); d.ry = mix(sy, ey, e); d.rz = mix(sz, ez, e)
            d.z = if (k < 0.72f) { val q = k / 0.72f; 1.6f * 4f * q * (1f - q) } else { val q = (k - 0.72f) / 0.28f; 0.3f * 4f * q * (1f - q) }
            d.scale = 1.6f + d.z * 0.15f
            if (!bounced && k >= 0.72f) { bounced = true; d.impulse(-8f); app.sfx(SfxId.DiceBounce, 0.7f) }
        }
        d.rx = tx; d.ry = ty; d.rz = tz
        d.z = 0f; d.scale = 1.6f
        d.value = value
        d.impulse(-6f)
        app.sfx(SfxId.DiceBounce, 0.6f)
        app.haptic(Haptic.Light)
        if (value == 6) {
            stage.ring(home, 0.3f, 2.2f, 0.4f, style.accent, 0.9f)
            app.sfx(SfxId.DiceSix, 0.7f)
        }
        stage.wait(0.18f)
    }
}
