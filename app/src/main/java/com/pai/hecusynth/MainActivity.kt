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
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The main screen, portrait (the phone sits upright on the handlebar). Redesigned 9 Nov:
 *  - always on screen: top bar (name, connection chip, ⋮ menu), engine dropdown, rev meter,
 *    the throttle on the right, and one big Connect / Start button at the bottom;
 *  - tabs: Ride (bigger meter, volume, quick on/off chips), Sound (cards: Engine, Turbo,
 *    Exhaust, Simulator), Tune (the per-style sliders, grouped; saved on the phone);
 *  - ⋮ menu: Simulator / Bike, Auto-connect, Bike rev range, About.
 * Source: Simulator (the on-screen throttle) or Bike (the ECU over the K-line, read-only, via
 * [SynthService] so the sound keeps going with the screen off). In Bike mode it connects by
 * itself when the cable is plugged in (Auto-connect, on by default).
 */
class MainActivity : Activity(), Choreographer.FrameCallback {
    private val synth get() = Core.synth
    private val sim = EngineSim()
    private var bike = BikeRange()
    private var bikeMode = false                  // source: false = Simulator, true = Bike (K-line)

    private val prefs by lazy { getSharedPreferences("synth", MODE_PRIVATE) }
    /** Tuned styles, saved as "styleId.setting" -> value (the same keys as the desktop's file). */
    private val tunePrefs by lazy { getSharedPreferences("tuning", MODE_PRIVATE) }
    private val tuned = LinkedHashMap<String, EngineStyle>()
    private var bikeRate = false          // send data ~17 times a second, like the real K-line
    private var sinceSend = 0.0
    private var lastFrame = 0L

    // always-visible parts
    private lateinit var chipView: TextView
    private lateinit var styleButton: Button
    private lateinit var meter: RevMeterView
    private lateinit var info: TextView
    private lateinit var throttle: ThrottleView
    private lateinit var bigButton: Button
    private lateinit var scroll: ScrollView
    private val tabButtons = mutableListOf<Button>()
    private var tab = 0                    // 0 Ride, 1 Sound, 2 Tune
    private val quickChips = mutableListOf<Pair<Button, () -> Boolean>>()
    private var camLabel: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val saved = tunePrefs.all.mapValues { it.value.toString() }
        Styles.ALL.forEach { tuned[it.id] = Tuning.load(it, saved) }
        bike = BikeRange(prefs.getInt("bike_idle", 1400).toDouble(), prefs.getInt("bike_redline", 9500).toDouble())
        sim.idle = bike.idle
        sim.limiter = bike.redline
        bikeMode = prefs.getString("source", "sim") == "bike"
        tab = prefs.getInt("tab", 0)
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
        registerReceiver(usbPermission, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        showStyle()
        showTab(tab)
        showSource()
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) cablePlugged()
    }

    /** The cable was plugged in while the app runs (singleTask: comes here, not a new screen). */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) cablePlugged()
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermission)
        super.onDestroy()
    }

    // ------------------------------------------------------------ layout

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.BACKGROUND)
            padForSystemBars()
        }

        // top bar: name, connection chip, ⋮
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(uiText(20f, Look.TEXT, "H-ECU Synth").apply { typeface = Typeface.DEFAULT_BOLD })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chipView = uiText(13f, Look.TEXT).apply { setOnClickListener { chipTapped() } }
        top.addView(chipView)
        lateinit var more: Button
        more = uiButton("⋮") { menu(more) }.apply { textSize = 18f }
        top.addView(more)
        root.addView(top)

        // engine dropdown (was two rows of buttons)
        styleButton = uiButton("") { chooseStyle() }.apply { textSize = 16f }
        root.addView(styleButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        meter = RevMeterView(this)
        root.addView(meter, LinearLayout.LayoutParams(MATCH_PARENT, dp(130)).apply { topMargin = dp(4) })
        info = uiText(12f, Look.GREY).apply { gravity = Gravity.CENTER }
        root.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // tabs
        val tabs = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(2)) }
        listOf("Ride", "Sound", "Tune").forEachIndexed { i, name ->
            val b = uiButton(name) { showTab(i) }
            tabButtons += b
            tabs.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        // tab content (scrolls) + the throttle on the right
        val middle = LinearLayout(this)
        scroll = ScrollView(this)
        middle.addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = dp(10) })
        throttle = ThrottleView(this)
        middle.addView(throttle, LinearLayout.LayoutParams(dp(96), MATCH_PARENT))
        root.addView(middle, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // one big button: Connect / Disconnect (Bike) or Start / Stop engine (Simulator)
        bigButton = uiButton("") { if (bikeMode) toggleBike() else toggleEngine() }.apply { textSize = 18f }
        root.addView(bigButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(8) })
        return root
    }

    private fun showTab(i: Int) {
        tab = i
        prefs.edit().putInt("tab", i).apply()
        tabButtons.forEachIndexed { j, b -> b.tone(if (j == i) Tone.PRIMARY else Tone.PLAIN) }
        scroll.removeAllViews()
        camLabel = null
        quickChips.clear()
        scroll.addView(when (i) { 0 -> rideTab(); 1 -> soundTab(); else -> tuneTab() })
        scroll.scrollTo(0, 0)
        // Ride: a bigger meter for a glance on the move
        meter.layoutParams = (meter.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (i == 0) 200 else 130) }
        meter.requestLayout()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun gap(v: LinearLayout) = v.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

    // ------------------------------------------------------------ Ride tab

    private fun rideTab(): View {
        val c = column()
        val vol = uiText(13f, Look.GREY)
        fun showVol() { vol.text = "Volume ${(synth.volume * 100).toInt()} %" }
        showVol()
        c.addView(vol)
        c.addView(seek(100, (synth.volume * 100).toInt()) { v ->
            synth.volume = v / 100.0
            prefs.edit().putInt("volume", v).apply()
            showVol()
        })
        gap(c)
        c.addView(uiText(13f, Look.GREY, "Quick on / off"))
        val chips = listOf<Triple<String, () -> Boolean, (Boolean) -> Unit>>(
            Triple("Engine", { synth.engineSound }, { on -> synth.engineSound = on; prefs.edit().putBoolean("engine_sound", on).apply() }),
            Triple("Turbo", { synth.turbo }, { on -> synth.turbo = on; prefs.edit().putBoolean("turbo", on).apply() }),
            Triple("Blow-off", { synth.blowOff }, { on -> synth.blowOff = on; prefs.edit().putBoolean("blow_off", on).apply() }),
            Triple("Flutter", { synth.flutter }, { on -> synth.flutter = on; prefs.edit().putBoolean("flutter", on).apply() }),
            Triple("Pops", { synth.crackles }, { on -> synth.crackles = on; prefs.edit().putBoolean("crackles", on).apply() }),
            Triple("Bangs", { synth.bangs }, { on -> synth.bangs = on; prefs.edit().putBoolean("bangs", on).apply() }),
        )
        chips.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { (name, get, set) ->
                lateinit var b: Button
                b = uiButton(name) { set(!get()); showQuick() }
                quickChips += b to get
                row.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            c.addView(row)
        }
        showQuick()
        gap(c)
        c.addView(uiText(11f, Look.GREY, "Engine off = only the extras play (on the bike, the real engine is the engine sound)."))
        return c
    }

    private fun showQuick() = quickChips.forEach { (b, get) -> b.tone(if (get()) Tone.PRIMARY else Tone.PLAIN) }

    // ------------------------------------------------------------ Sound tab

    private fun soundTab(): View {
        val c = column()
        val engine = uiCard("Engine")
        engine.addView(switch("Engine sound", synth.engineSound) { on ->
            synth.engineSound = on
            prefs.edit().putBoolean("engine_sound", on).apply()
        })
        engine.addView(uiText(11f, Look.GREY, "Off = mute the engine: only turbo, blow-off, flutter, pops and bangs play."))
        engine.addView(switch("Cam crossover", synth.camEnabled) { on ->
            synth.camEnabled = on
            prefs.edit().putBoolean("cam_on", on).apply()
            showCam()
        })
        camLabel = uiText(12f, Look.GREY).also { engine.addView(it) }
        engine.addView(seek(60, (synth.camRpm.toInt() - 3000) / 100) { v ->
            synth.camRpm = 3000.0 + v * 100
            prefs.edit().putInt("cam_rpm", synth.camRpm.toInt()).apply()
            showCam()
        })
        showCam()
        c.addView(engine)
        gap(c)

        val turbo = uiCard("Turbo")
        turbo.addView(switch("Turbo whistle", synth.turbo) { on ->
            synth.turbo = on
            prefs.edit().putBoolean("turbo", on).apply()
        })
        turbo.addView(switch("Blow-off valve", synth.blowOff) { on ->
            synth.blowOff = on
            prefs.edit().putBoolean("blow_off", on).apply()
        })
        val pitch = uiText(12f, Look.GREY)
        fun showPitch() {
            val hz = synth.blowOffPitch.toInt()
            pitch.text = "Blow-off pitch %.1f kHz%s".format(hz / 1000.0, when (hz) { 8000 -> " (default)"; 6500 -> " (original)"; else -> "" })
        }
        showPitch()
        turbo.addView(pitch)
        turbo.addView(seek(60, (synth.blowOffPitch.toInt() - 5000) / 100) { v ->
            synth.blowOffPitch = 5000.0 + v * 100
            prefs.edit().putInt("blow_off_pitch", synth.blowOffPitch.toInt()).apply()
            showPitch()
        })
        val bovVol = uiText(12f, Look.GREY)
        fun showBovVol() { bovVol.text = "Blow-off volume ${(synth.blowOffVolume * 100).toInt()} %" }
        showBovVol()
        turbo.addView(bovVol)
        turbo.addView(seek(200, (synth.blowOffVolume * 100).toInt()) { v ->
            synth.blowOffVolume = v / 100.0
            prefs.edit().putInt("blow_off_volume", v).apply()
            showBovVol()
        })
        turbo.addView(switch("Turbo flutter", synth.flutter) { on ->
            synth.flutter = on
            prefs.edit().putBoolean("flutter", on).apply()
        })
        turbo.addView(uiText(11f, Look.GREY, "Flutter: after a pull (mid revs is enough), let go fully. Back on the throttle stops it."))
        c.addView(turbo)
        gap(c)

        val exhaust = uiCard("Exhaust")
        exhaust.addView(switch("Exhaust pops", synth.crackles) { on ->
            synth.crackles = on
            prefs.edit().putBoolean("crackles", on).apply()
        })
        exhaust.addView(switch("Backfire bangs", synth.bangs) { on ->
            synth.bangs = on
            prefs.edit().putBoolean("bangs", on).apply()
        })
        c.addView(exhaust)
        gap(c)

        val simCard = uiCard("Simulator")
        simCard.addView(switch("Bike data rate (17/s)", bikeRate) { on ->
            bikeRate = on
            prefs.edit().putBoolean("bike_rate", on).apply()
        })
        simCard.addView(uiText(11f, Look.GREY, "Feeds the sound at the K-line's speed, to hear how it will be on the bike."))
        c.addView(simCard)
        return c
    }

    // ------------------------------------------------------------ Tune tab (like the desktop)

    private val tuneGroups = listOf(
        "Character" to listOf("drive", "growl", "pulseDeg", "bark", "gain"),
        "Pipe and tone" to listOf("pipeMs", "pipeFeedback", "toneHz", "toneLoadHz", "resonance", "intake"),
        "Idle" to listOf("idle", "lope", "jitterDeg"),
        "Cylinders" to listOf("unevenAmp", "unevenDeg", "unevenWidth"),
        "Revs and limiter" to listOf("redline", "limiterHz"),
    )

    /** One slider per sound setting of the chosen style, grouped; moving one changes the sound at once. */
    private fun tuneTab(): View {
        val c = column()
        val st = synth.style
        c.addView(uiText(15f, Look.TEXT, "Tune: ${st.name}").apply { typeface = Typeface.DEFAULT_BOLD })
        c.addView(uiText(11f, Look.GREY, "Saved on this phone. Rev with the throttle while you tune."))
        val buttons = LinearLayout(this)
        buttons.addView(uiButton("Copy settings") { copySettings() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(uiButton("Reset style") { resetStyle() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        c.addView(buttons)
        val byKey = Tuning.PARAMS.associateBy { it.key }
        val listed = tuneGroups.flatMap { it.second }.toSet()
        val groups = tuneGroups + listOf("Other" to Tuning.PARAMS.map { it.key }.filter { it !in listed })
        for ((title, keys) in groups) {
            val params = keys.mapNotNull { byKey[it] }
            if (params.isEmpty()) continue
            gap(c)
            val card = uiCard(title)
            params.forEach { addTuneSlider(card, it, st) }
            c.addView(card)
        }
        return c
    }

    private fun addTuneSlider(card: LinearLayout, prm: Tuning.Param, st: EngineStyle) {
        val steps = 1000
        fun toPos(v: Double) = ((v - prm.min) / (prm.max - prm.min) * steps).toInt().coerceIn(0, steps)
        fun fromPos(p: Int) = prm.min + (prm.max - prm.min) * p / steps
        val value = uiText(13f, Look.AMBER, fmt(prm.get(st)))
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        head.addView(uiText(13f, Look.TEXT, prm.label), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(value)
        card.addView(head)
        card.addView(uiText(11f, Look.GREY, prm.help))
        card.addView(SeekBar(this).apply {
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
                showTab(2)
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

    // ------------------------------------------------------------ engine style dropdown

    private fun chooseStyle() {
        val names = Styles.ALL.map { "${it.label}  ·  ${it.name}" }.toTypedArray()
        val now = Styles.ALL.indexOfFirst { it.id == synth.style.id }
        AlertDialog.Builder(this)
            .setTitle("Engine")
            .setSingleChoiceItems(names, now) { d, i ->
                synth.style = tuned.getValue(Styles.ALL[i].id)          // with the phone's own tuning
                prefs.edit().putString("style", Styles.ALL[i].id).apply()
                showStyle()
                if (tab != 0) showTab(tab)                              // Sound / Tune show this style
                d.dismiss()
            }
            .show()
    }

    private fun showStyle() {
        val st = synth.style
        styleButton.text = "Engine:  ${st.label}  ·  ${st.name}   ▾"
        meter.redline = st.redline
        showCam()
    }

    private fun showCam() {
        val st = synth.style
        val bikeAt = synth.camRpm
        meter.camAt = if (synth.camEnabled) bike.toStyle(bikeAt, st) else Double.NaN
        camLabel?.text = "at %,d bike rpm (%s: %,d)".format(bikeAt.toInt(), st.label, bike.toStyle(bikeAt, st).toInt())
        meter.invalidate()
    }

    // ------------------------------------------------------------ menu, source, connection

    private fun menu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (bikeMode) "Switch to Simulator" else "Switch to Bike (K-line)")
        m.menu.add(0, 2, 1, "Auto-connect when the cable is plugged in").apply {
            isCheckable = true
            isChecked = prefs.getBoolean("auto_connect", true)
        }
        m.menu.add(0, 3, 2, "Bike rev range: %,d / %,d rpm".format(bike.idle.toInt(), bike.redline.toInt()))
        m.menu.add(0, 4, 3, "About")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleSource()
                2 -> prefs.edit().putBoolean("auto_connect", !prefs.getBoolean("auto_connect", true)).apply()
                3 -> chooseRange()
                4 -> about()
            }
            true
        }
        m.show()
    }

    /** Tapping the chip: Simulator <-> Bike (when not connected), or the full status. */
    private fun chipTapped() {
        if (Core.connected) Toast.makeText(this, Core.status, Toast.LENGTH_LONG).show() else toggleSource()
    }

    private fun toggleSource() {
        if (Core.connected) {
            Toast.makeText(this, "Disconnect from the bike first", Toast.LENGTH_SHORT).show()
            return
        }
        if (sim.running) sim.stop()
        bikeMode = !bikeMode
        prefs.edit().putString("source", if (bikeMode) "bike" else "sim").apply()
        if (bikeMode) Core.stopAudio("screen") else Core.startAudio("screen")
        synth.set(0.0, 0.0, true)
        showSource()
        if (bikeMode) cablePlugged()           // cable already in? connect right away
    }

    private var shownState = ""

    /** Chip, big button and throttle mode. Called every frame in Bike mode, so it only redraws
     *  the colors when the state really changed (the chip's readings/s text is cheap). */
    private fun showSource() {
        val linked = Core.connected && Core.status == "Connected"
        val state = "$bikeMode $linked ${Core.connected} ${sim.running}"
        if (bikeMode && linked) chipView.text = "Bike · %.0f/s".format(Core.readingsPerSecond)
        if (state == shownState) return
        shownState = state
        if (!bikeMode) throttle.external = null else if (throttle.external == null) throttle.external = 0.0
        throttle.invalidate()
        when {
            bikeMode && Core.connected && Core.status == "Connected" -> chipView.chip(Look.GREEN, Look.TEXT)
            bikeMode && Core.connected -> chipView.chip(Look.AMBER, Look.BACKGROUND)
            bikeMode -> chipView.chip(Look.LINE, Look.TEXT)
            else -> chipView.chip(Look.LINE, Look.GREY)
        }
        chipView.text = when {
            bikeMode && Core.connected && Core.status == "Connected" -> "Bike · %.0f/s".format(Core.readingsPerSecond)
            bikeMode && Core.connected -> "Bike · connecting"
            bikeMode -> "Bike"
            else -> "Simulator"
        }
        when {
            bikeMode && Core.connected -> { bigButton.text = "Disconnect"; bigButton.tone(Tone.STOP) }
            bikeMode -> { bigButton.text = "Connect to bike"; bigButton.tone(Tone.PRIMARY) }
            sim.running -> { bigButton.text = "Stop engine"; bigButton.tone(Tone.STOP) }
            else -> { bigButton.text = "Start engine"; bigButton.tone(Tone.RECORD) }
        }
    }

    /** Bike mode + Auto-connect: connect when the cable is (already) plugged in. */
    private fun cablePlugged() {
        if (bikeMode && !Core.connected && prefs.getBoolean("auto_connect", true) && findCable() != null) toggleBike()
    }

    private fun findCable() = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber()
        .findAllDrivers(getSystemService(USB_SERVICE) as UsbManager).firstOrNull()?.device

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
        val device = findCable()
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

    // ------------------------------------------------------------ small helpers

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

    // ------------------------------------------------------------ frame loop

    override fun doFrame(now: Long) {
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.05)
        lastFrame = now
        val st = synth.style
        if (bikeMode) {
            frameBike(st)
        } else {
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
            info.text = "Simulator · bike %,d rpm · throttle %d %%%s%s".format(
                sim.rpm.toInt(), throttle.tps.toInt(),
                if (synth.camHigh) " · CAM" else "",
                if (synth.limiting) " · LIMITER" else if (sim.fuelCut && sim.running) " · fuel cut" else "",
            )
        }
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
            !Core.connected -> "Bike: not connected. Plug in the cable" +
                if (prefs.getBoolean("auto_connect", true)) " (connects by itself)" else ", then Connect"
            fresh -> "bike %,d rpm · throttle %d %%%s".format(s!!.rpm.toInt(), s.tpsPct.toInt(),
                if (synth.limiting) " · LIMITER" else if (s.fuelCut) " · fuel cut" else "")
            else -> Core.status
        }
        showSource()                       // chip: connecting / connected + readings per second
    }

    override fun onResume() {
        super.onResume()
        if (!bikeMode) Core.startAudio("screen")         // Bike mode: the service plays it
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
        showSource()
        if (bikeMode) showQuick()
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
