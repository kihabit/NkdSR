package com.nkd.screenrecorder.utils

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import com.nkd.screenrecorder.adapter.CropRatioAdapter
import com.nkd.screenrecorder.databinding.ActivityEditVideoBinding

/**
 * Drop-in helper that wires the crop ratio selector bar to the CropOverlayView.
 *
 * Usage inside your Activity:
 *
 *   private lateinit var cropRatioHelper: CropRatioSelectorHelper
 *
 *   override fun onCreate(...) {
 *       ...
 *       cropRatioHelper = CropRatioSelectorHelper(binding)
 *   }
 *
 *   // When the user taps the Crop feature button:
 *   cropRatioHelper.show()
 *
 *   // When the user confirms crop (✓) or exits crop mode:
 *   cropRatioHelper.hide()
 */
class CropRatioSelectorHelper(private val binding: ActivityEditVideoBinding) {

    private val ratioAdapter = CropRatioAdapter { ratio ->
        binding.cropView.aspectRatio = ratio
    }

    init {
        binding.cropRatioSelector.ratioRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = ratioAdapter
        }
    }

    /** Slide the ratio bar up into view. Call when entering crop mode. */
    fun show() {
        val bar = binding.cropRatioSelector.root
        if (bar.visibility == View.VISIBLE) return

        bar.visibility = View.VISIBLE
        // ✅ Crop ke waqt timeline chhupao, taaki ratio bar uski jagah dikhe
        binding.frameLayoutEditor.visibility = View.INVISIBLE
        binding.tvCurrentTime.visibility = View.INVISIBLE
        binding.tvTotalDuration.visibility = View.INVISIBLE
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(bar.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = bar.measuredHeight.toFloat()

        bar.translationY = targetH          // start below
        bar.animate()
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /** Slide the ratio bar back down and hide it. Call when leaving crop mode. */
    fun hide() {
        val bar = binding.cropRatioSelector.root
        if (bar.visibility != View.VISIBLE) return

        bar.measure(
            View.MeasureSpec.makeMeasureSpec(bar.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = bar.measuredHeight.toFloat()

        bar.animate()
            .translationY(targetH)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                bar.visibility = View.GONE
                bar.translationY = 0f
                // ✅ Crop band hote hi timeline wapas dikhao
                binding.frameLayoutEditor.visibility = View.VISIBLE
                binding.tvCurrentTime.visibility = View.VISIBLE
                binding.tvTotalDuration.visibility = View.VISIBLE
                // Reset crop view to FREE and adapter highlight to first item
                binding.cropView.aspectRatio = AspectRatio.FREE
                ratioAdapter.resetSelection()
            }
            .start()
    }

    /** Returns true if the bar is currently visible (crop mode is active). */
    val isVisible: Boolean
        get() = binding.cropRatioSelector.root.visibility == View.VISIBLE
}