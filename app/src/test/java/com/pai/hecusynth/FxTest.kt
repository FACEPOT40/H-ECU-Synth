package com.pai.hecusynth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Cam lope, exhaust pops and backfire bangs. */
class FxTest {
    private val sr = 48000

    /** Loudness every 0.2 s while idling for 8 s (long enough to hold whole engine cycles, so
     *  only the lope's hunting and weak bangs make it change). */
    private fun idleLoudness(st: EngineStyle): List<Double> {
        val s = Synth(sr).apply { style = st }
        val block = FloatArray(sr / 5)
        val out = ArrayList<Double>()
        s.set(1400.0, 0.0, false)
        repeat(5 * 9) { f ->
            s.render(block, block.size)
            if (f >= 5) out += sqrt(block.sumOf { it.toDouble() * it } / block.size)
        }
        return out
    }

    private fun wobble(x: List<Double>): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size) / m
    }

    @Test fun lopeMakesTheIdleUneven() {
        val twin = Styles.byId("vtwin")
        val smooth = wobble(idleLoudness(twin.copy(lope = 0.0, jitterDeg = 0.0)))
        val lopey = wobble(idleLoudness(twin.copy(lope = 1.0)))
        println("idle loudness wobble: no lope %.3f, full lope %.3f".format(smooth, lopey))
        assertTrue("$smooth -> $lopey", lopey > smooth * 2)
    }

    @Test fun lopeFadesOutAtHighRevs() {
        val twin = Styles.byId("vtwin")
        fun high(st: EngineStyle): FloatArray {
            val s = Synth(sr, seed = 3).apply { style = st }
            s.set(8000.0, 60.0, false)
            val a = FloatArray(sr)
            s.render(a, a.size)
            return a
        }
        // same loudness at high revs (the random details differ, the sound doesn't)
        fun rms(x: FloatArray) = sqrt(x.drop(sr / 2).sumOf { it.toDouble() * it } / (x.size - sr / 2))
        val a = rms(high(twin.copy(lope = 0.0)))
        val b = rms(high(twin.copy(lope = 1.0)))
        assertTrue("lope changed the sound at 8,000 rpm: $a -> $b", abs(a - b) / a < 0.05)
    }

    /** Snap the throttle shut from high revs: bangs (when on) make it much louder for a moment. */
    private fun snapPeak(bangs: Boolean, pops: Boolean = false): Double {
        val s = Synth(sr).apply { style = Styles.byId("i4"); this.bangs = bangs; crackles = pops }
        val sim = EngineSim()
        sim.start()
        val block = FloatArray(sr / 60)
        var peak = 0.0
        for (f in 0 until 60 * 5) {
            val t = f / 60.0
            val tps = if (t in 1.0..2.5) 100.0 else 0.0
            sim.step(1 / 60.0, tps)
            s.set(sim.rpm, tps, sim.fuelCut)
            s.render(block, block.size)
            for (v in block) {
                assertFalse(v.isNaN())
                if (t > 2.5) peak = maxOf(peak, abs(v).toDouble())
            }
        }
        return peak
    }

    @Test fun bangsOnlyWhenSwitchedOn() {
        val off = snapPeak(false)
        val on = snapPeak(true)
        println("peak after snapping shut: bangs off %.2f, on %.2f".format(off, on))
        assertTrue("$off -> $on", on > off * 1.8)
        assertTrue(on <= 1.0)
    }
}
