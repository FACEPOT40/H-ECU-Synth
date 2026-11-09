package com.pai.hecusynth

/**
 * The one synth for the whole app, shared by the screen (simulator) and [SynthService] (bike),
 * so the sound keeps playing with the screen off while connected. Audio runs while anyone
 * uses it: the visible screen in Simulator mode, the service while connected to the bike.
 */
object Core {
    @Volatile private var synthOrNull: Synth? = null
    val synth: Synth
        get() = synthOrNull ?: synchronized(this) {
            synthOrNull ?: Synth(AudioOut.nativeRate()).also { synthOrNull = it }
        }

    private var audio: AudioOut? = null
    private val users = mutableSetOf<String>()

    @Synchronized fun startAudio(user: String) {
        users += user
        if (audio == null) audio = AudioOut(synth).also { it.start() }
    }

    @Synchronized fun stopAudio(user: String) {
        users -= user
        if (users.isEmpty()) {
            audio?.stop()
            audio = null
        }
    }

    // ---- live bike state, written by the service, read by the screen
    @Volatile var connected = false            // the service is running (connecting or connected)
    @Volatile var status = "Disconnected"
    @Volatile var sample: EcuSample? = null
    @Volatile var sampleAt = 0L
    @Volatile var readingsPerSecond = 0.0
}
