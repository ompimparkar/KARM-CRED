package com.example.karmcredapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Compare Two Workers — mirrors web #/compare, including the one-click
 * "Load Demo: Healthy vs Risky Twins" button: identical income and
 * identical raw volatility, opposite risk patterns, different scores.
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

        rvDiff.layoutManager = LinearLayoutManager(this)
        rvDiff.adapter = ReasonCardAdapter(emptyList())

        findViewById<Button>(R.id.btnCmpRun).setOnClickListener { run() }
        findViewById<Button>(R.id.btnCmpDemo).setOnClickListener {
            etA.setText("TWIN_HEALTHY")
            etB.setText("TWIN_RISKY")
            run()
        }

        // Prefill the demo pair so the screen is never empty.
        if (etA.text.isNullOrBlank()) etA.setText("TWIN_HEALTHY")
        if (etB.text.isNullOrBlank()) etB.setText("TWIN_RISKY")
    }

    private fun run() {
        val a = etA.text.toString().trim()
        val b = etB.text.toString().trim()
        if (a.isEmpty() || b.isEmpty()) {
            tvState.text = "Enter both User IDs (or hit the demo button)."
            return
        }
        tvState.text = "Comparing $a vs $b…"
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
                        tvState.text = "Compare failed (${response.code()}) — check the IDs."
                    }
                }

                override fun onFailure(call: Call<CompareResponse>, t: Throwable) {
                    tvState.text = "Backend unreachable: ${t.message}"
                }
            })
    }

    private fun cohort(c: CohortInfo?): String =
        listOfNotNull(c?.gig_type, c?.city, c?.vehicle_class)
            .joinToString(" · ")

    private fun render(d: CompareResponse) {
        tvState.text = ""

        val a = d.userA
        val b = d.userB
        tvIdA.text = "Worker A · ${a.userId}"
        tvScoreA.text = a.predictedTrustScore.toString()
        tvScoreA.setTextColor(UiUtils.bandColor(this, a.predictedTrustScore))
        tvCohortA.text = cohort(a.cohort)
        tvSummaryA.text = a.summary.orEmpty()

        tvIdB.text = "Worker B · ${b.userId}"
        tvScoreB.text = b.predictedTrustScore.toString()
        tvScoreB.setTextColor(UiUtils.bandColor(this, b.predictedTrustScore))
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

        val cards = mutableListOf<ReasonCard>()
        d.diffReasons?.forEach { x ->
            cards.add(
                ReasonCard(
                    x.feature,
                    "${UiUtils.signed(x.delta)} pts"
                )
            )
        }
        if (cards.isEmpty()) {
            cards.add(ReasonCard("No material attribution differences.", "—"))
        }
        rvDiff.adapter = ReasonCardAdapter(cards)
    }
}
