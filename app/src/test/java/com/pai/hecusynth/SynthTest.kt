package com.pai.hecusynth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class SynthTest {
    private val sr = 48000

    private fun render(s: Synth, seconds: Double): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        val block = FloatArray(256)
        var i = 0
        while (i < out.size) {
            val n = minOf(256, out.size - i)
            s.render(block, n)
            System.arraycopy(block, 0, out, i, n)
            i += n
        }
        return out
    }

    private fun steady(st: EngineStyle, bikeRpm: Double, tps: Double): FloatArray {
        val s = Synth(sr).apply { style = st; crackles = false }
        s.set(bikeRpm, tps, false)
        render(s, 0.6)                       // settle: glide, volume fade-in, filters
        return render(s, 1.0)
    }

    /** Power at frequency [f] (Goertzel with a Hann window). */
    private fun power(x: FloatArray, f: Double): Double {
        val w = 2 * PI * f / sr
        val k = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in x.indices) {
            val win = 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1))
            val s0 = x[i] * win + k * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - k * s1 * s2
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test fun silentWithEngineOff() {
        for (st in Styles.ALL) {
            val x = steady(st, 0.0, 0.0)
            assertTrue("${st.id} should be silent", rms(x) < 1e-4)
        }
    }

    @Test fun boundedAndFinite() {
        for (st in Styles.ALL) {
            val s = Synth(sr).apply { style = st; volume = 1.0; camEnabled = true; camRpm = 5000.0; crackles = true; bangs = true }
            val sim = EngineSim()
            sim.start()
            val block = FloatArray(800)
            // start, idle, flat out into the limiter, then let go (pops)
            for (frame in 0 until 60 * 7) {
                val t = frame / 60.0
                val tps = if (t in 1.5..4.0) 100.0 else 0.0
                sim.step(1 / 60.0, tps)
                s.set(sim.rpm, tps, sim.fuelCut)
                s.render(block, block.size)
                for (v in block) {
                    assertFalse("${st.id} NaN", v.isNaN())
                    assertTrue("${st.id} out of range", v in -1f..1f)
                }
            }
        }
    }

    /** The sound must follow the crank: energy at multiples of the cycle frequency (one cycle =
     *  2 turns), much less in between. Checks the rpm-to-pitch link for every style. */
    @Test fun pitchFollowsRpm() {
        val bike = BikeRange()
        for (st in Styles.ALL) {
            val bikeRpm = 4500.0
            val x = steady(st, bikeRpm, 40.0)
            val cycle = bike.toStyle(bikeRpm, st) / 120.0
            var on = 0.0
            var off = 0.0
            var k = 1
            while ((k + 1) * cycle < 8000) {
                on += power(x, k * cycle)
                off += power(x, (k + 0.5) * cycle)
                k++
            }
            assertTrue("${st.id}: on/off = ${on / off}", on > 10 * off)
        }
    }

    /** How "engine-like" vs "vacuum-cleaner-like": share of the sound that sits on the engine's
     *  harmonics (tone) rather than between them (noise), at full throttle, high revs. */
    @Test fun toneReport() {
        val bike = BikeRange()
        for (st in Styles.ALL) {
            val x = steady(st, 7500.0, 100.0)
            val cycle = bike.toStyle(7500.0, st) / 120.0
            // growl = cycle harmonics that aren't firing harmonics (cylinder differences: what
            // makes it an engine and not a motor whine); hiss = noise between harmonics > 2 kHz
            var fire = 0.0
            var growl = 0.0
            var hiOn = 0.0
            var hiOff = 0.0
            var k = 1
            while ((k + 1) * cycle < 12000) {
                val p = power(x, k * cycle)
                if (k % st.cylinders == 0) fire += p else growl += p
                if (k * cycle > 2000) { hiOn += p; hiOff += power(x, (k + 0.5) * cycle) }
                k++
            }
            println("%-7s growl/fire %.2f  hiss above 2 kHz %.0f %%".format(st.id, growl / fire, 100 * hiOff / (hiOn + hiOff)))
        }
    }

    @Test fun throttleMakesItLouder() {
        for (st in Styles.ALL) {
            val closed = rms(steady(st, 5000.0, 0.0))
            val open = rms(steady(st, 5000.0, 100.0))
            println("%-7s rms closed %.3f  open %.3f".format(st.id, closed, open))
            assertTrue("${st.id}: $closed -> $open", open > closed * 1.3)
            assertTrue("${st.id} too quiet at full throttle: $open", open > 0.05)
        }
    }

    /** At the limiter the loudness must pulse at the style's limiter rate ("tatatata"), even
     *  with bike data arriving only ~17 times a second. */
    @Test fun limiterStuttersFast() {
        for (st in Styles.ALL) {
            val s = Synth(sr).apply { style = st }
            val block = FloatArray(sr / 60)
            val env = ArrayList<Double>()            // loudness 300 times a second
            var e1 = 0.0
            var e2 = 0.0
            val a = 1.0 / (0.004 * sr)               // smoothed like a meter (~40 Hz), so faster
            var count = 0                            // pulses (firing, growl) can't fold down
            var since = 1.0
            for (f in 0 until 60 * 3) {
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(9500.0, 100.0, f % 2 == 0) }
                s.render(block, block.size)
                for (v in block) {
                    e1 += (v.toDouble() * v - e1) * a
                    e2 += (e1 - e2) * a
                    if (f >= 30 && ++count % 160 == 0) env += sqrt(e2)
                }
            }
            assertTrue("${st.id} should be limiting", s.limiting)
            val mean = env.average()                 // only the pulsing, not the steady loudness
            fun pw(f: Double): Double {
                var re = 0.0
                var im = 0.0
                env.forEachIndexed { i, v0 ->
                    val v = v0 - mean
                    re += v * cos(2 * PI * f * i / 300); im += v * kotlin.math.sin(2 * PI * f * i / 300)
                }
                return re * re + im * im
            }
            // the strongest pulse near the style's limiter rate (whole firings + the rev sag move
            // it a little) must beat every slower pulse: slower = the "ta.. ta.. ta" problem
            fun scan(from: Double, to: Double) = generateSequence(from) { it + 0.25 }.takeWhile { it <= to }
            val rate = scan(st.limiterHz * 0.75, st.limiterHz * 1.25).maxBy { pw(it) }
            val at = pw(rate)
            val slow = scan(3.0, st.limiterHz * 0.6).maxOf { pw(it) }
            println("%-7s limiter stutter %.1f /s (target %.0f), %.1fx any slower pulse".format(st.id, rate, st.limiterHz, at / slow))
            assertTrue("${st.id}: stutter at $rate/s $at vs slower $slow", at > 2 * slow)
        }
    }

    @Test fun bikeRangeMapsIdleAndRedline() {
        val b = BikeRange(1400.0, 9500.0)
        val v10 = Styles.byId("v10")
        assertEquals(4000.0, b.toStyle(1400.0, v10), 1e-6)
        assertEquals(19000.0, b.toStyle(9500.0, v10), 1e-6)
        assertEquals(0.0, b.toStyle(0.0, v10), 1e-6)
        assertEquals(2000.0, b.toStyle(700.0, v10), 1e-6)
    }

    @Test fun camSwitchHasHysteresis() {
        val cam = CamSwitch(300.0)
        assertFalse(cam.update(6400.0, 6500.0, true))
        assertTrue(cam.update(6500.0, 6500.0, true))
        assertTrue(cam.update(6300.0, 6500.0, true))     // still on inside the gap
        assertFalse(cam.update(6150.0, 6500.0, true))
        assertFalse(cam.update(7000.0, 6500.0, false))   // switched off in the settings
    }

    /** Writes one rev run per style to app/build/demo-wav/ so the sounds can be heard on the PC:
     *  `<style>.wav` is the stock sound, `_pops` / `_bangs` the same run with that extra on,
     *  `<style>_lope_max.wav` the idle with full cam lope. */
    @Test fun demoWavs() {
        val dir = File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        // stock, pops only, bangs only
        // stock, pops, bangs for every style; the turbo extras on the I4 and the single
        val runs = Styles.ALL.flatMap { st -> listOf("", "_pops", "_bangs").map { st to it } } +
            listOf("i4", "single").flatMap { id -> listOf("_turbo", "_turbo_bov", "_turbo_flutter").map { Styles.byId(id) to it } }
        for ((st, fx) in runs) {
            val s = Synth(sr).apply {
                style = st; camEnabled = true; camRpm = 6500.0
                crackles = fx == "_pops"; bangs = fx == "_bangs"
                turbo = fx.startsWith("_turbo"); blowOff = fx == "_turbo_bov"; flutter = fx == "_turbo_flutter"
            }
            val sim = EngineSim()
            val pcm = ArrayList<Float>()
            val frame = FloatArray(sr / 60)
            var since = 1.0
            for (f in 0 until 60 * 9) {
                val t = f / 60.0
                if (f == 0) sim.start()
                if (t >= 8.2 && sim.running) sim.stop()
                val tps = when {
                    t in 2.0..2.4 -> 50.0       // blip
                    t in 3.4..6.0 -> 100.0      // flat out into the limiter
                    else -> 0.0                 // idle / let go (pops)
                }
                sim.step(1 / 60.0, tps)
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, tps, sim.fuelCut) }   // K-line rate
                s.render(frame, frame.size)
                frame.forEach { pcm += it }
            }
            writeWav(File(dir, "${st.id}$fx.wav"), pcm)
        }
        // big-cam lope at full: 5 s of idle with two short blips
        for (id in listOf("vtwin", "single")) {
            val s = Synth(sr).apply { style = Styles.byId(id).copy(lope = 1.0) }
            val sim = EngineSim()
            val pcm = ArrayList<Float>()
            val frame = FloatArray(sr / 60)
            for (f in 0 until 60 * 6) {
                val t = f / 60.0
                if (f == 0) sim.start()
                val tps = if (t in 3.0..3.25 || t in 4.2..4.45) 45.0 else 0.0
                sim.step(1 / 60.0, tps)
                if (f % 3 == 0) s.set(sim.rpm, tps, sim.fuelCut)
                s.render(frame, frame.size)
                frame.forEach { pcm += it }
            }
            writeWav(File(dir, "${id}_lope_max.wav"), pcm)
        }
        println("demo WAVs in $dir")
    }

    private fun writeWav(f: File, pcm: List<Float>) {
        val data = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2)
            .putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        f.writeBytes(data.array())
    }
}
