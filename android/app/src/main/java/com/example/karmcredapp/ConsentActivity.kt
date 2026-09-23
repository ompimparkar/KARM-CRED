package com.example.karmcredapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Data & Consent — mirrors web #/consent with the motion layer:
 * per-source toggles (id=sourceToggles — tour step 5) with the flash-on/
 * flash-off chip animation on every change (2 × 0.85s, cyan/red), PREVIEW
 * skeleton on first load, live score preview re-scoring GIG_0001 with
 * ?consent=…, feature granted/withheld chips, docs, and the replay-tour
 * button (web #replayTour twin).
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var checks: Map<String, CheckBox>
    private lateinit var featureBox: LinearLayout
    private lateinit var tvScore: TextView
    private lateinit var tvSummary: TextView
    private lateinit var consentSkeleton: SkeletonView
    private lateinit var previewContent: LinearLayout

    /** Source → its header chip in featureBox (flash target). */
    private val headerChips = mutableMapOf<String, TextView>()

    private var firstPreview = true

    /** Feature → consent source (null = always used, derived history). */
    private val featureSource = mapOf(
        "monthly_avg_income" to null,
        "cohort_adjusted_volatility" to null,
        "income_trend_slope" to null,
        "income_floor_ratio" to null,
        "earning_gap_irregularity" to null,
        "platform_tenure_months" to null,
        "avg_savings_ratio" to null,
        "data_completeness" to null,
        "upi_monthly_txns" to "upi",
        "utility_on_time_ratio" to "utility",
        "avg_platform_rating" to "platform",
        "rating_trend" to "platform"
    )

    private val featureLabels = mapOf(
        "monthly_avg_income" to "monthly_avg_income",
        "cohort_adjusted_volatility" to "cohort_adjusted_volatility (swings vs peers)",
        "income_trend_slope" to "income_trend_slope",
        "income_floor_ratio" to "income_floor_ratio",
        "earning_gap_irregularity" to "earning_gap_irregularity (payday gaps)",
        "platform_tenure_months" to "platform_tenure_months",
        "avg_savings_ratio" to "avg_savings_ratio",
        "data_completeness" to "data_completeness (reported honestly)",
        "upi_monthly_txns" to "upi_monthly_txns",
        "utility_on_time_ratio" to "utility_on_time_ratio",
        "avg_platform_rating" to "avg_platform_rating",
        "rating_trend" to "rating_trend"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        BottomNav.wire(this, bottomNav, R.id.nav_consent)

        checks = mapOf(
            "upi" to findViewById(R.id.chkUpi),
            "utility" to findViewById(R.id.chkUtility),
            "platform" to findViewById(R.id.chkPlatform)
        )
        featureBox = findViewById(R.id.featureBox)
        tvScore = findViewById(R.id.tvConsentScore)
        tvSummary = findViewById(R.id.tvConsentSummary)
        consentSkeleton = findViewById(R.id.consentSkeleton)
        previewContent = findViewById(R.id.previewContent)

        consentSkeleton.setPattern(SkeletonView.Pattern.PREVIEW)

        checks.forEach { (source, box) ->
            box.isChecked = ConsentManager.isGranted(this, source)
            box.setOnCheckedChangeListener { _, checked ->
                ConsentManager.setGranted(this, source, checked)
                renderFeatureStates()   // immediate withheld/restored feedback
                flash(source, checked)  // web flash-on/flash-off chip animation
                loadPreview()
            }
        }

        val btnPreview = findViewById<Button>(R.id.btnConsentPreview)
        btnPreview.setOnClickListener { loadPreview() }
        Motion.attachPressScale(btnPreview)

        val btnReplay = findViewById<Button>(R.id.btnReplayTour)
        btnReplay.setOnClickListener { TourManager.replay(this) }
        Motion.attachPressScale(btnReplay)

        renderFeatureStates()
        loadPreview()
    }

    override fun onResume() {
        super.onResume()
        TourManager.attach(this)
    }

    override fun onPause() {
        TourManager.detach(this)
        super.onPause()
    }

    /**
     * Flash the source's header chip twice (web: flash-on cyan / flash-off
     * red, 0.85s × 2, then back to the steady state). Reduced motion: skip.
     */
    private fun flash(source: String, granted: Boolean) {
        val chip = headerChips[source] ?: return
        if (!Motion.animatorsEnabled(chip)) return

        val flashRes =
            if (granted) R.drawable.bg_chip_flash_on else R.drawable.bg_chip_flash_off
        val flashColor = getColor(if (granted) R.color.accent else R.color.band_low)

        val steps = listOf(
            0L to true, 850L to false, 1700L to true, 2550L to false
        )
        steps.forEach { (delay, on) ->
            chip.postDelayed({
                if (on) {
                    chip.setBackgroundResource(flashRes)
                    chip.setTextColor(flashColor)
                } else {
                    chip.setBackgroundColor(Color.TRANSPARENT)
                    chip.setTextColor(getColor(R.color.accent))
                }
            }, delay)
        }
    }

    // ----------------------------------------------------- feature states ---
    private fun renderFeatureStates() {
        featureBox.removeAllViews()
        headerChips.clear()

        addChip("Always used (derived from your earnings history):", null, true, true)
        featureSource.filterValues { it == null }.keys.forEach { f ->
            addChip(featureLabels[f] ?: f, null, false, true)
        }

        ConsentManager.SOURCES.forEach { source ->
            val granted = ConsentManager.isGranted(this, source)
            addChip(
                "$source — ${if (granted) "GRANTED" else "WITHHELD (nulled before inference)"}",
                source, true, granted
            )
            featureSource.filterValues { it == source }.keys.forEach { f ->
                addChip(featureLabels[f] ?: f, source, false, granted)
            }
        }
    }

    private fun addChip(
        text: String, source: String?, isHeader: Boolean, granted: Boolean
    ) {
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiUtils.dp(context, 4) }
            setPadding(
                UiUtils.dp(context, 10), UiUtils.dp(context, 6),
                UiUtils.dp(context, 10), UiUtils.dp(context, 6)
            )
            textSize = if (isHeader) 13f else 12f
            this.text = text
            if (isHeader) {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(getColor(R.color.accent))
            } else {
                setBackgroundResource(
                    if (granted) R.drawable.bg_chip else R.drawable.bg_chip_off
                )
                setTextColor(
                    getColor(if (granted) R.color.text_primary else R.color.text_muted)
                )
            }
        }
        if (isHeader && source != null) headerChips[source] = tv
        featureBox.addView(tv)
    }

    // -------------------------------------------------------- live preview ---
    private fun loadPreview() {
        val granted = ConsentManager.hasAnyGrant(this)
        val consent = ConsentManager.consentQuery(this)

        if (firstPreview) {
            consentSkeleton.visibility = View.VISIBLE
            previewContent.visibility = View.GONE
        }

        if (!granted) {
            finishPreview()
            tvScore.setTextColor(getColor(R.color.text_muted))
            tvScore.text = "—"
            tvSummary.text =
                "No sources granted — the model receives no optional features at all."
            return
        }

        tvScore.text = "…"
        RetrofitClient.apiService.getUserScore(PREVIEW_USER, consent)
            .enqueue(object : Callback<TrustScoreResponse> {
                override fun onResponse(
                    call: Call<TrustScoreResponse>,
                    response: Response<TrustScoreResponse>
                ) {
                    finishPreview()
                    val d = response.body()
                    if (response.isSuccessful && d != null) {
                        tvScore.setTextColor(
                            UiUtils.bandColor(this@ConsentActivity, d.predictedTrustScore)
                        )
                        tvScore.text = d.predictedTrustScore.toString()
                        val pct = d.features?.data_completeness
                            ?.let { "${Math.round(it * 100)}%" } ?: "—"
                        tvSummary.text =
                            "${d.summary.orEmpty()}\ncompleteness $pct · consent=[${consent}]"
                    } else {
                        tvSummary.text = "Preview failed (${response.code()})"
                    }
                }

                override fun onFailure(call: Call<TrustScoreResponse>, t: Throwable) {
                    finishPreview()
                    tvSummary.text = "Backend unreachable: ${t.message}"
                    Toast.makeText(
                        this@ConsentActivity,
                        "Start the Flask server to preview scores.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    /** Swap skeleton → preview content once (first load only). */
    private fun finishPreview() {
        if (firstPreview) {
            firstPreview = false
            consentSkeleton.visibility = View.GONE
            previewContent.visibility = View.VISIBLE
        }
    }

    companion object {
        /** Same worker the web consent preview re-scores (last or default). */
        private const val PREVIEW_USER = "GIG_0001"
    }
}
