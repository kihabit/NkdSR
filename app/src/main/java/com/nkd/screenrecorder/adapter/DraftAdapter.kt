package com.nkd.screenrecorder.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.ItemDraftBinding
import com.nkd.screenrecorder.utils.DraftEntry
import com.nkd.screenrecorder.utils.convertToDurationForVideo

class DraftAdapter(private val drafts: List<DraftEntry>) :
    RecyclerView.Adapter<DraftAdapter.DraftViewHolder>() {

    var onItemClick: ((DraftEntry) -> Unit)? = null
    var onItemLongClick: ((DraftEntry) -> Unit)? = null

    inner class DraftViewHolder(val binding: ItemDraftBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftViewHolder {
        val binding = ItemDraftBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DraftViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
        val entry = drafts[position]

        holder.binding.apply {
            draftName.text = entry.displayName

            draftTimestamp.text = DateUtils.getRelativeTimeSpanString(
                entry.lastEditTimestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            draftDuration.text = convertToDurationForVideo(0, entry.durationMs.toInt())

            if (entry.thumbnailPath.isNotEmpty()) {
                Glide.with(draftThumbnail.context)
                    .load(entry.thumbnailPath)
                    .placeholder(R.drawable.ic_video_icon)
                    .error(R.drawable.ic_video_icon)
                    .into(draftThumbnail)
            } else {
                draftThumbnail.setImageResource(R.drawable.ic_video_icon)
            }

            root.setOnClickListener {
                onItemClick?.invoke(entry)
            }

            root.setOnLongClickListener {
                onItemLongClick?.invoke(entry)
                true
            }
        }
    }

    override fun getItemCount(): Int = drafts.size
}