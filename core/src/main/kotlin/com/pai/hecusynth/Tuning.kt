package com.pai.hecusynth

import java.util.Locale

/**
 * Every sound setting of a style that can be tuned by ear, with a plain description and a sane
 * range. Used by the desktop tuning panel; plain Kotlin, so the phone can use it later too.
 */
object Tuning {
    class Param(
        val key: String,
        val label: String,
        val help: String,
        val min: Double,
        val max: Double,
        val get: (EngineStyle) -> Double,
        val set: (EngineStyle, Double) -> EngineStyle,
    )

    val PARAMS = listOf(
        Param("idle", "Idle rpm", "Style rpm when the bike idles", 600.0, 6000.0, { it.idle }) { s, v -> s.copy(idle = v) },
        Param("redline", "Redline rpm", "Style rpm at the bike's redline", 6000.0, 22000.0, { it.redline }) { s, v -> s.copy(redline = v) },
        Param("pulseDeg", "Pulse length °", "How long each exhaust bang lasts. Short = sharp, buzzy; long = smooth, round", 30.0, 360.0, { it.pulseDeg }) { s, v -> s.copy(pulseDeg = v) },
        Param("pipeMs", "Pipe echo ms", "Exhaust pipe length. Short = high metallic ring; long = deep, hollow", 0.2, 10.0, { it.pipeMs }) { s, v -> s.copy(pipeMs = v) },
        Param("pipeFeedback", "Pipe ring", "How strong the pipe echo is. High = ringy, tube-like", 0.0, 0.85, { it.pipeFeedback }) { s, v -> s.copy(pipeFeedback = v) },
        Param("toneHz", "Tone closed Hz", "Brightness with the throttle shut. Low = muffled", 100.0, 6000.0, { it.toneHz }) { s, v -> s.copy(toneHz = v) },
        Param("toneLoadHz", "Tone open +Hz", "Extra brightness at full throttle", 0.0, 15000.0, { it.toneLoadHz }) { s, v -> s.copy(toneLoadHz = v) },
        Param("resonance", "Tone peak", "Peak at the tone filter's edge. High = nasal, honky", 0.5, 6.0, { it.resonance }) { s, v -> s.copy(resonance = v) },
        Param("drive", "Rasp", "Saturation. High = rawer, raspier, more distorted", 0.3, 8.0, { it.drive }) { s, v -> s.copy(drive = v) },
        Param("bark", "Bark", "Combustion crack at each bang (noise burst)", 0.0, 1.5, { it.bark }) { s, v -> s.copy(bark = v) },
        Param("intake", "Intake roar", "Air-rush noise with the throttle open", 0.0, 1.0, { it.intake }) { s, v -> s.copy(intake = v) },
        Param("jitterDeg", "Idle lump °", "Random timing wobble at idle (lumpy, uneven idle)", 0.0, 20.0, { it.jitterDeg }) { s, v -> s.copy(jitterDeg = v) },
        Param("unevenAmp", "Cyl. loudness diff", "How different each cylinder's bang is in loudness", 0.0, 0.8, { it.unevenAmp }) { s, v -> s.copy(unevenAmp = v) },
        Param("unevenDeg", "Cyl. timing diff °", "How early / late each cylinder fires (fixed)", 0.0, 20.0, { it.unevenDeg }) { s, v -> s.copy(unevenDeg = v) },
        Param("unevenWidth", "Cyl. length diff", "How different each cylinder's pulse length is", 0.0, 0.6, { it.unevenWidth }) { s, v -> s.copy(unevenWidth = v) },
        Param("growl", "Growl", "Swell once per cycle: the low, rough engine part", 0.0, 1.0, { it.growl }) { s, v -> s.copy(growl = v) },
        Param("gain", "Loudness", "Evens out the volume between styles", 0.1, 3.0, { it.gain }) { s, v -> s.copy(gain = v) },
    )

    /** The style as Kotlin, ready to paste over its entry in [Styles.ALL]. */
    fun toKotlin(s: EngineStyle): String {
        fun f(v: Double) = String.format(Locale.US, "%.3f", v).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }
        val firing = s.firing.joinToString(", ") { f(it) }
        val lines = PARAMS.chunked(4).joinToString(",\n    ") { row ->
            row.joinToString(", ") { "${it.key} = ${f(it.get(s))}" }
        }
        return "EngineStyle(\n    \"${s.id}\", \"${s.label}\", \"${s.name}\", doubleArrayOf($firing),\n    " +
            lines + (if (s.turbo) ", turbo = true" else "") + ",\n),"
    }

    /** Saved form, one "key=value" per setting (for the desktop app's tuning file). */
    fun save(s: EngineStyle): Map<String, String> = PARAMS.associate { "${s.id}.${it.key}" to it.get(s).toString() }

    fun load(s: EngineStyle, saved: Map<String, String>): EngineStyle =
        PARAMS.fold(s) { acc, p -> saved["${s.id}.${p.key}"]?.toDoubleOrNull()?.let { p.set(acc, it) } ?: acc }
}
