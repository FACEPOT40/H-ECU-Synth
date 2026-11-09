package com.pai.hecusynth

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// Same look as H-ECU Logger (its Ui.kt), so the two apps feel like one family.

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.uiText(size: Float, color: Int, value: String = "") = TextView(this).apply {
    textSize = size
    setTextColor(color)
    text = value
}

fun Context.uiButton(label: String, tone: Tone = Tone.PLAIN, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
    tone(tone)
}

/** PRIMARY = blue (chosen option), RECORD = amber (Start engine), STOP = red (Stop engine). */
enum class Tone { PLAIN, PRIMARY, RECORD, STOP }

fun Button.tone(t: Tone) {
    val (fill, edge, label) = when (t) {
        Tone.PLAIN -> Triple(Look.TILE, Look.LINE, Look.TEXT)
        Tone.PRIMARY -> Triple(Look.BLUE, Look.BLUE, Color.WHITE)
        Tone.RECORD -> Triple(Look.AMBER, Look.AMBER, Look.BACKGROUND)
        Tone.STOP -> Triple(Look.RED, Look.RED, Color.WHITE)
    }
    val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
    val shape = GradientDrawable().apply {
        cornerRadius = context.dp(8).toFloat()
        color = ColorStateList(states, intArrayOf(Look.TILE, fill))
        setStroke(context.dp(1), ColorStateList(states, intArrayOf(Look.TILE, edge)))
    }
    val gap = context.dp(3)
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), InsetDrawable(shape, gap), null)
    setTextColor(ColorStateList(states, intArrayOf(Look.DISABLED, label)))
    stateListAnimator = null
    setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
}

/** Android 15+ draws under the system bars: keep the content clear of them. */
fun View.padForSystemBars() = setOnApplyWindowInsetsListener { v, insets ->
    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    v.setPadding(b.left + context.dp(12), b.top + context.dp(8), b.right + context.dp(12), b.bottom + context.dp(8))
    insets
}

/** A rounded panel in the tile color, for grouping settings ("cards"). */
fun Context.uiCard(title: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Look.TILE)
        setStroke(dp(1), Look.LINE)
    }
    setPadding(dp(12), dp(10), dp(12), dp(10))
    addView(uiText(15f, Look.TEXT, title).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
}

/** A small rounded label with a colored background (status chip). */
fun TextView.chip(fill: Int, text: Int) {
    background = GradientDrawable().apply { cornerRadius = context.dp(14).toFloat(); setColor(fill) }
    setTextColor(text)
    setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
}

/** Brand colors, the same as H-ECU Logger's. */
object Look {
    val GREEN = Color.parseColor("#43A047")
    val BACKGROUND = Color.parseColor("#08090C")
    val TILE = Color.parseColor("#141A22")
    val LINE = Color.parseColor("#232C38")
    val TRACK_OFF = Color.parseColor("#4A5568")       // an off switch's track: visible on the dark screen
    val BLUE = Color.parseColor("#1976D2")
    val AMBER = Color.parseColor("#FFA000")
    val TEXT = Color.parseColor("#E8EDF3")
    val GREY = Color.parseColor("#8A96A6")
    val DISABLED = Color.parseColor("#4A5462")
    val RED = Color.parseColor("#EF5350")
}
