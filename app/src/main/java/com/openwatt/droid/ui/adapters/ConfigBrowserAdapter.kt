package com.openwatt.droid.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.databinding.ItemConfigGroupBinding
import com.openwatt.droid.databinding.ItemConfigLeafBinding
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

        fun bind(group: BrowserItem.Group) {
            binding.groupIcon.text = group.icon
            binding.groupName.text = group.displayName
            binding.groupCount.text = "${group.childCount}"
            binding.expandArrow.text = if (group.expanded) "▾" else "▸"

            binding.root.setOnClickListener {
                onGroupClick(group.name)
            }
        }
    }

    inner class LeafViewHolder(
        private val binding: ItemConfigLeafBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(leaf: BrowserItem.Leaf) {
            binding.leafIcon.text = leaf.icon
            binding.leafName.text = leaf.displayName

            binding.root.setOnClickListener {
                onLeafClick(leaf.collectionName)
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_LEAF = 1
    }
}

private class BrowserDiffCallback : DiffUtil.ItemCallback<BrowserItem>() {
    override fun areItemsTheSame(oldItem: BrowserItem, newItem: BrowserItem): Boolean {
        return when {
            oldItem is BrowserItem.Group && newItem is BrowserItem.Group ->
                oldItem.name == newItem.name
            oldItem is BrowserItem.Leaf && newItem is BrowserItem.Leaf ->
                oldItem.collectionName == newItem.collectionName
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: BrowserItem, newItem: BrowserItem): Boolean {
        return oldItem == newItem
    }
}
