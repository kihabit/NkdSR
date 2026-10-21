package com.nkd.screenrecorder.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.nkd.screenrecorder.activities.EditVideoActivity
import com.nkd.screenrecorder.model.StickerBurnData
import java.io.File
import java.io.FileOutputStream

// ─── FONT SETUP ───────────────────────────────────────────────────────────────
// Place your real .ttf files in:  app/src/main/assets/fonts/
//   e.g.  assets/fonts/roboto_serif.ttf
//         assets/fonts/aladin.ttf
//         assets/fonts/aguafina_script.ttf
//
// res/font/ resources are often compiled XML font-family descriptors which
// FFmpeg cannot read.  assets/ files are always copied as-is.
// ─────────────────────────────────────────────────────────────────────────────


object VideoTextBurner {
    data class TextBurnData(
        val text: String,
        val xPx: Int,
        val yPx: Int,
        val fontSizePx: Float,
        val fontFilePath: String,
        val fontColor: String,
        val startSec: Double,
        val endSec: Double
    )

    private const val TAG = "VideoTextBurner"
    private var activeSession: FFmpegSession? = null

    /** Source video ka bitrate (bps), audio ke liye 1.1x, 4-40 Mbps ke beech. Padh na paye to 8 Mbps. */
    private fun sourceBitrateBps(context: Context, path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) r.setDataSource(context, path.toUri())
            else r.setDataSource(path)
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            (br * 1.1).toLong().coerceIn(4_000_000L, 40_000_000L)
        } catch (e: Exception) {
            Log.w(TAG, "bitrate read failed: ${e.message}")
            8_000_000L
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /**
     * Every intermediate burn step (text/sticker/music) writes to a LOCAL cache
     * path and its input may now be either the original content:// video or a
     * previous step's local cache output — only wrap it as a SAF parameter when
     * it's actually a content:// URI; a plain local path is already readable by
     * FFmpeg directly.
     */
    fun resolveFfmpegInputPath(context: Context, path: String): String {
        return if (path.startsWith("content://")) {
            FFmpegKitConfig.getSafParameterForRead(context, path.toUri())
        } else {
            path
        }
    }

    fun burn(
        context: Context,
        inputPath: String,
        texts: List<TextBurnData>,
        videoDurationMs: Long,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val inputSaf = resolveFfmpegInputPath(context, inputPath)
        val outputDir =
            File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                "textOverlay"
            )
                .also { it.mkdirs() }
        val outputName = "text_overlay_${System.currentTimeMillis()}.mp4"
        val outputPath = File(outputDir, outputName).absolutePath

        // ── Escape text for drawtext ───────────────────────────────────────
        val filterBuilder = StringBuilder()
        texts.forEachIndexed { index, t ->
            if (index > 0) filterBuilder.append(",")
            val escapedText = t.text.replace("'", "\\'").replace(":", "\\:")
            filterBuilder.append(
                "drawtext=text='$escapedText'" +
                        ":fontfile='${t.fontFilePath}'" +
                        ":fontsize=${t.fontSizePx.toInt()}" +
                        ":fontcolor=${t.fontColor}" +
                        ":x=${t.xPx}:y=${t.yPx}" +
                        ":enable='between(t,${t.startSec},${t.endSec})'"
            )
        }

        val command = arrayOf(
            "-i", inputSaf,
            "-vf", "fps=60," + filterBuilder.toString(),
            "-map", "0:v",
            "-map", "0:a?",
            "-c:v", "libopenh264",
            "-b:v", sourceBitrateBps(context, inputPath).toString(),
            "-c:a", "copy",
            outputPath
        )

        activeSession = FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> {
                        // ✅ Local cache path only — do NOT promote to the SAF save
                        // folder here. Only the final export step should do that.
                        onSuccess(outputPath)
                        Log.d(TAG, "Text burn success, local output: $outputPath")
                    }

                    ReturnCode.isCancel(session.returnCode) -> onError("Cancelled")
                    else -> {
                        // allLogsAsString gives the full FFmpeg stderr — far more useful than stacktrace
                        val detail = session.allLogsAsString
                            ?: session.failStackTrace
                            ?: "no detail available"
                        Log.e(TAG, "FFmpeg failed:\n$detail")
                        onError(detail)
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (videoDurationMs > 0) {
                    val pct = ((stats.time.toFloat() / videoDurationMs) * 100)
                        .toInt().coerceIn(0, 100)
                    onProgress(pct)
                }
            }
        )
    }

    fun cancel() {
        activeSession?.cancel()
        activeSession = null
    }

    // ── Font helpers ───────────────────────────────────────────────────────

    /**
     * Copies  assets/fonts/<assetFileName>  to the cache dir so FFmpeg can
     * access it by absolute path.
     *
     * Returns the absolute path on success, or a system-font fallback,
     * or null if nothing suitable is found (FFmpeg will use its built-in font).
     */
    fun extractFontFromAssets(context: Context, assetFileName: String): String? {
        if (assetFileName.isBlank()) return systemFontFallback()

        return try {
            val dest = File(context.cacheDir, assetFileName)
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open("fonts/$assetFileName").use { src ->
                    FileOutputStream(dest).use { dst -> src.copyTo(dst) }
                }
            }
            if (isBinaryFont(dest.absolutePath)) {
                Log.d(TAG, "Font ready: ${dest.absolutePath}")
                dest.absolutePath
            } else {
                Log.w(TAG, "$assetFileName is XML/text – falling back to system font")
                dest.delete()
                systemFontFallback()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset not found: fonts/$assetFileName (${e.message})")
            systemFontFallback()
        }
    }

    /**
     * Returns true when the file starts with known font magic bytes:
     *   TTF  → 0x00 0x01 0x00 0x00
     *   OTF  → "OTTO"
     *   WOFF → "wOFF"
     * Android binary XML starts with 0x03 0x00 … — clearly not a font.
     */
    fun isBinaryFont(path: String): Boolean {
        return try {
            val b = File(path).inputStream().use {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.readNBytes(4)
                } else {
                    it.readBytes()
                }
            }
            if (b.size < 4) return false
            val isTtf = b[0] == 0x00.toByte() && b[1] == 0x01.toByte()
            val isOtf = b[0] == 'O'.code.toByte() && b[1] == 'T'.code.toByte()
            val isWoff = b[0] == 'w'.code.toByte() && b[1] == 'O'.code.toByte()
            isTtf || isOtf || isWoff
        } catch (e: Exception) {
            false
        }
    }

    private fun systemFontFallback(): String? = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Arial.ttf"
    ).firstOrNull { File(it).exists() }

    /**
     * If [path] is already a real file path (starts with '/'), returns it as-is.
     * If it is a content:// URI string, copies the file to [context.cacheDir]/videos/
     * and returns the absolute path of the copy.
     *
     * Call this ONCE when a video is first loaded and store the result.
     */
    fun toRealPath(context: Context, path: String): String {
        if (path.startsWith("/")) return path          // already a file path
        return try {
            toRealPath(context, path.toUri())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve path: $path", e)
            path   // return original and let FFmpeg give a better error
        }
    }

    fun toRealPath(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: uri.toString()

        val dir = File(context.cacheDir, "videos").also { it.mkdirs() }
        val dest = File(dir, "input_${System.currentTimeMillis()}.mp4")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open URI: $uri")

        Log.d(TAG, "Copied content URI → ${dest.absolutePath}")
        return dest.absolutePath
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

        val tree = androidx.documentfile.provider.DocumentFile
            .fromTreeUri(context, treeUri) ?: return outputPath

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

    fun getVideoBitrateKbps(context: Context, path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, path.toUri())
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 4_000_000L
            retriever.release()
            bitrate / 1000L  // convert bps → kbps
        } catch (e: Exception) {
            4000L // safe fallback
        }
    }

    fun burnSticker(
        context: Context,
        inputPath: String,
        imagePath: String,
        xPx: Int,
        yPx: Int,
        width: Int,
        height: Int,
        startSec: Double,
        endSec: Double,
        videoDurationMs: Long = 0L,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val inputSaf = resolveFfmpegInputPath(context, inputPath)

        val outputDirFile = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "stickerOverlay"
        ).also { it.mkdirs() }

        val outputName = "sticker_overlay_${System.currentTimeMillis()}.mp4"
        val outputPath = File(outputDirFile, outputName).absolutePath

        // ── Build filter_complex ─────────────────────────────────────────────

        val enablePart = if (startSec >= 0 && endSec > startSec) {
            ":enable='between(t,${String.format(java.util.Locale.US, "%.3f", startSec)},${String.format(java.util.Locale.US, "%.3f", endSec)})'"
        } else ""

        val filter = "[0:v]fps=60[base];[1:v]scale=$width:$height[ov];[base][ov]overlay=$xPx:$yPx$enablePart"

        Log.d(TAG, "sticker filter → $filter")

        // ── FFmpeg args ──────────────────────────────────────────────────────

        val args = arrayOf(
            "-i", inputSaf,
            "-i", imagePath,
            "-filter_complex", "$filter,scale=trunc(iw/2)*2:trunc(ih/2)*2",
            "-c:v", "libopenh264",
            "-b:v", sourceBitrateBps(context, inputPath).toString(),
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            "-y",
            outputPath
        )

        activeSession = FFmpegKit.executeWithArgumentsAsync(
            args,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> {
                        // ✅ Local cache path only — do NOT promote to the SAF save
                        // folder here. Only the final export step should do that.
                        onSuccess(outputPath)
                        Log.d(TAG, "Sticker overlay success, local output: $outputPath")
                    }

                    ReturnCode.isCancel(session.returnCode) -> onError("Cancelled")

                    else -> {
                        val detail = session.allLogsAsString
                            ?: session.failStackTrace
                            ?: "no detail available"
                        Log.e(TAG, "FFmpeg sticker failed:\n$detail")
                        onError(detail)
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (videoDurationMs > 0) {
                    val pct = ((stats.time.toFloat() / videoDurationMs) * 100)
                        .toInt().coerceIn(0, 100)
                    onProgress(pct)
                }
            }
        )
    }

    fun burnAllStickers(
        context: Context,
        inputPath: String,
        stickers: List<StickerBurnData>,
        videoDurationMs: Long,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (stickers.isEmpty()) {
            onError("No stickers provided")
            return
        }
        val inputSaf = resolveFfmpegInputPath(context, inputPath)
        val outputDirFile = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "stickerOverlay"
        ).also { it.mkdirs() }

        val outputName = "sticker_overlay_${System.currentTimeMillis()}.mp4"
        val outputFile = File(outputDirFile, outputName).absolutePath

        // ── Build filter_complex with all stickers chained ─────────────────
        // Input 0 = video, Input 1..N = sticker images
        // Chain: [0:v][1:v]overlay=...[v1]; [v1][2:v]overlay=...[v2]; ...
        val filterBuilder = StringBuilder()
        filterBuilder.append("[0:v]fps=60[base];")

        stickers.forEachIndexed { index, sticker ->
            val inputTag = if (index == 0) "[base]" else "[v$index]"
            val imageTag = "[${index + 1}:v]"
            val outputTag = if (index == stickers.lastIndex) "[vout]" else "[v${index + 1}]"

            // scale each sticker image to exact target size first
            filterBuilder.append(
                "${imageTag}scale=${sticker.width}:${sticker.height}[s$index];"
            )
            filterBuilder.append(
                "${inputTag}[s$index]overlay=" +
                        "x=${sticker.xPx}:y=${sticker.yPx}:" +
                        "enable='between(t,${sticker.startSec},${sticker.endSec})'" +
                        "${outputTag}"
            )
            if (index < stickers.lastIndex) filterBuilder.append(";")
        }

        // ── Build FFmpeg command ────────────────────────────────────────────
        // -i video -i sticker1 -i sticker2 ... -filter_complex "..." -map [vout] -map 0:a
        val command = mutableListOf<String>().apply {
            add("-i"); add(inputSaf)
            // Add each sticker image as input
            stickers.forEach { sticker ->
                add("-i"); add(sticker.imagePath)
            }
            add("-filter_complex"); add(filterBuilder.toString())
            add("-map"); add("[vout]")
            add("-map"); add("0:a?")        // copy audio if present
            add("-c:v"); add("libopenh264")
            add("-b:v"); add(sourceBitrateBps(context, inputPath).toString())
            add("-c:a"); add("copy")
            add(outputFile)
        }.toTypedArray()

        Log.d("BurnSticker", "FFmpeg command: ${command.joinToString(" ")}")

        /* executeFFmpeg(
             command = command,
             videoDurationMs = videoDurationMs,
             onProgress = onProgress,
             onSuccess = { onSuccess(outputFile) },
             onError = onError
         )*/
        activeSession = FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> {
                        // ✅ Local cache path only — do NOT promote to the SAF save
                        // folder here. Only the final export step should do that.
                        onSuccess(outputFile)
                        Log.d(TAG, "Sticker overlay success, local output: $outputFile")
                    }

                    ReturnCode.isCancel(session.returnCode) -> onError("Cancelled")

                    else -> {
                        val detail = session.allLogsAsString
                            ?: session.failStackTrace
                            ?: "no detail available"
                        Log.e(TAG, "FFmpeg sticker failed:\n$detail")
                        onError(detail)
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (videoDurationMs > 0) {
                    val pct = ((stats.time.toFloat() / videoDurationMs) * 100)
                        .toInt().coerceIn(0, 100)
                    onProgress(pct)
                }
            }
        )
    }

    /** Music ke bina sirf original audio ki volume badalta hai. Video re-encode nahi hota. */
    fun burnVideoVolume(
        context: Context,
        inputVideoPath: String,
        volumePercent: Int,
        videoDurationMs: Long,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // Video me audio hi nahi hai to badalne ko kuch nahi
        if (!hasAudioStream(context, inputVideoPath)) {
            onSuccess(inputVideoPath)
            return
        }
        val inputSaf = resolveFfmpegInputPath(context, inputVideoPath)
        val outputDirFile = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "music"
        ).also { it.mkdirs() }
        val outputFile = File(outputDirFile, "volume_${System.currentTimeMillis()}.mp4").absolutePath

        val command = arrayOf(
            "-i", inputSaf,
            "-map", "0:v",
            "-map", "0:a",
            "-c:v", "copy",
            "-af", "volume=${volumePercent.coerceIn(0, 100) / 100.0}",
            "-c:a", "aac",
            "-b:a", "192k",
            "-y",
            outputFile
        )
        Log.d("BurnVolume", "FFmpeg command: ${command.joinToString(" ")}")

        FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> onSuccess(outputFile)
                    ReturnCode.isCancel(session.returnCode) -> onError("Cancelled")
                    else -> onError(
                        session.allLogsAsString ?: session.failStackTrace ?: "no detail available"
                    )
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (videoDurationMs > 0) {
                    onProgress(((stats.time.toFloat() / videoDurationMs) * 100).toInt().coerceIn(0, 100))
                }
            }
        )
    }

    /** True if the video at [path] (content:// or file path) has an audio track.
     *  Defaults to true if it can't be determined, i.e. the original mixing behaviour. */
    fun hasAudioStream(context: Context, path: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) retriever.setDataSource(context, path.toUri())
            else retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) {
            Log.w(TAG, "hasAudioStream check failed, assuming audio: ${e.message}")
            true
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    fun burnMusic(
        context: Context,
        inputVideoPath: String,
        musicTracks: List<EditVideoActivity.MusicBurnData>,
        videoDurationMs: Long,
        videoVolume: Int = 100,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (musicTracks.isEmpty()) {
            onError("No music tracks provided")
            return
        }

        // ── Copy all music URIs to temp files FFmpeg can read ─────────────
        val musicFiles = musicTracks.mapIndexed { index, track ->
            val file = File(context.cacheDir, "music_${index}_${System.currentTimeMillis()}.mp3")
            context.contentResolver.openInputStream(track.musicUri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: run {
                onError("Cannot read music file: ${track.title}")
                return
            }
            file
        }

        val inputSaf = resolveFfmpegInputPath(context, inputVideoPath)
        val outputDirFile = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "music"
        ).also { it.mkdirs() }

        val outputName = "music_overlay_${System.currentTimeMillis()}.mp4"
        val outputFile = File(outputDirFile, outputName).absolutePath

        // ── Build filter_complex for all music tracks ──────────────────────
        // Input 0 = video
        // Input 1..N = music files
        // Each music track: atrim → adelay → asetpts → labeled [mN]
        // Final: amix all [mN] with original [0:a]

        val filterBuilder = StringBuilder()

        // ── Step 1: Process each music input ──────────────────────────────
        musicTracks.forEachIndexed { index, track ->
            val inputIndex = index + 1  // 0 is video
            val delayMs = (track.videoStartSec * 1000).toLong()
            val musicDurationSec = track.videoEndSec - track.videoStartSec

            val spd = track.speed.toDouble().coerceIn(0.5, 2.0)
            filterBuilder.append(
                "[$inputIndex:a]" +
                        "atrim=start=${track.musicOffsetSec}:duration=${musicDurationSec * spd}," +
                        "asetpts=PTS-STARTPTS," +
                        "atempo=$spd," +
                        "adelay=${delayMs}|${delayMs}," +
                        "volume=${track.volume.coerceIn(0, 100) / 100.0}" +
                        "[m$index];"
            )
        }

        // ── Step 2: Mix all music tracks + original video audio ────────────
        // [0:a] = original video audio
        // [m0],[m1]... = processed music tracks
        // ✅ A video with NO audio stream (e.g. screen recording without mic) has no [0:a] —
        // referencing it makes FFmpeg fail, so mix only the music inputs in that case and
        // pad the result with silence; -shortest then cuts it at the video's end.
        val videoHasAudio = hasAudioStream(context, inputVideoPath)
        if (videoHasAudio) {
            filterBuilder.append("[0:a]volume=${videoVolume.coerceIn(0, 100) / 100.0}[va];")
            val mixInputs = StringBuilder("[va]")
            musicTracks.forEachIndexed { index, _ ->
                mixInputs.append("[m$index]")
            }
            val totalInputs = musicTracks.size + 1  // +1 for original video audio

            filterBuilder.append(
                "${mixInputs}amix=inputs=${totalInputs}:" +
                        "duration=first:" +
                        "dropout_transition=0:" +
                        "normalize=0" +   // ✅ normalize=0 prevents volume drop when mixing
                        "[aout]"
            )
        } else if (musicTracks.size == 1) {
            filterBuilder.append("[m0]apad[aout]")
        } else {
            val mixInputs = StringBuilder()
            musicTracks.forEachIndexed { index, _ ->
                mixInputs.append("[m$index]")
            }
            filterBuilder.append(
                "${mixInputs}amix=inputs=${musicTracks.size}:" +
                        "duration=longest:" +
                        "dropout_transition=0:" +
                        "normalize=0," +
                        "apad" +
                        "[aout]"
            )
        }
        Log.d("BurnMusic", "videoHasAudio=$videoHasAudio")

        // ── Build FFmpeg command ───────────────────────────────────────────
        val command = mutableListOf<String>().apply {
            // Video input
            add("-i"); add(inputSaf)
            // All music inputs
            musicFiles.forEach { file ->
                add("-i"); add(file.absolutePath)
            }
            add("-filter_complex"); add(filterBuilder.toString())
            add("-map"); add("0:v")       // video from original
            add("-map"); add("[aout]")    // mixed audio
            add("-c:v"); add("copy")      // no video re-encode
            add("-c:a"); add("aac")
            add("-b:a"); add("192k")
            add("-shortest")
            add(outputFile)
        }.toTypedArray()

        Log.d("BurnMusic", "FFmpeg command: ${command.joinToString(" ")}")

        FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> {
                        // ✅ Local cache path only — do NOT promote to the SAF save
                        // folder here. Only the final export step should do that.
                        onSuccess(outputFile)
                        Log.d(TAG, "Music mix success, local output: $outputFile")
                    }

                    ReturnCode.isCancel(session.returnCode) -> onError("Cancelled")

                    else -> {
                        val detail = session.allLogsAsString
                            ?: session.failStackTrace
                            ?: "no detail available"
                        Log.e(TAG, "FFmpeg sticker failed:\n$detail")
                        onError(detail)
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                if (videoDurationMs > 0) {
                    val pct = ((stats.time.toFloat() / videoDurationMs) * 100)
                        .toInt().coerceIn(0, 100)
                    onProgress(pct)
                }
            }
        )
    }

    // ── Pure filter-fragment builders for the combined single-pass export ──────
    // These build filter-graph strings only — no FFmpeg execution, no I/O, no
    // forced fps conversion. They exist so EditVideoActivity's combined export
    // can fold trim + crop + text + stickers + audio into ONE FFmpeg invocation
    // instead of burn()/burnAllStickers()/burnMusic() each running their own
    // full encode. The standalone burn()/burnAllStickers()/burnMusic()/
    // burnVideoVolume() functions above are untouched and keep their existing
    // (fps=60-forcing) behaviour for their existing non-export callers.

    /** `drawtext=...` chain for one or more text overlays, with no fps filter and
     *  no bracket labels — the caller appends this into its own filter chain. */
    fun buildDrawTextFilterFragment(texts: List<TextBurnData>): String {
        val filterBuilder = StringBuilder()
        texts.forEachIndexed { index, t ->
            if (index > 0) filterBuilder.append(",")
            val escapedText = t.text.replace("'", "\\'").replace(":", "\\:")
            filterBuilder.append(
                "drawtext=text='$escapedText'" +
                        ":fontfile='${t.fontFilePath}'" +
                        ":fontsize=${t.fontSizePx.toInt()}" +
                        ":fontcolor=${t.fontColor}" +
                        ":x=${t.xPx}:y=${t.yPx}" +
                        ":enable='between(t,${t.startSec},${t.endSec})'"
            )
        }
        return filterBuilder.toString()
    }

    /**
     * Image-overlay chain for one or more stickers/labeled-text PNGs, mirroring the
     * chain burnAllStickers() builds internally — but taking the already-filtered
     * video label as input instead of starting fresh from `[0:v]`, and letting the
     * caller say where the sticker image `-i` inputs start in the ffmpeg input list.
     * Returns (filterFragment, outputLabel) — outputLabel is always "[vout]".
     */
    fun buildStickerOverlayFilterFragment(
        inputLabel: String,
        firstImageInputIndex: Int,
        stickers: List<StickerBurnData>
    ): Pair<String, String> {
        if (stickers.isEmpty()) return "" to inputLabel
        val filterBuilder = StringBuilder()
        stickers.forEachIndexed { index, sticker ->
            val inTag = if (index == 0) inputLabel else "[v$index]"
            val imageTag = "[${firstImageInputIndex + index}:v]"
            val outTag = if (index == stickers.lastIndex) "[vout]" else "[v${index + 1}]"
            filterBuilder.append("${imageTag}scale=${sticker.width}:${sticker.height}[s$index];")
            filterBuilder.append(
                "${inTag}[s$index]overlay=" +
                        "x=${sticker.xPx}:y=${sticker.yPx}:" +
                        "enable='between(t,${sticker.startSec},${sticker.endSec})'" +
                        outTag
            )
            if (index < stickers.lastIndex) filterBuilder.append(";")
        }
        return filterBuilder.toString() to "[vout]"
    }

    /**
     * Music-mix portion of the combined export's audio graph — the same amix
     * construction burnMusic() uses, but parameterized so a trim stage can run
     * first (via [baseVideoAudioLabel]) and the music file `-i` inputs can start
     * anywhere in the input list (via [firstMusicInputIndex], e.g. after sticker
     * image inputs). Returns (filterFragment, outputLabel) — outputLabel is "[aout]".
     */
    fun buildMusicMixFilterFragment(
        baseVideoAudioLabel: String,
        videoHasAudio: Boolean,
        videoVolumePercent: Int,
        musicTracks: List<EditVideoActivity.MusicBurnData>,
        firstMusicInputIndex: Int
    ): Pair<String, String> {
        val filterBuilder = StringBuilder()
        musicTracks.forEachIndexed { index, track ->
            val delayMs = (track.videoStartSec * 1000).toLong()
            val musicDurationSec = track.videoEndSec - track.videoStartSec
            val spd = track.speed.toDouble().coerceIn(0.5, 2.0)
            filterBuilder.append(
                "[${firstMusicInputIndex + index}:a]" +
                        "atrim=start=${track.musicOffsetSec}:duration=${musicDurationSec * spd}," +
                        "asetpts=PTS-STARTPTS," +
                        "atempo=$spd," +
                        "adelay=${delayMs}|${delayMs}," +
                        "volume=${track.volume.coerceIn(0, 100) / 100.0}" +
                        "[m$index];"
            )
        }
        if (videoHasAudio) {
            filterBuilder.append(
                "${baseVideoAudioLabel}volume=${videoVolumePercent.coerceIn(0, 100) / 100.0}[va];"
            )
            val mixInputs = StringBuilder("[va]")
            musicTracks.forEachIndexed { index, _ -> mixInputs.append("[m$index]") }
            val totalInputs = musicTracks.size + 1
            filterBuilder.append(
                "${mixInputs}amix=inputs=$totalInputs:" +
                        "duration=first:dropout_transition=0:normalize=0[aout]"
            )
        } else if (musicTracks.size == 1) {
            filterBuilder.append("[m0]apad[aout]")
        } else {
            val mixInputs = StringBuilder()
            musicTracks.forEachIndexed { index, _ -> mixInputs.append("[m$index]") }
            filterBuilder.append(
                "${mixInputs}amix=inputs=${musicTracks.size}:" +
                        "duration=longest:dropout_transition=0:normalize=0,apad[aout]"
            )
        }
        return filterBuilder.toString() to "[aout]"
    }
}