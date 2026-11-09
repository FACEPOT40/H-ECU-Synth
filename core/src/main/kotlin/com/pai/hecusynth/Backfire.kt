package com.pai.hecusynth

import kotlin.math.exp
import kotlin.math.tanh

/** How a pop sounds. Pai picks by ear (desktop + phone). */
enum class PopSound(val label: String) {
    /** One of the engine's own exhaust pulses, suddenly huge, raw and distorted. */
    BARK("Engine bark"),
    /** A shock wave (sharp push, then pull) with a short distorted noise burst. */
    CRACK("Sharp crack"),
}

/**
 * Exhaust pops and backfire bangs.
 *
 * Timing follows a reference clip Pai liked (9 Nov, measured, not copied): BURSTS of 4-9 pops
 * ~28-35 ms apart, the first loudest, each next one softer, then quiet.
 *
 * The sound has NO pitched parts. Two earlier versions rang each pop on tuned resonators
 * ("pipe notes", long and then short): Pai said they sounded like a PVC-pipe instrument /
 * RimbaTubes, not an engine, even though they measured close to the clip. So now:
 *  - [PopSound.BARK]: a pop is handed to the [Synth] ([takeBark]), which makes the next firing a
 *    huge pulse with lots of combustion noise, and opens its tone filter and adds rasp while it
 *    sounds ([open]): the engine itself barks, through its own pipe and rasp.
 *  - [PopSound.CRACK]: a pop is a shock wave (0.4 ms push, then a pull) plus a 5 ms noise burst,
 *    soft-clipped, made here ([fx]).
 * Both get a short dark "air" tail (noise, not echoes: echoes ring).
 */
class Backfire(private val sr: Double, private val rnd: () -> Double) {
    var sound = PopSound.BARK

    private var left = 0            // pops still to come in this burst
    private var timer = 0
    private var amp = 0.0           // loudness of the next pop
    private var big = false         // this burst is a bang

    private var pending = 0.0       // BARK: a pop waiting for the next firing
    private var pendingAge = 0

    private var shockAge = Int.MAX_VALUE
    private var shockAmp = 0.0
    private var burstEnv = 0.0
    private var burstDecay = 0.0
    private var nLp = 0.0
    private var nLp2 = 0.0
    private var tailEnv = 0.0
    private var tailLp = 0.0
    private var tailDecay = exp(-1.0 / (0.05 * sr))
    private val openDecay = exp(-1.0 / (0.025 * sr))

    /** This sample's crack / tail sound, to add after the engine's saturation. */
    var fx = 0.0
        private set
    /** 0..~2: how strongly a bark is sounding (the Synth opens its tone filter, adds rasp). */
    var open = 0.0
        private set

    val busy get() = left > 0

    /** Starts a burst of [count] pops, the first [first] loud. [bang] = bigger and longer. A
     *  burst already running is only replaced by a louder one. */
    fun burst(count: Int, first: Double, bang: Boolean = false) {
        if (left > 0 && first <= amp) return
        left = count
        timer = 0
        amp = first
        big = bang
    }

    /** BARK: the pop due now (0 = none). The Synth calls this at each firing and makes that
     *  firing a bark of this size. */
    fun takeBark(): Double {
        val p = pending
        pending = 0.0
        if (p > 0) open = maxOf(open, p)
        return p
    }

    fun step() {
        if (left > 0 && --timer <= 0) {
            val a = amp
            if (sound == PopSound.BARK) {
                pending = a
                pendingAge = 0
                shockAmp = a * 0.25                     // a small crack on the bark's front
            } else {
                shockAmp = a
                burstEnv = a
            }
            shockAge = 0
            burstDecay = exp(-1.0 / ((if (big) 0.010 else 0.005) * sr))
            tailDecay = exp(-1.0 / ((if (big) 0.14 else 0.05) * sr))
            tailEnv += a * (if (big) 0.3 else 0.15)
            left--
            amp *= 0.7 + 0.12 * rnd()
            big = false                                 // only the first is the bang
            timer = ((0.028 + 0.007 * rnd()) * sr).toInt()   // clip: 25-30 ms apart
        }
        if (pending > 0 && ++pendingAge > 0.04 * sr) pending = 0.0   // no firing came: drop it
        open *= openDecay

        var out = 0.0
        // shock wave: up in 0.4 ms, down to -0.6 by 1.6 ms, back to 0 by 3.6 ms
        if (shockAge < (0.0036 * sr).toInt()) {
            val t = shockAge / sr * 1000                // ms
            val w = when {
                t < 0.4 -> t / 0.4
                t < 1.6 -> 1 - 1.6 * (t - 0.4) / 1.2
                else -> -0.6 * (1 - (t - 1.6) / 2.0)
            }
            out += shockAmp * w
            shockAge++
        }
        // noise burst, ~150 Hz - 3.5 kHz
        if (burstEnv > 1e-4) {
            nLp += (rnd() * 2 - 1 - nLp) * 0.4
            nLp2 += (nLp - nLp2) * 0.02
            out += burstEnv * (nLp - nLp2) * 1.6
            burstEnv *= burstDecay
        }
        // soft clip like a real blast hitting the mic (only the loud part distorts)
        out = tanh(2.5 * out) / 2.5 * 1.6
        // dark air tail
        if (tailEnv > 1e-4) {
            tailLp += (rnd() * 2 - 1 - tailLp) * 0.06
            out += tailEnv * tailLp
            tailEnv *= tailDecay
        }
        fx = out
    }
}
