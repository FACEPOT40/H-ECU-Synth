package com.pai.hecusynth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Turbo whistle and blow-off (Turbo.kt). */
class TurboTest {
    private val sr = 48000

    private fun turbo(): Turbo {
        var r = 0x2545F4914F6CDD1DL
        return Turbo(sr.toDouble()) { r = r xor (r shl 13); r = r xor (r ushr 7); r = r xor (r shl 17); (r ushr 11).toDouble() / (1L shl 53) }
    }

    /** [seconds] of sound at [load] / [revs]. */
    private fun Turbo.run(seconds: Double, load: Double, revs: Double, whistle: Boolean) =
        DoubleArray((seconds * sr).toInt()) { next(load, revs, true, whistle) }

    private fun power(x: DoubleArray, f: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1))
            re += x[i] * w * cos(2 * PI * f * i / sr); im += x[i] * w * sin(2 * PI * f * i / sr)
        }
        return re * re + im * im
    }

    /** The strongest pitch between 1.5 and 9 kHz (every 50 Hz). */
    private fun pitch(x: DoubleArray) = generateSequence(1500.0) { it + 50 }.takeWhile { it <= 9000 }.maxBy { power(x, it) }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    @Test fun whistleClimbsWithSpoolAndFallsWhenLetGo() {
        val t = turbo()
        val start = pitch(t.run(0.1, 1.0, 0.8, true))
        val spooled = pitch(t.run(1.5, 1.0, 0.8, true).takeLast(4800).toDoubleArray())
        val down = pitch(t.run(1.2, 0.0, 0.8, true).takeLast(4800).toDoubleArray())
        println("whistle pitch: start %.0f Hz, spooled %.0f Hz, 1.2 s after letting go %.0f Hz".format(start, spooled, down))
        assertTrue("start $start", start < 4000)               // clip: ~3 kHz
        assertTrue("spooled $spooled", spooled > 6500)         // clip: up to ~7.7 kHz
        assertTrue("let go $down", down < spooled - 1500)      // clip: back to ~2.6 kHz
    }

    @Test fun blowOffIsAShortHighTsss() {
        val t = turbo()
        t.run(1.5, 1.0, 0.8, false)                            // build boost, whistle off
        assertTrue("boost ${t.pressure}", t.pressure > 0.6)
        t.snap(blowOff = true)
        assertEquals(0.0, t.pressure, 1e-9)
        val x = t.run(0.5, 0.0, 0.8, false)
        val head = x.copyOfRange(0, (0.2 * sr).toInt())
        val inBand = generateSequence(5500.0) { it + 50 }.takeWhile { it <= 7500 }.sumOf { power(head, it) }
        val all = generateSequence(100.0) { it + 50 }.takeWhile { it <= 16000 }.sumOf { power(head, it) }
        val tail = rms(x.copyOfRange((0.4 * sr).toInt(), x.size))
        println("blow-off: %.0f %% at 5.5-7.5 kHz (clip 80-90 %%), rms %.3f, after 0.4 s %.4f".format(100 * inBand / all, rms(head), tail))
        assertTrue(inBand / all > 0.7)
        assertTrue(tail < rms(head) * 0.01)
    }

    /** Pai: the blow-off only played at the limiter. Every let-off after some throttle must
     *  give one: a short rev released at once, and a slower release over ~0.3 s. Run through
     *  the whole Synth at the K-line's ~17 readings a second. */
    @Test fun blowOffOnEveryLetOff() {
        fun tsss(blowOff: Boolean, release: (Double) -> Double): Double {
            val s = Synth(sr).apply { style = Styles.byId("i4"); this.blowOff = blowOff }
            val sim = EngineSim()
            sim.start()
            val frame = FloatArray(sr / 60)
            val after = ArrayList<Double>()
            var since = 1.0
            for (f in 0 until 60 * 4) {
                val t = f / 60.0
                val tps = release(t)
                sim.step(1 / 60.0, tps)
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, tps, sim.fuelCut) }
                s.render(frame, frame.size)
                if (t in 2.0..2.8) frame.forEach { after += it.toDouble() }
            }
            val x = after.toDoubleArray()
            return generateSequence(6000.0) { it + 50 }.takeWhile { it <= 7000 }.sumOf { power(x, it) }
        }
        val quick: (Double) -> Double = { t -> if (t in 1.4..2.0) 60.0 else 0.0 }              // 0.6 s at 60 %, let go
        val slow: (Double) -> Double = { t -> when {                                            // 0.6 s at 60 %, eased off
            t in 1.4..2.0 -> 60.0
            t in 2.0..2.3 -> 60.0 * (2.3 - t) / 0.3
            else -> 0.0
        } }
        for ((name, r) in listOf("quick" to quick, "slow" to slow)) {
            val on = tsss(true, r)
            val off = tsss(false, r)
            println("blow-off after a short rev, %s release: %.0fx more 6-7 kHz than with it off".format(name, on / off))
            assertTrue("$name release: no blow-off ($on vs $off)", on > off * 20)
        }
    }

    /** The pitch setting moves the original blow-off up, keeping its loudness and shape. */
    @Test fun blowOffPitchMovesTheSameSound() {
        fun blow(hz: Double): DoubleArray {
            val t = turbo().apply { blowOffPitch = hz }
            t.run(1.5, 1.0, 0.8, false)
            t.snap(blowOff = true)
            return t.run(0.4, 0.0, 0.8, false).copyOfRange(0, (0.2 * sr).toInt())
        }
        val base = blow(6500.0)
        for (hz in listOf(8000.0, 9500.0)) {
            val x = blow(hz)
            val peak = generateSequence(hz - 1000) { it + 20 }.takeWhile { it <= hz + 1000 }.maxBy { power(x, it) }
            val db = 20 * kotlin.math.log10(rms(x) / rms(base))
            println("blow-off pitch %.0f Hz: strongest at %.0f Hz, level %+.1f dB vs the original".format(hz, peak, db))
            assertTrue("pitch $hz -> peak $peak", abs(peak - hz) < hz * 0.03)
            assertTrue("level $db dB", abs(db) < 2.0)
        }
    }

    /** The blow-off volume scales only the blow-off: half = -6 dB, 0 = gone. */
    @Test fun blowOffVolumeScalesIt() {
        fun blow(vol: Double): Double {
            val t = turbo().apply { blowOffVolume = vol }
            t.run(1.5, 1.0, 0.8, false)
            t.snap(blowOff = true)
            return rms(t.run(0.3, 0.0, 0.8, false))
        }
        val full = blow(1.0)
        val db = 20 * kotlin.math.log10(blow(0.5) / full)
        println("blow-off volume 50 %%: %.1f dB".format(db))
        assertEquals(-6.0, db, 0.3)
        assertEquals(0.0, blow(0.0), 0.0)
        assertTrue(blow(2.0) > full * 1.9)
    }

    /** Surge measured like the clips: slow, deep "tu"s (~6-10 a second, gaps down to ~0.1),
     *  mostly 300-1000 Hz, each darker; only after real boost. Writes lone_surge.wav. */
    @Test fun surgeIsSlowAndDeep() {
        val t = turbo()
        t.run(0.15, 0.5, 0.3, false)                            // a quick blip: not enough boost
        t.snap(blowOff = false, flutter = true)
        assertEquals(0.0, t.run(0.3, 0.0, 0.3, false).maxOf { abs(it) }, 0.0)

        t.run(1.5, 1.0, 0.8, false)                             // a real pull
        t.snap(blowOff = false, flutter = true)
        val x = t.run(1.5, 0.0, 0.8, false)
        val env = x.toList().chunked(192).map { ch -> sqrt(ch.sumOf { it * it } / ch.size) }   // every 4 ms
        val top = env.max()
        val peaks = (1 until env.size - 1).filter { i ->
            env[i] >= env.subList(maxOf(0, i - 10), minOf(env.size, i + 11)).max() && env[i] > 0.3 * top
        }
        val gaps = peaks.zipWithNext { a, b -> (b - a) * 4 }
        val dips = peaks.zipWithNext { a, b -> env.subList(a, b).min() / minOf(env[a], env[b]) }
        println("surge: %d 'tu's, gaps ms %s, dips %s".format(peaks.size, gaps, dips.joinToString(" ") { "%.2f".format(it) }))
        println("surge loudness every 4 ms: " + env.take(300).joinToString("") { "${(it / top * 9).toInt()}" })
        assertTrue("'tu's: ${peaks.size}", peaks.size in 8..16)
        assertTrue("gaps $gaps", gaps.all { it in 60..180 })
        assertTrue("dips $dips", dips.all { it < 0.4 })
        System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() }?.let { d -> File(d).mkdirs(); write(File(d, "lone_surge.wav"), x) }
    }

    /** Demo: the "turbo Wave" with surge - single, engine muted, turbo whistle + flutter; flat
     *  out, and at mid revs only (40 % throttle, up to ~5,800 rpm). */
    @Test fun turboOnlySurgeWav() {
        val d = System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return
        for ((name, open) in listOf("single_turbo_only_flutter" to 100.0, "single_turbo_only_flutter_midrpm" to 40.0)) {
        val s = Synth(sr).apply { style = Styles.byId("single"); engineSound = false; turbo = true; flutter = true }
        val sim = EngineSim()
        val pcm = ArrayList<Double>()
        val frame = FloatArray(sr / 60)
        var since = 1.0
        for (f in 0 until 60 * 9) {
            val t = f / 60.0
            if (f == 0) sim.start()
            val tps = if (t in 1.5..3.5 || t in 5.0..6.5) open else 0.0
            sim.step(1 / 60.0, tps)
            since += 1 / 60.0
            if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, tps, sim.fuelCut) }
            s.render(frame, frame.size)
            frame.forEach { pcm += it.toDouble() }
        }
        File(d).mkdirs()
        write(File(d, "$name.wav"), pcm.toDoubleArray())
        }
    }

    /** Pai: "also sound at mid-rpm" - a moderate pull at mid revs gives nearly the full surge,
     *  through the whole Synth at the K-line rate (single, ~5,800 bike rpm at 40 % throttle). */
    @Test fun surgeAtMidRpm() {
        fun heCount(tps: Double, hold: Double): Int {
            val s = Synth(sr).apply { style = Styles.byId("single"); engineSound = false; flutter = true }
            val sim = EngineSim()
            val pcm = ArrayList<Double>()
            val frame = FloatArray(sr / 60)
            var since = 1.0
            var maxRpm = 0.0
            for (f in 0 until 60 * 5) {
                val t = f / 60.0
                if (f == 0) sim.start()
                val th = if (t in 1.0..(1.0 + hold)) tps else 0.0
                sim.step(1 / 60.0, th)
                maxRpm = maxOf(maxRpm, sim.rpm)
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, th, sim.fuelCut) }
                s.render(frame, frame.size)
                frame.forEach { pcm += it.toDouble() }
            }
            val env = pcm.chunked(192).map { ch -> sqrt(ch.sumOf { it * it } / ch.size) }
            val top = env.max()
            if (top < 1e-3) return 0
            val n = (1 until env.size - 1).count { i ->
                env[i] >= env.subList(maxOf(0, i - 10), minOf(env.size, i + 11)).max() && env[i] > 0.3 * top
            }
            println("surge after %.0f %% throttle for %.1f s (up to %.0f bike rpm): %d 'he's".format(tps, hold, maxRpm, n))
            return n
        }
        assertTrue("mid-rpm pull", heCount(40.0, 1.5) >= 9)
        assertTrue("big pull", heCount(100.0, 1.5) >= 11)
        assertEquals("quick blip", 0, heCount(40.0, 0.15))
    }

    /** Pai: the surge kept playing after he got back on the throttle. Reopening it must stop
     *  the surge within ~60 ms; while the throttle stays shut it plays on. */
    @Test fun surgeStopsWhenBackOnTheThrottle() {
        val t = turbo()
        t.run(1.5, 1.0, 0.8, false)
        t.snap(blowOff = false, flutter = true)
        val shut = t.run(0.25, 0.0, 0.8, false)                 // surging
        val back = DoubleArray((0.4 * sr).toInt()) { t.next(0.0, 0.8, true, false, throttleNow = 0.6) }
        val during = rms(shut.copyOfRange((0.1 * sr).toInt(), shut.size))
        val after = rms(back.copyOfRange((0.06 * sr).toInt(), back.size))
        println("surge: rms %.3f while shut, %.5f from 60 ms after reopening".format(during, after))
        assertTrue("surge should play while shut", during > 0.01)
        assertTrue("surge should stop when reopened", after < during * 0.01)
    }

    @Test fun switchesOffMeansNoSound() {
        val t = turbo()
        t.run(1.5, 1.0, 0.8, false)
        t.snap(blowOff = false)
        assertTrue("boost kept when nothing lets it out", t.pressure > 0.5)
        val x = t.run(0.5, 0.0, 0.8, false)
        assertEquals(0.0, x.maxOf { abs(it) }, 0.0)
    }

    /** Engine sound off (Pai: turbo + blow-off on the real single): the engine's own sound is
     *  gone, but the turbo still spools with the revs and the blow-off still fires on let-off.
     *  Also writes `single_turbo_only.wav` (two revs, both let off). */
    @Test fun engineMutedLeavesTurboAndBlowOff() {
        fun ride(engine: Boolean, turboOn: Boolean): DoubleArray {
            val s = Synth(sr).apply {
                style = Styles.byId("single"); engineSound = engine; turbo = turboOn; blowOff = turboOn
            }
            val sim = EngineSim()
            val pcm = ArrayList<Double>()
            val frame = FloatArray(sr / 60)
            var since = 1.0
            for (f in 0 until 60 * 8) {
                val t = f / 60.0
                if (f == 0) sim.start()
                val tps = if (t in 2.0..2.8 || t in 4.0..6.0) 100.0 else 0.0
                sim.step(1 / 60.0, tps)
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, tps, sim.fuelCut) }
                s.render(frame, frame.size)
                frame.forEach { pcm += it.toDouble() }
            }
            return pcm.toDoubleArray()
        }
        fun part(x: DoubleArray, a: Double, b: Double) = x.copyOfRange((a * sr).toInt(), (b * sr).toInt())
        val silent = ride(engine = false, turboOn = false)
        assertTrue("engine muted, no extras: should be silent", rms(silent) < 1e-3)

        val withEngine = ride(engine = true, turboOn = true)
        val turboOnly = ride(engine = false, turboOn = true)
        // the engine's low notes are gone (single at ~4,000-9,000 rpm: firing 33-80 Hz, harmonics)
        val lowEngine = generateSequence(60.0) { it + 20 }.takeWhile { it <= 600 }.sumOf { power(part(withEngine, 4.5, 5.9), it) }
        val lowTurbo = generateSequence(60.0) { it + 20 }.takeWhile { it <= 600 }.sumOf { power(part(turboOnly, 4.5, 5.9), it) }
        // the whistle is still there while revving, and the blow-off after each let-off
        val whistle = rms(part(turboOnly, 4.5, 5.9))
        val bov1 = generateSequence(6000.0) { it + 50 }.takeWhile { it <= 7000 }.sumOf { power(part(turboOnly, 2.8, 3.3), it) }
        val bov2 = generateSequence(6000.0) { it + 50 }.takeWhile { it <= 7000 }.sumOf { power(part(turboOnly, 6.0, 6.5), it) }
        val quiet = generateSequence(6000.0) { it + 50 }.takeWhile { it <= 7000 }.sumOf { power(part(turboOnly, 1.0, 1.5), it) }
        println("engine muted: engine lows %.0fx lower, whistle rms %.3f, blow-offs %.0fx / %.0fx over idle".format(
            lowEngine / lowTurbo, whistle, bov1 / quiet, bov2 / quiet))
        assertTrue("engine still audible", lowTurbo < lowEngine / 100)
        assertTrue("no whistle", whistle > 0.01)
        assertTrue("no blow-off after the first rev", bov1 > quiet * 50)
        assertTrue("no blow-off after the second rev", bov2 > quiet * 50)

        System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() }?.let { d ->
            File(d).mkdirs()
            write(File(d, "single_turbo_only.wav"), turboOnly)
        }
    }

    /** Lone blow-off and whistle (no engine) to compare with the clips by ear. */
    @Test fun loneTurboWavs() {
        val dir = File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        // the original (6.5 kHz) and higher pitches, to hear the slider's range
        for ((name, hz) in listOf("lone_bov" to 6500.0, "lone_bov_8k" to 8000.0, "lone_bov_9k5" to 9500.0, "lone_bov_11k" to 11000.0)) {
            val b = turbo().apply { blowOffPitch = hz }
            b.run(1.5, 1.0, 0.8, false)
            b.snap(blowOff = true)
            write(File(dir, "$name.wav"), b.run(2.0, 0.0, 0.8, false))
        }
        // whistle: 1.5 s flat out, then let go
        val t = turbo()
        write(File(dir, "lone_whistle.wav"), t.run(1.5, 1.0, 0.8, true) + t.run(1.5, 0.0, 0.8, true))
    }

    private fun write(f: File, x: DoubleArray) {
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val pcm = x.map { (it / peak * 0.9).toFloat() }            // scaled to a 0.9 peak: no clipping
        val bb = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { bb.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        f.writeBytes(bb.array())
    }
}
