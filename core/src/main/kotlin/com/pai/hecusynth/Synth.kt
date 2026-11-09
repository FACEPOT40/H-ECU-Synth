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
    companion object {
        /** "Engine bark" pop size next to the engine's own pulses (1.0 = a full-throttle bang). */
        const val BARK_GAIN = 2.5
    }

    // ---- settings (any thread)
    @Volatile var style: EngineStyle = Styles.ALL[0]
    @Volatile var bike = BikeRange()
    @Volatile var volume = 0.7
    @Volatile var camEnabled = false
    @Volatile var camRpm = 6500.0          // bike rpm
    @Volatile var crackles = false          // exhaust pops: an extra, off by default
    @Volatile var bangs = false             // backfire bangs: an extra, off by default
    @Volatile var popSound = PopSound.BARK  // how pops and bangs sound

    // ---- read back for the screen
    @Volatile var camHigh = false
        private set
    /** At the rev limiter: the synth is playing its own fast cut / fire stutter. */
    @Volatile var limiting = false
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
    private var cutIn = false               // fuel cut as the bike / simulator reports it
    private var cut = false                 // fuel cut right now (the input, or our own limiter)
    private var atLimiter = false
    private var limCount = 0                // firings counted at the limiter
    private var limPos = 0.0                // where in the cut / fire pattern we are, 0..1
    private var lastTps = 0.0
    private val cam = CamSwitch()
    private var camMix = 0.0
    private var camKick = 0.0

    // ---- the engine
    private var built: EngineStyle? = null
    private var crank = 0.0
    private var next = DoubleArray(0)
    private var off = DoubleArray(0)        // this cycle's timing offset (character + wobble)
    private var uAmp = DoubleArray(0)       // each cylinder's fixed character, -1..1; scaled by
    private var uDeg = DoubleArray(0)       // the style's uneven* settings every block, so the
    private var uWidth = DoubleArray(0)     // tuning sliders work live
    private var cylAmp = DoubleArray(0)
    private var cylWidth = DoubleArray(0)
    private var phase = DoubleArray(0)
    private var amp = DoubleArray(0)
    private var barkEnv = DoubleArray(0)
    private var barkDec = DoubleArray(0)    // per cylinder: a bark's noise lasts longer
    private var popW = DoubleArray(0)       // per cylinder: a bark's pulse is longer
    private var idleF = 0.0                 // 1 at idle, 0 from ~35 % of the rev range up

    // ---- big-cam lope: the idle hunts up and down, bangs come uneven, some go weak
    private var lopeOff = 0.0
    private var lopeTarget = 0.0
    private var lopeTimer = 0

    // ---- pops and bangs: bursts of rough blasts through the engine's own exhaust (see Backfire)
    // its own random numbers, so switching pops on or off never changes the engine's own sound
    private var rngFx = seed * 0x2545F4914F6CDD1DL or 1L
    private fun randFx(): Double {
        rngFx = rngFx xor (rngFx shl 13); rngFx = rngFx xor (rngFx ushr 7); rngFx = rngFx xor (rngFx shl 17)
        return (rngFx ushr 11).toDouble() / (1L shl 53)
    }
    private val backfire = Backfire(sr) { randFx() }
    private var wasCoasting = false
    private var lastSpread = 0.0


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
    private val popBarkDecay = exp(-1.0 / (0.008 * sr))
    private val kickDecay = exp(-1.0 / (0.15 * sr))
    private val bovDecay = exp(-1.0 / (0.35 * sr))

    private fun build(st: EngineStyle) {
        built = st
        val n = st.cylinders
        // each cylinder's own fixed character: loudness, timing and pulse length
        uAmp = DoubleArray(n) { rand01() * 2 - 1 }
        uDeg = DoubleArray(n) { rand01() * 2 - 1 }
        uWidth = DoubleArray(n) { rand01() * 2 - 1 }
        cylAmp = DoubleArray(n)
        cylWidth = DoubleArray(n)
        off = DoubleArray(n) { st.unevenDeg * uDeg[it] }
        next = DoubleArray(n) { st.firing[it] + off[it] + 1.0 }
        phase = DoubleArray(n) { -1.0 }
        amp = DoubleArray(n)
        barkEnv = DoubleArray(n)
        barkDec = DoubleArray(n) { barkDecay }
        popW = DoubleArray(n) { 1.0 }
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
        // a different engine layout starts a new engine; a tuned copy of the same one carries on
        // without a click (only its settings change)
        val old = built
        if (old == null || old.id != st.id || !old.firing.contentEquals(st.firing)) build(st)
        built = st
        for (c in 0 until st.cylinders) {
            cylAmp[c] = 1.0 + st.unevenAmp * uAmp[c]
            cylWidth[c] = st.pulseDeg * (1.0 + st.unevenWidth * uWidth[c])
        }
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
            // throttle snapped shut from high revs: a big bang leading a burst of pops
            if (bangs && lastTps - tps > 40 && lastSpread > 0.4) bang()
            lastTps = tps
            cutIn = inCut
            sinceInput = 0
        }
        sinceInput += frames

        // Rev limiter: a real one cuts and restores the fuel 20-40 times a second
        // ("tatatata"). The K-line's ~17 readings a second can never show that, so at the
        // redline with the throttle open the synth makes the stutter itself, at the style's rate.
        // Like a real one it skips whole firings (e.g. 2 cut, 2 fire on a single), so the
        // pattern can't drift against the bangs and wobble slowly ("ta.. ta..").
        atLimiter = rpm >= range.redline - 250 && targetLoad > 0.3
        limiting = atLimiter

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

            if (!atLimiter) cut = cutIn
            val dip = if (!atLimiter) 0.0 else if (cut) limPos / 0.45 else (1 - limPos) / 0.55
            var rpmS = range.toStyle(rpm, st) * (1 - 0.02 * dip)   // revs sag in each cut
            val running = rpmS > 120
            val spread = ((rpmS - st.idle) / (st.redline - st.idle)).coerceIn(0.0, 1.0)
            lastSpread = spread
            idleF = (1 - spread / 0.35).coerceIn(0.0, 1.0)
            // lope: the idle hunts up and down by up to ~15 %, a new direction every 0.15-0.5 s
            if (st.lope > 0 && running) {
                if (--lopeTimer <= 0) {
                    lopeTarget = rand01() * 2 - 1
                    lopeTimer = ((0.15 + 0.35 * rand01()) * sr).toInt()
                }
                lopeOff += (lopeTarget - lopeOff) / (0.12 * sr)
                rpmS *= 1 + 0.15 * st.lope * idleF * lopeOff
            }
            val dDeg = rpmS * 6.0 / sr
            val fireHz = st.firingHz(rpmS)

            // 1. crank and cylinders
            crank += dDeg
            var excite = 0.0
            for (c in 0 until n) {
                if (crank >= next[c]) {
                    val over = crank - next[c]
                    next[c] += 720.0 - off[c]
                    off[c] = st.unevenDeg * uDeg[c] +
                        (st.jitterDeg * (1 - 0.8 * spread) + 40 * st.lope * idleF) * (rand01() * 2 - 1)
                    next[c] += off[c]
                    if (running && atLimiter) {
                        val m = st.limiterFirings(fireHz)
                        val k = limCount++ % m
                        val cutN = st.limiterCut(m)
                        cut = k < cutN                          // first part cut, rest fire
                        limPos = if (cut) 0.45 * k / cutN else 0.45 + 0.55 * (k - cutN) / (m - cutN)
                    }
                    if (running) fire(c, st, over, rpmS)
                }
                val p = phase[c]
                if (p >= 0) {
                    excite += amp[c] * shape(p)
                    val np = p + dDeg / (cylWidth[c] * popW[c])
                    phase[c] = if (np >= 1) -1.0 else np
                }
                if (barkEnv[c] > 1e-5) {
                    excite += barkEnv[c] * noise()
                    barkEnv[c] *= barkDec[c]
                }
            }
            if (crank >= 720.0) {
                crank -= 720.0
                for (c in 0 until n) next[c] -= 720.0
            }
            // pops and bangs: their own sound, added after the saturation so they stay punchy.
            // Coasting = the fuel is cut with the throttle shut at high revs (not the limiter).
            val coasting = running && cut && !atLimiter && spread > 0.25
            if (coasting && !wasCoasting && spread > 0.3) {
                if (crackles) backfire.burst(4 + (randFx() * 6).toInt(), 0.65 + 0.2 * randFx())
            }
            wasCoasting = coasting
            if (coasting && !backfire.busy) {
                if (crackles && randFx() < 1.3 / sr) {
                    backfire.burst(3 + (randFx() * 4).toInt(), 0.35 + 0.3 * randFx())
                }
                if (bangs && randFx() < 0.3 / sr) bang()
            }
            backfire.sound = popSound
            backfire.step()

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
                val fc = (st.toneHz + st.toneLoadHz * open) * (1 + 0.5 * camMix) + 1.5 * fireHz +
                    3000 * backfire.open                 // a bark opens the sound up
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

            var pre = (body.low * 0.85 + y * 0.15) * norm + roar
            val drv = st.drive * (1 + 0.35 * camMix) * (0.7 + 0.6 * load) * (1 + min(1.5, backfire.open))   // and gets raw
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

            pre += backfire.fx * 0.9                 // crack / shock + air tail, after the rasp

            // DC blocker, smooth volume, soft limit (never a hard clip)
            val d = pre - dcX + 0.995 * dcY
            dcX = pre; dcY = d
            outGain += (vol - outGain) * 0.0005
            out[i] = tanh(d * outGain * 1.1 * (1 + 0.25 * camKick)).toFloat()
        }
    }

    /** A backfire bang: a very loud first pop with a longer tail, then its burst of pops. */
    private fun bang() = backfire.burst(6 + (randFx() * 4).toInt(), 1.5 + 0.3 * randFx(), bang = true)

    private fun fire(c: Int, st: EngineStyle, over: Double, rpmS: Double) {
        // a limiter cut is near silence (that's the "ta" gap); coasting with the fuel cut still
        // pumps air through, so it keeps a soft sound
        val loud = if (cut) (if (atLimiter) 0.03 else 0.12) else 0.28 + 0.72 * load
        // a little random loudness per firing, mostly for the slow singles and twins: on an
        // engine firing 1,000+ times a second it only adds hiss
        val rough = 0.08 * min(1.0, st.jitterDeg / 4.0)
        var a = loud * cylAmp[c] * (1 + rough * (rand01() - 0.5)) * (1 + 0.3 * camKick)
        // big-cam lope: uneven bangs at idle, and now and then a weak one (half-burnt)
        val lope = st.lope * idleF
        if (lope > 0 && !cut) {
            a *= 1 + lope * (rand01() * 1.4 - 0.7)
            if (rand01() < 0.18 * lope) a *= 0.2
        }
        // "Engine bark" pop due: this firing (fuel cut, so unburnt mixture) goes off in the
        // pipe instead: a huge, longer pulse with lots of combustion noise
        val pop = if (cut) backfire.takeBark() else 0.0
        if (pop > 0) {
            a = pop * BARK_GAIN
            popW[c] = 1.6
            barkEnv[c] = pop * 1.0
            barkDec[c] = popBarkDecay
        } else {
            popW[c] = 1.0
            barkEnv[c] = if (cut) 0.0 else st.bark * (0.3 + 0.7 * load + lope) * a
            barkDec[c] = barkDecay
        }
        phase[c] = min(over / (cylWidth[c] * popW[c]), 0.99)
        amp[c] = a
        // at the limiter, now and then a small pop in a cut (about 6 a second)
        if (crackles && cut && atLimiter && !backfire.busy && randFx() < 6.0 / st.firingHz(rpmS)) {
            backfire.burst(1, 0.3 + 0.2 * randFx())
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
