package com.example.karmcredapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Radial score gauge — the Android twin of the web dashboard's SVG
 * gauge-wrap (r=52, 326.73 circumference, band-coloured arc). The sweep is
 * driven externally by Motion.countUpTo() so number and arc move together.
 *
 * progress = (score − 300) / 600, same normalisation as the web gauge.
 */
class ScoreGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val stroke = 11f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xFF334155.toInt()          // web .gauge-track
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = 0xFF38BDF8.toInt()
    }

    private val bounds = RectF()
    private var progress = 0f

    /** 0..1 sweep; [color] is the band colour (good/mid/low). */
    fun setProgress(p: Float, color: Int) {
        progress = p.coerceIn(0f, 1f)
        arcPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = stroke / 2f + 2f * density
        bounds.set(half, half, width - half, height - half)

        // full ring track, then the value arc starting at 12 o'clock
        canvas.drawOval(bounds, trackPaint)
        canvas.drawArc(bounds, -90f, progress * 360f, false, arcPaint)
    }
}
