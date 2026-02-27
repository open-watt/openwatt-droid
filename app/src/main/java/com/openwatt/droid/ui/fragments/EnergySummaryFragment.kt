package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentEnergySummaryBinding
import com.openwatt.droid.model.energy.ChartPeriod
import com.openwatt.droid.model.energy.PeriodTotal
import com.openwatt.droid.ui.adapters.ApplianceListAdapter
import com.openwatt.droid.ui.energy.EnergyFormatters
import com.openwatt.droid.viewmodel.EnergySummaryViewModel
import kotlin.math.abs

class EnergySummaryFragment : Fragment() {
    private var _binding: FragmentEnergySummaryBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: EnergySummaryViewModel
    private lateinit var applianceAdapter: ApplianceListAdapter

    private var serverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEnergySummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[EnergySummaryViewModel::class.java]

        setupApplianceList()
        setupPeriodChips()
        setupRetryButton()
        observeViewModel()

        serverId?.let { viewModel.initialize(it) }
    }

    private fun setupApplianceList() {
        applianceAdapter = ApplianceListAdapter { id -> viewModel.selectAppliance(id) }
        binding.applianceRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = applianceAdapter
        }
    }

    private fun setupPeriodChips() {
        // Set default selection
        binding.chipRealtime.isChecked = true

        binding.periodChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val period = when {
                checkedIds.contains(R.id.chipRealtime) -> ChartPeriod.REALTIME
                checkedIds.contains(R.id.chipDay) -> ChartPeriod.DAY
                checkedIds.contains(R.id.chipWeek) -> ChartPeriod.WEEK
                checkedIds.contains(R.id.chipMonth) -> ChartPeriod.MONTH
                checkedIds.contains(R.id.chipYear) -> ChartPeriod.YEAR
                else -> ChartPeriod.REALTIME
            }
            viewModel.selectPeriod(period)
        }
    }

    private fun setupRetryButton() {
        binding.btnEnergyRetry.setOnClickListener { viewModel.retry() }
    }

    private fun observeViewModel() {
        viewModel.chartData.observe(viewLifecycleOwner) { data ->
            if (_binding == null) return@observe
            val period = viewModel.selectedPeriod.value ?: ChartPeriod.REALTIME
            if (period == ChartPeriod.REALTIME) {
                binding.powerChart.setData(
                    data.timestamps.toLongArray(),
                    data.power.toFloatArray(),
                )
            }
        }

        viewModel.selectedPeriod.observe(viewLifecycleOwner) { period ->
            if (_binding == null) return@observe
            val isRealtime = period == ChartPeriod.REALTIME
            binding.powerChart.visibility = if (isRealtime) View.VISIBLE else View.GONE
            binding.chartStub.visibility = if (isRealtime) View.GONE else View.VISIBLE
            binding.chartStubText.text =
                "${period.label} chart requires backend:\nGET /api/energy/history"
        }

        viewModel.summaries.observe(viewLifecycleOwner) { summaries ->
            if (_binding == null) return@observe
            applianceAdapter.submitList(summaries)

            val hasData = summaries.isNotEmpty()
            binding.applianceRecycler.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.energyEmpty.visibility =
                if (!hasData && viewModel.isLoading.value != true && viewModel.error.value == null)
                    View.VISIBLE else View.GONE
        }

        viewModel.selectedAppliance.observe(viewLifecycleOwner) { id ->
            applianceAdapter.selectedId = id
        }

        viewModel.periodTotals.observe(viewLifecycleOwner) { totals ->
            if (_binding == null) return@observe
            buildPeriodTotalsRow(totals)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (_binding == null) return@observe
            binding.energyLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (_binding == null) return@observe
            val hasError = error != null
            binding.energyError.visibility = if (hasError) View.VISIBLE else View.GONE
            binding.energyErrorMessage.text = error
        }

        // Update current power indicator
        viewModel.circuits.observe(viewLifecycleOwner) { circuits ->
            if (_binding == null) return@observe
            val mainCircuit = circuits.values.firstOrNull()
            val power = mainCircuit?.meterData?.powerWatts ?: 0.0
            val meterType = mainCircuit?.meterData?.inferMeterType()
                ?: com.openwatt.droid.model.energy.MeterType.SINGLE_PHASE

            val state = EnergyFormatters.getPowerState(power, meterType, mainCircuit?.type, "circuit")
            binding.currentPowerLabel.text = state.label
            binding.currentPowerValue.text = "${state.arrow} ${EnergyFormatters.formatPower(abs(power))}"

            val colorRes = when (state.stateClass) {
                "consuming" -> R.color.state_error
                "producing" -> R.color.state_ok
                else -> R.color.state_idle
            }
            binding.currentPowerValue.setTextColor(
                ContextCompat.getColor(requireContext(), colorRes)
            )
        }
    }

    private fun buildPeriodTotalsRow(totals: List<PeriodTotal>) {
        val container = binding.periodTotalsRow
        container.removeAllViews()

        val dp = resources.displayMetrics.density

        for (total in totals) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * dp).toInt()
                    marginStart = (4 * dp).toInt()
                }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                setBackgroundResource(com.google.android.material.R.drawable.m3_tabs_rounded_line_indicator)
            }

            // Label
            val labelView = TextView(requireContext()).apply {
                text = total.label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.state_idle))
            }
            card.addView(labelView)

            // Value
            val valueView = TextView(requireContext()).apply {
                text = total.value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                gravity = Gravity.CENTER
                if (total.isStub) {
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.state_idle))
                }
            }
            card.addView(valueView)

            // Cost or stub text
            total.costText?.let { costMsg ->
                val costView = TextView(requireContext()).apply {
                    text = costMsg
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                    gravity = Gravity.CENTER
                    setTextColor(0xFF856404.toInt()) // amber stub text
                    textSize = 9f
                    maxLines = 3
                }
                card.addView(costView)
            }

            container.addView(card)
        }
    }

    override fun onResume() {
        super.onResume()
        serverId?.let { viewModel.initialize(it) }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): EnergySummaryFragment {
            return EnergySummaryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
