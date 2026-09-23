package com.example.karmcredapp

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Wires the shared 6-item BottomNavigationView (see res/menu/
 * bottom_nav_menu.xml) on every section screen so the Android app mirrors
 * the web sidebar exactly: Score / Leaderboard / Simulator / Compare /
 * Fairness / Consent.
 *
 * Navigation keeps the stack shallow: the score screen stays the root, and
 * switching sections finishes the current (non-root) activity so back never
 * walks through every section the user happened to visit.
 */
object BottomNav {

    fun wire(activity: Activity, view: BottomNavigationView, currentItemId: Int) {
        // Select first (no listener yet), so no navigation fires for "you are here".
        view.selectedItemId = currentItemId

        view.setOnItemSelectedListener { item ->
            if (item.itemId == currentItemId) {
                true
            } else {
                val target = when (item.itemId) {
                    R.id.nav_score -> MainActivity::class.java
                    R.id.nav_leaderboard -> LeaderboardActivity::class.java
                    R.id.nav_simulator -> SimulatorActivity::class.java
                    R.id.nav_compare -> CompareActivity::class.java
                    R.id.nav_fairness -> FairnessActivity::class.java
                    R.id.nav_consent -> ConsentActivity::class.java
                    else -> null
                }
                if (target == null) {
                    false
                } else {
                    val intent = Intent(activity, target).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    activity.startActivity(intent)
                    if (activity !is MainActivity) activity.finish()
                    true
                }
            }
        }
    }
}
