package com.nkd.screenrecorder.adapter

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.R

class VideoThumbnailAdapter(
    private val uriList: MutableList<Uri>,
    private val onItemClick: (Uri) -> Unit
) : RecyclerView.Adapter<VideoThumbnailAdapter.ThumbnailViewHolder>() {

    private var selectedPosition = 0

    inner class ThumbnailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val selectionBorder: View = itemView.findViewById(R.id.selectionBorder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_thumbnail, parent, false)
        return ThumbnailViewHolder(view)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        val uri = uriList[position]

        // Extract thumbnail from video using MediaMetadataRetriever
        val bitmap = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(holder.itemView.context, uri)
                retriever.getFrameAtTime(0) // first frame
            }
        }.getOrNull()

        holder.ivThumbnail.setImageBitmap(bitmap)

        // Highlight selected clip
        holder.selectionBorder.visibility =
            if (position == selectedPosition) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val previous = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(previous)
            notifyItemChanged(selectedPosition)
            onItemClick(uri)
        }
    }

    override fun getItemCount(): Int = uriList.size
}