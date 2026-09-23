package com.example.karmcredapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Score Simulator — mirrors web #/simulator: the 12 feature sliders are
 * built at runtime from GET /api/feature_defaults (medians = starting
 * position), debounced POST /api/simulate updates the gauge + SHAP reasons +
 * counterfactuals live. Motion layer: TALL2 skeleton on load, gauge
 * count-up + band crossfade, bars with normalised growth, stagger.
 */
class SimulatorActivity : AppCompatActivity() {

    private val values = mutableMapOf<String, Double>()
    private val minMap = mutableMapOf<String, Double>()
    private val spanMap = mutableMapOf<String, Double>()
    private val stepMap = mutableMapOf<String, Double>()
    private val fmtMap = mutableMapOf<String, String>()
    private val labelMap = mutableMapOf<String, String>()
    private val seekMap = mutableMapOf<String, SeekBar>()
    private val labelViews = mutableMapOf<String, TextView>()

    private lateinit var slidersBox: LinearLayout
    private lateinit var tvState: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvSummary: TextView
    private lateinit var rvReasons: RecyclerView
    private lateinit var rvCf: RecyclerView
    private lateinit var simGauge: ScoreGaugeView
    private lateinit var simSkeleton: SkeletonView
    private lateinit var simContent: LinearLayout

    private var firstRender = true

    private val handler = Handler(Looper.getMainLooper())
    private val simulateRunnable = Runnable { runSimulate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulator)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        BottomNav.wire(this, bottomNav, R.id.nav_simulator)

        slidersBox = findViewById(R.id.simSliders)
        tvState = findViewById(R.id.tvSimState)
        tvScore = findViewById(R.id.tvSimScore)
        tvSummary = findViewById(R.id.tvSimSummary)
        rvReasons = findViewById(R.id.rvSimReasons)
        rvCf = findViewById(R.id.rvSimCf)
        simGauge = findViewById(R.id.simGauge)
        simSkeleton = findViewById(R.id.simSkeleton)
        simContent = findViewById(R.id.simContent)

        simSkeleton.setPattern(SkeletonView.Pattern.TALL2)

        rvReasons.layoutManager = LinearLayoutManager(this)
        rvReasons.adapter = ReasonCardAdapter(emptyList())
        rvCf.layoutManager = LinearLayoutManager(this)
        rvCf.adapter = ReasonCardAdapter(emptyList())

        val btnReset = findViewById<Button>(R.id.btnSimReset)
        btnReset.setOnClickListener { resetToTypical() }
        Motion.attachPressScale(btnReset)

        loadDefaults()
    }

    override fun onResume() {
        super.onResume()
        TourManager.attach(this)
    }

    override fun onPause() {
        TourManager.detach(this)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(simulateRunnable)
    }

    // ---------------------------------------------------------- sliders ---
    private fun loadDefaults() {
        setSkeleton(true)
        RetrofitClient.apiService.getFeatureDefaults()
            .enqueue(object : Callback<FeatureDefaultsResponse> {
                override fun onResponse(
                    call: Call<FeatureDefaultsResponse>,
                    response: Response<FeatureDefaultsResponse>
                ) {
                    val d = response.body()
                    if (response.isSuccessful && d != null) {
                        setSkeleton(false)
                        tvState.visibility = TextView.GONE
                        defaultsMedians = d.medians
                        buildSliders(d)
                        resetToTypical()
                    } else {
                        setSkeleton(false)
                        tvState.visibility = TextView.VISIBLE
                        tvState.text = "Could not load slider defaults (${response.code()})"
                    }
                }

                override fun onFailure(
                    call: Call<FeatureDefaultsResponse>, t: Throwable
                ) {
                    setSkeleton(false)
                    tvState.visibility = TextView.VISIBLE
                    tvState.text = "Backend unreachable: ${t.message}"
                }
            })
    }

    /** Skeleton ↔ content swap while the defaults load (web .sk twin). */
    private fun setSkeleton(loading: Boolean) {
        simSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
        simContent.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun buildSliders(defaults: FeatureDefaultsResponse) {
        slidersBox.removeAllViews()
        val order = defaults.labels.keys.filter { defaults.ranges.containsKey(it) }
        for (feature in order) {
            val range = defaults.ranges[feature] ?: continue
            if (range.size < 2 || range[0] >= range[1]) continue

            val min = range[0]
            val span = range[1] - range[0]
            val step = defaults.steps[feature] ?: 0.0
            val fmt = defaults.formats[feature] ?: ""
            val label = defaults.labels[feature] ?: feature

            minMap[feature] = min
            spanMap[feature] = span
            stepMap[feature] = step
            fmtMap[feature] = fmt
            labelMap[feature] = label

            val tv = TextView(this).apply {
                setPadding(0, UiUtils.dp(context, 10), 0, UiUtils.dp(context, 2))
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            }
            val seek = SeekBar(this).apply {
                max = 1000
                progressTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                setPadding(0, 0, 0, UiUtils.dp(context, 6))
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    values[feature] = snap(feature, p)
                    tv.text = rowLabel(feature)
                    scheduleSimulate()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    handler.removeCallbacks(simulateRunnable)
                    handler.postDelayed(simulateRunnable, 60)
                }
            })

            seekMap[feature] = seek
            labelViews[feature] = tv
            slidersBox.addView(tv)
            slidersBox.addView(seek)
        }
    }

    private fun snap(feature: String, progress: Int): Double {
        val min = minMap[feature] ?: 0.0
        val span = spanMap[feature] ?: 1.0
        val step = stepMap[feature] ?: 0.0
        val raw = min + (progress / 1000.0) * span
        return if (step > 0) {
            min + Math.round((raw - min) / step) * step
        } else raw
    }

    private fun progressOf(feature: String, value: Double): Int {
        val min = minMap[feature] ?: 0.0
        val span = spanMap[feature] ?: 1.0
        return (((value - min) / span) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun rowLabel(feature: String): String {
        val v = values[feature] ?: return labelMap[feature] ?: feature
        return "${labelMap[feature]}: ${UiUtils.fmtFeature(v.toFloat(), fmtMap[feature] ?: "")}"
    }

    private fun resetToTypical() {
        for ((feature, seek) in seekMap) {
            val med = defaultsMedians?.get(feature) ?: continue
            values[feature] = snap(feature, progressOf(feature, med))
            seek.progress = progressOf(feature, med)
            labelViews[feature]?.text = rowLabel(feature)
        }
        handler.removeCallbacks(simulateRunnable)
        handler.postDelayed(simulateRunnable, 50)
    }

    private var defaultsMedians: Map<String, Double>? = null

    private fun scheduleSimulate() {
        handler.removeCallbacks(simulateRunnable)
        handler.postDelayed(simulateRunnable, 220)   // debounce like the web
    }

    // --------------------------------------------------------- simulate ---
    private fun runSimulate() {
        if (values.isEmpty()) return
        tvScore.setTextColor(getColor(R.color.text_muted))
        val body: Map<String, Any> = HashMap(values)
        RetrofitClient.apiService.simulate(body)
            .enqueue(object : Callback<SimulateResponse> {
                override fun onResponse(
                    call: Call<SimulateResponse>,
                    response: Response<SimulateResponse>
                ) {
                    val d = response.body()
                    if (response.isSuccessful && d != null) {
                        render(d)
                    } else {
                        tvSummary.text = "Simulation failed (${response.code()})"
                    }
                }

                override fun onFailure(call: Call<SimulateResponse>, t: Throwable) {
                    tvSummary.text = "Backend unreachable: ${t.message}"
                }
            })
    }

    private fun render(data: SimulateResponse) {
        // gauge sweep + number count-up with band crossfade
        Motion.countUpTo(tvScore, data.predictedTrustScore, simGauge)
        tvSummary.text = data.summary.orEmpty()

        // SHAP reasons with normalised growing bars
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
        }
        rvReasons.adapter = ReasonCardAdapter(
            reasonCards.ifEmpty {
                listOf(ReasonCard("No attribution available.", "—"))
            }
        )

        // counterfactuals as cyan cards
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
        rvCf.adapter = ReasonCardAdapter(cfCards)

        // staggered entrance on the first render only (slider ticks are live)
        if (firstRender) {
            firstRender = false
            Motion.staggerRecycler(rvReasons)
            Motion.staggerRecycler(rvCf)
        }
    }
}
