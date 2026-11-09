package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Exhaust pops and backfire bangs, shaped after a reference clip Pai liked (9 Nov), measured,
 * not copied:
 *  - they come in BURSTS: 4-9 pops 25-33 ms apart ("papapapapa"), then quiet;
 *  - the first ones are loudest (up to ~20 dB over the engine), each next one softer;
 *  - each pop SWELLS over a few ms and lasts ~20-30 ms with a rough, grainy texture, and
 *    rings at the same pipe notes as the engine: it is the engine's own exhaust, barking.
 *
 * So a pop is a rough blast fed into the engine's own exhaust path in [Synth] (pipe echo, tone
 * filter, saturation) via [excite], plus a short crack and a noisy "air" tail via [fx], which
 * go straight to the output. [open] tells the Synth how far to open its tone filter and add
 * rasp while a pop sounds. (A first version rang the pops on pitched resonators: it sounded
 * like a PVC-pipe drum, Pai said, so no pitched parts here.)
 */
class Backfire(private val sr: Double, private val rnd: () -> Double) {
    private var left = 0            // pops still to come in this burst
    private var timer = 0
    private var amp = 0.0           // loudness of the next pop
    private var lenS = 0.025        // length of the next pop, seconds
    private var crackBoost = 1.0

    private var popAmp = 0.0
    private var popPos = 1.0        // 0..1 through the current pop, 1 = none
    private var popStep = 0.0
    private var grain = 0.0
    private var grain2 = 0.0
    private var air = 0.0
    // the pops' own broad "pipe body" (two wide filters, Q ~1: air, not notes), set to where the
    // clip's pops sit; the engine's own pipe echo rings at other notes for most styles
    private val body1 = Svf().apply { set(450.0, 0.9, sr) }
    private val body2 = Svf().apply { set(950.0, 1.1, sr) }

    // The pipe notes: five short-ringing resonators. Unlike the first try (a long, clean ring on
    // the same notes every time, which sounded like a PVC-pipe drum) they ring only ~35 -> 15 ms
    // and every pop gets its own slightly different notes, like a real blast in a pipe.
    private val noteHz = doubleArrayOf(320.0, 480.0, 660.0, 900.0, 1300.0)
    private val noteW = doubleArrayOf(0.7, 1.0, 0.75, 0.6, 0.5).mapIndexed { k, w -> w * noteHz[k] / 480.0 }.toDoubleArray()
    private val y1 = DoubleArray(5)
    private val y2 = DoubleArray(5)
    private val c1 = DoubleArray(5)
    private val c2 = DoubleArray(5)
    private var ringS = 0.035
    private var crack = 0.0
    private var crackLp = 0.0
    private var crackLp2 = 0.0
    private var tailEnv = 0.0
    private var tailLp = 0.0
    private var tailDecay = exp(-1.0 / (0.07 * sr))
    private val crackDecay = exp(-1.0 / (0.0012 * sr))

    /** A little of this sample's blast, to add to the engine's exhaust pulses (before the pipe). */
    var excite = 0.0
        private set
    /** This sample's pop / bang sound (body + crack + air tail), to add after the engine's saturation. */
    var fx = 0.0
        private set
    /** 0..~1.5: how strongly a pop is sounding (opens the tone filter, adds rasp). */
    var open = 0.0
        private set

    val busy get() = left > 0

    /** Starts a burst of [count] pops, the first [first] loud and [lengthS] long. A burst
     *  already running is only replaced by a louder one. Bangs: bigger [crackBoost], longer. */
    fun burst(count: Int, first: Double, lengthS: Double = 0.025, crackBoost: Double = 1.0) {
        if (left > 0 && first <= amp) return
        left = count
        timer = 0
        amp = first
        lenS = lengthS
        this.crackBoost = crackBoost
        ringS = if (crackBoost > 1) 0.05 else 0.035
        tailDecay = exp(-1.0 / ((if (crackBoost > 1) 0.16 else 0.07) * sr))
    }

    fun step() {
        if (left > 0 && --timer <= 0) {
            popAmp = amp
            popPos = 0.0
            popStep = 1.0 / ((lenS * (0.8 + 0.4 * rnd())) * sr)
            crack = amp * crackBoost
            tailEnv += amp * 0.12 * crackBoost
            left--
            for (k in 0 until 5) {                      // this pop's own notes and ring
                val hz = noteHz[k] * (0.9 + 0.2 * rnd())
                val r = exp(-1.0 / (maxOf(0.006, ringS * (1 - 0.12 * k)) * sr))
                c1[k] = 2 * r * kotlin.math.cos(2 * PI * hz / sr)
                c2[k] = -r * r
            }
            ringS = maxOf(0.012, ringS * 0.8)
            amp *= 0.7 + 0.12 * rnd()
            lenS = maxOf(0.015, lenS * 0.85)
            crackBoost = 1.0
            timer = ((0.028 + 0.007 * rnd()) * sr).toInt()      // clip: 25-30 ms apart
        }

        // the blast: swells over the first quarter, then dies away; rough, not a clean bump
        var env = 0.0
        if (popPos < 1.0) {
            env = if (popPos < 0.25) sin(popPos / 0.25 * PI / 2) else {
                val d = (popPos - 0.25) / 0.75
                (1 - d) * (1 - d)
            }
            popPos += popStep
        }
        // The blast's body: rough air shaped by the two wide filters into the clip's range
        // (mostly 200-1000 Hz, almost no bass). Two earlier tries, measured: a smooth push of air
        // gave a dull "whump" (96 % under 200 Hz); the same noise sent down the engine's pipe
        // came out at 1-2 kHz (that pipe rings at 625 / 1250 Hz on the I4).
        val blast = popAmp * env * (rnd() * 2 - 1)
        body1.process(blast)
        body2.process(blast)
        var notes = 0.0
        for (k in 0 until 5) {
            val y = c1[k] * y1[k] + c2[k] * y2[k] + blast * noteW[k] * NOTES
            y2[k] = y1[k]
            y1[k] = y
            notes += y
        }
        air = notes + (body1.band * 1.0 + body2.band * 0.6) * AIR
        grain += (rnd() * 2 - 1 - grain) * 0.12         // a little rough air down the engine's pipe
        grain2 += (grain - grain2) * 0.03               // too, so it blends with the engine
        excite = popAmp * env * 1.2 * (grain - grain2)
        open += (popAmp * env - open) * 0.02

        // crack (1-2.7 kHz edge at the start) + air tail (noise, no echoes: those ring tonally)
        var out = air * BODY
        if (crack > 1e-4) {
            val nz = rnd() * 2 - 1
            crackLp += (nz - crackLp) * 0.35
            crackLp2 += (crackLp - crackLp2) * 0.12
            out += crack * (crackLp - crackLp2)
            crack *= crackDecay
        }
        if (tailEnv > 1e-4) {
            tailLp += (rnd() * 2 - 1 - tailLp) * 0.06
            out += tailEnv * tailLp
            tailEnv *= tailDecay
        }
        fx = out
    }

    companion object {
        /** Level of the pops' own body (calibrated with FxTest / the probe WAVs). */
        const val BODY = 2.0
        /** Pipe notes vs rough air in the body (calibrated against the clip's noisiness). */
        const val NOTES = 0.0015
        const val AIR = 0.4
    }
}
