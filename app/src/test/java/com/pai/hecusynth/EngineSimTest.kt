package com.pai.hecusynth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSimTest {
    private fun run(sim: EngineSim, seconds: Double, tps: Double) {
        repeat((seconds * 100).toInt()) { sim.step(0.01, tps) }
    }

    @Test fun startsAndIdles() {
        val sim = EngineSim(1400.0, 9500.0)
        assertEquals(0.0, sim.rpm, 0.0)
        sim.start()
        run(sim, 2.0, 0.0)
        assertEquals(1400.0, sim.rpm, 1.0)
        assertFalse(sim.fuelCut)
    }

    @Test fun revsUpAndBouncesOffTheLimiter() {
        val sim = EngineSim(1400.0, 9500.0)
        sim.start()
        run(sim, 1.0, 0.0)
        run(sim, 1.0, 100.0)
        var cuts = 0
        var top = 0.0
        repeat(100) {
            sim.step(0.01, 100.0)
            if (sim.fuelCut) cuts++
            top = maxOf(top, sim.rpm)
        }
        assertEquals(9500.0, top, 0.0)
        assertTrue("limiter should cut part of the time: $cuts", cuts in 10..90)
    }

    @Test fun lettingGoCutsFuelThenIdles() {
        val sim = EngineSim(1400.0, 9500.0)
        sim.start()
        run(sim, 1.0, 0.0)
        run(sim, 1.0, 80.0)
        sim.step(0.01, 0.0)
        assertTrue(sim.fuelCut)                     // overrun: fuel cut (pops)
        run(sim, 4.0, 0.0)
        assertEquals(1400.0, sim.rpm, 1.0)
        assertFalse(sim.fuelCut)
    }

    @Test fun stopSpinsDownToSilence() {
        val sim = EngineSim()
        sim.start()
        run(sim, 1.0, 0.0)
        sim.stop()
        run(sim, 1.0, 0.0)
        assertEquals(0.0, sim.rpm, 0.0)
    }
}
