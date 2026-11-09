package com.pai.hecusynth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Big rpm number for the chosen style, with a rev bar, the cam point and the red zone. */
class RevMeterView(c: Context) : View(c) {
    var rpm = 0.0
    var redline = 9500.0
    var camAt = Double.NaN          // style rpm of the cam crossover, NaN = off
    var camHigh = false
    var caption = ""

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.GREY; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        big.textSize = h * 0.42f
        small.textSize = 14 * d
        cv.drawText("%,d".format(rpm.toInt()), w / 2, h * 0.45f, big)
        cv.drawText(caption, w / 2, h * 0.45f + 20 * d, small)

        val top = h - 26 * d
        val bot = h - 6 * d
        val full = redline * 1.08
        fun x(v: Double) = (v / full * w).toFloat().coerceIn(0f, w)
        r.set(0f, top, w, bot)
        paint.color = Look.TILE
        cv.drawRoundRect(r, 6 * d, 6 * d, paint)
        paint.color = 0x55EF5350
        cv.drawRect(x(redline), top, w, bot, paint)                      // red zone
        paint.color = when {
            rpm >= redline * 0.97 -> Look.RED
            camHigh -> Look.AMBER
            else -> Look.BLUE
        }
        r.set(0f, top, x(rpm), bot)
        cv.drawRoundRect(r, 6 * d, 6 * d, paint)
        if (!camAt.isNaN()) {                                              // cam crossover mark
            paint.color = Look.AMBER
            cv.drawRect(x(camAt) - 1.5f * d, top - 5 * d, x(camAt) + 1.5f * d, bot + 3 * d, paint)
        }
    }
}

/**
 * The twist grip: hold and slide up to open the throttle. Letting go snaps it shut,
 * like a real grip's spring. Touching high up gives that much throttle straight away.
 */
class ThrottleView(c: Context) : View(c) {
    var tps = 0.0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val r = RectF()
    private val pad get() = 14 * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                tps = ((height - pad - e.y) / (height - 2 * pad) * 100.0).coerceIn(0.0, 100.0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tps = 0.0
        }
        invalidate()
        return true
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = Look.TILE
        cv.drawRoundRect(r, 14 * d, 14 * d, paint)
        val fillTop = (h - pad - (h - 2 * pad) * tps / 100.0).toFloat()
        r.set(0f, fillTop.coerceAtMost(h - pad), w, h)
        paint.color = if (tps > 90) Look.RED else Look.AMBER
        cv.drawRoundRect(r, 14 * d, 14 * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * d
        paint.color = Look.LINE
        r.set(0f, 0f, w, h)
        cv.drawRoundRect(r, 14 * d, 14 * d, paint)

        label.textSize = 22 * d
        label.color = Look.TEXT
        cv.drawText("%d %%".format(tps.toInt()), w / 2, 34 * d, label)
        label.textSize = 11 * d
        label.color = Look.GREY
        cv.drawText("THROTTLE", w / 2, 52 * d, label)
        cv.drawText("hold + slide up", w / 2, h - 12 * d, label)
    }
}
