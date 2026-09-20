package com.example.karmcredapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class ReasonCardAdapter(private val reasons: List<ReasonCard>) :
    RecyclerView.Adapter<ReasonCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: CardView = view.findViewById(R.id.cardContainer)
        val tvReason: TextView = view.findViewById(R.id.tvReasonText)
        val tvImpact: TextView = view.findViewById(R.id.tvImpactScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reason_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = reasons[position]
        holder.tvReason.text = item.title
        holder.tvImpact.text = item.impactPoints

        if (item.isPositive) {
            holder.container.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
            holder.tvImpact.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            holder.container.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
            holder.tvImpact.setTextColor(Color.parseColor("#C62828"))
        }
    }

    override fun getItemCount() = reasons.size
}