package com.nkd.screenrecorder.utils

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.nkd.screenrecorder.widgets.MaskedFrameLayout
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.frame.Frame
import com.otaliastudios.cameraview.frame.FrameProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Facecam "Face only": ML Kit selfie segmentation (on-device, STREAM_MODE). The camera preview
 * is untouched and always full speed; only the mask updates at ML Kit's speed, and the last mask
 * stays on screen between updates. The mask is applied on the GPU by [MaskedFrameLayout]
 * (bilinear upscale = soft edge).
 *
 * Per frame, with nothing allocated once running:
 * 1. CameraView frame thread: skip if busy/paused. Else sample the frame (any size the camera
 *    gives, e.g. 640×480) straight into a reused upright [INPUT_LONG_SIDE]-px ARGB bitmap —
 *    downscale, YUV→RGB and rotation in one pass. ML Kit then gets a ready bitmap with rotation
 *    0, so it doesn't build its own full-size NV21→Bitmap / rotated copies every frame (those
 *    were the ~20 MB/s of large objects).
 * 2. ML Kit runs on [mlThread] (one dedicated thread, normal priority); the result is handled
 *    there too: raw 256×256 confidence mask → bulk read → reused ALPHA_8 bitmap (3 rotating).
 * 3. Main thread only swaps the bitmap reference and redraws.
 *
 * Heavy load (ML Kit slower than [HEAVY_ML_MS] per mask) or a running/paused screen recording
 * → low-power mode: at most one frame per [LOW_POWER_INTERVAL_MS] (~10/s) and the ML thread at
 * background priority, so a game and the recording aren't slowed down. Face only stays on.
 *
 * [onUnsupported] (main thread, `duringRecording` = a recording was running):
 * - library fails to load, or keeps throwing errors — any time;
 * - "too slow" (mask below [MIN_FPS] for [SLOW_WINDOWS_LIMIT] windows of [WINDOW_MS] in a row)
 *   — ONLY while not recording, so the shape never changes by itself mid-recording. Counting
 *   restarts whenever a recording starts or stops, so short dips (loading screens) don't count.
 * [setPaused] while dragging/resizing. [stop] releases everything.
 */
class FaceOnlySegmenter(
    private val cameraView: CameraView,
    private val target: MaskedFrameLayout,
    /** True while a screen recording is running or paused. */
    private val isRecording: () -> Boolean,
    private val onUnsupported: (duringRecording: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "FaceOnlySegmenter"
        /** CameraView frame-processing size limit (some phones can't go this small — we downscale anyway). */
        const val FRAME_MAX_SIZE = 320
        /** Long side of the image ML Kit gets (the model itself works at 256×256). */
        private const val INPUT_LONG_SIDE = 256
        private const val MIN_FPS = 5
        /** Low-power mode: at most ~10 masks per second. */
        private const val LOW_POWER_INTERVAL_MS = 100L
        /** Average ML Kit time per mask above this = heavy load → low power; below [LIGHT_ML_MS] → normal. */
        private const val HEAVY_ML_MS = 80f
        private const val LIGHT_ML_MS = 50f
        private const val WARMUP_MS = 2_000L
        private const val WINDOW_MS = 3_000L
        private const val SLOW_WINDOWS_LIMIT = 2
        private const val MAX_FAILURES = 5
        private const val MASK_BITMAPS = 3
    }

    private val debug = cameraView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mlThread: ExecutorService? = null
    private var segmenter: Segmenter? = null

    @Volatile private var closed = false
    @Volatile private var paused = false
    /** A frame is inside ML Kit / mask building — new frames are skipped until it's done. */
    private val busy = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    // input side (frame thread; only touched while not busy)
    private var inputBitmap: Bitmap? = null
    private var inputPixels = IntArray(0)
    private var loggedSize = false

    // preview frame rate before Face only (restored in stop)
    private var oldFrameRate = 0f
    private var oldFrameRateExact = false

    // mask side (ML thread)
    private var confidences = FloatArray(0)
    private var alpha = ByteArray(0)
    private var alphaBuffer: ByteBuffer = ByteBuffer.wrap(alpha)
    private val maskBitmaps = arrayOfNulls<Bitmap>(MASK_BITMAPS)
    private var nextMask = 0

    // speed check + debug timings (ML thread)
    private var startedAt = 0L
    private var windowStart = 0L
    private var windowFrames = 0
    private var slowWindows = 0
    private var failures = 0
    private var mlNs = 0L
    @Volatile private var frameSize = ""

    // low-power mode (set on the ML thread, read on the frame thread)
    @Volatile private var lowPower = false
    private var lastSubmitAt = 0L          // frame thread
    private var wasRecording = false       // ML thread

    private val processor = FrameProcessor { frame -> onFrame(frame) }

    /** False if ML Kit could not be created (then [onUnsupported] was already posted). */
    fun start(): Boolean {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                r.run()
            }, "FaceOnlyML")
        }
        segmenter = try {
            Segmentation.getClient(
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()     // model's own 256×256 output: no resize inside ML Kit
                    .setExecutor(executor)   // inference on our normal-priority thread
                    .build()
            )
        } catch (t: Throwable) {   // also UnsatisfiedLinkError if the native library can't load
            Log.e(TAG, "Selfie segmentation unavailable", t)
            executor.shutdown()
            reportUnsupported()
            return false
        }
        mlThread = executor
        startedAt = SystemClock.elapsedRealtime()
        wasRecording = isRecording()
        if (wasRecording) setLowPower(true)
        // Ask for a 30 FPS preview (not exact) so more frames reach the mask
        oldFrameRate = cameraView.previewFrameRate
        oldFrameRateExact = cameraView.previewFrameRateExact
        cameraView.previewFrameRateExact = false
        cameraView.previewFrameRate = 30f
        cameraView.addFrameProcessor(processor)
        return true
    }

    /** True while the facecam window is dragged or resized: no segmentation, last mask stays. */
    fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        if (!paused) windowStart = 0L   // the paused time doesn't count as "slow"
    }

    fun stop() {
        if (closed) return
        closed = true
        cameraView.removeFrameProcessor(processor)
        cameraView.previewFrameRate = oldFrameRate
        cameraView.previewFrameRateExact = oldFrameRateExact
        mainHandler.removeCallbacksAndMessages(null)
        target.setMask(null, 0, 0, false)
        if (!busy.get()) release()
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            segmenter?.close()
        } catch (_: Exception) {
        }
        segmenter = null
        mlThread?.shutdown()
        mlThread = null
    }

    // ---- CameraView frame thread: sample + hand off, never wait

    private fun onFrame(frame: Frame) {
        if (closed || paused) return
        // Low power: skip frames so ML Kit runs at most ~10 times per second
        val now = SystemClock.elapsedRealtime()
        if (lowPower && now - lastSubmitAt < LOW_POWER_INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return   // busy → skip this frame
        lastSubmitAt = now
        val client = segmenter
        val executor = mlThread
        if (closed || client == null || executor == null) {
            finishFrame()
            return
        }
        try {
            val bitmap = sampleUpright(frame)
            val t0 = SystemClock.elapsedRealtimeNanos()
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(executor) { mask ->
                    mlNs += SystemClock.elapsedRealtimeNanos() - t0
                    onMask(mask, bitmap.width, bitmap.height)
                }
                .addOnFailureListener(executor) { e -> onFailure(e) }
        } catch (t: Throwable) {
            onFailure(t)
        }
    }

    /**
     * Frame (YUV_420_888 Image, or NV21 bytes from Camera1) → reused upright ARGB bitmap with
     * a [INPUT_LONG_SIDE]-px long side. Nearest-neighbour sampling: downscale, rotation and
     * YUV→RGB in one pass over the small output only.
     */
    private fun sampleUpright(frame: Frame): Bitmap {
        val w = frame.size.width
        val h = frame.size.height
        val rotation = ((frame.rotationToView % 360) + 360) % 360

        val yBuf: ByteBuffer
        val uBuf: ByteBuffer
        val vBuf: ByteBuffer
        val yStride: Int
        val uvStride: Int
        val uvPixel: Int
        var uOff = 0
        var vOff = 0
        if (frame.dataClass == Image::class.java) {
            val image = frame.getData<Image>()
            require(image.format == ImageFormat.YUV_420_888)
            val p = image.planes
            yBuf = p[0].buffer; yStride = p[0].rowStride
            uBuf = p[1].buffer; vBuf = p[2].buffer
            uvStride = p[1].rowStride; uvPixel = p[1].pixelStride
        } else {   // NV21: Y plane, then V/U interleaved
            val data = ByteBuffer.wrap(frame.getData<ByteArray>())
            yBuf = data; uBuf = data; vBuf = data
            yStride = w; uvStride = w; uvPixel = 2
            vOff = w * h; uOff = w * h + 1
        }

        // Upright frame size, and the small upright output size
        val upW = if (rotation % 180 == 0) w else h
        val upH = if (rotation % 180 == 0) h else w
        val scale = INPUT_LONG_SIDE.toFloat() / max(upW, upH)
        val outW = (upW * scale).roundToInt().coerceAtLeast(2)
        val outH = (upH * scale).roundToInt().coerceAtLeast(2)

        var bmp = inputBitmap
        if (bmp == null || bmp.width != outW || bmp.height != outH) {
            bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            inputBitmap = bmp
            inputPixels = IntArray(outW * outH)
        }
        if (!loggedSize) {
            loggedSize = true
            Log.i(TAG, "Face only: camera frame ${w}x$h (rotation $rotation) → ML Kit input ${outW}x$outH")
        }
        frameSize = "${w}x$h→${outW}x$outH"

        val px = inputPixels
        var i = 0
        for (oy in 0 until outH) {
            val uy = oy * upH / outH
            for (ox in 0 until outW) {
                val ux = ox * upW / outW
                // upright (ux, uy) → sensor (sx, sy); rotation = clockwise degrees to upright
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = uy; sy = h - 1 - ux }
                    180 -> { sx = w - 1 - ux; sy = h - 1 - uy }
                    270 -> { sx = w - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val yy = (yBuf.get(sy * yStride + sx).toInt() and 0xFF) - 16
                val uvIndex = (sy shr 1) * uvStride + (sx shr 1) * uvPixel
                val u = (uBuf.get(uOff + uvIndex).toInt() and 0xFF) - 128
                val v = (vBuf.get(vOff + uvIndex).toInt() and 0xFF) - 128
                val y1192 = 1192 * max(yy, 0)
                val r = ((y1192 + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((y1192 - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((y1192 + 2066 * u) shr 10).coerceIn(0, 255)
                px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    // ---- ML thread: mask → bitmap

    private fun onMask(mask: SegmentationMask, imageW: Int, imageH: Int) {
        try {
            if (closed) return
            failures = 0
            val bmp = fillMaskBitmap(mask)
            val mirror = cameraView.facing == Facing.FRONT
            mainHandler.post { if (!closed) target.setMask(bmp, imageW, imageH, mirror) }
            checkSpeed()
        } finally {
            finishFrame()
        }
    }

    private fun onFailure(t: Throwable) {
        try {
            if (closed) return
            Log.w(TAG, "Segmentation failed: ${t.message}")
            if (++failures >= MAX_FAILURES) reportUnsupported()
        } finally {
            finishFrame()
        }
    }

    private fun finishFrame() {
        busy.set(false)
        if (closed) release()
    }

    /** Confidences → ALPHA_8 with one bulk read and a tight loop, into the next reused bitmap. */
    private fun fillMaskBitmap(mask: SegmentationMask): Bitmap {
        val w = mask.width
        val h = mask.height
        val n = w * h
        if (confidences.size != n) {
            confidences = FloatArray(n)
            alpha = ByteArray(n)
            alphaBuffer = ByteBuffer.wrap(alpha)
        }
        val buffer = mask.buffer
        buffer.rewind()
        buffer.asFloatBuffer().get(confidences, 0, n)
        val c = confidences
        val a = alpha
        for (i in 0 until n) a[i] = (c[i] * 255f).toInt().toByte()

        // 3 bitmaps in rotation: the one written is never the one on screen (or just replaced)
        var bmp = maskBitmaps[nextMask]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            maskBitmaps[nextMask] = bmp
        }
        nextMask = (nextMask + 1) % MASK_BITMAPS
        if (bmp.rowBytes == w) {
            alphaBuffer.rewind()
            bmp.copyPixelsFromBuffer(alphaBuffer)
        }
        return bmp
    }

    /** Low power on/off: frame skipping + ML thread priority (called on the ML thread). */
    private fun setLowPower(on: Boolean) {
        if (lowPower == on) return
        lowPower = on
        val priority = if (on) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT
        mlThread?.execute { Process.setThreadPriority(priority) }
    }

    /**
     * Every [WINDOW_MS]: low power on/off from the recording state and ML Kit's speed; the
     * "too slow" fallback only while not recording (see class doc).
     */
    private fun checkSpeed() {
        val now = SystemClock.elapsedRealtime()
        val recording = isRecording()
        if (recording != wasRecording) {
            // Recording started/stopped: start counting again, and never carry a "slow" over
            wasRecording = recording
            slowWindows = 0
            windowStart = 0L
            if (recording) setLowPower(true)
        }
        if (now - startedAt < WARMUP_MS || paused) return
        if (windowStart == 0L) {
            windowStart = now
            windowFrames = 0
            mlNs = 0
            return
        }
        windowFrames++
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS) return
        val fps = windowFrames * 1000f / elapsed
        val mlMs = mlNs / (windowFrames.coerceAtLeast(1) * 1_000_000f)
        if (debug) {
            Log.d(
                TAG, "Face only mask %.1f fps | frame %s | ML Kit %.1f ms%s"
                    .format(fps, frameSize, mlMs, if (lowPower) " | low power" else "")
            )
        }
        // ML Kit's own time (not the throttled FPS) decides the load
        when {
            recording || mlMs > HEAVY_ML_MS -> setLowPower(true)
            mlMs < LIGHT_ML_MS -> setLowPower(false)
        }
        slowWindows = if (!recording && fps < MIN_FPS) slowWindows + 1 else 0
        windowStart = now
        windowFrames = 0
        mlNs = 0
        if (slowWindows >= SLOW_WINDOWS_LIMIT) reportUnsupported()
    }

    private fun reportUnsupported() {
        if (!reported.compareAndSet(false, true)) return
        val duringRecording = isRecording()
        mainHandler.post { if (!closed) onUnsupported(duringRecording) }
    }
}
