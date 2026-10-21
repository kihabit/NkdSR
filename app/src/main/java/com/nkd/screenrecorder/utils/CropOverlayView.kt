package com.nkd.screenrecorder.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlinx.parcelize.Parcelize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.toColorInt

enum class AspectRatio(val label: String, val widthRatio: Float, val heightRatio: Float) {
    FREE("Free", 0f, 0f),
    RATIO_1_1("1:1", 1f, 1f),
    RATIO_4_5("4:5", 4f, 5f),
    RATIO_9_16("9:16", 9f, 16f),
    RATIO_16_9("16:9", 16f, 9f),
    RATIO_5_4("5:4", 5f, 4f),
    RATIO_3_4("3:4", 3f, 4f),
    RATIO_4_3("4:3", 4f, 3f),
    RATIO_2_3("2:3", 2f, 3f),
    RATIO_3_2("3:2", 3f, 2f),
    RATIO_1_2("1:2", 1f, 2f);
}

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val borderPaint = Paint().apply {
        color = "#FF5722".toColorInt(); style = Paint.Style.STROKE; strokeWidth =
        6f; isAntiAlias = true
    }
    private val gridPaint =
        Paint().apply { color = "#66FF5722".toColorInt(); strokeWidth = 2f }
    private val handlePaint = Paint().apply {
    color = "#FF5722".toColorInt(); strokeWidth = 10f; style = Paint.Style.STROKE
    }
    private val iconPaint =
        Paint().apply { color = "#AAFF5722".toColorInt(); strokeWidth = 6f }

    private val minSize = 100f
    private val handleLength = 60f
    private val touchMargin = 60f

    // Video native dimensions — set once from onPrepared
    private var videoNativeWidth: Int = 0
    private var videoNativeHeight: Int = 0

    // Computed from videoNativeWidth/Height + view size in recalculateVideoBounds()
    private var videoBounds: RectF? = null
    private var cropRect = RectF()

    var aspectRatio: AspectRatio = AspectRatio.FREE
        set(value) {
            field = value; if (value != AspectRatio.FREE) applyAspectRatioToRect(); invalidate()
        }

    private var lastX = 0f
    private var lastY = 0f
    private var touchMode = NONE

    companion object {
        private const val TAG = "CropOverlayView"
        private const val NONE = 0;
        private const val MOVE = 1
        private const val LEFT = 2;
        private const val RIGHT = 3
        private const val TOP = 4;
        private const val BOTTOM = 5
        private const val TOP_LEFT = 6;
        private const val TOP_RIGHT = 7
        private const val BOTTOM_LEFT = 8;
        private const val BOTTOM_RIGHT = 9
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * The ONLY call you need from Activity:
     *
     *   binding.videoView.setOnPreparedListener { mp ->
     *       binding.cropView.setVideoSize(mp.videoWidth, mp.videoHeight)
     *   }
     *
     * No post{}, no doOnLayout, no lifecycle scopes needed.
     */
    fun setVideoSize(videoW: Int, videoH: Int) {
        Log.d(TAG, "setVideoSize: ${videoW}x${videoH}  view: ${width}x${height}")
        videoNativeWidth = videoW
        videoNativeHeight = videoH
        recalculateVideoBounds()   // safe — returns early if view not laid out yet
    }

    fun clearVideoSize() {
        videoNativeWidth = 0; videoNativeHeight = 0
        videoBounds = null
        resetCropToFullView()
        invalidate()
    }

    fun getCropArea(): Rect =
        Rect(
            cropRect.left.toInt(),
            cropRect.top.toInt(),
            cropRect.right.toInt(),
            cropRect.bottom.toInt()
        )

    fun getNormalisedCropRect(): RectF? {
        val vb = videoBounds ?: return null
        if (vb.width() == 0f || vb.height() == 0f) return null
        return RectF(
            (cropRect.left - vb.left) / vb.width(),
            (cropRect.top - vb.top) / vb.height(),
            (cropRect.right - vb.left) / vb.width(),
            (cropRect.bottom - vb.top) / vb.height()
        )
    }

    fun getCropCoordinates(): CropCoordinates = CropCoordinates(
        Point(cropRect.left.toInt(), cropRect.top.toInt()),
        Point(cropRect.right.toInt(), cropRect.top.toInt()),
        Point(cropRect.left.toInt(), cropRect.bottom.toInt()),
        Point(cropRect.right.toInt(), cropRect.bottom.toInt())
    )

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged: ${w}x${h}  video: ${videoNativeWidth}x${videoNativeHeight}")
        recalculateVideoBounds()
    }

    private fun recalculateVideoBounds() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if (viewW <= 0f || viewH <= 0f) {
            Log.d(TAG, "recalculate skipped — view not laid out")
            return
        }
        if (videoNativeWidth <= 0 || videoNativeHeight <= 0) {
            Log.d(TAG, "recalculate — no video size yet, using full view")
            videoBounds = null
            resetCropToFullView()
            invalidate()
            return
        }

        val videoAspect = videoNativeWidth.toFloat() / videoNativeHeight.toFloat()
        val viewAspect = viewW / viewH

        val renderedW: Float
        val renderedH: Float

        if (videoAspect > viewAspect) {
            renderedW = viewW
            renderedH = viewW / videoAspect
        } else {
            renderedH = viewH
            renderedW = viewH * videoAspect
        }

        val left = (viewW - renderedW) / 2f
        val top = (viewH - renderedH) / 2f

        videoBounds = RectF(left, top, left + renderedW, top + renderedH)
        Log.d(TAG, "videoBounds set to: $videoBounds")

        cropRect.set(videoBounds!!)
        if (aspectRatio != AspectRatio.FREE) applyAspectRatioToRect()
        invalidate()
    }

    private fun resetCropToFullView() {
        if (width > 0 && height > 0) cropRect.set(0f, 0f, width.toFloat(), height.toFloat())
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val vb = videoBounds
        if (vb != null) {
            canvas.drawRect(vb, clearPaint)
            canvas.drawRect(cropRect, clearPaint)
            drawVideoDimAroundCrop(canvas, vb)
        } else {
            canvas.drawRect(cropRect, clearPaint)
        }
        canvas.drawRect(cropRect, borderPaint)
        drawGrid(canvas)
        drawCornerBrackets(canvas)
        drawCenterMoveIcon(canvas)
    }

    private fun drawVideoDimAroundCrop(canvas: Canvas, vb: RectF) {
        if (cropRect.top > vb.top) canvas.drawRect(
            vb.left,
            vb.top,
            vb.right,
            cropRect.top,
            dimPaint
        )
        if (cropRect.bottom < vb.bottom) canvas.drawRect(
            vb.left,
            cropRect.bottom,
            vb.right,
            vb.bottom,
            dimPaint
        )
        if (cropRect.left > vb.left) canvas.drawRect(
            vb.left,
            cropRect.top,
            cropRect.left,
            cropRect.bottom,
            dimPaint
        )
        if (cropRect.right < vb.right) canvas.drawRect(
            cropRect.right,
            cropRect.top,
            vb.right,
            cropRect.bottom,
            dimPaint
        )
    }

    private fun drawGrid(canvas: Canvas) {
        val tW = cropRect.width() / 3;
        val tH = cropRect.height() / 3
        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + i * tW,
                cropRect.top,
                cropRect.left + i * tW,
                cropRect.bottom,
                gridPaint
            )
            canvas.drawLine(
                cropRect.left,
                cropRect.top + i * tH,
                cropRect.right,
                cropRect.top + i * tH,
                gridPaint
            )
        }
    }

    private fun drawCornerBrackets(canvas: Canvas) {
        val r = cropRect;
        val l = handleLength
        canvas.drawLine(r.left, r.top, r.left + l, r.top, handlePaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + l, handlePaint)
        canvas.drawLine(r.right - l, r.top, r.right, r.top, handlePaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + l, handlePaint)
        canvas.drawLine(r.left, r.bottom - l, r.left, r.bottom, handlePaint)
        canvas.drawLine(r.left, r.bottom, r.left + l, r.bottom, handlePaint)
        canvas.drawLine(r.right - l, r.bottom, r.right, r.bottom, handlePaint)
        canvas.drawLine(r.right, r.bottom - l, r.right, r.bottom, handlePaint)
    }

    private fun drawCenterMoveIcon(canvas: Canvas) {
        val cx = cropRect.centerX();
        val cy = cropRect.centerY();
        val s = 40f
        canvas.drawLine(cx - s, cy, cx + s, cy, iconPaint)
        canvas.drawLine(cx, cy - s, cx, cy + s, iconPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x;
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchMode = detectTouch(x, y); lastX = x; lastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                resizeOrMove(x - lastX, y - lastY); lastX = x; lastY = y; invalidate()
            }
        }
        return true
    }

    private fun detectTouch(x: Float, y: Float): Int {
        val r = cropRect
        if (near(x, r.left) && near(y, r.top)) return TOP_LEFT
        if (near(x, r.right) && near(y, r.top)) return TOP_RIGHT
        if (near(x, r.left) && near(y, r.bottom)) return BOTTOM_LEFT
        if (near(x, r.right) && near(y, r.bottom)) return BOTTOM_RIGHT
        if (near(x, r.left)) return LEFT
        if (near(x, r.right)) return RIGHT
        if (near(y, r.top)) return TOP
        if (near(y, r.bottom)) return BOTTOM
        if (r.contains(x, y)) return MOVE
        return NONE
    }

    private fun near(a: Float, b: Float) = abs(a - b) < touchMargin

    private fun resizeOrMove(dx: Float, dy: Float) {
        when (touchMode) {
            MOVE -> cropRect.offset(dx, dy)
            LEFT -> {
                cropRect.left += dx; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(true)
            }

            RIGHT -> {
                cropRect.right += dx; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(true)
            }

            TOP -> {
                cropRect.top += dy; if (aspectRatio != AspectRatio.FREE) adjustWidthForHeight(true)
            }

            BOTTOM -> {
                cropRect.bottom += dy; if (aspectRatio != AspectRatio.FREE) adjustWidthForHeight(
                    true
                )
            }

            TOP_LEFT -> {
                cropRect.left += dx; cropRect.top += dy; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(
                    false
                )
            }

            TOP_RIGHT -> {
                cropRect.right += dx; cropRect.top += dy; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(
                    false
                )
            }

            BOTTOM_LEFT -> {
                cropRect.left += dx; cropRect.bottom += dy; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(
                    true
                )
            }

            BOTTOM_RIGHT -> {
                cropRect.right += dx; cropRect.bottom += dy; if (aspectRatio != AspectRatio.FREE) adjustHeightForWidth(
                    true
                )
            }
        }
        enforceBounds()
    }

    private fun adjustHeightForWidth(anchorBottom: Boolean) {
        val newH = cropRect.width() * (aspectRatio.heightRatio / aspectRatio.widthRatio)
        if (anchorBottom) cropRect.bottom = cropRect.top + newH else cropRect.top =
            cropRect.bottom - newH
    }

    private fun adjustWidthForHeight(anchorRight: Boolean) {
        val newW = cropRect.height() * (aspectRatio.widthRatio / aspectRatio.heightRatio)
        if (anchorRight) cropRect.right = cropRect.left + newW else cropRect.left =
            cropRect.right - newW
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    private fun effectiveBounds(): RectF =
        videoBounds ?: RectF(0f, 0f, width.toFloat(), height.toFloat())

    private fun enforceBounds() {
        val b = effectiveBounds()
        if (aspectRatio == AspectRatio.FREE) {
            if (cropRect.width() < minSize) cropRect.right = cropRect.left + minSize
            if (cropRect.height() < minSize) cropRect.bottom = cropRect.top + minSize
            cropRect.left = max(b.left, cropRect.left)
            cropRect.top = max(b.top, cropRect.top)
            cropRect.right = min(b.right, cropRect.right)
            cropRect.bottom = min(b.bottom, cropRect.bottom)
        } else {
            if (cropRect.width() < minSize) {
                cropRect.right = cropRect.left + minSize; adjustHeightForWidth(true)
            }
            if (cropRect.height() < minSize) {
                cropRect.bottom = cropRect.top + minSize; adjustWidthForHeight(true)
            }
            if (cropRect.left < b.left) {
                cropRect.right -= cropRect.left - b.left; cropRect.left = b.left
            }
            if (cropRect.top < b.top) {
                cropRect.bottom -= cropRect.top - b.top; cropRect.top = b.top
            }
            if (cropRect.right > b.right) {
                cropRect.left -= cropRect.right - b.right; cropRect.right = b.right
            }
            if (cropRect.bottom > b.bottom) {
                cropRect.top -= cropRect.bottom - b.bottom; cropRect.bottom = b.bottom
            }
            cropRect.left = max(b.left, cropRect.left)
            cropRect.top = max(b.top, cropRect.top)
            cropRect.right = min(b.right, cropRect.right)
            cropRect.bottom = min(b.bottom, cropRect.bottom)
        }
    }

    private fun applyAspectRatioToRect() {
        val b = effectiveBounds()
        if (b.width() == 0f || b.height() == 0f) return
        val ratio = aspectRatio.heightRatio / aspectRatio.widthRatio
        val cx = cropRect.centerX();
        val cy = cropRect.centerY()
        var newW = cropRect.width();
        var newH = newW * ratio
        if (newH > b.height()) {
            newH = b.height(); newW = newH / ratio
        }
        if (newW > b.width()) {
            newW = b.width(); newH = newW * ratio
        }
        cropRect.set(cx - newW / 2f, cy - newH / 2f, cx + newW / 2f, cy + newH / 2f)
        enforceBounds()
    }
}

@Parcelize
data class CropCoordinates(
    val topLeft: Point,
    val topRight: Point,
    val bottomLeft: Point,
    val bottomRight: Point
) : Parcelable