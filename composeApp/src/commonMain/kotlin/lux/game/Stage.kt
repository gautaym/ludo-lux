package lux.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import lux.app.AppContext
import lux.theme.BoardStyle
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Everything on the table that moves: pieces, the die, particles and effects, plus the clock and "camera".
 * Game time runs on the display's frames, so pausing, hit-stop freezes and headless screenshots all work the same way.
 * Coroutines (the match loop, capture scenes) wait on [frame] / [wait] / [tween].
 */
class Stage(val app: AppContext, var style: BoardStyle) {
    /** Bumped every frame; the canvas reads it so it redraws. */
    var frameCount by mutableLongStateOf(0L)
        private set

    // ---------------------------------------------------------------- clock

    /** Game seconds since the stage started (stops while paused, slows during hit-stop). */
    var time = 0f
        private set
    var paused = false
    private var hitStop = 0f
    private val waiters = ArrayList<CancellableContinuation<Float>>()
    lateinit var scope: CoroutineScope

    /** Called once per display frame by the screen with the real elapsed seconds. */
    fun onFrame(realDt: Float) {
        val raw = min(realDt, 0.05f)
        var dt = if (paused) 0f else raw
        if (hitStop > 0f) { hitStop -= raw; dt *= 0.04f }
        time += dt
        tick(dt, if (paused) 0f else raw)
        frameCount++
        if (waiters.isNotEmpty()) {
            val ws = ArrayList(waiters)
            waiters.clear()
            for (w in ws) if (w.isActive) w.resume(dt)
        }
    }

    /** Suspends until the next frame; returns that frame's game delta time. */
    suspend fun frame(): Float = suspendCancellableCoroutine { waiters += it }

    suspend fun wait(seconds: Float) {
        var t = 0f
        while (t < seconds) t += frame()
    }

    /** Runs [f] with progress 0..1 over [seconds] of game time; always ends with f(1). */
    suspend fun tween(seconds: Float, f: (Float) -> Unit) {
        var t = 0f
        while (t < seconds) {
            f((t / seconds).coerceIn(0f, 1f))
            t += frame()
        }
        f(1f)
    }

    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }

    /** Brief freeze-frame that sells an impact. Skipped with reduced motion. */
    fun hitStop(realSeconds: Float) { if (!app.settings.reducedMotion) hitStop = realSeconds }

    // ---------------------------------------------------------------- camera

    /** How many quarter turns the board is rotated on screen (so the local player sits bottom-left). */
    var viewTurns = 0
    var focusPoint = G.Centre
        private set
    private var focusTarget = 0f
    var focusAmount = 0f
        private set
    private var shakeAmp = 0f
    private var shakeLeft = 0f
    private var shakeLen = 1f
    var shake = G(0f, 0f)
        private set
    private var punch = 0f
    private var punchVel = 0f
    /** Extra zoom from impacts (springs back). */
    val punchZoom get() = punch * 0.006f

    /** Leans the camera in towards [p] by [amount] (0.16 = 16% closer). Ignored with reduced motion. */
    fun focus(p: G, amount: Float) {
        if (app.settings.reducedMotion) return
        focusPoint = p; focusTarget = amount
    }

    fun unfocus() { focusTarget = 0f }

    fun shake(amp: Float, seconds: Float) {
        if (app.settings.reducedMotion) return
        shakeAmp = max(shakeAmp, amp); shakeLeft = seconds; shakeLen = seconds
    }

    fun punch(strength: Float) { if (!app.settings.reducedMotion) punchVel += strength * 9f }

    /** Darkens the board except a pool of light round [spotAt]. */
    var spotAt = G.Centre
    var spotTarget = 0f
    var spotAlpha = 0f
        private set

    // ---------------------------------------------------------------- things on the table

    val pieces = ArrayList<PieceSprite>()
    val die = DieSprite()
    val particles = ArrayList<Particle>(512)
    val decals = ArrayList<Decal>(64)
    val awards = ArrayList<AwardSprite>()
    /** Landing markers while a human picks a move, and when they last flashed (after an unclear tap). */
    val targets = ArrayList<Target>()
    var targetsFlashAt = -10f
    val rng = Random(Random.nextLong())

    /** Particle multiplier: reduced motion tones effects down. */
    val effectScale get() = if (app.settings.reducedMotion) 0.4f else 1f
    fun count(n: Int) = max(1, (n * effectScale).toInt())

    fun clear() {
        pieces.clear(); particles.clear(); decals.clear(); awards.clear(); targets.clear()
        die.visible = false
        spotTarget = 0f; spotAlpha = 0f; focusTarget = 0f; focusAmount = 0f
        shakeLeft = 0f; shake = G(0f, 0f); hitStop = 0f; punch = 0f; punchVel = 0f
    }

    private fun tick(dt: Float, realDt: Float) {
        focusAmount += (focusTarget - focusAmount) * min(1f, realDt * 7f)
        if (shakeLeft > 0f) {
            shakeLeft -= realDt
            val k = max(0f, shakeLeft / shakeLen)
            val a = shakeAmp * k * k
            shake = G(sin(time * 91f + 1f) * a, sin(time * 77f + 2f) * a)
            if (shakeLeft <= 0f) { shakeAmp = 0f; shake = G(0f, 0f) }
        }
        // Damped spring for the impact zoom.
        punchVel += (-punch * 180f - punchVel * 16f) * realDt
        punch += punchVel * realDt
        spotAlpha += (spotTarget - spotAlpha) * min(1f, realDt * if (spotTarget > spotAlpha) 6f else 3f)

        for (p in pieces) {
            p.tick(dt, time)
            // Motion trail: fading after-images behind fast-moving pieces.
            if (p.trail && p.visible && dt > 0f) {
                p.trailClock += dt
                if (p.trailClock >= 0.035f) {
                    p.trailClock = 0f
                    particles += Particle(PK.Ghost, p.pos.x, p.pos.y, p.z, 0f, 0f, 0f, Color.White.copy(alpha = 0.4f), 1f, 0.22f, tag = p.color)
                }
            }
        }
        die.tick(dt, time)
        for (a in awards) a.age += dt

        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            p.age += dt
            if (p.age >= p.life) { particles[i] = particles[particles.size - 1]; particles.removeAt(particles.size - 1); continue }
            val drag = exp(-p.drag * dt)
            p.vx *= drag; p.vy *= drag; p.vz *= drag
            p.vz -= p.gravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            if (p.z < 0f && p.gravity > 0f) { p.z = 0f; p.vz = -p.vz * 0.35f; p.vx *= 0.6f; p.vy *= 0.6f }
            p.rot += p.spin * dt
            i++
        }
        i = 0
        while (i < decals.size) {
            val d = decals[i]
            d.age += dt
            if (d.age >= d.life) { decals.removeAt(i); continue }
            i++
        }
    }
}

/** A playing piece as drawn. Positions are on the board surface; [z] is height above it, in cells. */
class PieceSprite(val seat: Int, val id: Int, val color: Int, start: G) {
    var pos = start
    var z = 0f
    var scale = 1f
    var restPos = start
    var restScale = 1f
    /** While busy, the sprite is animated by a coroutine and does not glide back to its rest spot. */
    var busy = false
    var visible = true
    var alpha = 1f
    /** Screen-space rotation in degrees (tumbling captured pieces). */
    var spin = 0f
    /** Vertical flip, -1..1 (1 = upright); a 360-degree flip runs it through cos(). */
    var flip = 1f
    /** Lean forward/back in degrees (the kick). */
    var lean = 0f
    var flash = 0f
    var dissolve = 0f
    var shake = G(0f, 0f)
    var selectable = false
    var trail = false
    /** Sword cut: when set, the piece is drawn as two halves. */
    var cut: Cut? = null
    var squash = 0f
    private var squashVel = 0f
    /** Tiny idle sway so pieces feel alive. */
    var idlePhase = Random.nextFloat() * 6.28f
    /** A gentle "relief" sigh on safe squares / celebratory bounce. */
    var joy = 0f
    var trailClock = 0f

    class Cut(var nx: Float, var ny: Float, var apart: Float = 0f, var tilt: Float = 0f, var glow: Float = 1f, var fade: Float = 0f)

    fun impulse(v: Float) { squashVel += v }

    fun place(p: G, s: Float = restScale) { pos = p; restPos = p; scale = s; restScale = s; z = 0f }

    fun tick(dt: Float, time: Float) {
        squashVel += (-squash * 260f - squashVel * 14f) * dt
        squash += squashVel * dt
        flash = max(0f, flash - dt * 2.6f)
        joy = max(0f, joy - dt * 1.4f)
        if (!busy) {
            val k = min(1f, dt * 16f)
            pos = lerp(pos, restPos, k)
            scale += (restScale - scale) * k
            z += (0f - z) * k
        }
    }
}

/** The die: drawn as a real cube projected onto the board (see board/Sprites.kt). */
class DieSprite {
    var visible = false
    var pos = G.Centre
    var z = 0f
    var value = 6
    /** Euler angles in degrees, applied X then Y then Z. */
    var rx = 0f
    var ry = 0f
    var rz = 0f
    var scale = 1f
    var waiting = false
    var accent = Color.White
    var glow = 0f
    var squash = 0f
    private var squashVel = 0f
    private var t = 0f

    fun impulse(v: Float) { squashVel += v }

    fun tick(dt: Float, time: Float) {
        t += dt
        squashVel += (-squash * 300f - squashVel * 15f) * dt
        squash += squashVel * dt
        glow += ((if (waiting) 1f else 0f) - glow) * min(1f, dt * 6f)
    }

    /** Waiting-for-tap breathing, 0..1. */
    fun pulse(time: Float) = (0.5f + 0.5f * sin(time * 2f * PI.toFloat() * 0.9f)) * glow
}

enum class PK { Glow, Sparkle, Streak, Confetti, Smoke, Ember, Shard, Dust, Ghost }

class Particle(
    val kind: PK,
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    val color: Color,
    val size: Float,
    val life: Float,
    var rot: Float = 0f,
    val spin: Float = 0f,
    val gravity: Float = 0f,
    val drag: Float = 0f,
    val endSize: Float = size,
    /** Ghost particles carry the piece colour index. */
    val tag: Int = 0,
) {
    var age = 0f
}

enum class DK { Ring, Flash, Rays, Pillar, Rune, Scorch, StarBurst, Bolt, Shield, Spot, Slash, Sword, Medal }

/** A short-lived effect drawn on (or above) the board. Fields are interpreted per [kind]. */
class Decal(
    val kind: DK,
    var pos: G,
    val life: Float,
    val color: Color,
    val r0: Float = 0f,
    val r1: Float = 1f,
    val alpha: Float = 1f,
    val width: Float = 0.1f,
    val spin: Float = 0f,
    val delay: Float = 0f,
    val z: Float = 0f,
    /** Bolt points (grid x, y, height triples), sword pose, etc. */
    var data: FloatArray? = null,
    /** Follows this piece (shield bubble). */
    val follow: PieceSprite? = null,
) {
    var age = 0f
    /** Progress 0..1 after the delay. */
    val k get() = ((age - delay) / (life - delay)).coerceIn(0f, 1f)
    val started get() = age >= delay
}

/** Where a movable piece would land, shown while the player chooses (tap it to move exactly that piece). */
class Target(val pos: G, val color: Int)

/** Medal (rank 1-3) or the "Loser" sign over a yard. */
class AwardSprite(val at: G, val rank: Int, val loser: Boolean, val color: Color, val soft: Boolean = false) { var age = 0f }
