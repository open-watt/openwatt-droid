package com.openwatt.droid.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.openwatt.droid.databinding.FragmentDevicesBinding
import com.openwatt.droid.ui.adapters.DeviceListAdapter
import com.openwatt.droid.viewmodel.DevicesViewModel

class DevicesFragment : Fragment() {
    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DevicesViewModel by viewModels()
    private lateinit var adapter: DeviceListAdapter
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
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceListAdapter(
            onToggleExpand = { deviceId -> viewModel.toggleExpanded(deviceId) },
            onToggleSwitch = { deviceId, switchPath, currentValue ->
                viewModel.toggleSwitch(deviceId, switchPath, currentValue)
            },
            onEditValue = { deviceId, elementPath, elementName, currentValue, unit ->
                showEditValueDialog(deviceId, elementPath, elementName, currentValue, unit)
            },
        )

        binding.devicesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesRecycler.itemAnimator = null
        binding.devicesRecycler.adapter = adapter

        binding.btnRetry.setOnClickListener {
            viewModel.retry()
        }

        observeViewModel()

        serverId?.let { viewModel.initialize(it) }
    }

    private fun observeViewModel() {
        viewModel.devices.observe(viewLifecycleOwner) { devices ->
            adapter.submitList(devices.toList())

            if (devices.isEmpty() && viewModel.isLoading.value != true && viewModel.error.value == null) {
                binding.emptyState.visibility = View.VISIBLE
                binding.devicesRecycler.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.devicesRecycler.visibility = View.VISIBLE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (isLoading) {
                binding.emptyState.visibility = View.GONE
                binding.errorState.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorState.visibility = View.VISIBLE
                binding.errorMessage.text = error
                binding.devicesRecycler.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
            } else {
                binding.errorState.visibility = View.GONE
            }
        }
    }

    private fun showEditValueDialog(
        deviceId: String,
        elementPath: String,
        elementName: String,
        currentValue: Any?,
        unit: String?,
    ) {
        val context = requireContext()
        val dp = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }

        // Show current value
        val currentLabel = TextView(context).apply {
            text = "Current: ${currentValue ?: "--"}${if (unit != null) " $unit" else ""}"
            textSize = 13f
        }
        layout.addView(currentLabel)

        val input = EditText(context).apply {
            hint = "New value"
            setText(currentValue?.toString() ?: "")
            selectAll()
        }
        layout.addView(input)

        AlertDialog.Builder(context)
            .setTitle(elementName)
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                val text = input.text.toString()
                val parsed = parseInputValue(text)
                viewModel.setValue(deviceId, elementPath, parsed)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Parse user input into appropriate type: boolean, number, or string */
    private fun parseInputValue(text: String): Any {
        return when (text.lowercase()) {
            "true", "on" -> true
            "false", "off" -> false
            else -> text.toLongOrNull() ?: text.toDoubleOrNull() ?: text
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopPolling()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): DevicesFragment {
            return DevicesFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
