package com.pai.hecusynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process

/**
 * Plays [Synth] through the phone's current output (speaker, wired, or Bluetooth headset).
 * Small blocks keep the delay low on the speaker; Bluetooth adds its own ~150-300 ms on top.
 */
class AudioOut(private val synth: Synth) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "synth-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = synth.sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, BLOCK * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = FloatArray(BLOCK)
        try {
            track.play()
            while (running) {
                synth.render(buf, BLOCK)
                track.write(buf, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    companion object {
        const val BLOCK = 256

        /** The output's own rate (usually 48,000), so Android doesn't have to resample. */
        fun nativeRate() = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000
    }
}
