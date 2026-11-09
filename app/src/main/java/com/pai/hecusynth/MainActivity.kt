package com.pai.hecusynth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 0.1.0: the simulator. Pick a style, start the engine, hold the throttle and rev it.
 * "Tune" swaps the switches for the same sound sliders as the desktop app (shared [Tuning]),
 * saved on the phone per style; the meter and throttle stay, so you can rev while tuning.
 * Source: Simulator (the on-screen throttle) or Bike (the ECU over the K-line, read-only, via
 * [SynthService] so the sound keeps going with the screen off).
 */
class MainActivity : Activity(), Choreographer.FrameCallback {
    private val synth get() = Core.synth
    private val sim = EngineSim()
    private var bike = BikeRange()
    private var bikeMode = false                  // source: false = Simulator, true = Bike (K-line)
    private lateinit var sourceButton: Button
    private lateinit var rangeButton: Button

    private lateinit var meter: RevMeterView
    private lateinit var info: TextView
    private lateinit var throttle: ThrottleView
    private lateinit var engineButton: Button
    private lateinit var camLabel: TextView
    private val styleButtons = mutableListOf<Pair<EngineStyle, Button>>()

    private val prefs by lazy { getSharedPreferences("synth", MODE_PRIVATE) }
    /** Tuned styles, saved as "styleId.setting" -> value (the same keys as the desktop's file). */
    private val tunePrefs by lazy { getSharedPreferences("tuning", MODE_PRIVATE) }
    private val tuned = LinkedHashMap<String, EngineStyle>()
    private lateinit var scroll: ScrollView
    private lateinit var controls: LinearLayout
    private lateinit var tunePanel: LinearLayout
    private lateinit var tuneButton: Button
    private var tuning = false
    private var bikeRate = false          // send data ~17 times a second, like the real K-line
    private var sinceSend = 0.0
    private var lastFrame = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val saved = tunePrefs.all.mapValues { it.value.toString() }
        Styles.ALL.forEach { tuned[it.id] = Tuning.load(it, saved) }
        bike = BikeRange(prefs.getInt("bike_idle", 1400).toDouble(), prefs.getInt("bike_redline", 9500).toDouble())
        sim.idle = bike.idle
        sim.limiter = bike.redline
        bikeMode = prefs.getString("source", "sim") == "bike"
        synth.apply {
            bike = this@MainActivity.bike
            style = tuned[prefs.getString("style", null)] ?: tuned.getValue(Styles.ALL[0].id)
            volume = prefs.getInt("volume", 70) / 100.0
            camEnabled = prefs.getBoolean("cam_on", false)
            camRpm = prefs.getInt("cam_rpm", 6500).toDouble()
            crackles = prefs.getBoolean("crackles", false)   // off unless the rider turns them on
            bangs = prefs.getBoolean("bangs", false)
            engineSound = prefs.getBoolean("engine_sound", true)
            turbo = prefs.getBoolean("turbo", false)
            blowOff = prefs.getBoolean("blow_off", false)
            flutter = prefs.getBoolean("flutter", false)
            blowOffPitch = prefs.getInt("blow_off_pitch", 8000).toDouble()
            blowOffVolume = prefs.getInt("blow_off_volume", 100) / 100.0
        }
        bikeRate = prefs.getBoolean("bike_rate", false)
        setContentView(buildUi())
        showStyle()
        showSource()
        registerReceiver(usbPermission, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermission)
        super.onDestroy()
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
        top.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sourceButton = uiButton("Simulator") { toggleSource() }
        top.addView(sourceButton)
        tuneButton = uiButton("Tune") { toggleTuning() }
        top.addView(tuneButton)
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
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tunePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        engineButton = uiButton("Start engine", Tone.RECORD) { if (bikeMode) toggleBike() else toggleEngine() }
        controls.addView(engineButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
        rangeButton = uiButton("") { chooseRange() }
        controls.addView(rangeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // at the top, easy to find (Pai looked for "mute bike sound"): off = only the extras
        controls.addView(switch("Engine sound", synth.engineSound) { on ->
            synth.engineSound = on
            prefs.edit().putBoolean("engine_sound", on).apply()
        })
        controls.addView(uiText(11f, Look.GREY,
            "Off = mute the engine: only turbo, blow-off, flutter, pops and bangs play (the real bike is the engine)."))

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
        controls.addView(switch("Backfire bangs", synth.bangs) { on ->
            synth.bangs = on
            prefs.edit().putBoolean("bangs", on).apply()
        })
        controls.addView(switch("Turbo", synth.turbo) { on ->
            synth.turbo = on
            prefs.edit().putBoolean("turbo", on).apply()
        })
        controls.addView(switch("Blow-off valve", synth.blowOff) { on ->
            synth.blowOff = on
            prefs.edit().putBoolean("blow_off", on).apply()
        })
        controls.addView(switch("Turbo flutter", synth.flutter) { on ->
            synth.flutter = on
            prefs.edit().putBoolean("flutter", on).apply()
        })
        val bovPitchLabel = uiText(12f, Look.GREY)
        fun showBovPitch() {
            val hz = synth.blowOffPitch.toInt()
            bovPitchLabel.text = "Blow-off pitch %.1f kHz%s".format(hz / 1000.0, when (hz) { 8000 -> " (default)"; 6500 -> " (original)"; else -> "" })
        }
        showBovPitch()
        controls.addView(bovPitchLabel)
        controls.addView(seek(60, (synth.blowOffPitch.toInt() - 5000) / 100) { v ->
            synth.blowOffPitch = 5000.0 + v * 100
            prefs.edit().putInt("blow_off_pitch", synth.blowOffPitch.toInt()).apply()
            showBovPitch()
        })
        val bovVolLabel = uiText(12f, Look.GREY)
        fun showBovVol() { bovVolLabel.text = "Blow-off volume ${(synth.blowOffVolume * 100).toInt()} %" }
        showBovVol()
        controls.addView(bovVolLabel)
        controls.addView(seek(200, (synth.blowOffVolume * 100).toInt()) { v ->
            synth.blowOffVolume = v / 100.0
            prefs.edit().putInt("blow_off_volume", v).apply()
            showBovVol()
        })
        controls.addView(switch("Bike data rate (17/s)", bikeRate) { on ->
            bikeRate = on
            prefs.edit().putBoolean("bike_rate", on).apply()
        })
        controls.addView(uiText(11f, Look.GREY,
            "Bike data rate: feeds the sound at the K-line's speed, to hear how it will be on the bike."))

        // the switches scroll: they no longer all fit beside the throttle
        scroll = ScrollView(this).apply { addView(controls) }
        bottom.addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = dp(12) })
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
        synth.style = tuned.getValue(st.id)                  // with the phone's own tuning
        prefs.edit().putString("style", st.id).apply()
        showStyle()
        if (tuning) buildTunePanel()
    }

    private fun showStyle() {
        val st = synth.style
        styleButtons.forEach { (s, b) -> b.tone(if (s.id == st.id) Tone.PRIMARY else Tone.PLAIN) }
        meter.redline = st.redline
        showCam()
    }

    // ------------------------------------------------------------ tuning (like the desktop)

    private fun toggleTuning() {
        tuning = !tuning
        scroll.removeAllViews()
        if (tuning) {
            buildTunePanel()
            scroll.addView(tunePanel)
            tuneButton.text = "Done"
            tuneButton.tone(Tone.PRIMARY)
        } else {
            scroll.addView(controls)
            tuneButton.text = "Tune"
            tuneButton.tone(Tone.PLAIN)
        }
        scroll.scrollTo(0, 0)
    }

    /** One slider per sound setting of the chosen style; moving it changes the sound at once. */
    private fun buildTunePanel() {
        tunePanel.removeAllViews()
        val st = synth.style
        tunePanel.addView(uiText(15f, Look.TEXT, "Tune: ${st.name}").apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        tunePanel.addView(uiText(11f, Look.GREY, "Saved on this phone. Rev with the throttle while you tune."))
        val buttons = LinearLayout(this)
        buttons.addView(uiButton("Copy settings") { copySettings() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(uiButton("Reset style") { resetStyle() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tunePanel.addView(buttons)
        val steps = 1000
        for (prm in Tuning.PARAMS) {
            fun toPos(v: Double) = ((v - prm.min) / (prm.max - prm.min) * steps).toInt().coerceIn(0, steps)
            fun fromPos(p: Int) = prm.min + (prm.max - prm.min) * p / steps
            val name = uiText(13f, Look.TEXT, prm.label).apply { setPadding(0, dp(10), 0, 0) }
            val value = uiText(13f, Look.AMBER, fmt(prm.get(st)))
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(value)
            tunePanel.addView(head)
            tunePanel.addView(uiText(11f, Look.GREY, prm.help))
            tunePanel.addView(SeekBar(this).apply {
                max = steps
                progress = toPos(prm.get(st))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = fromPos(p)
                        value.text = fmt(v)
                        val cur = tuned.getValue(synth.style.id)
                        tuned[cur.id] = prm.set(cur, v)
                        synth.style = tuned.getValue(cur.id)
                        if (prm.key == "redline" || prm.key == "idle") showStyle()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) = saveTuning()
                })
            })
        }
    }

    private fun fmt(v: Double) = when {
        v >= 100 -> "%,.0f".format(v)
        v >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }

    private fun saveTuning() {
        val e = tunePrefs.edit()
        tuned.values.forEach { st -> Tuning.save(st).forEach { (k, v) -> e.putString(k, v) } }
        e.apply()
    }

    private fun resetStyle() {
        val st = synth.style
        AlertDialog.Builder(this)
            .setMessage("Put ${st.label} back to its built-in sound?")
            .setPositiveButton("Reset") { _, _ ->
                tuned[st.id] = Styles.byId(st.id)
                synth.style = tuned.getValue(st.id)
                val e = tunePrefs.edit()
                Tuning.PARAMS.forEach { e.remove("${st.id}.${it.key}") }
                e.apply()
                showStyle()
                buildTunePanel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The style's values as text (same as the desktop's "Copy as Kotlin"), to send to Claude. */
    private fun copySettings() {
        val text = Tuning.toKotlin(synth.style)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("H-ECU Synth tuning", text))
        Toast.makeText(this, "Copied ${synth.style.label} settings", Toast.LENGTH_SHORT).show()
    }

    private fun showCam() {
        val st = synth.style
        val bikeAt = synth.camRpm
        meter.camAt = if (synth.camEnabled) bike.toStyle(bikeAt, st) else Double.NaN
        camLabel.text = "at %,d bike rpm (%s: %,d)".format(bikeAt.toInt(), st.label, bike.toStyle(bikeAt, st).toInt())
        meter.invalidate()
    }

    // ------------------------------------------------------------ source: Simulator or Bike

    private fun toggleSource() {
        if (Core.connected) {
            Toast.makeText(this, "Disconnect from the bike first", Toast.LENGTH_SHORT).show()
            return
        }
        if (sim.running) toggleEngine()
        bikeMode = !bikeMode
        prefs.edit().putString("source", if (bikeMode) "bike" else "sim").apply()
        if (bikeMode) Core.stopAudio("screen") else Core.startAudio("screen")
        synth.set(0.0, 0.0, true)
        showSource()
    }

    private fun showSource() {
        sourceButton.text = if (bikeMode) "Bike (K-line)" else "Simulator"
        sourceButton.tone(if (bikeMode) Tone.PRIMARY else Tone.PLAIN)
        throttle.external = if (bikeMode) 0.0 else null
        throttle.invalidate()
        rangeButton.text = "Bike rev range: idle %,d · limiter %,d rpm".format(bike.idle.toInt(), bike.redline.toInt())
        when {
            bikeMode && Core.connected -> { engineButton.text = "Disconnect"; engineButton.tone(Tone.STOP) }
            bikeMode -> { engineButton.text = "Connect to bike"; engineButton.tone(Tone.PRIMARY) }
            sim.running -> { engineButton.text = "Stop engine"; engineButton.tone(Tone.STOP) }
            else -> { engineButton.text = "Start engine"; engineButton.tone(Tone.RECORD) }
        }
    }

    private fun toggleBike() {
        if (Core.connected) {
            startService(Intent(this, SynthService::class.java).setAction(SynthService.ACTION_DISCONNECT))
            Core.connected = false
            showSource()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)   // for the "connected" notice
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val device = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber()
            .findAllDrivers(usb).firstOrNull()?.device
        when {
            device == null -> Toast.makeText(this, "Cable not found. Plug the K-line cable into the phone (OTG).", Toast.LENGTH_LONG).show()
            usb.hasPermission(device) -> startBike()
            else -> usb.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startBike()
            else Toast.makeText(c, "No permission for the cable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBike() {
        Core.connected = true
        Core.status = "Connecting"
        startForegroundService(Intent(this, SynthService::class.java).setAction(SynthService.ACTION_CONNECT))
        showSource()
    }

    /** The bike's own idle and rev limiter: they map onto each style's range, and the synth's
     *  limiter stutter starts near the limiter. */
    private fun chooseRange() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        var idle = bike.idle.toInt()
        var red = bike.redline.toInt()
        val idleText = uiText(14f, Look.TEXT)
        val redText = uiText(14f, Look.TEXT)
        fun show() {
            idleText.text = "Idle: %,d rpm".format(idle)
            redText.text = "Rev limiter: %,d rpm".format(red)
        }
        show()
        box.addView(idleText)
        box.addView(seek(34, (idle - 800) / 50) { v -> idle = 800 + v * 50; show() })   // 800-2,500
        box.addView(redText)
        box.addView(seek(80, (red - 6000) / 100) { v -> red = 6000 + v * 100; show() })  // 6,000-14,000
        AlertDialog.Builder(this)
            .setTitle("Bike rev range")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                bike = BikeRange(idle.toDouble(), red.toDouble())
                synth.bike = bike
                sim.idle = bike.idle
                sim.limiter = bike.redline
                prefs.edit().putInt("bike_idle", idle).putInt("bike_redline", red).apply()
                showSource()
                showCam()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleEngine() {
        if (sim.running) sim.stop() else sim.start()
        showSource()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("H-ECU Synth " + packageManager.getPackageInfo(packageName, 0).versionName)
            .setMessage(
                "Engine sound made from scratch, driven by your bike's revs and throttle.\n\n" +
                    "Bike mode reads the ECU over the K-line cable, read-only like H-ECU Logger: it only " +
                    "asks for live data (rpm, throttle, injector), never writes to the ECU.\n\n" +
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
        val st = synth.style
        if (bikeMode) {
            frameBike(st)
            Choreographer.getInstance().postFrameCallback(this)
            return
        }
        sim.step(dt, throttle.tps)
        sinceSend += dt
        if (!bikeRate || sinceSend >= 1 / 17.7) {
            sinceSend = 0.0
            synth.set(sim.rpm, throttle.tps, sim.fuelCut)
        }
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

    /** Bike mode: show what the ECU sends (the service feeds the synth, even screen-off). */
    private fun frameBike(st: EngineStyle) {
        val s = Core.sample
        val fresh = s != null && System.currentTimeMillis() - Core.sampleAt < 1000
        meter.rpm = if (fresh) bike.toStyle(s!!.rpm, st) else 0.0
        meter.camHigh = synth.camHigh
        meter.caption = "rpm · ${st.name}"
        meter.invalidate()
        throttle.external = if (fresh) s!!.tpsPct else 0.0
        throttle.invalidate()
        info.text = when {
            !Core.connected -> "Bike: not connected"
            fresh -> "%s · bike %,d rpm · throttle %d %% · %.0f readings/s%s".format(
                Core.status, s!!.rpm.toInt(), s.tpsPct.toInt(), Core.readingsPerSecond,
                if (synth.limiting) " · LIMITER" else if (s.fuelCut) " · fuel cut" else "")
            else -> Core.status
        }
        if (Core.connected != (engineButton.text == "Disconnect")) showSource()
    }

    override fun onResume() {
        super.onResume()
        if (!bikeMode) Core.startAudio("screen")         // Bike mode: the service plays it
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        Core.stopAudio("screen")
        super.onPause()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pai.hecusynth.USB_PERMISSION"
    }
}
