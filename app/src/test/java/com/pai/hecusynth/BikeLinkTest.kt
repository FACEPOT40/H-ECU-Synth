package com.pai.hecusynth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The K-line side: read-only whitelist, table 0x17 decoding, and the poller. */
class BikeLinkTest {

    // ------------------------------------------------------------ read-only, always

    @Test fun onlyWakeInitAndLiveDataAreAllowed() {
        assertTrue(Protocol.isAllowed(Protocol.WAKE_MSG))
        assertTrue(Protocol.isAllowed(Protocol.INIT_MSG))
        for (t in listOf(0x00, 0x17, 0x20, 0xD1)) assertTrue(Protocol.isAllowed(Protocol.queryMsg(t)))

        fun msg(vararg b: Int): ByteArray {
            val body = ByteArray(b.size) { b[it].toByte() }
            return body + Protocol.checksum(body).toByte()
        }
        val blocked = listOf(
            "clear fault codes" to Protocol.hex("72 05 60 03 26"),
            "fault codes (current)" to msg(0x72, 0x05, 0x74, 0x01),   // allowed in the logger, not here
            "fault codes (past)" to msg(0x72, 0x05, 0x73, 0x01),
            "security access" to msg(0x72, 0x05, 0x27, 0x01),
            "write" to msg(0x72, 0x05, 0x2E, 0x17),
            "wrong checksum" to Protocol.hex("72 05 71 17 00"),
            "too long" to msg(0x72, 0x06, 0x71, 0x17, 0x00),
        )
        for ((name, m) in blocked) {
            assertFalse(name, Protocol.isAllowed(m))
            try {
                Protocol.requireAllowed(m)
                fail("$name was not blocked")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ------------------------------------------------------------ table 0x17

    /** Vectors from H-ECU Logger's DecodeTest (checked against decode.py there). */
    @Test fun decodesLikeTheLogger() {
        val vectors = listOf(
            "ea107d906941b28de10e421e67eb1b5aa03a80" to Triple(59920.0, 90.0, 31.53),
            "a64a31d019a1ab744e9e539e95ec506bfbdd20" to Triple(42570.0, 100.0, 164.47),   // 130 % in the logger: clamped
            "5768042def9b39617bece832f1a9e8917e2313" to Triple(22376.0, 28.12, 52.83),
            "58ac537f1d7b5f1c786dd6dcf39f9e7f10cc15" to Triple(22700.0, 79.38, 229.15),
        )
        for ((hex, want) in vectors) {
            val s = EcuSample.decode17(Protocol.hex(hex.chunked(2).joinToString(" ")))
            assertNotNull(s)
            assertEquals(hex, want.first, s!!.rpm, 0.0)
            assertEquals(hex, want.second, s.tpsPct, 0.006)
            assertEquals(hex, want.third, s.injMs, 0.006)
        }
        assertEquals(null, EcuSample.decode17(ByteArray(5)))
    }

    @Test fun fuelCutIsInjectorZeroAboveIdle() {
        assertTrue(EcuSample(5000.0, 0.0, 0.0).fuelCut)
        assertFalse(EcuSample(5000.0, 0.0, 1.2).fuelCut)
        assertFalse(EcuSample(1200.0, 0.0, 0.0).fuelCut)        // stalling / cranking, not a cut
    }

    // ------------------------------------------------------------ the poller

    /** A pretend Wave ECU: answers wake / init / table 0x17, counts what it was sent, and can
     *  go quiet (a shaken plug). */
    private class FakeEcu : Link {
        val sent = mutableListOf<String>()
        @Volatile var silent = false
        var rpm = 1400

        override fun wake() {}
        override fun close() {}
        override fun send(msg: ByteArray, timeoutMs: Long): ByteArray? {
            Protocol.requireAllowed(msg)
            sent += Protocol.toHex(msg)
            if (silent) return null
            if (msg.contentEquals(Protocol.WAKE_MSG)) return Protocol.hex("0E 04 72 7C")
            if (msg.contentEquals(Protocol.INIT_MSG)) return Protocol.INIT_OK
            val table = msg[3].toInt() and 0xFF
            if (table != 0x17) return null
            val inj = (3.0 * 65535 / 265.5).toInt()
            val data = byteArrayOf((rpm shr 8).toByte(), rpm.toByte(), 0x30, 80, 0x75, 110, -1, -1, -1, -1,
                130.toByte(), (inj shr 8).toByte(), inj.toByte(), 140.toByte(), 0x80.toByte(), 0, 0, 0, 0)
            val body = byteArrayOf(0x02, (data.size + 5).toByte(), 0x71, 0x17) + data
            return body + Protocol.checksum(body).toByte()
        }
    }

    @Test fun pollsOnlyTable17AndReconnectsAfterSilence() {
        val ecu = FakeEcu()
        val samples = mutableListOf<EcuSample>()
        val status = mutableListOf<String>()
        lateinit var p: EcuPoller
        var opened = 0
        p = EcuPoller(
            openLink = { opened++; ecu },
            onStatus = { synchronized(status) { status += it } },
            onSample = { synchronized(samples) { samples += it } },
        )
        val th = Thread(p::run).apply { start() }
        Thread.sleep(150)
        ecu.silent = true                                       // plug shaken loose
        Thread.sleep(200)
        ecu.silent = false
        Thread.sleep(800)
        p.stop()
        th.join(3000)

        assertTrue("got samples", samples.size > 10)
        val s = samples.last()
        assertEquals(1400.0, s.rpm, 0.0)
        assertEquals(50.0, s.tpsPct, 0.0)
        assertEquals(3.0, s.injMs, 0.01)
        // read-only and nothing but what the sound needs
        val allowed = setOf(Protocol.toHex(Protocol.WAKE_MSG), Protocol.toHex(Protocol.INIT_MSG), Protocol.toHex(Protocol.queryMsg(0x17)))
        assertTrue("sent ${ecu.sent.toSet()}", ecu.sent.toSet() == allowed)
        assertTrue("reconnected (opened $opened times, status $status)", opened >= 2)
        assertTrue(status.contains("Connected"))
        assertEquals("Disconnected", status.last())
    }
}
