package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.nkd.screenrecorder.databinding.ItemImagePreviewBinding
import com.nkd.screenrecorder.model.GifItem
import com.nkd.screenrecorder.model.ImageItem
import com.nkd.screenrecorder.model.MediaItem

class ImagePreviewAdapter(
    private val images: List<MediaItem>,
    private val onImageClick: () -> Unit
) : RecyclerView.Adapter<ImagePreviewAdapter.PreviewViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
        val binding = ItemImagePreviewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PreviewViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
        holder.bind(images[position])
    }

    override fun getItemCount(): Int = images.size

    inner class PreviewViewHolder(
        private val binding: ItemImagePreviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(image: MediaItem) {
            if (image is ImageItem) {
                Glide.with(binding.root.context)
                    .asBitmap()
                    .load(image.uri)
                    .fitCenter()
                    .into(binding.photoView)
            } else if (image is GifItem) {

                if (image.filePath.isNotEmpty()) {
                    Glide.with(itemView.context)
                        .asGif()
                        .load(image.filePath)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .dontTransform()
                        .signature(ObjectKey(System.currentTimeMillis()))
                        .into(binding.photoView)
                } else {
                    Glide.with(itemView.context)
                        .load(image.uri)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .dontTransform()
                        .signature(ObjectKey(System.currentTimeMillis()))
                        .into(binding.photoView)
                }

            }
            binding.photoView.setOnClickListener {
                onImageClick()
            }
        }
    }
}