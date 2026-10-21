package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.LayoutVideoOptionsBinding
import com.nkd.screenrecorder.fragments.VideoEditOption

class VideoEditOptionAdapter(val list: List<VideoEditOption>) :
    RecyclerView.Adapter<VideoEditOptionAdapter.ViewHolder>() {
    var onItemClick: ((position: Int) -> Unit)? = null

    inner class ViewHolder(val binding: LayoutVideoOptionsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(data: VideoEditOption) {
            binding.apply {
                title.text = data.name
                optionIcon.setImageResource(data.icon)
            }
            itemView.setOnClickListener {
                onItemClick?.invoke(absoluteAdapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        return ViewHolder(
            LayoutVideoOptionsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        list[position].let {
            holder.bind(it)
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }
}