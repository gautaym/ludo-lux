package lux.game

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import lux.app.CaptureStyle
import lux.app.Haptic
import lux.audio.SfxId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Everything a capture scene needs. Positions are on the board surface. */
class CaptureContext(
    val attacker: PieceSprite,
    val victim: PieceSprite,
    /** The square being landed on. */
    val impact: G,
    /** Where the victim ends up (its base spot). */
    val victimHome: G,
    val attackerColor: Color,
    val victimColor: Color,
    /** Victim's board colour (0-3), piece id and the progress it was captured at (for retracing its path). */
    val victimBoardColor: Int,
    val victimPiece: Int,
    val victimProgress: Int,
)

private const val TAU = (PI * 2).toFloat()

/**
 * Plays a capture with cinematic framing: the rest of the board dims under a soft spotlight on the square and the
 * camera leans in slightly, then both ease back. Each scene runs about 1.5 s.
 */
suspend fun Stage.playCapture(style: CaptureStyle, c: CaptureContext) {
    spotAt = c.impact
    spotTarget = 0.42f
    focus(c.impact, 0.07f)
    when (style) {
        CaptureStyle.Boom -> boomCapture(c)
        CaptureStyle.Confetti -> confettiCapture(c)
        CaptureStyle.Smoke -> smokeCapture(c)
        CaptureStyle.Zap -> zapCapture(c)
        CaptureStyle.Sword -> swordCapture(c)
        CaptureStyle.Kick -> kickCapture(c)
        CaptureStyle.Rewind -> rewindCapture(c)
    }
    spotTarget = 0f
    unfocus()
}

/** Sends an extra victim of a multi-capture spinning home. */
suspend fun Stage.returnHome(v: PieceSprite, from: G, to: G) {
    fling(v, from, to, 0.85f, 2.4f, 1.5f)
    landingDust(to, Color.White)
    app.sfx(SfxId.Land, 0.6f)
}

private fun Stage.victimArrives(c: CaptureContext, strength: Float) {
    landingDust(c.victimHome, c.victimColor)
    c.victim.impulse(8f * strength)
    app.sfx(SfxId.Land, 0.8f)
    app.sfx(SfxId.VictimReturn, 0.7f)
    app.haptic(Haptic.Light)
}

private fun Stage.dizzyStars(at: G) {
    repeat(count(6)) { i ->
        val a = i / 6f * TAU
        emit(PK.Sparkle, at + G(cos(a), sin(a)) * 0.4f, 1.1f, -sin(a) * 1.6f, cos(a) * 1.6f, 0.1f, Color(1f, 0.9f, 0.35f), 0.3f, 0.75f)
    }
}

// ================================================================ 1. Boom: a slam and a shockwave

private suspend fun Stage.boomCapture(c: CaptureContext) {
    val hot = Color(1f, 0.62f, 0.2f)
    val g = c.impact
    // Anticipation: the victim senses it and trembles, the attacker coils.
    shudder(c.victim, 0.24f, 0.05f)
    c.victim.flash = 0.55f
    c.attacker.impulse(10f)
    wait(0.12f)
    c.victim.flash = 0.8f
    wait(0.1f)
    hop(c.attacker, g, 0.34f, 1.8f, {
        flash(g, 4.2f, 0.28f, hot.lighten(0.5f))
        ring(g, 0.2f, 3.6f, 0.5f, hot)
        ring(g, 0.1f, 2.3f, 0.36f, Color.White, 0.9f)
        ring(g, 0.2f, 5.0f, 0.8f, c.attackerColor.lighten(0.2f), 0.55f, 0.05f)
        burst(PK.Dust, g, 0.05f, 16, 2.6f, 0.9f, 0.3f, 0.75f, Color(1f, 0.9f, 0.8f, 0.55f))
        shards(g, c.victimColor, 16, 4.2f, 0.26f)
        shards(g, c.victimColor.lighten(0.6f), 6, 3.2f, 0.16f)
        repeat(count(26)) {
            val a = rng.nextFloat() * TAU
            val s = rnd(3f, 7f)
            emit(PK.Streak, g, 0.3f, cos(a) * s, sin(a) * s, rnd(0.2f, 1f) * s * 0.3f, lerp(hot, Color.White, rng.nextFloat()), rnd(0.1f, 0.2f), rnd(0.35f, 0.7f), drag = 2f)
        }
        shake(0.26f, 0.5f)
        punch(2.4f)
        hitStop(0.075f)
        app.sfx(SfxId.BoomCapture)
        app.haptic(Haptic.Heavy)
    })
    // Victim is blasted back to its yard.
    c.victim.flash = 1f
    c.victim.impulse(-6f)
    wait(0.05f)
    fling(c.victim, g, c.victimHome, 0.95f, 3.6f, 2f)
    victimArrives(c, 1f)
    wait(0.12f)
}

// ================================================================ 2. Confetti: a party popper, the victim boings home

private suspend fun Stage.confettiCapture(c: CaptureContext) {
    val g = c.impact
    val pal = listOf(
        c.attackerColor, c.victimColor, Color(1f, 0.85f, 0.2f), Color.White,
        Color(1f, 0.35f, 0.7f), Color(0.25f, 0.85f, 1f), Color(0.6f, 1f, 0.3f),
    )
    c.victim.impulse(-7f)
    wait(0.10f)
    c.attacker.impulse(8f)
    wait(0.10f)
    hop(c.attacker, g, 0.3f, 1.3f, {
        flash(g, 2.8f, 0.2f, Color(1f, 0.95f, 0.8f), 0.8f)
        ring(g, 0.15f, 2.4f, 0.55f, Color(1f, 0.85f, 0.35f), 0.9f)
        ring(g, 0.1f, 1.5f, 0.4f, Color(1f, 0.4f, 0.75f), 0.8f, 0.05f)
        confetti(g, pal, 95, 4.8f, 7.2f)
        repeat(count(22)) {
            val a = rng.nextFloat() * TAU
            val s = rnd(1.2f, 3.4f)
            emit(PK.Sparkle, g, 0.4f, cos(a) * s, sin(a) * s, rnd(0.8f, 1.8f) * s, pal[rng.nextInt(pal.size)], rnd(0.3f, 0.5f), rnd(0.8f, 1.4f), gravity = 3f, drag = 1.5f)
        }
        burst(PK.Dust, g, 0.05f, 6, 1.4f, 0.6f, 0.25f, 0.5f, Color(1f, 1f, 1f, 0.35f))
        shake(0.07f, 0.25f)
        punch(1.6f)
        app.sfx(SfxId.ConfettiCapture)
        app.haptic(Haptic.Medium)
    })
    // The victim boings home across the board in three shrinking bounces, spinning, with dizzy stars.
    val v = c.victim
    v.busy = true
    v.impulse(9f)
    var from = g
    val heights = floatArrayOf(1.9f, 1.0f, 0.45f)
    val fractions = floatArrayOf(0.5f, 0.82f, 1f)
    val durs = floatArrayOf(0.38f, 0.28f, 0.2f)
    for (b in 0 until 3) {
        val to = lerp(g, c.victimHome, fractions[b])
        val f0 = from
        tween(durs[b]) { k ->
            v.pos = lerp(f0, to, k)
            v.z = 4f * heights[b] * k * (1f - k)
            v.spin = 360f * (b + k) / 3f
        }
        from = to
        v.impulse(8f - b * 1.5f)
        landingDust(to, c.victimColor)
        app.sfx(SfxId.Hop, 0.8f, 1.2f - b * 0.12f)
    }
    v.spin = 0f
    v.pos = c.victimHome; v.restPos = c.victimHome; v.z = 0f
    v.busy = false
    app.haptic(Haptic.Light)
    app.sfx(SfxId.VictimReturn, 0.6f)
    dizzyStars(c.victimHome)
    wait(0.15f)
}

// ================================================================ 3. Magic smoke: dissolves and drifts home as a wisp

private suspend fun Stage.smokeCapture(c: CaptureContext) {
    val g = c.impact
    val violet = Color(0.62f, 0.35f, 1f)
    val teal = Color(0.3f, 0.95f, 0.9f)
    decal(DK.Rune, g, 0.4f, 1.55f, 1.1f, violet, 0.95f, 70f)
    decal(DK.Rune, g, 0.3f, 1.0f, 1.1f, teal, 0.6f, -110f, 0.05f)
    app.sfx(SfxId.MagicCapture, 0.9f)
    app.haptic(Haptic.Light)
    c.victim.impulse(-4f)
    hop(c.attacker, g, 0.42f, 1.0f, {
        ring(g, 0.15f, 1.9f, 0.55f, violet, 0.8f)
        burst(PK.Smoke, g, 0.1f, 6, 0.8f, 0.9f, 0.5f, 1.1f, Color(0.55f, 0.35f, 0.85f, 0.5f))
        punch(0.8f)
    })
    val v = c.victim
    v.busy = true
    tween(0.62f) { k ->
        v.dissolve = smooth(k) * 0.97f
        if (chance(0.9f)) {
            val a = rng.nextFloat() * TAU
            val r = rnd(0.1f, 0.45f)
            emit(PK.Ember, g + G(cos(a), sin(a)) * r, rnd(0.2f, 1f), -sin(a) * 0.9f, cos(a) * 0.9f, 1.3f, lerp(violet, teal, rng.nextFloat()), rnd(0.1f, 0.2f), rnd(0.7f, 1.2f))
        }
        if (chance(0.4f)) emit(PK.Smoke, g + G(rnd(-0.3f, 0.3f), rnd(-0.3f, 0.3f)), rnd(0.1f, 0.8f), 0f, 0f, rnd(0.4f, 0.9f), Color(0.6f, 0.4f, 0.9f, 0.45f), rnd(0.35f, 0.5f), rnd(0.9f, 1.4f), endSize = 0.9f)
        if (chance(0.5f)) emit(PK.Sparkle, g + G(rnd(-0.5f, 0.5f), rnd(-0.5f, 0.5f)), rnd(0.3f, 1f), 0f, 0f, 1.5f, lerp(Color.White, teal, rng.nextFloat()), rnd(0.18f, 0.3f), rnd(0.5f, 0.9f))
    }
    v.visible = false
    burst(PK.Smoke, g, 0.4f, 10, 1.4f, 1.1f, 0.6f, 1.2f, Color(0.5f, 0.3f, 0.8f, 0.5f))
    // A wisp carries the soul home along a curved path.
    val side = G(-(c.victimHome - g).normalized().y, (c.victimHome - g).normalized().x)
    val mid = lerp(g, c.victimHome, 0.5f) + side * 1.3f
    tween(0.85f) { k ->
        val e = smooth(k)
        val p = quad(g, mid, c.victimHome, e)
        val zz = 0.5f + 2.6f * 4f * e * (1f - e) * 0.5f
        emit(PK.Glow, p, zz, 0f, 0f, 0f, violet.lighten(0.4f), 0.75f, 0.18f, endSize = 0.2f)
        emit(PK.Ember, p, zz, rnd(-0.4f, 0.4f), rnd(-0.4f, 0.4f), rnd(-0.4f, 0.4f), lerp(violet, teal, rng.nextFloat()), rnd(0.1f, 0.2f), rnd(0.4f, 0.8f))
        if (chance(0.5f)) emit(PK.Smoke, p, zz, rnd(-0.3f, 0.3f), rnd(-0.3f, 0.3f), 0f, Color(0.55f, 0.4f, 0.9f, 0.3f), 0.3f, 0.7f, endSize = 0.6f)
    }
    // Re-materialise at home.
    decal(DK.Rune, c.victimHome, 0.3f, 1.3f, 0.9f, teal, 0.9f, 90f)
    ring(c.victimHome, 0.1f, 1.8f, 0.5f, Color.White, 0.7f)
    burst(PK.Smoke, c.victimHome, 0.15f, 8, 1.6f, 0.8f, 0.45f, 0.9f, Color(0.7f, 0.55f, 1f, 0.45f))
    burst(PK.Sparkle, c.victimHome, 0.5f, 18, 1.8f, 0.4f, 0.3f, 1.0f, lerp(Color.White, teal, 0.5f), 2.2f)
    v.pos = c.victimHome; v.restPos = c.victimHome; v.z = 0f
    v.visible = true
    tween(0.5f) { k ->
        v.dissolve = mix(0.97f, 0f, smooth(k))
        if (chance(0.7f)) emit(PK.Ember, c.victimHome + G(rnd(-0.35f, 0.35f), rnd(-0.35f, 0.35f)), rnd(0.2f, 0.8f), 0f, 0f, 0.8f, teal, 0.14f, 0.6f)
    }
    v.dissolve = 0f
    v.busy = false
    victimArrives(c, 0.6f)
    wait(0.1f)
}

// ================================================================ 4. Zap: charged, struck by lightning, zapped home

private suspend fun Stage.zapCapture(c: CaptureContext) {
    val g = c.impact
    val electric = Color(0.55f, 0.78f, 1f)
    app.haptic(Haptic.Light)
    spotTarget = 0.55f
    val hopJob = launch { hop(c.attacker, g, 0.4f, 1.0f) }
    var nextArc = 0f
    var t = 0f
    val charge = 0.65f
    while (t < charge) {
        val k = t / charge
        c.victim.shake = G(sin(t * 70f), cos(t * 61f)) * (0.025f * k)
        if (t >= nextArc) { nextArc = t + mix(0.14f, 0.05f, k); arc(g, 0.45f, 0.9f, 0.18f, electric) }
        if (chance(0.6f)) emit(PK.Sparkle, g + G(rnd(-0.5f, 0.5f), rnd(-0.5f, 0.5f)), rnd(0.3f, 0.9f), 0f, 0f, rnd(0.4f, 1.2f), lerp(electric, Color.White, rng.nextFloat()), rnd(0.14f, 0.26f), rnd(0.3f, 0.6f))
        t += frame()
    }
    hopJob.join()
    // The strike: one bright pulse (no strobing), forked bolt, scorch mark, shockwave.
    val top = g + G(0.7f, 0.6f)
    bolt(top, 11f, g, 0.7f, 0.3f, electric, 0.3f, 1.1f, 5)
    bolt(top + G(0.4f, -0.2f), 8f, g + G(1.4f, -0.9f), 0.05f, 0.22f, electric, 0.12f, 0.6f, 4)
    bolt(top + G(-0.3f, 0f), 5.5f, g + G(-1.5f, 0.7f), 0.05f, 0.2f, electric, 0.1f, 0.6f, 4)
    flash(g, 5.2f, 0.32f, lerp(electric, Color.White, 0.6f), 1f, 0.7f)
    decal(DK.Scorch, g, 0.6f, 1.2f, 1.8f, Color.White, 0.85f)
    ring(g, 0.2f, 3.2f, 0.5f, electric)
    ring(g, 0.1f, 2.0f, 0.35f, Color.White, 0.8f)
    repeat(count(30)) {
        val a = rng.nextFloat() * TAU
        val s = rnd(3f, 8f)
        emit(PK.Streak, g, 0.2f, cos(a) * s, sin(a) * s, rnd(0.3f, 1.3f) * s * 0.3f, lerp(electric, Color.White, rng.nextFloat() * 0.7f), rnd(0.08f, 0.14f), rnd(0.3f, 0.6f), drag = 2f)
    }
    burst(PK.Smoke, g, 0.2f, 6, 0.7f, 0.7f, 0.5f, 1.2f, Color(0.2f, 0.2f, 0.25f, 0.55f))
    spotTarget = 0.15f
    shake(0.3f, 0.5f)
    punch(2.8f)
    app.sfx(SfxId.ThunderCapture)
    app.haptic(Haptic.Heavy)
    hitStop(0.06f)
    wait(0.07f)
    spotTarget = 0.45f
    // Electrocuted: three slow pulses (no more than 3 per second), arcs and smoke.
    repeat(3) {
        c.victim.flash = 1f
        c.victim.shake = G(rnd(-1f, 1f), rnd(-1f, 1f)) * 0.07f
        arc(g, 0.4f, 0.85f, 0.16f, electric)
        arc(g, 0.35f, 0.7f, 0.16f, Color.White)
        emit(PK.Smoke, g, 0.7f, 0f, 0f, 0.8f, Color(0.25f, 0.25f, 0.3f, 0.45f), 0.35f, 1.1f, endSize = 0.8f)
        wait(0.33f)
    }
    c.victim.shake = G(0f, 0f)
    // It zaps home along a jagged arc.
    bolt(g, 0.6f, c.victimHome, 0.6f, 0.4f, electric, 0.16f, 0.9f, 5)
    app.sfx(SfxId.VictimReturn, 0.8f, 1.3f)
    spotTarget = 0.42f
    fling(c.victim, g, c.victimHome, 0.5f, 1.4f, 0f)
    ring(c.victimHome, 0.1f, 1.6f, 0.4f, electric, 0.8f)
    burst(PK.Sparkle, c.victimHome, 0.5f, 14, 1.6f, 0.3f, 0.3f, 0.8f, lerp(electric, Color.White, 0.5f), 2f)
    victimArrives(c, 0.8f)
    wait(0.1f)
}

// ================================================================ 5. Sword: the piece is sliced clean in half

private suspend fun Stage.swordCapture(c: CaptureContext) {
    val g = c.impact
    val steel = Color(0.65f, 0.85f, 1f)
    val v = c.victim
    // The sword is drawn in screen space above the victim: data = [angle, scale, alpha, trailFromAngle].
    val pose = floatArrayOf(108f, 0.2f, 1f, 108f)
    val sword = Decal(DK.Sword, g, 99f, steel, data = pose)
    decals += sword
    app.sfx(SfxId.SwordDraw, 0.9f)
    shudder(v, 0.4f, 0.035f)
    v.flash = 0.5f
    flash(g, 2.2f, 0.25f, steel, 0.8f, 2.4f)
    tween(0.18f) { k ->
        pose[0] = 108f; pose[3] = 108f; pose[1] = mix(0.2f, 1f, smooth(k))
        if (chance(0.6f)) emit(PK.Sparkle, g + G(rnd(-0.8f, 0.8f), rnd(-0.4f, 0.4f)), rnd(1.4f, 2.8f), rnd(-0.6f, 0.6f), rnd(-0.6f, 0.6f), rnd(-0.6f, 0.6f), lerp(steel, Color.White, rng.nextFloat()), rnd(0.2f, 0.35f), 0.4f)
    }
    // Wind-up.
    tween(0.08f) { k -> pose[0] = mix(108f, 122f, smooth(k)); pose[3] = pose[0] }
    // The slash: an accelerating sweep straight through the piece.
    app.sfx(SfxId.SwordSlash)
    var cut = false
    tween(0.12f) { k ->
        pose[0] = mix(122f, -78f, k * k * (2f - k))
        pose[3] = 122f
        if (!cut && pose[0] <= 4f) {
            cut = true
            v.cut = PieceSprite.Cut(0.57f, 0.82f)
            bolt(g + G(-1.4f, 1.0f), 0.45f, g + G(1.4f, -1.0f), 0.45f, 0.3f, steel, 0.02f, 0.3f, 0)
            decal(DK.StarBurst, g, 0.2f, 1.6f, 0.25f, Color.White, 0.9f)
            flash(g, 3.4f, 0.22f, lerp(steel, Color.White, 0.6f), 1f, 0.3f)
            repeat(count(30)) {
                val q = lerp(g + G(-1.2f, 0.8f), g + G(1.2f, -0.8f), rng.nextFloat())
                val side = if (chance(0.5f)) 1f else -1f
                val s = rnd(1.5f, 5f)
                emit(PK.Streak, q, 0.45f, 0.57f * s * side, 0.82f * s * side, rnd(0.5f, 3f), lerp(steel, Color.White, rng.nextFloat()), rnd(0.07f, 0.12f), rnd(0.25f, 0.5f), drag = 2f)
            }
            ring(g, 0.15f, 2.2f, 0.35f, Color.White, 0.8f)
            shake(0.18f, 0.35f)
            punch(2f)
            hitStop(0.11f)
            app.haptic(Haptic.Heavy)
        }
    }
    if (!cut) v.cut = PieceSprite.Cut(0.57f, 0.82f)
    // The halves part and topple; the attacker hops on; the sword fades away.
    val atk = launch { hop(c.attacker, g, 0.3f, 1.0f, { landingDust(g, c.attackerColor); app.sfx(SfxId.Land, 0.8f) }) }
    val cutState = v.cut!!
    tween(0.48f) { k ->
        val e = 1f - (1f - k) * (1f - k)
        cutState.apart = 0.8f * e
        cutState.tilt = 84f * e
        cutState.glow = 1f - k
        pose[2] = 1f - clamp01(k * 2.2f)
        pose[3] = pose[0]
        if (chance(0.7f)) emit(PK.Ember, g, rnd(0.1f, 0.7f), rnd(-0.8f, 0.8f), rnd(-0.8f, 0.8f), 0.6f, c.victimColor.lighten(0.5f), rnd(0.08f, 0.14f), 0.5f)
    }
    decals -= sword
    atk.join()
    // The halves crumble into light that streaks home.
    app.sfx(SfxId.VictimReturn, 0.7f, 1.15f)
    v.busy = true
    tween(0.36f) { k ->
        cutState.fade = smooth(k * 1.4f)
        val p = quad(g, lerp(g, c.victimHome, 0.5f), c.victimHome, smooth(k))
        val zz = 0.5f + 2.2f * 4f * k * (1f - k) * 0.5f
        emit(PK.Glow, p, zz, 0f, 0f, 0f, c.victimColor.lighten(0.5f), 0.6f, 0.16f, endSize = 0.2f)
        emit(PK.Ember, p, zz, rnd(-0.5f, 0.5f), rnd(-0.5f, 0.5f), rnd(-0.5f, 0.5f), c.victimColor.lighten(rng.nextFloat() * 0.5f), rnd(0.1f, 0.18f), 0.5f)
    }
    // At home the two halves zip back together with a click.
    v.pos = c.victimHome; v.restPos = c.victimHome; v.z = 0f
    cutState.fade = 0f
    tween(0.2f) { k ->
        val e = k * k
        cutState.apart = 0.45f * (1f - e)
        cutState.tilt = 25f * (1f - e)
        cutState.glow = 0f
    }
    v.cut = null
    v.busy = false
    v.flash = 1f
    ring(c.victimHome, 0.1f, 1.4f, 0.35f, Color.White, 0.8f)
    burst(PK.Sparkle, c.victimHome, 0.5f, 10, 1.4f, 0.3f, 0.3f, 0.6f, c.victimColor.lighten(0.5f), 1.5f)
    victimArrives(c, 0.7f)
    wait(0.08f)
}

// ================================================================ 6. Kick: punted home like a football

private suspend fun Stage.kickCapture(c: CaptureContext) {
    val g = c.impact
    val start = c.attacker.pos
    var dir = (g - start).normalized()
    if (dir.length() < 0.5f) dir = (c.victimHome - g).normalized()
    // Lean is in screen space: tip towards the direction of the kick as seen on screen.
    val screenDir = Geo.rotateVec(dir, viewTurns)
    val leanSign = if (screenDir.x >= 0f) 1f else -1f
    val spot = g - dir * 0.62f
    shudder(c.victim, 0.42f, 0.03f)
    c.victim.flash = 0.4f
    hop(c.attacker, spot, 0.24f, 0.8f, { app.sfx(SfxId.Hop, 0.6f) })
    // Wind-up: rock back, squashing like a spring.
    val a = c.attacker
    a.busy = true
    a.impulse(6f)
    app.sfx(SfxId.Whoosh, 0.6f, 0.8f)
    tween(0.15f) { k -> a.lean = -30f * smooth(k) * leanSign }
    tween(0.05f) { k -> a.lean = mix(-30f, 40f, k) * leanSign }
    // Impact.
    val pow = Color(1f, 0.85f, 0.35f)
    decal(DK.StarBurst, g - dir * 0.2f, 0.3f, 1.5f, 0.28f, pow.lighten(0.4f))
    flash(g, 3f, 0.2f, pow, 0.9f, 0.45f)
    ring(g, 0.15f, 2.4f, 0.4f, pow, 0.9f)
    repeat(count(22)) {
        val s = rnd(2.5f, 7f)
        emit(PK.Streak, g - dir * 0.2f, 0.45f, dir.x * s + rnd(-2f, 2f), dir.y * s + rnd(-2f, 2f), rnd(0.5f, 2.5f), lerp(pow, Color.White, rng.nextFloat()), rnd(0.07f, 0.12f), rnd(0.25f, 0.45f), drag = 2f)
    }
    burst(PK.Dust, g, 0.05f, 8, 1.6f, 0.6f, 0.25f, 0.6f, Color(1f, 0.93f, 0.8f, 0.5f))
    c.victim.flash = 1f
    c.victim.impulse(-10f)
    shake(0.22f, 0.4f)
    punch(2.4f)
    hitStop(0.09f)
    app.sfx(SfxId.Kick)
    app.haptic(Haptic.Heavy)
    // The attacker recovers and steps onto the square while the victim sails away.
    val recover = launch {
        tween(0.22f) { k -> a.lean = mix(40f, 0f, smooth(k)) * leanSign }
        a.lean = 0f
        hop(a, g, 0.18f, 0.4f, { landingDust(g, c.attackerColor) })
    }
    val v = c.victim
    v.busy = true
    v.trail = true
    val first = lerp(g, c.victimHome, 0.86f)
    val back = (g - first).normalized()
    tween(0.62f) { k ->
        v.pos = lerp(g, first, k)
        v.z = 4f * 3.6f * k * (1f - k)
        v.spin = -720f * k * leanSign
        if (chance(0.8f)) emit(PK.Sparkle, v.pos, v.z + 0.3f, rnd(-0.4f, 0.4f), rnd(-0.4f, 0.4f), rnd(-0.4f, 0.4f), c.victimColor.lighten(0.5f), rnd(0.2f, 0.3f), 0.35f)
        repeat(2) {
            emit(PK.Streak, v.pos + G(rnd(-0.35f, 0.35f), rnd(-0.35f, 0.35f)), v.z + 0.3f, back.x * rnd(5f, 8f), back.y * rnd(5f, 8f), 0f, Color(1f, 1f, 1f, 0.7f), 0.06f, 0.18f)
        }
    }
    landingDust(first, c.victimColor)
    app.sfx(SfxId.Land, 0.8f, 1.1f)
    v.impulse(9f)
    tween(0.2f) { k ->
        v.pos = lerp(first, c.victimHome, k)
        v.z = 4f * 0.55f * k * (1f - k)
        v.spin = (-720f - 180f * k) * leanSign
    }
    v.spin = 0f; v.z = 0f
    v.pos = c.victimHome; v.restPos = c.victimHome
    v.trail = false
    v.busy = false
    victimArrives(c, 0.9f)
    dizzyStars(c.victimHome)
    recover.join()
    a.busy = false
    wait(0.06f)
}

// ================================================================ 7. Rewind: back home the way it came

/**
 * The captured piece is "rewound": it hops backwards along exactly the squares it travelled, faster and faster,
 * leaving fading after-images, and drops back into its well. Always about 1.4 s, however far it had come.
 */
private suspend fun Stage.rewindCapture(c: CaptureContext) {
    val g = c.impact
    val tape = Color(0.55f, 0.85f, 1f)
    shudder(c.victim, 0.2f, 0.03f)
    hop(c.attacker, g, 0.3f, 1.2f, {
        flash(g, 2.6f, 0.2f, tape.lighten(0.5f), 0.9f)
        ring(g, 0.15f, 2f, 0.4f, tape, 0.9f)
        punch(1.6f)
        hitStop(0.06f)
        app.sfx(SfxId.Kick, 0.7f, 1.2f)
        app.haptic(Haptic.Medium)
    })
    c.victim.flash = 1f
    app.sfx(SfxId.Whoosh, 0.9f, 0.7f)
    app.sfx(SfxId.VictimReturn, 0.6f, 1.4f)
    decal(DK.Rays, c.victimHome, 0.3f, 1.4f, 1.3f, tape, 0.35f, -200f)
    val path = ArrayList<G>()
    for (p in c.victimProgress - 1 downTo 1) path += Geo.piece(c.victimBoardColor, c.victimPiece, p)
    path += c.victimHome
    val v = c.victim
    v.busy = true
    v.trail = true
    val total = (0.35f + path.size * 0.03f).coerceIn(0.6f, 1.25f)
    // Accelerating: each hop is a little shorter than the last.
    val weights = FloatArray(path.size) { mix(1.6f, 0.6f, if (path.size > 1) it / (path.size - 1f) else 0f) }
    val sum = weights.sum()
    var from = v.pos
    for (i in path.indices) {
        val to = path[i]
        val h = if (i == path.size - 1) 0.9f else 0.28f
        val f0 = from
        tween(total * weights[i] / sum) { k ->
            v.pos = lerp(f0, to, k)
            v.z = 4f * h * k * (1f - k)
        }
        // After-image left on each square, tinted like an old video tape rewinding.
        emit(PK.Ghost, to, 0f, 0f, 0f, 0f, tape.withA(0.55f), 1f, 0.35f, rot = 0f, tag = c.victimBoardColor)
        if (i % 3 == 0) app.sfx(SfxId.Hop, 0.35f, 1.3f + i * 0.02f)
        from = to
    }
    v.trail = false
    v.z = 0f
    v.restPos = c.victimHome
    v.busy = false
    ring(c.victimHome, 0.1f, 1.4f, 0.35f, tape, 0.8f)
    victimArrives(c, 0.8f)
    wait(0.08f)
}
