package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.openwatt.droid.model.energy.DetailLevel
import com.openwatt.droid.model.energy.EnergySource
import com.openwatt.droid.model.energy.graph.EnergyModel
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.ForestNode
import com.openwatt.droid.model.energy.graph.buildForest
import com.openwatt.droid.repository.ServerRepository

/**
 * Drives the Energy Flow tab. Reads the shared [EnergySource] — the live
 * `device:energy.**` model feed over /sync — and reshapes each parsed
 * EnergyModel into a render [Forest] for the tree view.
 */
class EnergyFlowViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)

    private var serverId: String? = null
    private var energy: EnergySource? = null
    private var releaseEnergy: (() -> Unit)? = null

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

    private val modelObserver = Observer<EnergyModel?> { model ->
        if (model == null) return@Observer
        _forest.value = buildForest(model)
        _isLoading.value = false
    }

    private val statusObserver = Observer<EnergySource.Status> { status ->
        when (status) {
            EnergySource.Status.UNSUPPORTED -> {
                _error.value = "Backend does not serve the live energy model"
                _isLoading.value = false
            }
            EnergySource.Status.LOADING -> _isLoading.value = _forest.value?.roots?.isEmpty() != false
            EnergySource.Status.READY -> _error.value = null
        }
    }

    /** Attach to the live feed. Idempotent; re-attaches after [stopPolling]. */
    fun initialize(serverId: String) {
        this.serverId = serverId
        if (releaseEnergy != null) return
        val server = serverRepository.getServer(serverId) ?: return

        _isLoading.value = _forest.value?.roots?.isEmpty() != false
        _error.value = null

        val source = com.openwatt.droid.network.SyncClientManager.get(server).energy
        energy = source
        releaseEnergy = source.acquire()
        source.model.observeForever(modelObserver)
        source.status.observeForever(statusObserver)
    }

    fun getNode(nodeKey: String): ForestNode? = _forest.value?.index?.get(nodeKey)

    /** Detach from the live feed while the screen is off-stage. */
    fun stopPolling() {
        energy?.model?.removeObserver(modelObserver)
        energy?.status?.removeObserver(statusObserver)
        releaseEnergy?.invoke()
        releaseEnergy = null
        energy = null
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
        stopPolling()
        serverId?.let { initialize(it) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
