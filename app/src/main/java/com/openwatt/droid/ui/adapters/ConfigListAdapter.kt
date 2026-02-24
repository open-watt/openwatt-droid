package com.openwatt.droid.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ItemConfigItemBinding
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.formatFlags
import com.openwatt.droid.ui.config.SparklineView
import com.openwatt.droid.util.UnitConverter

class ConfigListAdapter(
    private val schema: CollectionSchema,
    private val onItemClick: (CollectionItem) -> Unit,
    private val onItemLongClick: (CollectionItem) -> Unit,
) : RecyclerView.Adapter<ConfigListAdapter.ItemViewHolder>() {

    private var items: List<CollectionItem> = emptyList()

    /** Callback to get rate history for a given item name */
    var rateHistoryProvider: ((String) -> Map<String, FloatArray>?)? = null

    /** Callback to get high-water marks for a given item name */
    var highWaterMarkProvider: ((String) -> Map<String, Float>?)? = null

    /** Which rate keys to show (max 2) */
    var rateKeys: List<String> = emptyList()

    /** Colors for the sparklines: first = tx/primary, second = rx/secondary */
    private val sparklineColors = intArrayOf(
        0xFF16A34A.toInt(), // green
        0xFF3B82F6.toInt(), // blue
    )

    val currentList: List<CollectionItem> get() = items

    fun submitList(newItems: List<CollectionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Refresh just the sparkline data without full rebind */
    fun refreshSparklines() {
        notifyItemRangeChanged(0, items.size, PAYLOAD_SPARKLINE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemConfigItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SPARKLINE)) {
            holder.bindSparklines(items[position])
        } else {
            holder.bind(items[position])
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ItemViewHolder(
        private val binding: ItemConfigItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CollectionItem) {
            val context = binding.root.context
            val flagsValue = item.flags

            // Name
            binding.itemName.text = item.name

            // Status dot color
            val dotColor = when {
                item.isDisabled -> R.color.state_idle
                flagsValue and (1 shl 4) != 0 -> R.color.state_ok       // Running
                flagsValue and (1 shl 3) != 0 -> R.color.state_error    // Invalid
                flagsValue != 0 -> R.color.state_warn                    // Has flags but not running
                else -> R.color.state_unknown
            }
            binding.statusDot.background.setTint(ContextCompat.getColor(context, dotColor))

            // Type (on name row)
            val type = item["type"]?.toString()
            if (!type.isNullOrEmpty()) {
                binding.itemType.text = type
                binding.itemType.visibility = View.VISIBLE
            } else {
                binding.itemType.visibility = View.GONE
            }

            // Comment (row 1, above name)
            val comment = item.comment
            if (!comment.isNullOrBlank()) {
                binding.itemComment.text = comment
                binding.itemComment.visibility = View.VISIBLE
            } else {
                binding.itemComment.visibility = View.GONE
            }

            // Bottom row: flags + status message
            val flagsText = formatFlags(flagsValue)
            val status = item["status"]?.toString()

            if (flagsText.isNotEmpty()) {
                binding.itemFlags.text = flagsText
                binding.itemFlags.visibility = View.VISIBLE
            } else {
                binding.itemFlags.visibility = View.GONE
            }

            if (!status.isNullOrBlank()) {
                binding.itemSubtitle.text = status
                binding.itemSubtitle.visibility = View.VISIBLE
            } else {
                binding.itemSubtitle.visibility = View.GONE
            }

            binding.bottomRow.visibility =
                if (flagsText.isNotEmpty() || !status.isNullOrBlank()) View.VISIBLE else View.GONE

            // Disabled items greyed out
            binding.root.alpha = if (item.isDisabled) 0.5f else 1.0f

            // Click handlers
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }

            // Sparklines
            bindSparklines(item)
        }

        fun bindSparklines(item: CollectionItem) {
            val container = binding.sparklineContainer
            val history = rateHistoryProvider?.invoke(item.name)
            val hwm = highWaterMarkProvider?.invoke(item.name)
            val keysToShow = rateKeys.take(2)

            if (history == null || keysToShow.isEmpty()) {
                container.visibility = View.GONE
                return
            }

            val hasData = keysToShow.any { key -> history[key] != null }
            if (!hasData) {
                container.visibility = View.GONE
                return
            }

            container.visibility = View.VISIBLE
            container.removeAllViews()

            for ((i, key) in keysToShow.withIndex()) {
                val data = history[key] ?: continue
                if (data.isEmpty()) continue

                // Rate fields are always Bps from backend; display as bps (×8)
                val current = data.last() * 8f
                val formatted = UnitConverter.formatWithPrefix(current.toDouble(), "bps", 3)
                val direction = key.substringBefore("_rate").uppercase()
                val label = "$direction ${formatted.formatted}"

                val highWater = hwm?.get(key)?.let { it * 8f } ?: Float.NaN

                val displayData = FloatArray(data.size) { j -> data[j] * 8f }

                val sparkline = SparklineView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f,
                    ).apply {
                        if (i > 0) marginStart = (2 * resources.displayMetrics.density).toInt()
                    }
                    setColor(sparklineColors[i % sparklineColors.size])
                    setData(displayData)
                    setLabel(label)
                    setHighWaterMark(highWater)
                }
                container.addView(sparkline)
            }
        }

    }

    companion object {
        private const val PAYLOAD_SPARKLINE = "sparkline"
    }
}
