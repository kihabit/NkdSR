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
import com.nkd.screenrecorder.databinding.LayoutFloatingTitanBinding
import com.nkd.screenrecorder.databinding.ViewDrawingColorPalletesBinding
import com.nkd.screenrecorder.databinding.ViewDrawingToolbarBinding
import com.nkd.screenrecorder.utils.BubbleDeleteZone
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

    // Idle: same as SSViewService's screenshot bubble, which collapses 1s after it is shown
    // (36dp, alpha 0.7, only 20dp peeking from the edge). Here the timer restarts after every
    // touch and never runs while dragging or while the drawing board is open.
    private val IDLE_COLLAPSE_DELAY_MS = 1000L
    private val idleCollapseRunnable = Runnable {
        if (isRunning && !isDrawingVieVisible) animateToCollapsed()
    }

    private fun scheduleIdleCollapse() {
        mainHandler.removeCallbacks(idleCollapseRunnable)
        if (isDrawingVieVisible) return
        mainHandler.postDelayed(idleCollapseRunnable, IDLE_COLLAPSE_DELAY_MS)
    }

    private fun cancelIdleCollapse() {
        mainHandler.removeCallbacks(idleCollapseRunnable)
    }

    /** The current touch woke the bubble from its collapsed (idle) state. */
    private var wokenFromCollapsed = false

    // Drag-to-close target: the SAME one (code, look, magnet) as SSViewService's bubbles
    private val deleteZone by lazy { BubbleDeleteZone(this, wm, mainHandler) }

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

                deleteZone.ensureWindow()   // delete zone must sit BELOW the bubble (window order)
                wm.addView(binding.root, params)
                binding.mainFab.alpha = 1f
                binding.rootLayout.alpha = 1f

                isExpanded = true
                scheduleIdleCollapse()
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
        var offsetX = 0
        var offsetY = 0

        // On the icon AND the circle around it: the 8dp ring (most of a collapsed bubble) had
        // no listener, so touching it did nothing.
        val touchListener = View.OnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Wake at once: no idle collapse while the finger is down, and full
                    // size/opacity now — the drag never waits for a grow animation.
                    cancelIdleCollapse()
                    currentAnimator?.cancel()
                    wokenFromCollapsed = !isExpanded
                    deleteZone.restoreFullSizeOnScreen(
                        binding.root, params,
                        (SIZE_EXPANDED * Resources.getSystem().displayMetrics.density).toInt()
                    )
                    binding.mainFab.alpha = 1f
                    binding.rootLayout.alpha = 1f
                    isExpanded = true
                    isMoving = false
                    wasDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = e.rawX
                    initialTouchY = e.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val rawX = (e.rawX - initialTouchX).toInt()
                    val rawY = (e.rawY - initialTouchY).toInt()

                    if (abs(rawX) > 10 || abs(rawY) > 10) {
                        if (!isMoving) {
                            // The magnet needs the real window size (params start as WRAP_CONTENT)
                            if (params.width < 0 || params.height < 0) {
                                params.width = binding.root.width
                                params.height = binding.root.height
                            }
                            // On-screen vs params offset of this window (for the magnet)
                            val off = deleteZone.screenOffsetOf(binding.root, params)
                            offsetX = off.first
                            offsetY = off.second
                        }
                        isMoving = true
                        wasDragging = true

                        if (!deleteZone.isVisible) {
                            deleteZone.show()
                        }
                    }

                    if (isMoving) {
                        // Follow the finger, or stick to the delete circle (magnet + haptic)
                        deleteZone.dragWithMagnet(
                            rootView = binding.root,
                            params = params,
                            desiredX = initialX + rawX,
                            desiredY = initialY + rawY,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            // The visible bubble is the root (orange circle), not the 24dp icon
                            bubbleSizePx = binding.rootLayout.width,
                            hapticView = view
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val removeIt = wasDragging && deleteZone.isOver
                    deleteZone.hide()

                    if (wasDragging) {
                        if (removeIt) {
                            // Magnetized on the ✕: the floating button's close sequence on the
                            // whole circle (rootLayout — shrinking only the icon left the circle
                            // frozen until the window vanished), then close drawing completely.
                            currentAnimator?.cancel()
                            deleteZone.closeBubble(binding.rootLayout) {
                                closeDrawingFromDeleteZone()
                            }
                        } else {
                            snapToEdge()
                            scheduleIdleCollapse()
                        }
                    } else {
                        binding.mainFab.performClick()
                        scheduleIdleCollapse()   // no-op once the click opened the board
                    }

                    isMoving = false
                    deleteZone.isOver = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // Gesture taken away: hide the delete zone, no delete
                    deleteZone.hide()
                    if (wasDragging) snapToEdge()
                    scheduleIdleCollapse()
                    isMoving = false
                    wasDragging = false
                    deleteZone.isOver = false
                    true
                }

                else -> false
            }
        }
        binding.mainFab.setOnTouchListener(touchListener)
        binding.rootLayout.setOnTouchListener(touchListener)
    }

    /**
     * Bubble released on the delete zone: remove the canvas, toolbar and palette, then the
     * bubble (its ACTION_HIDE_BRUSH broadcast updates the app's drawing button), and stop.
     * isRunning=false keeps the toolbox's drawing switch in sync.
     */
    private fun closeDrawingFromDeleteZone() {
        if (isDrawingVieVisible) closeDrawingBoard()
        hideFloatingButton()
        isRunning = false
        stopSelf()
    }

    private fun hideFloatingButton() {
        try {
            cancelIdleCollapse()
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
            val fromCollapsed = wokenFromCollapsed
            wokenFromCollapsed = false
            if (fromCollapsed) {
                // Tap woke a collapsed bubble (already full size): same result as before
                if (!isDrawingVieVisible) {
                    binding.rootLayout.isGone = true
                    showDrawingBoard()
                } else {
                    closeDrawingBoard()
                }
            } else if (!isExpanded) {
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

        // Each removal on its own: a failing toolbar removal must not leave the full-screen canvas
        removeOverlay(drawingBinding?.root)
        removeOverlay(drawView)
        drawView = null
        drawingBinding = null
        selectedToolView = null
        isDrawingVieVisible = false
        scheduleIdleCollapse()
    }

    private fun showDrawingBoard() {
        cancelIdleCollapse()
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

    /** Released elsewhere: slide to the nearest edge, same animation as SSViewService's toolbox bubble. */
    private fun snapToEdge() {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val bubbleWidth = if (binding.root.width > 0) binding.root.width else params.width
        val isOnLeftSide = params.x + bubbleWidth / 2 < screenWidth / 2
        val margin = -10.dpToPx()
        val targetX = if (isOnLeftSide) margin else screenWidth - bubbleWidth - margin

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(0.5f)
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(binding.root, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating layout during snap", e)
                }
            }
            start()
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
        removeOverlay(viewColorSelectorBinding.root)
        isColorPalettesShown = false
        drawView?.paletteView = null
    }

    /** removeView that never throws and skips views that aren't attached. */
    private fun removeOverlay(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay already removed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelIdleCollapse()
        currentAnimator?.cancel()
        if (isColorPalettesShown) hideColorPalettes()   // palette window was never removed here
        deleteZone.removeWindow()
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