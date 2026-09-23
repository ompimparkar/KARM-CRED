package com.example.karmcredapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Pulsing skeleton loader — Android twin of the web dashboard's `.sk`
 * blocks (pulsing alpha 0.35 ↔ 0.8, ~1.1s cycle). Pattern is chosen in
 * code (no custom attrs needed) so one view replaces six loading layouts.
 *
 * Patterns (blocks are (widthFraction, heightDp), rows stack vertically):
 *  - SCORE  : line + [card | chart]      (My TrustScore)
 *  - GRID6  : 6 stacked cards            (Leaderboard)
 *  - CARD2  : [card | card] + wide row   (Compare)
 *  - TALL2  : [tall | tall]              (Simulator)
 *  - FAIR   : 4 stat blocks + rows       (Fairness)
 *  - PREVIEW: single preview card        (Consent)
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Pattern(val rows: List<List<Pair<Float, Float>>>) {
        SCORE(
            listOf(
                listOf(0.55f to 14f),
                listOf(0.48f to 170f, 0.48f to 170f)
            )
        ),
        GRID6(
            listOf(
                listOf(1f to 80f), listOf(1f to 80f), listOf(1f to 80f),
                listOf(1f to 80f), listOf(1f to 80f), listOf(1f to 80f)
            )
        ),
        CARD2(
            listOf(
                listOf(0.48f to 150f, 0.48f to 150f),
                listOf(1f to 96f)
            )
        ),
        TALL2(
            listOf(listOf(0.48f to 300f, 0.48f to 300f))
        ),
        FAIR(
            listOf(
                listOf(0.24f to 64f, 0.24f to 64f, 0.24f to 64f, 0.24f to 64f),
                listOf(1f to 36f),
                listOf(1f to 150f)
            )
        ),
        PREVIEW(
            listOf(listOf(1f to 120f))
        )
    }

    private val density = context.resources.displayMetrics.density
    private var pattern: Pattern = Pattern.CARD2
    private var pulse = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E293B.toInt()          // bg_panel, breathing alpha
    }

    private val radius = 10f * density
    private val gapH = 10f * density
    private val gapV = 12f * density

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    fun setPattern(p: Pattern) {
        pattern = p
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            300 * density else MeasureSpec.getSize(widthMeasureSpec)
        var h = 0f
        pattern.rows.forEachIndexed { i, row ->
            h += row.first().second * density
            if (i < pattern.rows.size - 1) h += gapV
        }
        h += 8f * density * 2
        setMeasuredDimension(w.toInt(), resolveSize(h.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.alpha = (90 + (pulse * 115)).toInt()     // 0.35 ↔ 0.8

        val padH = 4f * density
        var y = 4f * density
        val innerW = width - padH * 2

        for (row in pattern.rows) {
            var x = padH
            val rowH = row.first().second * density
            val totalGap = gapH * (row.size - 1)
            for ((frac, _) in row) {
                val w = (innerW - totalGap) * frac
                val rect = RectF(x, y, x + w, y + rowH)
                canvas.drawRoundRect(rect, radius, radius, paint)
                x += w + gapH
            }
            y += rowH + gapV
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimator()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun syncAnimator() {
        val shouldRun = isShown && Motion.animatorsEnabled(this)
        if (shouldRun && !animator.isStarted) animator.start()
        else if (!shouldRun && animator.isStarted) animator.cancel()
    }
}
