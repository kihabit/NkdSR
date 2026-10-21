package com.nkd.screenrecorder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.ItemCropRatioBinding
import com.nkd.screenrecorder.utils.AspectRatio

class CropRatioAdapter(
    private val onRatioSelected: (AspectRatio) -> Unit
) : RecyclerView.Adapter<CropRatioAdapter.RatioViewHolder>() {

    // All ratios shown in the selector bar (FREE = "Original" shown first)
    private val ratios = listOf(
        AspectRatio.FREE,
        AspectRatio.RATIO_1_1,
        AspectRatio.RATIO_4_5,
        AspectRatio.RATIO_9_16,
        AspectRatio.RATIO_16_9,
        AspectRatio.RATIO_5_4,
        AspectRatio.RATIO_3_4,
        AspectRatio.RATIO_4_3,
        AspectRatio.RATIO_2_3,
        AspectRatio.RATIO_3_2,
        AspectRatio.RATIO_1_2
    )

    private var selectedIndex = 0  // FREE selected by default

    inner class RatioViewHolder(
        private val binding: ItemCropRatioBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(ratio: AspectRatio, isSelected: Boolean) {
            binding.ratioLabel.text =
                if (ratio == AspectRatio.FREE) binding.root.context.getString(R.string.crop_free) else ratio.label

            // Dynamically size the icon rectangle to visually represent the ratio
            val iconSize = 32 // dp base
            val density = binding.root.context.resources.displayMetrics.density
            val basePx = (iconSize * density).toInt()

            val (iconW, iconH) = when {
                ratio == AspectRatio.FREE -> basePx to basePx
                ratio.widthRatio >= ratio.heightRatio -> {
                    // Landscape/square: fix width, shrink height
                    val h = (basePx * ratio.heightRatio / ratio.widthRatio).toInt()
                    basePx to h.coerceAtLeast((12 * density).toInt())
                }
                else -> {
                    // Portrait: fix height, shrink width
                    val w = (basePx * ratio.widthRatio / ratio.heightRatio).toInt()
                    w.coerceAtLeast((12 * density).toInt()) to basePx
                }
            }

            binding.ratioIconView.layoutParams =
                binding.ratioIconView.layoutParams.apply {
                    width = iconW
                    height = iconH
                }

            // Toggle selected / unselected drawable
            binding.ratioIconView.setBackgroundResource(
                if (isSelected) R.drawable.bg_ratio_icon_selected
                else R.drawable.bg_ratio_icon_unselected
            )

            // Label colour: white when selected, dim when not
            binding.ratioLabel.setTextColor(
                binding.root.context.getColor(
                    if (isSelected) android.R.color.white else android.R.color.darker_gray
                )
            )

            binding.root.setOnClickListener {
                val prev = selectedIndex
                selectedIndex = bindingAdapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedIndex)
                onRatioSelected(ratio)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RatioViewHolder {
        val binding = ItemCropRatioBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RatioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RatioViewHolder, position: Int) {
        holder.bind(ratios[position], position == selectedIndex)
    }

    override fun getItemCount() = ratios.size

    /** Call this to reset selection to FREE from outside (e.g. when hiding the bar). */
    fun resetSelection() {
        val prev = selectedIndex
        selectedIndex = 0
        notifyItemChanged(prev)
        notifyItemChanged(0)
    }
}