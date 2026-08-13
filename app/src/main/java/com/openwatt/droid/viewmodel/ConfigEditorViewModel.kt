package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.SyncItem
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.model.PropertySchema
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConfigEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()

    private val _saveResult = MutableLiveData<SaveResult?>()
    val saveResult: LiveData<SaveResult?> = _saveResult

    private val _isSaving = MutableLiveData(false)
    val isSaving: LiveData<Boolean> = _isSaving

    /** Cache of enum values: enumName -> {displayName -> value} */
    private val enumCache = mutableMapOf<String, Map<String, Any>>()

    private val _enumLoaded = MutableLiveData<Pair<String, Map<String, Any>>>()
    val enumLoaded: LiveData<Pair<String, Map<String, Any>>> = _enumLoaded

    private val _refLoaded = MutableLiveData<Pair<String, List<String>>>()
    val refLoaded: LiveData<Pair<String, List<String>>> = _refLoaded

    /** Emits updated item from the sync store (for refreshing non-dirty fields) */
    private val _liveItem = MutableLiveData<SyncItem?>()
    val liveItem: LiveData<SyncItem?> = _liveItem

    private val _commentResult = MutableLiveData<SaveResult?>()
    val commentResult: LiveData<SaveResult?> = _commentResult

    private var serverId: String? = null
    private var schema: CollectionSchema? = null
    private var itemHandle: Long? = null
    private var itemType: String? = null
    private var itemName: String? = null
    private var itemPattern: String? = null
    private var syncClient: SyncClient? = null

    /** Read-only traffic/rate fields charted in the editor (auto-detected). */
    private var chartKeys: List<String> = emptyList()
    /** Rate-history ring buffers for the open item: fieldKey -> samples. */
    private val rateHistory = mutableMapOf<String, FloatArray>()
    private var sampleJob: Job? = null

    private val _rateHistoryVersion = MutableLiveData(0L)
    val rateHistoryVersion: LiveData<Long> = _rateHistoryVersion

    private val storeObserver = Observer<Map<Long, SyncItem>> { snapshot ->
        val handle = itemHandle
        var item = handle?.let { snapshot[it] }
        if (item == null) {
            // Handles are session-scoped: after a reconnect the item comes
            // back under a fresh handle. Re-acquire it by (type, name).
            val type = itemType
            val name = itemName
            if (type != null && name != null) {
                item = snapshot.values.firstOrNull { it.type == type && it.name == name }
                if (item != null) itemHandle = item.handle
            }
        }
        // Track renames so later writes (comment, save) still resolve.
        item?.name?.let { itemName = it }
        _liveItem.value = item
    }

    fun initialize(serverId: String, schema: CollectionSchema) {
        this.serverId = serverId
        this.schema = schema
        val server = serverRepository.getServer(serverId) ?: return
        syncClient = SyncClientManager.get(server)

        // Auto-detect chartable traffic metrics: read-only numeric/quantity
        // fields that look like a rate or a timing/back-pressure measure.
        chartKeys = schema.properties.entries
            .filter { (key, prop) -> isChartable(key, prop) }
            .map { it.key }
    }

    private fun isChartable(key: String, prop: PropertySchema): Boolean {
        if (!prop.isReadOnly) return false
        val type = prop.primaryType
        val numeric = type == "num" || type == "int" || type == "uint" || type.startsWith("q_")
        if (!numeric) return false
        val k = key.lowercase()
        if (k.endsWith("-max")) return false
        return k.endsWith("-rate") || k.contains("queue") || k.contains("service") ||
            k.contains("backpressure") || k.contains("back-pressure") || k.contains("latency")
    }

    /**
     * Start observing live updates for a single item. Installs a narrow
     * `=<type>:<name>` subscription so the server only pushes updates for
     * this one item while the editor is open (`=` pins the exact concrete
     * type). Identity is (type, name) — the session [handle] is only a
     * fast-path key that a reconnect invalidates; the store observer
     * re-acquires the fresh handle by name. Idempotent against repeat
     * calls for the same item (the fragment currently calls twice).
     *
     * NOTE: the pattern is stable only until a rename — renaming an item
     * while it's open in the editor orphans this sub (same limitation as
     * the web client's editor windows).
     */
    fun startObserving(handle: Long, type: String, name: String?) {
        if (itemHandle == handle && itemPattern != null) return
        stopObserving()
        itemHandle = handle
        itemType = type
        itemName = name
        val client = syncClient ?: return
        _liveItem.value = client.store.getItem(handle)
        val pattern = if (name != null) "=$type:$name" else "$type:*"
        itemPattern = pattern
        client.subscribe(pattern)
        client.store.items.observeForever(storeObserver)
        startRateSampling()
    }

    fun stopObserving() {
        sampleJob?.cancel()
        sampleJob = null
        val client = syncClient ?: return
        client.store.items.removeObserver(storeObserver)
        itemPattern?.let { client.unsubscribe(it) }
        itemPattern = null
    }

    /**
     * Sample the open item's chartable fields on a fixed cadence so the ring
     * buffers stay on an even time axis (sync pushes arrive at arbitrary rates).
     */
    private fun startRateSampling() {
        sampleJob?.cancel()
        if (chartKeys.isEmpty()) return
        rateHistory.clear()
        sampleJob = viewModelScope.launch {
            while (true) {
                delay(ConfigListViewModel.SAMPLE_INTERVAL_MS)
                recordSample()
            }
        }
    }

    private fun recordSample() {
        val handle = itemHandle ?: return
        val item = syncClient?.store?.getItem(handle) ?: return
        val size = ConfigListViewModel.HISTORY_SIZE
        for (key in chartKeys) {
            val value = extractNumericValue(item.properties[key]) ?: continue
            val buf = rateHistory.getOrPut(key) { FloatArray(size) }
            System.arraycopy(buf, 1, buf, 0, size - 1)
            buf[size - 1] = value
            rateHistory[key] = buf
        }
        _rateHistoryVersion.value = (_rateHistoryVersion.value ?: 0) + 1
    }

    private fun extractNumericValue(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is Map<*, *> -> (value["q"] as? Number)?.toFloat()
        else -> null
    }

    /** Rate-history ring buffers for the open item, keyed by field. */
    fun getRateHistory(): Map<String, FloatArray> = rateHistory

    fun currentItem(): SyncItem? = itemHandle?.let { syncClient?.store?.getItem(it) }

    override fun onCleared() {
        super.onCleared()
        stopObserving()
    }

    /**
     * Save an item.
     *
     * For new items: send `create{type, props}` over the sync socket; the
     * matching `bind{seq}` installs the proxy and returns the new CID.
     *
     * For existing items: iterate dirty fields and send set / reset over the
     * sync socket. The server echoes each change back via the store, so the
     * UI stays in sync with no round-trip polling.
     */
    fun save(
        isNew: Boolean,
        values: Map<String, Any?>,
        originalItem: SyncItem?,
    ) {
        val client = syncClient
        if (client == null) {
            _saveResult.value = SaveResult.Error("No sync client")
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            _saveResult.value = null

            try {
                if (isNew) {
                    val typeName = schema?.typeName.orEmpty()
                    if (typeName.isEmpty()) {
                        _saveResult.value = SaveResult.Error("Schema has no type name")
                        return@launch
                    }
                    val props = values.entries
                        .filter { (k, v) ->
                            k != "flags" && k != "_originalName" && v != null &&
                                (v !is String || v.isNotEmpty())
                        }
                        .associate { (k, v) -> k to coerceForWire(v) }

                    client.create(typeName, props).onSuccess {
                        _saveResult.value = SaveResult.Success
                    }.onFailure { e ->
                        _saveResult.value = SaveResult.Error(e.message ?: "Failed to add item")
                    }
                } else {
                    val handle = itemHandle
                    if (handle == null) {
                        _saveResult.value = SaveResult.Error("No target item")
                        return@launch
                    }

                    for ((key, value) in values) {
                        if (key == "flags") continue // read-only

                        val strValue = value?.toString() ?: ""
                        val originalValue = originalItem?.properties?.get(key)

                        if (strValue.isEmpty() && originalValue != null &&
                            originalValue.toString().isNotEmpty()
                        ) {
                            // Field was cleared — reset to default
                            client.resetProperty(handle, key)
                        } else if (strValue.isNotEmpty()) {
                            client.setProperty(handle, key, coerceForWire(value))
                        }
                    }

                    _saveResult.value = SaveResult.Success
                }
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Save failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Coerce form-field values into types the sync API expects on the wire.
     * Strings that parse cleanly as numbers/booleans are converted; everything
     * else passes through so the backend can handle unit-suffixed strings etc.
     */
    private fun coerceForWire(value: Any?): Any? {
        if (value !is String) return value
        when (value.lowercase()) {
            "true" -> return true
            "false" -> return false
        }
        value.toLongOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }
        return value
    }

    /**
     * Load enum values asynchronously via the sync socket.
     */
    fun loadEnum(enumName: String) {
        enumCache[enumName]?.let {
            _enumLoaded.value = enumName to it
            return
        }

        val client = syncClient ?: return

        viewModelScope.launch {
            try {
                client.getEnum(enumName).onSuccess { options ->
                    enumCache[enumName] = options
                    _enumLoaded.value = enumName to options
                }
            } catch (_: Exception) {
                // Silently fail — field will show raw value
            }
        }
    }

    /**
     * Load collection items for reference field suggestions.
     * This remains HTTP-based since we just need a one-shot list of names.
     */
    fun loadCollectionRef(refType: String) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        viewModelScope.launch {
            try {
                cliClient.listCollection(server, "/$refType").onSuccess { items ->
                    val names = items.map { it.name }
                    _refLoaded.value = refType to names
                }
            } catch (_: Exception) {
                // Silently fail
            }
        }
    }

    /**
     * Set/clear the comment on an existing item immediately.
     */
    fun setComment(comment: String) {
        val handle = itemHandle ?: return
        val client = syncClient ?: return

        if (comment.isNotEmpty()) {
            client.setProperty(handle, "comment", comment)
        } else {
            client.resetProperty(handle, "comment")
        }
        _commentResult.value = SaveResult.Success
        _commentResult.value = null
    }

    fun clearSaveResult() {
        _saveResult.value = null
    }

    sealed class SaveResult {
        data object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
}
