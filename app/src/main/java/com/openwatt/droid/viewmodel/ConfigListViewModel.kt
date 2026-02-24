package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConfigListViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()

    private val _items = MutableLiveData<List<CollectionItem>>(emptyList())
    val items: LiveData<List<CollectionItem>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _filterText = MutableLiveData("")
    val filterText: LiveData<String> = _filterText

    private var serverId: String? = null
    private var collectionPath: String? = null
    var schema: CollectionSchema? = null
        private set

    private var allItems: List<CollectionItem> = emptyList()
    private var pollingJob: Job? = null

    /** Rate history ring buffers: itemName -> (fieldKey -> FloatArray) */
    private val rateHistory = mutableMapOf<String, MutableMap<String, FloatArray>>()
    private var rateKeys: List<String> = emptyList()
    /** Matching _max field for each rate key (e.g. tx_rate -> tx_rate_max) */
    private var rateMaxKeys: Map<String, String> = emptyMap()

    /** LiveData that emits whenever rate history is updated */
    private val _rateHistoryVersion = MutableLiveData(0L)
    val rateHistoryVersion: LiveData<Long> = _rateHistoryVersion

    companion object {
        /** Max samples kept per item per field */
        const val HISTORY_SIZE = 30
    }

    fun initialize(serverId: String, schema: CollectionSchema) {
        this.serverId = serverId
        this.schema = schema
        this.collectionPath = schema.path

        // Detect rate fields for sparklines: tx_rate, rx_rate (exclude _max variants)
        // Order: rx first (left), tx second (right)
        val propKeys = schema.properties.keys
        rateKeys = propKeys
            .filter { key ->
                key.endsWith("_rate") && !key.endsWith("_rate_max")
                    && schema.properties[key]?.isReadOnly == true
            }
            .sortedBy { key -> if (key.startsWith("rx")) 0 else 1 }
            .toList()

        // Map each rate key to its _max counterpart if it exists
        rateMaxKeys = rateKeys.associateWith { key -> "${key}_max" }
            .filterValues { it in propKeys }

        viewModelScope.launch {
            loadItems(showLoading = true)
            startPolling()
        }
    }

    fun setFilter(text: String) {
        _filterText.value = text
        applyFilter()
    }

    private suspend fun loadItems(showLoading: Boolean = false) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val path = collectionPath ?: return

        if (showLoading) {
            _isLoading.value = true
            _error.value = null
        }

        try {
            cliClient.listCollection(server, path).onSuccess { items ->
                allItems = items
                recordRateHistory(items)
                applyFilter()
            }.onFailure { e ->
                if (showLoading) {
                    _error.value = e.message ?: "Failed to load items"
                }
            }
        } catch (e: Exception) {
            if (showLoading) {
                _error.value = e.message ?: "Failed to load items"
            }
        } finally {
            if (showLoading) {
                _isLoading.value = false
            }
        }
    }

    private fun applyFilter() {
        val filter = _filterText.value?.lowercase() ?: ""
        if (filter.isEmpty()) {
            _items.value = allItems
        } else {
            _items.value = allItems.filter { item ->
                item.name.lowercase().contains(filter) ||
                    item.properties.values.any { value ->
                        value?.toString()?.lowercase()?.contains(filter) == true
                    }
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                loadItems()
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun deleteItem(itemName: String) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val path = collectionPath ?: return

        viewModelScope.launch {
            try {
                cliClient.removeItem(server, path, itemName).onSuccess {
                    loadItems()
                }.onFailure { e ->
                    _error.value = "Delete failed: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            loadItems(showLoading = true)
        }
    }

    /** Append current rate values to per-item ring buffers */
    private fun recordRateHistory(items: List<CollectionItem>) {
        if (rateKeys.isEmpty()) return

        for (item in items) {
            val itemHistory = rateHistory.getOrPut(item.name) { mutableMapOf() }
            for (key in rateKeys) {
                val value = item.properties[key]
                val numericValue = extractNumericValue(value)
                if (numericValue != null) {
                    val buf = itemHistory.getOrPut(key) { FloatArray(HISTORY_SIZE) }
                    // Shift left and append new value at the end
                    System.arraycopy(buf, 1, buf, 0, HISTORY_SIZE - 1)
                    buf[HISTORY_SIZE - 1] = numericValue
                    itemHistory[key] = buf
                }
            }
        }
        // Remove history for items that no longer exist
        val currentNames = items.map { it.name }.toSet()
        rateHistory.keys.removeAll { it !in currentNames }

        _rateHistoryVersion.value = (_rateHistoryVersion.value ?: 0) + 1
    }

    /** Extract a float from a quantity object {q, u} or a plain number */
    private fun extractNumericValue(value: Any?): Float? {
        return when (value) {
            is Number -> value.toFloat()
            is Map<*, *> -> (value["q"] as? Number)?.toFloat()
            else -> null
        }
    }

    /** Get rate history for an item. Returns fieldKey -> FloatArray pairs. */
    fun getRateHistory(itemName: String): Map<String, FloatArray>? = rateHistory[itemName]

    /** Get high-water marks from the backend's _max fields for an item. */
    fun getHighWaterMarks(itemName: String): Map<String, Float>? {
        if (rateMaxKeys.isEmpty()) return null
        val item = allItems.find { it.name == itemName } ?: return null
        val result = mutableMapOf<String, Float>()
        for ((rateKey, maxKey) in rateMaxKeys) {
            val maxValue = extractNumericValue(item.properties[maxKey])
            if (maxValue != null) result[rateKey] = maxValue
        }
        return result.ifEmpty { null }
    }

    /** The field keys being tracked for sparklines */
    fun getRateKeys(): List<String> = rateKeys

    /** Get a specific item by name (for passing to editor after refresh) */
    fun getItem(name: String): CollectionItem? = allItems.find { it.name == name }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
