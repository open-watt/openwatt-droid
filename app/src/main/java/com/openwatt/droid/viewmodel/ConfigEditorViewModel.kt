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
import com.openwatt.droid.repository.ServerRepository
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
    private var itemCid: Int? = null
    private var itemPattern: String? = null
    private var syncClient: SyncClient? = null

    private val storeObserver = Observer<Map<Int, SyncItem>> { snapshot ->
        val cid = itemCid ?: return@Observer
        _liveItem.value = snapshot[cid]
    }

    fun initialize(serverId: String, schema: CollectionSchema) {
        this.serverId = serverId
        this.schema = schema
        val server = serverRepository.getServer(serverId) ?: return
        syncClient = SyncClientManager.get(server)
    }

    /**
     * Start observing live updates for a single item by CID. Installs a
     * narrow `#<cid>` subscription so the server only pushes updates for
     * this one item while the editor is open. Idempotent against repeat
     * calls for the same CID (the fragment currently calls twice).
     *
     * NOTE: `#<cid>` is stable only until a rename — a rename triggers a
     * rekey event on the server. Rekey handling isn't implemented yet, so
     * renaming an item while it's open in the editor will orphan this sub.
     */
    fun startObserving(cid: Int) {
        if (itemCid == cid && itemPattern != null) return
        stopObserving()
        itemCid = cid
        val client = syncClient ?: return
        _liveItem.value = client.store.getItem(cid)
        val pattern = "#$cid"
        itemPattern = pattern
        client.subscribe(pattern)
        client.store.items.observeForever(storeObserver)
    }

    fun stopObserving() {
        val client = syncClient ?: return
        client.store.items.removeObserver(storeObserver)
        itemPattern?.let { client.unsubscribe(it) }
        itemPattern = null
    }

    fun currentItem(): SyncItem? = itemCid?.let { syncClient?.store?.getItem(it) }

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
                    val cid = itemCid
                    if (cid == null) {
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
                            client.resetProperty(cid, key)
                        } else if (strValue.isNotEmpty()) {
                            client.setProperty(cid, key, coerceForWire(value))
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
        val cid = itemCid ?: return
        val client = syncClient ?: return

        if (comment.isNotEmpty()) {
            client.setProperty(cid, "comment", comment)
        } else {
            client.resetProperty(cid, "comment")
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
