package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turbo whistle, turbo flutter and blow-off valve, each switched on its own (Pai, 9 Nov: "for
 * fun"). Shaped by measuring two clips Pai sent (an HKS blow-off on a Subaru, a "turbo flutter"
 * clip; measured only, never copied or shipped):
 *  - Whistle: a clean tone that climbs from ~3 to ~7.7 kHz in ~0.8 s as the turbo spools up,
 *    and slides back to ~2.6 kHz over ~1.2 s as it spins down. Here: pitch = 2.5 + 5.5 kHz x
 *    turbo speed.
 *  - Flutter (compressor surge when the throttle shuts with boost and no blow-off): choppy air
 *    "stu-tu-tu" chuffs, mostly under 1 kHz with a big swing, starting at ~35-40 a second and
 *    slowing to ~12 as the pressure runs out; the whistle is chopped with them.
 *  - Blow-off: a narrow ~6.5 kHz "tsss" (80-90 % of it in a ~150 Hz wide band), up in 15-20 ms,
 *    then chattering as it fades over ~150 ms.
 * Plain Kotlin, driven by [Synth].
 */
class Turbo(private val sr: Double, private val rnd: () -> Double) {
    /** How fast the turbo spins, 0..1 (sets the whistle pitch and loudness). */
    var speed = 0.0
        private set
    /** Boost pressure built up, 0..1 (what a blow-off or flutter lets out). */
    var pressure = 0.0
        private set

    private var whistlePh = 0.0
    private var wobble = 0.0

    // flutter
    private var flutterT = 0.0          // seconds into the flutter
    private var flutterLen = 0.0        // 0 = none
    private var flutterSize = 0.0
    private var chuffPh = 0.0
    private var chuffEnv = 0.0
    private var chuffLp = 0.0
    private val chuffDecay = exp(-1.0 / (0.007 * sr))

    // blow-off
    private var bovT = -1.0             // seconds into the blow-off, < 0 = none
    private var bovSize = 0.0
    private var chatter = 0.0
    private var chatter2 = 0.0
    private val bovBand = Svf().apply { set(6500.0, 25.0, sr) }

    /** Throttle snapped shut: let the boost out, by blow-off and / or flutter. */
    fun snap(blowOff: Boolean, flutter: Boolean) {
        val p = pressure
        if (p < 0.25) return
        if (blowOff) {
            bovT = 0.0
            bovSize = p
        }
        if (flutter) {
            flutterT = 0.0
            flutterSize = if (blowOff) p * 0.4 else p        // after a blow-off, only a little
            flutterLen = (if (blowOff) 0.35 else 0.6 + 0.9 * p) * (0.85 + 0.3 * rnd())
        }
        if (blowOff || flutter) pressure = 0.0
    }

    /**
     * One sample. [load] 0..1 throttle, [revs] 0..1 of the style's rev range, [whistle] = the
     * whistle switch. Returns the sound to add after the engine's saturation.
     */
    fun next(load: Double, revs: Double, running: Boolean, whistle: Boolean): Double {
        // spool: up with throttle and revs (~0.6 s), down when let go (~0.6 s: clip ~7.7 -> 2.6 kHz in ~1.2 s)
        val target = if (running) (0.12 + 0.88 * ((revs - 0.1) / 0.6).coerceIn(0.0, 1.0)) * (0.1 + 0.9 * load) else 0.0
        speed += (target - speed) / ((if (target > speed) 0.6 else 0.45) * sr)
        // boost builds while the throttle is open, leaks away slowly when it isn't
        val pTarget = if (load > 0.3) speed else 0.0
        pressure += (pTarget - pressure) / ((if (pTarget > pressure) 0.25 else 1.5) * sr)

        var out = 0.0

        // flutter chuffs: ~38 a second, slowing quickly at first, then tailing off toward 12
        var gate = 1.0
        if (flutterLen > 0) {
            val prog = flutterT / flutterLen
            if (prog >= 1) {
                flutterLen = 0.0
            } else {
                chuffPh += (12.0 + 26.0 * exp(-3.0 * prog)) * (0.9 + 0.2 * rnd()) / sr
                if (chuffPh >= 1) {
                    chuffPh -= 1.0
                    chuffEnv = flutterSize * (1 - prog) * (0.7 + 0.3 * rnd())
                }
                flutterT += 1 / sr
            }
        }
        if (chuffEnv > 1e-4) {
            chuffLp += (rnd() * 2 - 1 - chuffLp) * 0.08    // under ~600 Hz: the "stu"
            out += chuffEnv * chuffLp * FLUTTER
            gate = 0.55 + 0.9 * chuffEnv.coerceAtMost(0.5)  // the whistle is chopped with it
            chuffEnv *= chuffDecay
        } else if (flutterLen > 0) {
            gate = 0.55
        }

        // whistle
        if (whistle && speed > 0.01) {
            wobble += (rnd() * 2 - 1 - wobble) * 0.001
            val hz = (2500 + 5500 * speed) * (1 + 0.004 * wobble)
            whistlePh += hz / sr
            if (whistlePh >= 1) whistlePh -= 1.0
            val w = sin(2 * PI * whistlePh) + 0.15 * sin(4 * PI * whistlePh)
            out += w * WHISTLE * speed.pow(1.5) * (0.35 + 0.65 * load) * gate
        }

        // blow-off: up in 15 ms, fades over ~150 ms, chattering
        if (bovT >= 0) {
            val env = if (bovT < 0.015) bovT / 0.015 else exp(-(bovT - 0.015) / 0.06)
            chatter += (rnd() * 2 - 1 - chatter) * 0.01    // valve chatter, a few tens per second
            chatter2 += (chatter - chatter2) * 0.01
            val mod = (0.6 + 4.0 * chatter2).coerceIn(0.15, 1.3)
            bovBand.process(rnd() * 2 - 1)
            out += bovBand.band * env * mod * bovSize * BLOW_OFF
            bovT += 1 / sr
            if (bovT > 0.4) bovT = -1.0
        }
        return out
    }

    companion object {
        // levels next to the engine (calibrated with TurboTest / the demo WAVs)
        const val WHISTLE = 0.35
        const val FLUTTER = 10.0
        const val BLOW_OFF = 2.4
    }
}
