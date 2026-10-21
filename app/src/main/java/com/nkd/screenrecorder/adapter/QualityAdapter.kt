package com.nkd.screenrecorder.adapter

import com.nkd.screenrecorder.R
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.nkd.screenrecorder.fragments.VideoQuality

class QualityAdapter(
    context: Context,
    private val items: List<VideoQuality>,
    defaultPosition: Int = 1 // MEDIUM default
) : ArrayAdapter<VideoQuality>(context, 0, items) {

    var selectedPosition: Int = defaultPosition
        private set

    // Translated quality name (VideoQuality.label stays the English id)
    private fun labelOf(q: VideoQuality): String = context.getString(
        when (q) {
            VideoQuality.LOW -> R.string.quality_low
            VideoQuality.MEDIUM -> R.string.quality_medium
            VideoQuality.HIGH -> R.string.quality_high
        }
    )

    fun updateSelection(position: Int) {
        selectedPosition = position
        notifyDataSetChanged()
    }

    // ✅ Selected view (closed spinner)
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_spinner_item, parent, false)

        val text = view.findViewById<TextView>(android.R.id.text1)
        text.text = labelOf(items[selectedPosition])
        text.setTextColor(Color.WHITE)

        return view
    }

    // ✅ Dropdown view (dialog list)
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {

        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_spinner_dropdown_item, parent, false)

        val text = view.findViewById<TextView>(android.R.id.text1)

        val item = items[position]

        text.text = labelOf(item)

        // Highlight selected item
        if (position == selectedPosition) {
            text.setTypeface(null, Typeface.BOLD)
        } else {
            text.setTypeface(null, Typeface.NORMAL)
        }

        return view
    }
}