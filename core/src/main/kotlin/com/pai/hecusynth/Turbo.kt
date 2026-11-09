package com.pai.hecusynth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turbo whistle and blow-off valve, each switched on its own (Pai, 9 Nov: "for fun"). Shaped
 * by measuring a clip Pai sent (an HKS blow-off on a Subaru, plus the whistle in a turbo clip;
 * measured only, never copied or shipped):
 *  - Whistle: a clean tone that climbs from ~3 to ~7.7 kHz in ~0.8 s as the turbo spools up,
 *    and slides back to ~2.6 kHz over ~1.2 s as it spins down. Here: pitch = 2.5 + 5.5 kHz x
 *    turbo speed.
 *  - Blow-off: a narrow ~6.5 kHz "tsss" (80-90 % of it in a ~150 Hz wide band), up in 15-20 ms,
 *    then chattering as it fades over ~150 ms.
 * Pai liked both. The blow-off's pitch can be moved ([blowOffPitch]; Pai wanted a higher one
 * like an SQV-type valve). Three tries at a separate "whistle" blow-off all sounded like a
 * bell to Pai, so it's this same sound, only shifted in pitch. (A turbo flutter was tried and dropped: not enough reference samples to get
 * it right, Pai's call.)
 *
 * Turbo flutter = compressor SURGE (Pai, 9 Nov, 2nd try: "a stutututu that comes from the
 * extremely high boost that suddenly got nowhere else to go ... aggressively come back to the
 * turbine"). Measured from four surge clips Pai sent (never shipped): it is SLOW and DEEP -
 * each "tu" is a swell of air ~80-150 ms long (rise ~40-60 ms, fall ~30-50 ms) with small
 * 20-30 ms ripples inside, the sound drops to ~0.1 between them, ~6-10 "tu"s a second slowing
 * down, mostly 300-1000 Hz (48-85 %) with some 1-4 kHz, each "tu" darker than the last
 * (~3.5 kHz -> ~1 kHz bright), ~0.4-0.85 s in all. (The first try wobbled at 38/s, too fast
 * and too shallow; separate thumps = "PVC", irregular sharp hits = "rain on zinc".)
 * Each "tu" is VOICED, like a horse's whinny "he he he he" (Pai): strongly pitched
 * (autocorrelation 0.8-0.95), the pitch gliding down across the whole surge, ~1040 -> ~660 Hz
 * after big boost (~630 -> ~450 Hz after less), mostly the note plus some overtone. So the
 * voice is a buzzy note (4 harmonics) with small random pitch / loudness wavers (a voice, not
 * a bell) that SWELLS in with each "tu", over a little breath noise. (Whispered noise "tu"s
 * had the right rhythm but the wrong pitch.)
 * Plain Kotlin, driven by [Synth].
 */
class Turbo(private val sr: Double, private val rnd: () -> Double) {
    /** How fast the turbo spins, 0..1 (sets the whistle pitch and loudness). */
    var speed = 0.0
        private set
    /** Boost pressure built up, 0..1 (what a blow-off lets out). */
    var pressure = 0.0
        private set

    private var whistlePh = 0.0
    private var wobble = 0.0

    // blow-off
    private var bovT = -1.0             // seconds into the blow-off, < 0 = none
    private var bovSize = 0.0
    private var chatter = 0.0
    private var chatter2 = 0.0
    private val bovBand = Svf().apply { set(6500.0, 25.0, sr) }
    private var bovLevel = 1.0

    /** Blow-off pitch in Hz: 6500 = the original sound; Pai picked 8000 (the app default, set
     *  by [Synth]). Takes effect at the next blow-off. */
    var blowOffPitch = 6500.0
    /** Blow-off loudness, 0..2 (1 = the tuned level). Applies right away. */
    var blowOffVolume = 1.0

    // surge ("turbo flutter")
    private var surgeIdx = 0            // which "tu"
    private var surgeTotal = 0          // 0 = no surge
    private var surgeT = 0.0            // seconds into this "tu"
    private var surgeLen = 0.1          // this "tu"'s length
    private var surgeSize = 0.0         // boost at the let-off
    private var tuAmp = 0.0
    private var surgeFade = 1.0         // drops to 0 in ~30 ms when the throttle opens again
    private val surgeFadeStep = kotlin.math.exp(-1.0 / (0.01 * sr))
    private val surgeBody = Svf()       // the "tu": air ~300-1000 Hz (wide, no ringing note)
    private val surgeEdge = Svf()       // the "st": a brighter edge at each "tu"'s start
    private var fStart = 1000.0         // the voice's starting pitch (higher after more boost)
    private var vPh = 0.0
    private var vJit = 0.0              // small random pitch waver
    private var vShim = 0.0             // small random loudness waver

    private fun startTu() {
        val prog = surgeIdx.toDouble() / surgeTotal
        surgeT = 0.0
        // ~12.5 -> 7.5 a second (Pai: "faster a bit" than the clips' ~10 -> 6)
        surgeLen = (1.0 / (12.5 - 5.0 * prog)) * (0.9 + 0.2 * rnd())
        surgeBody.set(850.0 - 300.0 * prog, 2.0, sr)                        // darker each time
        surgeEdge.set(1800.0 - 800.0 * prog, 2.0, sr)
        tuAmp = surgeSize * (1 - 0.55 * prog) * (0.85 + 0.3 * rnd())
    }

    /** Throttle let off: with [blowOff] on, the valve lets the boost out. Every let-off after
     *  some throttle gives one (Pai: "every time we let off the throttle"), bigger after a
     *  long pull. */
    fun snap(blowOff: Boolean, flutter: Boolean = false) {
        val p = pressure
        if (p < 0.1 || !(blowOff || flutter)) return
        if (blowOff) {
            bovT = 0.0
            bovSize = 0.35 + 0.65 * p
            val hz = blowOffPitch.coerceIn(PITCH_MIN, PITCH_MAX)
            bovBand.set(hz, 25.0, sr)                       // same sharpness, only the pitch moves
            bovLevel = kotlin.math.sqrt(6500.0 / hz)        // a higher band holds more noise: same loudness
        }
        // surge needs some boost (a pull, not a quick blip). Pai doesn't rev the real bike that
        // high, so a moderate pull at mid revs (boost > 0.25) is enough, and it then sounds
        // nearly like the full surge: the size goes by 0.6 + 0.4 x boost, so after a big pull
        // (boost ~0.95) it's the approved sound unchanged, at mid revs a little shorter / lower
        if (flutter && p > 0.25) {
            val big = 0.6 + 0.4 * p
            surgeSize = big
            fStart = 600.0 + 450.0 * big                        // ~1040 Hz after a big pull
            surgeTotal = (4 + 10 * big).toInt()                 // Pai: "longer a bit": ~1.3 s after a big pull
            surgeIdx = 0
            surgeFade = 1.0
            startTu()
        }
        pressure = 0.0
    }

    /**
     * One sample. [load] 0..1 throttle (smoothed), [revs] 0..1 of the style's rev range,
     * [whistle] = the whistle switch, [throttleNow] = the latest throttle reading, unsmoothed:
     * back on the throttle stops a surge at once (Pai: it kept playing while he rode on).
     * Returns the sound to add after the engine's saturation.
     */
    fun next(load: Double, revs: Double, running: Boolean, whistle: Boolean, throttleNow: Double = load): Double {
        // spool: up with throttle and revs (~0.6 s), down when let go (~0.45 s: clip ~7.7 -> 2.6 kHz in ~1.2 s)
        val target = if (running) (0.12 + 0.88 * ((revs - 0.1) / 0.6).coerceIn(0.0, 1.0)) * (0.1 + 0.9 * load) else 0.0
        speed += (target - speed) / ((if (target > speed) 0.6 else 0.45) * sr)
        // boost builds quickly with throttle (more with turbo speed), leaks away slowly when
        // it's shut. (It used to need high revs and a long pull, so only the limiter got a
        // blow-off.)
        // Boost follows how hard the turbo spins once you're on the throttle (half open or more
        // counts as fully on), so a moderate pull at mid revs builds real boost (Pai rides at
        // mid rpm). It builds over ~0.3 s, so a quick blip doesn't make a surge.
        val pTarget = if (load > 0.2) (0.5 + 0.5 * speed) * minOf(1.0, load / 0.5) else 0.0
        pressure += (pTarget - pressure) / ((if (pTarget > pressure) 0.3 else 1.5) * sr)

        var out = 0.0

        // surge: each "tu" swells (40 %), holds briefly, falls; deep gap (~0.08) between; the
        // whistle (if on) is chopped with it
        var gate = 1.0
        if (surgeTotal > 0 && throttleNow > 0.2) surgeFade *= surgeFadeStep   // back on the throttle: fade out
        if (surgeTotal > 0 && surgeFade < 0.001) surgeTotal = 0
        if (surgeTotal > 0) {
            val ph = surgeT / surgeLen
            val env = when {
                ph < 0.4 -> sin(ph / 0.4 * PI / 2).let { it * it }
                ph < 0.55 -> 1.0
                else -> ((1 - ph) / 0.45).let { it * it }
            }
            val ripple = 1 + 0.25 * sin(2 * PI * 35 * surgeT)                 // the 20-30 ms ripples
            // the voice: pitch glides down across the whole surge (x0.62), and a little within
            // each "he"; tiny random wavers in pitch and loudness keep it a voice, not a bell
            val prog = (surgeIdx + minOf(1.0, ph)) / surgeTotal
            vJit += (rnd() * 2 - 1 - vJit) * 0.002
            vShim += (rnd() * 2 - 1 - vShim) * 0.004
            val f0 = fStart * 0.62.pow(prog) * (1 - 0.03 * ph) * (1 + 0.04 * vJit)
            vPh += f0 / sr
            if (vPh >= 1) vPh -= 1.0
            val a = 2 * PI * vPh
            val voice = (sin(a) + 0.35 * sin(2 * a) + 0.15 * sin(3 * a) + 0.06 * sin(4 * a)) * (1 + 0.6 * vShim)
            val nz = rnd() * 2 - 1
            surgeBody.process(nz)
            surgeEdge.process(nz)
            val st = env * exp(-ph * 4)                                       // edge mostly at the start
            val breath = surgeBody.band * BREATH + surgeEdge.band * 0.25 * st
            out += (voice * VOICE + breath) * (0.12 + 0.88 * env) * ripple * tuAmp * SURGE * surgeFade
            gate = 1 - (0.75 - 0.75 * env) * surgeFade
            surgeT += 1 / sr
            if (surgeT >= surgeLen) {
                surgeIdx++
                if (surgeIdx < surgeTotal) startTu() else surgeTotal = 0
            }
        }

        // whistle
        if (whistle && speed > 0.01) {
            wobble += (rnd() * 2 - 1 - wobble) * 0.001
            val hz = (2500 + 5500 * speed) * (1 + 0.004 * wobble)
            whistlePh += hz / sr
            if (whistlePh >= 1) whistlePh -= 1.0
            val w = sin(2 * PI * whistlePh) + 0.15 * sin(4 * PI * whistlePh)
            out += w * WHISTLE * speed.pow(1.5) * (0.35 + 0.65 * load) * gate
        }

        // blow-off: up in 15 ms, fades over ~150 ms, chattering
        if (bovT >= 0) {
            val env = if (bovT < 0.015) bovT / 0.015 else exp(-(bovT - 0.015) / 0.06)
            chatter += (rnd() * 2 - 1 - chatter) * 0.01    // valve chatter, a few tens per second
            chatter2 += (chatter - chatter2) * 0.01
            val mod = (0.6 + 4.0 * chatter2).coerceIn(0.15, 1.3)
            bovBand.process(rnd() * 2 - 1)
            out += bovBand.band * env * mod * bovSize * BLOW_OFF * bovLevel * blowOffVolume
            bovT += 1 / sr
            if (bovT > 0.4) bovT = -1.0
        }
        return out
    }

    companion object {
        // levels next to the engine (calibrated with TurboTest / the demo WAVs)
        const val WHISTLE = 0.35
        const val BLOW_OFF = 2.4
        const val SURGE = 3.0           // as loud as the blow-off or more on the demos
        const val VOICE = 0.25          // the "he" voice ...
        const val BREATH = 0.35         // ... over a little breath noise
        const val PITCH_MIN = 5000.0
        const val PITCH_MAX = 11000.0
    }
}
