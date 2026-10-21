package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.databinding.LayoutImageItemsBinding
import com.nkd.screenrecorder.databinding.LayoutTitleItemBinding
import com.nkd.screenrecorder.model.GifItem
import com.nkd.screenrecorder.model.MediaListItem

class GIFGridAdapter(var list: List<MediaListItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onItemClick: ((GifItem, Int) -> Unit)? = null

    var onLongClick: ((GifItem, Int) -> Unit)? = null
    private var selectionMode = false
    private var selectedItems = setOf<Long>()

    fun updateItems(newItems: List<MediaListItem>) {
        list = newItems
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        notifyDataSetChanged()
    }

    fun setSelectedItems(selected: Set<Long>) {
        selectedItems = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            TitleViewHolder(
                LayoutTitleItemBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        } else {
            ViewHolder(
                LayoutImageItemsBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        when (val items = list[position]) {
            is MediaListItem.Media -> {
                (holder as ViewHolder).apply {
                    bind(items.item as GifItem)
                }
            }

            is MediaListItem.Header -> {
                (holder as TitleViewHolder).apply {
                    bind(items)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    override fun getItemViewType(position: Int): Int {
        return when (list[position]) {
            is MediaListItem.Header -> 0
            is MediaListItem.Media -> 1
        }
    }

    inner class ViewHolder(private val binding: LayoutImageItemsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(imageItem: GifItem) {
            binding.apply {
                Glide.with(itemView.context)
                    .asGif()
                    .thumbnail(0.1f)
                    .centerCrop()
                    .load(imageItem.uri)
                    .into(imageView)

                val isSelected = selectedItems.contains(imageItem.id)
                binding.checkboxOverlay.isVisible = selectionMode
                binding.checkbox.isVisible = selectionMode
                binding.checkbox.isChecked = isSelected

                // Show selection overlay
                binding.selectionOverlay.isVisible = selectionMode && isSelected

                root.setOnClickListener {
                    if (selectionMode) {
                        onItemClick?.invoke(imageItem, absoluteAdapterPosition)
                    } else {
                        // Normal click - you can open image viewer here
                        onItemClick?.invoke(imageItem, absoluteAdapterPosition)
                    }
                }
                root.setOnLongClickListener {
                    onLongClick?.invoke(imageItem, absoluteAdapterPosition)
                    true
                }
            }
        }
    }
}