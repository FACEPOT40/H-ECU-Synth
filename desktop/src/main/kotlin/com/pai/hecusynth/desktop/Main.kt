package com.pai.hecusynth.desktop

import com.pai.hecusynth.BikeRange
import com.pai.hecusynth.EngineSim
import com.pai.hecusynth.EngineStyle
import com.pai.hecusynth.Styles
import com.pai.hecusynth.Synth
import com.pai.hecusynth.Tuning
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

/** Brand colors, the same as the phone app and H-ECU Logger. */
object Ink {
    val NIGHT = Color(0x08090C)
    val PANEL = Color(0x141A22)
    val LINE = Color(0x232C38)
    val BLUE = Color(0x1976D2)
    val AMBER = Color(0xFFA000)
    val TEXT = Color(0xE8EDF3)
    val GREY = Color(0x8A96A6)
    val RED = Color(0xEF5350)
}

fun main() {
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    SwingUtilities.invokeLater {
        darkTheme()                         // before any window part is made
        SynthWindow().isVisible = true
    }
}

/**
 * The simulator plus a tuning panel. Same sound code as the phone (core/), so a style tuned
 * here sounds the same on the phone once its values are copied into Engines.kt.
 *
 * Keyboard: hold Space or Up = full throttle; hold 1..9 = 10..90 %, 0 = 100 %; Enter = start /
 * stop the engine. Or drag on the throttle bar with the mouse.
 */
class SynthWindow : JFrame("H-ECU Synth (desktop)") {
    private val synth = Synth(DesktopAudio.RATE)
    private val audio = DesktopAudio(synth)
    private val sim = EngineSim()
    private val bike = BikeRange(sim.idle, sim.limiter)

    private val tuneFile = File(System.getProperty("user.home"), ".hecusynth/tuning.properties")
    private val saved = Properties()
    private val tuned = LinkedHashMap<String, EngineStyle>()

    private val meter = Meter()
    private val tunePanel = JPanel(GridBagLayout())
    private val tuneTitle = label("", 16f, Ink.TEXT, bold = true)
    private val engineButton = JButton("Start engine (Enter)")
    private val recordButton = JButton("Record WAV")
    private val camLabel = label("", 12f, Ink.GREY)
    private val keys = HashMap<Int, Double>()     // held key -> throttle %
    private var mouseTps = -1.0
    private var bikeRate = false
    private var sinceSend = 0.0
    private var lastNs = 0L

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane.background = Ink.NIGHT

        if (tuneFile.exists()) tuneFile.inputStream().use { saved.load(it) }
        val map = saved.stringPropertyNames().associateWith { saved.getProperty(it) }
        Styles.ALL.forEach { tuned[it.id] = Tuning.load(it, map) }
        synth.style = tuned[saved.getProperty("style")] ?: tuned.getValue("i4")
        synth.volume = 0.7
        synth.camRpm = 6500.0

        layout = BorderLayout(10, 10)
        rootPane.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(styleRow(), BorderLayout.NORTH)
        add(leftSide(), BorderLayout.CENTER)
        add(rightSide(), BorderLayout.EAST)
        showStyle()

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e -> onKey(e) }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) { audio.stop(); saveTuning(); System.exit(0) }
        })

        Timer(15) { frame() }.start()
        audio.start()
        size = Dimension(1250, 760)
        setLocationRelativeTo(null)
    }

    // ------------------------------------------------------------ layout

    private fun styleRow(): JComponent {
        val row = JPanel(GridLayout(1, Styles.ALL.size, 6, 0)).apply { isOpaque = false }
        val group = ButtonGroup()
        Styles.ALL.forEach { st ->
            val b = JToggleButton(st.label).apply {
                isFocusable = false
                font = font.deriveFont(Font.BOLD, 14f)
                isSelected = st.id == synth.style.id
                addActionListener { pick(st.id) }
            }
            group.add(b)
            row.add(b)
        }
        return row
    }

    private fun leftSide(): JComponent {
        val p = JPanel(BorderLayout(0, 10)).apply { isOpaque = false }
        p.add(meter, BorderLayout.CENTER)

        val c = JPanel().apply { isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        engineButton.apply {
            isFocusable = false
            font = font.deriveFont(Font.BOLD, 15f)
            addActionListener { toggleEngine() }
        }
        recordButton.apply { isFocusable = false; addActionListener { toggleRecord() } }
        c.add(row(engineButton, recordButton))
        c.add(row(label("Volume", 13f, Ink.GREY), slider(0, 100, 70) { synth.volume = it / 100.0 }))
        c.add(row(check("Cam crossover", false) { synth.camEnabled = it; showCam() },
            slider(30, 90, 65) { synth.camRpm = it * 100.0; showCam() }, camLabel))
        c.add(row(check("Engine sound", true) { synth.engineSound = it },
            check("Turbo", false) { synth.turbo = it },
            check("Blow-off valve", false) { synth.blowOff = it }))
        val bovPitchLabel = label("", 12f, Ink.GREY)
        c.add(row(label("Blow-off pitch", 13f, Ink.GREY),
            slider(50, 110, 80) { v ->
                synth.blowOffPitch = v * 100.0
                bovPitchLabel.text = "%.1f kHz%s".format(v / 10.0, when (v) { 80 -> " (default)"; 65 -> " (original)"; else -> "" })
            }, bovPitchLabel))
        c.add(row(check("Exhaust pops", false) { synth.crackles = it },
            check("Backfire bangs", false) { synth.bangs = it },

            check("Bike data rate (17/s)", false) { bikeRate = it }))
        c.add(row(label("Throttle: hold Space or Up = 100 %, hold 1..9 = 10..90 %, 0 = 100 %, " +
            "or drag on the bar. Enter = start / stop.", 12f, Ink.GREY)))
        p.add(c, BorderLayout.SOUTH)
        return p
    }

    private fun rightSide(): JComponent {
        val p = JPanel(BorderLayout(0, 8)).apply { isOpaque = false; preferredSize = Dimension(520, 0) }
        p.add(tuneTitle, BorderLayout.NORTH)
        tunePanel.isOpaque = false
        val scroll = JScrollPane(tunePanel).apply {
            border = BorderFactory.createLineBorder(Ink.LINE)
            viewport.background = Ink.PANEL
            verticalScrollBar.unitIncrement = 16
        }
        p.add(scroll, BorderLayout.CENTER)
        val copy = JButton("Copy as Kotlin").apply {
            isFocusable = false
            toolTipText = "Copies this style's values: paste them to Claude or over the style in Engines.kt"
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(Tuning.toKotlin(synth.style)), null)
                text = "Copied!"
                Timer(1200) { text = "Copy as Kotlin" }.apply { isRepeats = false; start() }
            }
        }
        val reset = JButton("Reset this style").apply {
            isFocusable = false
            addActionListener {
                val st = synth.style
                val ok = JOptionPane.showConfirmDialog(this@SynthWindow, "Put ${st.label} back to its built-in sound?",
                    "Reset", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
                if (ok) {
                    tuned[st.id] = Styles.byId(st.id)
                    synth.style = tuned.getValue(st.id)
                    saveTuning()
                    showStyle()
                }
            }
        }
        p.add(row(copy, reset), BorderLayout.SOUTH)
        return p
    }

    /** One slider per setting of the chosen style; moving it changes the sound at once. */
    private fun buildTuning() {
        tunePanel.removeAll()
        val st = synth.style
        Tuning.PARAMS.forEachIndexed { i, prm ->
            val value = label("", 13f, Ink.TEXT).apply { preferredSize = Dimension(70, 20) }
            fun show(v: Double) { value.text = fmt(v) }
            val steps = 1000
            fun toPos(v: Double) = ((v - prm.min) / (prm.max - prm.min) * steps).roundToInt().coerceIn(0, steps)
            fun fromPos(p: Int) = prm.min + (prm.max - prm.min) * p / steps
            val s = JSlider(0, steps, toPos(prm.get(st))).apply {
                isOpaque = false
                isFocusable = false
                toolTipText = prm.help
                addChangeListener {
                    val v = fromPos(this.value)
                    show(v)
                    val cur = tuned.getValue(synth.style.id)
                    tuned[cur.id] = prm.set(cur, v)
                    synth.style = tuned.getValue(cur.id)
                    if (prm.key == "redline" || prm.key == "idle") showCam()
                }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) = saveTuning()
                })
            }
            show(prm.get(st))
            val name = label(prm.label, 13f, Ink.TEXT).apply { toolTipText = prm.help }
            val help = label(prm.help, 11f, Ink.GREY)
            val g = GridBagConstraints().apply { insets = Insets(6, 8, 0, 8); anchor = GridBagConstraints.WEST }
            tunePanel.add(name, g.apply { gridx = 0; gridy = i * 2; weightx = 0.0; fill = GridBagConstraints.NONE })
            tunePanel.add(s, g.apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            tunePanel.add(value, g.apply { gridx = 2; weightx = 0.0; fill = GridBagConstraints.NONE })
            tunePanel.add(help, GridBagConstraints().apply {
                gridx = 0; gridy = i * 2 + 1; gridwidth = 3; anchor = GridBagConstraints.WEST
                insets = Insets(0, 8, 4, 8)
            })
        }
        tunePanel.add(Box.createVerticalGlue(), GridBagConstraints().apply { gridy = 99; weighty = 1.0 })
        tunePanel.revalidate()
        tunePanel.repaint()
    }

    // ------------------------------------------------------------ actions

    private fun pick(id: String) {
        synth.style = tuned.getValue(id)
        saved.setProperty("style", id)
        saveTuning()
        showStyle()
    }

    private fun showStyle() {
        val st = synth.style
        tuneTitle.text = "Tune: ${st.name}   (hover a name for help)"
        buildTuning()
        showCam()
    }

    private fun showCam() {
        val st = synth.style
        camLabel.text = "at %,d bike rpm (%s: %,d)".format(synth.camRpm.toInt(), st.label, bike.toStyle(synth.camRpm, st).toInt())
    }

    private fun toggleEngine() {
        if (sim.running) sim.stop() else sim.start()
        engineButton.text = if (sim.running) "Stop engine (Enter)" else "Start engine (Enter)"
    }

    private fun toggleRecord() {
        if (!audio.isRecording) {
            audio.startRecording()
            recordButton.text = "Stop + save WAV"
            recordButton.foreground = Ink.RED
            return
        }
        recordButton.text = "Record WAV"
        recordButton.foreground = Ink.TEXT
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val chooser = JFileChooser().apply {
            selectedFile = File(File(System.getProperty("user.home"), "Music"), "hecusynth_${synth.style.id}_$stamp.wav")
            fileFilter = FileNameExtensionFilter("WAV sound", "wav")
        }
        val file = if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        audio.stopRecording(file)
    }

    private fun saveTuning() {
        tuned.values.forEach { st -> Tuning.save(st).forEach { (k, v) -> saved.setProperty(k, v) } }
        tuneFile.parentFile.mkdirs()
        tuneFile.outputStream().use { saved.store(it, "H-ECU Synth desktop tuning") }
    }

    private fun onKey(e: KeyEvent): Boolean {
        val tps = when (e.keyCode) {
            KeyEvent.VK_SPACE, KeyEvent.VK_UP -> 100.0
            in KeyEvent.VK_1..KeyEvent.VK_9 -> (e.keyCode - KeyEvent.VK_0) * 10.0
            KeyEvent.VK_0 -> 100.0
            KeyEvent.VK_ENTER -> {
                if (e.id == KeyEvent.KEY_PRESSED) toggleEngine()
                return true
            }
            else -> return false
        }
        when (e.id) {
            KeyEvent.KEY_PRESSED -> keys[e.keyCode] = tps
            KeyEvent.KEY_RELEASED -> keys.remove(e.keyCode)
        }
        return true                         // keep Space etc. away from buttons and sliders
    }

    private fun throttle() = maxOf(keys.values.maxOrNull() ?: 0.0, mouseTps)

    private fun frame() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.015 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
        lastNs = now
        val tps = throttle()
        sim.step(dt, tps)
        sinceSend += dt
        if (!bikeRate || sinceSend >= 1 / 17.7) {
            sinceSend = 0.0
            synth.set(sim.rpm, tps, sim.fuelCut)
        }
        meter.repaint()
    }

    // ------------------------------------------------------------ the rev meter + throttle bar

    private inner class Meter : JComponent() {
        init {
            val m = object : MouseAdapter() {
                fun at(e: MouseEvent) {
                    if (e.y > height - 80) mouseTps = (e.x * 100.0 / width).coerceIn(0.0, 100.0)
                }
                override fun mousePressed(e: MouseEvent) = at(e)
                override fun mouseDragged(e: MouseEvent) = at(e)
                override fun mouseReleased(e: MouseEvent) { mouseTps = -1.0 }
            }
            addMouseListener(m)
            addMouseMotionListener(m)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = width
            val h = height
            g.color = Ink.PANEL
            g.fillRoundRect(0, 0, w, h, 16, 16)

            val st = synth.style
            val rpm = bike.toStyle(sim.rpm, st)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (h * 0.28).toInt().coerceAtMost(150))
            g.color = Ink.TEXT
            val txt = "%,d".format(rpm.toInt())
            g.drawString(txt, (w - g.fontMetrics.stringWidth(txt)) / 2, (h * 0.40).toInt())
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            g.color = Ink.GREY
            val tps = throttle()
            val cap = "rpm · ${st.name} · bike %,d rpm%s%s".format(sim.rpm.toInt(),
                if (synth.camHigh) " · CAM" else "",
                if (synth.limiting) " · LIMITER" else if (sim.fuelCut && sim.running) " · fuel cut" else "")
            g.drawString(cap, (w - g.fontMetrics.stringWidth(cap)) / 2, (h * 0.40).toInt() + 42)

            // rev bar
            val full = st.redline * 1.08
            fun x(v: Double) = (v / full * (w - 40)).toInt().coerceIn(0, w - 40) + 20
            val top = (h * 0.55).toInt()
            g.color = Ink.NIGHT
            g.fillRoundRect(20, top, w - 40, 26, 10, 10)
            g.color = Color(0xEF, 0x53, 0x50, 0x55)
            g.fillRect(x(st.redline), top, w - 20 - x(st.redline), 26)
            g.color = when {
                rpm >= st.redline * 0.97 -> Ink.RED
                synth.camHigh -> Ink.AMBER
                else -> Ink.BLUE
            }
            g.fillRoundRect(20, top, x(rpm) - 20, 26, 10, 10)
            if (synth.camEnabled) {
                g.color = Ink.AMBER
                g.stroke = BasicStroke(3f)
                val cx = x(bike.toStyle(synth.camRpm, st))
                g.drawLine(cx, top - 6, cx, top + 32)
            }

            // throttle bar (also takes the mouse)
            val ty = h - 60
            g.color = Ink.GREY
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString("Throttle %d %%  (drag here, or hold Space / Up / 1..0)".format(tps.toInt()), 20, ty - 8)
            g.color = Ink.NIGHT
            g.fillRoundRect(20, ty, w - 40, 36, 10, 10)
            g.color = if (tps > 90) Ink.RED else Ink.AMBER
            g.fillRoundRect(20, ty, ((w - 40) * tps / 100).toInt(), 36, 10, 10)
        }
    }

    // ------------------------------------------------------------ small helpers

    private fun fmt(v: Double) = when {
        v >= 100 -> "%,.0f".format(v)
        v >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }

    private fun label(t: String, size: Float, color: Color, bold: Boolean = false) = JLabel(t).apply {
        foreground = color
        font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
    }

    private fun row(vararg parts: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false
        alignmentX = 0f
        parts.forEach { add(it) }
    }

    private fun slider(min: Int, max: Int, value: Int, changed: (Int) -> Unit) = JSlider(min, max, value).apply {
        isOpaque = false
        isFocusable = false
        preferredSize = Dimension(220, 28)
        addChangeListener { changed(this.value) }
        changed(value)
    }

    private fun check(t: String, on: Boolean, changed: (Boolean) -> Unit) = JCheckBox(t, on).apply {
        isOpaque = false
        isFocusable = false
        foreground = Ink.TEXT
        font = font.deriveFont(14f)
        addActionListener { changed(isSelected) }
    }
}

/** Dark Swing look in the brand colors. */
fun darkTheme() {
    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
    mapOf(
        "Panel.background" to Ink.NIGHT, "Label.foreground" to Ink.TEXT,
        "Button.background" to Ink.PANEL, "Button.foreground" to Ink.TEXT, "Button.select" to Ink.BLUE,
        "Button.focus" to Ink.PANEL,
        "ToggleButton.background" to Ink.PANEL, "ToggleButton.foreground" to Ink.TEXT,
        "ToggleButton.select" to Ink.BLUE, "ToggleButton.focus" to Ink.PANEL,
        "CheckBox.foreground" to Ink.TEXT, "Slider.foreground" to Ink.AMBER,
        "OptionPane.background" to Ink.PANEL, "OptionPane.messageForeground" to Ink.TEXT,
        "ScrollBar.background" to Ink.PANEL, "ScrollBar.thumb" to Ink.LINE,
        "ToolTip.background" to Ink.PANEL, "ToolTip.foreground" to Ink.TEXT,
    ).forEach { (k, v) -> UIManager.put(k, v) }
    UIManager.put("Button.border", BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(Ink.LINE), BorderFactory.createEmptyBorder(8, 14, 8, 14)))
    UIManager.put("ToggleButton.border", UIManager.get("Button.border"))
}
