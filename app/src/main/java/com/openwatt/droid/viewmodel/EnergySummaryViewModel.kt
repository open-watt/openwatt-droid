package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.energy.ApplianceSummary
import com.openwatt.droid.model.energy.ChartData
import com.openwatt.droid.model.energy.ChartPeriod
import com.openwatt.droid.model.energy.PeriodTotal
import com.openwatt.droid.model.energy.graph.EnergyModel
import com.openwatt.droid.model.energy.graph.EnergyModelParser
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.buildForest
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.ui.energy.EnergyFormatters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Energy Summary tab from the `circuit.*` element model. Headline
 * grid figures come from the primary island's grid account (falling back to the
 * root bus); the appliance ranking comes from the forest's appliance nodes.
 * Non-realtime chart periods remain stubbed pending a backend history API.
 */
class EnergySummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val apiClient = OpenWattClient()

    private var serverId: String? = null
    private var pollingJob: Job? = null
    private var lastModel: EnergyModel? = null

    /** Whole-home grid headline: live power + today's import/export (kWh). */
    data class Headline(val power: Double?, val importKwh: Double?, val exportKwh: Double?)

    private val _headline = MutableLiveData(Headline(null, null, null))
    val headline: LiveData<Headline> = _headline

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

    // Chart history buffer — always full-width (10 minutes at 2s intervals).
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
        publishChart()
    }

    private fun loadData() {
        val sid = serverId ?: return
        val server = serverRepository.getServer(sid) ?: return

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            apiClient.getEnergyElements(server)
                .onSuccess { raw ->
                    process(EnergyModelParser.parse(raw))
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
                delay(pollIntervalMs)
                apiClient.getEnergyElements(server).onSuccess { raw ->
                    process(EnergyModelParser.parse(raw))
                }
            }
        }
    }

    private fun process(model: EnergyModel) {
        lastModel = model
        val forest = buildForest(model)
        val head = gridHeadline(model, forest)
        _headline.value = head
        collectHistoryPoint(head.power)
        _summaries.value = buildSummaries(forest)
        updatePeriodTotals(model, head)
    }

    /**
     * Headline grid figures: prefer the primary (grid) island account; fall back
     * to the first root bus's meter when no island is published.
     */
    private fun gridHeadline(model: EnergyModel, forest: Forest): Headline {
        val island = model.primaryIsland()
        if (island != null) {
            return Headline(
                power = numOf(island["account.grid.power"]),
                importKwh = numOf(island["account.grid.today.import"]),
                exportKwh = numOf(island["account.grid.today.export"]),
            )
        }
        val rootBus = forest.roots.firstOrNull { it.kind == "bus" }
        return Headline(rootBus?.power, rootBus?.meter?.import, rootBus?.meter?.export)
    }

    private fun buildSummaries(forest: Forest): List<ApplianceSummary> {
        return forest.index.values
            .filter { it.kind == "appliance" }
            .map { node ->
                val m = node.anchorPort?.meter
                val power = node.power
                ApplianceSummary(
                    id = node.id,
                    name = node.name,
                    type = node.applianceType ?: "",
                    icon = Archetypes.resolve(node.applianceType ?: "").icon,
                    currentPower = power ?: 0.0,
                    dailyImport = m?.import ?: 0.0,
                    dailyExport = m?.export ?: 0.0,
                    isProducing = power != null && power < -10,
                )
            }
            .sortedByDescending { it.dailyImport - it.dailyExport }
    }

    private fun collectHistoryPoint(power: Double?) {
        historyTimestamps.add(System.currentTimeMillis())
        historyPower.add((power ?: 0.0).toFloat())
        if (historyTimestamps.size > maxHistoryPoints) {
            historyTimestamps.removeAt(0)
            historyPower.removeAt(0)
        }
        publishChart()
    }

    private fun publishChart() {
        _chartData.value = ChartData(
            timestamps = historyTimestamps.toList(),
            power = historyPower.toList(),
        )
    }

    private fun updatePeriodTotals(model: EnergyModel, head: Headline) {
        val period = _selectedPeriod.value ?: ChartPeriod.REALTIME
        val isRealtime = period == ChartPeriod.REALTIME
        val historyStub = "Requires backend:\nGET /api/energy/history"

        val generated = numOf(model.primaryIsland()?.get("account.solar.today.energy"))
        val imported = head.importKwh
        val exported = head.exportKwh
        val consumed = (imported ?: 0.0) + (generated ?: 0.0) - (exported ?: 0.0)

        fun cell(label: String, kwh: Double?): PeriodTotal {
            val show = isRealtime && kwh != null
            return PeriodTotal(
                label = label,
                value = if (show) EnergyFormatters.formatEnergyKWh(kwh!!) else "--",
                costText = if (!isRealtime) historyStub else null,
                isStub = !show,
            )
        }

        _periodTotals.value = listOf(
            cell("Imported", imported),
            cell("Exported", exported),
            cell("Generated", generated),
            PeriodTotal(
                label = "Consumed",
                value = if (isRealtime) EnergyFormatters.formatEnergyKWh(consumed) else "--",
                costText = if (!isRealtime) historyStub else null,
                isStub = !isRealtime,
            ),
        )
    }

    fun selectPeriod(period: ChartPeriod) {
        _selectedPeriod.value = period
        lastModel?.let { updatePeriodTotals(it, _headline.value ?: Headline(null, null, null)) }
    }

    fun selectAppliance(applianceId: String?) {
        _selectedAppliance.value = if (_selectedAppliance.value == applianceId) null else applianceId
    }

    fun retry() {
        serverId?.let {
            this.serverId = null
            initialize(it)
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun numOf(v: Any?): Double? = (v as? Number)?.toDouble()

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
