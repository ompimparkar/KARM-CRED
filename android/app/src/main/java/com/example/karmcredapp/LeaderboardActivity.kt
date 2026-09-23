package com.example.karmcredapp

import android.content.Intent
import android.os.Bundle
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
 * (Top / Building / Needs Support) over the batch-scored population,
 * cards open the worker's full score screen.
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var adapter: LeaderboardAdapter
    private lateinit var tvState: TextView
    private lateinit var tvMeta: TextView
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
        btnTop = findViewById(R.id.btnLbTop)
        btnMiddle = findViewById(R.id.btnLbMiddle)
        btnBottom = findViewById(R.id.btnLbBottom)

        adapter = LeaderboardAdapter(emptyList()) { userId ->
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_USER_ID, userId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
        val rv = findViewById<RecyclerView>(R.id.rvLeaderboard)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnTop.setOnClickListener { selectTab("top") }
        btnMiddle.setOnClickListener { selectTab("middle") }
        btnBottom.setOnClickListener { selectTab("bottom") }

        selectTab("top")
        load()
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
        adapter.submit(buckets[tab] ?: emptyList())
    }

    private fun load() {
        tvState.text = "Loading leaderboard…"
        tvState.visibility = TextView.VISIBLE
        RetrofitClient.apiService.getLeaderboard()
            .enqueue(object : Callback<LeaderboardResponse> {
                override fun onResponse(
                    call: Call<LeaderboardResponse>,
                    response: Response<LeaderboardResponse>
                ) {
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        tvState.visibility = TextView.GONE
                        buckets["top"] = body.top
                        buckets["middle"] = body.middle
                        buckets["bottom"] = body.bottom
                        tvMeta.text =
                            "Population ${body.populationCount} · median ${body.scoreMedian} · " +
                                "tap a card to open that worker's score"
                        selectTab(currentTab)
                    } else {
                        tvState.text =
                            "Could not load leaderboard (${response.code()}) — retrying on next visit."
                    }
                }

                override fun onFailure(call: Call<LeaderboardResponse>, t: Throwable) {
                    tvState.text = "Backend unreachable: ${t.message}"
                    Toast.makeText(
                        this@LeaderboardActivity,
                        "Check that the Flask server is running.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }
}
