package com.nkd.screenrecorder.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.nkd.screenrecorder.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class DrawMode {
    FREEHAND, RECTANGLE, CIRCLE, ARROW, ERASER, CHECKBOX
}

class DrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentMode = DrawMode.FREEHAND
    private var currentColor = Color.RED
    private var strokeWidth = 10f

    // For smooth drawing
    private var currentPath = Path()
    private var currentPaint = Paint()

    // For shapes
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawingShape = false

    // History for undo
    private val paths = mutableListOf<DrawingPath>()
    private val redoPaths = mutableListOf<DrawingPath>()

    // Bitmap for better performance
    private var canvasBitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)

    // For smooth curves
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val TOUCH_TOLERANCE = 4f

    // Simple exclusion - reference to toolbar and palette views
    var toolbarView: View? = null
    var paletteView: View? = null


    init {
        setupPaint()
    }

    /**
     * Check if a touch point is on the toolbar or palette
     */
    private fun isTouchOnUIElement(rawX: Float, rawY: Float): Boolean {
        // Check toolbar
        toolbarView?.let { toolbar ->
            if (toolbar.isVisible) {
                val location = IntArray(2)
                toolbar.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val right = left + toolbar.width
                val bottom = top + toolbar.height

                if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                    return true
                }
            }
        }

        // Check palette
        paletteView?.let { palette ->
            if (palette.isVisible) {
                val location = IntArray(2)
                palette.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val right = left + palette.width
                val bottom = top + palette.height

                if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                    return true
                }
            }
        }

        return false
    }

    private fun setupPaint() {
        currentPaint.apply {
            isAntiAlias = true
            isDither = true
            color = currentColor
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = this@DrawingOverlayView.strokeWidth
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmapCanvas = Canvas(canvasBitmap!!)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw the bitmap (completed paths)
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, bitmapPaint)
        }

        // Draw current path or shape being drawn
        when (currentMode) {
            DrawMode.FREEHAND, DrawMode.ERASER -> {
                canvas.drawPath(currentPath, currentPaint)
            }

            DrawMode.CHECKBOX -> {
                // Show live preview of checkbox pattern while drawing
                drawCheckboxPatternPreview(canvas, currentPath, currentPaint)
            }

            DrawMode.RECTANGLE -> {
                if (isDrawingShape) {
                    canvas.drawRect(
                        minOf(startX, endX),
                        minOf(startY, endY),
                        maxOf(startX, endX),
                        maxOf(startY, endY),
                        currentPaint
                    )
                }
            }

            DrawMode.CIRCLE -> {
                if (isDrawingShape) {
                    val radius = sqrt(
                        (endX - startX) * (endX - startX) +
                                (endY - startY) * (endY - startY)
                    )
                    canvas.drawCircle(startX, startY, radius, currentPaint)
                }
            }

            DrawMode.ARROW -> {
                if (isDrawingShape) {
                    drawArrow(canvas, startX, startY, endX, endY, currentPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Check if touch is on toolbar or palette using screen coordinates
        if (isTouchOnUIElement(event.rawX, event.rawY)) {
            // Don't handle this touch, let it pass through to UI elements
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStart(x, y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                touchMove(x, y)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                touchUp(x, y)
                invalidate()
            }
        }
        return true
    }

    private fun touchStart(x: Float, y: Float) {
        redoPaths.clear()

        // ALWAYS create a fresh paint object for every new stroke/shape
        // This ensures color and stroke width changes don't affect ongoing drawings
        when (currentMode) {
            DrawMode.FREEHAND -> {
                currentPaint = createFreshPaint()
                currentPath.reset()
                currentPath.moveTo(x, y)
                lastTouchX = x
                lastTouchY = y
            }

            DrawMode.CHECKBOX -> {
                currentPaint = createCheckerPaint()
                currentPath.reset()
                currentPath.moveTo(x, y)
                lastTouchX = x
                lastTouchY = y
            }

            DrawMode.ERASER -> {
                currentPaint = createEraserPaint()
                currentPath.reset()
                currentPath.moveTo(x, y)
                lastTouchX = x
                lastTouchY = y
            }

            DrawMode.RECTANGLE, DrawMode.CIRCLE, DrawMode.ARROW -> {
                currentPaint = createFreshPaint()
                startX = x
                startY = y
                endX = x
                endY = y
                isDrawingShape = true
            }
        }
    }

    /**
     * Creates a fresh paint object with current drawing settings
     */
    private fun createFreshPaint(): Paint {
        // Ensure we have a valid color (not transparent)
        val paintColor = if (currentColor == Color.TRANSPARENT || currentColor == 0) {
            Log.w(
                "DrawingOverlay",
                "WARNING: currentColor is invalid ($currentColor), using RED as fallback"
            )
            Color.RED
        } else {
            currentColor
        }

        Log.d("DrawingOverlay", "createFreshPaint - color: $paintColor, width: $strokeWidth")
        return Paint().apply {
            isAntiAlias = true
            isDither = true
            color = paintColor
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = this@DrawingOverlayView.strokeWidth
        }
    }

    /**
     * Creates a fresh paint object for eraser mode
     */
    private fun createEraserPaint(): Paint {
        return Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = this@DrawingOverlayView.strokeWidth
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }

    private fun createCheckerPaint(): Paint {
        Log.d("DrawingOverlay", "CreateCheckerPaint - creating checkerboard pattern from drawable")

        // Get drawable from resources
        val checkerDrawable = ContextCompat.getDrawable(context, R.drawable.checker_pattern)

        // Convert drawable to bitmap
        val checkerBitmap = if (checkerDrawable != null) {
            val bitmap = Bitmap.createBitmap(
                checkerDrawable.intrinsicWidth.takeIf { it > 0 } ?: 20,
                checkerDrawable.intrinsicHeight.takeIf { it > 0 } ?: 20,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            checkerDrawable.setBounds(0, 0, canvas.width, canvas.height)
            checkerDrawable.draw(canvas)
            bitmap
        } else {
            Log.d("DrawingOverlay", "CreateCheckerPaint - creating checkerboard pattern Manually")
            // Fallback: create simple checker bitmap if drawable not found
            createCheckerBitmap()
        }

        return Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = this@DrawingOverlayView.strokeWidth
            shader = BitmapShader(
                checkerBitmap,
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT
            )
        }
    }

    // Fallback function to create checker bitmap programmatically
    private fun createCheckerBitmap(): Bitmap {
        val checkerSize = 8
        val patternSize = checkerSize * 2
        val bitmap = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val grayPaint =
            Paint().apply { color = Color.parseColor("#000000"); style = Paint.Style.FILL }

        canvas.drawRect(0f, 0f, checkerSize.toFloat(), checkerSize.toFloat(), grayPaint)
        canvas.drawRect(
            checkerSize.toFloat(),
            0f,
            patternSize.toFloat(),
            checkerSize.toFloat(),
            whitePaint
        )
        canvas.drawRect(
            checkerSize.toFloat(),
            checkerSize.toFloat(),
            patternSize.toFloat(),
            patternSize.toFloat(),
            grayPaint
        )
        canvas.drawRect(
            0f,
            checkerSize.toFloat(),
            checkerSize.toFloat(),
            patternSize.toFloat(),
            whitePaint
        )

        return bitmap
    }

    private fun touchMove(x: Float, y: Float) {
        when (currentMode) {
            DrawMode.FREEHAND, DrawMode.ERASER, DrawMode.CHECKBOX -> {
                val dx = abs(x - lastTouchX)
                val dy = abs(y - lastTouchY)

                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    // Use quadratic bezier curve for smooth drawing
                    currentPath.quadTo(
                        lastTouchX,
                        lastTouchY,
                        (x + lastTouchX) / 2,
                        (y + lastTouchY) / 2
                    )
                    lastTouchX = x
                    lastTouchY = y
                }
            }

            DrawMode.RECTANGLE, DrawMode.CIRCLE, DrawMode.ARROW -> {
                endX = x
                endY = y
            }
        }
    }

    private fun touchUp(x: Float, y: Float) {
        when (currentMode) {
            DrawMode.FREEHAND, DrawMode.ERASER -> {
                currentPath.lineTo(lastTouchX, lastTouchY)

                // Commit to bitmap
                bitmapCanvas?.drawPath(currentPath, currentPaint)

                // Save to history with a copy of the paint
                paths.add(DrawingPath(Path(currentPath), Paint(currentPaint), currentMode))

                // Reset for next drawing
                currentPath.reset()
            }

            DrawMode.CHECKBOX -> {
                currentPath.lineTo(lastTouchX, lastTouchY)

                // Don't draw the path itself, only draw checkbox pattern
                drawCheckboxPattern(currentPath, currentPaint)

                // Save to history
                paths.add(DrawingPath(Path(currentPath), Paint(currentPaint), currentMode))

                // Reset for next drawing
                currentPath.reset()
            }

            DrawMode.RECTANGLE -> {
                if (isDrawingShape) {
                    val rect = RectF(
                        minOf(startX, endX),
                        minOf(startY, endY),
                        maxOf(startX, endX),
                        maxOf(startY, endY)
                    )
                    bitmapCanvas?.drawRect(rect, currentPaint)

                    val shapePath = Path().apply {
                        addRect(rect, Path.Direction.CW)
                    }
                    paths.add(DrawingPath(shapePath, Paint(currentPaint), currentMode))
                    isDrawingShape = false
                }
            }

            DrawMode.CIRCLE -> {
                if (isDrawingShape) {
                    val radius = sqrt(
                        (endX - startX) * (endX - startX) +
                                (endY - startY) * (endY - startY)
                    )
                    bitmapCanvas?.drawCircle(startX, startY, radius, currentPaint)

                    val shapePath = Path().apply {
                        addCircle(startX, startY, radius, Path.Direction.CW)
                    }
                    paths.add(DrawingPath(shapePath, Paint(currentPaint), currentMode))
                    isDrawingShape = false
                }
            }

            DrawMode.ARROW -> {
                if (isDrawingShape) {
                    bitmapCanvas?.let { canvas ->
                        drawArrow(canvas, startX, startY, endX, endY, currentPaint)
                    }

                    val shapePath = Path()
                    createArrowPath(shapePath, startX, startY, endX, endY)
                    paths.add(DrawingPath(shapePath, Paint(currentPaint), currentMode))
                    isDrawingShape = false
                }
            }
        }
    }

    /**
     * Draw checkbox/checkerboard pattern along the path
     */
    /**
     * Draw checkbox/checkerboard pattern along the path
     */
    private fun drawCheckboxPattern(path: Path, paint: Paint) {
        val pathMeasure = android.graphics.PathMeasure(path, false)
        val pathLength = pathMeasure.length

        val checkerSize = strokeWidth * 1.5f

        // Create checker bitmap for the pattern
        val checkerBitmap = createCheckerBitmap()
        val checkerPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = BitmapShader(checkerBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        val pos = FloatArray(2)
        val tan = FloatArray(2)

        var distance = 0f
        val step = checkerSize / 2f

        while (distance < pathLength) {
            pathMeasure.getPosTan(distance, pos, tan)

            // Calculate rotation angle based on tangent
            val angle = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()

            // Draw alternating checkers
            val checkerIndex = (distance / step).toInt()
            if (checkerIndex % 2 == 0) {
                bitmapCanvas?.save()
                bitmapCanvas?.translate(pos[0], pos[1])
                bitmapCanvas?.rotate(angle)

                // Draw rectangle with checker pattern
                bitmapCanvas?.drawRect(
                    -checkerSize / 2,
                    -checkerSize / 2,
                    checkerSize / 2,
                    checkerSize / 2,
                    checkerPaint
                )

                bitmapCanvas?.restore()
            }

            distance += step
        }
    }

    /**
     * Preview checkbox pattern while drawing (for live feedback)
     */
    /**
     * Preview checkbox pattern while drawing (for live feedback)
     */
    private fun drawCheckboxPatternPreview(canvas: Canvas, path: Path, paint: Paint) {
        val pathMeasure = android.graphics.PathMeasure(path, false)
        val pathLength = pathMeasure.length

        val checkerSize = strokeWidth * 1.5f

        // Create checker bitmap for the pattern
        val checkerBitmap = createCheckerBitmap()
        val checkerPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = BitmapShader(checkerBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        var distance = 0f
        val step = checkerSize / 2f

        while (distance < pathLength) {
            pathMeasure.getPosTan(distance, pos, tan)
            val angle = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
            val checkerIndex = (distance / step).toInt()

            if (checkerIndex % 2 == 0) {
                canvas.save()
                canvas.translate(pos[0], pos[1])
                canvas.rotate(angle)

                // Draw rectangle with checker pattern
                canvas.drawRect(
                    -checkerSize / 2,
                    -checkerSize / 2,
                    checkerSize / 2,
                    checkerSize / 2,
                    checkerPaint
                )

                canvas.restore()
            }
            distance += step
        }
    }

    private fun drawArrow(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        paint: Paint
    ) {
        // Draw line
        canvas.drawLine(x1, y1, x2, y2, paint)

        // Calculate arrow head
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLength = 40f
        val arrowAngle = Math.toRadians(30.0)

        val x3 = (x2 - arrowLength * kotlin.math.cos(angle - arrowAngle)).toFloat()
        val y3 = (y2 - arrowLength * kotlin.math.sin(angle - arrowAngle)).toFloat()
        val x4 = (x2 - arrowLength * kotlin.math.cos(angle + arrowAngle)).toFloat()
        val y4 = (y2 - arrowLength * kotlin.math.sin(angle + arrowAngle)).toFloat()

        // Draw arrow head
        val arrowPath = Path().apply {
            moveTo(x2, y2)
            lineTo(x3, y3)
            moveTo(x2, y2)
            lineTo(x4, y4)
        }
        canvas.drawPath(arrowPath, paint)
    }

    private fun createArrowPath(path: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)

        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLength = 40f
        val arrowAngle = Math.toRadians(30.0)

        val x3 = (x2 - arrowLength * kotlin.math.cos(angle - arrowAngle)).toFloat()
        val y3 = (y2 - arrowLength * kotlin.math.sin(angle - arrowAngle)).toFloat()
        val x4 = (x2 - arrowLength * kotlin.math.cos(angle + arrowAngle)).toFloat()
        val y4 = (y2 - arrowLength * kotlin.math.sin(angle + arrowAngle)).toFloat()

        path.moveTo(x2, y2)
        path.lineTo(x3, y3)
        path.moveTo(x2, y2)
        path.lineTo(x4, y4)
    }

    fun setMode(mode: DrawMode) {
        currentMode = mode
        // Don't call setupPaint here, it will be set fresh on next touch
    }

    /**
     * Set color - accepts both direct color Int and color resource ID
     * Only updates the stored color value - fresh paint will be created on next stroke
     */
    fun setColor(color: Int) {
        val resolvedColor = try {
            // Try to resolve as resource ID first
            ContextCompat.getColor(context, color)
        } catch (e: Exception) {
            Log.d(
                "DrawingOverlay",
                "${e.cause}"
            )
            // If it fails, treat it as a direct color value
            color
        }

        Log.d(
            "DrawingOverlay",
            "setColor called: input=$color, resolved=$resolvedColor (old: $currentColor)"
        )
        currentColor = resolvedColor
        // Don't modify currentPaint here - it will be created fresh on next touch
    }

    /**
     * Set stroke width
     * Only updates the stored width value - fresh paint will be created on next stroke
     */
    fun setStrokeWidth(width: Float) {
        Log.d("DrawingOverlay", "setStrokeWidth called: $width (old: $strokeWidth)")
        strokeWidth = width
        // Don't modify currentPaint here - it will be created fresh on next touch
    }

    fun getStrokeWidth(): Float {
        return strokeWidth
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            val lastPath = paths.removeAt(paths.size - 1)
            redoPaths.add(lastPath)
            redrawCanvas()
        }
    }

    fun redo() {
        if (redoPaths.isNotEmpty()) {
            val path = redoPaths.removeAt(redoPaths.size - 1)
            paths.add(path)
            redrawCanvas()
        }
    }

    fun clear() {
        paths.clear()
        redoPaths.clear()
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }

    private fun redrawCanvas() {
        canvasBitmap?.let { bitmap ->
            bitmapCanvas = Canvas(bitmap)
            bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            paths.forEach { drawingPath ->
                if (drawingPath.mode == DrawMode.ERASER) {
                    drawingPath.paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }

                // For checkbox mode, don't draw the path itself, only the pattern
                if (drawingPath.mode != DrawMode.CHECKBOX) {
                    bitmapCanvas?.drawPath(drawingPath.path, drawingPath.paint)
                }

                // Redraw checkbox pattern if needed
                if (drawingPath.mode == DrawMode.CHECKBOX) {
                    val pathMeasure = android.graphics.PathMeasure(drawingPath.path, false)
                    val pathLength = pathMeasure.length

                    val checkerSize = drawingPath.paint.strokeWidth * 1.5f

                    // Create checker bitmap for the pattern
                    val checkerBitmap = createCheckerBitmap()
                    val checkerPaint = Paint().apply {
                        isAntiAlias = true
                        style = Paint.Style.FILL
                        shader = BitmapShader(checkerBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    }

                    val pos = FloatArray(2)
                    val tan = FloatArray(2)
                    var distance = 0f
                    val step = checkerSize / 2f

                    while (distance < pathLength) {
                        pathMeasure.getPosTan(distance, pos, tan)
                        val angle = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
                        val checkerIndex = (distance / step).toInt()

                        if (checkerIndex % 2 == 0) {
                            bitmapCanvas?.save()
                            bitmapCanvas?.translate(pos[0], pos[1])
                            bitmapCanvas?.rotate(angle)
                            bitmapCanvas?.drawRect(
                                -checkerSize / 2,
                                -checkerSize / 2,
                                checkerSize / 2,
                                checkerSize / 2,
                                checkerPaint
                            )
                            bitmapCanvas?.restore()
                        }
                        distance += step
                    }
                }

                if (drawingPath.mode == DrawMode.ERASER) {
                    drawingPath.paint.xfermode = null
                }
            }
        }
        invalidate()
    }

    data class DrawingPath(
        val path: Path,
        val paint: Paint,
        val mode: DrawMode
    )
}