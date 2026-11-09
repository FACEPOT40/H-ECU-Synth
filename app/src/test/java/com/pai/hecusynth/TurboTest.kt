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
        val t = turbo().apply { blowOffSound = BlowOffSound.HISS }
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

    /** Whistle blow-off, like the SQV clip: an airy "tsss" centred near 6.4 kHz with more hiss
     *  up to ~13 kHz, ~400 ms long; about as loud as the hiss one. No sharp note: pure tones,
     *  and even very narrow bands, sounded like a bell (Pai). */
    @Test fun whistleBlowOffIsAnAiryTsss() {
        fun blow(kind: BlowOffSound): DoubleArray {
            val t = turbo().apply { blowOffSound = kind }
            t.run(1.5, 1.0, 0.8, false)
            t.snap(blowOff = true)
            return t.run(0.7, 0.0, 0.8, false)
        }
        val w = blow(BlowOffSound.WHISTLE)
        val h = blow(BlowOffSound.HISS)
        val head = w.copyOfRange(0, (0.3 * sr).toInt())
        // 5 Hz steps: the main note is only ~30 Hz wide, coarser steps miss it
        fun band(a: Double, b: Double) = generateSequence(a) { it + 5 }.takeWhile { it < b }.sumOf { power(head, it) }
        val all = band(2500.0, 20000.0)
        val note = band(5500.0, 7500.0)
        val bright = band(7500.0, 13000.0)
        val long = rms(w.copyOfRange((0.3 * sr).toInt(), (0.45 * sr).toInt())) / rms(head)
        println("whistle blow-off: %.0f %% at 5.5-7.5 kHz (clip ~79 %%), %.0f %% at 7.5-13 kHz (clip ~11 %%), level vs hiss %.1f dB, 0.3-0.45 s at %.2f of the start"
            .format(100 * note / all, 100 * bright / all, 20 * kotlin.math.log10(rms(head) / rms(h.copyOfRange(0, (0.3 * sr).toInt()))), long))
        assertTrue("centred near 6.4 kHz", note / all > 0.6)
        // airy, not a ringing note: no narrow peak far above its neighbours
        val peak = generateSequence(6200.0) { it + 5 }.takeWhile { it < 6600 }.maxOf { power(head, it) }
        val near = generateSequence(6200.0) { it + 5 }.takeWhile { it < 6600 }.map { power(head, it) }.sorted().toList().let { it[it.size / 2] }
        assertTrue("a ringing note (peak ${peak / near}x its neighbours)", peak < near * 30)
        assertTrue("airy top", bright / all in 0.05..0.25)
        assertTrue("long tail", long > 0.05)
        assertTrue("silent after", rms(w.copyOfRange((0.62 * sr).toInt(), w.size)) < rms(head) * 0.01)
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
        for ((name, kind) in listOf("lone_bov" to BlowOffSound.HISS, "lone_bov_whistle" to BlowOffSound.WHISTLE)) {
            val b = turbo().apply { blowOffSound = kind }
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
