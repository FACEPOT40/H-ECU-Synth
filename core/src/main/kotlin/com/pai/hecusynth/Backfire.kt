package com.pai.hecusynth

import kotlin.math.exp
import kotlin.math.tanh

/**
 * Exhaust pops and backfire bangs.
 *
 * Built from measurements of two references Pai sent (9 Nov; measured only, never copied or
 * shipped): a pops-and-bangs video and a clean "Bang" sound effect.
 *  - Timing (video): BURSTS of 4-9 pops ~28-35 ms apart, the first loudest, each next softer.
 *  - Each blast (bang SFX): full level within 0.2 ms; HOLDS flat-out loud (~85 ms for a bang),
 *    heavily clipped (42 % of the first 80 ms near full scale: dense, raw); then dies fast
 *    (-11 dB at 120 ms, -28 at 200, -46 at 300); 75-85 % of the energy at 500-2000 Hz, almost
 *    no bass; a bright 2-4 kHz crack in the first ~5 ms.
 * So a blast = noise centred on ~1 kHz, driven hard into clipping, under a hold-then-fade
 * envelope, with a crack at the front. Pops are the same thing, much shorter.
 *
 * Earlier tries Pai rejected by ear: tuned resonators ("PVC instrument", "RimbaTubes"), then
 * a low thumping engine pulse and a thin 5 ms crack ("sound like a fart").
 */
class Backfire(private val sr: Double, private val rnd: () -> Double) {
    private var left = 0            // pops still to come in this burst
    private var timer = 0
    private var amp = 0.0           // loudness of the next blast
    private var big = false         // the next blast is a bang

    private var level = 0.0         // this blast's loudness
    private var holdLeft = 0        // samples left at full level
    private var env = 0.0
    private var release = 0.0
    private var crack = 0.0
    private val crackDecay = exp(-1.0 / (0.003 * sr))
    private val band = Svf().apply { set(750.0, 0.7, sr) }      // ~500-2000 Hz once clipped
    private val crackBand = Svf().apply { set(3000.0, 1.0, sr) } // the crack: ~2-4 kHz
    private var lpOut = 0.0
    private var lpOut2 = 0.0

    /** This sample's pop / bang, to add after the engine's saturation. */
    var fx = 0.0
        private set

    val busy get() = left > 0

    /** Starts a burst of [count] blasts, the first [first] loud. [bang] makes the first one a
     *  full bang (long hold); its pops follow after it. A burst already running is only
     *  replaced by a louder one. */
    fun burst(count: Int, first: Double, bang: Boolean = false) {
        if (left > 0 && first <= amp) return
        left = count
        timer = 0
        amp = first
        big = bang
    }

    fun step() {
        if (left > 0 && --timer <= 0) {
            val holdS = if (big) 0.085 else 0.006 + 0.012 * minOf(1.0, amp)   // pops: 6-18 ms
            val tau = if (big) 0.045 else 0.009
            level = amp
            env = 1.0
            holdLeft = (holdS * sr).toInt()
            release = exp(-1.0 / (tau * sr))
            crack = amp
            left--
            amp *= 0.7 + 0.12 * rnd()
            // next one 28-35 ms on (video); after a bang, once the bang has died down
            timer = (((if (big) holdS + 0.06 else 0.0) + 0.028 + 0.007 * rnd()) * sr).toInt()
            big = false
        }
        if (env < 1e-4) { fx = 0.0; return }

        if (holdLeft > 0) holdLeft-- else env *= release
        val nz = rnd() * 2 - 1
        band.process(nz)
        // hard drive into clipping: during the hold it's a dense, near-square roar; as the
        // envelope falls it comes out of clipping and fades (like the reference)
        val out = tanh(DRIVE * band.band * env) * level
        lpOut += (out - lpOut) * 0.3             // clipping adds a harsh top: take it off
        lpOut2 += (lpOut - lpOut2) * 0.3         // (two steps, above ~2.5 kHz)
        var o = lpOut2
        if (crack > 1e-4) {                      // bright 2-4 kHz edge at the very start
            crackBand.process(nz)                // (after that filter, so it stays bright)
            o += crack * crackBand.band * 5.0
            crack *= crackDecay
        }
        fx = o
    }

    companion object {
        /** How hard the blast is driven into clipping (reference: ~40 % near full scale). */
        const val DRIVE = 20.0
    }
}
