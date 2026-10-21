package com.nkd.screenrecorder.utils

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.DialogStorageDetailsBinding
import com.nkd.screenrecorder.databinding.ItemStorageDetailRowBinding

/**
 * "Storage Details" popup shown when the storage pill at the top of a home tab is tapped.
 * Numbers use the same [Utils.formatFileSizeToGB] as the pill, so both always match.
 */
object StorageDetailsDialog {

    /** Low space: less than 10% of the total, or less than 2 GB, is available. */
    private const val LOW_FREE_PERCENT = 10
    private const val LOW_FREE_BYTES = 2L * 1024 * 1024 * 1024

    private const val RING_ANIM_MS = 600L

    fun show(context: Context, total: Long, used: Long, free: Long) {
        val binding = DialogStorageDetailsBinding.inflate(LayoutInflater.from(context))

        val usedPercentage = if (total > 0) {
            ((used.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else 0
        val isLow = total > 0 && (free * 100 < total * LOW_FREE_PERCENT || free < LOW_FREE_BYTES)

        val primary = ContextCompat.getColor(context, R.color.primaryColor)
        val low = ContextCompat.getColor(context, R.color.storage_low)

        binding.tvPercent.text = context.getString(R.string.storage_percent_value, usedPercentage)
        binding.ring.setIndicatorColor(if (isLow) low else primary)

        bindRow(
            binding.rowUsed, R.string.storage_label_used, Utils.formatFileSizeToGB(used),
            dotColor = primary
        )
        bindRow(
            binding.rowAvailable, R.string.storage_label_available, Utils.formatFileSizeToGB(free),
            dotColor = ContextCompat.getColor(context, R.color.storage_dot_available),
            valueColor = if (isLow) low else null
        )
        bindRow(
            binding.rowTotal, R.string.storage_label_total, Utils.formatFileSizeToGB(total),
            dotColor = ContextCompat.getColor(context, R.color.storage_dot_total)
        )
        binding.tvLowWarning.visibility = if (isLow) android.view.View.VISIBLE else android.view.View.GONE

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()
        binding.btnOk.setOnClickListener { dialog.dismiss() }
        binding.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            // Ring fills from 0 to the used percentage when the popup opens
            ObjectAnimator.ofInt(binding.ring, "progress", 0, usedPercentage).apply {
                duration = RING_ANIM_MS
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        dialog.show()

        // The card draws its own rounded background; keep the default dim behind it.
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val dm = context.resources.displayMetrics
            val maxWidth = context.resources.getDimensionPixelSize(R.dimen.storage_dialog_max_width)
            setLayout(
                minOf((dm.widthPixels * 0.9f).toInt(), maxWidth),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun bindRow(
        row: ItemStorageDetailRowBinding,
        labelRes: Int,
        sizeGb: String,
        dotColor: Int,
        valueColor: Int? = null
    ) {
        val context = row.root.context
        row.label.setText(labelRes)
        row.value.text = context.getString(R.string.storage_gb_value, sizeGb)
        row.dot.backgroundTintList = ColorStateList.valueOf(dotColor)
        if (valueColor != null) row.value.setTextColor(valueColor)
    }
}
