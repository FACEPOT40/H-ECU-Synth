package com.pai.hecusynth

import kotlin.math.exp
import kotlin.math.tanh

/**
 * Exhaust pops and backfire bangs: one blast each.
 *
 * The blast is modelled on a clean "Bang" sound effect Pai sent (9 Nov; measured only, never
 * copied or shipped): full level within 0.2 ms; HOLDS flat-out loud (~85 ms), heavily clipped
 * (dense, raw); then dies fast (-11 dB at 120 ms, -28 at 200, -46 at 300); 75-85 % of the
 * energy at 500-2000 Hz, almost no bass; a bright 2-4 kHz crack in the first ~5 ms.
 * So a blast = noise centred on ~1 kHz, driven hard into clipping, under a hold-then-fade
 * envelope, with a crack at the front. Pai: the bang is right.
 *
 * A pop is the same blast, shorter and softer (Pai: 6-18 ms was "too short"; now ~25-40 ms).
 * Each is a SINGLE blast, with a short quiet gap after it before the next can start: the
 * earlier bursts of quick pops after each bang came out as a "brbrbr" Pai didn't want.
 *
 * Earlier tries Pai rejected by ear: tuned resonators ("PVC instrument", "RimbaTubes"), a low
 * thumping engine pulse and a thin 5 ms crack ("sound like a fart"), bursts after the bang.
 */
class Backfire(private val sr: Double, private val rnd: () -> Double) {
    private var level = 0.0         // this blast's loudness
    private var holdLeft = 0        // samples left at full level
    private var env = 0.0
    private var release = 0.0
    private var quietLeft = 0       // samples until the next blast may start
    private var crack = 0.0
    private val crackDecay = exp(-1.0 / (0.003 * sr))
    private val band = Svf().apply { set(750.0, 0.7, sr) }      // ~500-2000 Hz once clipped
    private val crackBand = Svf().apply { set(3000.0, 1.0, sr) } // the crack: ~2-4 kHz
    private var lpOut = 0.0
    private var lpOut2 = 0.0

    /** This sample's pop / bang, to add after the engine's saturation. */
    var fx = 0.0
        private set

    /** A blast is sounding, or it's too soon after one for the next. */
    val busy get() = quietLeft > 0

    /** One blast of loudness [loud]: a pop, or with [bang] a full bang. While [busy] a pop is
     *  skipped; a bang always goes (it replaces a pop that's still sounding). */
    fun blast(loud: Double, bang: Boolean = false) {
        if (busy && !bang) return
        val holdS = if (bang) 0.085 else 0.025 + 0.015 * minOf(1.0, loud)   // pops: 25-40 ms
        val tau = if (bang) 0.045 else 0.022
        level = loud
        env = 1.0
        holdLeft = (holdS * sr).toInt()
        release = exp(-1.0 / (tau * sr))
        crack = loud
        // quiet gap: the blast has to die down first (~5 x its fade), then a little more
        quietLeft = ((holdS + 5 * tau + 0.05 + 0.1 * rnd()) * sr).toInt()
    }

    fun step() {
        if (quietLeft > 0) quietLeft--
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
