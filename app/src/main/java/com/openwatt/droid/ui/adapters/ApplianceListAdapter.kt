package com.openwatt.droid.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ItemApplianceRowBinding
import com.openwatt.droid.model.energy.ApplianceSummary
import com.openwatt.droid.ui.energy.EnergyFormatters
import kotlin.math.abs

class ApplianceListAdapter(
    private val onSelect: (applianceId: String) -> Unit,
) : ListAdapter<ApplianceSummary, ApplianceListAdapter.ViewHolder>(DIFF_CALLBACK) {

    var selectedId: String? = null
        set(value) {
            val old = field
            field = value
            if (old != value) {
                currentList.forEachIndexed { i, item ->
                    if (item.id == old || item.id == value) notifyItemChanged(i)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemApplianceRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemApplianceRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ApplianceSummary) {
            binding.applianceIcon.text = item.icon
            binding.applianceName.text = item.name

            // Power display with direction and color
            val powerAbs = abs(item.currentPower)
            val powerText = EnergyFormatters.formatPower(powerAbs)
            val arrow = when {
                item.isProducing -> "\u2191 "  // ↑
                item.currentPower > 10 -> "\u2193 " // ↓
                else -> ""
            }
            binding.appliancePower.text = "$arrow$powerText"

            val powerColor = when {
                item.currentPower > 10 -> ContextCompat.getColor(itemView.context, R.color.state_error) // consuming = red
                item.currentPower < -10 -> ContextCompat.getColor(itemView.context, R.color.state_ok) // producing = green
                else -> ContextCompat.getColor(itemView.context, R.color.state_idle)
            }
            binding.appliancePower.setTextColor(powerColor)

            // Daily energy
            val dailyEnergy = if (item.isProducing) item.dailyExport else item.dailyImport
            binding.applianceEnergy.text = if (dailyEnergy > 0) {
                EnergyFormatters.formatEnergy(dailyEnergy)
            } else {
                "--"
            }

            // Selection state
            val isSelected = item.id == selectedId
            binding.root.isActivated = isSelected
            binding.root.alpha = if (isSelected) 1.0f else 0.85f

            binding.root.setOnClickListener { onSelect(item.id) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ApplianceSummary>() {
            override fun areItemsTheSame(a: ApplianceSummary, b: ApplianceSummary) = a.id == b.id
            override fun areContentsTheSame(a: ApplianceSummary, b: ApplianceSummary) = a == b
        }
    }
}
