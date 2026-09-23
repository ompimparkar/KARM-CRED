package com.example.karmcredapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Compare Two Workers — mirrors web #/compare including the one-click
 * "Load Demo: Healthy vs Risky Twins" button. Motion layer: CARD2 skeleton
 * while comparing, twin gauge count-ups + band crossfade, diff-reason bars,
 * staggered entrance, shared press micro-interactions.
 */
class CompareActivity : AppCompatActivity() {

    private lateinit var etA: EditText
    private lateinit var etB: EditText
    private lateinit var tvState: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvIdA: TextView
    private lateinit var tvScoreA: TextView
    private lateinit var tvCohortA: TextView
    private lateinit var tvSummaryA: TextView
    private lateinit var tvIdB: TextView
    private lateinit var tvScoreB: TextView
    private lateinit var tvCohortB: TextView
    private lateinit var tvSummaryB: TextView
    private lateinit var rvDiff: RecyclerView
    private lateinit var gaugeA: ScoreGaugeView
    private lateinit var gaugeB: ScoreGaugeView
    private lateinit var cmpSkeleton: SkeletonView
    private lateinit var cmpContent: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        BottomNav.wire(this, bottomNav, R.id.nav_compare)

        etA = findViewById(R.id.etCmpA)
        etB = findViewById(R.id.etCmpB)
        tvState = findViewById(R.id.tvCmpState)
        tvSummary = findViewById(R.id.tvCmpSummary)
        tvIdA = findViewById(R.id.tvCmpIdA)
        tvScoreA = findViewById(R.id.tvCmpScoreA)
        tvCohortA = findViewById(R.id.tvCmpCohortA)
        tvSummaryA = findViewById(R.id.tvCmpSummaryA)
        tvIdB = findViewById(R.id.tvCmpIdB)
        tvScoreB = findViewById(R.id.tvCmpScoreB)
        tvCohortB = findViewById(R.id.tvCmpCohortB)
        tvSummaryB = findViewById(R.id.tvCmpSummaryB)
        rvDiff = findViewById(R.id.rvCmpDiff)
        gaugeA = findViewById(R.id.gaugeA)
        gaugeB = findViewById(R.id.gaugeB)
        cmpSkeleton = findViewById(R.id.cmpSkeleton)
        cmpContent = findViewById(R.id.cmpContent)

        cmpSkeleton.setPattern(SkeletonView.Pattern.CARD2)

        rvDiff.layoutManager = LinearLayoutManager(this)
        rvDiff.adapter = ReasonCardAdapter(emptyList())

        val btnRun = findViewById<Button>(R.id.btnCmpRun)
        val btnDemo = findViewById<Button>(R.id.btnCmpDemo)
        btnRun.setOnClickListener { run() }
        btnDemo.setOnClickListener {
            etA.setText("TWIN_HEALTHY")
            etB.setText("TWIN_RISKY")
            run()
        }
        Motion.attachPressScale(btnRun)
        Motion.attachPressScale(btnDemo)

        // Prefill the demo pair so the screen is never empty.
        if (etA.text.isNullOrBlank()) etA.setText("TWIN_HEALTHY")
        if (etB.text.isNullOrBlank()) etB.setText("TWIN_RISKY")
    }

    override fun onResume() {
        super.onResume()
        TourManager.attach(this)
    }

    override fun onPause() {
        TourManager.detach(this)
        super.onPause()
    }

    private fun run() {
        val a = etA.text.toString().trim()
        val b = etB.text.toString().trim()
        if (a.isEmpty() || b.isEmpty()) {
            tvState.text = "Enter both User IDs (or hit the demo button)."
            return
        }
        tvState.text = "Comparing $a vs $b…"

        // skeleton while the request runs (web .sk twin)
        cmpSkeleton.visibility = View.VISIBLE
        cmpContent.visibility = View.GONE

        RetrofitClient.apiService.compare(a, b)
            .enqueue(object : Callback<CompareResponse> {
                override fun onResponse(
                    call: Call<CompareResponse>,
                    response: Response<CompareResponse>
                ) {
                    val d = response.body()
                    if (response.isSuccessful && d != null) {
                        render(d)
                    } else {
                        setLoading(false)
                        tvState.text = "Compare failed (${response.code()}) — check the IDs."
                    }
                }

                override fun onFailure(call: Call<CompareResponse>, t: Throwable) {
                    setLoading(false)
                    tvState.text = "Backend unreachable: ${t.message}"
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        cmpSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
        cmpContent.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun cohort(c: CohortInfo?): String =
        listOfNotNull(c?.gig_type, c?.city, c?.vehicle_class)
            .joinToString(" · ")

    private fun render(d: CompareResponse) {
        setLoading(false)
        tvState.text = ""

        val a = d.userA
        val b = d.userB
        tvIdA.text = "Worker A · ${a.userId}"
        Motion.countUpTo(tvScoreA, a.predictedTrustScore, gaugeA)
        tvCohortA.text = cohort(a.cohort)
        tvSummaryA.text = a.summary.orEmpty()

        tvIdB.text = "Worker B · ${b.userId}"
        Motion.countUpTo(tvScoreB, b.predictedTrustScore, gaugeB)
        tvCohortB.text = cohort(b.cohort)
        tvSummaryB.text = b.summary.orEmpty()

        val leader =
            if (d.scoreGap >= 0) "A (id=${a.userId})" else "B (id=${b.userId})"
        tvSummary.text = buildString {
            append("${a.userId} scores ${a.predictedTrustScore} vs ")
            append("${b.userId} ${b.predictedTrustScore} — gap ${Math.abs(d.scoreGap)} pts. ")
            append("$leader leads.")
            d.diffReasons?.firstOrNull()?.let {
                append(" Top driver: ${it.feature} (${UiUtils.signed(it.delta)} pts).")
            }
        }

        // diff reasons with normalised bars (green A-ahead / red B-ahead)
        val diffs = d.diffReasons.orEmpty()
        val fracs = Motion.barFractions(diffs.map { it.delta })
        val cards = diffs.mapIndexed { i, x ->
            ReasonCard(
                reason = x.feature,
                impact = "${UiUtils.signed(x.delta)} pts",
                barFraction = fracs[i],
                barTone = if (x.delta >= 0)
                    ReasonCard.TONE_POS else ReasonCard.TONE_NEG
            )
        }
        rvDiff.adapter = ReasonCardAdapter(
            cards.ifEmpty {
                listOf(ReasonCard("No material attribution differences.", "—"))
            }
        )
        Motion.staggerRecycler(rvDiff)
    }
}
