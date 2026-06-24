package com.openwatt.droid.ui.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.databinding.ItemConfigGroupBinding
import com.openwatt.droid.databinding.ItemConfigLeafBinding
import com.openwatt.droid.util.CollectionIcons
import com.openwatt.droid.viewmodel.BrowserItem

class ConfigBrowserAdapter(
    private val onGroupClick: (String) -> Unit,
    private val onLeafClick: (collectionName: String) -> Unit,
) : ListAdapter<BrowserItem, RecyclerView.ViewHolder>(BrowserDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is BrowserItem.Group -> VIEW_TYPE_GROUP
            is BrowserItem.Leaf -> VIEW_TYPE_LEAF
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_GROUP -> GroupViewHolder(
                ItemConfigGroupBinding.inflate(inflater, parent, false)
            )
            else -> LeafViewHolder(
                ItemConfigLeafBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowserItem.Group -> (holder as GroupViewHolder).bind(item)
            is BrowserItem.Leaf -> (holder as LeafViewHolder).bind(item)
        }
    }

    inner class GroupViewHolder(
        private val binding: ItemConfigGroupBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        // Captured before any bind mutates the icon, so badge rendering can be
        // reverted when this holder is recycled to show a plain emoji.
        private val defaultIconColors = binding.groupIcon.textColors
        private val defaultIconTypeface = binding.groupIcon.typeface
        private val defaultIconTextSize = binding.groupIcon.textSize

        fun bind(group: BrowserItem.Group) {
            bindCollectionIcon(binding.groupIcon, group.icon, defaultIconColors, defaultIconTypeface, defaultIconTextSize)
            binding.groupName.text = group.displayName
            binding.groupCount.text = "${group.childCount}"
            binding.expandArrow.text = if (group.expanded) "▾" else "▸"

            // Indent based on depth (base 16dp + depth * 16dp)
            val basePx = dpToPx(binding.root.context, 16 + group.depth * 16)
            binding.root.setPadding(basePx, binding.root.paddingTop, binding.root.paddingRight, binding.root.paddingBottom)

            binding.root.setOnClickListener {
                onGroupClick(group.key)
            }
        }
    }

    inner class LeafViewHolder(
        private val binding: ItemConfigLeafBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        // Captured before any bind mutates the icon, so badge rendering can be
        // reverted when this holder is recycled to show a plain emoji.
        private val defaultIconColors = binding.leafIcon.textColors
        private val defaultIconTypeface = binding.leafIcon.typeface
        private val defaultIconTextSize = binding.leafIcon.textSize

        fun bind(leaf: BrowserItem.Leaf) {
            bindCollectionIcon(binding.leafIcon, leaf.icon, defaultIconColors, defaultIconTypeface, defaultIconTextSize)
            binding.leafName.text = leaf.displayName

            // Match the group's indent formula so leaves at the same depth
            // align with group headers, rather than being pushed an extra
            // level in as if they lived inside an invisible folder.
            val basePx = dpToPx(binding.root.context, 16 + leaf.depth * 16)
            binding.root.setPadding(basePx, binding.root.paddingTop, binding.root.paddingRight, binding.root.paddingBottom)

            binding.root.setOnClickListener {
                onLeafClick(leaf.collectionName)
            }
        }
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_LEAF = 1
    }
}

/**
 * Render a collection icon into [view]. Most icons are plain emoji, but the
 * [CollectionIcons.BADGE_PREFIX] sentinel (`badge:<letter>:<#hexColor>`) is drawn
 * as a coloured circular badge — mirroring the web app's Zigbee/BLE logos, which
 * can't be expressed as a single emoji.
 *
 * Icon TextViews are recycled, so the non-badge branch restores everything the
 * badge branch changes ([defaultColors], [defaultTypeface], [defaultTextSize],
 * background, padding).
 */
private fun bindCollectionIcon(
    view: TextView,
    icon: String,
    defaultColors: ColorStateList,
    defaultTypeface: Typeface?,
    defaultTextSize: Float,
) {
    if (icon.startsWith(CollectionIcons.BADGE_PREFIX)) {
        val body = icon.removePrefix(CollectionIcons.BADGE_PREFIX)
        val sep = body.lastIndexOf(':')
        val letter = if (sep >= 0) body.substring(0, sep) else body
        val colorHex = if (sep >= 0) body.substring(sep + 1) else "#888888"

        // A small filled circle, sized to roughly the emoji glyph it sits among
        // (the web draws the badge the same size as neighbouring icons), centred
        // in the fixed-size icon box by symmetric padding. The "Z" is ASCII and
        // drawn small and bold; the BLE rune is non-ASCII and drawn larger at
        // normal weight — matching the web's `.icon-badge` / `.icon-bt` rules.
        val isRune = letter.isNotEmpty() && letter[0].code > 0x7F
        val diameter = defaultTextSize // px, ~= an emoji glyph
        val boxPx = view.layoutParams?.width ?: 0
        val pad = if (boxPx > diameter) ((boxPx - diameter) / 2f).toInt() else 0

        view.text = letter
        view.setTextColor(Color.WHITE)
        view.setTypeface(defaultTypeface, if (isRune) Typeface.NORMAL else Typeface.BOLD)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, diameter * if (isRune) 0.9f else 0.62f)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.DKGRAY))
        }
        view.setPadding(pad, pad, pad, pad)
    } else {
        view.text = icon
        view.background = null
        view.setPadding(0, 0, 0, 0)
        view.setTextColor(defaultColors)
        view.setTypeface(defaultTypeface)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSize)
    }
}

private class BrowserDiffCallback : DiffUtil.ItemCallback<BrowserItem>() {
    override fun areItemsTheSame(oldItem: BrowserItem, newItem: BrowserItem): Boolean {
        return when {
            oldItem is BrowserItem.Group && newItem is BrowserItem.Group ->
                oldItem.key == newItem.key
            oldItem is BrowserItem.Leaf && newItem is BrowserItem.Leaf ->
                oldItem.collectionName == newItem.collectionName
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: BrowserItem, newItem: BrowserItem): Boolean {
        return oldItem == newItem
    }
}
