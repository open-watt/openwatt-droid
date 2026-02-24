package com.openwatt.droid.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentConfigEditorBinding
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.EDITOR_FLAGS
import com.openwatt.droid.model.PropertySchema
import com.openwatt.droid.ui.config.FieldState
import com.openwatt.droid.ui.config.PropertyFieldFactory
import com.openwatt.droid.viewmodel.ConfigEditorViewModel

class ConfigEditorFragment : Fragment() {
    private var _binding: FragmentConfigEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfigEditorViewModel by viewModels()

    private var serverId: String? = null
    private var collectionName: String? = null
    private var schema: CollectionSchema? = null
    private var item: CollectionItem? = null
    private val isNew: Boolean get() = item == null

    /** All field views grouped by category, for value extraction and dirty checking */
    private val fieldViews = mutableMapOf<String, MutableList<View>>()

    /** Map of enum field name -> field view, for async population */
    private val pendingEnumFields = mutableMapOf<String, View>()
    private val pendingRefFields = mutableMapOf<String, View>()

    /** Bottom bar flags indicators (read-only status from backend) */
    private var flagIndicators: List<TextView>? = null
    private var currentFlagsValue: Int = 0

    /** Comment value (saved immediately, not part of dirty tracking) */
    private var commentValue: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
        collectionName = arguments?.getString(ARG_COLLECTION_NAME)

        val schemaJson = arguments?.getString(ARG_SCHEMA_JSON)
        schema = schemaJson?.let { Gson().fromJson(it, CollectionSchema::class.java) }

        val itemJson = arguments?.getString(ARG_ITEM_JSON)
        if (itemJson != null) {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val props: Map<String, Any?> = Gson().fromJson(itemJson, type)
            val name = props["name"]?.toString() ?: ""
            // Normalize Gson's Double→Long for whole numbers to match CliClient.parseJsonValue
            val normalizedProps = props.filterKeys { it != "name" }.mapValues { (_, v) ->
                normalizeGsonValue(v)
            }
            item = CollectionItem(
                name = name,
                properties = normalizedProps,
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConfigEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSchema = schema ?: return

        // Initialize ViewModel
        serverId?.let { viewModel.initialize(it, currentSchema) }

        // Toolbar
        val title = if (isNew) "New ${formatName(collectionName ?: "Item")}" else (item?.name ?: "Edit")
        binding.editorToolbar.title = title
        binding.editorToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.editorToolbar.setNavigationOnClickListener { handleBack() }

        // Back press handler
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        // Build form fields grouped by category
        buildForm(currentSchema)

        // Build bottom bar (flags + comment)
        buildBottomBar(currentSchema)

        // Action buttons
        binding.btnSave.setOnClickListener { saveItem() }
        binding.btnReset.setOnClickListener { resetForm() }

        observeViewModel()

        // Start polling for existing items to get live updates
        if (!isNew) {
            item?.name?.let { viewModel.startPolling(it) }
        }
    }

    private fun buildForm(schema: CollectionSchema) {
        // Group properties by category, filtering hidden/skipped and bottom-bar fields
        val categories = linkedMapOf<String, MutableList<Pair<String, PropertySchema>>>()

        for ((key, prop) in schema.properties) {
            if (prop.isHidden) continue
            if (isNew && prop.isReadOnly) continue
            if (key == "flags" || key == "comment") continue // handled in bottom bar

            val category = prop.category ?: "General"
            categories.getOrPut(category) { mutableListOf() }.add(key to prop)
        }

        // Ensure "General" is first
        val sorted = linkedMapOf<String, List<Pair<String, PropertySchema>>>()
        categories["General"]?.let { sorted["General"] = it }
        for ((cat, props) in categories) {
            if (cat != "General") sorted[cat] = props
        }

        if (sorted.size <= 1) {
            // Single category: no tabs, just a scroll view
            binding.categoryTabs.visibility = View.GONE
            binding.categoryPager.visibility = View.GONE

            // Replace pager with a simple ScrollView
            val scrollView = ScrollView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                )
            }
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            val entries = sorted.values.firstOrNull() ?: emptyList()
            val fields = mutableListOf<View>()
            for ((key, prop) in entries) {
                val value = getItemValue(key)
                val fv = createFieldWithLoaders(key, prop, value)
                container.addView(fv)
                fields.add(fv)
            }
            fieldViews["General"] = fields.toMutableList()

            scrollView.addView(container)

            // Add scrollView to the parent layout, replacing the pager slot
            val parent = binding.categoryPager.parent as? ViewGroup ?: return
            val pagerIndex = parent.indexOfChild(binding.categoryPager)
            parent.removeView(binding.categoryPager)
            parent.addView(scrollView, pagerIndex, scrollView.layoutParams)
        } else {
            // Multiple categories: use TabLayout + ViewPager2
            binding.categoryTabs.visibility = View.VISIBLE
            binding.categoryPager.visibility = View.VISIBLE

            val categoryNames = sorted.keys.toList()
            val categoryEntries = sorted.values.toList()

            binding.categoryPager.adapter = object : RecyclerView.Adapter<CategoryViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
                    val scrollView = ScrollView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                    val container = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                    }
                    scrollView.addView(container)
                    return CategoryViewHolder(scrollView, container)
                }

                override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
                    holder.container.removeAllViews()
                    val entries = categoryEntries[position]
                    val catName = categoryNames[position]
                    val fields = mutableListOf<View>()

                    for ((key, prop) in entries) {
                        val value = getItemValue(key)
                        val fv = createFieldWithLoaders(key, prop, value)
                        holder.container.addView(fv)
                        fields.add(fv)
                    }
                    fieldViews[catName] = fields.toMutableList()
                }

                override fun getItemCount() = categoryNames.size
            }

            TabLayoutMediator(binding.categoryTabs, binding.categoryPager) { tab, position ->
                tab.text = categoryNames[position]
            }.attach()
        }

        // Request enum/ref loading for fields that need it
        reloadPendingData()
    }

    private fun buildBottomBar(schema: CollectionSchema) {
        // Flags: read-only status indicators
        val flagsValue = (getItemValue("flags") as? Number)?.toInt() ?: 0
        currentFlagsValue = flagsValue
        if (flagsValue != 0 || !isNew) {
            val flagsRow = binding.flagsRow
            flagsRow.visibility = View.VISIBLE

            val indicators = mutableListOf<TextView>()
            for (flag in EDITOR_FLAGS) {
                val active = flagsValue and (1 shl flag.bit) != 0
                val tv = TextView(requireContext()).apply {
                    text = flag.icon
                    textSize = 14f
                    alpha = if (active) 1.0f else 0.25f
                    setPadding(dp(2), 0, dp(2), 0)
                    contentDescription = flag.name
                }
                indicators.add(tv)
                flagsRow.addView(tv)
            }
            flagIndicators = indicators
        }

        // Comment button — always available (comment is a universal collection item field)
        commentValue = getItemValue("comment")?.toString() ?: ""

        binding.btnComment.visibility = View.VISIBLE
        updateCommentButtonLabel()
        binding.btnComment.setOnClickListener { showCommentDialog() }
    }

    private fun updateCommentButtonLabel() {
        binding.btnComment.text = "Comment"
    }

    private fun showCommentDialog() {
        val editText = EditText(requireContext()).apply {
            setText(commentValue)
            hint = "Enter comment..."
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minLines = 3
            isSingleLine = false
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Comment")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                commentValue = editText.text.toString()
                updateCommentButtonLabel()
                item?.name?.let { viewModel.setComment(it, commentValue) }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                commentValue = ""
                updateCommentButtonLabel()
                item?.name?.let { viewModel.setComment(it, "") }
            }
            .show()
    }

    /**
     * Create a field and register enum/ref loaders that correctly reference the created view.
     */
    private fun createFieldWithLoaders(key: String, prop: PropertySchema, value: Any?): View {
        var fieldViewRef: View? = null
        val fv = PropertyFieldFactory.createField(
            requireContext(), key, prop, value,
            enumLoader = { enumName ->
                fieldViewRef?.let { pendingEnumFields[enumName] = it }
                viewModel.loadEnum(enumName)
            },
            refLoader = { refType ->
                fieldViewRef?.let { pendingRefFields[refType] = it }
                viewModel.loadCollectionRef(refType)
            },
        )
        fieldViewRef = fv
        // Re-trigger loaders now that fieldViewRef is set
        val type = prop.primaryType
        if (type.startsWith("enum_") && type != "enum_ObjectFlags") {
            pendingEnumFields[type.removePrefix("enum_")] = fv
        } else if (type.startsWith("#")) {
            pendingRefFields[type.removePrefix("#")] = fv
        }
        return fv
    }

    private fun reloadPendingData() {
        for ((key, prop) in schema?.properties ?: emptyMap()) {
            val type = prop.primaryType
            if (type.startsWith("enum_") && type != "enum_ObjectFlags") {
                val enumName = type.removePrefix("enum_")
                viewModel.loadEnum(enumName)
            } else if (type.startsWith("#")) {
                val refType = type.removePrefix("#")
                viewModel.loadCollectionRef(refType)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.enumLoaded.observe(viewLifecycleOwner) { (enumName, options) ->
            for (fields in fieldViews.values) {
                for (fieldView in fields) {
                    val fieldEnumName = fieldView.getTag(R.id.tag_enum_name) as? String
                    if (fieldEnumName == enumName) {
                        val key = (fieldView.tag as? FieldState)?.key
                        val currentValue = if (key != null) getItemValue(key) else null
                        PropertyFieldFactory.populateEnumField(fieldView, options, currentValue)
                    }
                }
            }
        }

        viewModel.refLoaded.observe(viewLifecycleOwner) { (refType, names) ->
            for (fields in fieldViews.values) {
                for (fieldView in fields) {
                    val fieldRefType = fieldView.getTag(R.id.tag_ref_type) as? String
                    if (fieldRefType == refType) {
                        PropertyFieldFactory.populateReferenceField(fieldView, names)
                    }
                }
            }
        }

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ConfigEditorViewModel.SaveResult.Success -> {
                    Snackbar.make(binding.root, "Saved successfully", Snackbar.LENGTH_SHORT).show()
                    viewModel.clearSaveResult()
                    parentFragmentManager.popBackStack()
                }
                is ConfigEditorViewModel.SaveResult.Error -> {
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    viewModel.clearSaveResult()
                }
                null -> {}
            }
        }

        viewModel.isSaving.observe(viewLifecycleOwner) { isSaving ->
            binding.btnSave.isEnabled = !isSaving
            binding.btnReset.isEnabled = !isSaving
            binding.progressBar.visibility = if (isSaving) View.VISIBLE else View.GONE
        }

        viewModel.liveItem.observe(viewLifecycleOwner) { updatedItem ->
            if (updatedItem == null) return@observe
            // Update non-dirty form fields
            for (fields in fieldViews.values) {
                for (fieldView in fields) {
                    val state = fieldView.tag as? FieldState ?: continue
                    if (state.isDirty()) continue
                    val key = state.key
                    val newValue = if (key == "name") updatedItem.name else updatedItem.properties[key]
                    val unwrapped = PropertyFieldFactory.unwrapQuantity(newValue)
                    state.setValue(unwrapped)
                    state.originalValue = unwrapped
                }
            }
            // Update flags indicators (always — they're read-only)
            val newFlags = (updatedItem.properties["flags"] as? Number)?.toInt() ?: 0
            updateFlagIndicators(newFlags)
        }

        viewModel.commentResult.observe(viewLifecycleOwner) { result ->
            if (result is ConfigEditorViewModel.SaveResult.Error) {
                Snackbar.make(binding.root, "Comment: ${result.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Update flag indicator visuals from a flags integer */
    private fun updateFlagIndicators(flagsValue: Int) {
        currentFlagsValue = flagsValue
        val indicators = flagIndicators ?: return
        for ((i, tv) in indicators.withIndex()) {
            val active = flagsValue and (1 shl EDITOR_FLAGS[i].bit) != 0
            tv.alpha = if (active) 1.0f else 0.25f
        }
    }

    private fun saveItem() {
        val values = extractAllValues()

        viewModel.save(
            isNew = isNew,
            originalName = item?.name,
            values = values,
            originalItem = item,
        )
    }

    private fun resetForm() {
        // Reset form fields
        for (fields in fieldViews.values) {
            for (fieldView in fields) {
                val state = fieldView.tag as? FieldState ?: continue
                state.setValue(state.originalValue)
                PropertyFieldFactory.updateDirtyIndicator(fieldView)
            }
        }
    }

    private fun extractAllValues(): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()

        for (fields in fieldViews.values) {
            for (fieldView in fields) {
                val state = fieldView.tag as? FieldState ?: continue
                values[state.key] = state.getValue()
            }
        }

        return values
    }

    private fun hasUnsavedChanges(): Boolean {
        return fieldViews.values.any { fields ->
            fields.any { PropertyFieldFactory.isDirty(it) }
        }
    }

    private fun handleBack() {
        if (hasUnsavedChanges()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Discard changes?")
                .setMessage("You have unsaved changes. Discard them?")
                .setPositiveButton("Discard") { _, _ ->
                    parentFragmentManager.popBackStack()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    private fun getItemValue(key: String): Any? {
        if (item == null) return null
        return if (key == "name") item?.name else item?.properties?.get(key)
    }

    private fun formatName(name: String): String {
        return name
            .replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** Normalize Gson's default number parsing (all numbers → Double) to match CliClient */
    private fun normalizeGsonValue(value: Any?): Any? {
        return when (value) {
            is Double -> if (value == value.toLong().toDouble()) value.toLong() else value
            is Map<*, *> -> value.mapValues { (_, v) -> normalizeGsonValue(v) }
            is List<*> -> value.map { normalizeGsonValue(it) }
            else -> value
        }
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopPolling()
        fieldViews.clear()
        pendingEnumFields.clear()
        pendingRefFields.clear()
        flagIndicators = null
        _binding = null
    }

    private class CategoryViewHolder(
        val scrollView: ScrollView,
        val container: LinearLayout,
    ) : RecyclerView.ViewHolder(scrollView)

    companion object {
        private const val ARG_SERVER_ID = "server_id"
        private const val ARG_COLLECTION_NAME = "collection_name"
        private const val ARG_SCHEMA_JSON = "schema_json"
        private const val ARG_ITEM_JSON = "item_json"

        fun newInstance(
            serverId: String,
            collectionName: String,
            schema: CollectionSchema,
            item: CollectionItem?,
        ): ConfigEditorFragment {
            return ConfigEditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_COLLECTION_NAME, collectionName)
                    putString(ARG_SCHEMA_JSON, Gson().toJson(schema))
                    if (item != null) {
                        // Serialize item as flat map including name
                        val itemMap = mutableMapOf<String, Any?>("name" to item.name)
                        itemMap.putAll(item.properties)
                        putString(ARG_ITEM_JSON, Gson().toJson(itemMap))
                    }
                }
            }
        }
    }
}
