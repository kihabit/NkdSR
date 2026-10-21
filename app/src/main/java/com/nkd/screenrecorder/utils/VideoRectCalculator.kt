package com.nkd.screenrecorder.utils

import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.widget.VideoView

/**
 * Calculates the exact pixel rect that the actual video content occupies
 * inside a VideoView.
 *
 * VideoView always scales the video to fit while preserving aspect ratio
 * (equivalent to ImageView scaleType="fitCenter"). This means there will
 * be transparent letterbox bars (top/bottom) or pillarbox bars (left/right)
 * depending on whether the video is wider or taller than the view.
 *
 * Usage:
 *   val videoRect = VideoRectCalculator.calculate(videoView, videoPath)
 *   cropView.setVideoBounds(videoRect)
 */
object VideoRectCalculator {

    /**
     * Returns the RectF of the actual rendered video content inside [videoView],
     * in the coordinate space of [videoView] itself (i.e. relative to its top-left).
     *
     * Returns null if the video dimensions cannot be read or the view has no size yet.
     *
     * @param videoView  The VideoView that is playing / will play the video.
     * @param videoPath  The file path or content URI string of the video.
     */
    fun calculate(videoView: VideoView, videoPath: String): RectF? {
        val (videoW, videoH) = getVideoDimensions(videoPath) ?: return null
        return calculateFromDimensions(videoView, videoW, videoH)
    }

    /**
     * Overload for when you already have the video width/height (e.g. from
     * MediaPlayer.getVideoWidth() / getVideoHeight() in onPrepared).
     */
    fun calculateFromDimensions(videoView: VideoView, videoW: Int, videoH: Int): RectF? {
        if (videoW <= 0 || videoH <= 0) return null

        val viewW = videoView.width.toFloat()
        val viewH = videoView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = viewW / viewH

        val renderedW: Float
        val renderedH: Float

        if (videoAspect > viewAspect) {
            // Video is wider than the view → pillarbox (black bars top/bottom)
            // Width fills the view, height is letterboxed
            renderedW = viewW
            renderedH = viewW / videoAspect
        } else {
            // Video is taller than the view → letterbox (black bars left/right)
            // Height fills the view, width is pillarboxed
            renderedH = viewH
            renderedW = viewH * videoAspect
        }

        // Centre the rendered rect inside the view
        val left = (viewW - renderedW) / 2f
        val top = (viewH - renderedH) / 2f
        val right = left + renderedW
        val bottom = top + renderedH

        return RectF(left, top, right, bottom)
    }

    /**
     * Uses MediaMetadataRetriever to read the intrinsic video dimensions
     * without needing a prepared MediaPlayer.
     */
    private fun getVideoDimensions(path: String): Pair<Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            retriever.release()
            if (w != null && h != null && w > 0 && h > 0) Pair(w, h) else null
        } catch (e: Exception) {
            null
        }
    }
}