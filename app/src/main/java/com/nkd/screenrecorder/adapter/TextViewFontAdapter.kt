package com.nkd.screenrecorder.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.LayoutTextviewFontBinding
import com.nkd.screenrecorder.utils.TextFontModel

class TextViewFontAdapter(val list: List<TextFontModel>) :
    RecyclerView.Adapter<TextViewFontAdapter.ViewHolder>() {

    var onItemClick: ((TextFontModel) -> Unit)? = null

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        return ViewHolder(
            LayoutTextviewFontBinding.inflate(
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
        onItemClick?.invoke(list[0])
    }

    override fun getItemCount(): Int {
        return list.size
    }

    inner class ViewHolder(val binding: LayoutTextviewFontBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(data: TextFontModel) {
            binding.textview.text = data.name
            binding.textview.typeface = ResourcesCompat.getFont(binding.root.context, data.font)
            Log.e("Adapter", "called on position $absoluteAdapterPosition")
            itemView.setOnClickListener {
                onItemClick?.invoke(data)
            }
        }
    }
}