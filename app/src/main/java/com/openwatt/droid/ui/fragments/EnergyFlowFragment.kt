package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.openwatt.droid.databinding.FragmentEnergyFlowBinding
import com.openwatt.droid.model.energy.Circuit
import com.openwatt.droid.ui.energy.CircuitTreeBuilder
import com.openwatt.droid.ui.energy.MeterDetailView
import com.openwatt.droid.viewmodel.EnergyFlowViewModel

class EnergyFlowFragment : Fragment() {
    private var _binding: FragmentEnergyFlowBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: EnergyFlowViewModel
    private lateinit var treeBuilder: CircuitTreeBuilder
    private lateinit var detailView: MeterDetailView
    private lateinit var bottomSheet: BottomSheetBehavior<View>

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
        _binding = FragmentEnergyFlowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[EnergyFlowViewModel::class.java]

        treeBuilder = CircuitTreeBuilder(requireContext()) { nodeKey ->
            viewModel.selectNode(nodeKey)
        }

        detailView = MeterDetailView(
            context = requireContext(),
            onDetailMore = {
                viewModel.selectedNode.value?.let { viewModel.cycleDetailLevel(it) }
            },
            onDetailLess = {
                viewModel.selectedNode.value?.let { viewModel.decreaseDetailLevel(it) }
            },
            onDetailBack = {
                viewModel.selectedNode.value?.let { viewModel.resetDetailLevel(it) }
            },
        )

        // Set up bottom sheet — starts hidden, slides up when a node is selected
        bottomSheet = BottomSheetBehavior.from(binding.detailPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheet.isFitToContents = true
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheetView: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    viewModel.selectNode(null)
                }
            }

            override fun onSlide(bottomSheetView: View, slideOffset: Float) {}
        })

        binding.btnFlowRetry.setOnClickListener { viewModel.retry() }

        observeViewModel()

        serverId?.let { viewModel.initialize(it) }
    }

    private fun observeViewModel() {
        viewModel.circuits.observe(viewLifecycleOwner) { rebuildTree() }
        viewModel.appliances.observe(viewLifecycleOwner) { rebuildTree() }

        viewModel.selectedNode.observe(viewLifecycleOwner) { nodeKey ->
            if (_binding == null) return@observe
            treeBuilder.setSelectedNode(nodeKey)
            rebuildTree()

            if (nodeKey != null) {
                updateDetailPanel(nodeKey)
                if (bottomSheet.state == BottomSheetBehavior.STATE_HIDDEN) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                }
            } else {
                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }

        viewModel.detailLevels.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            val nodeKey = viewModel.selectedNode.value
            if (nodeKey != null) updateDetailPanel(nodeKey)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (_binding == null) return@observe
            binding.flowLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (_binding == null) return@observe
            binding.flowError.visibility = if (error != null) View.VISIBLE else View.GONE
            binding.flowErrorMessage.text = error
        }
    }

    private fun rebuildTree() {
        if (_binding == null) return
        val circuits = viewModel.circuits.value ?: emptyMap()
        val appliances = viewModel.appliances.value ?: emptyMap()

        val hasData = circuits.isNotEmpty()
        binding.treeScrollView.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.flowEmpty.visibility =
            if (!hasData && viewModel.isLoading.value != true && viewModel.error.value == null)
                View.VISIBLE else View.GONE

        treeBuilder.buildTree(binding.treeContainer, circuits, appliances)
    }

    private fun updateDetailPanel(nodeKey: String) {
        if (_binding == null) return

        val circuits = viewModel.circuits.value ?: emptyMap()
        val appliances = viewModel.appliances.value ?: emptyMap()
        val detailLevel = viewModel.getDetailLevel(nodeKey)

        val parts = nodeKey.split(":", limit = 2)
        if (parts.size != 2) return

        when (parts[0]) {
            "circuit" -> {
                val circuit = findCircuit(parts[1], circuits)
                if (circuit != null) {
                    detailView.buildCircuitDetail(binding.detailContent, circuit, detailLevel)
                }
            }
            "appliance" -> {
                val appliance = appliances[parts[1]]
                if (appliance != null) {
                    detailView.buildApplianceDetail(binding.detailContent, appliance, appliances, detailLevel)
                }
            }
        }
    }

    private fun findCircuit(id: String, circuits: Map<String, Circuit>): Circuit? {
        circuits[id]?.let { return it }
        for (circuit in circuits.values) {
            findCircuit(id, circuit.subCircuits)?.let { return it }
        }
        return null
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

        fun newInstance(serverId: String): EnergyFlowFragment {
            return EnergyFlowFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
