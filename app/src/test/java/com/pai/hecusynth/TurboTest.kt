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

/** Turbo whistle, flutter and blow-off (Turbo.kt). */
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
        t.snap(blowOff = true, flutter = false)
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

    @Test fun flutterChuffsFastThenSlower() {
        val t = turbo()
        t.run(1.5, 1.0, 0.8, false)
        t.snap(blowOff = false, flutter = true)
        val x = t.run(2.0, 0.0, 0.8, false)
        // loudness 1,000 times a second, then its strongest repeat rate early and late
        val env = x.toList().chunked(48).map { ch -> sqrt(ch.sumOf { it * it } / ch.size) }
        fun rate(a: Int, b: Int): Double {
            val e = env.subList(a, b); val m = e.average()
            return generateSequence(5.0) { it + 0.5 }.takeWhile { it <= 80 }.maxBy { f ->
                var re = 0.0; var im = 0.0
                e.forEachIndexed { i, v -> re += (v - m) * cos(2 * PI * f * i / 1000); im += (v - m) * sin(2 * PI * f * i / 1000) }
                re * re + im * im
            }
        }
        val early = rate(0, 200)
        val late = rate(700, 950)
        println("flutter: %.1f chuffs/s early, %.1f later (clip ~35-40 -> ~12)".format(early, late))
        assertTrue("early $early", early in 28.0..45.0)
        assertTrue("late $late", late < early - 8)
    }

    @Test fun switchesOffMeansNoSound() {
        val t = turbo()
        t.run(1.5, 1.0, 0.8, false)
        t.snap(blowOff = false, flutter = false)
        assertTrue("boost kept when nothing lets it out", t.pressure > 0.5)
        val x = t.run(0.5, 0.0, 0.8, false)
        assertEquals(0.0, x.maxOf { abs(it) }, 0.0)
    }

    /** Lone blow-off and flutter (no engine) to compare with the clips by ear. */
    @Test fun loneTurboWavs() {
        val dir = File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        for ((name, bov, flut) in listOf(Triple("lone_bov", true, false), Triple("lone_flutter", false, true))) {
            val t = turbo()
            t.run(1.5, 1.0, 0.8, false)
            t.snap(bov, flut)
            write(File(dir, "$name.wav"), t.run(2.0, 0.0, 0.8, false))
        }
        // whistle: 1.5 s flat out, then let go
        val t = turbo()
        write(File(dir, "lone_whistle.wav"), t.run(1.5, 1.0, 0.8, true) + t.run(1.5, 0.0, 0.8, true))
    }

    /** Flutter versions for Pai to pick by ear: A = low-mid "tu" body, B = A slower,
     *  C = B louder and longer. Alone (no engine) and on the I4 (snap at 6 s). */
    @Test fun flutterVersionWavs() {
        val dir = File(System.getProperty("wav.dir")?.takeIf { it.isNotEmpty() } ?: return)
        dir.mkdirs()
        val versions = mapOf<String, Turbo.() -> Unit>(
            "A" to { flutterBody = 1.0 },
            "B" to { flutterBody = 1.0; flutterSpeed = 0.6 },
            "C" to { flutterBody = 1.0; flutterSpeed = 0.6; flutterLength = 1.5; flutterLevel = 1.6 },
        )
        for ((name, knobs) in versions) {
            val t = turbo().apply(knobs)
            t.run(1.5, 1.0, 0.8, false)
            t.snap(blowOff = false, flutter = true)
            write(File(dir, "flutter_$name.wav"), t.run(2.5, 0.0, 0.8, false))

            val s = Synth(sr).apply { style = Styles.byId("i4"); turbo = true; flutter = true; turboFx.knobs() }
            val sim = EngineSim()
            val pcm = ArrayList<Double>()
            val frame = FloatArray(sr / 60)
            var since = 1.0
            for (f in 0 until 60 * 9) {
                val tt = f / 60.0
                if (f == 0) sim.start()
                val tps = if (tt in 3.4..6.0) 100.0 else 0.0
                sim.step(1 / 60.0, tps)
                since += 1 / 60.0
                if (since >= 1 / 17.7) { since = 0.0; s.set(sim.rpm, tps, sim.fuelCut) }
                s.render(frame, frame.size)
                frame.forEach { pcm += it / 4.0 }            // write() makes it 4x louder again
            }
            write(File(dir, "i4_flutter_$name.wav"), pcm.toDoubleArray())
        }
    }

    private fun write(f: File, x: DoubleArray) {
        val pcm = x.map { (it * 4).toFloat() }                    // louder: alone, it's quiet
        val bb = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { bb.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        f.writeBytes(bb.array())
    }
}
