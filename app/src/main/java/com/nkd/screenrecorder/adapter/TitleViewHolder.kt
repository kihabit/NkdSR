package com.nkd.screenrecorder.adapter

import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.LayoutTitleItemBinding
import com.nkd.screenrecorder.model.MediaListItem

class TitleViewHolder(private val binding: LayoutTitleItemBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(items: MediaListItem.Header): Unit {
        binding.titleTextview.text = items.title
    }
}