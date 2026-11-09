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

    /** One blast, then silence: nothing trails a bang (the old bursts made a "brbrbr"), and a
     *  pop asked for while one still sounds is skipped. A pop lasts ~25-40 ms at full level. */
    @Test fun singleBlastThenQuiet() {
        var r = 0x2545F4914F6CDD1DL
        val bf = Backfire(48000.0) { r = r xor (r shl 13); r = r xor (r ushr 7); r = r xor (r shl 17); (r ushr 11).toDouble() / (1L shl 53) }
        for (bang in listOf(true, false)) {
            bf.blast(1.0, bang)
            val x = DoubleArray(sr) { i -> if (i == 2400) bf.blast(0.8); bf.step(); bf.fx }   // a pop asked for 50 ms in
            val loud = x.toList().chunked(480).map { ch -> sqrt(ch.sumOf { it * it } / ch.size) }  // every 10 ms
            println((if (bang) "bang" else "pop ") + " loudness every 10 ms: " + loud.take(30).joinToString(" ") { "%.2f".format(it) })
            val peak = loud.max()
            val after = loud.drop(if (bang) 35 else 20)
            assertTrue("something after the ${if (bang) "bang" else "pop"}: ${after.max()}", after.max() < peak * 0.01)
            if (!bang) {
                val held = loud.takeWhile { it > peak * 0.5 }.size
                assertTrue("pop held only $held x 10 ms", held >= 3)
            }
            repeat(sr) { bf.step() }                   // let it rest before the next case
        }
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
            bf.blast(if (bang) 1.0 else 0.7, bang)
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
