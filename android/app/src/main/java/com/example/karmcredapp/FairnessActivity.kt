package com.example.karmcredapp

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Fairness & Population — mirrors web #/fairness with the motion layer:
 * FAIR skeleton while the SHAP cache builds, headline stats block
 * (id=fairStats — tour step 4), decile bars growing from 0 on every render
 * (web chart draw-in twin), PASS/FAIL verdict against the ±30pt gate.
 */
class FairnessActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvCaption: TextView
    private lateinit var decileContainer: LinearLayout
    private lateinit var llImportance: LinearLayout
    private lateinit var tvHi: TextView
    private lateinit var tvLo: TextView
    private lateinit var tvGap: TextView
    private lateinit var tvDec: TextView
    private lateinit var tvPop: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var fairSkeleton: SkeletonView
    private lateinit var fairContent: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fairness)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        BottomNav.wire(this, bottomNav, R.id.nav_fairness)

        tvState = findViewById(R.id.tvFairState)
        tvCaption = findViewById(R.id.tvFairCaption)
        decileContainer = findViewById(R.id.decileContainer)
        llImportance = findViewById(R.id.llImportance)
        tvHi = findViewById(R.id.tvFairHi)
        tvLo = findViewById(R.id.tvFairLo)
        tvGap = findViewById(R.id.tvFairGap)
        tvDec = findViewById(R.id.tvFairDec)
        tvPop = findViewById(R.id.tvFairPop)
        tvVerdict = findViewById(R.id.tvFairVerdict)
        fairSkeleton = findViewById(R.id.fairSkeleton)
        fairContent = findViewById(R.id.fairContent)

        fairSkeleton.setPattern(SkeletonView.Pattern.FAIR)

        load()
    }

    override fun onResume() {
        super.onResume()
        TourManager.attach(this)
    }

    override fun onPause() {
        TourManager.detach(this)
        super.onPause()
    }

    private fun setSkeleton(loading: Boolean) {
        fairSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
        fairContent.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun load() {
        setSkeleton(true)
        tvState.text = "Running fairness analysis (first hit builds the SHAP cache)…"
        RetrofitClient.apiService.getFairness()
            .enqueue(object : Callback<FairnessResponse> {
                override fun onResponse(
                    call: Call<FairnessResponse>,
                    response: Response<FairnessResponse>
                ) {
                    val f = response.body()
                    if (response.isSuccessful && f != null) {
                        setSkeleton(false)
                        render(f)
                    } else {
                        setSkeleton(false)
                        tvState.text = "Fairness analysis failed (${response.code()})"
                    }
                }

                override fun onFailure(call: Call<FairnessResponse>, t: Throwable) {
                    setSkeleton(false)
                    tvState.text = "Backend unreachable: ${t.message}"
                }
            })
    }

    private fun render(f: FairnessResponse) {
        tvState.text = ""

        val pop = f.population
        val hi = pop?.let { pp ->
            f.highVolHealthyAvg?.let { hv -> pp.count?.let { n -> "$hv pts · n=$n" } }
        } ?: "—"
        val lo = pop?.let { pp ->
            f.lowVolAvg?.let { lv -> pp.count?.let { n -> "$lv pts" } }
        } ?: "—"
        tvHi.text = hi
        tvLo.text = lo

        val gap = f.gapVsLowVol
        val decGap = f.healthyDecileGap
        val tol = f.maxAcceptableGap ?: 30f

        tvGap.text = gap?.let { UiUtils.signed(it) } ?: "—"
        tvDec.text = decGap?.let { UiUtils.signed(it) } ?: "—"
        val decColor =
            if (decGap == null) R.color.text_secondary
            else if (decGap >= -tol) R.color.band_good else R.color.band_low
        val gapColor =
            if (gap == null) R.color.text_secondary
            else if (gap >= -tol) R.color.band_good else R.color.band_low
        tvGap.setTextColor(getColor(gapColor))
        tvDec.setTextColor(getColor(decColor))

        // Caption states the actual numbers, exactly like the web chart caption.
        tvCaption.text = buildString {
            append("Swingiest decile (D10) vs smoothest (D1): ")
            append(decGap?.let { UiUtils.signed(it) } ?: "n/a")
            append(" pts for otherwise-healthy workers. ")
            append("Healthy high-swing vs low-swing avg score: ")
            append(gap?.let { UiUtils.signed(it) } ?: "n/a")
            append(" pts (tolerance ±${Math.round(tol)}).")
        }

        renderDeciles(f)
        renderImportance(f)
        renderPopulation(f)
        renderVerdict(f)
    }

    private fun renderDeciles(f: FairnessResponse) {
        decileContainer.removeAllViews()
        f.deciles?.forEach { d ->
            val row = layoutInflater
                .inflate(R.layout.item_decile_row, decileContainer, false)
            row.findViewById<TextView>(R.id.tvDecLabel).text = "D${d.decile}"
            // Same 400–750 zoom as the web chart so differences are visible.
            val pct = ((d.avgScore - 400f) / 350f * 100f).toInt().coerceIn(0, 100)
            val pb = row.findViewById<ProgressBar>(R.id.pbDec)
            row.findViewById<TextView>(R.id.tvDecValue).text =
                Math.round(d.avgScore).toString()
            row.findViewById<TextView>(R.id.tvDecHealthy).text =
                d.avgScoreHealthy?.let { Math.round(it).toString() } ?: "–"
            decileContainer.addView(row)

            // bars grow from 0 (web chart draw-in twin); reduced motion snaps
            pb.progress = 0
            if (Motion.animatorsEnabled(pb)) {
                pb.post {
                    ObjectAnimator.ofInt(pb, "progress", pct).apply {
                        duration = 550
                        interpolator = android.view.animation.DecelerateInterpolator(2f)
                        start()
                    }
                }
            } else {
                pb.progress = pct
            }
        }
    }

    private fun renderImportance(f: FairnessResponse) {
        llImportance.removeAllViews()
        f.importance?.take(5)?.forEach { item ->
            val tv = TextView(this).apply {
                setPadding(0, UiUtils.dp(context, 5), 0, UiUtils.dp(context, 5))
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                text = "${item.label}  ·  mean |SHAP| ${"%.4f".format(item.meanAbsShap)}"
            }
            llImportance.addView(tv)
        }
    }

    private fun renderPopulation(f: FairnessResponse) {
        val p = f.population
        tvPop.text = p?.let {
            "Population ${it.count} · min ${it.min} · median ${Math.round(it.median ?: 0f)} · " +
                "mean ${"%.1f".format(it.mean ?: 0f)} ± ${"%.1f".format(it.std ?: 0f)} · max ${it.max}"
        } ?: ""
    }

    private fun renderVerdict(f: FairnessResponse) {
        val tol = f.maxAcceptableGap ?: 30f
        val decGap = f.healthyDecileGap
        val verdict = if (decGap == null) {
            "Insufficient data to evaluate the gate."
        } else if (decGap >= -tol) {
            "PASS — the model does not systematically punish unusual-but-healthy " +
                "volatility: healthy workers in the wildest swings decile score " +
                "${UiUtils.signed(decGap)} pts vs the smoothest (tolerance ±${Math.round(tol)})."
        } else {
            "FAIL — healthy swingy workers lose ${Math.abs(Math.round(decGap))} pts " +
                "(tolerance ±${Math.round(tol)}). The flat volatility penalty we set out " +
                "to avoid has reappeared; retrain with the fairness gate enabled."
        }
        tvVerdict.text = verdict
        tvVerdict.setTextColor(
            getColor(if (decGap != null && decGap >= -tol) R.color.band_good else R.color.band_low)
        )
    }
}
