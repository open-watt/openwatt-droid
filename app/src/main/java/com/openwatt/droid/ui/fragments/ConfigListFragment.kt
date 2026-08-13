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
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentConfigListBinding
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.SyncItem
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

    /** Non-abstract types sharing this collection's collection_id, keyed by type name.
     *  Empty when the collection has no polymorphic members. */
    private var memberTypes: Map<String, CollectionSchema> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
        collectionName = arguments?.getString(ARG_COLLECTION_NAME)

        val schemaJson = arguments?.getString(ARG_SCHEMA_JSON)
        schema = schemaJson?.let { Gson().fromJson(it, CollectionSchema::class.java) }

        val membersJson = arguments?.getString(ARG_MEMBER_TYPES_JSON)
        if (membersJson != null) {
            val type = object : TypeToken<Map<String, CollectionSchema>>() {}.type
            memberTypes = Gson().fromJson(membersJson, type) ?: emptyMap()
        }
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

        binding.configListToolbar.title = formatName(collectionName ?: "Collection")
        binding.configListToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.configListToolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = ConfigListAdapter(
            schema = currentSchema,
            onItemClick = { item -> editItem(item) },
            onItemLongClick = { item -> showContextMenu(item) },
        )
        adapter.rateHistoryProvider = { handle -> viewModel.getRateHistory(handle) }
        adapter.highWaterMarkProvider = { handle -> viewModel.getHighWaterMarks(handle) }

        binding.itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.itemsRecycler.adapter = adapter
        // Rate fields update constantly; the default fade-in-fade-out change
        // animation would fire on every tick and the whole list would shimmer.
        (binding.itemsRecycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setFilter(s?.toString() ?: "")
            }
        })

        binding.fabAdd.setOnClickListener {
            showAddMenu()
        }

        binding.btnRetry.setOnClickListener {
            viewModel.retry()
        }

        observeViewModel()

        // Items on the sync channel are filtered by concrete type name, so
        // hand the viewmodel every member type of this collection (keys and
        // typeName fields both, in case they diverge).
        val memberTypeNames = memberTypes.keys + memberTypes.values.map { it.typeName }
        serverId?.let { viewModel.initialize(it, currentSchema, memberTypeNames) }
        adapter.rateKeys = viewModel.getRateKeys()
    }

    override fun onStart() {
        super.onStart()
        viewModel.resumeSubscription()
    }

    override fun onStop() {
        super.onStop()
        viewModel.pauseSubscription()
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

    /** Decide whether to show a type picker or go straight to the editor for a new item. */
    private fun showAddMenu() {
        val creatable = creatableTypes()
        when (creatable.size) {
            0 -> {
                // Nothing instantiable here (all types abstract). Shouldn't normally happen.
                return
            }
            1 -> navigateToEditor(null, creatable.values.first())
            else -> {
                val typeNames = creatable.keys.toList()
                val labels = typeNames.map { formatName(it) }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Add ${formatName(collectionName ?: "Item")}")
                    .setItems(labels) { _, which ->
                        navigateToEditor(null, creatable[typeNames[which]]!!)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** Non-abstract schemas available for creation in this collection. */
    private fun creatableTypes(): Map<String, CollectionSchema> {
        val main = schema ?: return emptyMap()
        val pool = if (memberTypes.isNotEmpty()) memberTypes else mapOf((collectionName ?: main.path) to main)
        return pool.filterValues { !it.abstract }
    }

    /** Look up the schema for an existing item by its sync type, with fallbacks. */
    private fun schemaForItem(item: SyncItem): CollectionSchema {
        val main = schema!!
        val type = item.type
        if (type.isEmpty()) return main
        memberTypes[type]?.let { return it }
        memberTypes.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.let { return it.value }
        memberTypes.values.firstOrNull { it.typeName.equals(type, ignoreCase = true) }?.let { return it }
        return main
    }

    private fun navigateToEditor(item: SyncItem?, typeSchema: CollectionSchema) {
        val sid = serverId ?: return
        val name = collectionName ?: return

        val fragment = ConfigEditorFragment.newInstance(sid, name, typeSchema, item)
        parentFragmentManager.beginTransaction()
            .replace(id, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Edit path for an existing item: resolve the correct schema by item type. */
    private fun editItem(item: SyncItem) {
        navigateToEditor(item, schemaForItem(item))
    }

    private fun showContextMenu(item: SyncItem) {
        val anchor = binding.itemsRecycler.findViewHolderForAdapterPosition(
            adapter.currentList.indexOf(item)
        )?.itemView ?: return

        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_EDIT, 0, "Edit")
            menu.add(0, MENU_DELETE, 1, "Delete")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_EDIT -> {
                        editItem(item)
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

    private fun confirmDelete(item: SyncItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Item")
            .setMessage("Delete \"${item.name ?: "#${item.handle}"}\"?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteItem(item.handle)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatName(name: String): String {
        return name
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"
        private const val ARG_COLLECTION_NAME = "collection_name"
        private const val ARG_SCHEMA_JSON = "schema_json"
        private const val ARG_MEMBER_TYPES_JSON = "member_types_json"

        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2

        fun newInstance(
            serverId: String,
            collectionName: String,
            schema: CollectionSchema,
            memberTypes: Map<String, CollectionSchema> = emptyMap(),
        ): ConfigListFragment {
            return ConfigListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_COLLECTION_NAME, collectionName)
                    putString(ARG_SCHEMA_JSON, Gson().toJson(schema))
                    if (memberTypes.isNotEmpty()) {
                        putString(ARG_MEMBER_TYPES_JSON, Gson().toJson(memberTypes))
                    }
                }
            }
        }
    }
}
