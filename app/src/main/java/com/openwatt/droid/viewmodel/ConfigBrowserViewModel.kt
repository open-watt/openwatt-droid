package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.launch

/**
 * Item in the browser list — either a group header or a collection leaf.
 */
sealed class BrowserItem {
    data class Group(
        val name: String,
        val displayName: String,
        val icon: String,
        val expanded: Boolean,
        val childCount: Int,
    ) : BrowserItem()

    data class Leaf(
        val collectionName: String,
        val displayName: String,
        val icon: String,
        val path: String,
    ) : BrowserItem()
}

class ConfigBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()

    private val _items = MutableLiveData<List<BrowserItem>>(emptyList())
    val items: LiveData<List<BrowserItem>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /** Full schema, keyed by collection name */
    private var schema: Map<String, CollectionSchema> = emptyMap()

    /** Which groups are expanded */
    private val expandedGroups = mutableSetOf<String>()

    private var serverId: String? = null

    fun initialize(serverId: String) {
        this.serverId = serverId
        loadSchema()
    }

    private fun loadSchema() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                apiClient.getSchema(server).onSuccess { loadedSchema ->
                    schema = loadedSchema
                    // Expand all groups by default
                    expandedGroups.addAll(buildGroupNames())
                    rebuildItems()
                }.onFailure { e ->
                    _error.value = e.message ?: "Failed to load schema"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load schema"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getSchema(collectionName: String): CollectionSchema? = schema[collectionName]

    fun toggleGroup(groupName: String) {
        if (expandedGroups.contains(groupName)) {
            expandedGroups.remove(groupName)
        } else {
            expandedGroups.add(groupName)
        }
        rebuildItems()
    }

    fun retry() {
        loadSchema()
    }

    /**
     * Build the flat list of browser items from the schema tree.
     */
    private fun rebuildItems() {
        val tree = buildPathTree()
        val items = mutableListOf<BrowserItem>()

        for ((groupName, node) in tree.toSortedMap()) {
            val collections = node.collections.sortedBy { it.label }
            val childCount = collections.size
            val isExpanded = expandedGroups.contains(groupName)

            items.add(
                BrowserItem.Group(
                    name = groupName,
                    displayName = formatName(groupName),
                    icon = getGroupIcon(groupName),
                    expanded = isExpanded,
                    childCount = childCount,
                )
            )

            if (isExpanded) {
                for (col in collections) {
                    items.add(
                        BrowserItem.Leaf(
                            collectionName = col.name,
                            displayName = formatName(col.label),
                            icon = getCollectionIcon(col.name),
                            path = col.schema.path,
                        )
                    )
                }
            }
        }

        _items.value = items
    }

    private data class TreeNode(
        val collections: MutableList<CollectionEntry> = mutableListOf(),
    )

    private data class CollectionEntry(
        val name: String,
        val schema: CollectionSchema,
        val label: String,
    )

    /**
     * Build tree from collection paths.
     * /interface/modbus → group="interface", leaf="modbus"
     * /device → group="device", leaf="device"
     */
    private fun buildPathTree(): Map<String, TreeNode> {
        val tree = mutableMapOf<String, TreeNode>()

        for ((name, collectionSchema) in schema) {
            val parts = collectionSchema.path.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            val groupName = parts.first()
            val leafLabel = if (parts.size > 1) parts.last() else parts.first()

            val node = tree.getOrPut(groupName) { TreeNode() }
            node.collections.add(CollectionEntry(name, collectionSchema, leafLabel))
        }

        return tree
    }

    private fun buildGroupNames(): Set<String> {
        return schema.values.mapNotNull { s ->
            s.path.split("/").filter { it.isNotEmpty() }.firstOrNull()
        }.toSet()
    }

    private fun formatName(name: String): String {
        return name
            .replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun getGroupIcon(name: String): String {
        return GROUP_ICONS[name.lowercase()] ?: "\uD83D\uDCC1" // 📁
    }

    private fun getCollectionIcon(name: String): String {
        val lower = name.lowercase()
        for ((key, icon) in COLLECTION_ICONS) {
            if (lower.contains(key)) return icon
        }
        return "\uD83D\uDCC4" // 📄
    }

    companion object {
        private val GROUP_ICONS = mapOf(
            "interface" to "\uD83D\uDD0C", // 🔌
            "device" to "\uD83D\uDCF1",    // 📱
            "stream" to "\uD83D\uDCA7",    // 💧
            "route" to "\uD83D\uDD00",     // 🔀
            "schedule" to "\u23F0",         // ⏰
            "automation" to "\uD83E\uDD16", // 🤖
            "energy" to "\u26A1",           // ⚡
            "sampler" to "\uD83D\uDCCA",   // 📊
            "logger" to "\uD83D\uDCDD",    // 📝
            "alert" to "\uD83D\uDD14",     // 🔔
        )

        private val COLLECTION_ICONS = mapOf(
            "modbus" to "\uD83D\uDCE1",   // 📡
            "mqtt" to "\uD83D\uDCE8",     // 📨
            "http" to "\uD83C\uDF10",     // 🌐
            "zigbee" to "\uD83D\uDCF6",   // 📶
            "can" to "\uD83D\uDE97",      // 🚗
            "device" to "\uD83D\uDCF1",   // 📱
            "stream" to "\uD83D\uDCA7",   // 💧
            "route" to "\uD83D\uDD00",    // 🔀
            "schedule" to "\u23F0",        // ⏰
            "automation" to "\uD83E\uDD16",// 🤖
            "energy" to "\u26A1",          // ⚡
            "sampler" to "\uD83D\uDCCA",  // 📊
            "logger" to "\uD83D\uDCDD",   // 📝
            "alert" to "\uD83D\uDD14",    // 🔔
        )
    }
}
