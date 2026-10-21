package com.nkd.screenrecorder.adapter

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MusicPickerActivity
import com.nkd.screenrecorder.databinding.LayoutMusicListBinding

class MusicListAdapter(list: List<MusicPickerActivity.MusicItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<MusicPickerActivity.MusicItem> = list

    /** "Use" tapped on the selected row — adds the song to the timeline. */
    var onItemCLick: ((MusicPickerActivity.MusicItem) -> Unit)? = null

    /** Row (or play/pause icon) tapped — start / pause / switch the preview. */
    var onRowClick: ((position: Int, item: MusicPickerActivity.MusicItem) -> Unit)? = null

    var selectedPosition = RecyclerView.NO_POSITION
        private set
    var isPlaying = false
        private set

    /** Replaces the shown list (search filtering) and clears the selection. */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<MusicPickerActivity.MusicItem>) {
        items = newItems
        selectedPosition = RecyclerView.NO_POSITION
        isPlaying = false
        notifyDataSetChanged()
    }

    /** Marks [position] as the selected row and whether its preview is playing. */
    fun setSelection(position: Int, playing: Boolean) {
        val old = selectedPosition
        selectedPosition = position
        isPlaying = playing
        if (old != RecyclerView.NO_POSITION && old != position) notifyItemChanged(old)
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
    }

    inner class ViewHolder(private val binding: LayoutMusicListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(musicItem: MusicPickerActivity.MusicItem) {
            binding.apply {
                bindArtwork(albumImage, musicItem)
                albumTitle.text = musicItem.title
                albumSubTitle.text = subtitleFor(musicItem)

                val selected = absoluteAdapterPosition == selectedPosition
                root.setBackgroundColor(if (selected) 0x33FF7700 else Color.TRANSPARENT)
                addMusicButton.setImageResource(
                    if (selected && isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                useButton.isVisible = selected

                root.setOnClickListener {
                    onRowClick?.invoke(absoluteAdapterPosition, musicItem)
                }
                addMusicButton.setOnClickListener {
                    onRowClick?.invoke(absoluteAdapterPosition, musicItem)
                }
                useButton.setOnClickListener {
                    onItemCLick?.invoke(musicItem)
                }
            }
        }
    }

    /** Music-note icon by default; swapped for the rounded album art if it loads. */
    private fun bindArtwork(view: ImageView, item: MusicPickerActivity.MusicItem) {
        val density = view.resources.displayMetrics.density
        val notePadding = (10 * density).toInt()
        fun showNote() {
            view.setPadding(notePadding, notePadding, notePadding, notePadding)
            view.scaleType = ImageView.ScaleType.CENTER_INSIDE
            view.setImageResource(R.drawable.ic_music_note)
        }
        showNote()
        Glide.with(view)
            .load(item.artworkUri)
            .transform(CenterCrop(), RoundedCorners((8 * density).toInt()))
            .into(object : CustomViewTarget<ImageView, Drawable>(view) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    view.setPadding(0, 0, 0, 0)
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageDrawable(resource)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) = showNote()
                override fun onResourceCleared(placeholder: Drawable?) = showNote()
            })
    }

    /** "artist / m:ss", or only "m:ss" when the artist is missing or "<unknown>". */
    private fun subtitleFor(item: MusicPickerActivity.MusicItem): String {
        val time = formatMinSec(item.duration)
        val artist = item.artist.trim()
        return if (artist.isEmpty() || artist.equals("<unknown>", ignoreCase = true)) time
        else "$artist / $time"
    }

    private fun formatMinSec(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup, viewType: Int
    ): RecyclerView.ViewHolder {

        return ViewHolder(
            LayoutMusicListBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int
    ) {
        (holder as ViewHolder).bind(items[position])
    }

    override fun getItemCount(): Int {
        return items.size
    }
}
