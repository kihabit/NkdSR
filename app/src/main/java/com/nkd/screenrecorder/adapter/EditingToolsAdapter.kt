package com.nkd.screenrecorder.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.databinding.RowEditingToolsBinding
import com.nkd.screenrecorder.utils.ToolType

class EditingToolsAdapter(
    private val mToolList: MutableList<ToolModel>,
    private val onToolSelected: ((toolType: ToolType) -> Unit)
) : RecyclerView.Adapter<EditingToolsAdapter.ViewHolder>() {

    class ToolModel(
        val mToolName: String,
        val mToolIcon: Int,
        val mToolType: ToolType
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            RowEditingToolsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mToolList[position]
        holder.apply {
            bind(item)
        }

    }

    override fun getItemCount(): Int {
        return mToolList.size
    }

    inner class ViewHolder(val binding: RowEditingToolsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ToolModel) {
            Log.e("editingtolsadapter", model.mToolName)
            binding.apply {
                txtTool.text = model.mToolName
                imgToolIcon.setImageResource(model.mToolIcon)
            }
            itemView.setOnClickListener { _: View? ->
                onToolSelected(
                    mToolList[layoutPosition].mToolType
                )
            }
        }
    }
}