package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.DeviceManager
import com.openwatt.droid.model.HomeSwitch
import com.openwatt.droid.model.ValueFormatter
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()
    private val deviceManager = DeviceManager()

    private val _switches = MutableLiveData<List<HomeSwitch>>(emptyList())
    val switches: LiveData<List<HomeSwitch>> = _switches

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var serverId: String? = null
    private var valuePollingJob: Job? = null
    private var listPollingJob: Job? = null

    fun initialize(serverId: String) {
        if (this.serverId == serverId && _switches.value?.isNotEmpty() == true) return
        this.serverId = serverId
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

                    // Fetch info + switch types (once)
                    val infoPaths = deviceManager.getInfoPaths()
                    if (infoPaths.isNotEmpty()) {
                        apiClient.getValues(server, infoPaths).onSuccess { values ->
                            deviceManager.updateValues(values)
                        }
                    }

                    // Fetch initial switch values
                    refreshSwitchValues()
                    _switches.value = buildSwitchList()

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

    /** Build flat list of all switches across all devices */
    private fun buildSwitchList(): List<HomeSwitch> {
        return deviceManager.getAll().flatMap { device ->
            device.getSwitchComponents().map { sw ->
                val switchValue = device.getValue("${sw.path}.switch")
                val isOn = switchValue == true || switchValue == "on"
                val switchType = device.getValue("${sw.path}.type")?.toString()
                val icon = Archetypes.SWITCH_TYPE_ICONS[switchType] ?: "\uD83D\uDD0C"

                // Display name
                val elemName = sw.component.elements["switch"]?.name
                val isGeneric = elemName == null || elemName.matches(Regex("(?i)^switch\\s*(state)?$"))
                val displayName = if (isGeneric) humanizeId(sw.id) else elemName!!

                // Power from meter sub-component
                val powerValue = device.getValue("${sw.path}.meter.power")
                val powerUnit = device.getUnit("${sw.path}.meter.power")
                val power = (powerValue as? Number)?.toDouble()
                val powerFormatted = if (power != null) {
                    ValueFormatter.format(powerValue, "power", powerUnit)
                } else null

                HomeSwitch(
                    deviceId = device.id,
                    deviceName = device.name,
                    switchId = sw.id,
                    switchPath = sw.path,
                    displayName = displayName,
                    switchType = switchType,
                    icon = icon,
                    isOn = isOn,
                    power = power,
                    powerFormatted = powerFormatted,
                )
            }
        }
    }

    /** Get paths needed for switch polling */
    private fun getSwitchPaths(): List<String> {
        return deviceManager.getAll().flatMap { device ->
            device.getSwitchComponents().flatMap { sw ->
                val base = "${device.id}.${sw.path}"
                val paths = mutableListOf("$base.switch")
                // Meter data if available
                sw.component.components["meter"]?.let { meter ->
                    for (elemId in meter.elements.keys) {
                        paths.add("$base.meter.$elemId")
                    }
                }
                paths
            }
        }
    }

    private suspend fun refreshSwitchValues() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val paths = getSwitchPaths()
        if (paths.isEmpty()) return

        apiClient.getValues(server, paths).onSuccess { values ->
            deviceManager.updateValues(values)
        }
    }

    private fun startPolling() {
        stopPolling()

        // Fast poll: switch values every 2 seconds
        valuePollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    refreshSwitchValues()
                    _switches.value = buildSwitchList()
                } catch (_: Exception) { }
            }
        }

        // Slow poll: device list every 15 seconds (new switches may appear)
        listPollingJob = viewModelScope.launch {
            while (true) {
                delay(15000)
                try {
                    val server = serverId?.let { serverRepository.getServer(it) } ?: continue
                    apiClient.listDevices(server).onSuccess { structure ->
                        val previousIds = deviceManager.getAll().map { it.id }.toSet()
                        deviceManager.initFromList(structure)
                        val currentIds = deviceManager.getAll().map { it.id }.toSet()

                        val added = currentIds - previousIds
                        if (added.isNotEmpty()) {
                            val newInfoPaths = added.flatMap { id ->
                                val device = deviceManager.get(id)
                                (device?.getInfoPaths() ?: emptyList()) +
                                    (device?.getSwitchTypePaths() ?: emptyList())
                            }
                            if (newInfoPaths.isNotEmpty()) {
                                apiClient.getValues(server, newInfoPaths).onSuccess { values ->
                                    deviceManager.updateValues(values)
                                }
                            }
                        }

                        if (added.isNotEmpty() || (previousIds - currentIds).isNotEmpty()) {
                            _switches.value = buildSwitchList()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun stopPolling() {
        valuePollingJob?.cancel()
        valuePollingJob = null
        listPollingJob?.cancel()
        listPollingJob = null
    }

    /** Toggle a switch on/off */
    fun toggleSwitch(deviceId: String, switchPath: String, currentValue: Boolean) {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return
        val fullPath = "$deviceId.$switchPath.switch"
        val newValue = !currentValue

        viewModelScope.launch {
            try {
                apiClient.setValues(server, mapOf(fullPath to newValue)).onSuccess {
                    refreshSwitchValues()
                    _switches.value = buildSwitchList()
                }
            } catch (_: Exception) {
                // Refresh to revert UI to actual state
                _switches.value = buildSwitchList()
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
        private fun humanizeId(id: String): String {
            return id.replace(Regex("[_-]"), " ")
                .replaceFirstChar { it.uppercase() }
        }
    }
}
