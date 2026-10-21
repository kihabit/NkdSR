package com.nkd.screenrecorder.utils

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.BottomSheetRecordingOptionsBinding
import com.nkd.screenrecorder.databinding.ItemRecordingOptionBinding
import com.nkd.screenrecorder.utils.VideoQualityPrefs.FrameRate
import com.nkd.screenrecorder.utils.VideoQualityPrefs.Quality

/**
 * "Video quality" / "Frame rate" bottom sheets — one radio list, tap = save + close.
 * Used by Home, Settings and the floating toolbox ([overlay] = shown from a service).
 */
object RecordingOptionsSheet {

    private class Option(
        val label: String,
        val subtitle: String?,
        val recommended: Boolean,
        val selected: Boolean,
        val onPick: () -> Unit
    )

    fun showVideoQuality(context: Context, overlay: Boolean = false, onChanged: () -> Unit) {
        val current = VideoQualityPrefs.quality(context)
        val options = Quality.entries
            .filter { it != Quality.P1080 || VideoQualityPrefs.is1080pAvailable(context) }
            .map { q ->
                Option(
                    label = VideoQualityPrefs.qualityLabel(context, q),
                    subtitle = if (q == Quality.AUTO) {
                        context.getString(R.string.auto_for_this_phone, VideoQualityPrefs.autoQualityLabel(context))
                    } else null,
                    recommended = q == Quality.AUTO,
                    selected = q == current,
                    onPick = { VideoQualityPrefs.setQuality(context, q) }
                )
            }
        show(context, R.string.video_quality_sheet_title, options, overlay, onChanged)
    }

    fun showFrameRate(context: Context, overlay: Boolean = false, onChanged: () -> Unit) {
        val current = VideoQualityPrefs.frameRate(context)
        val options = FrameRate.entries
            .filter { it != FrameRate.FPS60 || VideoQualityPrefs.is60FpsAvailable(context) }
            .map { fr ->
                Option(
                    label = VideoQualityPrefs.frameRateLabel(context, fr),
                    subtitle = if (fr == FrameRate.AUTO) {
                        context.getString(
                            R.string.auto_for_this_phone,
                            context.getString(R.string.fps_value, VideoQualityPrefs.autoFps(context))
                        )
                    } else null,
                    recommended = fr == FrameRate.AUTO,
                    selected = fr == current,
                    onPick = { VideoQualityPrefs.setFrameRate(context, fr) }
                )
            }
        show(context, R.string.frame_rate_sheet_title, options, overlay, onChanged)
    }

    private fun show(
        context: Context,
        titleRes: Int,
        options: List<Option>,
        overlay: Boolean,
        onChanged: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val sheet = BottomSheetRecordingOptionsBinding.inflate(inflater)
        sheet.title.setText(titleRes)

        options.forEach { option ->
            val row = ItemRecordingOptionBinding.inflate(inflater, sheet.options, false)
            row.label.text = option.label
            row.radio.isChecked = option.selected
            row.tag.visibility = if (option.recommended) View.VISIBLE else View.GONE
            row.subtitle.visibility = if (option.subtitle != null) View.VISIBLE else View.GONE
            row.subtitle.text = option.subtitle
            row.root.setOnClickListener {
                option.onPick()
                onChanged()
                dialog.dismiss()
            }
            sheet.options.addView(row.root)
        }

        dialog.setContentView(sheet.root)
        if (overlay) {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            )
        }
        dialog.show()
    }
}
