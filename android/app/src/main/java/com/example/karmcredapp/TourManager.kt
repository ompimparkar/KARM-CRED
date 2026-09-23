package com.example.karmcredapp

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * First-load guided tour + replay — Android twin of the web tour
 * (tour-blocker / tour-spot / tour-card, localStorage "karmcred.tour.v1").
 *
 * Five steps, one per key section. Steps may live on DIFFERENT activities,
 * so the overlay is (re)attached in each host's onResume and removed in
 * onPause; advancing to a step hosted elsewhere starts that activity with
 * the shared page fade, and the new host attaches its own overlay.
 */
object TourManager {

    private const val PREFS = "karmcred.prefs"
    private const val KEY = "karmcred.tour.v1"

    private class Step(
        val host: Class<out Activity>,
        val navItem: Int,            // bottom-nav item to highlight on host
        val targetId: Int,
        val fallbackId: Int,
        val title: String,
        val body: String
    )

    private val steps = listOf(
        Step(
            MainActivity::class.java, R.id.nav_score,
            R.id.bottomNav, R.id.tvScore,
            "Six views, one app",
            "The bottom bar switches between Score, Leaderboard, Simulator, " +
                "Compare, Fairness and Data & Consent — the same six sections " +
                "as the web dashboard."
        ),
        Step(
            MainActivity::class.java, R.id.nav_score,
            R.id.searchRow, R.id.etUserId,
            "Look up any worker",
            "Enter a User ID — try GIG_0001, or the twins TWIN_HEALTHY / " +
                "TWIN_RISKY — for the score, reason bars, counterfactuals and " +
                "profile radar. Use Share to send the score card."
        ),
        Step(
            CompareActivity::class.java, R.id.nav_compare,
            R.id.btnCmpDemo, R.id.btnCmpRun,
            "One-click proof: the twins",
            "Load Demo: Healthy vs Risky Twins fills and runs the comparison: " +
                "identical income, identical raw volatility, opposite risk patterns."
        ),
        Step(
            FairnessActivity::class.java, R.id.nav_fairness,
            R.id.fairStats, R.id.tvFairCaption,
            "The fairness claim, in numbers",
            "Swingiest vs smoothest deciles and healthy high-swing vs low-swing " +
                "gaps, judged against the ±30-point tolerance enforced at training time."
        ),
        Step(
            ConsentActivity::class.java, R.id.nav_consent,
            R.id.sourceToggles, R.id.btnConsentPreview,
            "Consent is enforced",
            "Toggle a source off: its features are nulled server-side before the " +
                "model runs, and the preview score updates to show what changed."
        )
    )

    var active = false
        private set
    private var stepIndex = 0
    private var hostRef: WeakReference<Activity>? = null
    private var overlay: View? = null

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Called by MainActivity after the first successful score render. */
    fun maybeStart(activity: Activity) {
        if (seen(activity) || active) return
        active = true
        stepIndex = 0
        activity.window.decorView.postDelayed({ attach(activity) }, 900)
    }

    /** Explicit replay (Consent screen button): resets and starts at step 1. */
    fun replay(activity: Activity) {
        active = true
        stepIndex = 0
        if (steps[0].host == activity.javaClass) {
            attach(activity)
        } else {
            // step 1 is hosted on the score screen — go there, attach on resume
            val intent = android.content.Intent(activity, MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            activity.startActivity(intent)
            Motion.fadeTo(activity)
        }
    }

    /** Host activity onResume: rebuild the overlay for the current step. */
    fun attach(activity: Activity) {
        if (!active) return
        if (steps[stepIndex].host != activity.javaClass) {
            // The user backed out of the tour flow (system back while the
            // overlay was up). Normal tour navigation always lands on the
            // expected host, so ending it here matches Skip — minus the
            // "seen" flag, so a fresh launch can offer the tour again.
            finish()
            return
        }
        detach(activity)
        hostRef = WeakReference(activity)
        overlay = buildOverlay(activity).also { root ->
            (activity.findViewById<ViewGroup>(android.R.id.content)).addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /** Host activity onPause: remove this host's overlay (avoids staleness). */
    fun detach(activity: Activity) {
        if (hostRef?.get() !== activity) return
        (activity.findViewById<ViewGroup>(android.R.id.content)).removeView(overlay)
        overlay = null
        hostRef = null
    }

    private fun finish() {
        active = false
        hostRef?.get()?.let { activity ->
            (activity.findViewById<ViewGroup>(android.R.id.content)).removeView(overlay)
        }
        overlay = null
        hostRef = null
        stepIndex = 0
    }

    private fun persistSeen() {
        hostRef?.get()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY, true)?.apply()
    }

    // ---------------------------------------------------------------- build

    private fun buildOverlay(activity: Activity): View {
        val step = steps[stepIndex]
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity)

        // spotlight: dims everything, punches a rounded hole over the target
        val spot = object : View(activity) {
            private val dimPaint = Paint().apply { color = 0xC7020617.toInt() }   // 78% dim
            private val holePaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            private val hole = RectF()
            private val radius = 12f * density

            var targetRect: RectF? = null

            override fun onDraw(canvas: Canvas) {
                val layer = canvas.saveLayer(
                    0f, 0f, width.toFloat(), height.toFloat(), null
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                targetRect?.let { r ->
                    hole.set(r)
                    hole.inset(-8f * density, -8f * density)
                    canvas.drawRoundRect(hole, radius, radius, holePaint)
                }
                canvas.restoreToCount(layer)
            }
        }
        root.addView(
            spot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // full-screen click swallow (the hole is visual only, like the web blocker)
        spot.isClickable = true

        // card
        val card = buildCard(activity)
        root.addView(
            card,
            FrameLayout.LayoutParams(
                (320 * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // position hole + card once the target is laid out; prefer a target
        // that is actually visible (fairness/consent content may still be
        // behind a skeleton), then the fallback, then the whole screen.
        root.post {
            val stepDef = steps[stepIndex]
            val target = sequenceOf(stepDef.targetId, stepDef.fallbackId)
                .mapNotNull { id ->
                    activity.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }
                }
                .firstOrNull()
                ?: activity.findViewById(android.R.id.content)
            val loc = IntArray(2)
            target.getLocationOnScreen(activity, loc)
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val rect = RectF(
                (loc[0] - rootLoc[0]).toFloat(),
                (loc[1] - rootLoc[1]).toFloat(),
                (loc[0] - rootLoc[0] + target.width).toFloat(),
                (loc[1] - rootLoc[1] + target.height).toFloat()
            )
            spot.targetRect = rect
            spot.invalidate()

            // place card below the spot; above it if no room
            val lp = card.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val belowY = rect.bottom + 16 * density
            val cardEst = 210 * density
            lp.topMargin = if (belowY + cardEst < root.height - 16 * density)
                belowY.toInt()
            else
                ((rect.top - cardEst - 16 * density).coerceAtLeast(16 * density)).toInt()
            lp.gravity = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK.inv() or Gravity.TOP
            card.layoutParams = lp
        }

        return root
    }

    private fun buildCard(activity: Activity): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val step = steps[stepIndex]

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(
                (18 * density).toInt(), (16 * density).toInt(),
                (18 * density).toInt(), (14 * density).toInt()
            )
        }

        val tvStep = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.accent))
            textSize = 11f
            text = "Step ${stepIndex + 1} of ${steps.size}"
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(tvStep, wide())

        val tvTitle = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            text = step.title
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        card.addView(tvTitle, wide())

        val tvBody = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 13f
            text = step.body
            lineSpacingExtra = 3f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        card.addView(tvBody, wide())

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (14 * density).toInt(), 0, 0)
        }

        fun textBtn(label: String, colorRes: Int, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(colorRes))
                setPadding((18 * density).toInt(), (10 * density).toInt(),
                    (18 * density).toInt(), (10 * density).toInt())
                isClickable = true
                setOnClickListener { onClick() }
                Motion.attachPressScale(this, 0.94f)
            }

        if (stepIndex > 0) {
            row.addView(textBtn("Back", R.color.text_secondary) { back(activity) })
        }
        row.addView(textBtn("Skip", R.color.text_secondary) {
            persistSeen()
            finish()
        })
        row.addView(
            textBtn(
                if (stepIndex == steps.size - 1) "Done" else "Next",
                R.color.accent
            ) { next(activity) }
        )

        card.addView(row, wide())
        return card
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    // ------------------------------------------------------------- advance

    private fun next(activity: Activity) {
        if (stepIndex == steps.size - 1) {
            persistSeen()
            finish()
            return
        }
        stepIndex++
        goTo(activity)
    }

    private fun back(activity: Activity) {
        if (stepIndex == 0) return
        stepIndex--
        goTo(activity)
    }

    /** Navigate if the new step is hosted on a different activity. */
    private fun goTo(current: Activity) {
        val targetHost = steps[stepIndex].host
        if (targetHost == current.javaClass) {
            detach(current)
            attach(current)
        } else {
            detach(current)          // old overlay goes with pause anyway
            val intent = android.content.Intent(current, targetHost)
            if (targetHost == MainActivity::class.java) {
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            current.startActivity(intent)
            Motion.fadeTo(current)
        }
    }
}
