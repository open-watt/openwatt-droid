package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConfigEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()
    private val apiClient = OpenWattClient()

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

    /** Emits updated item data from polling (for refreshing non-dirty fields) */
    private val _liveItem = MutableLiveData<CollectionItem?>()
    val liveItem: LiveData<CollectionItem?> = _liveItem

    private var serverId: String? = null
    private var schema: CollectionSchema? = null
    private var itemName: String? = null
    private var pollingJob: Job? = null

    fun initialize(serverId: String, schema: CollectionSchema) {
        this.serverId = serverId
        this.schema = schema
    }

    /** Start polling for live updates to an existing item */
    fun startPolling(name: String) {
        this.itemName = name
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                pollItem()
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollItem() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val path = schema?.path ?: return
        val name = itemName ?: return

        try {
            cliClient.listCollection(server, path).onSuccess { items ->
                val updated = items.find { it.name == name }
                if (updated != null) {
                    _liveItem.value = updated
                }
            }
        } catch (_: Exception) {
            // Silently continue polling
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    /**
     * Save an item (create new or update existing).
     */
    fun save(
        isNew: Boolean,
        originalName: String?,
        values: Map<String, Any?>,
        originalItem: CollectionItem?,
    ) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val path = schema?.path ?: return

        viewModelScope.launch {
            _isSaving.value = true
            _saveResult.value = null

            try {
                if (isNew) {
                    cliClient.addItem(server, path, values).onSuccess {
                        _saveResult.value = SaveResult.Success
                    }.onFailure { e ->
                        _saveResult.value = SaveResult.Error(e.message ?: "Failed to add item")
                    }
                } else {
                    val name = originalName ?: return@launch

                    // Separate values into set and reset operations
                    val setProps = mutableMapOf<String, Any?>()
                    val resetKeys = mutableListOf<String>()

                    for ((key, value) in values) {
                        if (key == "name") continue // Don't set name via set command

                        val strValue = value?.toString() ?: ""
                        val originalValue = originalItem?.properties?.get(key)

                        if (strValue.isEmpty() && originalValue != null && originalValue.toString().isNotEmpty()) {
                            // Value was cleared — reset it
                            resetKeys.add(key)
                        } else if (strValue.isNotEmpty()) {
                            setProps[key] = value
                        }
                    }

                    var success = true

                    if (setProps.isNotEmpty()) {
                        cliClient.setItem(server, path, name, setProps).onFailure { e ->
                            _saveResult.value = SaveResult.Error(e.message ?: "Failed to update item")
                            success = false
                        }
                    }

                    if (success && resetKeys.isNotEmpty()) {
                        cliClient.resetItem(server, path, name, resetKeys).onFailure { e ->
                            _saveResult.value = SaveResult.Error(e.message ?: "Failed to reset properties")
                            success = false
                        }
                    }

                    if (success) {
                        _saveResult.value = SaveResult.Success
                    }
                }
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Save failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Load enum values asynchronously.
     */
    fun loadEnum(enumName: String) {
        // Check cache first
        enumCache[enumName]?.let {
            _enumLoaded.value = enumName to it
            return
        }

        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        viewModelScope.launch {
            try {
                apiClient.getEnum(server, enumName).onSuccess { options ->
                    @Suppress("UNCHECKED_CAST")
                    val typedOptions = options as Map<String, Any>
                    enumCache[enumName] = typedOptions
                    _enumLoaded.value = enumName to typedOptions
                }
            } catch (_: Exception) {
                // Silently fail — field will show raw value
            }
        }
    }

    /**
     * Load collection items for reference field suggestions.
     */
    fun loadCollectionRef(refType: String) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        // Try to find the collection path from schema — we don't have the full schema map here,
        // so we'll try the common pattern of /refType as the path
        viewModelScope.launch {
            try {
                // Try loading via schema path pattern
                cliClient.listCollection(server, "/$refType").onSuccess { items ->
                    val names = items.map { it.name }
                    _refLoaded.value = refType to names
                }
            } catch (_: Exception) {
                // Silently fail
            }
        }
    }

    private val _commentResult = MutableLiveData<SaveResult?>()
    val commentResult: LiveData<SaveResult?> = _commentResult

    /**
     * Set comment on an existing item immediately (no dirty tracking needed).
     */
    fun setComment(itemName: String, comment: String) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val path = schema?.path ?: return

        viewModelScope.launch {
            val command = if (comment.isNotEmpty()) {
                // Only quote if the value contains spaces or quotes
                val needsQuoting = comment.contains(' ') || comment.contains('"')
                val arg = if (needsQuoting) {
                    "comment=\"${comment.replace("\"", "\\\"")}\""
                } else {
                    "comment=$comment"
                }
                "$path/set $itemName $arg"
            } else {
                "$path/reset $itemName comment"
            }
            cliClient.executeCommand(server, command).onSuccess {
                _commentResult.value = SaveResult.Success
            }.onFailure { e ->
                _commentResult.value = SaveResult.Error(e.message ?: "Failed to save comment")
            }
            _commentResult.value = null
        }
    }

    fun clearSaveResult() {
        _saveResult.value = null
    }

    sealed class SaveResult {
        data object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
}
