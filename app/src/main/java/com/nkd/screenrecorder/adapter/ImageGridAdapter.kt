package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.databinding.LayoutImageItemsBinding
import com.nkd.screenrecorder.model.ImageItem

class ImageGridAdapter(
    private var list: List<ImageItem>,
    private val onItemClick: (ImageItem) -> Unit,
    private val onItemLongClick: (ImageItem) -> Unit
) :
    RecyclerView.Adapter<ImageGridAdapter.ViewHolder>() {
    private var selectionMode = false
    private var selectedItems = setOf<Long>()

    fun updateItems(newItems: List<ImageItem>) {
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
    ): ViewHolder {
        return ViewHolder(
            LayoutImageItemsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(
        holder: ImageGridAdapter.ViewHolder,
        position: Int
    ) {
        holder.apply {
            bind(list[position])
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }


    inner class ViewHolder(private val binding: LayoutImageItemsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(imageItem: ImageItem): Unit {
            binding.apply {
                Glide.with(itemView.context)
                    .asBitmap()
                    .thumbnail(0.1f)
                    .centerCrop()
                    .load(imageItem.uri)
                    .into(imageView)
                // Show/hide checkbox and overlay based on selection mode
                val isSelected = selectedItems.contains(imageItem.id)
                binding.checkboxOverlay.isVisible = selectionMode
                binding.checkbox.isVisible = selectionMode
                binding.checkbox.isChecked = isSelected

                // Show selection overlay
                binding.selectionOverlay.isVisible = selectionMode && isSelected

                binding.root.setOnClickListener {
                    if (selectionMode) {
                        onItemClick(imageItem)
                    } else {
                        // Normal click - you can open image viewer here
                        onItemClick(imageItem)
                    }
                }

                binding.root.setOnLongClickListener {
                    onItemLongClick(imageItem)
                    true
                }
            }
        }
    }
}