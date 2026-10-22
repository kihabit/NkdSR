package com.nkd.screenrecorder.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Facecam preview frame. With a [mask] set (facecam "Face only"), the children — the live,
 * full-quality camera preview — are drawn into an offscreen GPU layer and only the parts where
 * the small ALPHA_8 mask is opaque are kept (DST_IN); the rest is transparent, so the screen
 * behind the facecam window shows through. The mask is scaled up with bilinear filtering on the
 * GPU, which also gives it its soft edge. Without a mask it is a plain FrameLayout.
 * Redrawn only when a new mask arrives (the preview's own frames are composited by the GPU).
 */
class MaskedFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var mask: Bitmap? = null
    private var imageW = 0
    private var imageH = 0
    private var mirror = false
    private val maskMatrix = Matrix()
    private val maskPaint = Paint().apply {
        isFilterBitmap = true   // bilinear upscale = soft edge
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    init {
        setWillNotDraw(false)
    }

    /**
     * [mask] covers the whole upright camera frame of [imageW]×[imageH] (it may be stretched,
     * e.g. ML Kit's raw 256×256 output); it is center-cropped like the preview. Null = off.
     */
    fun setMask(mask: Bitmap?, imageW: Int, imageH: Int, mirror: Boolean) {
        val changedGeometry = imageW != this.imageW || imageH != this.imageH || mirror != this.mirror ||
                mask?.width != this.mask?.width || mask?.height != this.mask?.height
        this.mask = mask
        this.imageW = imageW
        this.imageH = imageH
        this.mirror = mirror
        if (changedGeometry) updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    /** mask → upright frame (stretch) → view (same center crop as CameraView's preview). */
    private fun updateMatrix() {
        val m = mask ?: return
        if (width == 0 || height == 0 || imageW == 0 || imageH == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val crop = max(w / imageW, h / imageH)
        maskMatrix.setScale(imageW * crop / m.width, imageH * crop / m.height)
        maskMatrix.postTranslate((w - imageW * crop) / 2f, (h - imageH * crop) / 2f)
        if (mirror) maskMatrix.postScale(-1f, 1f, w / 2f, 0f)   // front camera preview is mirrored
    }

    override fun dispatchDraw(canvas: Canvas) {
        val m = mask
        if (m == null || width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(m, maskMatrix, maskPaint)
        canvas.restoreToCount(save)
    }
}
