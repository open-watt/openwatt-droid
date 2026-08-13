package com.openwatt.droid.model.energy

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.openwatt.droid.model.energy.graph.EnergyModel
import com.openwatt.droid.model.energy.graph.EnergyModelParser
import com.openwatt.droid.network.ModelMirror
import com.openwatt.droid.network.SyncClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live energy model, shared by the Home, Energy Flow and Energy Summary
 * screens. Kotlin port of the web client's `js/models/energy-source.js`.
 *
 * The synthetic `energy` device publishes the whole presentation contract —
 * circuit, boundaries, appliances, topology, islands and the planner's
 * policy/allocation records — as ordinary elements, so one model-plane
 * subscription replaces every /api/get section. The mirror refcounts the
 * pattern, and this refcounts the parse on top of it.
 */
class EnergySource(private val sync: SyncClient) : ModelMirror.Listener {

    enum class Status { LOADING, READY, UNSUPPORTED }

    private val _model = MutableLiveData<EnergyModel?>(null)
    val model: LiveData<EnergyModel?> = _model

    private val _status = MutableLiveData(Status.LOADING)
    val status: LiveData<Status> = _status

    private val lock = Any()
    private val holders = mutableSetOf<Any>()
    private var sub: ModelMirror.Subscription? = null
    private var refreshedAt = 0L
    private var pendingRefresh: Job? = null

    /** Returns a release function; safe to call more than once. */
    fun acquire(): () -> Unit {
        val token = Any()
        synchronized(lock) {
            if (holders.isEmpty()) {
                sub = sync.model.subscribe(ENERGY_PATTERN)
                sync.model.addListener(this)
                checkSupport()
                refresh()
            }
            holders.add(token)
        }
        return { release(token) }
    }

    private fun release(token: Any) {
        synchronized(lock) {
            if (!holders.remove(token)) return
            if (holders.isNotEmpty()) return

            sync.model.removeListener(this)
            pendingRefresh?.cancel()
            pendingRefresh = null
            sub?.release()
            sub = null
            _model.postValue(null)
            _status.postValue(Status.LOADING)
        }
    }

    private fun checkSupport() {
        if (sync.model.hello != null && !sync.model.available) {
            _status.postValue(Status.UNSUPPORTED)
        }
    }

    override fun onHello(info: ModelMirror.HelloInfo) {
        checkSupport()
    }

    /**
     * The model is a whole-graph derivation — every value feeds the accounts,
     * so there is no incremental parse. Rebuilding it (plus the forest and
     * views downstream) costs far more than a tick's worth of frames is
     * worth, so it is rate-limited rather than run on every feed flush.
     */
    override fun onChange(dirty: Set<ModelMirror.Node>) {
        if (dirty.isNotEmpty() && !touchesEnergy(dirty)) return
        synchronized(lock) {
            if (holders.isEmpty()) return
            val wait = REFRESH_INTERVAL_MS - (System.currentTimeMillis() - refreshedAt)
            if (wait <= 0) {
                refresh()
                return
            }
            if (pendingRefresh?.isActive != true) {
                pendingRefresh = sync.ioScope.launch {
                    delay(wait)
                    synchronized(lock) {
                        pendingRefresh = null
                        if (holders.isNotEmpty()) refresh()
                    }
                }
            }
        }
    }

    private fun touchesEnergy(dirty: Set<ModelMirror.Node>): Boolean =
        dirty.any { it.ns == "device" && it.path.startsWith("energy.") }

    private fun refresh() {
        refreshedAt = System.currentTimeMillis()
        val snapshot = sync.model.snapshot(ENERGY_PATTERN)
        // An empty mirror is "not served yet", not an empty site; holding the
        // last good model keeps views from blanking across a reconnect.
        if (snapshot.isEmpty()) return

        // Reshape to the /api/get entry shape the parser was written against.
        val raw = snapshot.mapValues { (_, entry) ->
            mapOf("value" to entry.value, "unit" to entry.unit, "age" to entry.ageSeconds)
        }
        _model.postValue(EnergyModelParser.parse(raw))
        _status.postValue(Status.READY)
    }

    companion object {
        const val ENERGY_PATTERN = "device:energy.**"

        /**
         * Floor between whole-model rebuilds. The feed flushes far faster
         * than the views can usefully redraw.
         */
        const val REFRESH_INTERVAL_MS = 500L
    }
}
