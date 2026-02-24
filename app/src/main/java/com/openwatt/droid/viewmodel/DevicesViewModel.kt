package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Device
import com.openwatt.droid.model.DeviceManager
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var valuePollingJob: Job? = null
    private var listPollingJob: Job? = null

    fun initialize(serverId: String) {
        this.serverId = serverId
        loadDevices()
    }

    private fun loadDevices() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                // 1. Get device structure
                val structureResult = apiClient.listDevices(server)
                structureResult.onSuccess { structure ->
                    deviceManager.initFromList(structure)

                    // 2. Get initial info (static, fetched once)
                    val infoPaths = deviceManager.getInfoPaths()
                    if (infoPaths.isNotEmpty()) {
                        apiClient.getValues(server, infoPaths).onSuccess { values ->
                            deviceManager.updateValues(values)
                        }
                    }

                    // 3. Get initial summary values
                    refreshValues()

                    // 4. Update UI
                    _devices.value = deviceManager.getAll()

                    // 5. Start polling
                    startPolling()
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

    private suspend fun refreshValues() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val paths = deviceManager.getPollingPaths()
        if (paths.isEmpty()) return

        apiClient.getValues(server, paths).onSuccess { values ->
            deviceManager.updateValues(values)
        }
    }

    private fun startPolling() {
        stopPolling()

        // Fast poll: values every 2 seconds
        valuePollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    refreshValues()
                    _devices.value = deviceManager.getAll()
                } catch (_: Exception) {
                    // Silently continue polling
                }
            }
        }

        // Slow poll: device list every 15 seconds
        listPollingJob = viewModelScope.launch {
            while (true) {
                delay(15000)
                try {
                    refreshDeviceList()
                } catch (_: Exception) {
                    // Silently continue polling
                }
            }
        }
    }

    fun stopPolling() {
        valuePollingJob?.cancel()
        valuePollingJob = null
        listPollingJob?.cancel()
        listPollingJob = null
    }

    private suspend fun refreshDeviceList() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        apiClient.listDevices(server).onSuccess { structure ->
            val previousIds = deviceManager.getAll().map { it.id }.toSet()
            deviceManager.initFromList(structure)
            val currentIds = deviceManager.getAll().map { it.id }.toSet()

            val added = currentIds - previousIds
            if (added.isNotEmpty()) {
                // Load info for new devices
                val newInfoPaths = added.flatMap { id ->
                    val device = deviceManager.get(id)
                    (device?.getInfoPaths() ?: emptyList()) + (device?.getSwitchTypePaths() ?: emptyList())
                }
                if (newInfoPaths.isNotEmpty()) {
                    apiClient.getValues(server, newInfoPaths).onSuccess { values ->
                        deviceManager.updateValues(values)
                    }
                }
            }

            if (added.isNotEmpty() || (previousIds - currentIds).isNotEmpty()) {
                _devices.value = deviceManager.getAll()
            }
        }
    }

    /** Toggle a device's expanded state */
    fun toggleExpanded(deviceId: String) {
        val device = deviceManager.get(deviceId) ?: return
        device.expanded = !device.expanded

        if (device.expanded) {
            // Fetch expanded element values
            viewModelScope.launch {
                val server = serverId?.let { serverRepository.getServer(it) } ?: return@launch
                val paths = device.getExpandedPaths() + device.getConstantPaths()
                if (paths.isNotEmpty()) {
                    apiClient.getValues(server, paths).onSuccess { values ->
                        deviceManager.updateValues(values)
                        _devices.value = deviceManager.getAll()
                    }
                }
            }
        }

        _devices.value = deviceManager.getAll()
    }

    /** Toggle a switch on/off */
    fun toggleSwitch(deviceId: String, switchPath: String, currentValue: Boolean) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val fullPath = "$deviceId.$switchPath.switch"
        val newValue = !currentValue

        viewModelScope.launch {
            try {
                val result = apiClient.setValues(server, mapOf(fullPath to newValue))
                result.onSuccess {
                    // Refresh device values to get actual state
                    val device = deviceManager.get(deviceId) ?: return@onSuccess
                    val paths = device.getExpandedPaths()
                    if (paths.isNotEmpty()) {
                        apiClient.getValues(server, paths).onSuccess { values ->
                            deviceManager.updateValues(values)
                        }
                    }
                    _devices.value = deviceManager.getAll()
                }
            } catch (_: Exception) {
                // Refresh to revert UI to actual state
                _devices.value = deviceManager.getAll()
            }
        }
    }

    /** Set an element value */
    fun setValue(deviceId: String, elementPath: String, newValue: Any) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val fullPath = "$deviceId.$elementPath"

        viewModelScope.launch {
            try {
                apiClient.setValues(server, mapOf(fullPath to newValue)).onSuccess {
                    // Refresh to get actual state
                    val device = deviceManager.get(deviceId) ?: return@onSuccess
                    val paths = device.getExpandedPaths()
                    if (paths.isNotEmpty()) {
                        apiClient.getValues(server, paths).onSuccess { values ->
                            deviceManager.updateValues(values)
                        }
                    }
                    _devices.value = deviceManager.getAll()
                }
            } catch (_: Exception) {
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
}
