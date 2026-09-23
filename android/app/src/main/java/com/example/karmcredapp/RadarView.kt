package com.example.karmcredapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Five-axis profile radar — Android twin of the web's radar chart
 * (Income Floor / UPI Activity / Utility Reliability / Platform Rating /
 * Savings Discipline), including the "grow from centre" entrance: the
 * polygon scales 0 → 1 over ~900ms, then points land.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        val LABELS = listOf(
            "Income Floor", "UPI Activity", "Utility Reliability",
            "Platform Rating", "Savings Discipline"
        )
    }

    private val density = context.resources.displayMetrics.density
    private var values = FloatArray(5) { 0f }     // 0..100 per axis
    private var scale = 1f                        // entrance 0..1

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xFF334155.toInt()
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xFF1E293B.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2E38BDF8.toInt()               // cyan at ~18% (web polygon)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF38BDF8.toInt()
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF7DD3FC.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
    }

    private fun axisPoint(index: Int, fraction: Float, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val angle = Math.toRadians((-90 + index * 72).toDouble())
        val rr = r * fraction
        return (cx + rr * Math.cos(angle).toFloat()) to (cy + rr * Math.sin(angle).toFloat())
    }

    /** Push new axis values (0..100). [animate] plays the centre-out reveal. */
    fun setValues(newValues: List<Float>, animate: Boolean = true) {
        values = FloatArray(5) { i ->
            (newValues.getOrElse(i) { 0f }).coerceIn(0f, 100f)
        }
        if (!Motion.animatorsEnabled(this) || !animate) {
            scale = 1f
            invalidate()
            return
        }
        scale = 0f
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(900)
        anim.interpolator = android.view.animation.DecelerateInterpolator(2f)
        anim.addUpdateListener {
            scale = it.animatedValue as Float
            invalidate()
        }
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelPad = 40f * density
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f - labelPad).coerceAtLeast(20f * density)

        // four grid rings at 25/50/75/100
        for (ring in 1..4) {
            val path = Path()
            for (i in 0..4) {
                val (x, y) = axisPoint(i, ring / 4f, cx, cy, r)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, gridPaint)
        }
        // spokes
        for (i in 0..4) {
            val (x, y) = axisPoint(i, 1f, cx, cy, r)
            canvas.drawLine(cx, cy, x, y, spokePaint)
        }

        // data polygon, scaled from the centre during the entrance
        val poly = Path()
        for (i in 0..4) {
            val frac = (values[i] / 100f) * scale
            val (x, y) = axisPoint(i, frac, cx, cy, r)
            if (i == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
        }
        poly.close()
        canvas.drawPath(poly, fillPaint)
        canvas.drawPath(poly, strokePaint)

        // points fade/scale in with the polygon
        if (scale > 0.2f) {
            for (i in 0..4) {
                val frac = (values[i] / 100f) * scale
                val (x, y) = axisPoint(i, frac, cx, cy, r)
                canvas.drawCircle(x, y, 3.2f * density, pointPaint)
            }
        }

        // labels around the ring
        val labelRadius = r + 16f * density
        val verticalShift = 4f * density   // optical centring per axis
        for (i in 0..4) {
            val angle = Math.toRadians((-90 + i * 72).toDouble())
            val lx = cx + labelRadius * Math.cos(angle).toFloat()
            val ly = cy + labelRadius * Math.sin(angle).toFloat() + verticalShift
            val anchor = when {
                i == 0 -> Paint.Align.CENTER
                Math.cos(angle) > 0.3 -> Paint.Align.LEFT
                Math.cos(angle) < -0.3 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            labelPaint.textAlign = anchor
            canvas.drawText(LABELS[i], lx, ly, labelPaint)
        }
    }
}
