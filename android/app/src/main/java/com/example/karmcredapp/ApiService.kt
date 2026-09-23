package com.example.karmcredapp

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contract for the Flask backend (must match backend/app.py + backend/ml/
 * data_utils.py MODEL_FEATURES). All six web sections are mirrored here.
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

// ---------------------------------------------------------------- next ----
// Leaderboard (web: #/leaderboard)

data class LeaderboardEntry(
    @SerializedName("user_id") val userId: String,
    @SerializedName("predicted_trust_score") val predictedTrustScore: Int,
    @SerializedName("top_reason") val topReason: String?,
    @SerializedName("top_reasons") val topReasons: List<ReasonItem>?,
    val highlights: Map<String, Float?>?
)

data class LeaderboardResponse(
    val top: List<LeaderboardEntry>,
    val middle: List<LeaderboardEntry>,
    val bottom: List<LeaderboardEntry>,
    @SerializedName("score_median") val scoreMedian: Int,
    @SerializedName("population_count") val populationCount: Int
)

// ---------------------------------------------------------------- next ----
// Simulator (web: #/simulator) — same payload as the user page minus user_id

data class SimulateResponse(
    val cohort: CohortInfo?,
    val consent: List<String>?,
    val features: UserFeatures?,
    @SerializedName("predicted_trust_score") val predictedTrustScore: Int,
    val summary: String?,
    val reasons: List<ReasonItem>?,
    val counterfactuals: List<Counterfactual>?,
    @SerializedName("radar_values") val radarValues: List<Float>?
)

// ---------------------------------------------------------------- next ----
// Compare (web: #/compare)

data class DiffReason(
    val key: String?,
    val feature: String,
    @SerializedName("impact_a") val impactA: Float,
    @SerializedName("impact_b") val impactB: Float,
    val delta: Float
)

data class CompareResponse(
    @SerializedName("user_a") val userA: TrustScoreResponse,
    @SerializedName("user_b") val userB: TrustScoreResponse,
    @SerializedName("score_gap") val scoreGap: Int,
    @SerializedName("diff_reasons") val diffReasons: List<DiffReason>?
)

// ---------------------------------------------------------------- next ----
// Fairness (web: #/fairness)

data class DecileRow(
    val decile: Int,
    val count: Int,
    @SerializedName("avg_score") val avgScore: Float,
    @SerializedName("healthy_count") val healthyCount: Int?,
    @SerializedName("avg_score_healthy") val avgScoreHealthy: Float?
)

data class ImportanceItem(
    val feature: String?,
    val label: String,
    @SerializedName("mean_abs_shap") val meanAbsShap: Float
)

data class PopulationStats(
    val count: Int?,
    val min: Int?,
    val median: Float?,
    val max: Int?,
    val mean: Float?,
    val std: Float?
)

data class HistogramBucket(
    val bucket: String,
    val count: Int
)

data class FairnessResponse(
    @SerializedName("score_by_volatility_decile") val deciles: List<DecileRow>?,
    @SerializedName("healthy_decile_gap") val healthyDecileGap: Float?,
    @SerializedName("healthy_high_volatility_avg_score") val highVolHealthyAvg: Float?,
    @SerializedName("low_volatility_avg_score") val lowVolAvg: Float?,
    @SerializedName("gap_vs_low_volatility") val gapVsLowVol: Float?,
    @SerializedName("max_acceptable_gap_points") val maxAcceptableGap: Float?,
    @SerializedName("global_feature_importance") val importance: List<ImportanceItem>?,
    @SerializedName("population_stats") val population: PopulationStats?,
    @SerializedName("score_histogram") val histogram: List<HistogramBucket>?,
    @SerializedName("group_definition") val groupDefinition: String?
)

// ---------------------------------------------------------------- next ----
// Simulator slider metadata (web: GET /api/feature_defaults)

data class FeatureDefaultsResponse(
    val medians: Map<String, Double>,
    val ranges: Map<String, List<Double>>,
    val steps: Map<String, Double>,
    val formats: Map<String, String>,
    val labels: Map<String, String>
)

// ------------------------------------------------------------ endpoints ---
interface ApiService {
    @GET("api/user/{user_id}")
    fun getUserScore(
        @Path("user_id") userId: String,
        @Query("consent") consent: String
    ): Call<TrustScoreResponse>

    @GET("api/leaderboard")
    fun getLeaderboard(): Call<LeaderboardResponse>

    @POST("api/simulate")
    fun simulate(@Body body: Map<String, Any>): Call<SimulateResponse>

    @GET("api/compare")
    fun compare(
        @Query("a") a: String,
        @Query("b") b: String
    ): Call<CompareResponse>

    @GET("api/fairness")
    fun getFairness(): Call<FairnessResponse>

    @GET("api/feature_defaults")
    fun getFeatureDefaults(): Call<FeatureDefaultsResponse>

    @GET("api/health")
    fun health(): Call<Map<String, Any>>
}
