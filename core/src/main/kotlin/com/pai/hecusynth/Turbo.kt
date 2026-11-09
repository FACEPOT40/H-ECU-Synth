package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turbo whistle and blow-off valve, each switched on its own (Pai, 9 Nov: "for fun"). Shaped
 * by measuring a clip Pai sent (an HKS blow-off on a Subaru, plus the whistle in a turbo clip;
 * measured only, never copied or shipped):
 *  - Whistle: a clean tone that climbs from ~3 to ~7.7 kHz in ~0.8 s as the turbo spools up,
 *    and slides back to ~2.6 kHz over ~1.2 s as it spins down. Here: pitch = 2.5 + 5.5 kHz x
 *    turbo speed.
 *  - Blow-off: a narrow ~6.5 kHz "tsss" (80-90 % of it in a ~150 Hz wide band), up in 15-20 ms,
 *    then chattering as it fades over ~150 ms.
 * Pai liked both. The blow-off's pitch can be moved ([blowOffPitch]; Pai wanted a higher one
 * like an SQV-type valve). Three tries at a separate "whistle" blow-off all sounded like a
 * bell to Pai, so it's this same sound, only shifted in pitch. (A turbo flutter was tried and dropped: not enough reference samples to get
 * it right, Pai's call.)
 * Plain Kotlin, driven by [Synth].
 */
class Turbo(private val sr: Double, private val rnd: () -> Double) {
    /** How fast the turbo spins, 0..1 (sets the whistle pitch and loudness). */
    var speed = 0.0
        private set
    /** Boost pressure built up, 0..1 (what a blow-off lets out). */
    var pressure = 0.0
        private set

    private var whistlePh = 0.0
    private var wobble = 0.0

    // blow-off
    private var bovT = -1.0             // seconds into the blow-off, < 0 = none
    private var bovSize = 0.0
    private var chatter = 0.0
    private var chatter2 = 0.0
    private val bovBand = Svf().apply { set(6500.0, 25.0, sr) }
    private var bovLevel = 1.0

    /** Blow-off pitch in Hz: 6500 = the original sound; Pai picked 8000 (the app default, set
     *  by [Synth]). Takes effect at the next blow-off. */
    var blowOffPitch = 6500.0

    /** Throttle let off: with [blowOff] on, the valve lets the boost out. Every let-off after
     *  some throttle gives one (Pai: "every time we let off the throttle"), bigger after a
     *  long pull. */
    fun snap(blowOff: Boolean) {
        val p = pressure
        if (p < 0.1 || !blowOff) return
        bovT = 0.0
        bovSize = 0.35 + 0.65 * p
        val hz = blowOffPitch.coerceIn(PITCH_MIN, PITCH_MAX)
        bovBand.set(hz, 25.0, sr)                       // same sharpness, only the pitch moves
        bovLevel = kotlin.math.sqrt(6500.0 / hz)        // a higher band holds more noise: same loudness
        pressure = 0.0
    }

    /**
     * One sample. [load] 0..1 throttle, [revs] 0..1 of the style's rev range, [whistle] = the
     * whistle switch. Returns the sound to add after the engine's saturation.
     */
    fun next(load: Double, revs: Double, running: Boolean, whistle: Boolean): Double {
        // spool: up with throttle and revs (~0.6 s), down when let go (~0.45 s: clip ~7.7 -> 2.6 kHz in ~1.2 s)
        val target = if (running) (0.12 + 0.88 * ((revs - 0.1) / 0.6).coerceIn(0.0, 1.0)) * (0.1 + 0.9 * load) else 0.0
        speed += (target - speed) / ((if (target > speed) 0.6 else 0.45) * sr)
        // boost builds quickly with throttle (more with turbo speed), leaks away slowly when
        // it's shut. (It used to need high revs and a long pull, so only the limiter got a
        // blow-off.)
        val pTarget = if (load > 0.2) load * (0.4 + 0.6 * speed) else 0.0
        pressure += (pTarget - pressure) / ((if (pTarget > pressure) 0.15 else 1.5) * sr)

        var out = 0.0

        // whistle
        if (whistle && speed > 0.01) {
            wobble += (rnd() * 2 - 1 - wobble) * 0.001
            val hz = (2500 + 5500 * speed) * (1 + 0.004 * wobble)
            whistlePh += hz / sr
            if (whistlePh >= 1) whistlePh -= 1.0
            val w = sin(2 * PI * whistlePh) + 0.15 * sin(4 * PI * whistlePh)
            out += w * WHISTLE * speed.pow(1.5) * (0.35 + 0.65 * load)
        }

        // blow-off: up in 15 ms, fades over ~150 ms, chattering
        if (bovT >= 0) {
            val env = if (bovT < 0.015) bovT / 0.015 else exp(-(bovT - 0.015) / 0.06)
            chatter += (rnd() * 2 - 1 - chatter) * 0.01    // valve chatter, a few tens per second
            chatter2 += (chatter - chatter2) * 0.01
            val mod = (0.6 + 4.0 * chatter2).coerceIn(0.15, 1.3)
            bovBand.process(rnd() * 2 - 1)
            out += bovBand.band * env * mod * bovSize * BLOW_OFF * bovLevel
            bovT += 1 / sr
            if (bovT > 0.4) bovT = -1.0
        }
        return out
    }

    companion object {
        // levels next to the engine (calibrated with TurboTest / the demo WAVs)
        const val WHISTLE = 0.35
        const val BLOW_OFF = 2.4
        const val PITCH_MIN = 5000.0
        const val PITCH_MAX = 11000.0
    }
}
