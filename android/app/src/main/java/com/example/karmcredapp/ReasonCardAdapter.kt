package com.example.karmcredapp

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

/**
 * Reason / counterfactual cards — Android twin of the web `.reason-card`
 * with its animated `.reason-bar`:
 *
 *  - bar grows from 0 to its normalised fraction over ~450ms (decelerate)
 *  - tone colour: green positive / red negative / cyan counterfactual
 *  - cards get the shared press micro-interaction (Motion.attachPressScale)
 */
class ReasonCardAdapter(private val reasons: List<ReasonCard>) :
    RecyclerView.Adapter<ReasonCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: CardView = view.findViewById(R.id.cardContainer)
        val tvReason: TextView = view.findViewById(R.id.tvReasonText)
        val tvImpact: TextView = view.findViewById(R.id.tvImpactScore)
        val tvSub: TextView = view.findViewById(R.id.tvSub)
        val barTrack: FrameLayout = view.findViewById(R.id.barTrack)
        val barFill: View = view.findViewById(R.id.barFill)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reason_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = reasons[position]
        val ctx = holder.itemView.context

        holder.tvReason.text = item.reason
        holder.tvImpact.text = item.impact

        // optional counterfactual sub-line ("or fix mismatches — worth up to…")
        if (item.sub.isNullOrBlank()) {
            holder.tvSub.visibility = View.GONE
        } else {
            holder.tvSub.visibility = View.VISIBLE
            holder.tvSub.text = item.sub
        }

        // ---- bar -------------------------------------------------------
        if (item.barTone == ReasonCard.TONE_NONE || item.barFraction <= 0f) {
            holder.barTrack.visibility = View.GONE
        } else {
            holder.barTrack.visibility = View.VISIBLE
            val tint = when (item.barTone) {
                ReasonCard.TONE_POS -> ctx.getColor(R.color.band_good)
                ReasonCard.TONE_NEG -> ctx.getColor(R.color.band_low)
                else -> ctx.getColor(R.color.accent)
            }
            holder.barFill.background.setTint(tint)
            growBar(holder.barTrack, holder.barFill, item.barFraction)
        }

        Motion.attachPressScale(holder.container)
    }

    /**
     * Grow the fill's width 0 → (fraction × track width) in ~450ms.
     * Width (not scaleX) is animated so the rounded ends never distort.
     * The track is never laid out at bind time (RecyclerView), so the
     * measurement happens inside post{}; reduced motion snaps to final.
     */
    private fun growBar(track: FrameLayout, fill: View, fraction: Float) {
        track.post {
            val target = (track.width * fraction.coerceIn(0f, 1f)).toInt()
            if (!Motion.animatorsEnabled(track)) {
                fill.layoutParams = fill.layoutParams.apply { width = target }
                return@post
            }
            val anim = ValueAnimator.ofInt(0, target).setDuration(450)
            anim.interpolator = android.view.animation.DecelerateInterpolator(2f)
            anim.addUpdateListener {
                fill.layoutParams =
                    fill.layoutParams.apply { width = it.animatedValue as Int }
            }
            anim.start()
        }
    }

    override fun getItemCount(): Int {
        return reasons.size
    }
}
