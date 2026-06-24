package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.openwatt.droid.databinding.FragmentEnergyFlowBinding
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
            onDetailMore = { viewModel.selectedNode.value?.let { viewModel.cycleDetailLevel(it) } },
            onDetailLess = { viewModel.selectedNode.value?.let { viewModel.decreaseDetailLevel(it) } },
            onDetailBack = { viewModel.selectedNode.value?.let { viewModel.resetDetailLevel(it) } },
        )

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
        viewModel.forest.observe(viewLifecycleOwner) { rebuildTree() }

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
            viewModel.selectedNode.value?.let { updateDetailPanel(it) }
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
        val forest = viewModel.forest.value ?: return

        val hasData = forest.roots.isNotEmpty()
        binding.treeScrollView.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.flowEmpty.visibility =
            if (!hasData && viewModel.isLoading.value != true && viewModel.error.value == null) {
                View.VISIBLE
            } else {
                View.GONE
            }

        treeBuilder.buildTree(binding.treeContainer, forest)
    }

    private fun updateDetailPanel(nodeKey: String) {
        if (_binding == null) return
        val node = viewModel.getNode(nodeKey) ?: return
        detailView.buildDetail(binding.detailContent, node, viewModel.getDetailLevel(nodeKey))
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
