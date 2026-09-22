package com.example.karmcredapp

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

data class UserFeatures(
    val upi_monthly_txns: Float,
    val utility_on_time_ratio: Float,
    val platform_tenure_months: Float,
    val avg_savings_ratio: Float
)

data class TrustScoreResponse(
    val user_id: String,
    val features: UserFeatures,
    val predicted_trust_score: Int,
    val reasons: List<ReasonCard>,
    val radar_values: List<Float>
)

interface ApiService {
    @GET("api/user/{user_id}")
    fun getUserScore(@Path("user_id") userId: String): Call<TrustScoreResponse>
}