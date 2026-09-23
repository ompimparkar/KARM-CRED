package com.example.karmcredapp

/**
 * Display model for a single reason/counterfactual card — extended to carry
 * the web dashboard's animated reason-bar data:
 *
 *  - [barFraction] : 0..1 bar width (normalised against the sibling list's
 *                    max |impact|, min 4% — same rule as dashboard.js)
 *  - [barTone]     : TONE_POS (green) / TONE_NEG (red) / TONE_CF (cyan) /
 *                    TONE_NONE (no bar, e.g. plain rows)
 *  - [sub]         : optional second line (counterfactual detail)
 *
 * Existing call sites ReasonCard(text, impact) keep compiling via defaults.
 */
data class ReasonCard(
    val reason: String,
    val impact: String,
    val sub: String? = null,
    val barFraction: Float = 0f,
    val barTone: Int = TONE_NONE
) {
    companion object {
        const val TONE_NONE = 0
        const val TONE_POS = 1   // positive impact → green
        const val TONE_NEG = 2   // negative impact → red
        const val TONE_CF = 3    // counterfactual   → cyan
    }
}
