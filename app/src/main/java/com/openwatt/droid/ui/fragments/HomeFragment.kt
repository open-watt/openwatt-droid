package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.openwatt.droid.databinding.FragmentHomeBinding
import com.openwatt.droid.ui.adapters.SwitchGridAdapter
import com.openwatt.droid.viewmodel.HomeViewModel

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: SwitchGridAdapter
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

        binding.btnSwitchesRetry.setOnClickListener {
            viewModel.retry()
        }

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
    }

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
