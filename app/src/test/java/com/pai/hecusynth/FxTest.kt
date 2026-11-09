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

    /** A burst, like the reference clip: several blasts 25-33 ms apart, each softer than the
     *  last, then quiet. */
    @Test fun burstIsAFadingRowOfPops() {
        var r = 0x2545F4914F6CDD1DL
        val bf = Backfire(48000.0) { r = r xor (r shl 13); r = r xor (r ushr 7); r = r xor (r shl 17); (r ushr 11).toDouble() / (1L shl 53) }
        bf.burst(6, 1.0)
        val x = DoubleArray(sr / 2) { bf.step(); bf.fx }
        val hits = x.toList().chunked(1392).map { ch -> ch.maxOf { abs(it) } }   // 29 ms slots
        println("burst peaks per 29 ms: " + hits.take(10).joinToString(" ") { "%.2f".format(it) })
        assertTrue("first pop peak ${hits[0]}", hits[0] in 0.5..3.0)      // body ~1 + the crack spike
        assertTrue("later pops softer", hits[4] < hits[0] * 0.8)
        assertTrue("quiet after the burst", hits.last() < 0.01)
    }

    /** Two identical coasting runs, with and without pops, written for measuring the pops alone
     *  (their difference); the engine's own randomness is the same in both. */
    @Test fun popProbeWavs() {
        val dir = java.io.File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        for (pops in listOf("", "pops")) {
            val s = Synth(sr).apply { style = Styles.byId("i4"); crackles = pops != "" }
            val block = FloatArray(sr / 60)
            val pcm = ArrayList<Float>()
            for (f in 0 until 60 * 4) {
                val rpm = 8000.0 - f * 15.0              // coasting down from 8,000 rpm
                s.set(rpm, 0.0, true)
                s.render(block, block.size)
                block.forEach { pcm += it }
            }
            val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bb.putShort((it * 32767).toInt().toShort()) }
            java.io.File(dir, if (pops == "") "probe_nopops.raw" else "probe_$pops.raw").writeBytes(bb.array())
        }
    }

    /** One bang and one pop alone (no engine), for measuring against the reference bang. */
    @Test fun loneBangWav() {
        val dir = java.io.File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        var r = 0x2545F4914F6CDD1DL
        for ((name, bang) in listOf("lone_bang" to true, "lone_pop" to false)) {
            val bf = Backfire(48000.0) { r = r xor (r shl 13); r = r xor (r ushr 7); r = r xor (r shl 17); (r ushr 11).toDouble() / (1L shl 53) }
            bf.burst(1, if (bang) 1.0 else 0.7, bang)
            val pcm = FloatArray(sr) { bf.step(); bf.fx.toFloat() }
            val bb = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
            bb.put("data".toByteArray()).putInt(pcm.size * 2)
            pcm.forEach { bb.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
            java.io.File(dir, "$name.wav").writeBytes(bb.array())
        }
    }

    /** Snap the throttle shut from high revs: bangs (when on) make it much louder for a moment. */
    private fun snapPeak(bangs: Boolean): Double {
        val s = Synth(sr).apply { style = Styles.byId("i4"); this.bangs = bangs }
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
