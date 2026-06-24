package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.energy.DetailLevel
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.ForestNode
import com.openwatt.droid.model.energy.graph.EnergyModelParser
import com.openwatt.droid.model.energy.graph.buildForest
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Energy Flow tab. Polls the `energy.circuit.*`/`islands.*` element
 * model via [OpenWattClient.getEnergyElements], parses it into an EnergyModel,
 * and reshapes it into a render [Forest] for the tree view.
 */
class EnergyFlowViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()

    private var serverId: String? = null
    private var pollingJob: Job? = null

    private val _forest = MutableLiveData(Forest(emptyList(), emptyMap()))
    val forest: LiveData<Forest> = _forest

    /** Selected node key — a forest node.key like "bus:main" / "appliance:goodwe". */
    private val _selectedNode = MutableLiveData<String?>(null)
    val selectedNode: LiveData<String?> = _selectedNode

    /** Per-node detail levels. */
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

    fun getNode(nodeKey: String): ForestNode? = _forest.value?.index?.get(nodeKey)

    private fun loadData() {
        val sid = serverId ?: return
        val server = serverRepository.getServer(sid) ?: return

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            apiClient.getEnergyElements(server)
                .onSuccess { raw ->
                    _forest.value = buildForest(EnergyModelParser.parse(raw))
                    _isLoading.value = false
                    startPolling()
                }
                .onFailure { e ->
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
                apiClient.getEnergyElements(server).onSuccess { raw ->
                    _forest.value = buildForest(EnergyModelParser.parse(raw))
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

    fun getDetailLevel(nodeKey: String): DetailLevel =
        _detailLevels.value?.get(nodeKey) ?: DetailLevel.SIMPLE

    fun cycleDetailLevel(nodeKey: String) = setDetailLevel(nodeKey, getDetailLevel(nodeKey).next())

    fun decreaseDetailLevel(nodeKey: String) = setDetailLevel(nodeKey, getDetailLevel(nodeKey).previous())

    fun resetDetailLevel(nodeKey: String) = setDetailLevel(nodeKey, DetailLevel.SIMPLE)

    private fun setDetailLevel(nodeKey: String, level: DetailLevel) {
        val levels = (_detailLevels.value ?: emptyMap()).toMutableMap()
        levels[nodeKey] = level
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
