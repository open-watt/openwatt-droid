package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Device
import com.openwatt.droid.model.DeviceManager
import com.openwatt.droid.network.ModelMirror
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Devices tab.
 *
 * Device *structure* still comes from `POST /api/list` on a slow refresh —
 * the model plane's `add` frames carry no component templates (which drive
 * archetype/Switch/DeviceInfo detection), and removal is only visible through
 * a re-list. *Values* ride live model-plane subscriptions: a collapsed card
 * holds one narrow `device:<summary-path>` pattern per summary metric, an
 * expanded card swaps them for a single `device:<id>.**`. Static identity
 * (info elements) is read via one-shot fetches, re-read on every sync attach
 * because a one-shot served nothing if the socket was down at mount.
 */
class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()
    private val deviceManager = DeviceManager()

    private val _devices = MutableLiveData<List<Device>>(emptyList())
    val devices: LiveData<List<Device>> = _devices

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var serverId: String? = null
    private var syncClient: SyncClient? = null
    private var listPollingJob: Job? = null
    private var attached = false

    /** Live value feeds, keyed by pattern. */
    private val valueSubs = mutableMapOf<String, ModelMirror.Subscription>()

    /** Coalesced UI refresh after live updates. */
    private var refreshJob: Job? = null
    private var lastConnected: Boolean? = null

    private val mirrorListener = object : ModelMirror.Listener {
        override fun onChange(dirty: Set<ModelMirror.Node>) {
            val entries = dirty
                .filter { it.ns == "device" && !it.isDevice }
                .associate { it.path to mapOf("value" to it.value, "unit" to it.unit) }
            if (entries.isEmpty()) return
            deviceManager.updateValues(entries)
            scheduleRefresh()
        }
    }

    /**
     * Re-read static identity and re-arm after every sync attach: handles
     * (and one-shot fetches) don't survive the socket, and a fetch that raced
     * a down socket at mount served nothing.
     */
    private val connectedObserver = Observer<Boolean> { connected ->
        val was = lastConnected
        lastConnected = connected
        if (was == false && connected && attached) {
            viewModelScope.launch { fetchStaticInfo() }
        }
    }

    /** Attach to the live feeds. Idempotent; re-attaches after [stopPolling]. */
    fun initialize(serverId: String) {
        this.serverId = serverId
        if (attached) return
        attached = true

        val server = serverRepository.getServer(serverId) ?: return
        val client = SyncClientManager.get(server)
        syncClient = client
        client.model.addListener(mirrorListener)
        lastConnected = null
        client.connected.observeForever(connectedObserver)

        loadDevices()
    }

    private fun loadDevices() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val structureResult = apiClient.listDevices(server)
                structureResult.onSuccess { structure ->
                    deviceManager.initFromList(structure)
                    fetchStaticInfo()
                    reconcileSubscriptions()
                    _devices.value = deviceManager.getAll()
                    startListPolling()
                }.onFailure { e ->
                    _error.value = e.message ?: "Failed to load devices"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load devices"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * One-shot read of the static identity elements (DeviceInfo + switch
     * types). The burst lands in the mirror; fold the values into the device
     * model from there.
     */
    private suspend fun fetchStaticInfo() {
        val client = syncClient ?: return
        val paths = deviceManager.getInfoPaths()
        if (paths.isEmpty()) return
        client.model.fetch(paths.map { "device:$it" })
        applyMirrorValues(paths)
        _devices.value = deviceManager.getAll()
    }

    /** Fold current mirror values for [paths] into the device model. */
    private fun applyMirrorValues(paths: List<String>) {
        val client = syncClient ?: return
        val entries = mutableMapOf<String, Map<String, Any?>>()
        for (path in paths) {
            val node = client.model.getNode(path) ?: continue
            entries[path] = mapOf("value" to node.value, "unit" to node.unit)
        }
        if (entries.isNotEmpty()) deviceManager.updateValues(entries)
    }

    /**
     * Diff the wanted pattern set against the held one. Collapsed cards hold
     * one narrow pattern per summary path; an expanded card holds a single
     * `device:<id>.**` (which subsumes them — and every surviving pattern is
     * re-walked on unsub, so the narrow ones are dropped while it's open).
     * The mirror batches the resulting sub/unsub into one frame each way.
     */
    private fun reconcileSubscriptions() {
        val client = syncClient ?: return
        if (!attached) return
        val wanted = mutableSetOf<String>()
        for (device in deviceManager.getAll()) {
            if (device.expanded) {
                wanted.add("device:${device.id}.**")
            } else {
                device.getSummaryPaths().forEach { wanted.add("device:$it") }
            }
        }

        for (pattern in valueSubs.keys.toList()) {
            if (pattern !in wanted) valueSubs.remove(pattern)?.release()
        }
        for (pattern in wanted) {
            if (pattern !in valueSubs) valueSubs[pattern] = client.model.subscribe(pattern)
        }
    }

    private fun scheduleRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            delay(REFRESH_MS)
            _devices.value = deviceManager.getAll()
        }
    }

    /**
     * Slow structure refresh: the model plane has no `gone` verb and its
     * `add` frames carry no component templates, so device addition/removal
     * is only visible through a re-list.
     */
    private fun startListPolling() {
        listPollingJob?.cancel()
        listPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(LIST_REFRESH_MS)
                try {
                    refreshDeviceList()
                } catch (_: Exception) {
                    // Silently continue polling
                }
            }
        }
    }

    /** Detach from the live feeds while the screen is off-stage. */
    fun stopPolling() {
        attached = false
        listPollingJob?.cancel()
        listPollingJob = null
        refreshJob?.cancel()
        refreshJob = null
        valueSubs.values.forEach { it.release() }
        valueSubs.clear()
        syncClient?.model?.removeListener(mirrorListener)
        syncClient?.connected?.removeObserver(connectedObserver)
        syncClient = null
    }

    private suspend fun refreshDeviceList() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        apiClient.listDevices(server).onSuccess { structure ->
            val previousIds = deviceManager.getAll().map { it.id }.toSet()
            deviceManager.initFromList(structure)
            val currentIds = deviceManager.getAll().map { it.id }.toSet()

            val added = currentIds - previousIds
            if (added.isNotEmpty()) {
                // Load static identity for new devices
                val newInfoPaths = added.flatMap { id ->
                    val device = deviceManager.get(id)
                    (device?.getInfoPaths() ?: emptyList()) + (device?.getSwitchTypePaths() ?: emptyList())
                }
                if (newInfoPaths.isNotEmpty()) {
                    syncClient?.model?.fetch(newInfoPaths.map { "device:$it" })
                    applyMirrorValues(newInfoPaths)
                }
            }

            if (added.isNotEmpty() || (previousIds - currentIds).isNotEmpty()) {
                reconcileSubscriptions()
                _devices.value = deviceManager.getAll()
            }
        }
    }

    /** Toggle a device's expanded state */
    fun toggleExpanded(deviceId: String) {
        val device = deviceManager.get(deviceId) ?: return
        device.expanded = !device.expanded

        // Paint immediately from the known structure; the burst from the
        // swapped subscription fills values in as it lands.
        _devices.value = deviceManager.getAll()
        reconcileSubscriptions()

        if (device.expanded) {
            // Constant/config elements are excluded from the live feed set;
            // read them once per expand.
            viewModelScope.launch {
                val paths = device.getConstantPaths()
                if (paths.isNotEmpty()) {
                    syncClient?.model?.fetch(paths.map { "device:$it" })
                    applyMirrorValues(paths)
                    _devices.value = deviceManager.getAll()
                }
            }
        }
    }

    /** Toggle a switch on/off via the model plane's element write. */
    fun toggleSwitch(deviceId: String, switchPath: String, currentValue: Boolean) {
        setValue(deviceId, "$switchPath.switch", !currentValue)
    }

    /** Set an element value; `res` carries the applied value, no read-back. */
    fun setValue(deviceId: String, elementPath: String, newValue: Any) {
        val client = syncClient ?: return
        val fullPath = "$deviceId.$elementPath"

        viewModelScope.launch {
            client.model.setValue(fullPath, newValue).onSuccess { applied ->
                deviceManager.updateValues(mapOf(fullPath to mapOf("value" to applied)))
                _devices.value = deviceManager.getAll()
            }.onFailure {
                // Revert UI to actual state
                _devices.value = deviceManager.getAll()
            }
        }
    }

    fun retry() {
        loadDevices()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        /** Slow structure re-list cadence (matches the web client). */
        private const val LIST_REFRESH_MS = 15_000L

        /** Coalescing window between live-update list rebuilds. */
        private const val REFRESH_MS = 500L
    }
}
