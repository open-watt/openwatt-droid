package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Archetypes
import com.openwatt.droid.model.DeviceManager
import com.openwatt.droid.model.HomeSwitch
import com.openwatt.droid.model.ValueFormatter
import com.openwatt.droid.model.energy.EnergyFlowState
import com.openwatt.droid.model.energy.EnergySource
import com.openwatt.droid.model.energy.EvFlowState
import com.openwatt.droid.model.energy.ManagerData
import com.openwatt.droid.model.energy.ScorecardData
import com.openwatt.droid.model.energy.graph.EnergyModel
import com.openwatt.droid.model.energy.graph.Forest
import com.openwatt.droid.model.energy.graph.buildForest
import com.openwatt.droid.network.ModelMirror
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives the Home screen: quick-control switches plus the energy diamond,
 * scorecard and manager panels.
 *
 * Device *structure* still comes from `POST /api/list` on a slow refresh (the
 * model plane's `add` frames carry no component templates, and removal is
 * only visible through a re-list). Switch *values* ride live model-plane
 * subscriptions — one `device:<id>.<path>.**` pattern per switch — and the
 * energy panels read the shared [EnergySource]. Writes go through the model
 * plane's `set`; the feed echoes the applied value back.
 */
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
    private var syncClient: SyncClient? = null
    private var listPollingJob: Job? = null
    private var attached = false

    private var energy: EnergySource? = null
    private var releaseEnergy: (() -> Unit)? = null

    /** Live switch feeds, keyed by pattern. */
    private val switchSubs = mutableMapOf<String, ModelMirror.Subscription>()

    /** Coalesced UI refresh after live switch updates. */
    private var switchRefreshJob: Job? = null

    private val mirrorListener = object : ModelMirror.Listener {
        override fun onChange(dirty: Set<ModelMirror.Node>) {
            // Fold moved element values into the device model, then rebuild
            // the switch list once per coalescing window.
            val entries = dirty
                .filter { it.ns == "device" && !it.isDevice }
                .associate { it.path to mapOf("value" to it.value, "unit" to it.unit) }
            if (entries.isEmpty()) return
            deviceManager.updateValues(entries)
            scheduleSwitchRefresh()
        }
    }

    private val energyModelObserver = Observer<EnergyModel?> { model ->
        if (model == null) return@Observer
        val forest = buildForest(model)
        val available = model.hasTopology || model.islands.isNotEmpty()
        _energyAvailable.value = available
        _energyFlowState.value = if (available) deriveDiamond(model, forest) else null
        _scorecard.value = buildScorecard(model)
        _manager.value = buildManager(model)
    }

    private val energyStatusObserver = Observer<EnergySource.Status> { status ->
        if (status == EnergySource.Status.UNSUPPORTED) {
            _energyAvailable.value = false
            _energyFlowState.value = null
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
        val source = client.energy
        energy = source
        releaseEnergy = source.acquire()
        source.model.observeForever(energyModelObserver)
        source.status.observeForever(energyStatusObserver)

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

                    // Static identity (info + switch types), fetched once.
                    fetchStaticInfo(deviceManager.getInfoPaths())

                    reconcileSwitchSubscriptions()
                    _switches.value = buildSwitchList()
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
     * One-shot read of static identity elements via the mirror; the burst
     * lands there, so fold the values into the device model from it.
     */
    private suspend fun fetchStaticInfo(paths: List<String>) {
        val client = syncClient ?: return
        if (paths.isEmpty()) return
        client.model.fetch(paths.map { "device:$it" })
        val entries = mutableMapOf<String, Map<String, Any?>>()
        for (path in paths) {
            val node = client.model.getNode(path) ?: continue
            entries[path] = mapOf("value" to node.value, "unit" to node.unit)
        }
        if (entries.isNotEmpty()) deviceManager.updateValues(entries)
    }

    /**
     * One live pattern per switch subtree — covers the switch state, its type
     * and its meter elements. The mirror batches sub/unsub frames, so a whole
     * screenful arms in one frame each way.
     */
    private fun reconcileSwitchSubscriptions() {
        val client = syncClient ?: return
        if (!attached) return
        val wanted = deviceManager.getAll().flatMap { device ->
            device.getSwitchComponents().map { sw -> "device:${device.id}.${sw.path}.**" }
        }.toSet()

        for (pattern in switchSubs.keys.toList()) {
            if (pattern !in wanted) switchSubs.remove(pattern)?.release()
        }
        for (pattern in wanted) {
            if (pattern !in switchSubs) switchSubs[pattern] = client.model.subscribe(pattern)
        }
    }

    private fun scheduleSwitchRefresh() {
        if (switchRefreshJob?.isActive == true) return
        switchRefreshJob = viewModelScope.launch {
            delay(SWITCH_REFRESH_MS)
            _switches.value = buildSwitchList()
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

    /**
     * Slow structure refresh: the model plane has no `gone` verb and its
     * `add` frames carry no component templates, so new/removed switches are
     * only visible through a re-list.
     */
    private fun startListPolling() {
        listPollingJob?.cancel()
        listPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(LIST_REFRESH_MS)
                try {
                    val server = serverId?.let { serverRepository.getServer(it) } ?: continue
                    apiClient.listDevices(server).onSuccess { structure ->
                        val previousIds = deviceManager.getAll().map { it.id }.toSet()
                        // Any structural change matters — not just add/remove.
                        // A zigbee device is listed as soon as it joins, and
                        // grows its Switch gangs when the interview finishes.
                        val changed = deviceManager.initFromList(structure)
                        val currentIds = deviceManager.getAll().map { it.id }.toSet()

                        val added = currentIds - previousIds
                        if (changed) {
                            val newInfoPaths = (if (added.isNotEmpty()) added else currentIds)
                                .flatMap { id ->
                                    val device = deviceManager.get(id)
                                    (device?.getInfoPaths() ?: emptyList()) +
                                        (device?.getSwitchTypePaths() ?: emptyList())
                                }
                            fetchStaticInfo(newInfoPaths)
                            reconcileSwitchSubscriptions()
                            _switches.value = buildSwitchList()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    /** Detach from the live feeds while the screen is off-stage. */
    fun stopPolling() {
        attached = false
        listPollingJob?.cancel()
        listPollingJob = null
        switchRefreshJob?.cancel()
        switchRefreshJob = null
        switchSubs.values.forEach { it.release() }
        switchSubs.clear()
        syncClient?.model?.removeListener(mirrorListener)
        energy?.model?.removeObserver(energyModelObserver)
        energy?.status?.removeObserver(energyStatusObserver)
        releaseEnergy?.invoke()
        releaseEnergy = null
        energy = null
        syncClient = null
    }

    /** Toggle a switch on/off via the model plane's element write. */
    fun toggleSwitch(deviceId: String, switchPath: String, currentValue: Boolean) {
        val client = syncClient ?: return
        val fullPath = "$deviceId.$switchPath.switch"
        val newValue = !currentValue

        viewModelScope.launch {
            client.model.setValue(fullPath, newValue).onSuccess { applied ->
                // `res` carries the applied value; the feed echoes it too, but
                // fold it in now so the toggle doesn't visibly lag.
                deviceManager.updateValues(mapOf(fullPath to mapOf("value" to applied)))
                _switches.value = buildSwitchList()
            }.onFailure {
                // Revert UI to actual state
                _switches.value = buildSwitchList()
            }
        }
    }

    fun retry() {
        loadDevices()
    }

    // --- Energy (diamond + scorecard + manager) ---

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
        /** Slow structure re-list cadence (matches the web client). */
        private const val LIST_REFRESH_MS = 15_000L

        /** Coalescing window between live-update switch list rebuilds. */
        private const val SWITCH_REFRESH_MS = 500L

        private fun humanizeId(id: String): String {
            return id.replace(Regex("[_-]"), " ")
                .replaceFirstChar { it.uppercase() }
        }
    }
}
