package lux.game

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import lux.app.Haptic
import lux.audio.SfxId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Effects and piece moves, ported from the Unity Fx / CaptureFxMore classes. Positions are grid points with an
// optional height z (cells); velocities are cells per second.

private const val TAU = (PI * 2).toFloat()

fun smooth(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
fun Color.lighten(t: Float) = lerp(this, Color.White, t)
fun Color.withA(a: Float) = copy(alpha = a.coerceIn(0f, 1f))

val Gold = Color(1f, 0.85f, 0.35f)

fun Stage.rnd(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
fun Stage.chance(p: Float) = rng.nextFloat() < p

fun Stage.emit(
    kind: PK, p: G, z: Float, vx: Float, vy: Float, vz: Float, color: Color, size: Float, life: Float,
    rot: Float = rng.nextFloat() * 360f, spin: Float = 0f, gravity: Float = 0f, drag: Float = 0f, endSize: Float = size, tag: Int = 0,
) {
    if (particles.size > 1400) return
    particles += Particle(kind, p.x, p.y, z, vx, vy, vz, color, size, life, rot, spin, gravity, drag, endSize, tag)
}

/** Particles flying out in all directions. */
fun Stage.burst(kind: PK, p: G, z: Float, n: Int, speed: Float, up: Float, size: Float, life: Float, color: Color, gravity: Float = 0f) {
    repeat(count(n)) {
        val a = rng.nextFloat() * TAU
        val s = speed * rnd(0.4f, 1f)
        emit(kind, p, z, cos(a) * s, sin(a) * s, up * rnd(0.3f, 1f), color, size * rnd(0.7f, 1.2f), life * rnd(0.7f, 1.1f),
            gravity = gravity, drag = 2.2f, endSize = if (kind == PK.Smoke || kind == PK.Dust) size * 2.2f else size * 0.3f)
    }
}

fun Stage.ring(p: G, r0: Float, r1: Float, life: Float, color: Color, alpha: Float = 1f, delay: Float = 0f, width: Float = 0.12f) {
    decals += Decal(DK.Ring, p, life + delay, color, r0, r1, alpha, width, delay = delay)
}

fun Stage.flash(p: G, radius: Float, life: Float, color: Color, alpha: Float = 1f, z: Float = 0f) {
    decals += Decal(DK.Flash, p, life, color, radius * 0.3f, radius, alpha, z = z)
}

fun Stage.decal(kind: DK, p: G, r0: Float, r1: Float, life: Float, color: Color, alpha: Float = 1f, spin: Float = 0f, delay: Float = 0f) {
    decals += Decal(kind, p, life + delay, color, r0, r1, alpha, spin = spin, delay = delay)
}

fun Stage.shards(p: G, color: Color, n: Int, speed: Float, size: Float) {
    repeat(count(n)) {
        val a = rng.nextFloat() * TAU
        val s = speed * rnd(0.35f, 1f)
        emit(PK.Shard, p, 0.3f, cos(a) * s, sin(a) * s, rnd(2f, 5f), color, size * rnd(0.6f, 1.2f), rnd(0.7f, 1.1f),
            spin = rnd(-720f, 720f), gravity = 14f, drag = 1.2f)
    }
}

fun Stage.confetti(p: G, palette: List<Color>, n: Int, speed: Float, up: Float) {
    repeat(count(n)) {
        val a = rng.nextFloat() * TAU
        val s = speed * rnd(0.2f, 1f)
        emit(PK.Confetti, p, 0.4f, cos(a) * s, sin(a) * s, up * rnd(0.5f, 1f), palette[rng.nextInt(palette.size)],
            rnd(0.12f, 0.2f), rnd(1.3f, 2.1f), spin = rnd(-900f, 900f), gravity = 7f, drag = 2.4f)
    }
}

fun Stage.landingDust(p: G, color: Color) {
    burst(PK.Dust, p, 0.05f, 6, 1.1f, 0.3f, 0.16f, 0.45f, lerp(color, Color.White, 0.65f).withA(0.45f))
}

/** A piece leaves its yard on a six: the well erupts in its colour. */
fun Stage.release(well: G, color: Color) {
    val light = color.lighten(0.45f)
    ring(well, 0.1f, 1.3f, 0.35f, light, 0.9f)
    ring(well, 0.05f, 0.8f, 0.25f, Color.White, 0.6f, 0.04f)
    repeat(count(16)) {
        val a = rng.nextFloat() * TAU
        val s = rnd(0.6f, 1.8f)
        emit(PK.Sparkle, well, 0.2f, cos(a) * s, sin(a) * s, rnd(2f, 4f), light.lighten(rng.nextFloat() * 0.5f), rnd(0.2f, 0.4f), rnd(0.5f, 0.8f), gravity = 4f)
    }
}

/** Turning into the home lane: a little gate of light in the seat colour. */
fun Stage.laneEntry(p: G, color: Color) {
    ring(p, 0.2f, 1.0f, 0.35f, color.lighten(0.4f), 0.8f)
    burst(PK.Sparkle, p, 0.3f, 8, 1.2f, 1.6f, 0.22f, 0.5f, color.lighten(0.5f))
}

/** Landing on a safe square: a soft shield bubble wobbles round the piece. */
fun Stage.shieldBubble(piece: PieceSprite, color: Color) {
    decals += Decal(DK.Shield, piece.pos, 0.95f, color.lighten(0.35f), 0.2f, 0.62f, 0.9f, follow = piece)
    piece.joy = 1f
    app.sfx(SfxId.Select, 0.35f, 1.3f)
}

/** First piece out: a little cheer (sparkles and a jump of joy). */
fun Stage.cheer(piece: PieceSprite, color: Color) {
    piece.joy = 1f
    piece.impulse(9f)
    repeat(count(10)) {
        val a = rng.nextFloat() * TAU
        emit(PK.Sparkle, piece.pos, 1.0f, cos(a) * 1.2f, sin(a) * 1.2f, rnd(1f, 2.2f), color.lighten(0.5f), rnd(0.2f, 0.32f), 0.6f, gravity = 3f)
    }
}

fun Stage.lightPillar(p: G, color: Color, life: Float = 0.7f) {
    decals += Decal(DK.Pillar, p, life, color.lighten(0.35f), 0.35f, 0.55f, 0.85f)
}

fun Stage.homeCelebration(p: G, color: Color) {
    ring(p, 0.1f, 1.6f, 0.45f, color.lighten(0.4f), 0.9f)
    ring(p, 0.1f, 1.0f, 0.35f, Gold, 0.8f, 0.06f)
    burst(PK.Sparkle, p, 0.4f, 18, 2.2f, 3f, 0.3f, 0.8f, color.lighten(0.5f), gravity = 4f)
    app.sfx(SfxId.HomeEnter, 0.8f)
}

/** Rockets that climb from [p] and burst in the palette's colours. */
fun Stage.fireworks(p: G, palette: List<Color>, rockets: Int, spread: Float) {
    repeat(count(rockets)) { i ->
        launch {
            wait(i * 0.18f + rnd(0f, 0.1f))
            val at = p + G(rnd(-spread, spread), rnd(-spread, spread) * 0.7f)
            val peak = rnd(2.2f, 3.4f)
            val col = palette[rng.nextInt(palette.size)]
            app.sfx(SfxId.Firework, 0.5f)
            // The climb: a bright head with a sparkling tail.
            tween(0.36f) { k ->
                val zz = peak * (1f - (1f - k) * (1f - k))
                emit(PK.Ember, at, zz, rnd(-0.2f, 0.2f), rnd(-0.2f, 0.2f), -0.8f, Color(1f, 0.9f, 0.7f), 0.1f, 0.35f)
            }
            flash(at, 1.6f, 0.22f, col.lighten(0.5f), 0.9f, peak)
            val n = count(42)
            repeat(n) { j ->
                val a = j * TAU / n + rnd(-0.08f, 0.08f)
                val s = rnd(2.4f, 3.4f)
                val c = if (rng.nextFloat() < 0.25f) Color.White else col
                emit(PK.Ember, at, peak, cos(a) * s, sin(a) * s * 0.75f, sin(a) * s * 0.6f + 0.6f, c, rnd(0.1f, 0.15f), rnd(0.9f, 1.3f), gravity = 2.6f, drag = 1.8f)
            }
            app.haptic(Haptic.Light)
        }
    }
}

/** Jagged bolt from [a] (height za) to [b] (height zb). */
fun Stage.bolt(a: G, za: Float, b: G, zb: Float, width: Float, color: Color, jag: Float, life: Float, branches: Int = 3) {
    val seg = 14
    val pts = FloatArray((seg + 1) * 3)
    val dir = (b - a).normalized()
    val nrm = G(-dir.y, dir.x)
    for (i in 0..seg) {
        val k = i / seg.toFloat()
        val off = if (i == 0 || i == seg) 0f else rnd(-jag, jag) * sin(k * PI.toFloat())
        val p = lerp(a, b, k) + nrm * off
        pts[i * 3] = p.x; pts[i * 3 + 1] = p.y; pts[i * 3 + 2] = mix(za, zb, k) + rnd(-jag, jag) * 0.4f * sin(k * PI.toFloat())
    }
    decals += Decal(DK.Bolt, a, life, color, width = width, data = pts, r1 = branches.toFloat())
}

/** Small electric arcs crawling round [p]. */
fun Stage.arc(p: G, r0: Float, r1: Float, life: Float, color: Color) {
    val a0 = rng.nextFloat() * TAU
    val a1 = a0 + rnd(1.2f, 2.4f)
    val pa = p + G(cos(a0), sin(a0)) * r0
    val pb = p + G(cos(a1), sin(a1)) * r1
    bolt(pa, rnd(0.1f, 0.6f), pb, rnd(0.1f, 0.6f), 0.05f, color, 0.15f, life, 0)
}

// ---------------------------------------------------------------- piece moves

/**
 * Jumps a piece to [to] in [dur] seconds along a parabola [height] cells high, calling [onLand] on touchdown.
 * [anticipate] crouches first; [flip] spins it through a full somersault of that many degrees (it never shrinks or vanishes).
 */
suspend fun Stage.hop(
    pc: PieceSprite, to: G, dur: Float, height: Float, onLand: (() -> Unit)? = null,
    anticipate: Boolean = false, scale: Float = pc.restScale, flip: Float = 0f,
) {
    pc.busy = true
    if (anticipate) { pc.impulse(-6f); wait(0.05f) }
    val from = pc.pos
    val s0 = pc.scale
    tween(dur) { k ->
        pc.pos = lerp(from, to, k)
        pc.z = 4f * height * k * (1f - k)
        pc.scale = mix(s0, scale, k)
        if (flip != 0f) pc.spin = flip * smooth(k)
    }
    pc.pos = to; pc.z = 0f
    if (flip != 0f) pc.spin = 0f
    pc.restPos = to; pc.restScale = scale; pc.scale = scale
    pc.impulse(7f)
    onLand?.invoke()
    pc.busy = false
}

/** Spinning parabolic flight for a captured piece. */
suspend fun Stage.fling(pc: PieceSprite, from: G, to: G, dur: Float, height: Float, turns: Float) {
    pc.busy = true
    pc.trail = true
    tween(dur) { k ->
        pc.pos = lerp(from, to, smooth(k) * 0.4f + k * 0.6f)
        pc.z = 4f * height * k * (1f - k)
        pc.spin = turns * 360f * k
    }
    pc.spin = 0f; pc.trail = false
    pc.pos = to; pc.z = 0f; pc.restPos = to
    pc.busy = false
}

fun Stage.shudder(pc: PieceSprite, seconds: Float, amp: Float) = launch {
    var t = 0f
    while (t < seconds) {
        val a = amp * (0.4f + t / seconds * 0.6f)
        pc.shake = G(sin(t * 95f) * a, cos(t * 83f) * a)
        t += frame()
    }
    pc.shake = G(0f, 0f)
}

fun quad(a: G, b: G, c: G, t: Float): G {
    val u = 1f - t
    return a * (u * u) + b * (2f * u * t) + c * (t * t)
}

fun dist(a: G, b: G) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
fun clamp01(v: Float) = max(0f, min(1f, v))
