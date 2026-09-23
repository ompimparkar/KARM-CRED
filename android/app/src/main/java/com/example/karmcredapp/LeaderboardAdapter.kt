package com.example.karmcredapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * One leaderboard worker: id, band-coloured score chip, top SHAP reasons
 * and raw stat chips — same information as the web .lb-card.
 * Tapping a card opens that worker's score in MainActivity.
 */
class LeaderboardAdapter(
    private var items: List<LeaderboardEntry>,
    private val onUserClick: (String) -> Unit
) : RecyclerView.Adapter<LeaderboardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: CardView = view.findViewById(R.id.cardContainer)
        val tvUser: TextView = view.findViewById(R.id.tvLbUser)
        val tvScore: TextView = view.findViewById(R.id.tvLbScore)
        val tvReasons: TextView = view.findViewById(R.id.tvLbReasons)
        val tvChips: TextView = view.findViewById(R.id.tvLbChips)
    }

    fun submit(newItems: List<LeaderboardEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val ctx = holder.itemView.context

        holder.tvUser.text = entry.userId
        holder.tvScore.text = entry.predictedTrustScore.toString()
        holder.tvScore.setTextColor(
            UiUtils.bandColor(ctx, entry.predictedTrustScore)
        )

        val reasons = entry.topReasons
        holder.tvReasons.text = when {
            !reasons.isNullOrEmpty() ->
                reasons.joinToString("\n") { r ->
                    val sign = if (r.impactPoints >= 0) "+" else ""
                    "$sign%.1f  %s".format(r.impactPoints, r.feature)
                }
            !entry.topReason.isNullOrBlank() -> entry.topReason
            else -> "—"
        }

        holder.tvChips.text = chipLine(entry)

        holder.container.setOnClickListener { onUserClick(entry.userId) }
    }

    private fun chipLine(entry: LeaderboardEntry): String {
        val h = entry.highlights ?: return "—"
        val parts = mutableListOf<String>()
        h["monthly_avg_income"]?.let { parts.add("${UiUtils.rupees(it.toDouble())}/mo") }
        h["income_floor_ratio"]?.let { parts.add("floor ${Math.round(it * 100)}%") }
        h["income_trend_slope"]?.let { parts.add("trend ${UiUtils.signed(it * 100)}%") }
        h["earning_gap_irregularity"]?.let { parts.add("σ ${"%.2f".format(it)} d") }
        h["utility_on_time_ratio"]?.let { parts.add("bills ${Math.round(it * 100)}%") }
        h["avg_platform_rating"]?.let { parts.add("${"%.1f".format(it)} ★") }
        return parts.joinToString("  ·  ")
    }

    override fun getItemCount(): Int = items.size
}
