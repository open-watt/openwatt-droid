package com.openwatt.droid.ui.fragments

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
import com.openwatt.droid.ui.config.BackpressureChartView
import com.openwatt.droid.ui.config.FieldState
import com.openwatt.droid.ui.config.PropertyFieldFactory
import com.openwatt.droid.ui.config.RateChartView
import com.openwatt.droid.viewmodel.ConfigEditorViewModel

class ConfigEditorFragment : Fragment() {
    private var _binding: FragmentConfigEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfigEditorViewModel by viewModels()

    private var serverId: String? = null
    private var collectionName: String? = null
    private var schema: CollectionSchema? = null
    private var itemHandle: Long? = null
    private var itemName: String? = null
    private var item: SyncItem? = null
    private val isNew: Boolean get() = itemHandle == null

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

    /** Traffic charts in the Traffic category page (null until built). */
    private var bandwidthRx: RateChartView? = null
    private var bandwidthTx: RateChartView? = null
    private var backpressureChart: BackpressureChartView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
        collectionName = arguments?.getString(ARG_COLLECTION_NAME)

        val schemaJson = arguments?.getString(ARG_SCHEMA_JSON)
        schema = schemaJson?.let { Gson().fromJson(it, CollectionSchema::class.java) }

        itemHandle = arguments?.getLong(ARG_ITEM_HANDLE, NO_HANDLE)?.takeIf { it != NO_HANDLE }
        itemName = arguments?.getString(ARG_ITEM_NAME)
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

        // Start observing first so the viewmodel knows which item to watch,
        // then snapshot from the store. currentItem() reads the handle
        // internally so startObserving must come before it.
        itemHandle?.let { handle ->
            viewModel.startObserving(handle, currentSchema.typeName, itemName)
            item = viewModel.currentItem()
                // live updates will fill it in
                ?: SyncItem(handle = handle, type = currentSchema.typeName)
        }

        val title = if (isNew) {
            "New ${formatName(collectionName ?: "Item")}"
        } else {
            item?.name ?: itemName ?: "#${itemHandle}"
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

            val holder = createCategoryPage(requireContext())
            holder.root.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            bindCategoryPage(holder, "General", sorted.values.firstOrNull() ?: emptyList())

            val parent = binding.categoryPager.parent as? ViewGroup ?: return
            val pagerIndex = parent.indexOfChild(binding.categoryPager)
            parent.removeView(binding.categoryPager)
            parent.addView(holder.root, pagerIndex, holder.root.layoutParams)
        } else {
            binding.categoryTabs.visibility = View.VISIBLE
            binding.categoryPager.visibility = View.VISIBLE

            val categoryNames = sorted.keys.toList()
            val categoryEntries = sorted.values.toList()

            binding.categoryPager.adapter = object : RecyclerView.Adapter<CategoryViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
                    return createCategoryPage(parent.context).also {
                        it.root.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    }
                }

                override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
                    bindCategoryPage(holder, categoryNames[position], categoryEntries[position])
                }

                override fun getItemCount() = categoryNames.size
            }

            TabLayoutMediator(binding.categoryTabs, binding.categoryPager) { tab, position ->
                tab.text = categoryNames[position]
            }.attach()
        }

        reloadPendingData()
    }

    /** Build an empty category page: a scrollable field area over a fixed chart panel. */
    private fun createCategoryPage(context: Context): CategoryViewHolder {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val fieldContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(fieldContainer)
        val chartPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
            visibility = View.GONE
        }
        root.addView(scroll)
        root.addView(chartPanel)
        return CategoryViewHolder(root, fieldContainer, chartPanel)
    }

    /** Populate a category page's fields and (for the Traffic category) its charts. */
    private fun bindCategoryPage(
        holder: CategoryViewHolder,
        catName: String,
        entries: List<Pair<String, PropertySchema>>,
    ) {
        holder.fieldContainer.removeAllViews()
        val fields = mutableListOf<View>()
        for ((key, prop) in entries) {
            val value = getItemValue(key)
            val fv = createFieldWithLoaders(key, prop, value)
            holder.fieldContainer.addView(fv)
            fields.add(fv)
        }
        fieldViews[catName] = fields.toMutableList()
        buildTrafficPanel(holder.chartPanel, entries)
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

    /**
     * Build the fixed Traffic panel for a category holding traffic metrics: a
     * top row of side-by-side RX/TX bandwidth charts, and below a combined
     * back-pressure chart (queue + service time stacked, max as a red line).
     * Mirrors the web bandwidth/backpressure charts; hidden for other categories.
     */
    private fun buildTrafficPanel(panel: LinearLayout, entries: List<Pair<String, PropertySchema>>) {
        panel.removeAllViews()
        val keys = entries.mapTo(HashSet()) { it.first }
        val hasBandwidth = "rx-rate" in keys || "tx-rate" in keys
        val hasBackpressure = "avg-queue-time" in keys && "avg-service-time" in keys
        if (!hasBandwidth && !hasBackpressure) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE

        if (hasBandwidth) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(80))
            }
            val rx = RateChartView(requireContext()).apply {
                configure("RX", 0xFF2563EB.toInt())
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            val tx = RateChartView(requireContext()).apply {
                configure("TX", 0xFF0D9488.toInt())
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            }
            row.addView(rx)
            row.addView(tx)
            panel.addView(row)
            bandwidthRx = rx
            bandwidthTx = tx
        } else {
            bandwidthRx = null
            bandwidthTx = null
        }

        if (hasBackpressure) {
            val bp = BackpressureChartView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(80))
                    .apply { topMargin = dp(8) }
            }
            panel.addView(bp)
            backpressureChart = bp
        } else {
            backpressureChart = null
        }

        updateCharts()
    }

    private fun updateCharts() {
        val h = viewModel.getRateHistory()
        bandwidthRx?.setData(toBits(h["rx-rate"]))
        bandwidthTx?.setData(toBits(h["tx-rate"]))
        backpressureChart?.setData(
            h["avg-queue-time"] ?: FloatArray(0),
            h["avg-service-time"] ?: FloatArray(0),
            h["max-service-time"] ?: FloatArray(0),
        )
    }

    /** Rate ring buffers are Bytes/s; the bandwidth charts display bits/s. */
    private fun toBits(bytes: FloatArray?): FloatArray {
        if (bytes == null) return FloatArray(0)
        return FloatArray(bytes.size) { bytes[it] * 8f }
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
                binding.editorToolbar.title = updatedItem.name ?: "#${updatedItem.handle}"
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

        viewModel.rateHistoryVersion.observe(viewLifecycleOwner) {
            if (_binding != null) updateCharts()
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
        bandwidthRx = null
        bandwidthTx = null
        backpressureChart = null
        flagIndicators = null
        _binding = null
    }

    private class CategoryViewHolder(
        val root: LinearLayout,
        val fieldContainer: LinearLayout,
        val chartPanel: LinearLayout,
    ) : RecyclerView.ViewHolder(root)

    companion object {
        private const val ARG_SERVER_ID = "server_id"
        private const val ARG_COLLECTION_NAME = "collection_name"
        private const val ARG_SCHEMA_JSON = "schema_json"
        private const val ARG_ITEM_HANDLE = "item_handle"
        private const val ARG_ITEM_NAME = "item_name"
        private const val NO_HANDLE = Long.MIN_VALUE

        /**
         * [item] carries both the session handle (fast-path key for this
         * attach) and the name — the durable half of the item's identity,
         * which the editor needs to re-subscribe and to survive a reconnect.
         */
        fun newInstance(
            serverId: String,
            collectionName: String,
            schema: CollectionSchema,
            item: SyncItem?,
        ): ConfigEditorFragment {
            return ConfigEditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                    putString(ARG_COLLECTION_NAME, collectionName)
                    putString(ARG_SCHEMA_JSON, Gson().toJson(schema))
                    putLong(ARG_ITEM_HANDLE, item?.handle ?: NO_HANDLE)
                    item?.name?.let { putString(ARG_ITEM_NAME, it) }
                }
            }
        }
    }
}
