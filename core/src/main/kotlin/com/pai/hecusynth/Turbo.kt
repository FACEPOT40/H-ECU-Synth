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
 *  - Flutter (compressor surge when the throttle shuts with boost and no blow-off): a steady
 *    rush of air whose loudness WOBBLES gently (swing ~0.25 of its level), mostly 2-10 kHz with
 *    the most at 4-7 kHz, high and low air about equally loud; the wobble starts at ~35-40 a
 *    second and slows to ~12 as the pressure runs out; the whistle wobbles with it.
 *    (A first version made separate low thumps per "tu": Pai, "sounds like a PVC instrument".)
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
    private var flutPh = 0.0
    private var flutDepth = 0.5         // this wobble's depth (a little different each time)
    private var airLp = 0.0
    private var lowLp = 0.0
    private val airBand = Svf().apply { set(5000.0, 0.6, sr) }   // the rush: centred ~5 kHz

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

        // flutter: rushing air (high and low) wobbling at ~38 a second, slowing quickly at first
        // and tailing off toward 12, fading out as the boost runs out
        var gate = 1.0
        if (flutterLen > 0) {
            val prog = flutterT / flutterLen
            if (prog >= 1) {
                flutterLen = 0.0
            } else {
                flutPh += (12.0 + 26.0 * exp(-3.0 * prog)) * (0.9 + 0.2 * rnd()) / sr
                if (flutPh >= 1) {
                    flutPh -= 1.0
                    flutDepth = 0.35 + 0.3 * rnd()
                }
                val wob = 1 + flutDepth * sin(2 * PI * flutPh)           // gentle, smooth wobble
                val fade = flutterSize * (1 - prog) * minOf(1.0, flutterT / 0.01)
                val nz = rnd() * 2 - 1
                airBand.process(nz)
                airLp += (nz - airLp) * 0.15                            // hiss above ~1 kHz ...
                lowLp += (nz - lowLp) * 0.05                            // ... and low air
                val air = airBand.band * 1.2 + (nz - airLp) * 0.2 + lowLp * 3.0
                out += air * wob * fade * FLUTTER
                gate = 1 - 0.5 * (1 - prog) + 0.5 * (1 - prog) * (wob - 1 + flutDepth) / (2 * flutDepth)
                flutterT += 1 / sr
            }
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
        const val FLUTTER = 0.9         // v2 sound, +5 dB (Pai: v2 "not aggressive enough"; v3 = "rain on zinc")
        const val BLOW_OFF = 2.4
    }
}
