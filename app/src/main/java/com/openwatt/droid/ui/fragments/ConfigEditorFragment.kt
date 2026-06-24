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
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentConfigEditorBinding
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.EDITOR_FLAGS
import com.openwatt.droid.model.PropertySchema
import com.openwatt.droid.model.SyncItem
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
    private var itemCid: Int? = null
    private var item: SyncItem? = null
    private val isNew: Boolean get() = itemCid == null

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

        itemCid = arguments?.getInt(ARG_ITEM_CID, NO_CID)?.takeIf { it != NO_CID }
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

        serverId?.let { viewModel.initialize(it, currentSchema) }

        // Start observing first so the viewmodel knows which CID to watch,
        // then snapshot from the store. currentItem() reads itemCid internally
        // so startObserving must come before it.
        itemCid?.let { cid ->
            viewModel.startObserving(cid)
            item = viewModel.currentItem()
                ?: SyncItem(cid = cid, type = "") // live updates will fill it in
        }

        val title = if (isNew) {
            "New ${formatName(collectionName ?: "Item")}"
        } else {
            item?.name ?: "#${itemCid}"
        }
        binding.editorToolbar.title = title
        binding.editorToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.editorToolbar.setNavigationOnClickListener { handleBack() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        buildForm(currentSchema)
        buildBottomBar(currentSchema)

        binding.btnSave.setOnClickListener { saveItem() }
        binding.btnReset.setOnClickListener { resetForm() }

        observeViewModel()
    }

    private fun buildForm(schema: CollectionSchema) {
        val categories = linkedMapOf<String, MutableList<Pair<String, PropertySchema>>>()

        for ((key, prop) in schema.properties) {
            if (prop.isHidden) continue
            if (isNew && prop.isReadOnly) continue
            if (key == "flags" || key == "comment") continue

            val category = prop.category ?: "General"
            categories.getOrPut(category) { mutableListOf() }.add(key to prop)
        }

        val sorted = linkedMapOf<String, List<Pair<String, PropertySchema>>>()
        categories["General"]?.let { sorted["General"] = it }
        for ((cat, props) in categories) {
            if (cat != "General") sorted[cat] = props
        }

        if (sorted.size <= 1) {
            binding.categoryTabs.visibility = View.GONE
            binding.categoryPager.visibility = View.GONE

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

            val parent = binding.categoryPager.parent as? ViewGroup ?: return
            val pagerIndex = parent.indexOfChild(binding.categoryPager)
            parent.removeView(binding.categoryPager)
            parent.addView(scrollView, pagerIndex, scrollView.layoutParams)
        } else {
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

        reloadPendingData()
    }

    private fun buildBottomBar(schema: CollectionSchema) {
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
                viewModel.setComment(commentValue)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                commentValue = ""
                updateCommentButtonLabel()
                viewModel.setComment("")
            }
            .show()
    }

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
        val type = prop.primaryType
        if (type.startsWith("enum_") && type != "enum_ObjectFlags") {
            pendingEnumFields[type.removePrefix("enum_")] = fv
        } else if (type.startsWith("#")) {
            pendingRefFields[type.removePrefix("#")] = fv
        }
        return fv
    }

    private fun reloadPendingData() {
        for ((_, prop) in schema?.properties ?: emptyMap()) {
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
            item = updatedItem
            if (!isNew) {
                binding.editorToolbar.title = updatedItem.name ?: "#${updatedItem.cid}"
            }
            // Update non-dirty form fields from the live value. Use getItemValue
            // so the "type" fallback to the bind-level type field is applied
            // here too, otherwise a live update overwrites the form field with
            // an empty value read from props (where "type" doesn't live).
            for (fields in fieldViews.values) {
                for (fieldView in fields) {
                    val state = fieldView.tag as? FieldState ?: continue
                    if (state.isDirty()) continue
                    // Don't disturb a field the user is actively editing —
                    // setText on a focused EditText resets the cursor and
                    // churns the IME's InputConnection (session-id-mismatch
                    // spam, red spell-check underline flicker).
                    if (fieldView.hasFocus() || fieldView.findFocus() != null) continue
                    val key = state.key
                    val newValue = getItemValue(key)
                    val unwrapped = PropertyFieldFactory.unwrapQuantity(newValue)
                    // Skip no-op writes: setText with an identical string
                    // still fires TextWatchers and nukes the cursor.
                    if (state.getValue() == unwrapped) continue
                    state.setValue(unwrapped)
                    state.originalValue = unwrapped
                }
            }
            val newFlags = (updatedItem.properties["flags"] as? Number)?.toInt() ?: 0
            updateFlagIndicators(newFlags)
        }

        viewModel.commentResult.observe(viewLifecycleOwner) { result ->
            if (result is ConfigEditorViewModel.SaveResult.Error) {
                Snackbar.make(binding.root, "Comment: ${result.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

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
            values = values,
            originalItem = item,
        )
    }

    private fun resetForm() {
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
        val current = item ?: return null
        return when (key) {
            "name" -> current.name
            // Sync's bind carries `type` as a top-level field, not a property,
            // so fall back to it when a schema property named "type" has no
            // explicit value in props.
            "type" -> current.properties["type"] ?: current.type.takeIf { it.isNotEmpty() }
            else -> current.properties[key]
        }
    }

    private fun formatName(name: String): String {
        return name
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
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
        private const val ARG_ITEM_CID = "item_cid"
        private const val NO_CID = Int.MIN_VALUE

        fun newInstance(
            serverId: String,
            collectionName: String,
            schema: CollectionSchema,
            cid: Int?,
        ): ConfigEditorFragment {
            return ConfigEditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_COLLECTION_NAME, collectionName)
                    putString(ARG_SCHEMA_JSON, Gson().toJson(schema))
                    putInt(ARG_ITEM_CID, cid ?: NO_CID)
                }
            }
        }
    }
}
