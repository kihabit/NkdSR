package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.databinding.LayoutTitleItemBinding
import com.nkd.screenrecorder.databinding.LayoutVideoItemsBinding
import com.nkd.screenrecorder.model.MediaListItem
import com.nkd.screenrecorder.model.VideoItem
import com.nkd.screenrecorder.utils.Utils

class VideoListAdapter(private val list: List<MediaListItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var onItemClick: ((VideoItem) -> Unit)? = null
    var onMoreClick: ((VideoItem) -> Unit)? = null
    var onEditClick: ((VideoItem) -> Unit)? = null
    var onShareClick: ((VideoItem) -> Unit)? = null

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        if (viewType == 0) {
            return TitleViewHolder(
                LayoutTitleItemBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        } else {

            return ViewHolder(
                LayoutVideoItemsBinding.inflate(
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
                    bind(items.item as VideoItem)
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

    inner class ViewHolder(private val binding: LayoutVideoItemsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(videoItem: VideoItem) {
            binding.apply {
                videoSizeTextView.text = Utils.formatFileSize(videoItem.size)
                title.text = videoItem.name
                Glide.with(itemView.context)
                    .asBitmap()
                    .load(Utils.getVideoThumbnailUri(videoItem.uri))
                    .into(previewImage)
                videoViewTextView.text = Utils.formatDuration(videoItem.duration)
                editVideo.setOnClickListener {
                    onEditClick?.invoke(videoItem)
                }
                shareVideo.setOnClickListener {
                    onShareClick?.invoke(videoItem)
                }
                moreIcon.setOnClickListener {
                    onMoreClick?.invoke(videoItem)
                }
                itemView.setOnClickListener {
                    onItemClick?.invoke(videoItem)
                }
            }
        }
    }
}