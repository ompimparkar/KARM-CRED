package com.example.karmcredapp

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

/**
 * One leaderboard worker: id, band-coloured score chip, top SHAP reasons
 * WITH animated bars (web .lb-card parity), raw stat chips.
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
        val reasonContainer: LinearLayout = view.findViewById(R.id.reasonContainer)
        val tvReasonsPlain: TextView = view.findViewById(R.id.tvLbReasonsPlain)
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

        // ---- reasons as label + impact + growing bar --------------------
        holder.reasonContainer.removeAllViews()
        val reasons = entry.topReasons
        if (!reasons.isNullOrEmpty()) {
            holder.tvReasonsPlain.visibility = View.GONE

            // normalise fractions against this card's max |impact|
            val mags = reasons.take(3).map { it.impactPoints.toFloat() }
            val fracs = Motion.barFractions(mags)

            reasons.take(3).forEachIndexed { i, r ->
                val row = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_reason_bar, holder.reasonContainer, false)
                row.findViewById<TextView>(R.id.tvBarLabel).text = r.feature
                val impactText =
                    "${if (r.impactPoints >= 0) "+" else ""}%.1f".format(r.impactPoints)
                val impactView = row.findViewById<TextView>(R.id.tvBarImpact)
                impactView.text = impactText
                impactView.setTextColor(
                    ctx.getColor(
                        if (r.impactPoints >= 0) R.color.band_good else R.color.band_low
                    )
                )

                val track = row.findViewById<FrameLayout>(R.id.barTrack)
                val fill = row.findViewById<View>(R.id.barFill)
                val tint = ctx.getColor(
                    if (r.impactPoints >= 0) R.color.band_good else R.color.band_low
                )
                fill.background.setTint(tint)
                holder.reasonContainer.addView(row)
                growBar(track, fill, fracs[i])
            }
        } else if (!entry.topReason.isNullOrBlank()) {
            holder.tvReasonsPlain.visibility = View.VISIBLE
            holder.tvReasonsPlain.text = entry.topReason
        } else {
            holder.tvReasonsPlain.visibility = View.GONE
        }

        holder.tvChips.text = chipLine(entry)
        Motion.attachPressScale(holder.container)
        holder.container.setOnClickListener { onUserClick(entry.userId) }
    }

    /** Width-grown bar (no scaleX distortion), reduced-motion snaps. */
    private fun growBar(track: View, fill: View, fraction: Float) {
        track.post {
            val target = (track.width * fraction.coerceIn(0f, 1f)).toInt()
            if (!Motion.animatorsEnabled(track)) {
                fill.layoutParams = fill.layoutParams.apply { width = target }
                return@post
            }
            val anim = ValueAnimator.ofInt(0, target).setDuration(420)
            anim.interpolator = android.view.animation.DecelerateInterpolator(2f)
            anim.addUpdateListener {
                fill.layoutParams = fill.layoutParams.apply { width = it.animatedValue as Int }
            }
            anim.start()
        }
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
