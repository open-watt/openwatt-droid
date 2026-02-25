package com.openwatt.droid.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.databinding.ItemSwitchCardBinding
import com.openwatt.droid.model.HomeSwitch

class SwitchGridAdapter(
    private val onToggle: (deviceId: String, switchPath: String, currentValue: Boolean) -> Unit,
) : ListAdapter<HomeSwitch, SwitchGridAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSwitchCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSwitchCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeSwitch) {
            binding.switchIcon.text = item.icon
            binding.switchName.text = item.displayName

            // Set toggle without triggering listener
            binding.switchToggle.setOnCheckedChangeListener(null)
            binding.switchToggle.isChecked = item.isOn
            binding.switchToggle.setOnCheckedChangeListener { _, _ ->
                onToggle(item.deviceId, item.switchPath, item.isOn)
            }

            if (item.powerFormatted != null) {
                binding.switchPower.text = item.powerFormatted
                binding.switchPower.visibility = View.VISIBLE
            } else {
                binding.switchPower.visibility = View.GONE
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HomeSwitch>() {
            override fun areItemsTheSame(oldItem: HomeSwitch, newItem: HomeSwitch): Boolean {
                return oldItem.deviceId == newItem.deviceId && oldItem.switchId == newItem.switchId
            }

            override fun areContentsTheSame(oldItem: HomeSwitch, newItem: HomeSwitch): Boolean {
                return oldItem == newItem
            }
        }
    }
}
