package com.nkd.screenrecorder.services

import com.nkd.screenrecorder.utils.LocaleHelper
import android.content.Context
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.imageview.ShapeableImageView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.ProjectionPermissionActivity
import com.nkd.screenrecorder.databinding.LayoutDeleteZoneBinding
import com.nkd.screenrecorder.databinding.LayoutFloatingTitanBinding
import com.nkd.screenrecorder.databinding.ViewDrawingColorPalletesBinding
import com.nkd.screenrecorder.databinding.ViewDrawingToolbarBinding
import com.nkd.screenrecorder.utils.DrawMode
import com.nkd.screenrecorder.utils.DrawingOverlayView
import com.nkd.screenrecorder.utils.dpToPx
import com.skydoves.colorpickerview.listeners.ColorListener
import kotlin.math.abs

class DrawingService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        var isRunning = false
        const val ACTION_SHOW_BRUSH = "show_brush"
        const val ACTION_HIDE_BRUSH = "hde_brush"
    }

    private val TAG: String = DrawingService::class.java.simpleName

    private lateinit var wm: WindowManager
    private lateinit var binding: LayoutFloatingTitanBinding
    private var drawView: DrawingOverlayView? = null
    private lateinit var params: WindowManager.LayoutParams

    var drawingBinding: ViewDrawingToolbarBinding? = null
    private lateinit var drawingViewParams: WindowManager.LayoutParams
    private var isDrawingVieVisible: Boolean = false
    private var isExpanded: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAnimator: AnimatorSet? = null
    private var isOnRightEdge = false
    private val SIZE_EXPANDED = 48
    private val SIZE_COLLAPSED = 36
    private val EDGE_PEEK_AMOUNT = 20
    private var deleteZoneBinding: LayoutDeleteZoneBinding? = null
    private var deleteZoneParams: WindowManager.LayoutParams? = null
    private var isDeleteZoneVisible = false
    private var isOverDeleteZone = false

    // Selected tool tracking
    private var selectedToolView: ImageView? = null
    private var currentDrawMode = DrawMode.FREEHAND

    lateinit var viewColorSelectorBinding: ViewDrawingColorPalletesBinding
    private lateinit var colorPalettesParam: WindowManager.LayoutParams
    private var isColorPalettesShown = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BRUSH -> {
                binding = LayoutFloatingTitanBinding.inflate(LayoutInflater.from(this), null, false)
                ViewCompat.setAccessibilityDelegate(
                    binding.mainFab,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = Button::class.java.name
                            info.contentDescription = getString(R.string.take_screenshot)
                            info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                        }
                    })
                params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )

                params.gravity = Gravity.TOP or Gravity.START
                params.x = resources.displayMetrics.widthPixels - 10
                params.y = (resources.displayMetrics.heightPixels / 2) - 100

                wm.addView(binding.root, params)
                binding.mainFab.alpha = 1f
                binding.rootLayout.alpha = 1f

                isExpanded = true
                mainHandler.postDelayed({
                    animateToCollapsed()
                }, 1000)
                setupDrag()
                setupTools()
                isRunning = true
                if (intent.getBooleanExtra("show_drawing_board", false)) {
                    showDrawingBoard()
                }
                val intent = Intent().apply {
                    action = ACTION_SHOW_BRUSH
                    `package` = packageName
                }
                sendBroadcast(intent)

            }

            ACTION_HIDE_BRUSH -> {
                isRunning = false
                stopSelf()

                val intent = Intent().apply {
                    action = ACTION_HIDE_BRUSH
                    `package` = packageName
                }
                sendBroadcast(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun animateToCollapsed() {
        if (!isExpanded) return
        currentAnimator?.cancel()

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val collapsedSizePx = (SIZE_COLLAPSED * displayMetrics.density).toInt()
        val peekAmountPx = (EDGE_PEEK_AMOUNT * displayMetrics.density).toInt()

        val viewWidth = binding.rootLayout.width
        isOnRightEdge = params.x + viewWidth / 2 >= screenWidth / 2

        val targetX = if (isOnRightEdge) {
            screenWidth - peekAmountPx
        } else {
            -collapsedSizePx + peekAmountPx
        }

        val animatorSet = AnimatorSet()

        val positionAnimator = ValueAnimator.ofInt(params.x, targetX)
        positionAnimator.addUpdateListener {
            params.x = it.animatedValue as Int
            try {
                wm.updateViewLayout(binding.root, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val sizeAnimator = ValueAnimator.ofInt(params.width, collapsedSizePx)
        sizeAnimator.addUpdateListener {
            val value = it.animatedValue as Int
            params.width = value
            params.height = value
            try {
                wm.updateViewLayout(binding.root, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val alphaAnimator = ValueAnimator.ofFloat(binding.mainFab.alpha, 0.7f)
        alphaAnimator.addUpdateListener {
            binding.mainFab.alpha = it.animatedValue as Float
            binding.rootLayout.alpha = it.animatedValue as Float
        }

        animatorSet.playTogether(positionAnimator, sizeAnimator, alphaAnimator)
        animatorSet.duration = 300
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isExpanded = false
                currentAnimator = null
            }
        })

        currentAnimator = animatorSet
        animatorSet.start()
    }

    private fun animateToExpanded(onComplete: (() -> Unit)? = null) {
        if (isExpanded) return
        currentAnimator?.cancel()

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val expandedSizePx = (SIZE_EXPANDED * displayMetrics.density).toInt()
        val targetX = if (isOnRightEdge) {
            screenWidth - expandedSizePx
        } else {
            0
        }

        val animatorSet = AnimatorSet()

        val positionAnimator = ValueAnimator.ofInt(params.x, targetX)
        positionAnimator.addUpdateListener {
            params.x = it.animatedValue as Int
            try {
                wm.updateViewLayout(binding.root, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val sizeAnimator = ValueAnimator.ofInt(params.width, expandedSizePx)
        sizeAnimator.addUpdateListener {
            val value = it.animatedValue as Int
            params.width = value
            params.height = value
            try {
                wm.updateViewLayout(binding.root, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val alphaAnimator = ValueAnimator.ofFloat(binding.mainFab.alpha, 1f)
        alphaAnimator.addUpdateListener {
            binding.mainFab.alpha = it.animatedValue as Float
            binding.rootLayout.alpha = it.animatedValue as Float
        }

        animatorSet.playTogether(positionAnimator, sizeAnimator, alphaAnimator)
        animatorSet.duration = 250
        animatorSet.interpolator = OvershootInterpolator(1.2f)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isExpanded = true
                currentAnimator = null
                onComplete?.invoke()
            }
        })

        currentAnimator = animatorSet
        animatorSet.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false
        var wasDragging = false

        binding.mainFab.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentAnimator?.cancel()
                    isMoving = false
                    wasDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = e.rawX
                    initialTouchY = e.rawY

                    if (!isExpanded) {
                        animateToExpanded()
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val rawX = (e.rawX - initialTouchX).toInt()
                    val rawY = (e.rawY - initialTouchY).toInt()

                    if (abs(rawX) > 10 || abs(rawY) > 10) {
                        isMoving = true
                        wasDragging = true

                        if (!isDeleteZoneVisible) {
                            showDeleteZone()
                        }
                    }

                    if (isMoving) {
                        val newX = initialX + rawX
                        val newY = initialY + rawY

                        params.x = newX
                        params.y = newY

                        try {
                            wm.updateViewLayout(binding.root, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout", e)
                        }

                        val wasOverDeleteZone = isOverDeleteZone
                        isOverDeleteZone = isInDeleteZone(newX, newY)

                        if (isOverDeleteZone != wasOverDeleteZone) {
                            updateScreenshotButtonAppearance(isOverDeleteZone)
                            updateDeleteZoneAppearance(isOverDeleteZone)

                            if (isOverDeleteZone) {
                                view.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.LONG_PRESS
                                )
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    hideDeleteZone()

                    if (wasDragging) {
                        if (isOverDeleteZone) {
                            hideFloatingButton()
                            isRunning = false
                            updateScreenshotButtonAppearance(false)
                        } else {
                            snapToEdge()
                        }
                    } else {
                        view.performClick()
                    }

                    isMoving = false
                    isOverDeleteZone = false
                    true
                }

                else -> false
            }
        }
    }

    private fun hideFloatingButton() {
        try {
            currentAnimator?.cancel()
            binding.let {
                wm.removeView(it.root)
            }
            val intent = Intent().apply {
                action = ACTION_HIDE_BRUSH
                `package` = packageName
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding floating button", e)
        }
    }

    private fun setupTools() {
        binding.mainFab.setOnClickListener {
            if (!isExpanded) {
                animateToExpanded {
                    if (!isDrawingVieVisible) {
                        binding.rootLayout.isGone = true
                        showDrawingBoard()
                    } else {
                        closeDrawingBoard()
                    }
                }
            } else {
                if (!isDrawingVieVisible) {
                    showDrawingBoard()
                } else {
                    closeDrawingBoard()
                }
            }
        }
    }

    private fun closeDrawingBoard() {
        if (isColorPalettesShown) {
            hideColorPalettes()
        }
        drawView?.toolbarView = null   // ← NEW LINE
        drawView?.paletteView = null   // ← NEW LINE

        drawingBinding?.let { wm.removeView(it.root) }
        drawView?.let { wm.removeView(it) }
        drawView = null
        drawingBinding = null
        selectedToolView = null
        isDrawingVieVisible = false
    }

    private fun showDrawingBoard() {
        startDrawing()
        drawingBinding = ViewDrawingToolbarBinding.inflate(LayoutInflater.from(this), null, false)
        drawingViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        drawingViewParams.x = resources.displayMetrics.widthPixels
        drawingViewParams.y = resources.displayMetrics.heightPixels

        wm.addView(drawingBinding!!.root, drawingViewParams)

        isDrawingVieVisible = true
        setupDrawingToolbar()
        setToolBoxDrag()

        // Set default tool (brush) as selected
        drawingBinding?.toolBrush?.let {
            selectTool(it, DrawMode.FREEHAND)
        }
        drawView?.toolbarView = drawingBinding?.root
    }

    private fun setupDrawingToolbar() {
        drawingBinding?.apply {
            toolBrush.setOnClickListener {
                selectTool(it as ImageView, DrawMode.FREEHAND)
            }

            toolSquare.setOnClickListener {
                selectTool(it as ImageView, DrawMode.RECTANGLE)
            }

            toolCircle.setOnClickListener {
                selectTool(it as ImageView, DrawMode.CIRCLE)
            }

            toolArrow.setOnClickListener {
                selectTool(it as ImageView, DrawMode.ARROW)
            }

            toolEraser.setOnClickListener {
                selectTool(it as ImageView, DrawMode.ERASER)
            }

            toolShape.setOnClickListener {
                selectTool(it as ImageView, DrawMode.CHECKBOX)
            }

            toolCamera.setOnClickListener {
                // ✅ Screenshots are disabled while a recording is running
                if (RecordScreenService.isRecording) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        getString(R.string.screenshot_unavailable_recording),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                // Take screenshot functionality
                val intent = Intent(this@DrawingService, ProjectionPermissionActivity::class.java)
                intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("REQUEST_TYPE", "SCREENSHOT")
                startActivity(intent)
            }

            toolUndo.setOnClickListener {
                drawView?.undo()
            }

            toolPalette.setOnClickListener {
                // Show color picker
                if (!isColorPalettesShown) {

                    addColorPalettes()
                } else {
                    hideColorPalettes()
                }
            }

            toolClose.setOnClickListener {
                closeDrawingBoard()
                binding.rootLayout.isVisible = true
                mainHandler.postDelayed({
                    animateToCollapsed()
                }, 1000)
            }
        }
    }

    /**
     * Select a tool and update the UI to show which tool is selected
     */
    private fun selectTool(toolView: ImageView, mode: DrawMode) {
        // Reset previous selected tool to default color
        selectedToolView?.let { previousTool ->
            resetToolColor(previousTool)
        }

        // Update the selected tool
        selectedToolView = toolView
        currentDrawMode = mode

        // Apply primary color to selected tool
        val primaryColor = try {
            ContextCompat.getColor(this, R.color.primaryColor)
        } catch (e: Exception) {
            Color.parseColor("#FF6200EE") // Fallback color
        }

        toolView.imageTintList = ColorStateList.valueOf(primaryColor)

        // Update draw view mode
        drawView?.setMode(mode)
    }

    /**
     * Reset tool icon to default color
     */
    private fun resetToolColor(toolView: ImageView) {
        val defaultColor = try {
            ContextCompat.getColor(this, R.color.default_tool_color)
        } catch (e: Exception) {
            Color.parseColor("#757575") // Fallback gray color
        }

        toolView.imageTintList = ColorStateList.valueOf(defaultColor)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setToolBoxDrag() {
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialX = 0
        var initialY = 0
        var isDragging = false

        val dragTouchListener = View.OnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = e.rawX
                    initialTouchY = e.rawY
                    initialX = drawingViewParams.x
                    initialY = drawingViewParams.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (e.rawX - initialTouchX).toInt()
                    val deltaY = (e.rawY - initialTouchY).toInt()

                    // Mark as dragging if moved more than threshold
                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        drawingViewParams.x = initialX + deltaX
                        drawingViewParams.y = initialY + deltaY
                        wm.updateViewLayout(drawingBinding!!.root, drawingViewParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    isDragging = false
                    true
                }

                else -> false
            }
        }

        // ✅ Apply drag listener to toolbar background
        drawingBinding?.drawingToolbar?.setOnTouchListener(dragTouchListener)

        // ✅ Apply same drag listener to all tool icons
        drawingBinding?.apply {
            toolBrush.setOnTouchListener(dragTouchListener)
            toolSquare.setOnTouchListener(dragTouchListener)
            toolCircle.setOnTouchListener(dragTouchListener)
            toolArrow.setOnTouchListener(dragTouchListener)
            toolEraser.setOnTouchListener(dragTouchListener)
            toolShape.setOnTouchListener(dragTouchListener)
            toolCamera.setOnTouchListener(dragTouchListener)
            toolUndo.setOnTouchListener(dragTouchListener)
            toolPalette.setOnTouchListener(dragTouchListener)
            toolClose.setOnTouchListener(dragTouchListener)
        }
    }

    private fun startDrawing() {
        if (drawView != null) return

        drawView = DrawingOverlayView(this)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(drawView, p)
    }

    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        params.x = if (params.x > screenWidth / 2) screenWidth else 0
        wm.updateViewLayout(binding.root, params)
    }

    private fun showDeleteZone() {
        if (isDeleteZoneVisible || deleteZoneBinding != null) return

        try {
            deleteZoneBinding =
                LayoutDeleteZoneBinding.inflate(LayoutInflater.from(this), null, false)

            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            deleteZoneParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - 80.dpToPx()) / 2
                y = screenHeight - 200.dpToPx()
            }

            wm.addView(deleteZoneBinding!!.root, deleteZoneParams)
            isDeleteZoneVisible = true

            deleteZoneBinding!!.root.apply {
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing delete zone", e)
        }
    }

    private fun hideDeleteZone() {
        if (!isDeleteZoneVisible || deleteZoneBinding == null) return

        try {
            deleteZoneBinding!!.root.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    try {
                        wm.removeView(deleteZoneBinding!!.root)
                        deleteZoneBinding = null
                        deleteZoneParams = null
                        isDeleteZoneVisible = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing delete zone", e)
                    }
                }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding delete zone", e)
        }
    }

    private fun isInDeleteZone(x: Int, y: Int): Boolean {
        if (deleteZoneParams == null || deleteZoneBinding == null) return false

        val deleteZoneX = deleteZoneParams!!.x
        val deleteZoneY = deleteZoneParams!!.y
        val deleteZoneWidth = 100.dpToPx()
        val deleteZoneHeight = 100.dpToPx()

        val buttonCenterX = x + (params.width) / 2
        val buttonCenterY = y + (params.height) / 2

        return buttonCenterX >= deleteZoneX &&
                buttonCenterX <= deleteZoneX + deleteZoneWidth &&
                buttonCenterY >= deleteZoneY &&
                buttonCenterY <= deleteZoneY + deleteZoneHeight
    }

    private fun updateScreenshotButtonAppearance(isDanger: Boolean) {
        binding.mainFab.let { button ->
            if (isDanger) {
                button.setBackgroundResource(R.drawable.screenshot_button_danger)
                button.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(100)
                    .start()
            } else {
                button.background = null
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
        }
    }

    private fun updateDeleteZoneAppearance(isActive: Boolean) {
        deleteZoneBinding?.ivDelete?.let { deleteIcon ->
            if (isActive) {
                deleteIcon.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .alpha(1f)
                    .setDuration(100)
                    .start()
            } else {
                deleteIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0.8f)
                    .setDuration(100)
                    .start()
            }
        }
    }


    //    <------------------------ Adding Color Palettes ------------------------------->
    private fun addColorPalettes() {
        val themedContext = ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
        )
        viewColorSelectorBinding =
            ViewDrawingColorPalletesBinding.inflate(LayoutInflater.from(themedContext), null, false)
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels

        colorPalettesParam = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (screenWidth - 20.dpToPx())
            y = 10.dpToPx()
        }
        wm.addView(viewColorSelectorBinding.root, colorPalettesParam)
        isColorPalettesShown = true
        drawView?.paletteView = viewColorSelectorBinding.root
        viewColorSelectorBinding.apply {
            drawView?.getStrokeWidth()?.let { it1 -> slider.value = it1 }
            redImage.setOnClickListener {
                drawView?.setColor(R.color.red)
                setStrokeToColors(redImage)
            }
            greenImage.setOnClickListener {
                drawView?.setColor(R.color.green)
                setStrokeToColors(greenImage)
            }
            blueImage.setOnClickListener {
                drawView?.setColor(R.color.blue)
                setStrokeToColors(blueImage)
            }
            yellowImage.setOnClickListener {
                drawView?.setColor(R.color.yellow)
                setStrokeToColors(yellowImage)
            }
            cyanImage.setOnClickListener {
                drawView?.setColor(R.color.cyan)
                setStrokeToColors(cyanImage)
            }
            whiteImage.setOnClickListener {
                drawView?.setColor(R.color.white)
                setStrokeToColors(whiteImage)

            }
            slider.addOnChangeListener { slider, point, p2 ->
                drawView?.setStrokeWidth(point)
            }
            colorPickerImage.setOnClickListener {
                if (!colorPickerView.isVisible)
                    colorPickerView.isVisible = true
                else
                    colorPickerView.isGone = true
            }
            colorPickerView.setColorListener(object : ColorListener {
                override fun onColorSelected(color: Int, fromUser: Boolean) {
                    if (color != 0 && color != Color.TRANSPARENT) {
                        drawView?.setColor(color)
                    }
                }
            })
            doneImage.setOnClickListener {
                hideColorPalettes()
            }
        }
    }

    fun setColorSafely(color: Int) {
        if (color != 0 && color != Color.TRANSPARENT) {
            drawView?.setColor(color)
        } else {
            Log.w("DrawingService", "Attempted to set invalid color: $color")
        }
    }

    private fun setStrokeToColors(selectedImage: ShapeableImageView) {
        viewColorSelectorBinding.apply {
            redImage.strokeWidth = 0f
            greenImage.strokeWidth = 0f
            blueImage.strokeWidth = 0f
            yellowImage.strokeWidth = 0f
            cyanImage.strokeWidth = 0f
            whiteImage.strokeWidth = 0f
            selectedImage.strokeWidth = 6f
            selectedImage.strokeColor = ColorStateList.valueOf(getColor(R.color.primaryColor))
        }
    }

    private fun hideColorPalettes() {
        wm.removeView(viewColorSelectorBinding.root)
        isColorPalettesShown = false
        drawView?.paletteView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wm.removeView(binding.root)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing main view", e)
        }

        drawView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing draw view", e)
            }
        }

        drawingBinding?.let {
            try {
                wm.removeView(it.root)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing drawing toolbar", e)
            }
        }

        drawView = null
        drawingBinding = null
        selectedToolView = null
    }
}