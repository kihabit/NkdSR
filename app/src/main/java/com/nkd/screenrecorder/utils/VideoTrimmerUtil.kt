package com.nkd.screenrecorder.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

object VideoTrimmerUtil {
    private val TAG: String = VideoTrimmerUtil::class.java.getSimpleName()
    const val MIN_SHOOT_DURATION: Long = 3000L // 最小剪辑时间3s
    const val VIDEO_MAX_TIME: Int = 600 // 60秒
    val MAX_SHOOT_DURATION: Long = VIDEO_MAX_TIME * 1000L //视频最多剪切多长时间10s
    const val MAX_COUNT_RANGE: Int = 6
    var SCREEN_WIDTH_FULL: Int = 1280
    val RECYCLER_VIEW_PADDING: Int = 15.dpToPx()
    var VIDEO_FRAMES_WIDTH: Int = SCREEN_WIDTH_FULL - RECYCLER_VIEW_PADDING * 2
    var THUMB_WIDTH: Int = (SCREEN_WIDTH_FULL - RECYCLER_VIEW_PADDING * 2) / MAX_COUNT_RANGE
    var THUMB_HEIGHT: Int = 60.dpToPx()


    fun shootVideoThumbInBackground(
        context: Context,
        videoUri: Uri,
        totalThumbsCount: Int,
        startPosition: Long,
        endPosition: Long,
        callback: (Bitmap, Int) -> Unit
    ) {
        var mediaMetadataRetriever: MediaMetadataRetriever? = null
        try {
            mediaMetadataRetriever = MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(context, videoUri)
            // Retrieve media data use microsecond
            val interval = (endPosition - startPosition) / (totalThumbsCount - 1)
            for (i in 0..<totalThumbsCount) {
                val frameTime = startPosition + interval * i
                var bitmap = mediaMetadataRetriever.getFrameAtTime(
                    frameTime * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bitmap == null) continue
                try {
                    bitmap =
                        Bitmap.createScaledBitmap(bitmap, THUMB_WIDTH, THUMB_HEIGHT, false)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                callback(bitmap, interval.toInt())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in Thumbnails ${e.message}")
            Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), e)
        } finally {
            mediaMetadataRetriever?.release()
        }
    }
}