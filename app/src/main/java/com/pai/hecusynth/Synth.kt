package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Engine sound made from scratch, one sample at a time. Plain Kotlin (no Android), so the unit
 * tests can render it to WAV files on the PC.
 *
 * How it works, in the order the sound flows:
 *  1. A virtual crankshaft turns at the style's rpm. Each time it passes a cylinder's firing
 *     angle, that cylinder sends an exhaust pulse (a short pressure bump) plus a little noise
 *     burst (the combustion "crack"). Throttle makes the pulses louder.
 *  2. The pulses go through a short echo (the exhaust pipe), which gives the metallic ring.
 *  3. A tone filter shapes it: dark with the throttle closed, bright when it's open.
 *  4. Intake roar, turbo whistle and exhaust pops are added, then soft saturation for rasp.
 *
 * Threads: the screen calls [set] and changes the settings; the audio thread calls [render].
 * Bike data arrives in steps (K-line: ~17 per second), so the rpm glides from one reading to the
 * next over the time between readings instead of jumping (a jump would sound like a zipper).
 */
class Synth(val sampleRate: Int, seed: Long = 7L) {
    // ---- settings (any thread)
    @Volatile var style: EngineStyle = Styles.ALL[0]
    @Volatile var bike = BikeRange()
    @Volatile var volume = 0.7
    @Volatile var camEnabled = false
    @Volatile var camRpm = 6500.0          // bike rpm
    @Volatile var crackles = false          // exhaust pops: an extra, off by default

    // ---- read back for the screen
    @Volatile var camHigh = false
        private set

    // ---- inputs
    @Volatile private var inRpm = 0.0
    @Volatile private var inTps = 0.0
    @Volatile private var inCut = false
    @Volatile private var inSeq = 0

    /** New bike data: rpm, throttle 0..100 %, and whether the fuel is cut (overrun / limiter). */
    fun set(bikeRpm: Double, tpsPct: Double, fuelCut: Boolean) {
        inRpm = bikeRpm; inTps = tpsPct; inCut = fuelCut
        inSeq += 1                          // written last: the audio thread sees a full set
    }

    private val sr = sampleRate.toDouble()

    // ---- noise (xorshift: fast, and the same every run for the tests)
    private var rng = seed * 0x9E3779B97F4A7C15uL.toLong() or 1L
    private fun noise(): Double {
        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
        return (rng ushr 11).toDouble() / (1L shl 53) * 2.0 - 1.0
    }
    private fun rand01() = (noise() + 1.0) * 0.5

    // ---- inputs as the audio thread sees them
    private var seenSeq = -1
    private var sinceInput = 0
    private var rpm = 0.0
    private var rpmStep = 0.0
    private var rampLeft = 0
    private var load = 0.0
    private var targetLoad = 0.0
    private var cut = false
    private var lastTps = 0.0
    private val cam = CamSwitch()
    private var camMix = 0.0
    private var camKick = 0.0

    // ---- the engine
    private var built: EngineStyle? = null
    private var crank = 0.0
    private var next = DoubleArray(0)
    private var jit = DoubleArray(0)
    private var cylAmp = DoubleArray(0)
    private var cylWidth = DoubleArray(0)
    private var phase = DoubleArray(0)
    private var amp = DoubleArray(0)
    private var barkEnv = DoubleArray(0)
    private var popEnv = 0.0

    // ---- sound shaping
    private val pipe = DoubleArray((sr * 0.02).toInt() + 2)
    private var pipeIdx = 0
    private var pipeLp = 0.0
    private val body = Svf()
    private val intake = Svf()
    private var hpLp = 0.0
    private var dcX = 0.0
    private var dcY = 0.0
    private var outGain = 0.0
    private var coefCount = 0

    // ---- turbo (V6)
    private var boost = 0.0
    private var whinePhase = 0.0
    private var bov = 0.0

    private val barkDecay = exp(-1.0 / (0.0025 * sr))
    private val popDecay = exp(-1.0 / (0.006 * sr))
    private val kickDecay = exp(-1.0 / (0.15 * sr))
    private val bovDecay = exp(-1.0 / (0.35 * sr))

    private fun build(st: EngineStyle) {
        built = st
        val n = st.cylinders
        // each cylinder's own fixed character: loudness, timing and pulse length
        cylAmp = DoubleArray(n) { 1.0 + st.unevenAmp * (rand01() * 2 - 1) }
        next = DoubleArray(n) { st.firing[it] + st.unevenDeg * (rand01() * 2 - 1) + 1.0 }
        cylWidth = DoubleArray(n) { st.pulseDeg * (1.0 + st.unevenWidth * (rand01() * 2 - 1)) }
        jit = DoubleArray(n)
        phase = DoubleArray(n) { -1.0 }
        amp = DoubleArray(n)
        barkEnv = DoubleArray(n)
        crank = 0.0
        pipe.fill(0.0); pipeLp = 0.0
        body.reset(); intake.reset()
        boost = 0.0; bov = 0.0
    }

    /** One exhaust pulse, p = 0..1 over its length: quick rise, then a fall that swings a little
     *  below zero (the pressure drop after the bump) and ends at 0. */
    private fun shape(p: Double): Double {
        if (p < 0.12) return sin(p / 0.12 * PI / 2)
        val d = (p - 0.12) / 0.88
        val r = 1 - d
        return r * r * (1 - 1.7 * d)
    }

    fun render(out: FloatArray, frames: Int) {
        val st = style
        if (st !== built) build(st)
        val range = bike

        // new bike data: glide to it over the time since the last reading (12..150 ms)
        val seq = inSeq
        if (seq != seenSeq) {
            seenSeq = seq
            val len = sinceInput.coerceIn((0.012 * sr).toInt(), (0.15 * sr).toInt())
            rpmStep = (inRpm - rpm) / len
            rampLeft = len
            val tps = inTps.coerceIn(0.0, 100.0)
            targetLoad = tps / 100.0
            if (st.turbo && lastTps - tps > 25 && boost > 0.3) bov = boost   // blow-off valve
            lastTps = tps
            cut = inCut
            sinceInput = 0
        }
        sinceInput += frames

        val high = cam.update(rpm, camRpm, camEnabled)
        if (high && !camHigh) camKick = 1.0
        camHigh = high
        val camTarget = if (high) 1.0 else 0.0

        val n = st.cylinders
        val overlap = max(1.0, n * st.pulseDeg / 720.0)
        val norm = (1 - st.pipeFeedback * 0.7) / overlap.pow(0.6)
        val pipeLen = (st.pipeMs / 1000.0 * sr).toInt().coerceIn(1, pipe.size - 1)
        val loadCoef = 1.0 / (0.03 * sr)
        val camCoef = 1.0 / (0.03 * sr)
        val vol = volume * st.gain

        for (i in 0 until frames) {
            if (rampLeft > 0) { rpm += rpmStep; rampLeft-- }
            if (rpm < 0) rpm = 0.0
            load += (targetLoad - load) * loadCoef
            camMix += (camTarget - camMix) * camCoef
            camKick *= kickDecay

            val rpmS = range.toStyle(rpm, st)
            val running = rpmS > 120
            val dDeg = rpmS * 6.0 / sr
            val fireHz = st.firingHz(rpmS)
            val spread = ((rpmS - st.idle) / (st.redline - st.idle)).coerceIn(0.0, 1.0)

            // 1. crank and cylinders
            crank += dDeg
            var excite = 0.0
            for (c in 0 until n) {
                if (crank >= next[c]) {
                    val over = crank - next[c]
                    next[c] += 720.0 - jit[c]
                    jit[c] = st.jitterDeg * (1 - 0.8 * spread) * (rand01() * 2 - 1)
                    next[c] += jit[c]
                    if (running) fire(c, st, over, rpmS)
                }
                val p = phase[c]
                if (p >= 0) {
                    excite += amp[c] * shape(p)
                    val np = p + dDeg / cylWidth[c]
                    phase[c] = if (np >= 1) -1.0 else np
                }
                if (barkEnv[c] > 1e-5) {
                    excite += barkEnv[c] * noise()
                    barkEnv[c] *= barkDecay
                }
            }
            if (crank >= 720.0) {
                crank -= 720.0
                for (c in 0 until n) next[c] -= 720.0
            }
            var pop = 0.0
            if (popEnv > 1e-4) {
                pop = popEnv * (0.5 + noise())
                popEnv *= popDecay
            }
            excite += pop

            // 2. exhaust pipe echo (with a little damping in the loop)
            var readIdx = pipeIdx - pipeLen
            if (readIdx < 0) readIdx += pipe.size
            pipeLp += (pipe[readIdx] - pipeLp) * 0.5
            val y = excite + st.pipeFeedback * pipeLp
            pipe[pipeIdx] = y
            pipeIdx = (pipeIdx + 1) % pipe.size

            // 3. tone filter (coefficients every 16 samples: tan() is slow)
            if (coefCount-- <= 0) {
                coefCount = 16
                val open = if (cut) 0.3 * load else load
                val fc = (st.toneHz + st.toneLoadHz * open) * (1 + 0.5 * camMix) + 1.5 * fireHz
                body.set(min(fc, 0.42 * sr), st.resonance, sr)
                intake.set(min(600 + 2.5 * fireHz, 6000.0), 0.8, sr)
            }
            body.process(y)

            // 4. intake roar, turbo, pops, saturation
            val alive = min(1.0, rpmS / st.idle)    // intake only breathes while the engine turns
            val roarAmp = alive * st.intake * (0.15 + load) * (0.3 + 0.7 * spread) * (1 + 0.8 * camMix)
            intake.process(noise())
            val roar = intake.band * roarAmp * (0.6 + min(1.0, abs(excite)))

            val nz = noise()
            hpLp += (nz - hpLp) * 0.3
            val hiss = nz - hpLp                     // high-passed noise
            val crackle = pop * hiss * 0.6

            var pre = (body.low * 0.85 + y * 0.15) * norm + roar + crackle
            val drv = st.drive * (1 + 0.35 * camMix) * (0.7 + 0.6 * load)
            pre = tanh(drv * pre) / tanh(drv)
            if (st.growl > 0) {
                val cyc = 2 * PI * crank / 720.0
                pre *= 1 + st.growl * (0.6 * sin(cyc) + 0.4 * sin(2 * cyc + 1.3))
            }

            if (st.turbo) {
                val tgt = if (cut) 0.0 else load * ((rpmS - 0.35 * st.redline) / (0.4 * st.redline)).coerceIn(0.0, 1.0)
                boost += (tgt - boost) / ((if (tgt > boost) 0.7 else 0.25) * sr)
                whinePhase += (1800 + 6500 * boost) / sr
                if (whinePhase >= 1) whinePhase -= 1.0
                pre += sin(2 * PI * whinePhase) * 0.05 * boost
                if (bov > 1e-4) {
                    pre += hiss * bov * 0.15
                    bov *= bovDecay
                }
            }

            // DC blocker, smooth volume, soft limit (never a hard clip)
            val d = pre - dcX + 0.995 * dcY
            dcX = pre; dcY = d
            outGain += (vol - outGain) * 0.0005
            out[i] = tanh(d * outGain * 1.1 * (1 + 0.25 * camKick)).toFloat()
        }
    }

    private fun fire(c: Int, st: EngineStyle, over: Double, rpmS: Double) {
        val loud = if (cut) 0.12 else 0.28 + 0.72 * load
        // a little random loudness per firing, mostly for the slow singles and twins: on an
        // engine firing 1,000+ times a second it only adds hiss
        val rough = 0.08 * min(1.0, st.jitterDeg / 4.0)
        val a = loud * cylAmp[c] * (1 + rough * (rand01() - 0.5)) * (1 + 0.3 * camKick)
        phase[c] = min(over / cylWidth[c], 0.99)
        amp[c] = a
        barkEnv[c] = if (cut) 0.0 else st.bark * (0.3 + 0.7 * load) * a
        // exhaust pops: unburnt fuel lighting in the pipe while the fuel is cut at high revs.
        // About 12 a second, whatever the number of cylinders.
        if (crackles && cut && rpmS > 0.3 * st.redline) {
            val perSecond = st.firingHz(rpmS)
            if (rand01() < 12.0 / perSecond) popEnv = 0.9 + 0.6 * rand01()
        }
    }
}

/** State-variable filter (Simper's TPT form): stable even when the cutoff moves fast. */
class Svf {
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    var low = 0.0
        private set
    var band = 0.0
        private set

    init { set(1000.0, 0.7, 48000.0) }

    fun set(fc: Double, q: Double, sr: Double) {
        val g = tan(PI * fc.coerceIn(20.0, 0.45 * sr) / sr)
        val k = 1.0 / q
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Double) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2 * v1 - ic1
        ic2 = 2 * v2 - ic2
        low = v2
        band = v1
    }

    fun reset() { ic1 = 0.0; ic2 = 0.0; low = 0.0; band = 0.0 }
}
