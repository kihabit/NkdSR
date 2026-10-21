package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.databinding.LayoutStickerViewBinding

class StickerViewAdapter(private val list: List<Int>, val onItemClick: ((Int) -> Unit)) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class ViewHolder(private val binding: LayoutStickerViewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(data: Int): Unit {
            binding.apply {
                itemView.setOnClickListener {
                    onItemClick.invoke(absoluteAdapterPosition)
                }
                Glide.with(stickerView)
                    .asBitmap()
                    .centerInside()
                    .load(data)
                    .into(stickerView)
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return ViewHolder(
            LayoutStickerViewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        list[position].let {
            (holder as ViewHolder).bind(it)
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }
}