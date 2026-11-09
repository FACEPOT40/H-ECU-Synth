package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max

/**
 * Exhaust pops and backfire bangs, shaped after a reference clip Pai liked (9 Nov), measured,
 * not copied:
 *  - they come in BURSTS: 4-9 pops 25-33 ms apart ("papapapapa"), then quiet;
 *  - the first ones are loudest (up to ~20 dB over the engine) and ring longest (100-200 ms),
 *    each next one softer and shorter;
 *  - the sound is a "pap" that rings at the exhaust pipe's own notes (~300-900 Hz there),
 *    with a crisp edge, and almost no deep boom.
 * So each pop = a short noise hit + a crack, ringing through a bank of pipe resonators.
 *
 * [pitch] moves the pipe notes for the style (longer pipe = lower). Plain Kotlin, used by [Synth].
 */
class Backfire(private val sr: Double, private val rnd: () -> Double) {
    private val baseHz = doubleArrayOf(320.0, 480.0, 660.0, 900.0, 1300.0)
    // A resonator rings louder the lower its note, so the weights rise with pitch to give the
    // clip's balance: about half the sound at 200-500 Hz, a quarter at 500-1k, the rest above.
    private val weight = doubleArrayOf(0.7, 1.0, 0.75, 0.6, 0.5).mapIndexed { k, w -> w * baseHz[k] / 480.0 }.toDoubleArray()
    private val n = baseHz.size
    private val y1 = DoubleArray(n)
    private val y2 = DoubleArray(n)
    private val c1 = DoubleArray(n)
    private val c2 = DoubleArray(n)
    private val cosw = DoubleArray(n)

    private var left = 0            // pops still to come in this burst
    private var timer = 0
    private var amp = 0.0           // loudness of the next pop
    private var ring = 0.1          // ring time of the next pop, seconds
    private var crackBoost = 1.0

    private var hitEnv = 0.0
    private var hitLp = 0.0
    private var crack = 0.0
    private var crackLp = 0.0
    private var crackLp2 = 0.0
    private val hitDecay = exp(-1.0 / (0.003 * sr))
    private val crackDecay = exp(-1.0 / (0.0012 * sr))

    /** Pipe notes scale: 1.0 = the reference clip, lower = longer pipe. */
    var pitch = 1.0

    val busy get() = left > 0

    /** Starts a burst of [count] pops, the first [first] loud, ringing [ringS] seconds. A burst
     *  already running is only replaced by a louder one. */
    fun burst(count: Int, first: Double, ringS: Double, crackBoost: Double = 1.0) {
        if (left > 0 && first <= amp) return
        left = count
        timer = 0
        amp = first
        ring = ringS
        this.crackBoost = crackBoost
        val detune = pitch * (0.94 + 0.12 * rnd())          // each burst a slightly different pipe
        for (k in 0 until n) cosw[k] = cos(2 * PI * baseHz[k] * detune / sr)
        retune()
    }

    private fun retune() {
        for (k in 0 until n) {
            val r = exp(-1.0 / (max(0.008, ring * (1 - 0.12 * k)) * sr))   // higher notes die sooner
            c1[k] = 2 * r * cosw[k]
            c2[k] = -r * r
        }
    }

    fun next(): Double {
        if (left > 0 && --timer <= 0) {
            hitEnv = amp
            crack = amp * crackBoost
            retune()
            left--
            amp *= 0.68 + 0.12 * rnd()
            ring *= 0.7
            crackBoost = 1.0
            timer = ((0.025 + 0.008 * rnd()) * sr).toInt()
        }
        var x = 0.0
        if (hitEnv > 1e-4) {
            hitLp += (rnd() * 2 - 1 - hitLp) * 0.5
            x = hitEnv * hitLp
            hitEnv *= hitDecay
        }
        var out = 0.0
        for (k in 0 until n) {
            val y = c1[k] * y1[k] + c2[k] * y2[k] + x * weight[k] * GAIN
            y2[k] = y1[k]
            y1[k] = y
            out += y
        }
        if (crack > 1e-4) {                                    // the crisp edge on top,
            val nz = rnd() * 2 - 1                             // kept to ~1-2.7 kHz like the clip
            crackLp += (nz - crackLp) * 0.35
            crackLp2 += (crackLp - crackLp2) * 0.12
            out += crack * (crackLp - crackLp2) * 1.0
            crack *= crackDecay
        }
        return out
    }

    companion object {
        /** Resonator input gain: a first pop of 1.0 peaks at about 1 (checked in FxTest). */
        const val GAIN = 0.0075
    }
}
