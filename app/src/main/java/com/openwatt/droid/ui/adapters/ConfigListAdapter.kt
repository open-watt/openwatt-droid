package com.openwatt.droid.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ItemConfigItemBinding
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.SyncItem
import com.openwatt.droid.model.formatFlags
import com.openwatt.droid.ui.config.SparklineView
import com.openwatt.droid.util.UnitConverter

class ConfigListAdapter(
    private val schema: CollectionSchema,
    private val onItemClick: (SyncItem) -> Unit,
    private val onItemLongClick: (SyncItem) -> Unit,
) : ListAdapter<SyncItem, ConfigListAdapter.ItemViewHolder>(DIFF) {

    /** Callback to get rate history for a given item by cid */
    var rateHistoryProvider: ((Int) -> Map<String, FloatArray>?)? = null

    /** Callback to get high-water marks for a given item by cid */
    var highWaterMarkProvider: ((Int) -> Map<String, Float>?)? = null

    /** Which rate keys to show (max 2) */
    var rateKeys: List<String> = emptyList()

    /** Colors for the sparklines: first = tx/primary, second = rx/secondary */
    private val sparklineColors = intArrayOf(
        0xFF16A34A.toInt(), // green
        0xFF3B82F6.toInt(), // blue
    )

    /** Refresh just the sparkline data without full rebind */
    fun refreshSparklines() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SPARKLINE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemConfigItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SPARKLINE }) {
            holder.bindSparklines(getItem(position))
        } else {
            holder.bind(getItem(position))
        }
    }

    inner class ItemViewHolder(
        private val binding: ItemConfigItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SyncItem) {
            val context = binding.root.context
            val flagsValue = item.flags

            binding.itemName.text = item.name ?: "#${item.cid}"

            val dotColor = when {
                item.state == "offline" -> R.color.state_idle
                item.isDisabled -> R.color.state_idle
                flagsValue and (1 shl 4) != 0 -> R.color.state_ok       // Running
                flagsValue and (1 shl 3) != 0 -> R.color.state_error    // Invalid
                item.state == "online" -> R.color.state_ok
                flagsValue != 0 -> R.color.state_warn
                else -> R.color.state_unknown
            }
            binding.statusDot.background.setTint(ContextCompat.getColor(context, dotColor))

            // Type shown on name row: use the member-type from bind when present,
            // falling back to a "type" property if the schema carries one.
            val typeDisplay = item.type.takeIf { it.isNotEmpty() } ?: item["type"]?.toString()
            if (!typeDisplay.isNullOrEmpty()) {
                binding.itemType.text = typeDisplay
                binding.itemType.visibility = View.VISIBLE
            } else {
                binding.itemType.visibility = View.GONE
            }

            val comment = item.comment
            if (!comment.isNullOrBlank()) {
                binding.itemComment.text = comment
                binding.itemComment.visibility = View.VISIBLE
            } else {
                binding.itemComment.visibility = View.GONE
            }

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

            binding.root.alpha = if (item.isDisabled) 0.5f else 1.0f

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }

            bindSparklines(item)
        }

        fun bindSparklines(item: SyncItem) {
            val container = binding.sparklineContainer
            val keysToShow = rateKeys.take(2)

            // No rate fields for this collection — keep the row compact.
            if (keysToShow.isEmpty()) {
                container.visibility = View.GONE
                return
            }

            // Always render placeholders when the collection has rate fields,
            // so the boxes and "RX 0 bps / TX 0 bps" labels show immediately
            // and later samples just fill in the trace rather than popping
            // new views into view.
            container.visibility = View.VISIBLE
            container.removeAllViews()

            val history = rateHistoryProvider?.invoke(item.cid)
            val hwm = highWaterMarkProvider?.invoke(item.cid)

            for ((i, key) in keysToShow.withIndex()) {
                val data = history?.get(key) ?: FloatArray(0)
                val direction = key.substringBefore("-rate").uppercase()

                // Rate fields are always Bps from backend; display as bps (×8)
                val label = if (data.isNotEmpty()) {
                    val current = data.last() * 8f
                    val formatted = UnitConverter.formatWithPrefix(current.toDouble(), "bps", 3)
                    "$direction ${formatted.formatted}"
                } else {
                    "$direction 0 bps"
                }

                val highWater = hwm?.get(key)?.let { it * 8f } ?: Float.NaN
                val displayData = if (data.isNotEmpty()) {
                    FloatArray(data.size) { j -> data[j] * 8f }
                } else {
                    FloatArray(0)
                }

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

        // Property-change store updates used to fire notifyDataSetChanged,
        // which cancelled in-flight touch gestures on items that happened to
        // be rebinding (rate-ticker rows). DiffUtil lets unchanged rows keep
        // their gesture state.
        private val DIFF = object : DiffUtil.ItemCallback<SyncItem>() {
            override fun areItemsTheSame(a: SyncItem, b: SyncItem) = a.cid == b.cid
            override fun areContentsTheSame(a: SyncItem, b: SyncItem) = a == b
        }
    }
}
