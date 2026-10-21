package com.nkd.screenrecorder.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var isRecording = false
        private set

    /** Returns false if the recorder could not start. [onError] fires on async recorder errors. */
    fun start(onError: () -> Unit): Boolean {
        val dir = File(context.filesDir, "voice").also { it.mkdirs() }
        val out = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.setOnErrorListener { _, what, extra ->
                Log.e("VoiceRecorder", "error what=$what extra=$extra")
                onError()
            }
            r.prepare()
            r.start()
            recorder = r
            file = out
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "start failed: ${e.message}")
            try { r.release() } catch (_: Exception) { }
            out.delete()
            false
        }
    }

    /** Stops and returns the recorded file, or null if nothing valid was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        isRecording = false
        return try {
            r.stop()
            f
        } catch (e: RuntimeException) {          // stop() called too soon / no data
            f?.delete()
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** Stops and deletes the file. Always safe to call. */
    fun cancel() {
        stop()?.delete()
    }
}