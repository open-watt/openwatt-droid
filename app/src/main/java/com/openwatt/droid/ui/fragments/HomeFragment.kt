package com.openwatt.droid.ui.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentHomeBinding
import com.openwatt.droid.model.energy.ManagerData
import com.openwatt.droid.model.energy.ScorecardData
import com.openwatt.droid.ui.adapters.SwitchGridAdapter
import com.openwatt.droid.ui.energy.EnergyFormatters
import com.openwatt.droid.viewmodel.HomeViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: SwitchGridAdapter
    private var serverId: String? = null

    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SwitchGridAdapter { deviceId, switchPath, currentValue ->
            viewModel.toggleSwitch(deviceId, switchPath, currentValue)
        }

        binding.switchesRecycler.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.switchesRecycler.itemAnimator = null
        binding.switchesRecycler.adapter = adapter

        binding.btnSwitchesRetry.setOnClickListener { viewModel.retry() }

        observeViewModel()

        serverId?.let { viewModel.initialize(it) }
    }

    private fun observeViewModel() {
        viewModel.switches.observe(viewLifecycleOwner) { switches ->
            adapter.submitList(switches.toList())

            if (switches.isEmpty() && viewModel.isLoading.value != true && viewModel.error.value == null) {
                binding.switchesEmpty.visibility = View.VISIBLE
                binding.switchesRecycler.visibility = View.GONE
            } else if (switches.isNotEmpty()) {
                binding.switchesEmpty.visibility = View.GONE
                binding.switchesRecycler.visibility = View.VISIBLE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.switchesLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (isLoading) {
                binding.switchesEmpty.visibility = View.GONE
                binding.switchesError.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.switchesError.visibility = View.VISIBLE
                binding.switchesErrorMessage.text = error
                binding.switchesRecycler.visibility = View.GONE
                binding.switchesEmpty.visibility = View.GONE
            } else {
                binding.switchesError.visibility = View.GONE
            }
        }

        viewModel.energyFlowState.observe(viewLifecycleOwner) { flowState ->
            if (_binding == null) return@observe
            if (flowState != null) {
                binding.energyFlowCross.visibility = View.VISIBLE
                binding.energyFlowPlaceholder.visibility = View.GONE
                binding.energyFlowCross.setState(flowState)
            }
        }

        viewModel.energyAvailable.observe(viewLifecycleOwner) { available ->
            if (_binding == null) return@observe
            if (!available) {
                binding.energyFlowCross.visibility = View.GONE
                binding.energyFlowPlaceholder.visibility = View.VISIBLE
            }
        }

        viewModel.scorecard.observe(viewLifecycleOwner) {
            if (_binding != null) renderScorecard(it)
        }

        viewModel.manager.observe(viewLifecycleOwner) {
            if (_binding != null) renderManager(it)
        }
    }

    // --- Scorecard rendering ---

    private fun renderScorecard(data: ScorecardData?) {
        val container = binding.scorecardContainer
        container.removeAllViews()
        container.addView(sectionTitle("Today's Scorecard"))

        if (data == null || !data.hasIsland) {
            container.addView(pendingText("📊  Today's energy totals will appear once the grid island publishes account data."))
            return
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }
        row.addView(scoreCell("Generated", fmtKwh(data.generatedKwh), null))
        row.addView(scoreCell("Consumed", fmtKwh(data.consumedKwh), null))
        row.addView(scoreCell("Self-use", data.selfUsePct?.let { "${it.roundToInt()}%" } ?: "--", null))

        val gridNet = data.gridNetKwh
        val gridText = if (gridNet == null) {
            "--"
        } else {
            "${if (gridNet >= 0) "↓" else "↑"} ${EnergyFormatters.formatEnergyKWh(abs(gridNet))}"
        }
        val gridColor = when {
            gridNet == null -> null
            gridNet > 0.01 -> R.color.state_error
            gridNet < -0.01 -> R.color.state_ok
            else -> null
        }
        row.addView(scoreCell("Grid", gridText, gridColor))
        container.addView(row)

        container.addView(TextView(requireContext()).apply {
            text = "🔋 charged ${fmtKwh(data.battChargedKwh)} · discharged ${fmtKwh(data.battDischargedKwh)}"
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        })
    }

    private fun scoreCell(label: String, value: String, colorRes: Int?): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFF94A3B8.toInt())
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                if (colorRes != null) setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            })
        }
    }

    // --- Manager rendering ---

    private fun renderManager(data: ManagerData?) {
        val container = binding.managerContainer
        container.removeAllViews()
        container.addView(sectionTitle("Energy Manager"))

        if (data == null || !data.hasContent) {
            container.addView(pendingText("🤖  Energy management status will appear once the planner is active."))
            return
        }

        container.addView(TextView(requireContext()).apply {
            text = "Mode: ${data.mode}"
            textSize = 13f
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        })

        if (data.chips.isNotEmpty()) {
            val chipRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in data.chips) chipRow.addView(chipView(c.label, c.value))
            container.addView(HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = matchWrap().apply { topMargin = dp(6) }
                addView(chipRow)
            })
        }

        if (data.decisions.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "No active control decisions."
                textSize = 12f
                setTextColor(0xFF94A3B8.toInt())
                layoutParams = matchWrap().apply { topMargin = dp(8) }
            })
        } else {
            for (d in data.decisions) container.addView(decisionRow(d))
        }
    }

    private fun chipView(label: String, value: String): View {
        return TextView(requireContext()).apply {
            text = "$label  $value"
            textSize = 11f
            setTextColor(0xFF475569.toInt())
            setBackgroundColor(0xFFF1F5F9.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }
    }

    private fun decisionRow(d: ManagerData.Decision): View {
        val dotColor = when (d.satisfied) {
            true -> ContextCompat.getColor(requireContext(), R.color.state_ok)
            false -> 0xFFF59E0B.toInt()
            null -> ContextCompat.getColor(requireContext(), R.color.state_idle)
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(6) }

            addView(TextView(requireContext()).apply {
                text = "●"
                textSize = 10f
                setTextColor(dotColor)
            })
            addView(TextView(requireContext()).apply {
                text = d.target
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            })
            addView(TextView(requireContext()).apply {
                text = d.reason
                textSize = 12f
                setTextColor(0xFF94A3B8.toInt())
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            })
            d.commanded?.let { cmd ->
                addView(TextView(requireContext()).apply {
                    text = cmd
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF334155.toInt())
                    setBackgroundColor(0xFFE2E8F0.toInt())
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                })
            }
        }
    }

    // --- shared view helpers ---

    private fun sectionTitle(text: String): View {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = matchWrap()
        }
    }

    private fun pendingText(text: String): View {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }
    }

    private fun fmtKwh(v: Double?): String = v?.let { EnergyFormatters.formatEnergyKWh(it) } ?: "--"

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopPolling()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
