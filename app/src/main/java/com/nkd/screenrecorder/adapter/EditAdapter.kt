package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.LayoutEditItemsBinding
import com.nkd.screenrecorder.fragments.EditModel

class EditAdapter(private val list: List<EditModel>) :
    RecyclerView.Adapter<EditAdapter.ViewHolder>() {

    var onItemClick: ((EditModel, Int) -> Unit)? = null

    inner class ViewHolder(private val binding: LayoutEditItemsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: EditModel): Unit {
            binding.apply {
                shapeableImageView.setImageDrawable(
                    AppCompatResources.getDrawable(
                        shapeableImageView.context,
                        item.imageDrawable
                    )
                )
                textView.text = item.title
                itemView.setOnClickListener {
                    onItemClick?.invoke(item, absoluteAdapterPosition)
                }
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): EditAdapter.ViewHolder {
        return ViewHolder(
            LayoutEditItemsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: EditAdapter.ViewHolder, position: Int) {
        holder.apply {
            bind(list[position])
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

}