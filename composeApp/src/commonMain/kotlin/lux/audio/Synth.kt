package lux.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class SfxId {
    UiTap, UiBack, Select, DiceShake, DiceBounce, DiceSix, Hop, Land, Entered,
    BoomCapture, ConfettiCapture, MagicCapture, ThunderCapture, VictimReturn,
    HomeEnter, Victory, TurnPing, NoMove, Penalty,
    SwordDraw, SwordSlash, Kick, Whoosh, Firework, Riser,
    /** A cute little "pip" for every square a piece hops. */
    Pip,
    /** "Uh-oh": a piece has been caught. */
    Caught,
}

/**
 * Every sound effect without a recorded sample is synthesised in code (original, tiny, no downloads).
 * Output is mono 16-bit PCM at [RATE], packed as WAV bytes so any platform player can load it.
 */
object Synth {
    const val RATE = 44100

    /** Variants of [id] as WAV files; empty when the sound comes from a recorded sample instead. */
    fun build(id: SfxId): List<ByteArray> = clips(id).map { wav(finish(it)) }

    private fun clips(id: SfxId): List<FloatArray> = when (id) {
        SfxId.UiTap -> listOf(tone(0.07f, 740f, 980f, 0.35f, 0.004f, 14f, 0f))
        SfxId.UiBack -> listOf(tone(0.08f, 520f, 380f, 0.3f, 0.004f, 14f, 0f))
        SfxId.Select -> listOf(mix(tone(0.09f, 880f, 1240f, 0.3f, 0.003f, 18f, 0f), tone(0.09f, 1320f, 1760f, 0.12f, 0.003f, 22f, 0f)))
        SfxId.DiceShake -> (0 until 3).map { diceRattle(0.34f, 7 + it, 100 + it) }
        SfxId.DiceBounce -> (0 until 3).map { mix(tone(0.22f, 150f - it * 12f, 62f, 0.8f, 0.001f, 16f, 0f), click(0.05f, 0.5f, 3000f, 40 + it), click(0.14f, 0.18f, 700f, 50 + it)) }
        SfxId.DiceSix -> listOf(arp(floatArrayOf(659.3f, 830.6f, 987.8f, 1318.5f), 0.11f, 0.9f))
        SfxId.Hop -> (0 until 3).map { mix(tone(0.06f, 560f + it * 40f, 330f, 0.35f, 0.001f, 40f, 0f), click(0.03f, 0.22f, 2400f, 200 + it)) }
        SfxId.Land -> (0 until 3).map { mix(click(0.04f, 0.7f, 1800f, 300 + it), tone(0.08f, 240f - it * 10f, 150f, 0.45f, 0.001f, 36f, 0f)) }
        SfxId.Entered -> listOf(arp(floatArrayOf(523.3f, 659.3f, 784f), 0.07f, 0.7f))
        SfxId.BoomCapture -> listOf(boom())
        SfxId.ConfettiCapture -> listOf(pop())
        SfxId.MagicCapture -> listOf(magic())
        SfxId.ThunderCapture -> listOf(thunder())
        SfxId.VictimReturn -> listOf(mix(tone(0.45f, 700f, 190f, 0.28f, 0.01f, 5f, 6f), tone(0.45f, 1050f, 290f, 0.12f, 0.01f, 5f, 6f)))
        SfxId.HomeEnter -> listOf(fanfare(floatArrayOf(523.3f, 659.3f, 784f, 1046.5f), 0.1f, 1.0f))
        SfxId.Victory -> listOf(victory())
        SfxId.TurnPing -> listOf(arp(floatArrayOf(880f, 1174.7f), 0.09f, 0.35f))
        SfxId.NoMove -> listOf(mix(tone(0.2f, 300f, 200f, 0.3f, 0.005f, 10f, 0f), tone(0.2f, 296f, 196f, 0.3f, 0.005f, 10f, 0f)))
        SfxId.SwordDraw -> listOf(mix(offset(noise(0.35f, 0.35f, 7000f, 7f, 90, 0.08f), 0f, 0.7f), offset(bell(2637f, 0.6f, 0.12f), 0.12f, 0.7f), offset(bell(3951f, 0.5f, 0.07f), 0.12f, 0.7f)))
        SfxId.SwordSlash -> listOf(swordSlash())
        SfxId.Kick -> listOf(mix(offset(tone(0.25f, 150f, 45f, 1.0f, 0.001f, 12f, 0f), 0f, 0.6f), offset(click(0.04f, 1.0f, 2200f, 93), 0f, 0.6f), offset(whoosh(0.4f, 94), 0.03f, 0.6f)))
        SfxId.Whoosh -> (0 until 2).map { whoosh(0.32f + it * 0.08f, 60 + it) }
        SfxId.Firework -> (0 until 3).map { firework(70 + it) }
        SfxId.Riser -> listOf(mix(offset(noise(0.9f, 0.4f, 2500f, 0.2f, 95, 0.7f), 0f, 1.3f), offset(tone(0.9f, 220f, 880f, 0.22f, 0.6f, 0.3f, 4f), 0f, 1.3f), offset(bell(1046.5f, 0.6f, 0.3f), 0.85f, 1.3f), offset(bell(1568f, 0.6f, 0.2f), 0.85f, 1.3f)))
        SfxId.Pip -> (0 until 3).map { pip(it) }
        SfxId.Caught -> listOf(uhOh())
        SfxId.Penalty -> listOf(mix(tone(0.5f, 440f, 110f, 0.35f, 0.005f, 4f, 4f), tone(0.5f, 466f, 116f, 0.3f, 0.005f, 4f, 4f)))
    }

    /** Normalise gently and apply a hard safety fade so nothing clicks at the end. */
    private fun finish(d: FloatArray, fadeEnd: Boolean = true): FloatArray {
        var peak = 0f
        for (v in d) peak = max(peak, abs(v))
        val g = if (peak > 0.9f) 0.9f / peak else 1f
        val fade = min(d.size / 8, 200)
        for (i in d.indices) {
            d[i] *= g
            if (fadeEnd && i > d.size - fade) d[i] *= (d.size - i) / fade.toFloat()
        }
        return d
    }

    fun wav(d: FloatArray): ByteArray {
        val dataLen = d.size * 2
        val out = ByteArray(44 + dataLen)
        fun i32(at: Int, v: Int) { for (k in 0 until 4) out[at + k] = (v ushr (8 * k)).toByte() }
        fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        "RIFF".encodeToByteArray().copyInto(out, 0); i32(4, 36 + dataLen)
        "WAVEfmt ".encodeToByteArray().copyInto(out, 8); i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, RATE); i32(28, RATE * 2); i16(32, 2); i16(34, 16)
        "data".encodeToByteArray().copyInto(out, 36); i32(40, dataLen)
        for (i in d.indices) i16(44 + i * 2, (d[i].coerceIn(-1f, 1f) * 32767).toInt())
        return out
    }

    // ---- primitives ----

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun tone(dur: Float, f0: Float, f1: Float, gain: Float, attack: Float, decayRate: Float, vibrato: Float): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val k = t / dur
            val f = lerp(f0, f1, k) * (1f + if (vibrato > 0) sin(t * 38f) * 0.012f * vibrato else 0f)
            phase += 2 * PI * f / RATE
            val env = min(1f, t / max(attack, 1e-4f)) * exp(-t * decayRate)
            d[i] = sin(phase).toFloat() * env * gain
        }
        return d
    }

    private fun click(dur: Float, gain: Float, bright: Float, seed: Int): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        val rng = Random(seed)
        var lp = 0f
        val a = (bright / RATE * 6f).coerceIn(0f, 1f)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val noise = (rng.nextDouble() * 2 - 1).toFloat()
            lp += (noise - lp) * a
            d[i] = (noise - lp * 0.6f) * exp(-t * (6f / dur)) * gain
        }
        return d
    }

    private fun noise(dur: Float, gain: Float, lowpass: Float, decay: Float, seed: Int, attack: Float = 0.002f): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        val rng = Random(seed)
        var lp = 0f
        val a = (lowpass / RATE * 6f).coerceIn(0f, 1f)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            lp += ((rng.nextDouble() * 2 - 1).toFloat() - lp) * a
            d[i] = lp * min(1f, t / attack) * exp(-t * decay) * gain
        }
        return d
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val d = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) d[i] += p[i]
        return d
    }

    private fun offset(data: FloatArray, seconds: Float, totalLen: Float): FloatArray {
        val d = FloatArray((totalLen * RATE).toInt())
        val o = (seconds * RATE).toInt()
        var i = 0
        while (i < data.size && i + o < d.size) { d[i + o] += data[i]; i++ }
        return d
    }

    private fun bell(freq: Float, dur: Float, gain: Float): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        val mult = floatArrayOf(1f, 2.01f, 2.76f, 4.07f)
        val amp = floatArrayOf(1f, 0.45f, 0.25f, 0.12f)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            var s = 0f
            for (h in 0 until 4) s += sin(2 * PI.toFloat() * freq * mult[h] * t) * amp[h] * exp(-t * (3.5f + h * 2.5f))
            d[i] = s * gain * min(1f, t / 0.003f)
        }
        return d
    }

    private fun arp(notes: FloatArray, step: Float, gain: Float): FloatArray {
        val total = notes.size * step + 0.7f
        return mix(*Array(notes.size) { offset(bell(notes[it], 0.65f, gain * 0.5f), it * step, total) })
    }

    private fun fanfare(notes: FloatArray, step: Float, gain: Float): FloatArray {
        val total = notes.size * step + 1.1f
        val parts = MutableList(notes.size) { offset(bell(notes[it], 0.9f, gain * 0.45f), it * step, total) }
        parts += offset(mix(bell(523.3f, 1.0f, gain * 0.3f), bell(659.3f, 1.0f, gain * 0.3f), bell(784f, 1.0f, gain * 0.3f)), notes.size * step, total)
        return mix(*parts.toTypedArray())
    }

    private fun victory(): FloatArray {
        val melody = floatArrayOf(523.3f, 659.3f, 784f, 1046.5f, 784f, 1046.5f, 1318.5f)
        val whenAt = floatArrayOf(0f, 0.14f, 0.28f, 0.42f, 0.72f, 0.86f, 1.0f)
        val total = 2.8f
        val parts = MutableList(melody.size) { offset(bell(melody[it], 1.2f, 0.4f), whenAt[it], total) }
        parts += offset(mix(bell(523.3f, 1.8f, 0.3f), bell(659.3f, 1.8f, 0.3f), bell(784f, 1.8f, 0.3f), bell(1046.5f, 1.8f, 0.3f)), 1.3f, total)
        return mix(*parts.toTypedArray())
    }

    private fun diceRattle(dur: Float, clacks: Int, seed: Int): FloatArray {
        val rng = Random(seed)
        val d = FloatArray((dur * RATE).toInt())
        for (c in 0 until clacks) {
            val at = (rng.nextDouble() * (dur - 0.05f)).toFloat()
            val k = mix(click(0.03f, 0.5f + rng.nextFloat() * 0.3f, 2500f + rng.nextFloat() * 1500f, seed * 7 + c), tone(0.03f, 900f + rng.nextFloat() * 500f, 700f, 0.12f, 0.001f, 60f, 0f))
            val o = (at * RATE).toInt()
            var i = 0
            while (i < k.size && i + o < d.size) { d[i + o] += k[i]; i++ }
        }
        return d
    }

    private fun boom(): FloatArray {
        val total = 0.9f
        return mix(
            offset(tone(0.8f, 110f, 34f, 1.0f, 0.001f, 4.6f, 0f), 0f, total),
            offset(noise(0.5f, 1.1f, 1800f, 7f, 1, 0.001f), 0f, total),
            offset(click(0.05f, 1.2f, 6000f, 9), 0f, total),
            offset(noise(0.7f, 0.5f, 400f, 3.5f, 3, 0.02f), 0.02f, total),
        )
    }

    private fun pop(): FloatArray {
        val total = 1.1f
        val parts = mutableListOf(
            offset(tone(0.12f, 380f, 1500f, 0.6f, 0.001f, 18f, 0f), 0f, total),
            offset(click(0.06f, 1.0f, 5000f, 21), 0f, total),
            offset(noise(0.35f, 0.35f, 4500f, 10f, 5, 0.001f), 0.01f, total),
        )
        val rng = Random(77)
        for (i in 0 until 7) parts += offset(bell(1500f + rng.nextFloat() * 2400f, 0.3f, 0.12f), 0.06f + i * 0.055f, total)
        return mix(*parts.toTypedArray())
    }

    private fun magic(): FloatArray {
        val total = 1.4f
        val parts = mutableListOf(
            offset(noise(1.1f, 0.55f, 1400f, 2.2f, 8, 0.25f), 0f, total),
            offset(tone(1.2f, 300f, 1700f, 0.3f, 0.2f, 1.6f, 6f), 0f, total),
            offset(tone(1.2f, 450f, 2550f, 0.18f, 0.2f, 1.6f, 6f), 0.02f, total),
        )
        val rng = Random(5)
        for (i in 0 until 9) parts += offset(bell(1800f + rng.nextFloat() * 2600f, 0.35f, 0.1f), 0.2f + i * 0.09f, total)
        return mix(*parts.toTypedArray())
    }

    /** A soft, bubbly upward blip (a cartoon footstep), with a tiny bell on top. Pitch rises a little each square. */
    private fun pip(v: Int): FloatArray {
        val f = 820f + v * 45f
        return mix(
            tone(0.09f, f, f * 1.55f, 0.42f, 0.002f, 30f, 0f),
            tone(0.07f, f * 2f, f * 3f, 0.1f, 0.002f, 40f, 0f),
            offset(bell(f * 2.5f, 0.12f, 0.06f), 0.01f, 0.13f),
        )
    }

    /** A sung-sounding note: a few harmonics with a vowel-ish weighting, gliding from [f0] to [f1]. */
    private fun voice(dur: Float, f0: Float, f1: Float, gain: Float): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        val amps = floatArrayOf(1f, 0.75f, 0.5f, 0.22f, 0.12f, 0.06f)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val k = t / dur
            val f = lerp(f0, f1, k * k) * (1f + sin(t * 32f) * 0.01f)
            phase += 2 * PI * f / RATE
            var s = 0f
            for (h in amps.indices) s += sin(phase * (h + 1)).toFloat() * amps[h]
            val env = min(1f, t / 0.025f) * min(1f, (dur - t) / 0.06f).coerceAtLeast(0f)
            d[i] = s * env * gain * 0.3f
        }
        return d
    }

    /** "Uh-oh!": two falling notes, then a little sad "womp" bend. */
    private fun uhOh(): FloatArray {
        val total = 0.9f
        return mix(
            offset(voice(0.17f, 587.3f, 600f, 0.9f), 0f, total),
            offset(voice(0.42f, 440f, 380f, 0.9f), 0.21f, total),
            offset(tone(0.35f, 180f, 120f, 0.25f, 0.02f, 5f, 0f), 0.22f, total),
        )
    }

    /**
     * The menu tune: a cheerful, gentle loop (C - G - Am - F, twice) of bell melody, soft chords, a plucked bass and a
     * light shaker. Built so its end runs straight into its start, for endless looping. About 18 s, mono.
     */
    fun music(): ByteArray {
        val bpm = 108f
        val beat = 60f / bpm
        val bars = 8
        val len = (bars * 4 * beat * RATE).toInt()
        val out = FloatArray(len)
        fun put(sound: FloatArray, at: Float, gain: Float = 1f) {
            val o = (at * RATE).toInt()
            for (i in sound.indices) out[(o + i) % len] += sound[i] * gain
        }
        fun midi(m: Int) = 440f * 2f.pow((m - 69) / 12f)
        // Chords (MIDI) and roots, one per bar.
        val chords = listOf(intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60))
        val roots = intArrayOf(36, 43, 45, 41)
        // Melody: (bar, beat in bar, MIDI note).
        val tune = listOf(
            0 to 0f to 76, 0 to 1f to 79, 0 to 1.5f to 81, 0 to 2f to 79, 0 to 3f to 76,
            1 to 0f to 74, 1 to 1f to 76, 1 to 1.5f to 74, 1 to 2f to 71, 1 to 3f to 67,
            2 to 0f to 72, 2 to 1f to 76, 2 to 1.5f to 81, 2 to 2f to 79, 2 to 3f to 76,
            3 to 0f to 77, 3 to 1f to 76, 3 to 2f to 74, 3 to 3f to 72,
            4 to 0f to 76, 4 to 0.5f to 79, 4 to 1f to 84, 4 to 2f to 79, 4 to 3f to 81,
            5 to 0f to 79, 5 to 1f to 74, 5 to 1.5f to 76, 5 to 2f to 74, 5 to 3f to 71,
            6 to 0f to 72, 6 to 0.5f to 76, 6 to 1f to 81, 6 to 2f to 84, 6 to 3f to 83,
            7 to 0f to 81, 7 to 1f to 79, 7 to 2f to 77, 7 to 2.5f to 76, 7 to 3f to 74, 7 to 3.5f to 67,
        )
        for ((bb, note) in tune) {
            val (bar, b) = bb
            val f = midi(note)
            val at = (bar * 4 + b) * beat
            put(bell(f, 0.7f, 0.3f), at)
            put(tone(0.35f, f, f, 0.1f, 0.004f, 7f, 0f), at)
        }
        for (bar in 0 until bars) {
            val ch = chords[bar % 4]
            val start = bar * 4 * beat
            // Soft chord pad, swelling in over the bar.
            for (m in ch) put(pad(midi(m), 4 * beat + 0.3f, 0.055f), start)
            // Plucked bass on beats 1 and 3, a passing fifth on the "and" of 4.
            put(tone(0.45f, midi(roots[bar % 4]), midi(roots[bar % 4]), 0.4f, 0.004f, 6f, 0f), start)
            put(tone(0.45f, midi(roots[bar % 4]), midi(roots[bar % 4]), 0.32f, 0.004f, 6f, 0f), start + 2 * beat)
            put(tone(0.25f, midi(roots[bar % 4] + 7), midi(roots[bar % 4] + 7), 0.22f, 0.004f, 9f, 0f), start + 3.5f * beat)
            // Light kick on 1 and 3, shaker on the off-beats.
            for (k in listOf(0f, 2f)) put(tone(0.16f, 120f, 50f, 0.35f, 0.001f, 18f, 0f), start + k * beat)
            for (k in 0 until 4) put(noise(0.06f, 0.07f, 9000f, 40f, 500 + bar * 4 + k), start + (k + 0.5f) * beat)
        }
        return wav(finish(out, fadeEnd = false))
    }

    /** A warm sustained chord note: a sine and a slightly detuned one, slow in, slow out. */
    private fun pad(freq: Float, dur: Float, gain: Float): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val env = min(1f, t / 0.5f) * min(1f, (dur - t) / 0.4f).coerceAtLeast(0f)
            d[i] = (sin(2 * PI.toFloat() * freq * t) + 0.6f * sin(2 * PI.toFloat() * freq * 1.004f * t) + 0.2f * sin(4 * PI.toFloat() * freq * t)) * env * gain
        }
        return d
    }

    /** Band-passed noise sweeping in brightness: the air rushing past a fast object. */
    private fun whoosh(dur: Float, seed: Int): FloatArray {
        val n = (dur * RATE).toInt()
        val d = FloatArray(n)
        val rng = Random(seed)
        var lp = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val k = t / dur
            val bright = lerp(600f, 4200f, sin(k * PI.toFloat()))
            val a = (bright / RATE * 6f).coerceIn(0f, 1f)
            lp += ((rng.nextDouble() * 2 - 1).toFloat() - lp) * a
            lp2 += (lp - lp2) * a * 0.5f
            d[i] = (lp - lp2) * sin(k * PI.toFloat()) * 0.9f
        }
        return d
    }

    private fun swordSlash(): FloatArray {
        val total = 1.0f
        return mix(
            offset(whoosh(0.18f, 91), 0f, total),
            offset(click(0.05f, 1.1f, 7500f, 92), 0.12f, total),
            offset(bell(1760f, 0.8f, 0.22f), 0.12f, total),
            offset(bell(2793f, 0.7f, 0.14f), 0.12f, total),
            offset(tone(0.3f, 180f, 60f, 0.6f, 0.001f, 9f, 0f), 0.12f, total),
        )
    }

    private fun firework(seed: Int): FloatArray {
        val total = 1.3f
        val rng = Random(seed)
        val parts = mutableListOf(
            offset(tone(0.35f, 900f, 2400f, 0.12f, 0.02f, 2f, 0f), 0f, total),     // launch whistle
            offset(noise(0.5f, 0.9f, 900f, 6f, seed, 0.001f), 0.36f, total),          // burst
            offset(tone(0.4f, 90f, 40f, 0.6f, 0.001f, 8f, 0f), 0.36f, total),
        )
        for (i in 0 until 12) parts += offset(click(0.02f, 0.35f, 6000f, seed * 31 + i), 0.45f + rng.nextFloat() * 0.7f, total) // crackle
        return mix(*parts.toTypedArray())
    }

    private fun thunder(): FloatArray {
        val total = 1.8f
        val parts = mutableListOf(
            offset(click(0.08f, 1.4f, 7000f, 31), 0f, total),
            offset(noise(0.25f, 1.0f, 5200f, 11f, 41, 0.001f), 0f, total),
            offset(noise(1.5f, 0.9f, 260f, 1.8f, 42, 0.04f), 0.06f, total),
            offset(tone(1.3f, 62f, 38f, 0.7f, 0.05f, 2.2f, 0f), 0.08f, total),
        )
        val rng = Random(13)
        for (i in 0 until 6) parts += offset(click(0.035f, 0.5f, 5000f, 60 + i), 0.05f + rng.nextFloat() * 0.35f, total)
        return mix(*parts.toTypedArray())
    }
}
