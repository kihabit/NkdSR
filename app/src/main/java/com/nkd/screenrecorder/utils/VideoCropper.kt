package com.nkd.screenrecorder.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Crops a video file using FFmpeg.
 *
 * Handles both real file paths (/storage/emulated/0/...) and
 * content:// URIs from MediaStore / SAF picker.
 *
 * Add to build.gradle (app):
 *   implementation 'com.arthenica:ffmpeg-kit-android:6.0-2.LTS'
 */
object VideoCropper {

    private const val TAG = "VideoCropper"

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val bitrateBps: Long = 0L
    )

    fun crop(
        context: Context,
        inputPath: String,
        normalisedRect: android.graphics.RectF,
        onProgress: (percent: Int) -> Unit = {},
        onSuccess: (outputPath: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val resolvedPath: String
        var tempFile: File? = null

        if (inputPath.startsWith("content://")) {
            val uri = inputPath.toUri()
            val tmp = File(context.cacheDir, "crop_input_${System.currentTimeMillis()}.mp4")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    onError("Cannot open content URI: $inputPath")
                    return
                }
                resolvedPath = tmp.absolutePath
                tempFile = tmp
                Log.d(TAG, "content:// copied to temp: $resolvedPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy content URI to temp file", e)
                onError("Failed to read video: ${e.message}")
                return
            }
        } else {
            resolvedPath = inputPath
        }

        // ── 2. Read actual video dimensions ───────────────────────────────────
        val info = getVideoInfo(context, resolvedPath)
        if (info == null) {
            tempFile?.delete()
            onError("Could not read video metadata")
            return
        }

        Log.d(TAG, "Video: ${info.width}x${info.height}  duration=${info.durationMs}ms")

        // ── 3. Convert normalised rect → pixel rect ───────────────────────────
        val clampedLeft = normalisedRect.left.coerceIn(0f, 1f)
        val clampedTop = normalisedRect.top.coerceIn(0f, 1f)
        val clampedRight = normalisedRect.right.coerceIn(0f, 1f)
        val clampedBottom = normalisedRect.bottom.coerceIn(0f, 1f)

        var cropW = ((clampedRight - clampedLeft) * info.width).toInt().makeEven()
        var cropH = ((clampedBottom - clampedTop) * info.height).toInt().makeEven()
        val cropX = (clampedLeft * info.width).toInt().makeEven()
        val cropY = (clampedTop * info.height).toInt().makeEven()

        val maxW = (info.width - cropX).makeEven()
        val maxH = (info.height - cropY).makeEven()

        cropW = cropW.coerceAtMost(maxW)
        cropH = cropH.coerceAtMost(maxH)

        if (cropW <= 0 || cropH <= 0) {
            tempFile?.delete()
            onError("Invalid crop dimensions: ${cropW}x${cropH}")
            return
        }

        Log.d(TAG, "FFmpeg crop: w=$cropW h=$cropH x=$cropX y=$cropY")

        // ── 4. Build output path ───────────────────────────────────────────────
        // Always save to app's Movies cache dir so we never need WRITE permission
        val outputDir =
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "cropped")
                .also { it.mkdirs() }
        val outputName = "cropped_${System.currentTimeMillis()}.mp4"
        val outputPath = File(outputDir, outputName).absolutePath

        File(outputPath).takeIf { it.exists() }?.delete()

        // ── 5. Build FFmpeg command ────────────────────────────────────────────
        // Source bitrate ke hisaab se (audio bhi total mein hota hai, isliye 1.1x); kam se kam 4 Mbps
        val videoBitrate = (info.bitrateBps * 1.1).toLong().coerceIn(4_000_000L, 40_000_000L)
        val command = "-i \"$resolvedPath\" " +
                "-vf \"fps=60,crop=$cropW:$cropH:$cropX:$cropY,scale=trunc(iw/2)*2:trunc(ih/2)*2\" " +
                "-c:v libopenh264 " +
                "-b:v $videoBitrate " +
                "-pix_fmt yuv420p " +
                "-c:a copy " +
                "-movflags +faststart " +
                "-y \"$outputPath\""

        Log.d(TAG, "FFmpeg command: $command")

        // ── 6. Execute ─────────────────────────────────────────────────────────
        FFmpegKit.executeAsync(
            command,
            { session ->
                // Always clean up the temp copy regardless of result
                tempFile?.delete()

                val returnCode = session.returnCode
                when {
                    ReturnCode.isSuccess(returnCode) -> {
                        // ✅ Local cache path only — do NOT promote to the SAF save
                        // folder here. Only the final export step should do that.
                        Log.d(TAG, "Crop success, local output: $outputPath")
                        onSuccess(outputPath)
                    }

                    ReturnCode.isCancel(returnCode) -> {
                        onError("FFmpeg cancelled")
                    }

                    else -> {
                        Log.e(TAG, "FFmpeg failed:\n${session.allLogsAsString}")
                        onError("Crop failed. Please try again.")
                    }
                }
            },
            { log -> Log.v(TAG, log.message) },
            { stats ->
                if (info.durationMs > 0) {
                    val percent = ((stats.time.toFloat() / info.durationMs) * 100)
                        .toInt().coerceIn(0, 100)
                    onProgress(percent)
                }
            }
        )
    }

    /** Pure result of [buildCropFilterFragment]: the `-filter_complex`/`-vf` fragment
     *  plus the frame size it produces, so callers can place overlays correctly
     *  without decoding anything. */
    data class CropFilterSpec(
        val filterFragment: String,
        val outputWidth: Int,
        val outputHeight: Int
    )

    /**
     * Same crop-rectangle math [crop] uses internally, exposed as a pure, no-FFmpeg,
     * no-I/O function so a combined single-pass export (built elsewhere) can fold
     * cropping into its own filter graph instead of running crop() as its own
     * standalone encode. Returns null for a degenerate (zero-area) crop rect.
     */
    fun buildCropFilterFragment(
        sourceWidth: Int,
        sourceHeight: Int,
        normalisedRect: android.graphics.RectF
    ): CropFilterSpec? {
        val clampedLeft = normalisedRect.left.coerceIn(0f, 1f)
        val clampedTop = normalisedRect.top.coerceIn(0f, 1f)
        val clampedRight = normalisedRect.right.coerceIn(0f, 1f)
        val clampedBottom = normalisedRect.bottom.coerceIn(0f, 1f)

        var cropW = ((clampedRight - clampedLeft) * sourceWidth).toInt().makeEven()
        var cropH = ((clampedBottom - clampedTop) * sourceHeight).toInt().makeEven()
        val cropX = (clampedLeft * sourceWidth).toInt().makeEven()
        val cropY = (clampedTop * sourceHeight).toInt().makeEven()

        val maxW = (sourceWidth - cropX).makeEven()
        val maxH = (sourceHeight - cropY).makeEven()

        cropW = cropW.coerceAtMost(maxW)
        cropH = cropH.coerceAtMost(maxH)

        if (cropW <= 0 || cropH <= 0) return null

        val fragment = "crop=$cropW:$cropH:$cropX:$cropY,scale=trunc(iw/2)*2:trunc(ih/2)*2"
        return CropFilterSpec(fragment, cropW, cropH)
    }

    fun moveOutputToInputFolder(
        context: Context,
        inputPath: String,
        outputPath: String
    ): String {

        val outputFile = File(outputPath)

        if (!inputPath.startsWith("content://")) {

            val inputFile = File(inputPath)
            val dest = File(inputFile.parentFile, outputFile.name)

            outputFile.copyTo(dest, overwrite = true)

            return dest.absolutePath
        }

        val resolver = context.contentResolver
        val inputUri = inputPath.toUri()

        val treeUri = DocumentsContract.buildTreeDocumentUri(
            inputUri.authority!!,
            DocumentsContract.getTreeDocumentId(inputUri)
        )

        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return outputPath

        val newFile = tree.createFile(
            "video/mp4",
            outputFile.nameWithoutExtension
        ) ?: return outputPath

        resolver.openOutputStream(newFile.uri)?.use { out ->
            outputFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        return newFile.uri.toString()
    }

    fun cancel() {
        FFmpegKit.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getVideoInfo(context: Context, path: String): VideoInfo? {
        return try {
            val retriever = MediaMetadataRetriever()
            // Use the context-aware overload which works with both file paths
            // and content:// URIs. Since we already resolved to a real path
            // above, passing it as a Uri still works correctly.
            if (path.startsWith("content://")) {
                retriever.setDataSource(context, path.toUri())
            } else {
                retriever.setDataSource(path)
            }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            if (w != null && h != null) VideoInfo(w, h, dur ?: 0L, br) else null
        } catch (e: Exception) {
            Log.e(TAG, "MetadataRetriever failed for path: $path", e)
            null
        }
    }

    private fun buildOutputPath(inputPath: String): String {
        val inputFile = File(inputPath)
        val nameWithoutExt = inputFile.nameWithoutExtension
        val ext = inputFile.extension.ifEmpty { "mp4" }
        return "${inputFile.parent}/${nameWithoutExt}_cropped.$ext"
    }

    private fun Int.makeEven() = if (this % 2 != 0) this - 1 else this


}