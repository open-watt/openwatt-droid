package com.openwatt.droid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.openwatt.droid.databinding.DialogElementHistoryBinding
import com.openwatt.droid.model.ValueFormatter
import com.openwatt.droid.network.ModelMirror
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Bottom sheet showing an element's recent history: a one-shot window read
 * seeding the chart, with the live model feed appending from there.
 *
 * Mirrors the web client's element-history flow: the live feed is armed
 * *before* the recorded window is requested, so anything recorded between
 * the two is delivered live rather than lost — the overlap dedupes on
 * timestamp in [ModelMirror.mergeSamples]. History failing (or empty — the
 * norm for constant elements) is not an error: the chart follows the live
 * feed alone with a note.
 */
class ElementHistorySheet : BottomSheetDialogFragment() {

    private var _binding: DialogElementHistoryBinding? = null
    private val binding get() = _binding!!

    private var client: SyncClient? = null
    private var sub: ModelMirror.Subscription? = null

    private val samples = mutableListOf<ModelMirror.Sample>()
    private var rangeMs = DEFAULT_RANGE_MS
    private var recorded = false
    private var historyJob: Job? = null
    private var repaintJob: Job? = null

    private lateinit var path: String
    private var unitHint: String = ""

    private val mirrorListener = object : ModelMirror.Listener {
        override fun onVal(node: ModelMirror.Node, incoming: List<ModelMirror.Sample>, lost: Long) {
            if (node.path != path) return
            synchronized(samples) {
                ModelMirror.mergeSamples(samples, incoming, since = windowStart())
            }
            // Repaint rides the 1s ticker; live samples just accumulate.
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogElementHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        path = requireArguments().getString(ARG_PATH)!!
        unitHint = requireArguments().getString(ARG_UNIT) ?: ""
        binding.historyTitle.text = requireArguments().getString(ARG_NAME) ?: path
        binding.historyCurrent.text = ""

        val server = requireArguments().getString(ARG_SERVER_ID)
            ?.let { ServerRepository(requireContext()).getServer(it) }
        if (server == null) {
            binding.historyChart.setMessage("Server not found")
            return
        }
        val syncClient = SyncClientManager.get(server)
        client = syncClient

        binding.rangeGroup.check(binding.range24h.id)
        binding.rangeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newRange = when (checkedId) {
                binding.range1h.id -> HOUR_MS
                binding.range6h.id -> 6 * HOUR_MS
                binding.range24h.id -> 24 * HOUR_MS
                binding.range7d.id -> 7 * 24 * HOUR_MS
                else -> return@addOnButtonCheckedListener
            }
            if (newRange != rangeMs) {
                val widening = newRange > rangeMs
                rangeMs = newRange
                // A wider window needs recorded data the first request's
                // `from` didn't cover.
                if (widening) requestHistory()
                repaint()
            }
        }

        // Arm the feed before asking for the window, so anything recorded
        // between the two is delivered rather than lost.
        sub = syncClient.model.subscribe("device:$path")
        syncClient.model.addListener(mirrorListener)
        syncClient.model.getNode(path)?.let { node ->
            // Fold the mirror's current value in so the trace starts painting
            // before either burst lands.
            val time = node.time
            if (time > 0) {
                synchronized(samples) {
                    ModelMirror.mergeSamples(samples, listOf(ModelMirror.Sample(time, node.value)))
                }
            }
        }
        requestHistory()

        // The window rolls with wall time; repaint on a fixed cadence.
        repaintJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                repaint()
                delay(REPAINT_INTERVAL_MS)
            }
        }
    }

    private fun windowStart(): Long = System.currentTimeMillis() - rangeMs

    private fun requestHistory() {
        val syncClient = client ?: return
        historyJob?.cancel()
        historyJob = viewLifecycleOwner.lifecycleScope.launch {
            syncClient.history(path, from = windowStart(), maxPoints = MAX_POINTS)
                .onSuccess { recordedSamples ->
                    synchronized(samples) {
                        ModelMirror.mergeSamples(samples, recordedSamples, since = windowStart())
                    }
                    recorded = recordedSamples.isNotEmpty()
                    updateNote()
                    repaint()
                }
                .onFailure {
                    // Not recorded is not a failure: the live feed still
                    // plots from here on.
                    recorded = false
                    updateNote()
                }
        }
    }

    private fun updateNote() {
        if (_binding == null) return
        binding.historyNote.visibility = if (recorded) View.GONE else View.VISIBLE
        binding.historyNote.text =
            "No history in this window — showing live samples only"
    }

    private fun repaint() {
        val b = _binding ?: return
        val from = windowStart()
        val now = System.currentTimeMillis()

        val ts: LongArray
        val vs: FloatArray
        var latest: ModelMirror.Sample? = null
        synchronized(samples) {
            val numeric = samples.filter { it.time >= from && numericOf(it.value) != null }
            ts = LongArray(numeric.size) { numeric[it].time }
            vs = FloatArray(numeric.size) { numericOf(numeric[it].value)!! }
            latest = samples.lastOrNull()
        }

        val unit = client?.model?.getUnit(path)?.ifEmpty { unitHint } ?: unitHint
        // Break the trace across gaps the recorder clearly didn't cover.
        b.historyChart.gapBreakMs = maxOf(rangeMs / 20, 5 * 60_000L)
        b.historyChart.setData(ts, vs, from, now, unit)

        latest?.let {
            b.historyCurrent.text = "Now: ${ValueFormatter.format(it.value, "default", unit)}"
        }
    }

    private fun numericOf(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is Boolean -> if (value) 1f else 0f
        else -> null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repaintJob?.cancel()
        historyJob?.cancel()
        client?.model?.removeListener(mirrorListener)
        sub?.release()
        sub = null
        client = null
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"
        private const val ARG_PATH = "path"
        private const val ARG_NAME = "name"
        private const val ARG_UNIT = "unit"

        private const val HOUR_MS = 3600_000L
        private const val DEFAULT_RANGE_MS = 24 * HOUR_MS
        private const val MAX_POINTS = 500
        private const val REPAINT_INTERVAL_MS = 1000L

        /** [path] is the full dotted element path including the device id. */
        fun newInstance(serverId: String, path: String, name: String, unit: String?): ElementHistorySheet {
            return ElementHistorySheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_PATH, path)
                    putString(ARG_NAME, name)
                    unit?.let { putString(ARG_UNIT, it) }
                }
            }
        }
    }
}
