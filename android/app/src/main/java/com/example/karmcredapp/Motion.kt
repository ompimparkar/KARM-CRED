package com.example.karmcredapp

import android.app.Activity
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.LayoutAnimationController
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared motion utilities — the Android twin of the web dashboard's
 * animateScoreTo() / stagger / :active-scale utilities in dashboard.js +
 * style.css. Everything here honours the system animator scale (the
 * platform equivalent of prefers-reduced-motion): when animations are
 * disabled, values snap to their final state instead of interpolating.
 */
object Motion {

    /** False when the user has disabled animations system-wide. */
    fun animatorsEnabled(view: View): Boolean = try {
        Settings.Global.getFloat(
            view.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    } catch (e: Exception) {
        true
    }

    private fun bandRes(score: Int): Int = when {
        score >= 700 -> R.color.band_good
        score >= 500 -> R.color.band_mid
        else -> R.color.band_low
    }

    /**
     * Count-up reveal (~800ms ease-out cubic) from [from] (or the number
     * currently displayed, else 300) to [to], crossfading the band colour
     * every frame and driving the paired [gauge]'s sweep in lockstep —
     * exactly what animateScoreTo() does on the web's three score views.
     */
    fun countUpTo(
        tv: TextView,
        to: Int,
        gauge: ScoreGaugeView? = null,
        from: Int? = null,
        duration: Long = 800
    ) {
        val start = from
            ?: tv.text?.toString()?.filter { it.isDigit() || it == '-' }?.toIntOrNull()
            ?: 300

        val paint = { v: Float ->
            val score = v.roundToInt()
            tv.text = score.toString()
            tv.setTextColor(tv.context.getColor(bandRes(score)))
            val frac = (score - 300f) / 600f.coerceAtLeast(1f)
            gauge?.setProgress(frac.coerceIn(0f, 1f), tv.context.getColor(bandRes(score)))
        }

        if (!animatorsEnabled(tv) || start == to) {
            paint(to.toFloat())
            return
        }

        val anim = android.animation.ValueAnimator.ofFloat(start.toFloat(), to.toFloat())
            .setDuration(duration)
        anim.interpolator = DecelerateInterpolator(3f)   // ease-out cubic
        anim.addUpdateListener { paint(it.animatedValue as Float) }
        anim.start()
    }

    /** Micro-interaction twin of the web's `:active { scale(0.97) }`. */
    fun attachPressScale(view: View, factor: Float = 0.97f) {
        val enabled = { animatorsEnabled(view) }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (enabled()) {
                    v.animate().scaleX(factor).scaleY(factor)
                        .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false   // never consume: clicks still fire normally
        }
    }

    /**
     * Staggered card entrance — delta 0.15 × the 260ms fade_up animation
     * puts item i at i×39ms, capping a 10-card list at ~390ms (the web's
     * --i × 40ms, ~400ms budget).
     */
    fun staggerRecycler(rv: RecyclerView) {
        if (!animatorsEnabled(rv)) return
        val anim = android.view.animation.AnimationUtils
            .loadAnimation(rv.context, R.anim.fade_up)
        rv.layoutAnimation = LayoutAnimationController(anim, 0.15f)
        rv.scheduleLayoutAnimation()
    }

    /** Page cross-fade twin of the web router's page-enter/page-leaving. */
    fun fadeTo(activity: Activity) {
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Same fade for a closing (finishing) activity. */
    fun fadeBack(activity: Activity) {
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Normalise a list of magnitudes to 0..1 bar fractions (min 4%). */
    fun barFractions(magnitudes: List<Float>): List<Float> {
        val max = magnitudes.map { abs(it) }.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return magnitudes.map { (abs(it) / max).coerceAtLeast(0.04f) }
    }
}
