package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.SyncItem
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConfigListViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)

    private val _items = MutableLiveData<List<SyncItem>>(emptyList())
    val items: LiveData<List<SyncItem>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _filterText = MutableLiveData("")
    val filterText: LiveData<String> = _filterText

    private var serverId: String? = null
    var schema: CollectionSchema? = null
        private set

    /** Concrete type names belonging to this collection (lowercased). */
    private var acceptedTypes: Set<String> = emptySet()

    private var allItems: List<SyncItem> = emptyList()
    private var syncClient: SyncClient? = null
    private var activePattern: String? = null
    private var isSubscribed: Boolean = false
    private var sampleJob: Job? = null

    /** Rate history ring buffers: handle -> (fieldKey -> FloatArray) */
    private val rateHistory = mutableMapOf<Long, MutableMap<String, FloatArray>>()
    private var rateKeys: List<String> = emptyList()
    /** Matching -max field for each rate key (e.g. tx-rate -> tx-rate-max) */
    private var rateMaxKeys: Map<String, String> = emptyMap()

    /** LiveData that emits whenever rate history is updated */
    private val _rateHistoryVersion = MutableLiveData(0L)
    val rateHistoryVersion: LiveData<Long> = _rateHistoryVersion

    private val storeObserver = Observer<Map<Long, SyncItem>> { snapshot ->
        if (acceptedTypes.isEmpty()) return@Observer
        allItems = snapshot.values
            .filter { it.type.lowercase() in acceptedTypes }
            .sortedBy { it.name ?: "~${it.handle}" }
        applyFilter()
        if (_isLoading.value == true) _isLoading.value = false
    }

    companion object {
        /** Max samples kept per item per field */
        const val HISTORY_SIZE = 30
        /** Sample interval for the rate-history ring buffers (ms) */
        const val SAMPLE_INTERVAL_MS = 2000L
    }

    /**
     * [memberTypeNames] lists the concrete member types of this collection —
     * the store is shared across all collections, and item identity on the
     * sync channel is (type, name), so the list filters by type name. Falls
     * back to the root type when the collection has no polymorphic members.
     */
    fun initialize(serverId: String, schema: CollectionSchema, memberTypeNames: Collection<String> = emptyList()) {
        this.serverId = serverId
        this.schema = schema

        acceptedTypes = buildSet {
            add(schema.typeName.lowercase())
            memberTypeNames.forEach { add(it.lowercase()) }
        }.filterTo(mutableSetOf()) { it.isNotEmpty() }

        // Detect rate fields for sparklines: tx-rate, rx-rate (exclude -max variants)
        // Order: rx first (left), tx second (right)
        val propKeys = schema.properties.keys
        rateKeys = propKeys
            .filter { key ->
                key.endsWith("-rate") && !key.endsWith("-rate-max")
                    && schema.properties[key]?.isReadOnly == true
            }
            .sortedBy { key -> if (key.startsWith("rx-")) 0 else 1 }
            .toList()

        // Map each rate key to its -max counterpart if it exists
        rateMaxKeys = rateKeys.associateWith { key -> "${key}-max" }
            .filterValues { it in propKeys }

        val server = serverRepository.getServer(serverId)
        if (server == null) {
            _error.value = "Server not found"
            return
        }

        _isLoading.value = true
        _error.value = null

        val client = SyncClientManager.get(server)
        syncClient = client
        // Pattern form is "<type>:<name-glob>" — a bare (non-`=`) type matches
        // descendants too, so the list view catches every member-type
        // subclass in the collection.
        activePattern = "${schema.typeName}:*"

        startRateSampling()
    }

    /**
     * Activate the collection subscription and start observing the store.
     * Called from the fragment's onStart — paired with [pauseSubscription] in
     * onStop so we stop pulling updates while the editor (or another screen)
     * is on top.
     */
    fun resumeSubscription() {
        if (isSubscribed) return
        val client = syncClient ?: return
        val pattern = activePattern ?: return
        // Subscribe BEFORE attaching observer so the initial observe fires
        // with the (possibly pre-populated) store snapshot.
        client.subscribe(pattern)
        client.store.items.observeForever(storeObserver)
        isSubscribed = true
    }

    fun pauseSubscription() {
        if (!isSubscribed) return
        val client = syncClient ?: return
        val pattern = activePattern ?: return
        client.store.items.removeObserver(storeObserver)
        client.unsubscribe(pattern)
        isSubscribed = false
    }

    fun setFilter(text: String) {
        _filterText.value = text
        applyFilter()
    }

    private fun applyFilter() {
        val filter = _filterText.value?.lowercase() ?: ""
        _items.value = if (filter.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                (item.name?.lowercase()?.contains(filter) == true) ||
                    item.properties.values.any { value ->
                        value?.toString()?.lowercase()?.contains(filter) == true
                    }
            }
        }
    }

    /**
     * Sample current rate values on a fixed cadence so the ring buffer stays
     * on a regular time axis. Sync messages arrive at arbitrary rates, so
     * recording them directly would produce an uneven time series.
     */
    private fun startRateSampling() {
        sampleJob?.cancel()
        if (rateKeys.isEmpty()) return
        sampleJob = viewModelScope.launch {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                recordRateHistory(allItems)
            }
        }
    }

    fun deleteItem(handle: Long) {
        val client = syncClient ?: return
        viewModelScope.launch {
            client.destroy(handle).onFailure { e ->
                _error.postValue("Delete failed: ${e.message}")
            }
            // On success, store.unbind has already removed the proxy.
        }
    }

    fun retry() {
        // Subscription is ref-counted and already active; a retry here just
        // clears the error surface and waits for the sync store to populate.
        _error.value = null
        _isLoading.value = allItems.isEmpty()
    }

    /** Append current rate values to per-item ring buffers */
    private fun recordRateHistory(items: List<SyncItem>) {
        if (rateKeys.isEmpty()) return

        for (item in items) {
            val itemHistory = rateHistory.getOrPut(item.handle) { mutableMapOf() }
            for (key in rateKeys) {
                val numericValue = extractNumericValue(item.properties[key]) ?: continue
                val buf = itemHistory.getOrPut(key) { FloatArray(HISTORY_SIZE) }
                System.arraycopy(buf, 1, buf, 0, HISTORY_SIZE - 1)
                buf[HISTORY_SIZE - 1] = numericValue
                itemHistory[key] = buf
            }
        }
        // Drop history for items that no longer exist
        val currentHandles = items.mapTo(HashSet()) { it.handle }
        rateHistory.keys.removeAll { it !in currentHandles }

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

    /** Get rate history for an item by handle */
    fun getRateHistory(handle: Long): Map<String, FloatArray>? = rateHistory[handle]

    /** Get high-water marks from the backend's -max fields for an item */
    fun getHighWaterMarks(handle: Long): Map<String, Float>? {
        if (rateMaxKeys.isEmpty()) return null
        val item = allItems.find { it.handle == handle } ?: return null
        val result = mutableMapOf<String, Float>()
        for ((rateKey, maxKey) in rateMaxKeys) {
            val maxValue = extractNumericValue(item.properties[maxKey])
            if (maxValue != null) result[rateKey] = maxValue
        }
        return result.ifEmpty { null }
    }

    /** The field keys being tracked for sparklines */
    fun getRateKeys(): List<String> = rateKeys

    /** Get a specific item by handle (for passing to editor) */
    fun getItem(handle: Long): SyncItem? = allItems.find { it.handle == handle }

    override fun onCleared() {
        super.onCleared()
        sampleJob?.cancel()
        sampleJob = null
        pauseSubscription()
        activePattern = null
        syncClient = null
    }
}
