package com.openwatt.droid.model

import com.google.gson.JsonObject

/**
 * Element metadata from the device structure tree.
 */
data class ElementMeta(
    val name: String?,
    val desc: String?,
    val unit: String?,
    val mode: String?,
    val access: String?,
)

/**
 * Component in the device hierarchy.
 */
data class ComponentStructure(
    val template: String?,
    val elements: Map<String, ElementMeta>,
    val components: Map<String, ComponentStructure>,
)

/**
 * A formatted summary metric for display.
 */
data class FormattedMetric(
    val label: String,
    val formatted: String,
    val path: String,
)

/**
 * Runtime representation of a device.
 */
class Device(
    val id: String,
    var structure: ComponentStructure?,
) {
    val values = mutableMapOf<String, Any?>()
    val units = mutableMapOf<String, String?>()
    val ages = mutableMapOf<String, Double>()
    val info = mutableMapOf<String, Any?>()
    var expanded = false
        set(value) {
            if (field != value) { field = value; version++ }
        }

    /** Increments on every mutation; lets adapters skip unchanged items. */
    var version = 0L
        private set

    private var _archetype: DeviceArchetype? = null

    // Sampling modes exempt from staleness
    companion object {
        private val STALENESS_EXEMPT_MODES = setOf("manual", "on_demand", "config", "constant", "report")
        private val NO_POLL_MODES = setOf("constant", "config")
    }

    /** Device type from DeviceInfo */
    val type: String
        get() = info["type"]?.toString() ?: "unknown"

    /** Resolved archetype */
    val archetype: DeviceArchetype
        get() {
            if (_archetype == null) {
                _archetype = Archetypes.resolve(type)
            }
            return _archetype!!
        }

    /** Display icon, with smart-switch logic */
    val icon: String
        get() {
            if (type == "smart-switch") {
                val switches = getSwitchComponents()
                if (switches.isNotEmpty()) {
                    val types = switches.mapNotNull { sw ->
                        values["${sw.path}.type"]?.toString()
                    }.toSet()

                    if (types.size == 1) {
                        Archetypes.SWITCH_TYPE_ICONS[types.first()]?.let { return it }
                    }
                    if ("light" in types) return Archetypes.SWITCH_TYPE_ICONS["light"]!!
                    if ("outlet" in types || "power" in types) return Archetypes.SWITCH_TYPE_ICONS["outlet"]!!
                }
            }
            return archetype.icon
        }

    /** Display name */
    val name: String
        get() = info["name"]?.toString() ?: id

    /** Subtitle: manufacturer + model */
    val subtitle: String?
        get() {
            val parts = mutableListOf<String>()
            info["manufacturer"]?.toString()?.let { parts.add(it) }
            (info["model"] ?: info["model_name"])?.toString()?.let { parts.add(it) }

            var text = parts.joinToString(" ")
            info["serial_number"]?.toString()?.let { serial ->
                text = if (text.isNotEmpty()) "$text (S/N: $serial)" else "S/N: $serial"
            }
            return text.ifEmpty { null }
        }

    fun getValue(path: String): Any? = values[path]
    fun getUnit(path: String): String? = units[path]
    fun getAge(path: String): Double? = ages[path]

    /** Get sampling mode for an element from structure */
    fun getElementMode(path: String): String? {
        val parts = path.split(".")
        if (parts.size < 2) return null

        val elemId = parts.last()
        val compParts = parts.dropLast(1)

        var comp = structure?.components ?: return null
        for ((index, part) in compParts.withIndex()) {
            val next = comp[part] ?: return null
            if (index < compParts.size - 1) {
                comp = next.components
            } else {
                return next.elements[elemId]?.mode
            }
        }
        return null
    }

    /** Update values from API response */
    fun updateValues(data: Map<String, Map<String, Any?>>) {
        val prefix = "$id."
        var changed = false
        for ((fullPath, entry) in data) {
            if (fullPath.startsWith(prefix)) {
                val localPath = fullPath.removePrefix(prefix)
                val rawValue = entry["value"]
                val rawUnit = entry["unit"] as? String

                val (resolvedValue, resolvedUnit) = com.openwatt.droid.util.UnitConverter.resolveQuantity(rawValue, rawUnit)
                if (values[localPath] != resolvedValue) changed = true
                values[localPath] = resolvedValue
                resolvedUnit?.let { units[localPath] = it }

                val age = (entry["age"] as? Number)?.toDouble()
                if (age != null) {
                    ages[localPath] = age
                }
            }
        }
        if (changed) version++
    }

    /** Extract DeviceInfo from values */
    fun extractInfo() {
        val infoPrefix = "info."
        for ((path, value) in values) {
            if (path.startsWith(infoPrefix)) {
                info[path.removePrefix(infoPrefix)] = value
            }
        }
        _archetype = null // Reset, type may have changed
    }

    /** Get summary metrics based on archetype */
    fun getSummaryMetrics(): List<FormattedMetric> {
        return archetype.summary.mapNotNull { metric ->
            val value = getValue(metric.path) ?: return@mapNotNull null
            val unit = getUnit(metric.path)
            FormattedMetric(
                label = metric.label,
                formatted = ValueFormatter.format(value, metric.format, unit),
                path = metric.path,
            )
        }
    }

    /** Get state indicator: ok, warn, error, idle, unknown */
    fun getStateIndicator(): String {
        val source = archetype.stateSource ?: return if (values.isNotEmpty()) "ok" else "unknown"
        val stateValue = getValue(source) ?: return "unknown"
        val stateKey = stateValue.toString().lowercase()
        return archetype.stateMap[stateKey] ?: "unknown"
    }

    /** Find all Switch components by template */
    fun getSwitchComponents(): List<SwitchInfo> {
        val switches = mutableListOf<SwitchInfo>()
        collectSwitchComponents(structure?.components ?: emptyMap(), "", switches)
        return switches
    }

    private fun collectSwitchComponents(
        components: Map<String, ComponentStructure>,
        parentPath: String,
        results: MutableList<SwitchInfo>,
    ) {
        for ((compId, comp) in components) {
            val compPath = if (parentPath.isEmpty()) compId else "$parentPath.$compId"
            if (comp.template == "Switch") {
                results.add(SwitchInfo(compId, compPath, comp))
            }
            collectSwitchComponents(comp.components, compPath, results)
        }
    }

    /** Get paths for DeviceInfo (fetched once) */
    fun getInfoPaths(): List<String> {
        val paths = mutableListOf<String>()
        collectTemplatePaths(structure?.components ?: emptyMap(), "", paths, "DeviceInfo")
        return paths
    }

    /** Get paths for Switch type elements (fetched once for icon) */
    fun getSwitchTypePaths(): List<String> {
        return getSwitchComponents().map { "$id.${it.path}.type" }
    }

    /** Get paths for summary display (polled regularly) */
    fun getSummaryPaths(): List<String> {
        val paths = mutableListOf<String>()
        for (metric in archetype.summary) {
            paths.add("$id.${metric.path}")
        }
        archetype.stateSource?.let { paths.add("$id.$it") }
        return paths
    }

    /** Get all element paths when expanded (skip constant/config modes) */
    fun getExpandedPaths(): List<String> {
        if (!expanded || structure == null) return emptyList()
        val paths = mutableListOf<String>()
        collectElementPaths(structure!!.components, "", paths, skipNoPoll = true)
        return paths
    }

    /** Get constant/config element paths (fetched once on expand) */
    fun getConstantPaths(): List<String> {
        if (structure == null) return emptyList()
        val paths = mutableListOf<String>()
        collectConstantPaths(structure!!.components, "", paths)
        return paths
    }

    private fun collectTemplatePaths(
        components: Map<String, ComponentStructure>,
        parentPath: String,
        paths: MutableList<String>,
        templateName: String,
    ) {
        for ((compId, comp) in components) {
            val compPath = if (parentPath.isEmpty()) compId else "$parentPath.$compId"
            if (comp.template == templateName) {
                collectAllNestedElements(comp, compPath, paths)
            }
            collectTemplatePaths(comp.components, compPath, paths, templateName)
        }
    }

    private fun collectAllNestedElements(
        comp: ComponentStructure,
        compPath: String,
        paths: MutableList<String>,
    ) {
        for (elemId in comp.elements.keys) {
            paths.add("$id.$compPath.$elemId")
        }
        for ((subId, subComp) in comp.components) {
            collectAllNestedElements(subComp, "$compPath.$subId", paths)
        }
    }

    private fun collectElementPaths(
        components: Map<String, ComponentStructure>,
        parentPath: String,
        paths: MutableList<String>,
        skipNoPoll: Boolean = false,
    ) {
        for ((compId, comp) in components) {
            if (comp.template == "DeviceInfo") continue
            val compPath = if (parentPath.isEmpty()) compId else "$parentPath.$compId"

            for ((elemId, elemMeta) in comp.elements) {
                if (skipNoPoll && elemMeta.mode != null && elemMeta.mode in NO_POLL_MODES) continue
                paths.add("$id.$compPath.$elemId")
            }
            collectElementPaths(comp.components, compPath, paths, skipNoPoll)
        }
    }

    private fun collectConstantPaths(
        components: Map<String, ComponentStructure>,
        parentPath: String,
        paths: MutableList<String>,
    ) {
        for ((compId, comp) in components) {
            if (comp.template == "DeviceInfo") continue
            val compPath = if (parentPath.isEmpty()) compId else "$parentPath.$compId"

            for ((elemId, elemMeta) in comp.elements) {
                if (elemMeta.mode != null && elemMeta.mode in NO_POLL_MODES) {
                    paths.add("$id.$compPath.$elemId")
                }
            }
            collectConstantPaths(comp.components, compPath, paths)
        }
    }
}

data class SwitchInfo(
    val id: String,
    val path: String,
    val component: ComponentStructure,
)

/**
 * Manages a collection of devices.
 */
class DeviceManager {
    private val devices = mutableMapOf<String, Device>()

    fun initFromList(listData: Map<String, ComponentStructure>) {
        for ((id, structure) in listData) {
            val existing = devices[id]
            if (existing != null) {
                existing.structure = structure
            } else {
                devices[id] = Device(id, structure)
            }
        }
        // Remove devices that no longer exist
        devices.keys.retainAll(listData.keys)
    }

    fun updateValues(valuesData: Map<String, Map<String, Any?>>) {
        for (device in devices.values) {
            device.updateValues(valuesData)
            device.extractInfo()
        }
    }

    fun getAll(): List<Device> = devices.values.toList()

    fun get(id: String): Device? = devices[id]

    /** Get info paths for initial load (DeviceInfo + switch types) */
    fun getInfoPaths(): List<String> {
        return devices.values.flatMap { it.getInfoPaths() + it.getSwitchTypePaths() }
    }

    /** Get paths for polling (summary + expanded) */
    fun getPollingPaths(): List<String> {
        return devices.values.flatMap { device ->
            val paths = device.getSummaryPaths().toMutableList()
            if (device.expanded) {
                paths.addAll(device.getExpandedPaths())
            }
            paths
        }
    }
}
