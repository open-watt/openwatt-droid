package com.openwatt.droid.ui.config

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openwatt.droid.R
import com.openwatt.droid.model.OBJECT_FLAGS
import com.openwatt.droid.model.PropertySchema

/**
 * Factory that generates Android form fields from backend property schemas.
 * Each field tracks its original value for dirty comparison.
 */
object PropertyFieldFactory {

    /**
     * Create a form field View for a given property.
     * Returns a wrapper View containing a label and input(s).
     * The wrapper's tag is set to a [FieldState] for dirty tracking and value extraction.
     */
    fun createField(
        context: Context,
        key: String,
        prop: PropertySchema,
        value: Any?,
        enumLoader: ((String) -> Unit)? = null,
        refLoader: ((String) -> Unit)? = null,
    ): View {
        val type = prop.primaryType
        val isReadOnly = prop.isReadOnly

        // Unwrap backend quantity objects {q, u}
        val unwrapped = unwrapQuantity(value)

        return when {
            type == "bool" -> createBoolField(context, key, unwrapped, isReadOnly)
            type == "enum_ObjectFlags" -> createBitfieldField(context, key, unwrapped, isReadOnly)
            type.startsWith("enum_") -> createEnumField(context, key, type, unwrapped, isReadOnly, enumLoader)
            type.startsWith("#") -> createReferenceField(context, key, type, unwrapped, isReadOnly, refLoader)
            type.startsWith("q_") -> createQuantityField(context, key, type, unwrapped, isReadOnly)
            type.endsWith("[]") -> createArrayField(context, key, type, unwrapped, isReadOnly)
            else -> createBasicField(context, key, type, unwrapped, isReadOnly, prop.default)
        }
    }

    /**
     * Extract the current value from a field's View, properly typed.
     */
    fun extractValue(fieldView: View): Any? {
        val state = fieldView.tag as? FieldState ?: return null
        return state.getValue()
    }

    /**
     * Check if a field has been modified from its original value.
     */
    fun isDirty(fieldView: View): Boolean {
        val state = fieldView.tag as? FieldState ?: return false
        return state.isDirty()
    }

    /**
     * Update a field's displayed value (for live backend updates on non-dirty fields).
     */
    fun updateValue(fieldView: View, newValue: Any?) {
        val state = fieldView.tag as? FieldState ?: return
        if (!state.isDirty()) {
            state.setValue(unwrapQuantity(newValue))
            state.originalValue = unwrapQuantity(newValue)
        } else {
            // Update original for dirty comparison, but keep user's edit
            state.originalValue = unwrapQuantity(newValue)
        }
    }

    /**
     * Update the dirty visual indicator on a field.
     */
    fun updateDirtyIndicator(fieldView: View) {
        val state = fieldView.tag as? FieldState ?: return
        val label = fieldView.findViewWithTag<TextView>("field_label")
        if (label != null) {
            val color = if (state.isDirty()) {
                ContextCompat.getColor(fieldView.context, R.color.purple_500)
            } else {
                label.currentTextColor // Keep current default
            }
            label.setTextColor(color)
        }
    }

    // ─── Field Creators ──────────────────────────────────────────────

    private fun createBasicField(
        context: Context,
        key: String,
        type: String,
        value: Any?,
        isReadOnly: Boolean,
        default: Any?,
    ): View {
        val wrapper = createFieldWrapper(context, key)

        val inputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val editText = TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isEnabled = !isReadOnly

            when (type) {
                "int" -> {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                "uint" -> {
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                "num" -> {
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                "byte" -> {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "0-255"
                }
                "ipv4" -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "192.168.1.1"
                }
                "eui" -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "00:11:22:33:44:55:66:77"
                }
                "dt" -> {
                    inputType = InputType.TYPE_CLASS_DATETIME
                }
                "com" -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "@device.component"
                }
                "elem" -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "@device.component.element"
                }
                "byte[]" -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 5
                    hint = "Base64 encoded data"
                }
                else -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                }
            }

            val displayValue = value ?: default ?: ""
            setText(displayValue.toString())
        }

        inputLayout.addView(editText)
        wrapper.addView(inputLayout)

        val state = object : FieldState(key, value) {
            override fun getValue(): Any? {
                val text = editText.text?.toString() ?: ""
                if (text.isEmpty()) return ""
                return when (type) {
                    "int", "uint", "byte" -> text.toLongOrNull() ?: text
                    "num" -> text.toDoubleOrNull() ?: text
                    else -> text
                }
            }

            override fun setValue(v: Any?) {
                editText.setText(v?.toString() ?: "")
            }
        }
        wrapper.tag = state

        // Track changes for dirty indicator
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateDirtyIndicator(wrapper)
            }
        })

        return wrapper
    }

    private fun createBoolField(
        context: Context,
        key: String,
        value: Any?,
        isReadOnly: Boolean,
    ): View {
        val wrapper = createFieldWrapper(context, key, includeLabel = false)

        val switch = SwitchMaterial(context).apply {
            text = formatLabel(key)
            isChecked = value == true || value?.toString() == "true"
            isEnabled = !isReadOnly
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        wrapper.addView(switch)

        val state = object : FieldState(key, value == true || value?.toString() == "true") {
            override fun getValue(): Any = switch.isChecked
            override fun setValue(v: Any?) {
                switch.isChecked = v == true || v?.toString() == "true"
            }
        }
        wrapper.tag = state

        switch.setOnCheckedChangeListener { _, _ -> updateDirtyIndicator(wrapper) }

        return wrapper
    }

    private fun createEnumField(
        context: Context,
        key: String,
        type: String,
        value: Any?,
        isReadOnly: Boolean,
        enumLoader: ((String) -> Unit)?,
    ): View {
        val wrapper = createFieldWrapper(context, key)
        val enumName = type.removePrefix("enum_")

        val inputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val autoComplete = AutoCompleteTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isEnabled = !isReadOnly
            setText(value?.toString() ?: "")
            tag = "enum_input" // marker for population
        }

        inputLayout.addView(autoComplete)
        wrapper.addView(inputLayout)

        // Store enum mapping for value extraction
        val enumMap = mutableMapOf<String, Any>() // displayName -> value
        wrapper.setTag(R.id.tag_enum_map, enumMap)
        wrapper.setTag(R.id.tag_enum_name, enumName)

        val state = object : FieldState(key, value) {
            override fun getValue(): Any? {
                val text = autoComplete.text?.toString() ?: ""
                // Try to map display name back to numeric value
                return enumMap[text] ?: text.toLongOrNull() ?: text
            }

            override fun setValue(v: Any?) {
                // Try to find display name for this value
                val displayName = enumMap.entries.find { it.value.toString() == v?.toString() }?.key
                autoComplete.setText(displayName ?: v?.toString() ?: "", false)
            }
        }
        wrapper.tag = state

        // Request async enum loading
        enumLoader?.invoke(enumName)

        return wrapper
    }

    private fun createReferenceField(
        context: Context,
        key: String,
        type: String,
        value: Any?,
        isReadOnly: Boolean,
        refLoader: ((String) -> Unit)?,
    ): View {
        val wrapper = createFieldWrapper(context, key)
        val refType = type.removePrefix("#")

        val inputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val autoComplete = AutoCompleteTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isEnabled = !isReadOnly
            setText(value?.toString() ?: "")
            threshold = 1
            tag = "ref_input"
        }

        inputLayout.addView(autoComplete)
        wrapper.addView(inputLayout)

        wrapper.setTag(R.id.tag_ref_type, refType)

        val state = object : FieldState(key, value) {
            override fun getValue(): Any? = autoComplete.text?.toString() ?: ""
            override fun setValue(v: Any?) {
                autoComplete.setText(v?.toString() ?: "", false)
            }
        }
        wrapper.tag = state

        autoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateDirtyIndicator(wrapper)
            }
        })

        refLoader?.invoke(refType)

        return wrapper
    }

    private fun createQuantityField(
        context: Context,
        key: String,
        type: String,
        value: Any?,
        isReadOnly: Boolean,
    ): View {
        val unit = type.removePrefix("q_")
        val wrapper = createFieldWrapper(context, key)

        val inputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            suffixText = unit
        }

        val editText = TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            isEnabled = !isReadOnly
            setText(value?.toString() ?: "")
        }

        inputLayout.addView(editText)
        wrapper.addView(inputLayout)

        val state = object : FieldState(key, value) {
            override fun getValue(): Any? {
                val text = editText.text?.toString() ?: ""
                return if (text.isEmpty()) "" else text.toDoubleOrNull() ?: text
            }

            override fun setValue(v: Any?) {
                editText.setText(v?.toString() ?: "")
            }
        }
        wrapper.tag = state

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateDirtyIndicator(wrapper)
            }
        })

        return wrapper
    }

    private fun createBitfieldField(
        context: Context,
        key: String,
        value: Any?,
        isReadOnly: Boolean,
    ): View {
        val numValue = (value as? Number)?.toInt() ?: 0
        val wrapper = createFieldWrapper(context, key)

        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val buttons = mutableListOf<MaterialButton>()

        for (flag in OBJECT_FLAGS) {
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = flag.letter.toString()
                isCheckable = true
                isChecked = numValue and (1 shl flag.bit) != 0
                isEnabled = !isReadOnly
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                contentDescription = flag.name
            }
            buttons.add(btn)
            buttonsLayout.addView(btn)
        }

        wrapper.addView(buttonsLayout)

        val state = object : FieldState(key, numValue) {
            override fun getValue(): Any {
                var result = 0
                for ((i, btn) in buttons.withIndex()) {
                    if (btn.isChecked) {
                        result = result or (1 shl OBJECT_FLAGS[i].bit)
                    }
                }
                return result
            }

            override fun setValue(v: Any?) {
                val num = (v as? Number)?.toInt() ?: 0
                for ((i, btn) in buttons.withIndex()) {
                    btn.isChecked = num and (1 shl OBJECT_FLAGS[i].bit) != 0
                }
            }
        }
        wrapper.tag = state

        for (btn in buttons) {
            btn.addOnCheckedChangeListener { _, _ -> updateDirtyIndicator(wrapper) }
        }

        return wrapper
    }

    private fun createArrayField(
        context: Context,
        key: String,
        type: String,
        value: Any?,
        isReadOnly: Boolean,
    ): View {
        val baseType = type.removeSuffix("[]")
        val values = (value as? List<*>) ?: emptyList<Any>()
        val wrapper = createFieldWrapper(context, key)

        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            tag = "array_container"
        }

        // Track text fields for value extraction
        val itemFields = mutableListOf<TextInputEditText>()

        fun addArrayItem(v: Any?) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 4 }
            }

            val inputLayout = TextInputLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }

            val editText = TextInputEditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                inputType = when (baseType) {
                    "int", "uint", "byte" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    "num" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else -> InputType.TYPE_CLASS_TEXT
                }
                isEnabled = !isReadOnly
                setText(v?.toString() ?: "")
            }
            itemFields.add(editText)

            inputLayout.addView(editText)
            row.addView(inputLayout)

            if (!isReadOnly) {
                val removeBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "×"
                    minWidth = 0
                    minimumWidth = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = 4 }
                }
                removeBtn.setOnClickListener {
                    itemFields.remove(editText)
                    itemsContainer.removeView(row)
                    updateDirtyIndicator(wrapper)
                }
                row.addView(removeBtn)
            }

            itemsContainer.addView(row)
        }

        for (v in values) {
            addArrayItem(v)
        }

        wrapper.addView(itemsContainer)

        if (!isReadOnly) {
            val addBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+ Add"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            addBtn.setOnClickListener {
                addArrayItem("")
                updateDirtyIndicator(wrapper)
            }
            wrapper.addView(addBtn)
        }

        val state = object : FieldState(key, values) {
            override fun getValue(): Any {
                return itemFields.mapNotNull { field ->
                    val text = field.text?.toString() ?: ""
                    if (text.isEmpty()) null
                    else when (baseType) {
                        "int", "uint", "byte" -> text.toLongOrNull() ?: text
                        "num" -> text.toDoubleOrNull() ?: text
                        else -> text
                    }
                }
            }

            override fun setValue(v: Any?) {
                // Array fields are complex; full rebuild would be needed
                // For now, just track dirty state
            }
        }
        wrapper.tag = state

        return wrapper
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun createFieldWrapper(
        context: Context,
        key: String,
        includeLabel: Boolean = true,
    ): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (12 * context.resources.displayMetrics.density).toInt()
            }
        }

        if (includeLabel) {
            val label = TextView(context).apply {
                text = formatLabel(key)
                textSize = 12f
                tag = "field_label"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = (4 * context.resources.displayMetrics.density).toInt()
                }
            }
            wrapper.addView(label)
        }

        return wrapper
    }

    fun formatLabel(key: String): String {
        return key
            .replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun unwrapQuantity(value: Any?): Any? {
        if (value is Map<*, *> && value.containsKey("q")) {
            return value["q"]
        }
        return value
    }

    /**
     * Populate an enum dropdown after async loading.
     */
    fun populateEnumField(fieldView: View, options: Map<String, Any>, currentValue: Any?) {
        val autoComplete = fieldView.findViewWithTag<AutoCompleteTextView>("enum_input") ?: return
        val enumMap = fieldView.getTag(R.id.tag_enum_map) as? MutableMap<String, Any> ?: return

        enumMap.clear()
        enumMap.putAll(options)

        val displayNames = options.keys.toList()
        val adapter = ArrayAdapter(fieldView.context, android.R.layout.simple_dropdown_item_1line, displayNames)
        autoComplete.setAdapter(adapter)

        // Set current display name
        val currentDisplay = options.entries.find { it.value.toString() == currentValue?.toString() }?.key
        if (currentDisplay != null) {
            autoComplete.setText(currentDisplay, false)
        }
    }

    /**
     * Populate a reference field's suggestions after async loading.
     */
    fun populateReferenceField(fieldView: View, suggestions: List<String>) {
        val autoComplete = fieldView.findViewWithTag<AutoCompleteTextView>("ref_input") ?: return
        val adapter = ArrayAdapter(fieldView.context, android.R.layout.simple_dropdown_item_1line, suggestions)
        autoComplete.setAdapter(adapter)
    }
}

/**
 * Tracks original and current value for a form field.
 */
abstract class FieldState(
    val key: String,
    var originalValue: Any?,
) {
    abstract fun getValue(): Any?
    abstract fun setValue(v: Any?)

    fun isDirty(): Boolean {
        val current = getValue()
        return normalizeForComparison(current) != normalizeForComparison(originalValue)
    }

    private fun normalizeForComparison(value: Any?): String {
        if (value == null) return ""
        // Normalize numbers: 42.0 (Double) and 42 (Long) should both produce "42"
        if (value is Number) {
            val d = value.toDouble()
            return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        return value.toString()
    }
}
