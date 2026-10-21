package com.nkd.screenrecorder.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nkd.screenrecorder.activities.ScreenshotPreviewActivity

object ScreenshotPreviewHelper {

    /**
     * Show screenshot preview in top-right corner with animation
     *
     * @param context Application or Activity context
     * @param screenshotUri URI of the saved screenshot
     */
    fun showScreenshotPreview(context: Context, screenshotUri: Uri) {
        val intent = Intent(context, ScreenshotPreviewActivity::class.java).apply {
            putExtra(ScreenshotPreviewActivity.EXTRA_SCREENSHOT_URI, screenshotUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        context.startActivity(intent)
    }
}