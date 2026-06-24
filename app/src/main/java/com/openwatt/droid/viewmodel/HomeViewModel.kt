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
import com.openwatt.droid.model.energy.EnergyFlowState
import com.openwatt.droid.model.energy.EvFlowState
import com.openwatt.droid.model.energy.ManagerData
import com.openwatt.droid.model.energy.ScorecardData
import com.openwatt.droid.model.energy.graph.EnergyModel
import com.openwatt.droid.model.energy.graph.EnergyModelParser
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.buildForest
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    private val _energyFlowState = MutableLiveData<EnergyFlowState?>(null)
    val energyFlowState: LiveData<EnergyFlowState?> = _energyFlowState

    private val _energyAvailable = MutableLiveData(true)
    val energyAvailable: LiveData<Boolean> = _energyAvailable

    private val _scorecard = MutableLiveData<ScorecardData?>(null)
    val scorecard: LiveData<ScorecardData?> = _scorecard

    private val _manager = MutableLiveData<ManagerData?>(null)
    val manager: LiveData<ManagerData?> = _manager

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

                    // Fetch initial energy state
                    refreshEnergy()

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
                val icon = Archetypes.SWITCH_TYPE_ICONS[switchType] ?: "🔌"

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

        // Fast poll: switch + energy values every 2 seconds
        valuePollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    refreshSwitchValues()
                    _switches.value = buildSwitchList()
                    refreshEnergy()
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

    // --- Energy (diamond + scorecard + manager) ---

    private suspend fun refreshEnergy() {
        val server = serverId?.let { serverRepository.getServer(it) } ?: return

        val energy = apiClient.getEnergyElements(server).getOrNull()
        if (energy == null) {
            _energyAvailable.value = false
            _energyFlowState.value = null
            return
        }
        val planner = apiClient.getPlannerElements(server).getOrNull()

        val merged = HashMap<String, Any?>(energy)
        if (planner != null) merged.putAll(planner)

        val model = EnergyModelParser.parse(merged)
        val forest = buildForest(model)

        val available = model.hasTopology || model.islands.isNotEmpty()
        _energyAvailable.value = available
        _energyFlowState.value = if (available) deriveDiamond(model, forest) else null
        _scorecard.value = buildScorecard(model)
        _manager.value = buildManager(model)
    }

    /**
     * Derive the diamond flow state. Prefer the primary (grid) island account
     * (its sign conventions already match the cross view: grid + = import,
     * battery + = discharge, solar/home + = produce/consume); fall back to the
     * circuit forest. Mirrors the web home diamond.js deriveDiamondData.
     */
    private fun deriveDiamond(model: EnergyModel, forest: Forest): EnergyFlowState {
        val island = model.primaryIsland()

        var solar = numOf(island?.get("account.solar.power"))
        var battery = numOf(island?.get("account.battery.power"))
        var grid = numOf(island?.get("account.grid.power"))
        var home = numOf(island?.get("account.load.total.power"))

        if (solar == null) {
            val s = sumPorts(forest, "pv")
            solar = if (s != 0.0) abs(s) else null
        }
        if (battery == null) {
            val b = sumPorts(forest, "battery")
            battery = if (b != 0.0) -b else null
        }
        if (grid == null) grid = forest.roots.firstOrNull { it.kind == "bus" }?.power
        if (home == null) home = maxOf(0.0, (grid ?: 0.0) + (solar ?: 0.0) + (battery ?: 0.0))

        var soc = model.batterySoc()
        if (soc == null) {
            val avail = numOf(island?.get("budget.battery_available_kwh"))
            val cap = numOf(island?.get("budget.battery_capacity_kwh"))
            if (avail != null && cap != null && cap > 0) {
                soc = (avail / cap * 100).coerceIn(0.0, 100.0)
            }
        }

        return EnergyFlowState(
            gridPower = grid ?: 0.0,
            solarPower = solar,
            batteryPower = battery,
            batterySoc = soc,
            homePower = home ?: 0.0,
            evs = buildEvs(forest),
            gasPower = null,
        )
    }

    /** EV nodes for the diamond: prefer EVSE chargers, fall back to car appliances. */
    private fun buildEvs(forest: Forest): List<EvFlowState>? {
        fun nodesOfType(type: String) = forest.index.values
            .filter { it.kind == "appliance" && it.applianceType == type }
        val nodes = nodesOfType("evse").ifEmpty { nodesOfType("car") }
        return nodes
            .map { EvFlowState(name = it.name, power = it.power ?: 0.0, soc = it.batteryStore?.soc) }
            .ifEmpty { null }
    }

    /** Sum power across all appliance ports with the given role (topology fallback). */
    private fun sumPorts(forest: Forest, role: String): Double {
        var sum = 0.0
        for (node in forest.index.values) {
            if (node.kind != "appliance") continue
            for (port in node.ports ?: emptyList()) {
                if (port.portRole == role) port.meter.power?.let { sum += it }
            }
        }
        return sum
    }

    private fun buildScorecard(model: EnergyModel): ScorecardData {
        val island = model.primaryIsland() ?: return ScorecardData(hasIsland = false)

        val solar = numOf(island["account.solar.today.energy"])
        val gridIn = numOf(island["account.grid.today.import"])
        val gridOut = numOf(island["account.grid.today.export"])
        val battIn = numOf(island["account.battery.today.charge"])
        val battOut = numOf(island["account.battery.today.discharge"])

        fun n(v: Double?) = v ?: 0.0
        // Energy delivered to the home today = sources in - stored/exported out.
        val consumed = n(solar) + n(gridIn) + n(battOut) - n(gridOut) - n(battIn)
        val gridNet = n(gridIn) - n(gridOut)
        val selfUse = if (solar != null && solar > 0) {
            ((n(solar) - n(gridOut)) / n(solar) * 100).coerceIn(0.0, 100.0)
        } else null

        return ScorecardData(
            hasIsland = true,
            generatedKwh = solar,
            consumedKwh = if (consumed > 0) consumed else null,
            selfUsePct = selfUse,
            gridNetKwh = gridNet,
            battChargedKwh = battIn,
            battDischargedKwh = battOut,
        )
    }

    private fun buildManager(model: EnergyModel): ManagerData {
        val island = model.primaryIsland()
        val decisions = buildDecisions(model)
        if (island == null && decisions.isEmpty()) return ManagerData(hasContent = false)

        val mode = when (island?.mode) {
            "on_grid" -> "On-grid"
            "off_grid" -> "Off-grid"
            else -> "—"
        }

        val chips = mutableListOf<ManagerData.Chip>()
        if (island != null) {
            numOf(island["budget.pressure"])?.let { chips.add(ManagerData.Chip("Pressure", "%.2f".format(it))) }
            numOf(island["budget.reserve_kwh"])?.let { chips.add(ManagerData.Chip("Reserve", fmtKwh(it))) }
            numOf(island["budget.forecast_net_kwh"])?.let { chips.add(ManagerData.Chip("Forecast net", fmtKwh(it))) }
            numOf(island["budget.battery_available_kwh"])?.let { chips.add(ManagerData.Chip("Battery", fmtKwh(it))) }
        }

        return ManagerData(hasContent = true, mode = mode, chips = chips, decisions = decisions)
    }

    /** Join policies with their allocations (same id); unsatisfied first, then by tier. */
    private fun buildDecisions(model: EnergyModel): List<ManagerData.Decision> {
        val ids = LinkedHashSet<String>().apply {
            addAll(model.policies.keys)
            addAll(model.allocations.keys)
        }
        return ids.map { id ->
            val p = model.policies[id]
            val a = model.allocations[id]
            val commandedRaw = a?.get("commanded")
            val commanded = when (commandedRaw) {
                null -> null
                is Boolean -> if (commandedRaw) "on" else "off"
                else -> commandedRaw.toString()
            }
            ManagerData.Decision(
                target = humanize((a?.get("target") as? String) ?: (p?.get("target") as? String) ?: id),
                reason = (a?.get("reason") as? String) ?: "",
                commanded = commanded,
                satisfied = p?.get("satisfied") as? Boolean,
                tier = numOf(p?.get("tier"))?.toInt(),
            )
        }.sortedWith(
            compareBy<ManagerData.Decision> { if (it.satisfied == true) 1 else 0 }
                .thenBy { it.tier ?: 99 }
        )
    }

    private fun numOf(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun fmtKwh(v: Double): String = "%.1f kWh".format(v)

    private fun humanize(s: String): String =
        s.replace(Regex("[_-]+"), " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

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
