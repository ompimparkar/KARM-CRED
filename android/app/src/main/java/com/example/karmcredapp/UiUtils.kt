package com.example.karmcredapp

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Small shared UI helpers so every section formats values the same way
 * as the web dashboard (₹ + en-IN digit grouping, score bands, SHAP
 * feature formatting — mirrors fmtFeature in dashboard.js).
 */
object UiUtils {

    /** ₹ with Indian digit grouping: 1234567 -> "₹12,34,567". */
    fun rupees(value: Double): String {
        val n = Math.round(value).toLong().toString()
        if (n.length <= 3) return "₹$n"
        val last3 = n.takeLast(3)
        val rest = n.dropLast(3)
        val grouped = rest.reversed()
            .chunked(2)
            .joinToString(",")
            .reversed()
        return "₹$grouped,$last3"
    }

    /** Band colour resource id: >=700 good, 500-699 mid, <500 low. */
    fun bandColorRes(score: Int): Int = when {
        score >= 700 -> R.color.band_good
        score >= 500 -> R.color.band_mid
        else -> R.color.band_low
    }

    fun bandColor(context: Context, score: Int): Int =
        ContextCompat.getColor(context, bandColorRes(score))

    /** Format one feature value the same way the web slider labels do. */
    fun fmtFeature(value: Float?, format: String): String {
        val v = value?.toDouble() ?: return "—"
        return when (format) {
            "money" -> rupees(v)
            "ratio" -> "${Math.round(v * 100)}%"
            "signed_pct" -> String.format("%+.1f%%", v * 100)
            "rating" -> String.format("%.1f ★", v)
            "months" -> "${Math.round(v)} mo"
            "int" -> Math.round(v).toString()
            "signed_num" -> String.format("%+.2f", v)
            "days" -> String.format("%.2f", v)
            else -> String.format("%.2f", v)
        }
    }

    fun dp(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** "+57" / "-12" for gap-style numbers. */
    fun signed(v: Number): String = if (v.toFloat() >= 0) "+${trim(v)}" else trim(v)

    private fun trim(v: Number): String =
        if (v.toFloat() == Math.floor(v.toFloat()).toDouble())
            Math.round(v.toFloat()).toString()
        else String.format("%.1f", v.toFloat())
}
