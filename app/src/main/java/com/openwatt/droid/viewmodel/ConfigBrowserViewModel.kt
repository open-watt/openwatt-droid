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
    /** Nesting depth (0 = top-level) — used by the adapter for indentation. */
    abstract val depth: Int

    data class Group(
        val key: String,
        val name: String,
        val displayName: String,
        val icon: String,
        val expanded: Boolean,
        val childCount: Int,
        override val depth: Int = 0,
    ) : BrowserItem()

    data class Leaf(
        val collectionName: String,
        val displayName: String,
        val icon: String,
        val path: String,
        override val depth: Int = 0,
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
                    expandedGroups.addAll(buildGroupKeys())
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
        flattenTree(tree, items, depth = 0, parentKey = "")
        _items.value = items
    }

    /**
     * Recursively flatten the tree into the adapter list.
     */
    private fun flattenTree(
        node: TreeNode,
        items: MutableList<BrowserItem>,
        depth: Int,
        parentKey: String,
    ) {
        val childKeys = node.children.keys.sorted()
        val collections = node.collections.sortedBy { it.label }

        // Render child groups first (folders before files)
        for (childName in childKeys) {
            val child = node.children[childName] ?: continue
            val totalCount = countDescendants(child)
            if (totalCount == 0) continue

            val key = if (parentKey.isEmpty()) childName else "$parentKey/$childName"
            val isExpanded = expandedGroups.contains(key)

            items.add(
                BrowserItem.Group(
                    key = key,
                    name = childName,
                    displayName = formatName(childName),
                    icon = getGroupIcon(childName),
                    expanded = isExpanded,
                    childCount = totalCount,
                    depth = depth,
                )
            )

            if (isExpanded) {
                flattenTree(child, items, depth + 1, key)
            }
        }

        // Then render leaf collections
        for (col in collections) {
            items.add(
                BrowserItem.Leaf(
                    collectionName = col.name,
                    displayName = formatName(col.label),
                    icon = getCollectionIcon(col.name),
                    path = col.schema.path,
                    depth = depth,
                )
            )
        }
    }

    /** Count total leaf collections reachable from a node. */
    private fun countDescendants(node: TreeNode): Int {
        return node.collections.size + node.children.values.sumOf { countDescendants(it) }
    }

    private data class TreeNode(
        val children: MutableMap<String, TreeNode> = mutableMapOf(),
        val collections: MutableList<CollectionEntry> = mutableListOf(),
    )

    private data class CollectionEntry(
        val name: String,
        val schema: CollectionSchema,
        val label: String,
    )

    /**
     * Build recursive tree from collection paths.
     * /interface/modbus/Server → interface (group) → modbus (group) → Server (leaf)
     * /device → device (leaf at root)
     */
    private fun buildPathTree(): TreeNode {
        val root = TreeNode()

        for ((name, collectionSchema) in schema) {
            val parts = collectionSchema.path.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            // Walk intermediate path segments, creating child groups as needed
            var node = root
            for (i in 0 until parts.size - 1) {
                node = node.children.getOrPut(parts[i]) { TreeNode() }
            }

            // Add collection at the deepest node
            node.collections.add(CollectionEntry(name, collectionSchema, parts.last()))
        }

        return root
    }

    /** Build the set of all group keys for initial expansion. */
    private fun buildGroupKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        fun walk(node: TreeNode, parentKey: String) {
            for ((childName, child) in node.children) {
                val key = if (parentKey.isEmpty()) childName else "$parentKey/$childName"
                keys.add(key)
                walk(child, key)
            }
        }
        walk(buildPathTree(), "")
        return keys
    }

    private fun formatName(name: String): String {
        return name
            .replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun getGroupIcon(name: String): String {
        val lower = name.lowercase()
        return GROUP_ICONS[lower] ?: COLLECTION_ICONS[lower] ?: "\uD83D\uDCC1" // 📁
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
