package com.nkd.screenrecorder.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Extracts a video's audio track into an .m4a (AAC 192k, no video) with FFmpeg —
 * used by the My Music screen's "Extract audio from video" card.
 * Callbacks arrive on an FFmpeg background thread; callers hop to the UI thread.
 */
object AudioExtractor {
    private const val TAG = "AudioExtractor"
    private var activeSession: FFmpegSession? = null

    /** True if [uri] has an audio track. Defaults to true when it can't be read,
     *  so FFmpeg gets a chance (and reports a real error) instead of a wrong toast. */
    fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) {
            Log.w(TAG, "hasAudioTrack failed, assuming audio: ${e.message}")
            true
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /** Duration of an audio/video [uri] in ms, or 0 if unknown. */
    fun readDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "readDurationMs failed: ${e.message}")
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /**
     * Re-encodes [videoUri]'s audio to AAC 192k in [outFile] (always re-encoded, so any
     * source codec works). [durationMs] drives [onProgress] (0–100). A failed or cancelled
     * run deletes the partial file; cancel reports onError("Cancelled").
     */
    fun extract(
        context: Context,
        videoUri: Uri,
        outFile: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val input = FFmpegKitConfig.getSafParameterForRead(context, videoUri)
        val args = arrayOf(
            "-y",
            "-i", input,
            "-vn",
            "-c:a", "aac",
            "-b:a", "192k",
            "-movflags", "+faststart",
            outFile.absolutePath
        )
        Log.d(TAG, "extract → ${outFile.absolutePath}")

        activeSession = FFmpegKit.executeWithArgumentsAsync(
            args,
            { session ->
                activeSession = null
                when {
                    ReturnCode.isSuccess(session.returnCode) && outFile.length() > 0L -> onSuccess(outFile)

                    ReturnCode.isCancel(session.returnCode) -> {
                        outFile.delete()
                        onError("Cancelled")
                    }

                    else -> {
                        outFile.delete()
                        val detail = session.allLogsAsString
                            ?: session.failStackTrace
                            ?: "no detail available"
                        Log.e(TAG, "FFmpeg extract failed:\n$detail")
                        onError(detail)
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (durationMs > 0) {
                    val pct = ((stats.time.toFloat() / durationMs) * 100).toInt().coerceIn(0, 100)
                    onProgress(pct)
                }
            }
        )
    }

    fun cancel() {
        activeSession?.cancel()
        activeSession = null
    }
}
