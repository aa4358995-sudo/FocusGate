package com.focusgate.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusgate.R

/**
 * RecyclerView adapter for the Work Mode app grid.
 * Displays whitelisted apps in a clean, minimal grid.
 */
class WorkModeAppAdapter(
    private val apps: List<AppGridItem>,
    private val onAppClick: (String) -> Unit
) : RecyclerView.Adapter<WorkModeAppAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        val tvLabel: TextView = itemView.findViewById(R.id.tv_app_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_work_mode_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvLabel.text = app.label
        holder.itemView.setOnClickListener { onAppClick(app.packageName) }

        // Entrance animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setStartDelay(position * 50L)
            .setDuration(300)
            .start()
    }

    override fun getItemCount() = apps.size
}
