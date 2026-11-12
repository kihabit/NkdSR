package com.nkd.screenrecorder.utils

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.BottomSheetRecordingOptionsBinding
import com.nkd.screenrecorder.databinding.ItemRecordingOptionBinding
import com.nkd.screenrecorder.services.RecordScreenService
import com.nkd.screenrecorder.utils.VideoQualityPrefs.FrameRate
import com.nkd.screenrecorder.utils.VideoQualityPrefs.Quality

/**
 * "Video quality" / "Frame rate" / "Audio source" / "Audio quality" bottom sheets — one radio list.
 * Used by Home, Settings and the floating toolbox ([overlay] = shown from a service).
 *
 * Video quality sheet: every row has its own audio chip on the right ("Best ▾"); tapping it opens
 * the Good / Best / Excellent popup for THAT resolution. Picking a resolution does NOT close the
 * sheet (so the chip can be reached) — swipe down or tap outside to close. All other sheets
 * close on tap.
 */
object RecordingOptionsSheet {

    private class Option(
        val label: String,
        val subtitle: String?,
        val recommended: Boolean,
        val selected: Boolean,
        /** Not null = this row shows an audio chip for that video quality. */
        val quality: Quality? = null,
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
                    quality = q,
                    onPick = { VideoQualityPrefs.setQuality(context, q) }
                )
            }
        show(context, R.string.video_quality_sheet_title, options, overlay, dismissOnPick = false, onChanged = onChanged)
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
        show(context, R.string.frame_rate_sheet_title, options, overlay, onChanged = onChanged)
    }

    /**
     * Mute / Microphone. Picking Microphone without RECORD_AUDIO doesn't save it — it calls
     * [requestMicPermission] instead (the caller asks and saves Microphone, or Mute if denied).
     * Changed while recording → short note: it applies to the next recording.
     */
    fun showAudioSource(
        context: Context,
        overlay: Boolean = false,
        requestMicPermission: () -> Unit,
        onChanged: () -> Unit
    ) {
        val current = AudioSourcePrefs.source(context)
        val options = AudioSourcePrefs.Source.entries.map { s ->
            Option(
                label = AudioSourcePrefs.label(context, s),
                subtitle = AudioSourcePrefs.subtitle(context, s),
                recommended = false,
                selected = s == current,
                onPick = {
                    if (s != current &&
                        (RecordScreenService.isRecording || RecordScreenService.isPaused)
                    ) {
                        Toast.makeText(context, R.string.audio_source_next_recording, Toast.LENGTH_SHORT).show()
                    }
                    if (s == AudioSourcePrefs.Source.MICROPHONE && !AudioSourcePrefs.hasMicPermission(context)) {
                        requestMicPermission()
                    } else {
                        AudioSourcePrefs.setSource(context, s)
                    }
                }
            )
        }
        show(context, R.string.audio_source_sheet_title, options, overlay, onChanged = onChanged)
    }

    /**
     * Good (96) / Best (128, recommended) / Excellent (256 kbps) for ONE video quality.
     * Opened from the audio chip of that row; can also be used from Settings.
     */
    fun showAudioQuality(context: Context, quality: Quality, overlay: Boolean = false, onChanged: () -> Unit) {
        val current = AudioQualityPrefs.level(context, quality)
        val options = AudioQualityPrefs.Level.entries.map { l ->
            Option(
                label = AudioQualityPrefs.label(context, l),
                subtitle = AudioQualityPrefs.kbpsLabel(context, l),
                recommended = l == AudioQualityPrefs.Level.BEST,
                selected = l == current,
                onPick = {
                    if (l != current &&
                        (RecordScreenService.isRecording || RecordScreenService.isPaused)
                    ) {
                        Toast.makeText(context, R.string.audio_source_next_recording, Toast.LENGTH_SHORT).show()
                    }
                    AudioQualityPrefs.setLevel(context, quality, l)
                }
            )
        }
        show(context, R.string.audio_quality_sheet_title, options, overlay, onChanged = onChanged)
    }

    private fun show(
        context: Context,
        titleRes: Int,
        options: List<Option>,
        overlay: Boolean,
        dismissOnPick: Boolean = true,
        onChanged: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val sheet = BottomSheetRecordingOptionsBinding.inflate(inflater)
        sheet.title.setText(titleRes)

        // Video quality sheet: "Audio quality" on the SAME line as the title, right side.
        // The title is moved into a horizontal row together with the header (same text style).
        if (options.any { it.quality != null }) {
            val dp = context.resources.displayMetrics.density
            val titleView = sheet.title
            val parent = titleView.parent as? ViewGroup
            if (parent != null) {
                val index = parent.indexOfChild(titleView)
                val rowParams = titleView.layoutParams
                rowParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                val titleId = titleView.id
                parent.removeViewAt(index)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = true // both texts sit on the same baseline
                    id = titleId // other views that reference the title keep working
                }
                titleView.id = View.generateViewId()
                titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                val header = TextView(context).apply {
                    text = context.getString(R.string.audio_quality_sheet_title)
                    gravity = Gravity.END
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, titleView.textSize)
                    typeface = titleView.typeface
                    setTextColor(titleView.textColors)
                    setPadding(0, 0, (24 * dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                row.addView(titleView)
                row.addView(header)
                parent.addView(row, index, rowParams)
            }
        }

        val rows = mutableListOf<ItemRecordingOptionBinding>()
        options.forEach { option ->
            val row = ItemRecordingOptionBinding.inflate(inflater, sheet.options, false)
            rows.add(row)
            row.label.text = option.label
            row.radio.isChecked = option.selected
            row.tag.visibility = if (option.recommended) View.VISIBLE else View.GONE
            row.subtitle.visibility = if (option.subtitle != null) View.VISIBLE else View.GONE
            row.subtitle.text = option.subtitle

            val q = option.quality
            if (q != null) {
                row.audioChip.visibility = View.VISIBLE
                row.audioChip.text = AudioQualityPrefs.chipText(context, q)
                // Chip tap = audio quality popup of THIS row on top; the video sheet stays open.
                row.audioChip.setOnClickListener {
                    showAudioQuality(context, q, overlay) {
                        row.audioChip.text = AudioQualityPrefs.chipText(context, q)
                        onChanged()
                    }
                }
            } else {
                row.audioChip.visibility = View.GONE
            }

            row.root.setOnClickListener {
                option.onPick()
                onChanged()
                if (dismissOnPick) {
                    dialog.dismiss()
                } else {
                    rows.forEach { it.radio.isChecked = it === row }
                }
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