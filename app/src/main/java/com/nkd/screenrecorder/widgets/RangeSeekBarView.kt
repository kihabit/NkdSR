package com.nkd.screenrecorder.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.dpToPx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RangeSeekBarView : View {
    private var mActivePointerId: Int = INVALID_POINTER_ID

    private var mMinShootTime: Long = VideoTrimmerUtil.MIN_SHOOT_DURATION
    private var absoluteMinValuePrim = 0.0
    private var absoluteMaxValuePrim = 0.0

    // Single source of truth for BOTH drawing position and time value (0.0 - 1.0)
    internal var normalizedMinValue = 0.0
    internal var normalizedMaxValue = 1.0

    private var mScaledTouchSlop = 0
    private var thumbImageLeft: Bitmap? = null
    private var thumbImageRight: Bitmap? = null
    private var thumbPressedImage: Bitmap? = null
    private var paint: Paint? = null
    private var rectPaint: Paint? = null
    private val mShadow = Paint()
    private var thumbWidth = 0
    private var thumbHalfWidth = 0f
    private val padding = 0f
    private val thumbPaddingTop = 0f
    private var isTouchDown = false
    private var mDownMotionX = 0f
    private var mIsDragging = false
    private var pressedThumb: Thumb? = null
    private var isMin = false

    var isNotifyWhileDragging: Boolean = false
    private var mRangeSeekBarChangeListener: OnRangeSeekBarChangeListener? = null

    // Tap/drag anywhere inside the selected trim range to seek the video directly,
    // without moving either thumb.
    private var mIsSeekTapping = false
    var onSeekTap: ((Long) -> Unit)? = null
    private val whiteColorRes = context.resources.getColor(R.color.white)

    enum class Thumb {
        MIN, MAX
    }

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        absoluteMinValuePrim: Long,
        absoluteMaxValuePrim: Long
    ) : super(context) {
        this.absoluteMinValuePrim = absoluteMinValuePrim.toDouble()
        this.absoluteMaxValuePrim = absoluteMaxValuePrim.toDouble()
        setFocusable(true)
        setFocusableInTouchMode(true)
        init()
    }

    private fun init() {
        mScaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        thumbImageLeft =
            BitmapFactory.decodeResource(resources, R.drawable.ic_video_thumb_handle)

        val width = thumbImageLeft!!.getWidth()
        val height = thumbImageLeft!!.getHeight()
        val newWidth: Int = 11.dpToPx()
        val newHeight: Int = 55.dpToPx()
        val scaleWidth = newWidth * 1.0f / width
        val scaleHeight = newHeight * 1.0f / height
        val matrix = Matrix()
        matrix.postScale(scaleWidth, scaleHeight)
        thumbImageLeft = Bitmap.createBitmap(thumbImageLeft!!, 0, 0, width, height, matrix, true)

        // Orange tint
        val orangePaint = Paint().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                android.graphics.Color.parseColor("#FFFF7700"),
                android.graphics.PorterDuff.Mode.SRC_ATOP
            )
        }
        val orangeBitmap = Bitmap.createBitmap(thumbImageLeft!!.width, thumbImageLeft!!.height, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(orangeBitmap)
        tempCanvas.drawBitmap(thumbImageLeft!!, 0f, 0f, orangePaint)
        thumbImageLeft = orangeBitmap
        thumbImageRight = orangeBitmap
        thumbPressedImage = orangeBitmap

        thumbWidth = newWidth
        thumbHalfWidth = (thumbWidth / 2).toFloat()
        mShadow.isAntiAlias = true
        mShadow.setARGB(190, 0, 0, 0)
        paint = Paint(Paint.ANTI_ALIAS_FLAG)
        rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        rectPaint!!.style = Paint.Style.FILL
        rectPaint!!.setColor(whiteColorRes)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = 300
        if (MeasureSpec.UNSPECIFIED != MeasureSpec.getMode(widthMeasureSpec)) {
            width = MeasureSpec.getSize(widthMeasureSpec)
        }
        var height = 140
        if (MeasureSpec.UNSPECIFIED != MeasureSpec.getMode(heightMeasureSpec)) {
            height = MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(width, height)
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bgMiddleLeft = 0f
        val bgMiddleRight = (width - getPaddingRight()).toFloat()
        val rangeL = normalizedToScreen(normalizedMinValue)
        val rangeR = normalizedToScreen(normalizedMaxValue)
        val leftRect = Rect(bgMiddleLeft.toInt(), 0, rangeL.toInt(), height)
        val rightRect = Rect(rangeR.toInt(), 0, bgMiddleRight.toInt(), height)

        // dark mask outside the selected trim range
        canvas.drawRect(leftRect, mShadow)
        canvas.drawRect(rightRect, mShadow)

        // thin white top/bottom borders across the selected range
        canvas.drawRect(
            rangeL,
            thumbPaddingTop,
            rangeR,
            thumbPaddingTop + (3.dpToPx()),
            rectPaint!!
        )
        canvas.drawRect(
            rangeL,
            (height - (3.dpToPx())).toFloat(),
            rangeR,
            height.toFloat(),
            rectPaint!!
        )

        drawThumb(normalizedToScreen(normalizedMinValue), false, canvas, true)
        drawThumb(normalizedToScreen(normalizedMaxValue), false, canvas, false)
    }

    private fun drawThumb(screenCoord: Float, pressed: Boolean, canvas: Canvas, isLeft: Boolean) {
        val bmp = (if (pressed) thumbPressedImage else (if (isLeft) thumbImageLeft else thumbImageRight))!!
        val left = screenCoord - (if (isLeft) 0f else thumbWidth.toFloat())
        // Stretch the handle to the FULL current height of the view (not a fixed dp size),
        // so it always reaches the bottom of the thumbnail strip regardless of container height.
        val dst = RectF(left, thumbPaddingTop, left + thumbWidth, height.toFloat())
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun disallowParentIntercept() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    /**
     * When this view is added programmatically (VideoTrimmerView adds it dynamically after
     * the video's duration is known) and the user taps it before the very first layout pass
     * has happened, getWidth()/getHeight() are still 0. Every screen<->normalized conversion
     * (normalizedToScreen, screenToNormalized, evalPressedThumb) divides/multiplies by width,
     * so on that first touch the thumbs are computed as sitting at x=0 and the tap almost
     * always misses them - the touch is swallowed by "return super.onTouchEvent(event)" in
     * evalPressedThumb's null case. By the *second* touch a layout pass has already run in
     * between, width is correct, and it works - matching the reported "first touch is wrong"
     * behaviour. Force a synchronous measure+layout here so width/height are valid even if
     * the normal layout traversal hasn't happened yet.
     */
    private fun ensureLaidOut() {
        if (width > 0 && height > 0) return
        val parentView = parent as? View ?: return
        val parentWidth = parentView.width
        val parentHeight = parentView.height
        if (parentWidth <= 0 || parentHeight <= 0) return
        measure(
            MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.EXACTLY)
        )
        layout(left, top, left + parentWidth, top + parentHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchDown) {
            return super.onTouchEvent(event)
        }
        if (event.pointerCount > 1) {
            return super.onTouchEvent(event)
        }

        if (!isEnabled) return false
        if (absoluteMaxValuePrim <= mMinShootTime) {
            return super.onTouchEvent(event)
        }
        ensureLaidOut()
        val pointerIndex: Int
        val action = event.action
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept()
                mActivePointerId = event.getPointerId(event.pointerCount - 1)
                pointerIndex = event.findPointerIndex(mActivePointerId)
                mDownMotionX = event.getX(pointerIndex)
                pressedThumb = evalPressedThumb(mDownMotionX)

                if (pressedThumb == null) {
                    val rangeL = normalizedToScreen(normalizedMinValue)
                    val rangeR = normalizedToScreen(normalizedMaxValue)
                    if (mDownMotionX in rangeL..rangeR) {
                        mIsSeekTapping = true
                        handleSeekTap(mDownMotionX)
                        return true
                    }
                    return super.onTouchEvent(event)
                }

                setPressed(true)
                onStartTrackingTouch()
                trackTouchEvent(event)
                attemptClaimDrag()
                if (mRangeSeekBarChangeListener != null) {
                    mRangeSeekBarChangeListener!!.onRangeSeekBarValuesChanged(
                        this,
                        this.selectedMinValue,
                        this.selectedMaxValue, MotionEvent.ACTION_DOWN, isMin,
                        pressedThumb
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                disallowParentIntercept()
                if (mIsSeekTapping) {
                    pointerIndex = event.findPointerIndex(mActivePointerId)
                    val x = event.getX(pointerIndex)
                    handleSeekTap(x)
                    return true
                }
                if (pressedThumb != null) {
                    if (mIsDragging) {
                        trackTouchEvent(event)
                    } else {
                        pointerIndex = event.findPointerIndex(mActivePointerId)
                        val x = event.getX(pointerIndex)
                        if (abs(x - mDownMotionX) > mScaledTouchSlop) {
                            setPressed(true)
                            invalidate()
                            onStartTrackingTouch()
                            trackTouchEvent(event)
                            attemptClaimDrag()
                        }
                    }
                    if (this.isNotifyWhileDragging && mRangeSeekBarChangeListener != null) {
                        mRangeSeekBarChangeListener!!.onRangeSeekBarValuesChanged(
                            this,
                            this.selectedMinValue,
                            this.selectedMaxValue, MotionEvent.ACTION_MOVE,
                            isMin, pressedThumb
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mIsSeekTapping) {
                    mIsSeekTapping = false
                    invalidate()
                    pressedThumb = null
                    return true
                }

                if (mIsDragging) {
                    trackTouchEvent(event)
                    onStopTrackingTouch()
                    setPressed(false)
                } else {
                    onStartTrackingTouch()
                    trackTouchEvent(event)
                    onStopTrackingTouch()
                }

                invalidate()
                if (mRangeSeekBarChangeListener != null) {
                    mRangeSeekBarChangeListener!!.onRangeSeekBarValuesChanged(
                        this,
                        this.selectedMinValue,
                        this.selectedMaxValue, MotionEvent.ACTION_UP, isMin,
                        pressedThumb
                    )
                }
                pressedThumb = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.pointerCount - 1
                mDownMotionX = event.getX(index)
                mActivePointerId = event.getPointerId(index)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(event)
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                mIsSeekTapping = false
                if (mIsDragging) {
                    onStopTrackingTouch()
                    setPressed(false)
                }
                invalidate()
            }

            else -> {}
        }
        return true
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val pointerIndex =
            (ev.action and ACTION_POINTER_INDEX_MASK) shr ACTION_POINTER_INDEX_SHIFT
        val pointerId = ev.getPointerId(pointerIndex)
        if (pointerId == mActivePointerId) {
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            mDownMotionX = ev.getX(newPointerIndex)
            mActivePointerId = ev.getPointerId(newPointerIndex)
        }
    }

    private fun handleSeekTap(touchX: Float) {
        val w = getWidth()
        if (w <= 0) return
        val raw = touchX.toDouble() / w.toDouble()
        // Only allow seeking within the currently selected trim range.
        val clamped = raw.coerceIn(normalizedMinValue, normalizedMaxValue)
        val value = normalizedToValue(clamped)
        onSeekTap?.invoke(value)
    }

    private fun trackTouchEvent(event: MotionEvent) {
        if (event.pointerCount > 1) return
        val pointerIndex = event.findPointerIndex(mActivePointerId)
        var x = 0f
        try {
            x = event.getX(pointerIndex)
        } catch (e: Exception) {
            return
        }
        if (Thumb.MIN == pressedThumb) {
            setNormalizedMinValue(screenToNormalized(x, 0))
        } else if (Thumb.MAX == pressedThumb) {
            setNormalizedMaxValue(screenToNormalized(x, 1))
        }
    }

    private fun screenToNormalized(screenCoord: Float, position: Int): Double {
        val w = getWidth()
        if (w <= 0) return 0.0
        isMin = false

        val raw = (screenCoord.toDouble() / w.toDouble())
        val clamped = min(1.0, max(0.0, raw))

        val totalRange = absoluteMaxValuePrim - absoluteMinValuePrim
        val minGapNormalized = if (totalRange > 0) mMinShootTime / totalRange else 0.0

        return if (position == 0) {
            val limit = normalizedMaxValue - minGapNormalized
            if (clamped > limit) {
                isMin = true
                max(0.0, limit)
            } else {
                clamped
            }
        } else {
            val limit = normalizedMinValue + minGapNormalized
            if (clamped < limit) {
                isMin = true
                min(1.0, limit)
            } else {
                clamped
            }
        }
    }

    private fun evalPressedThumb(touchX: Float): Thumb? {
        var result: Thumb? = null
        val minThumbPressed = isInThumbRange(touchX, normalizedMinValue, 2.0)
        val maxThumbPressed = isInThumbRange(touchX, normalizedMaxValue, 2.0)
        if (minThumbPressed && maxThumbPressed) {
            result = if (touchX / width > 0.5f) Thumb.MIN else Thumb.MAX
        } else if (minThumbPressed) {
            result = Thumb.MIN
        } else if (maxThumbPressed) {
            result = Thumb.MAX
        }
        return result
    }

    private fun isInThumbRange(
        touchX: Float,
        normalizedThumbValue: Double,
        scale: Double
    ): Boolean {
        return abs(touchX - normalizedToScreen(normalizedThumbValue)) <= thumbHalfWidth * scale
    }

    private fun attemptClaimDrag() {
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true)
        }
    }

    fun onStartTrackingTouch() {
        mIsDragging = true
    }

    fun onStopTrackingTouch() {
        mIsDragging = false
    }

    fun setMinShootTime(min_cut_time: Long) {
        this.mMinShootTime = min_cut_time
    }

    private fun normalizedToScreen(normalizedCoord: Double): Float {
        return (getPaddingLeft() + normalizedCoord * (width - getPaddingLeft() - getPaddingRight())).toFloat()
    }

    private fun valueToNormalized(value: Long): Double {
        if (0.0 == absoluteMaxValuePrim - absoluteMinValuePrim) {
            return 0.0
        }
        return (value - absoluteMinValuePrim) / (absoluteMaxValuePrim - absoluteMinValuePrim)
    }

    private fun normalizedToValue(normalized: Double): Long {
        return (absoluteMinValuePrim + normalized * (absoluteMaxValuePrim - absoluteMinValuePrim)).toLong()
    }

    fun setNormalizedMinValue(value: Double) {
        normalizedMinValue = max(0.0, min(1.0, min(value, normalizedMaxValue)))
        invalidate()
    }

    fun setNormalizedMaxValue(value: Double) {
        normalizedMaxValue = max(0.0, min(1.0, max(value, normalizedMinValue)))
        invalidate()
    }

    var selectedMinValue: Long
        get() = normalizedToValue(normalizedMinValue)
        set(value) {
            if (0.0 == (absoluteMaxValuePrim - absoluteMinValuePrim)) {
                setNormalizedMinValue(0.0)
            } else {
                setNormalizedMinValue(valueToNormalized(value))
            }
        }

    var selectedMaxValue: Long
        get() = normalizedToValue(normalizedMaxValue)
        set(value) {
            if (0.0 == (absoluteMaxValuePrim - absoluteMinValuePrim)) {
                setNormalizedMaxValue(1.0)
            } else {
                setNormalizedMaxValue(valueToNormalized(value))
            }
        }

    fun setTouchDown(touchDown: Boolean) {
        isTouchDown = touchDown
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable("SUPER", super.onSaveInstanceState())
        bundle.putDouble("MIN", normalizedMinValue)
        bundle.putDouble("MAX", normalizedMaxValue)
        return bundle
    }

    override fun onRestoreInstanceState(parcel: Parcelable?) {
        val bundle = parcel as Bundle
        super.onRestoreInstanceState(bundle.getParcelable<Parcelable?>("SUPER"))
        normalizedMinValue = bundle.getDouble("MIN")
        normalizedMaxValue = bundle.getDouble("MAX")
    }

    interface OnRangeSeekBarChangeListener {
        fun onRangeSeekBarValuesChanged(
            bar: RangeSeekBarView?,
            minValue: Long,
            maxValue: Long,
            action: Int,
            isMin: Boolean,
            pressedThumb: Thumb?
        )
    }

    fun setOnRangeSeekBarChangeListener(listener: OnRangeSeekBarChangeListener?) {
        this.mRangeSeekBarChangeListener = listener
    }

    companion object {
        private val TAG: String = RangeSeekBarView::class.java.getSimpleName()
        const val INVALID_POINTER_ID: Int = 255
        const val ACTION_POINTER_INDEX_MASK: Int = 0x0000ff00
        const val ACTION_POINTER_INDEX_SHIFT: Int = 8
    }
}