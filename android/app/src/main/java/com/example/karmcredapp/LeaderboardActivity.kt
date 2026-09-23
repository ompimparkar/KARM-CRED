package com.example.karmcredapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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
 * Gigs Leaderboard — mirrors web #/leaderboard: three segments
 * (Top / Building / Needs Support), GRID6 skeleton while loading,
 * staggered card entrances on every tab switch, reason bars on the cards,
 * empty/error illustration with Retry, cards open the worker's score.
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var adapter: LeaderboardAdapter
    private lateinit var rv: RecyclerView
    private lateinit var tvState: TextView
    private lateinit var tvMeta: TextView
    private lateinit var lbSkeleton: SkeletonView
    private lateinit var lbErrorBox: android.widget.LinearLayout
    private lateinit var btnTop: Button
    private lateinit var btnMiddle: Button
    private lateinit var btnBottom: Button

    private var currentTab = "top"
    private val buckets =
        mutableMapOf<String, List<LeaderboardEntry>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        BottomNav.wire(this, bottomNav, R.id.nav_leaderboard)

        tvState = findViewById(R.id.tvLbState)
        tvMeta = findViewById(R.id.tvLbMeta)
        lbSkeleton = findViewById(R.id.lbSkeleton)
        lbErrorBox = findViewById(R.id.lbErrorBox)
        btnTop = findViewById(R.id.btnLbTop)
        btnMiddle = findViewById(R.id.btnLbMiddle)
        btnBottom = findViewById(R.id.btnLbBottom)

        lbSkeleton.setPattern(SkeletonView.Pattern.GRID6)

        adapter = LeaderboardAdapter(emptyList()) { userId ->
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_USER_ID, userId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            Motion.fadeTo(this)
        }
        rv = findViewById(R.id.rvLeaderboard)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnTop.setOnClickListener { selectTab("top") }
        btnMiddle.setOnClickListener { selectTab("middle") }
        btnBottom.setOnClickListener { selectTab("bottom") }
        findViewById<Button>(R.id.btnLbRetry).setOnClickListener { load() }
        Motion.attachPressScale(btnTop)
        Motion.attachPressScale(btnMiddle)
        Motion.attachPressScale(btnBottom)

        selectTab("top")
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

    private fun selectTab(tab: String) {
        currentTab = tab
        listOf("top" to btnTop, "middle" to btnMiddle, "bottom" to btnBottom)
            .forEach { (key, btn) ->
                val on = key == tab
                btn.setBackgroundColor(
                    getColor(if (on) R.color.accent else R.color.bg_panel)
                )
                btn.setTextColor(
                    getColor(if (on) R.color.bg_dark else R.color.text_secondary)
                )
            }
        // staggered entrance on every tab switch (web staggerRow twin)
        Motion.staggerRecycler(rv)
        adapter.submit(buckets[tab] ?: emptyList())
    }

    private fun load() {
        // GRID6 skeleton while loading
        lbErrorBox.visibility = View.GONE
        tvMeta.visibility = View.GONE
        rv.visibility = View.GONE
        lbSkeleton.visibility = View.VISIBLE

        RetrofitClient.apiService.getLeaderboard()
            .enqueue(object : Callback<LeaderboardResponse> {
                override fun onResponse(
                    call: Call<LeaderboardResponse>,
                    response: Response<LeaderboardResponse>
                ) {
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        lbSkeleton.visibility = View.GONE
                        rv.visibility = View.VISIBLE
                        tvMeta.visibility = View.VISIBLE
                        buckets["top"] = body.top
                        buckets["middle"] = body.middle
                        buckets["bottom"] = body.bottom
                        tvMeta.text =
                            "Population ${body.populationCount} · median ${body.scoreMedian} · " +
                                "tap a card to open that worker's score"
                        selectTab(currentTab)
                    } else {
                        errorState(
                            "Could not load leaderboard (${response.code()})."
                        )
                    }
                }

                override fun onFailure(call: Call<LeaderboardResponse>, t: Throwable) {
                    errorState("Backend unreachable: ${t.message}")
                    Toast.makeText(
                        this@LeaderboardActivity,
                        "Check that the Flask server is running.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun errorState(message: String) {
        lbSkeleton.visibility = View.GONE
        rv.visibility = View.GONE
        tvMeta.visibility = View.GONE
        lbErrorBox.visibility = View.VISIBLE
        tvState.text = message
    }
}
