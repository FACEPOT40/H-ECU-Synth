package com.pai.hecusynth.desktop

import com.pai.hecusynth.Synth
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Plays [Synth] on the PC's sound output (16-bit mono, 48 kHz) and can record what it plays
 * to a WAV file. About 40 ms of buffer: small enough to feel direct, big enough for Windows.
 */
class DesktopAudio(private val synth: Synth) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Any()
    private var recording: ByteArrayOutputStream? = null

    val isRecording get() = synchronized(lock) { recording != null }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "synth-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun startRecording() = synchronized(lock) { recording = ByteArrayOutputStream() }

    /** Stops recording; returns the seconds recorded, written to [file] as a WAV. */
    fun stopRecording(file: File?): Double {
        val pcm = synchronized(lock) { recording.also { recording = null } } ?: return 0.0
        val bytes = pcm.toByteArray()
        if (file != null) {
            val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            head.put("RIFF".toByteArray()).putInt(36 + bytes.size).put("WAVE".toByteArray())
            head.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16)
            head.put("data".toByteArray()).putInt(bytes.size)
            file.outputStream().use { it.write(head.array()); it.write(bytes) }
        }
        return bytes.size / 2.0 / RATE
    }

    private fun loop() {
        val fmt = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(fmt)
        line.open(fmt, 2048 * 2)
        line.start()
        val buf = FloatArray(BLOCK)
        val bytes = ByteArray(BLOCK * 2)
        try {
            while (running) {
                synth.render(buf, BLOCK)
                for (i in 0 until BLOCK) {
                    val v = (buf[i].coerceIn(-1f, 1f) * 32767).toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)        // waits while the buffer is full
                synchronized(lock) { recording?.write(bytes) }
            }
        } finally {
            line.drain()
            line.close()
        }
    }

    companion object {
        const val RATE = 48000
        const val BLOCK = 256
    }
}
