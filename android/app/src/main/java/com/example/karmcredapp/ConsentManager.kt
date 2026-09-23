package com.example.karmcredapp

import android.content.Context

/**
 * On-device, per-source consent store (DPDP-style: purpose-bound and
 * revocable - the user can grant/deny each source independently and change
 * it later via "Manage data sources").
 *
 * The granted set is sent to the backend as ?consent=upi,utility,platform.
 * The backend NULLS every feature of a source that is not granted, so
 * non-consented data literally never reaches the model. Scoring is blocked
 * entirely when no source is granted (see MainActivity.analyze()).
 */
object ConsentManager {
    private const val PREFS = "karm_consent"

    val SOURCES = listOf("upi", "utility", "platform")

    val SOURCE_LABELS = mapOf(
        "upi" to "UPI & bank SMS metadata\nPayment frequency and on-time behaviour (parsed on device)",
        "utility" to "Utility bill reminders\nElectricity / phone bill punctuality (parsed on device)",
        "platform" to "Gig platform profile & ratings\nAverage rating and rating trend"
    )

    fun isGranted(context: Context, source: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(source, false)

    fun setGranted(context: Context, source: String, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(source, granted).apply()
    }

    fun hasAnyGrant(context: Context): Boolean =
        SOURCES.any { isGranted(context, it) }

    /** "upi,utility" style query value for the consent gate; "" = nothing granted. */
    fun consentQuery(context: Context): String =
        SOURCES.filter { isGranted(context, it) }.joinToString(",")
}
