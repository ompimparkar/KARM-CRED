package com.example.karmcredapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * My TrustScore — mirrors web #/score with the full motion layer:
 * gauge count-up + band crossfade, profile radar growing from centre,
 * reason/counterfactual bars, staggered card entrances, skeleton + empty
 * states, share-score-card (the Download-PDF twin) and the guided tour.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etUserId: EditText
    private lateinit var btnAnalyze: Button
    private lateinit var tvScore: TextView
    private lateinit var tvSummary: TextView
    private lateinit var rvReasonCards: RecyclerView
    private lateinit var rvCfCards: RecyclerView
    private lateinit var btnManageConsent: Button
    private lateinit var btnShare: Button
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var gauge: ScoreGaugeView
    private lateinit var radar: RadarView
    private lateinit var dashSkeleton: SkeletonView
    private lateinit var contentData: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var featureChipRow: LinearLayout
    private lateinit var tvCohort: TextView

    private var lastData: TrustScoreResponse? = null
    private var firstRender = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        etUserId = findViewById(R.id.etUserId)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        tvScore = findViewById(R.id.tvScore)
        tvSummary = findViewById(R.id.tvSummary)
        rvReasonCards = findViewById(R.id.rvReasonCards)
        rvCfCards = findViewById(R.id.rvCfCards)
        btnManageConsent = findViewById(R.id.btnManageConsent)
        btnShare = findViewById(R.id.btnShare)
        bottomNav = findViewById(R.id.bottomNav)
        gauge = findViewById(R.id.gauge)
        radar = findViewById(R.id.radar)
        dashSkeleton = findViewById(R.id.dashSkeleton)
        contentData = findViewById(R.id.contentData)
        emptyState = findViewById(R.id.emptyState)
        chipScroll = findViewById(R.id.chipScroll)
        featureChipRow = findViewById(R.id.featureChipRow)
        tvCohort = findViewById(R.id.tvCohort)

        dashSkeleton.setPattern(SkeletonView.Pattern.SCORE)

        // Persisted 6-section bottom navigation (mirrors the web sidebar).
        BottomNav.wire(this, bottomNav, R.id.nav_score)

        // Prevent RecyclerView layout crashes
        rvReasonCards.layoutManager = LinearLayoutManager(this)
        rvReasonCards.adapter = ReasonCardAdapter(emptyList())
        rvCfCards.layoutManager = LinearLayoutManager(this)
        rvCfCards.adapter = ReasonCardAdapter(emptyList())

        btnAnalyze.setOnClickListener { analyze() }
        // The full Data & Consent section (toggles + live preview + docs).
        btnManageConsent.setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
            Motion.fadeTo(this)
        }
        // Download-PDF equivalent: render card → system share sheet.
        btnShare.setOnClickListener {
            val data = lastData
            if (data == null) {
                Toast.makeText(this, "Analyze a score first.", Toast.LENGTH_SHORT).show()
            } else {
                ScoreCardExporter.share(this, data)
            }
        }
        Motion.attachPressScale(btnAnalyze)
        Motion.attachPressScale(btnShare)
        Motion.attachPressScale(btnManageConsent)

        // Deep link from other sections: leaderboard cards open a worker here.
        handleIntent(intent)

        // First launch: consent must come BEFORE any scoring happens.
        if (!ConsentManager.hasAnyGrant(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            Motion.fadeTo(this)
        }
    }

    override fun onResume() {
        super.onResume()
        TourManager.attach(this)
    }

    override fun onPause() {
        TourManager.detach(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val userId = intent?.getStringExtra(EXTRA_USER_ID) ?: return
        if (userId.isNotBlank()) {
            etUserId.setText(userId)
            analyze()
        }
    }

    private fun analyze() {
        val userId = etUserId.text.toString().trim()
        if (userId.isEmpty()) {
            Toast.makeText(this, "Please enter a User ID", Toast.LENGTH_SHORT).show()
            return
        }

        // Block scoring when nothing is consented to.
        val consent = ConsentManager.consentQuery(this)
        if (consent.isEmpty()) {
            Toast.makeText(
                this,
                "Scoring is blocked until you grant at least one data source.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            Motion.fadeTo(this)
            return
        }

        // skeleton while loading (web .sk → content swap)
        emptyState.visibility = android.view.View.GONE
        contentData.visibility = android.view.View.GONE
        dashSkeleton.visibility = android.view.View.VISIBLE

        btnAnalyze.isEnabled = false
        RetrofitClient.apiService.getUserScore(userId, consent)
            .enqueue(object : Callback<TrustScoreResponse> {
                override fun onResponse(
                    call: Call<TrustScoreResponse>,
                    response: Response<TrustScoreResponse>
                ) {
                    btnAnalyze.isEnabled = true
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        dashSkeleton.visibility = android.view.View.GONE
                        contentData.visibility = android.view.View.VISIBLE
                        render(body)
                    } else {
                        failState(
                            "Profile not found or server error (${response.code()})"
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "Profile not found or server error (${response.code()})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(call: Call<TrustScoreResponse>, t: Throwable) {
                    btnAnalyze.isEnabled = true
                    failState("Could not reach backend: ${t.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Could not reach backend: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun failState(message: String) {
        dashSkeleton.visibility = android.view.View.GONE
        contentData.visibility = android.view.View.GONE
        emptyState.visibility = android.view.View.VISIBLE
        findViewById<TextView>(R.id.tvEmptyTitle).text = "Couldn't load the score"
        findViewById<TextView>(R.id.tvEmptyHint).text = message
    }

    private fun render(data: TrustScoreResponse) {
        lastData = data

        // gauge + number count-up with band crossfade (animateScoreTo twin)
        Motion.countUpTo(tvScore, data.predictedTrustScore, gauge)
        tvSummary.text = data.summary.orEmpty()

        val f = data.features

        // cohort chip
        val cohortText = listOfNotNull(
            data.cohort?.gig_type, data.cohort?.city, data.cohort?.vehicle_class
        ).filterNot { it.isNullOrBlank() }.joinToString(" · ")
        if (data.cohort == null || cohortText.isBlank()) {
            tvCohort.visibility = android.view.View.GONE
        } else {
            tvCohort.visibility = android.view.View.VISIBLE
            tvCohort.text = cohortText
        }

        // metric chips (web .m-floor / .m-cohvol / .m-trend / .m-gap /
        // .m-rating / .m-completeness row)
        featureChipRow.removeAllViews()
        val chipDefs = listOf(
            Triple("Floor", f?.income_floor_ratio, "ratio"),
            Triple("Swings", f?.cohort_adjusted_volatility, "signed_pct"),
            Triple("Trend", f?.income_trend_slope, "signed_num"),
            Triple("Gaps", f?.earning_gap_irregularity, "days"),
            Triple("Rating", f?.avg_platform_rating, "rating"),
            Triple("Completeness", f?.data_completeness, "ratio")
        )
        chipDefs.forEach { (label, value, fmt) ->
            val chip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = UiUtils.dp(context, 6)
                }
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(
                    UiUtils.dp(context, 10), UiUtils.dp(context, 5),
                    UiUtils.dp(context, 10), UiUtils.dp(context, 5)
                )
                textSize = 12f
                setTextColor(getColor(R.color.accent_dim))
                text = "$label ${UiUtils.fmtFeature(value, fmt)}"
            }
            featureChipRow.addView(chip)
        }
        chipScroll.visibility = android.view.View.VISIBLE

        // profile radar — prefer the backend's radar_values (web parity),
        // fall back to a local normalisation of the raw features.
        radar.setValues(fallbackRadar(f, data.radarValues))

        // SHAP reasons with normalised bars
        val reasons = data.reasons.orEmpty()
        val reasonFracs = Motion.barFractions(reasons.map { it.impactPoints })
        val reasonCards = reasons.mapIndexed { i, r ->
            ReasonCard(
                reason = r.feature,
                impact = "%+.1f pts".format(r.impactPoints),
                barFraction = reasonFracs[i],
                barTone = if (r.type == "negative")
                    ReasonCard.TONE_NEG else ReasonCard.TONE_POS
            )
        }.ifEmpty {
            listOf(ReasonCard("No attribution available for this profile.", "—"))
        }
        rvReasonCards.adapter = ReasonCardAdapter(reasonCards)
        Motion.staggerRecycler(rvReasonCards)

        // counterfactuals as cyan cards with the current→target sub line
        val cfs = data.counterfactuals.orEmpty()
        val cfFracs = Motion.barFractions(cfs.map { it.deltaPoints })
        val cfCards = cfs.mapIndexed { i, cf ->
            ReasonCard(
                reason = "Try: ${cf.action}",
                impact = "+${cf.deltaPoints.toInt()} pts",
                sub = "${cf.feature} → ${UiUtils.fmtFeature(cf.target, "")}",
                barFraction = cfFracs[i],
                barTone = ReasonCard.TONE_CF
            )
        }.ifEmpty {
            listOf(
                ReasonCard(
                    "Profile already strong — no single change adds 3+ pts.", "—"
                )
            )
        }
        rvCfCards.adapter = ReasonCardAdapter(cfCards)
        Motion.staggerRecycler(rvCfCards)

        // start the first-load tour after the first successful render
        if (firstRender) {
            firstRender = false
            TourManager.maybeStart(this)
        }
    }

    /** Backend radar_values if present, else local 0..100 per-axis mapping. */
    private fun fallbackRadar(f: UserFeatures?, radarValues: List<Float>?): List<Float> {
        radarValues?.let { if (it.size >= 5) return it }
        return listOf(
            (f?.income_floor_ratio?.times(100f) ?: 0f).coerceAtMost(100f),
            (f?.upi_monthly_txns?.div(30f)?.times(100f) ?: 0f).coerceAtMost(100f),
            (f?.utility_on_time_ratio?.times(100f) ?: 0f).coerceAtMost(100f),
            (f?.avg_platform_rating?.div(5f)?.times(100f) ?: 0f).coerceAtMost(100f),
            (f?.avg_savings_ratio?.div(0.3f)?.times(100f) ?: 0f).coerceAtMost(100f)
        )
    }

    companion object {
        /** Passed by LeaderboardActivity so tapping a card opens their score. */
        const val EXTRA_USER_ID = "extra_user_id"
    }
}
