package com.example.karmcredapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var etUserId: EditText
    private lateinit var btnAnalyze: Button
    private lateinit var tvScore: TextView
    private lateinit var tvSummary: TextView
    private lateinit var rvReasonCards: RecyclerView
    private lateinit var btnManageConsent: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        etUserId = findViewById(R.id.etUserId)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        tvScore = findViewById(R.id.tvScore)
        tvSummary = findViewById(R.id.tvSummary)
        rvReasonCards = findViewById(R.id.rvReasonCards)
        btnManageConsent = findViewById(R.id.btnManageConsent)

        // Prevent RecyclerView layout crashes
        rvReasonCards.layoutManager = LinearLayoutManager(this)
        rvReasonCards.adapter = ReasonCardAdapter(emptyList())

        btnAnalyze.setOnClickListener { analyze() }
        btnManageConsent.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // First launch: consent must come BEFORE any scoring happens.
        if (!ConsentManager.hasAnyGrant(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
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
            return
        }

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
                        render(body)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Profile not found or server error (${response.code()})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(call: Call<TrustScoreResponse>, t: Throwable) {
                    btnAnalyze.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Could not reach backend: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun render(data: TrustScoreResponse) {
        tvScore.text = data.predictedTrustScore.toString()
        tvSummary.text = data.summary.orEmpty()

        val cards = mutableListOf<ReasonCard>()
        data.reasons?.forEach { item ->
            val sign = if (item.impactPoints >= 0) "+" else ""
            cards.add(ReasonCard(item.feature, "$sign${item.impactPoints} pts"))
        }
        data.counterfactuals?.forEach { cf ->
            cards.add(
                ReasonCard(
                    "Try: ${cf.action}",
                    "+${cf.deltaPoints.toInt()} pts"
                )
            )
        }
        rvReasonCards.adapter = ReasonCardAdapter(cards)
    }
}
