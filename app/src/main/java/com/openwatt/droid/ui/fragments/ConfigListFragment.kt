package com.openwatt.droid.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentConfigListBinding
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.ui.adapters.ConfigListAdapter
import com.openwatt.droid.viewmodel.ConfigListViewModel

class ConfigListFragment : Fragment() {
    private var _binding: FragmentConfigListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfigListViewModel by viewModels()
    private lateinit var adapter: ConfigListAdapter

    private var serverId: String? = null
    private var collectionName: String? = null
    private var schema: CollectionSchema? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
        collectionName = arguments?.getString(ARG_COLLECTION_NAME)

        val schemaJson = arguments?.getString(ARG_SCHEMA_JSON)
        schema = schemaJson?.let { Gson().fromJson(it, CollectionSchema::class.java) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConfigListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSchema = schema ?: return

        // Toolbar
        binding.configListToolbar.title = formatName(collectionName ?: "Collection")
        binding.configListToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.configListToolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Adapter
        adapter = ConfigListAdapter(
            schema = currentSchema,
            onItemClick = { item -> navigateToEditor(item) },
            onItemLongClick = { item -> showContextMenu(item) },
        )
        adapter.rateHistoryProvider = { itemName -> viewModel.getRateHistory(itemName) }
        adapter.highWaterMarkProvider = { itemName -> viewModel.getHighWaterMarks(itemName) }

        binding.itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.itemsRecycler.adapter = adapter

        // Search
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setFilter(s?.toString() ?: "")
            }
        })

        // FAB
        binding.fabAdd.setOnClickListener {
            navigateToEditor(null)
        }

        // Retry
        binding.btnRetry.setOnClickListener {
            viewModel.retry()
        }

        observeViewModel()

        serverId?.let { viewModel.initialize(it, currentSchema) }
        adapter.rateKeys = viewModel.getRateKeys()
    }

    private fun observeViewModel() {
        viewModel.items.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items.toList())

            val totalCount = items.size
            binding.statusBar.text = "$totalCount item${if (totalCount != 1) "s" else ""}"

            if (items.isEmpty() && viewModel.isLoading.value != true && viewModel.error.value == null) {
                binding.emptyState.visibility = View.VISIBLE
                binding.itemsRecycler.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.itemsRecycler.visibility = View.VISIBLE
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
                binding.itemsRecycler.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
            } else {
                binding.errorState.visibility = View.GONE
            }
        }

        viewModel.rateHistoryVersion.observe(viewLifecycleOwner) { _ ->
            adapter.refreshSparklines()
        }
    }

    private fun navigateToEditor(item: CollectionItem?) {
        val sid = serverId ?: return
        val name = collectionName ?: return
        val currentSchema = schema ?: return

        val fragment = ConfigEditorFragment.newInstance(sid, name, currentSchema, item)
        parentFragmentManager.beginTransaction()
            .replace(id, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showContextMenu(item: CollectionItem) {
        val anchor = binding.itemsRecycler.findViewHolderForAdapterPosition(
            adapter.currentList.indexOf(item)
        )?.itemView ?: return

        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_EDIT, 0, "Edit")
            menu.add(0, MENU_DELETE, 1, "Delete")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_EDIT -> {
                        navigateToEditor(item)
                        true
                    }
                    MENU_DELETE -> {
                        confirmDelete(item)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmDelete(item: CollectionItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Item")
            .setMessage("Delete \"${item.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteItem(item.name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatName(name: String): String {
        return name
            .replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopPolling()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"
        private const val ARG_COLLECTION_NAME = "collection_name"
        private const val ARG_SCHEMA_JSON = "schema_json"

        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2

        fun newInstance(
            serverId: String,
            collectionName: String,
            schema: CollectionSchema,
        ): ConfigListFragment {
            return ConfigListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_COLLECTION_NAME, collectionName)
                    putString(ARG_SCHEMA_JSON, Gson().toJson(schema))
                }
            }
        }
    }
}
