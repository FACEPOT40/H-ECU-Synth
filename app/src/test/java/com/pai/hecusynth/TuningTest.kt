package com.pai.hecusynth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TuningTest {
    @Test fun saveAndLoadRoundTrip() {
        val st = Styles.byId("v10")
        val changed = Tuning.PARAMS.fold(st) { s, p -> p.set(s, (p.min + p.max) / 3) }
        val back = Tuning.load(st, Tuning.save(changed))
        for (p in Tuning.PARAMS) assertEquals(p.key, p.get(changed), p.get(back), 1e-9)
        assertTrue(back.firing.contentEquals(st.firing))
    }

    @Test fun defaultsAreInsideTheSliderRanges() {
        for (st in Styles.ALL) for (p in Tuning.PARAMS) {
            val v = p.get(st)
            assertTrue("${st.id}.${p.key} = $v", v in p.min..p.max)
        }
    }

    @Test fun kotlinTextHasEveryValue() {
        val text = Tuning.toKotlin(Styles.byId("v6"))
        for (p in Tuning.PARAMS) assertTrue(p.key, text.contains("${p.key} = "))
        assertTrue(text.contains("turbo = true"))
        assertTrue(text.startsWith("EngineStyle(\n    \"v6\""))
    }

    /** Moving a slider must not restart the engine (that would click): the sound right after
     *  a small change carries straight on from the sound before it. */
    @Test fun liveChangeDoesNotClick() {
        val s = Synth(48000).apply { style = Styles.byId("i4") }
        s.set(6000.0, 50.0, false)
        val a = FloatArray(48000)
        s.render(a, a.size)
        s.style = Tuning.PARAMS.first { it.key == "growl" }.let { it.set(s.style, 0.3) }
        val b = FloatArray(64)
        s.render(b, b.size)
        val step = abs(b[0] - a.last())
        var typical = 0.0
        for (i in 1 until a.size) typical = maxOf(typical, abs(a[i] - a[i - 1]).toDouble())
        assertTrue("jump $step vs normal max $typical", step <= typical * 1.5)
    }
}
