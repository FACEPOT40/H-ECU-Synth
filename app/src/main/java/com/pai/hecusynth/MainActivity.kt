package com.pai.hecusynth

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 0.1.0: the simulator. Pick a style, start the engine, hold the throttle and rev it.
 * Later the same screen gets a K-line source next to the simulator.
 */
class MainActivity : Activity(), Choreographer.FrameCallback {
    private lateinit var synth: Synth
    private var audio: AudioOut? = null
    private val sim = EngineSim()
    private val bike = BikeRange(sim.idle, sim.limiter)

    private lateinit var meter: RevMeterView
    private lateinit var info: TextView
    private lateinit var throttle: ThrottleView
    private lateinit var engineButton: Button
    private lateinit var camLabel: TextView
    private val styleButtons = mutableListOf<Pair<EngineStyle, Button>>()

    private val prefs by lazy { getSharedPreferences("synth", MODE_PRIVATE) }
    private var bikeRate = false          // send data ~17 times a second, like the real K-line
    private var sinceSend = 0.0
    private var lastFrame = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        synth = Synth(AudioOut.nativeRate()).apply {
            bike = this@MainActivity.bike
            style = Styles.byId(prefs.getString("style", null))
            volume = prefs.getInt("volume", 70) / 100.0
            camEnabled = prefs.getBoolean("cam_on", false)
            camRpm = prefs.getInt("cam_rpm", 6500).toDouble()
            crackles = prefs.getBoolean("crackles", false)   // off unless the rider turns them on
        }
        bikeRate = prefs.getBoolean("bike_rate", false)
        setContentView(buildUi())
        showStyle()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }

        // top bar
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(uiText(20f, Look.TEXT, "H-ECU Synth").apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        top.addView(uiText(13f, Look.AMBER, "  Simulator"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(uiButton("ⓘ") { about() })
        root.addView(top)

        // style picker: two rows
        Styles.ALL.chunked(4).forEach { row ->
            val line = LinearLayout(this)
            row.forEach { st ->
                val b = uiButton(st.label) { pickStyle(st) }
                styleButtons += st to b
                line.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            repeat(4 - row.size) { line.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            root.addView(line)
        }

        meter = RevMeterView(this)
        root.addView(meter, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)).apply { topMargin = dp(8) })
        info = uiText(13f, Look.GREY).apply { gravity = Gravity.CENTER }
        root.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // bottom: controls on the left, throttle on the right
        val bottom = LinearLayout(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        engineButton = uiButton("Start engine", Tone.RECORD) { toggleEngine() }
        controls.addView(engineButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))

        controls.addView(uiText(13f, Look.GREY, "Volume").apply { setPadding(0, dp(10), 0, 0) })
        controls.addView(seek(100, prefs.getInt("volume", 70)) { v ->
            synth.volume = v / 100.0
            prefs.edit().putInt("volume", v).apply()
        })

        controls.addView(switch("Cam crossover", synth.camEnabled) { on ->
            synth.camEnabled = on
            prefs.edit().putBoolean("cam_on", on).apply()
            showCam()
        })
        camLabel = uiText(12f, Look.GREY)
        controls.addView(camLabel)
        controls.addView(seek(60, (synth.camRpm.toInt() - 3000) / 100) { v ->
            synth.camRpm = 3000.0 + v * 100
            prefs.edit().putInt("cam_rpm", synth.camRpm.toInt()).apply()
            showCam()
        })

        controls.addView(switch("Exhaust pops", synth.crackles) { on ->
            synth.crackles = on
            prefs.edit().putBoolean("crackles", on).apply()
        })
        controls.addView(switch("Bike data rate (17/s)", bikeRate) { on ->
            bikeRate = on
            prefs.edit().putBoolean("bike_rate", on).apply()
        })
        controls.addView(uiText(11f, Look.GREY,
            "Bike data rate: feeds the sound at the K-line's speed, to hear how it will be on the bike."))

        bottom.addView(controls, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = dp(12) })
        throttle = ThrottleView(this)
        bottom.addView(throttle, LinearLayout.LayoutParams(dp(120), MATCH_PARENT))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun seek(max: Int, value: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) changed(v) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(label: String, on: Boolean, changed: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 14f
        setTextColor(Look.TEXT)
        isChecked = on
        setPadding(0, dp(6), 0, dp(2))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> changed(v) }
    }

    private fun pickStyle(st: EngineStyle) {
        synth.style = st
        prefs.edit().putString("style", st.id).apply()
        showStyle()
    }

    private fun showStyle() {
        val st = synth.style
        styleButtons.forEach { (s, b) -> b.tone(if (s === st) Tone.PRIMARY else Tone.PLAIN) }
        meter.redline = st.redline
        showCam()
    }

    private fun showCam() {
        val st = synth.style
        val bikeAt = synth.camRpm
        meter.camAt = if (synth.camEnabled) bike.toStyle(bikeAt, st) else Double.NaN
        camLabel.text = "at %,d bike rpm (%s: %,d)".format(bikeAt.toInt(), st.label, bike.toStyle(bikeAt, st).toInt())
        meter.invalidate()
    }

    private fun toggleEngine() {
        if (sim.running) sim.stop() else sim.start()
        if (sim.running) {
            engineButton.text = "Stop engine"
            engineButton.tone(Tone.STOP)
        } else {
            engineButton.text = "Start engine"
            engineButton.tone(Tone.RECORD)
        }
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("H-ECU Synth " + packageManager.getPackageInfo(packageName, 0).versionName)
            .setMessage(
                "Engine sound made from scratch, driven by your bike's revs and throttle.\n\n" +
                    "This version is the simulator only: no bike connection yet. The K-line link " +
                    "will be read-only, like H-ECU Logger: it only reads data, never writes to the ECU.\n\n" +
                    "Ride safe: keep the volume low enough to hear traffic.\n\n" +
                    "Not affiliated with or endorsed by Honda. Engine names describe engine layouts only.\n\n" +
                    "License: GPL-3.0\nMade by FACEPOT40 (ปาย)"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------ frame loop

    override fun doFrame(now: Long) {
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.05)
        lastFrame = now
        sim.step(dt, throttle.tps)
        sinceSend += dt
        if (!bikeRate || sinceSend >= 1 / 17.7) {
            sinceSend = 0.0
            synth.set(sim.rpm, throttle.tps, sim.fuelCut)
        }
        val st = synth.style
        meter.rpm = bike.toStyle(sim.rpm, st)
        meter.camHigh = synth.camHigh
        meter.caption = "rpm · ${st.name}"
        meter.invalidate()
        info.text = "Bike %,d rpm · throttle %d %%%s%s".format(
            sim.rpm.toInt(), throttle.tps.toInt(),
            if (synth.camHigh) " · CAM" else "",
            if (synth.limiting) " · LIMITER" else if (sim.fuelCut && sim.running) " · fuel cut" else "",
        )
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onResume() {
        super.onResume()
        audio = AudioOut(synth).also { it.start() }
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        audio?.stop()
        audio = null
        super.onPause()
    }
}
