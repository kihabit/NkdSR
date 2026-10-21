package com.nkd.screenrecorder.utils

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.BottomSheetFacecamShapeBinding

/**
 * Facecam "Shape" bottom sheet: Rectangle / Circle cards side by side. Tap = apply + close.
 * Shown from CameraService, so it is an overlay window ([context] must carry the app theme).
 */
object FacecamShapeSheet {

    fun show(context: Context, isCircle: Boolean, onPick: (circle: Boolean) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val sheet = BottomSheetFacecamShapeBinding.inflate(LayoutInflater.from(context))

        sheet.apply {
            style(context, rectangleCard, rectangleLabel, rectangleCheck, selected = !isCircle)
            style(context, circleCard, circleLabel, circleCheck, selected = isCircle)
            rectangleCard.setOnClickListener {
                dialog.dismiss()
                onPick(false)
            }
            circleCard.setOnClickListener {
                dialog.dismiss()
                onPick(true)
            }
        }

        dialog.setContentView(sheet.root)
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    /** Selected: orange 2dp border, orange label, check mark. Otherwise grey 1dp border. */
    private fun style(
        context: Context, card: MaterialCardView, label: TextView, check: ImageView, selected: Boolean
    ) {
        val density = context.resources.displayMetrics.density
        val orange = ContextCompat.getColor(context, R.color.primaryColor)
        card.strokeColor =
            if (selected) orange else ContextCompat.getColor(context, R.color.facecam_shape_card_stroke)
        card.strokeWidth = ((if (selected) 2 else 1) * density).toInt()
        if (selected) label.setTextColor(orange)
        check.visibility = if (selected) View.VISIBLE else View.GONE
    }
}
