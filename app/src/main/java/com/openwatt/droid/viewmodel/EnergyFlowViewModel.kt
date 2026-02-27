package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.energy.Appliance
import com.openwatt.droid.model.energy.Circuit
import com.openwatt.droid.model.energy.DetailLevel
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EnergyFlowViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()

    private var serverId: String? = null
    private var pollingJob: Job? = null

    private val _circuits = MutableLiveData<Map<String, Circuit>>(emptyMap())
    val circuits: LiveData<Map<String, Circuit>> = _circuits

    private val _appliances = MutableLiveData<Map<String, Appliance>>(emptyMap())
    val appliances: LiveData<Map<String, Appliance>> = _appliances

    /** Selected node key: "circuit:id" or "appliance:id" */
    private val _selectedNode = MutableLiveData<String?>(null)
    val selectedNode: LiveData<String?> = _selectedNode

    /** Per-node detail levels */
    private val _detailLevels = MutableLiveData<Map<String, DetailLevel>>(emptyMap())
    val detailLevels: LiveData<Map<String, DetailLevel>> = _detailLevels

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    fun initialize(serverId: String) {
        if (this.serverId == serverId) return
        this.serverId = serverId
        loadData()
    }

    private fun loadData() {
        val sid = serverId ?: return
        val server = serverRepository.getServer(sid) ?: return

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val circuitsResult = apiClient.getCircuits(server)
                val appliancesResult = apiClient.getAppliances(server)

                circuitsResult.onSuccess { _circuits.value = it }
                    .onFailure { e ->
                        _error.value = e.message
                        _isLoading.value = false
                        return@launch
                    }

                appliancesResult.onSuccess { _appliances.value = it }
                    .onFailure { e ->
                        _error.value = e.message
                        _isLoading.value = false
                        return@launch
                    }

                _isLoading.value = false
                startPolling()
            } catch (e: Exception) {
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        val sid = serverId ?: return
        val server = serverRepository.getServer(sid) ?: return

        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                try {
                    apiClient.getCircuits(server).onSuccess { _circuits.value = it }
                    apiClient.getAppliances(server).onSuccess { _appliances.value = it }
                } catch (_: Exception) {
                    // Silent failure during polling
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun selectNode(nodeKey: String?) {
        _selectedNode.value = if (_selectedNode.value == nodeKey) null else nodeKey
    }

    fun getDetailLevel(nodeKey: String): DetailLevel {
        return _detailLevels.value?.get(nodeKey) ?: DetailLevel.SIMPLE
    }

    fun cycleDetailLevel(nodeKey: String) {
        val current = getDetailLevel(nodeKey)
        val next = current.next()
        val levels = (_detailLevels.value ?: emptyMap()).toMutableMap()
        levels[nodeKey] = next
        _detailLevels.value = levels
    }

    fun decreaseDetailLevel(nodeKey: String) {
        val current = getDetailLevel(nodeKey)
        val prev = current.previous()
        val levels = (_detailLevels.value ?: emptyMap()).toMutableMap()
        levels[nodeKey] = prev
        _detailLevels.value = levels
    }

    fun resetDetailLevel(nodeKey: String) {
        val levels = (_detailLevels.value ?: emptyMap()).toMutableMap()
        levels[nodeKey] = DetailLevel.SIMPLE
        _detailLevels.value = levels
    }

    fun retry() {
        serverId?.let {
            this.serverId = null
            initialize(it)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
