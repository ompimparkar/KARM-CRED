package com.example.karmcredapp

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contract for GET /api/user/{user_id}?consent=upi,utility,platform
 * (must match backend/app.py + backend/ml/data_utils.py MODEL_FEATURES).
 *
 * Optional sources (UPI, utility, platform rating) are NULLABLE: the value
 * is absent when the worker has no such history or did not consent to
 * sharing it. The backend imputes those and returns data_completeness.
 */
data class UserFeatures(
    val monthly_avg_income: Float?,
    val cohort_adjusted_volatility: Float?,
    val income_trend_slope: Float?,
    val income_floor_ratio: Float?,
    val earning_gap_irregularity: Float?,
    val upi_monthly_txns: Float?,
    val utility_on_time_ratio: Float?,
    val platform_tenure_months: Float?,
    val avg_savings_ratio: Float?,
    val avg_platform_rating: Float?,
    val rating_trend: Float?,
    val data_completeness: Float?
)

data class CohortInfo(
    val gig_type: String?,
    val city: String?,
    val vehicle_class: String?
)

/** One SHAP reason card as returned by the backend. */
data class ReasonItem(
    @SerializedName("feature") val feature: String,
    @SerializedName("key") val key: String?,
    @SerializedName("impact_points") val impactPoints: Float,
    @SerializedName("type") val type: String // "positive" | "negative"
)

/** Counterfactual suggestion: "do X -> about +N points". */
data class Counterfactual(
    val action: String,
    val feature: String,
    val target: Float,
    @SerializedName("delta_points") val deltaPoints: Float
)

data class TrustScoreResponse(
    @SerializedName("user_id") val userId: String,
    val cohort: CohortInfo?,
    val consent: List<String>?,
    val features: UserFeatures?,
    @SerializedName("predicted_trust_score") val predictedTrustScore: Int,
    val summary: String?,
    val reasons: List<ReasonItem>?,
    val counterfactuals: List<Counterfactual>?,
    @SerializedName("radar_values") val radarValues: List<Float>?
)

interface ApiService {
    @GET("api/user/{user_id}")
    fun getUserScore(
        @Path("user_id") userId: String,
        @Query("consent") consent: String
    ): Call<TrustScoreResponse>
}
