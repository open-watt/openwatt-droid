package com.openwatt.droid.ui.adapters

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ItemDeviceCardBinding
import com.openwatt.droid.databinding.ItemMetricChipBinding
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.ComponentStructure
import com.openwatt.droid.model.Device
import com.openwatt.droid.model.ValueFormatter

class DeviceListAdapter(
    private val onToggleExpand: (String) -> Unit,
    private val onToggleSwitch: (deviceId: String, switchPath: String, currentValue: Boolean) -> Unit,
    private val onEditValue: (deviceId: String, elementPath: String, elementName: String, currentValue: Any?, unit: String?) -> Unit,
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private var devices: List<Device> = emptyList()
    private var versions: Map<String, Long> = emptyMap()

    fun submitList(newDevices: List<Device>) {
        val oldDevices = devices
        val oldVersions = versions
        devices = newDevices
        versions = newDevices.associate { it.id to it.version }

        if (oldDevices.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        // Diff by id and version — only rebind items that actually changed
        val oldById = oldDevices.withIndex().associate { (i, d) -> d.id to i }
        val newById = newDevices.withIndex().associate { (i, d) -> d.id to i }

        // Removals (old items not in new list)
        for ((id, oldPos) in oldById) {
            if (id !in newById) notifyItemRemoved(oldPos)
        }
        // Insertions (new items not in old list)
        for ((id, newPos) in newById) {
            if (id !in oldById) notifyItemInserted(newPos)
        }
        // Changes (same item, different version)
        for ((id, newPos) in newById) {
            val oldPos = oldById[id] ?: continue
            val oldVersion = oldVersions[id] ?: -1L
            val newVersion = versions[id] ?: 0L
            if (oldVersion != newVersion) {
                if (oldPos != newPos) {
                    notifyItemMoved(oldPos, newPos)
                }
                notifyItemChanged(newPos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(
        private val binding: ItemDeviceCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            val context = binding.root.context

            // State indicator bar color
            val stateColor = when (device.getStateIndicator()) {
                "ok" -> R.color.state_ok
                "warn" -> R.color.state_warn
                "error" -> R.color.state_error
                "idle" -> R.color.state_idle
                else -> R.color.state_unknown
            }
            binding.stateIndicator.setBackgroundColor(ContextCompat.getColor(context, stateColor))

            // Icon
            binding.deviceIcon.text = device.icon

            // Name
            binding.deviceName.text = device.name

            // Subtitle
            val subtitle = device.subtitle
            if (subtitle != null) {
                binding.deviceSubtitle.text = subtitle
                binding.deviceSubtitle.visibility = View.VISIBLE
            } else {
                binding.deviceSubtitle.visibility = View.GONE
            }

            // Type label
            binding.deviceType.text = device.archetype.label

            // Expand arrow
            binding.expandArrow.text = if (device.expanded) "▲" else "▼"

            // Click to toggle expand
            binding.cardHeader.setOnClickListener {
                onToggleExpand(device.id)
            }

            // Summary metrics
            val metrics = device.getSummaryMetrics()
            binding.metricsContainer.removeAllViews()
            if (metrics.isNotEmpty()) {
                binding.metricsScroll.visibility = View.VISIBLE
                for (metric in metrics) {
                    val chipBinding = ItemMetricChipBinding.inflate(
                        LayoutInflater.from(context), binding.metricsContainer, false
                    )
                    chipBinding.metricLabel.text = metric.label
                    chipBinding.metricValue.text = metric.formatted
                    binding.metricsContainer.addView(chipBinding.root)
                }
            } else {
                binding.metricsScroll.visibility = View.GONE
            }

            // Expanded content
            binding.expandedContent.removeAllViews()
            if (device.expanded) {
                binding.expandedContent.visibility = View.VISIBLE
                renderComponentTree(device, device.structure?.components ?: emptyMap(), "", binding.expandedContent)
            } else {
                binding.expandedContent.visibility = View.GONE
            }
        }

        private fun renderComponentTree(
            device: Device,
            components: Map<String, ComponentStructure>,
            parentPath: String,
            container: LinearLayout,
        ) {
            for ((compId, comp) in components) {
                if (compId == "info" || compId == "status") continue

                val compPath = if (parentPath.isEmpty()) compId else "$parentPath.$compId"
                val context = container.context

                // Special rendering for Switch components
                if (comp.template == "Switch") {
                    renderSwitchComponent(device, compId, comp, compPath, container)
                    continue
                }

                // Component header
                val header = TextView(context).apply {
                    text = humanizeId(compId)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 13f
                    setPadding(0, dpToPx(8), 0, dpToPx(4))
                    setTextColor(ContextCompat.getColor(context, R.color.purple_500))
                }
                container.addView(header)

                // Elements
                if (comp.elements.isNotEmpty()) {
                    renderElements(device, comp, compPath, container)
                }

                // Sub-components (recursive)
                if (comp.components.isNotEmpty()) {
                    val indent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpToPx(12), 0, 0, 0)
                    }
                    renderComponentTree(device, comp.components, compPath, indent)
                    container.addView(indent)
                }
            }
        }

        private fun renderSwitchComponent(
            device: Device,
            compId: String,
            comp: ComponentStructure,
            compPath: String,
            container: LinearLayout,
        ) {
            val context = container.context

            val switchValue = device.getValue("$compPath.switch")
            val isOn = switchValue == true || switchValue == "on"
            val switchType = device.getValue("$compPath.type")?.toString()
            val icon = Archetypes.SWITCH_TYPE_ICONS[switchType] ?: "\uD83D\uDD0C"

            // Display name: prefer element name, fall back to humanized ID
            val elemName = comp.elements["switch"]?.name
            val isGeneric = elemName == null || elemName.matches(Regex("(?i)^switch\\s*(state)?$"))
            val displayName = if (isGeneric) humanizeId(compId) else elemName!!

            // Switch row: icon + name + toggle
            val switchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(8), 0, dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            val iconView = TextView(context).apply {
                text = icon
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dpToPx(8) }
            }

            val nameView = TextView(context).apply {
                text = displayName
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }

            val toggle = SwitchMaterial(context).apply {
                isChecked = isOn
                setOnClickListener {
                    onToggleSwitch(device.id, compPath, isOn)
                }
            }

            switchRow.addView(iconView)
            switchRow.addView(nameView)
            switchRow.addView(toggle)
            container.addView(switchRow)

            // Meter data below the switch (power, import, export)
            val meterElements = mutableListOf<Pair<String, String>>()

            // Check elements in the switch component itself (skip switch and type)
            for ((elemId, elemMeta) in comp.elements) {
                if (elemId == "switch" || elemId == "type") continue
                val elemPath = "$compPath.$elemId"
                val value = device.getValue(elemPath) ?: continue
                val unit = device.getUnit(elemPath) ?: elemMeta.unit
                val formatted = ValueFormatter.format(value, "default", unit)
                meterElements.add((elemMeta.name ?: elemId) to formatted)
            }

            // Check nested meter component
            comp.components["meter"]?.let { meterComp ->
                for ((elemId, elemMeta) in meterComp.elements) {
                    if (elemId !in listOf("power", "import", "export")) continue
                    val elemPath = "$compPath.meter.$elemId"
                    val value = device.getValue(elemPath) ?: continue
                    val unit = device.getUnit(elemPath) ?: elemMeta.unit
                    val formatted = ValueFormatter.format(value, "default", unit)
                    meterElements.add((elemMeta.name ?: elemId) to formatted)
                }
            }

            if (meterElements.isNotEmpty()) {
                val meterRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpToPx(26), 0, 0, dpToPx(4))
                }
                for ((label, value) in meterElements) {
                    val item = TextView(context).apply {
                        text = "$label: $value"
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dpToPx(12) }
                    }
                    meterRow.addView(item)
                }
                container.addView(meterRow)
            }
        }

        private fun renderElements(
            device: Device,
            comp: ComponentStructure,
            compPath: String,
            container: LinearLayout,
        ) {
            val context = container.context

            // Grid: 3 elements per row, tile layout (tiny label above value)
            var rowLayout: LinearLayout? = null
            var elemIndex = 0
            val columns = 3

            for ((elemId, elemMeta) in comp.elements) {
                val elemPath = "$compPath.$elemId"
                val value = device.getValue(elemPath)
                val unit = device.getUnit(elemPath) ?: elemMeta.unit
                val formatted = if (value != null) {
                    ValueFormatter.format(value, "default", unit)
                } else {
                    "--"
                }
                val displayName = elemMeta.name ?: elemId
                val isWritable = elemMeta.access == "w" || elemMeta.access == "rw"

                if (elemIndex % columns == 0) {
                    rowLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    container.addView(rowLayout)
                }

                // Tile: small label top-left, value below
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        bottomMargin = dpToPx(4)
                        marginEnd = dpToPx(4)
                    }
                    if (isWritable) {
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onEditValue(device.id, elemPath, displayName, value, unit)
                        }
                    }
                }

                val labelView = TextView(context).apply {
                    text = if (isWritable) "\u270E $displayName" else displayName
                    textSize = 9f
                    setTextColor(
                        if (isWritable) ContextCompat.getColor(context, R.color.purple_500)
                        else ContextCompat.getColor(context, android.R.color.darker_gray)
                    )
                }

                val valueView = TextView(context).apply {
                    text = formatted
                    textSize = 12f
                    maxLines = 1
                    setTypeface(null, Typeface.BOLD)
                }

                tile.addView(labelView)
                tile.addView(valueView)
                rowLayout?.addView(tile)

                elemIndex++
            }

            // Fill remaining cells in last row so tiles don't stretch
            if (elemIndex % columns != 0) {
                val remaining = columns - (elemIndex % columns)
                for (i in 0 until remaining) {
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    }
                    rowLayout?.addView(spacer)
                }
            }
        }

        private fun humanizeId(id: String): String {
            return id
                .replace(Regex("""(\D)(\d+)$"""), "$1 $2")
                .replace(Regex("[_-]"), " ")
                .replaceFirstChar { it.uppercase() }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * binding.root.context.resources.displayMetrics.density).toInt()
        }
    }
}
