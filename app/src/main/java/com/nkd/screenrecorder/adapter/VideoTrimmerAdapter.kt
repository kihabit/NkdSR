package com.nkd.screenrecorder.adapter

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.VideoThumbItemLayoutBinding
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.dpToPx


class VideoTrimmerAdapter(private val mBitmaps: ArrayList<Bitmap>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(itemWidth, itemHeight)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return ViewHolder(imageView)
        /* return TrimmerViewHolder(
             VideoThumbItemLayoutBinding.inflate(
                 LayoutInflater.from(parent.context),
                 parent,
                 false
             )
         )*/
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
//        (holder as TrimmerViewHolder).binding.thumb.setImageBitmap(mBitmaps[position])
        (holder as ViewHolder).imageView.layoutParams =
            ViewGroup.LayoutParams(itemWidth, itemHeight)
        holder.imageView.setImageBitmap(mBitmaps[position])
    }

    override fun getItemCount(): Int {
        return mBitmaps.size
    }

    private var itemWidth: Int = 80.dpToPx() // fallback square size
    private var itemHeight: Int = 80.dpToPx()

    fun setItemSize(width: Int, height: Int) {
        if (width > 0) itemWidth = width
        if (height > 0) itemHeight = height
    }

    fun addBitmaps(bitmap: Bitmap) {
        mBitmaps.add(bitmap)
        notifyItemInserted(mBitmaps.size - 1)
    }

    fun clearBitmaps() {
        mBitmaps.clear()
        notifyDataSetChanged()
    }

    inner class TrimmerViewHolder(val binding: VideoThumbItemLayoutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            val layoutParams = binding.thumb.layoutParams as LinearLayout.LayoutParams
            layoutParams.width = VideoTrimmerUtil.THUMB_WIDTH
            binding.thumb.setLayoutParams(layoutParams)
        }
    }

    inner class ViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}