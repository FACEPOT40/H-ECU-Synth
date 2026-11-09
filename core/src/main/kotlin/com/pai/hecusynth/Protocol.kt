package com.pai.hecusynth

/**
 * Honda/Keihin K-line messages, from H-ECU Logger's Protocol.kt. READ-ONLY by design, and
 * narrower than the logger: the synth only needs live data, so fault-code reads don't exist here.
 *
 * Only these messages can ever leave the app (whitelist, see requireAllowed):
 *   wake-up      FE 04 72 8C
 *   init         72 05 00 F0 99
 *   live data    72 05 71 <table> <checksum>
 * Never add clear-codes (72 05 60 03), write, flash, EEPROM or security-access commands here.
 */
object Protocol {
    const val BAUD = 10400

    val WAKE_MSG = hex("FE 04 72 8C")
    val INIT_MSG = hex("72 05 00 F0 99")
    val INIT_OK = hex("02 04 00 FA")
    const val LIVE = 0x71

    fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    fun checksum(data: ByteArray): Int =
        (0x100 - (data.sumOf { it.toInt() and 0xFF } and 0xFF)) and 0xFF

    fun queryMsg(table: Int): ByteArray {
        val body = byteArrayOf(0x72, 0x05, LIVE.toByte(), table.toByte())
        return body + checksum(body).toByte()
    }

    /** True only for wake-up, init, or a 5-byte live-data query with a correct checksum. */
    fun isAllowed(msg: ByteArray): Boolean =
        msg.contentEquals(WAKE_MSG) || msg.contentEquals(INIT_MSG) ||
            (msg.size == 5 && msg[0] == 0x72.toByte() && msg[1] == 0x05.toByte() &&
                (msg[2].toInt() and 0xFF) == LIVE &&
                checksum(msg.copyOf(4)) == (msg[4].toInt() and 0xFF))

    /** Throws unless msg is on the whitelist. Every link calls this before sending. */
    fun requireAllowed(msg: ByteArray) {
        if (!isAllowed(msg)) throw IllegalArgumentException("Blocked non-whitelisted command: " + toHex(msg))
    }

    /** Strip header (type, len), the echoed '71 tt' and checksum. Empty = table not supported. */
    fun payload(reply: ByteArray, table: Int): ByteArray {
        if (reply.size < 3) return ByteArray(0)
        val body = reply.copyOfRange(2, reply.size - 1)
        return if (body.size >= 2 && (body[0].toInt() and 0xFF) == LIVE && body[1] == table.toByte())
            body.copyOfRange(2, body.size) else body
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Something that can exchange K-line messages: the real cable, or a fake one in the tests. */
interface Link {
    fun wake()
    /** Send one whitelisted message; wait up to [timeoutMs] for each part of the reply. */
    fun send(msg: ByteArray, timeoutMs: Long = 600): ByteArray?
    fun close()
}

/**
 * The three values the sound needs, from table 0x17 (byte map: H-ECU Logger's decode.py,
 * confirmed on the Wave 24-25 Oct 2026): RPM d0-1, TPS % d3 / 1.6, injector d11-12 /
 * (65535 / 265.5) ms. Fuel cut = injector 0 above 1,500 rpm, the logger's rule.
 */
data class EcuSample(val rpm: Double, val tpsPct: Double, val injMs: Double) {
    val fuelCut get() = rpm > 1500 && injMs == 0.0

    companion object {
        private const val INJ_DIVISOR = 65535 / 265.5
        private fun u(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

        fun decode17(d: ByteArray): EcuSample? {
            if (d.size < 13) return null
            return EcuSample(
                rpm = (u(d, 0) shl 8 or u(d, 1)).toDouble(),
                tpsPct = (u(d, 3) / 1.6).coerceIn(0.0, 100.0),
                injMs = (u(d, 11) shl 8 or u(d, 12)) / INJ_DIVISOR,
            )
        }
    }
}

/**
 * Wake -> init -> ask table 0x17 again and again, as fast as the ECU answers (the sound only
 * needs rpm, throttle and fuel cut; the logger also reads 0x20 / 0xD1 and gets ~17/s). Keeps
 * retrying after errors (a loose plug on a bump), with the logger's short reconnect settings.
 * Plain Kotlin: the app gives it the real cable, the tests a fake one.
 */
class EcuPoller(
    private val openLink: () -> Link,
    private val onStatus: (String) -> Unit,
    private val onSample: (EcuSample) -> Unit,
) {
    @Volatile private var running = true

    fun stop() {
        running = false
    }

    private fun connect(): Link? {
        val link = openLink()
        for (attempt in 1..5) {
            if (!running) break
            onStatus("Waking ECU (try $attempt/5)…")
            link.wake()
            link.send(Protocol.WAKE_MSG, WAKE_REPLY_MS)      // the ECU may answer it or not
            if (link.send(Protocol.INIT_MSG)?.contentEquals(Protocol.INIT_OK) == true) return link
            sleep(500)
        }
        link.close()
        return null
    }

    fun run() {
        while (running) {
            var link: Link? = null
            try {
                link = connect()
                if (link == null) {
                    if (running) onStatus("No reply from ECU. Key ON? Cable in the DLC? Retrying…")
                    sleep(2000)
                    continue
                }
                pollUntilLost(link)
                if (running) onStatus("Connection lost, reconnecting…")
            } catch (e: Exception) {
                if (running) {
                    onStatus("Error: ${e.message}. Retrying…")
                    sleep(RETRY_MS)
                }
            } finally {
                link?.close()
            }
        }
        onStatus("Disconnected")
    }

    private fun pollUntilLost(link: Link) {
        onStatus("Connected")
        var misses = 0
        val query = Protocol.queryMsg(TABLE)
        while (running && misses < MAX_MISSES) {
            val r = link.send(query, REPLY_MS)
            val s = r?.let { EcuSample.decode17(Protocol.payload(it, TABLE)) }
            if (s == null) {
                misses++
                continue
            }
            misses = 0
            onSample(s)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val TABLE = 0x17
        /** A reply normally arrives in ~30 ms; after this the query counts as missed. */
        const val REPLY_MS = 300L
        const val WAKE_REPLY_MS = 150L
        /** Missed replies in a row before waking the ECU again. */
        const val MAX_MISSES = 3
        const val RETRY_MS = 300L
    }
}
