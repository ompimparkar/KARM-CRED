package com.example.karmcredapp

import retrofit2.Call
import retrofit2.http.GET


data class TrustScoreResponse(
    val score: Int,
    val reasons: List<ReasonCard>
)

interface ApiService {
    // This connects to the specific URL Om will create 
    @GET("api/get-trust-score")
    fun getTrustScore(): Call<TrustScoreResponse>
}