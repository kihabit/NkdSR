package com.nkd.screenrecorder.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isGone
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.fragments.Resolution

class ResolutionAdapter(
    context: Context,
    private val items: List<Resolution>,
    var selectedPosition: Int,
    private val durationSec: Long
) : ArrayAdapter<Resolution>(context, 0, items) {

    override fun isEnabled(position: Int): Boolean {
        return items[position].isEnabled
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_spinner_item, parent, false)

        val text = view.findViewById<TextView>(android.R.id.text1)
        text.text = items[selectedPosition].label   // always show selected
        text.setTextColor(Color.WHITE)
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent, isDropdown = true)
    }

    private fun createView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
        isDropdown: Boolean
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_resolution_spinner, parent, false)

        val radio = view.findViewById<RadioButton>(R.id.radioButton)
        val label = view.findViewById<TextView>(R.id.textLabel)
        val size = view.findViewById<TextView>(R.id.textSize)

        val item = items[position]

        label.text = item.label
        radio.isChecked = position == selectedPosition

        if (!item.isEnabled) {
            label.setTextColor(Color.GRAY)
            size.setTextColor(Color.GRAY)
            size.isGone = true
            radio.isEnabled = false
        } else {
            val bitrate = getBitrateForResolution(item.height)
            val sizeMb = calculateSizeMB(bitrate, durationSec)

            size.text = context.getString(R.string.size_mb, "%.1f".format(sizeMb))

            label.setTextColor(Color.WHITE)
            size.setTextColor(Color.LTGRAY)
        }

        return view
    }

    fun updateSelection(position: Int) {
        selectedPosition = position
        notifyDataSetChanged()
    }

    fun getBitrateForResolution(height: Int): Int {
        return when (height) {
            240 -> 400_000      // 0.4 Mbps
            360 -> 800_000      // 0.8 Mbps
            480 -> 1_200_000    // 1.2 Mbps
            720 -> 2_500_000    // 2.5 Mbps
            1080 -> 5_000_000   // 5 Mbps
            else -> 1_000_000   // fallback
        }
    }

    fun calculateSizeMB(
        videoBitrate: Int,
        durationSec: Long,
        audioBitrate: Int = 128_000 // default 128 kbps
    ): Double {
        val totalBitrate = videoBitrate + audioBitrate
        val totalBits = totalBitrate.toDouble() * durationSec
        val totalBytes = totalBits / 8
        return totalBytes / (1024 * 1024)
    }
}