package com.pai.hecusynth

/**
 * One engine sound style. Everything is made from scratch by [Synth]: no recordings.
 *
 * Firing angles are crank degrees in the four-stroke cycle (720° = 2 turns). That pattern is what
 * makes a V-twin lope and an I4 scream: even spacing gives a smooth tone, uneven spacing a beat.
 * A data class so the tuning panel can make changed copies ([Tuning]) while the sound plays.
 */
data class EngineStyle(
    val id: String,
    val label: String,          // button text
    val name: String,           // long name
    val firing: DoubleArray,    // firing angle of each cylinder
    val idle: Double,           // style rpm when the bike idles
    val redline: Double,        // style rpm at the bike's redline
    val pulseDeg: Double,       // how long one exhaust pulse lasts, in crank degrees
    val pipeMs: Double,         // exhaust pipe echo delay (shorter = higher, more metallic ring)
    val pipeFeedback: Double,   // how strong that echo is (0..0.8)
    val toneHz: Double,         // tone filter with the throttle closed
    val toneLoadHz: Double,     // added to it at full throttle (opens up the sound)
    val resonance: Double,      // tone filter peak (Q)
    val drive: Double,          // saturation: higher = rawer, raspier
    val bark: Double,           // combustion crack at each firing
    val intake: Double,         // intake roar with the throttle open
    val jitterDeg: Double,      // firing timing wobble at idle (makes a single "lumpy")
    val unevenAmp: Double,      // loudness differences between cylinders
    // Fixed differences between cylinders (timing, pulse length). Without them, evenly firing
    // engines (I4, V6-V10) collapse into one pure whine, like a vacuum cleaner; with them they
    // get the lower "growl" notes a real engine has.
    val unevenDeg: Double = 0.0,
    val unevenWidth: Double = 0.0,
    // Swell and dip once per cycle (2 turns): how the pulses of the different cylinders and
    // exhaust banks add up unevenly. The low, rough "engine" part of a multi-cylinder's sound.
    val growl: Double = 0.0,
    // Big-cam lope at idle (0..1): the revs hunt up and down, the bangs come uneven in time
    // and loudness, and some go weak. Fades out as the revs rise.
    val lope: Double = 0.0,
    val limiterHz: Double = 28.0, // rev limiter stutter: cut / fire cycles per second
    val turbo: Boolean = false, // turbo whistle + blow-off (V6 hybrid)
    val gain: Double = 1.0,     // evens out the loudness between styles
) {
    val cylinders get() = firing.size

    /** Firing pulses per second at [rpm]: one per cylinder every 2 turns. */
    fun firingHz(rpm: Double) = rpm / 120.0 * cylinders

    /** Firings in one limiter cut / fire cycle (whole firings, at least 2). The stutter then runs
     *  at firingHz / this, as close to [limiterHz] as whole firings allow. When there are enough
     *  firings it's a whole number of engine cycles, so every round cuts the same cylinders
     *  (otherwise their small differences add a slow wobble on top of the stutter). */
    fun limiterFirings(fireHz: Double): Int {
        val m = maxOf(2, Math.round(fireHz / limiterHz).toInt())
        if (cylinders < 2 || m < cylinders) return m
        return maxOf(1, Math.round(m.toDouble() / cylinders).toInt()) * cylinders
    }

    /** How many of those firings are cut: about 45 %, at least 1, never all. */
    fun limiterCut(m: Int) = Math.round(m * 0.45).toInt().coerceIn(1, m - 1)
}

object Styles {
    private fun even(n: Int) = DoubleArray(n) { it * 720.0 / n }

    val ALL = listOf(
        EngineStyle(
            "single", "1-cyl", "Single cylinder", doubleArrayOf(0.0),
            idle = 1400.0, redline = 9500.0, pulseDeg = 230.0, pipeMs = 3.6, pipeFeedback = 0.55,
            toneHz = 600.0, toneLoadHz = 2000.0, resonance = 1.3, drive = 1.8, bark = 0.35,
            intake = 0.12, jitterDeg = 7.0, unevenAmp = 0.0, lope = 0.2, limiterHz = 18.0, gain = 1.0,
        ),
        // 90° V-twin: fires at 0° and 270°, then waits 450° (the "potato" beat)
        EngineStyle(
            "vtwin", "V-twin", "90° V-twin", doubleArrayOf(0.0, 270.0),
            idle = 1100.0, redline = 11000.0, pulseDeg = 230.0, pipeMs = 2.8, pipeFeedback = 0.5,
            toneHz = 550.0, toneLoadHz = 2400.0, resonance = 1.4, drive = 2.1, bark = 0.3,
            intake = 0.18, jitterDeg = 4.0, unevenAmp = 0.12, lope = 0.35, limiterHz = 20.0, gain = 0.85,
        ),
        // flat-plane inline 4: even 180° spacing, the classic superbike scream
        EngineStyle(
            "i4", "I4", "Inline 4 superbike", even(4),
            idle = 1300.0, redline = 14500.0, pulseDeg = 200.0, pipeMs = 1.6, pipeFeedback = 0.45,
            toneHz = 1100.0, toneLoadHz = 4500.0, resonance = 1.1, drive = 2.2, bark = 0.2,
            intake = 0.2, jitterDeg = 1.5, unevenAmp = 0.22, unevenDeg = 3.0, unevenWidth = 0.12, growl = 0.25, limiterHz = 28.0, gain = 1.0,
        ),
        // crossplane inline 4: uneven 270-180-90-180 spacing, growls like a V4
        EngineStyle(
            "i4cp", "I4 CP", "Crossplane inline 4", doubleArrayOf(0.0, 270.0, 450.0, 540.0),
            idle = 1300.0, redline = 14000.0, pulseDeg = 200.0, pipeMs = 1.9, pipeFeedback = 0.45,
            toneHz = 900.0, toneLoadHz = 3800.0, resonance = 1.2, drive = 2.3, bark = 0.22,
            intake = 0.2, jitterDeg = 1.5, unevenAmp = 0.15, unevenDeg = 2.0, unevenWidth = 0.1, lope = 0.1, limiterHz = 26.0, gain = 0.9,
        ),
        EngineStyle(
            "v6", "V6 F1", "V6 turbo (F1 hybrid era)", even(6),
            idle = 4000.0, redline = 15000.0, pulseDeg = 110.0, pipeMs = 1.2, pipeFeedback = 0.4,
            toneHz = 1300.0, toneLoadHz = 4500.0, resonance = 1.0, drive = 1.8, bark = 0.15,
            intake = 0.1, jitterDeg = 0.5, unevenAmp = 0.28, unevenDeg = 4.0, unevenWidth = 0.15, growl = 0.4, turbo = true, limiterHz = 35.0, gain = 0.7,
        ),
        EngineStyle(
            "v8", "V8 F1", "V8 (F1 2006-2013)", even(8),
            idle = 4000.0, redline = 18000.0, pulseDeg = 100.0, pipeMs = 1.0, pipeFeedback = 0.4,
            toneHz = 1700.0, toneLoadHz = 6500.0, resonance = 1.0, drive = 2.4, bark = 0.15,
            intake = 0.12, jitterDeg = 0.5, unevenAmp = 0.28, unevenDeg = 4.0, unevenWidth = 0.15, growl = 0.55, limiterHz = 35.0, gain = 0.8,
        ),
        EngineStyle(
            "v10", "V10 F1", "V10 (F1 1989-2005)", even(10),
            idle = 4000.0, redline = 19000.0, pulseDeg = 90.0, pipeMs = 0.8, pipeFeedback = 0.4,
            toneHz = 2100.0, toneLoadHz = 8000.0, resonance = 1.0, drive = 2.6, bark = 0.15,
            intake = 0.12, jitterDeg = 0.5, unevenAmp = 0.28, unevenDeg = 4.0, unevenWidth = 0.15, growl = 0.55, limiterHz = 38.0, gain = 0.85,
        ),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[0]
}

/**
 * The bike's own rev range. Bike idle plays as the style's idle and the bike's redline as the
 * style's redline, so a Wave at 8,000 rpm can shriek like a V10 at 17,000.
 */
data class BikeRange(val idle: Double = 1400.0, val redline: Double = 9500.0) {
    fun toStyle(bikeRpm: Double, s: EngineStyle): Double =
        if (bikeRpm <= idle) bikeRpm * s.idle / idle      // starting / stalling: scale down to 0
        else s.idle + (bikeRpm - idle) * (s.redline - s.idle) / (redline - idle)
}

/**
 * Cam crossover ("VTEC"-style): switches to the hard cam at [onAt] bike rpm and back only
 * [gap] rpm lower, so it doesn't flicker when the revs sit right at the border.
 */
class CamSwitch(private val gap: Double = 300.0) {
    var high = false
        private set

    fun update(bikeRpm: Double, onAt: Double, enabled: Boolean): Boolean {
        high = enabled && if (high) bikeRpm >= onAt - gap else bikeRpm >= onAt
        return high
    }
}
