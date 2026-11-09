package com.pai.hecusynth

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A pretend bike engine revving in neutral, for trying the sounds without the bike.
 * It gives the same three things the K-line will: bike rpm, throttle %, fuel cut.
 *
 * Revs climb faster the more throttle you give, fall back when you let go (fuel cut while
 * the revs are well above idle, which is when the pops come), and bounce off the rev limiter.
 */
class EngineSim(var idle: Double = 1400.0, var limiter: Double = 9500.0) {
    var rpm = 0.0
        private set
    var fuelCut = false
        private set
    var running = false
        private set

    private var cutLeft = 0.0
    private var cranking = 0.0

    fun start() {
        if (running) return
        running = true
        cranking = 0.35
    }

    fun stop() { running = false }

    /** Moves the engine on by [dt] seconds with the throttle at [tpsPct] %. */
    fun step(dt: Double, tpsPct: Double) {
        val t = (tpsPct / 100.0).coerceIn(0.0, 1.0)
        if (!running) {                                    // engine off: spin down, then silence
            fuelCut = true
            rpm = max(0.0, rpm - 3000 * dt)
            if (rpm < 250) rpm = 0.0
            return
        }
        if (cranking > 0) {                                // starter motor, then it catches
            cranking -= dt
            fuelCut = true
            rpm = 280.0
            if (cranking <= 0) { rpm = idle + 500; fuelCut = false }
            return
        }
        val target = idle + (limiter + 600 - idle) * t.pow(0.75)
        when {
            cutLeft > 0 -> {                               // limiter cut: revs drop for a moment
                cutLeft -= dt
                rpm -= 4000 * dt
                fuelCut = true
            }
            rpm < target -> {
                rpm = min(target, rpm + (2000 + 14000 * t) * dt)
                fuelCut = false
            }
            else -> {
                rpm = max(target, rpm - (if (t < 0.02) 3200 else 2000) * dt)
                fuelCut = t < 0.02 && rpm > idle + 600
            }
        }
        if (rpm >= limiter) {                              // short cuts: the revs hover at the
            rpm = limiter                                  // limiter (the synth adds the fast
            cutLeft = 0.03                                 // stutter itself)
        }
    }
}
