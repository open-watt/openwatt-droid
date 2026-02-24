package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.openwatt.droid.databinding.FragmentConfigBrowserBinding
import com.openwatt.droid.ui.adapters.ConfigBrowserAdapter
import com.openwatt.droid.viewmodel.ConfigBrowserViewModel

class ConfigBrowserFragment : Fragment() {
    private var _binding: FragmentConfigBrowserBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfigBrowserViewModel by viewModels()
    private lateinit var adapter: ConfigBrowserAdapter
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
        _binding = FragmentConfigBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ConfigBrowserAdapter(
            onGroupClick = { groupName -> viewModel.toggleGroup(groupName) },
            onLeafClick = { collectionName -> navigateToCollection(collectionName) },
        )

        binding.browserRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.browserRecycler.adapter = adapter

        binding.btnRetry.setOnClickListener {
            viewModel.retry()
        }

        observeViewModel()

        serverId?.let { viewModel.initialize(it) }
    }

    private fun observeViewModel() {
        viewModel.items.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items.toList())

            if (items.isEmpty() && viewModel.isLoading.value != true && viewModel.error.value == null) {
                binding.emptyState.visibility = View.VISIBLE
                binding.browserRecycler.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.browserRecycler.visibility = View.VISIBLE
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
                binding.browserRecycler.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
            } else {
                binding.errorState.visibility = View.GONE
            }
        }
    }

    private fun navigateToCollection(collectionName: String) {
        val schema = viewModel.getSchema(collectionName) ?: return
        val sid = serverId ?: return

        val fragment = ConfigListFragment.newInstance(sid, collectionName, schema)
        parentFragmentManager.beginTransaction()
            .replace(id, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): ConfigBrowserFragment {
            return ConfigBrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
