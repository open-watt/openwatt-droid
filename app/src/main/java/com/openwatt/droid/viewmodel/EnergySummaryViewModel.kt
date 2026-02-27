package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.energy.Appliance
import com.openwatt.droid.model.energy.ApplianceSummary
import com.openwatt.droid.model.energy.ChartData
import com.openwatt.droid.model.energy.ChartPeriod
import com.openwatt.droid.model.energy.Circuit
import com.openwatt.droid.model.energy.PeriodTotal
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.ui.energy.EnergyFormatters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EnergySummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()

    private var serverId: String? = null
    private var pollingJob: Job? = null

    // Data state
    private val _circuits = MutableLiveData<Map<String, Circuit>>(emptyMap())
    val circuits: LiveData<Map<String, Circuit>> = _circuits

    private val _appliances = MutableLiveData<Map<String, Appliance>>(emptyMap())
    val appliances: LiveData<Map<String, Appliance>> = _appliances

    private val _summaries = MutableLiveData<List<ApplianceSummary>>(emptyList())
    val summaries: LiveData<List<ApplianceSummary>> = _summaries

    private val _chartData = MutableLiveData(ChartData(emptyList(), emptyList()))
    val chartData: LiveData<ChartData> = _chartData

    private val _selectedPeriod = MutableLiveData(ChartPeriod.REALTIME)
    val selectedPeriod: LiveData<ChartPeriod> = _selectedPeriod

    private val _periodTotals = MutableLiveData<List<PeriodTotal>>(emptyList())
    val periodTotals: LiveData<List<PeriodTotal>> = _periodTotals

    private val _selectedAppliance = MutableLiveData<String?>(null)
    val selectedAppliance: LiveData<String?> = _selectedAppliance

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    // Chart history buffer — always full-width (10 minutes at 2s intervals)
    private val maxHistoryPoints = 300
    private val pollIntervalMs = 2000L
    private val historyTimestamps = mutableListOf<Long>()
    private val historyPower = mutableListOf<Float>()

    fun initialize(serverId: String) {
        if (this.serverId == serverId) return
        this.serverId = serverId
        prefillHistory()
        loadData()
    }

    /** Pre-fill the buffer with zeroes so the chart is always full-width. */
    private fun prefillHistory() {
        historyTimestamps.clear()
        historyPower.clear()
        val now = System.currentTimeMillis()
        for (i in 0 until maxHistoryPoints) {
            historyTimestamps.add(now - (maxHistoryPoints - 1 - i) * pollIntervalMs)
            historyPower.add(0f)
        }
        _chartData.value = ChartData(
            timestamps = historyTimestamps.toList(),
            power = historyPower.toList(),
        )
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

                circuitsResult.onSuccess { c ->
                    _circuits.value = c
                }.onFailure { e ->
                    _error.value = e.message
                    _isLoading.value = false
                    return@launch
                }

                appliancesResult.onSuccess { a ->
                    _appliances.value = a
                }.onFailure { e ->
                    _error.value = e.message
                    _isLoading.value = false
                    return@launch
                }

                collectHistoryPoint()
                buildSummaries()
                updatePeriodTotals()
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
                    val circuitsResult = apiClient.getCircuits(server)
                    val appliancesResult = apiClient.getAppliances(server)

                    circuitsResult.onSuccess { _circuits.value = it }
                    appliancesResult.onSuccess { _appliances.value = it }

                    collectHistoryPoint()
                    buildSummaries()
                    updatePeriodTotals()
                } catch (_: Exception) {
                    // Silent failure during polling — don't overwrite existing data
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun selectPeriod(period: ChartPeriod) {
        _selectedPeriod.value = period
        updatePeriodTotals()
    }

    fun selectAppliance(applianceId: String?) {
        _selectedAppliance.value = if (_selectedAppliance.value == applianceId) null else applianceId
    }

    fun retry() {
        serverId?.let {
            this.serverId = null // Force re-init
            initialize(it)
        }
    }

    private fun collectHistoryPoint() {
        val circuits = _circuits.value ?: return
        val mainCircuit = circuits.values.firstOrNull() ?: return
        val power = mainCircuit.meterData?.powerWatts ?: 0.0

        // Append new point, drop oldest to keep fixed width
        historyTimestamps.add(System.currentTimeMillis())
        historyPower.add(power.toFloat())
        if (historyTimestamps.size > maxHistoryPoints) {
            historyTimestamps.removeAt(0)
            historyPower.removeAt(0)
        }

        _chartData.value = ChartData(
            timestamps = historyTimestamps.toList(),
            power = historyPower.toList(),
        )
    }

    private fun buildSummaries() {
        val appliances = _appliances.value ?: return

        val list = appliances.entries
            .filter { it.value.type != "car" } // Cars are sub-items of EVSEs
            .map { (id, app) ->
                val power = app.meterData?.powerWatts ?: 0.0
                val dailyImport = app.meterData?.import?.scalar ?: 0.0
                val dailyExport = app.meterData?.export?.scalar ?: 0.0

                ApplianceSummary(
                    id = id,
                    name = app.name ?: id,
                    type = app.type,
                    icon = Archetypes.resolve(app.type).icon,
                    currentPower = power,
                    dailyImport = dailyImport,
                    dailyExport = dailyExport,
                    isProducing = power < -10,
                )
            }
            .sortedByDescending { it.dailyImport - it.dailyExport } // Rank by net consumption

        _summaries.value = list
    }

    private fun updatePeriodTotals() {
        val period = _selectedPeriod.value ?: ChartPeriod.REALTIME
        val circuits = _circuits.value ?: emptyMap()
        val mainCircuit = circuits.values.firstOrNull()
        val mainMeter = mainCircuit?.meterData

        val isRealtime = period == ChartPeriod.REALTIME
        val periodLabel = if (isRealtime) "Session" else period.label

        // For non-realtime periods, we'd need the history API
        val stubMessage = if (!isRealtime) "Requires backend:\nGET /api/energy/history" else null
        val costStub = "Requires backend:\nGET /api/energy/tariff"

        val totalImport = mainMeter?.import?.scalar ?: 0.0
        val totalExport = mainMeter?.export?.scalar ?: 0.0

        // Estimate consumed and generated from appliances
        val appliances = _appliances.value ?: emptyMap()
        val totalGenerated = appliances.values
            .filter { (it.meterData?.powerWatts ?: 0.0) < -10 || it.type == "inverter" }
            .sumOf { it.meterData?.export?.scalar ?: 0.0 }

        val totals = listOf(
            PeriodTotal(
                label = "Imported",
                value = if (isRealtime && totalImport > 0) EnergyFormatters.formatEnergy(totalImport) else "--",
                costText = costStub,
                isStub = !isRealtime || totalImport == 0.0,
            ),
            PeriodTotal(
                label = "Exported",
                value = if (isRealtime && totalExport > 0) EnergyFormatters.formatEnergy(totalExport) else "--",
                costText = costStub,
                isStub = !isRealtime || totalExport == 0.0,
            ),
            PeriodTotal(
                label = "Generated",
                value = if (isRealtime && totalGenerated > 0) EnergyFormatters.formatEnergy(totalGenerated) else "--",
                costText = if (!isRealtime) stubMessage else null,
                isStub = !isRealtime || totalGenerated == 0.0,
            ),
            PeriodTotal(
                label = "Consumed",
                value = if (isRealtime && totalImport > 0) {
                    EnergyFormatters.formatEnergy(totalImport + totalGenerated - totalExport)
                } else "--",
                costText = if (!isRealtime) stubMessage else costStub,
                isStub = !isRealtime,
            ),
        )

        _periodTotals.value = totals
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
