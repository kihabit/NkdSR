package com.nkd.screenrecorder.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.ProjectionPermissionActivity
import com.nkd.screenrecorder.databinding.DialogToolboxLayoutBinding
import com.nkd.screenrecorder.databinding.LayoutBubbleMenuBinding
import com.nkd.screenrecorder.databinding.LayoutRecordingFlipBinding
import com.nkd.screenrecorder.databinding.ServiceScreenShotBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.fragments.SettingFragment.Companion.PREFS_NAME
import com.nkd.screenrecorder.fragments.SettingFragment.Companion.SHOW_COUNT_DOWN
import com.nkd.screenrecorder.utils.AudioSourcePrefs
import com.nkd.screenrecorder.utils.VideoQualityPrefs
import com.nkd.screenrecorder.utils.RecordingOptionsSheet
import com.nkd.screenrecorder.utils.BubbleDeleteZone
import com.nkd.screenrecorder.utils.LocaleHelper
import com.nkd.screenrecorder.utils.dpToPx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SSViewService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Screenshot button related
    private var screenshotBinding: ServiceScreenShotBinding? = null
    private var screenshotParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var currentAnimator: AnimatorSet? = null
    private var isOnRightEdge = false
    /** Idle collapse 1s after the button is shown / after the finger lifts. */
    private val screenshotIdleCollapse = Runnable { animateToCollapsed() }
    private val SIZE_EXPANDED = 48
    private val SIZE_COLLAPSED = 36
    private val EDGE_PEEK_AMOUNT = 20


    // Toolbox related
    private var toolboxBinding: LayoutRecordingFlipBinding? = null
    private var toolboxParams: WindowManager.LayoutParams? = null
    private var isToolboxExpanded = false
    private lateinit var preference: SharedPreferences

    companion object {
        internal const val TAG = "SSViewService"
        private const val CHANNEL_ID = "ss_view_channel"
        private const val NOTIFICATION_ID = 1002

        // Actions
        const val ACTION_SHOW_SCREENSHOT_BUTTON = "action_show_screenshot_button"
        const val ACTION_HIDE_SCREENSHOT_BUTTON = "action_hide_screenshot_button"
        const val ACTION_SHOW_TOOLBOX = "action_show_toolbox"
        const val ACTION_HIDE_TOOLBOX = "action_hide_toolbox"
        const val ACTION_TOGGLE_TOOLBOX = "action_toggle_toolbox"
        /** Notification toolbar → "Tools": show the toolbox dialog as an overlay. */
        const val ACTION_OPEN_TOOLS = "action_open_tools"

        /** Screenshot button image alpha while a recording is running (dimmed, not hidden). */
        private const val SCREENSHOT_DIMMED_ALPHA = 90

        var isScreenshotButtonVisible = false
        var isToolboxVisible = false

        /** Running instance (main thread only) — lets Settings apply "hide floating ball" at once. */
        private var instance: SSViewService? = null

        /** Settings → "Hide floating ball during recording" changed. No-op if the service isn't running. */
        fun applyHideWhileRecording(@Suppress("UNUSED_PARAMETER") context: Context) {
            instance?.applyFloatingViewsHidden()
        }
    }

    /**
     * Settings → "Hide floating ball during recording" (default OFF): while a recording runs,
     * the toolbox bubble and the floating screenshot button are hidden (window root GONE →
     * not drawn, not captured in the video, no touches); shown again when it stops.
     */
    private fun applyFloatingViewsHidden() {
        val hide = preference.getBoolean(SettingFragment.HIDE_FLOAT_VIEW, true) &&
                RecordScreenService.isRecording
        if (hide) hideBubbleMenu(animate = false)   // menu open → close it first
        val visibility = if (hide) View.GONE else View.VISIBLE
        toolboxBinding?.rootContainer?.let { if (bubbleMenuBinding == null) it.visibility = visibility }
        screenshotBinding?.root?.visibility = visibility
    }

    // ✅ Hides the floating screenshot button while a recording is active (screenshots
    // aren't available mid-recording — see ProjectionPermissionActivity) and restores it
    // once the recording stops.
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordScreenService.ACTION_RECORDING_STATE) {
                val recording = intent.getBooleanExtra(RecordScreenService.EXTRA_STATE, false)
                // ✅ Stay visible but dimmed while recording (tap → "not available" toast);
                // back to normal automatically when the recording stops.
                applyScreenshotButtonRecordingState(recording)
                updateMenuRecordIcon()   // bubble menu open: Record ↔ Stop, GIF dimmed
                applyFloatingViewsHidden()   // Settings → hide floating ball during recording
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preference = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val filter = IntentFilter(RecordScreenService.ACTION_RECORDING_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingStateReceiver, filter)
        }

        // Screen off (lock) → close the full-screen bubble menu. System broadcast.
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_SCREENSHOT_BUTTON -> {
                if (!isScreenshotButtonVisible) {
                    startForegroundWithType(createNotification(getString(R.string.notif_screenshot_active)))
                    setupFloatingButton()   // sets isScreenshotButtonVisible itself — true only on a successful attach
                    if (isScreenshotButtonVisible) {
                        val intent = Intent().apply {
                            action = ACTION_SHOW_SCREENSHOT_BUTTON
                            `package` = packageName
                        }
                        sendBroadcast(intent)
                    }
                }
            }

            ACTION_HIDE_SCREENSHOT_BUTTON -> {
                hideFloatingButton()
                isScreenshotButtonVisible = false
                checkShouldStopService()
            }

            ACTION_SHOW_TOOLBOX -> {
                if (!isToolboxVisible) {
                    startForegroundWithType(createNotification(getString(R.string.notif_toolbox_active)))
                    setupToolbox()   // sets isToolboxVisible itself — true only on a successful attach
                }
            }

            ACTION_HIDE_TOOLBOX -> {
                hideToolbox()
                isToolboxVisible = false
                checkShouldStopService()
            }

            ACTION_TOGGLE_TOOLBOX -> {
                toggleToolboxExpansion()
            }

            ACTION_OPEN_TOOLS -> {
                // Started via PendingIntent.getForegroundService → must go foreground now.
                startForegroundWithType(createNotification(getString(R.string.notif_toolbox_active)))
                handleToolboxAction(onDismiss = {
                    // Nothing else visible (no bubble / screenshot button)? Stop again. Delayed so
                    // a switch toggled in the dialog can deliver its own start command first.
                    mainHandler.postDelayed({ checkShouldStopService() }, 800)
                })
            }
        }
        return START_NOT_STICKY
    }

    /** Recording → screenshot button dimmed; stopped → normal. Never hidden. */
    private fun applyScreenshotButtonRecordingState(recording: Boolean) {
        screenshotBinding?.let {
            it.root.visibility = View.VISIBLE
            it.btnScreenshot.imageAlpha = if (recording) SCREENSHOT_DIMMED_ALPHA else 255
        }
    }

    private fun showScreenshotUnavailableToast() {
        android.widget.Toast.makeText(
            applicationContext,
            getString(R.string.screenshot_unavailable_recording),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+)
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13 (API 29-33)
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Below Android 10
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    // ============ SCREENSHOT BUTTON METHODS ============

    private fun setupFloatingButton() {
        // Already attached? Don't inflate and add a second window on top of the live one.
        if (screenshotBinding?.root?.isAttachedToWindow == true) {
            isScreenshotButtonVisible = true
            return
        }

        // Overlay permission can be revoked at any time without this service being notified —
        // WindowManager.addView() would then throw. Check first so a missing permission is a
        // graceful no-op, not a crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "setupFloatingButton: overlay permission not granted, cannot show button")
            screenshotBinding = null
            screenshotParams = null
            isScreenshotButtonVisible = false
            checkShouldStopService()
            return
        }

        val binding = ServiceScreenShotBinding.inflate(LayoutInflater.from(this), null, false)

        ViewCompat.setAccessibilityDelegate(
            binding.btnScreenshot, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View, info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.contentDescription = getString(R.string.take_screenshot)
                    info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                }
            })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 150

            val expandedSizePx = (SIZE_EXPANDED * resources.displayMetrics.density).toInt()
            width = expandedSizePx
            height = expandedSizePx
        }

        deleteZone.ensureWindow()   // delete zone must sit BELOW the bubble (window order)

        try {
            windowManager.addView(binding.root, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "setupFloatingButton: addView failed with BadTokenException", e)
            isScreenshotButtonVisible = false
            checkShouldStopService()
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "setupFloatingButton: addView failed with SecurityException", e)
            isScreenshotButtonVisible = false
            checkShouldStopService()
            return
        }

        // Only now that the window is actually attached do we publish the binding/params —
        // keeps screenshotBinding non-null iff the view is really on screen.
        screenshotBinding = binding
        screenshotParams = params

        // ✅ If a recording is already in progress when this button is (re)created, show it
        // dimmed right away (screenshots are disabled while recording).
        applyScreenshotButtonRecordingState(RecordScreenService.isRecording)
        applyFloatingViewsHidden()   // Settings → hide floating ball during recording

        screenshotBinding!!.btnScreenshot.alpha = 1f
        screenshotBinding!!.screenshotRoot.alpha = 1f
        isExpanded = true

        mainHandler.postDelayed(screenshotIdleCollapse, 1000)

        setupScreenshotDragging()

        screenshotBinding!!.btnScreenshot.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (RecordScreenService.isRecording) {
                    showScreenshotUnavailableToast()
                    return@setOnClickListener
                }
                if (!isExpanded) {
                    animateToExpanded {
                        launchScreenshotPermissionActivity()
                    }
                } else {
                    launchScreenshotPermissionActivity()
                }
            }
        }

        isScreenshotButtonVisible = true
    }

    private fun hideFloatingButton() {
        try {
            mainHandler.removeCallbacks(screenshotIdleCollapse)
            currentAnimator?.cancel()
            screenshotBinding?.let {
                if (it.root.isAttachedToWindow) {
                    windowManager.removeView(it.root)
                }
            }
            screenshotBinding = null
            screenshotParams = null
            isScreenshotButtonVisible = false
            val intent = Intent().apply {
                action = ACTION_HIDE_SCREENSHOT_BUTTON
                `package` = packageName

            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding floating button", e)
        }
    }

    //5 min
    private fun launchScreenshotPermissionActivity() {
        try {
            val intent = Intent(
                this, ProjectionPermissionActivity::class.java
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("REQUEST_TYPE", "SCREENSHOT")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching permission activity", e)
            animateToCollapsed()
        }
    }

    // ============ SCREENSHOT ANIMATION METHODS ============

    private fun animateToCollapsed() {
        if (!isExpanded || screenshotBinding == null || screenshotParams == null) return

        currentAnimator?.cancel()

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val collapsedSizePx = (SIZE_COLLAPSED * displayMetrics.density).toInt()
        val peekAmountPx = (EDGE_PEEK_AMOUNT * displayMetrics.density).toInt()

        val viewWidth = screenshotBinding!!.root.width
        isOnRightEdge = screenshotParams!!.x + viewWidth / 2 >= screenWidth / 2

        val targetX = if (isOnRightEdge) {
            screenWidth - peekAmountPx
        } else {
            -collapsedSizePx + peekAmountPx
        }

        val animatorSet = AnimatorSet()

        val positionAnimator = ValueAnimator.ofInt(screenshotParams!!.x, targetX)
        positionAnimator.addUpdateListener {
            screenshotParams?.x = it.animatedValue as Int
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val sizeAnimator = ValueAnimator.ofInt(screenshotParams!!.width, collapsedSizePx)
        sizeAnimator.addUpdateListener {
            val value = it.animatedValue as Int
            screenshotParams?.width = value
            screenshotParams?.height = value
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val alphaAnimator = ValueAnimator.ofFloat(screenshotBinding!!.btnScreenshot.alpha, 0.7f)
        alphaAnimator.addUpdateListener {
            screenshotBinding?.btnScreenshot?.alpha = it.animatedValue as Float
            screenshotBinding?.screenshotRoot?.alpha = it.animatedValue as Float
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
        if (isExpanded || screenshotBinding == null || screenshotParams == null) {
            onComplete?.invoke()
            return
        }

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

        val positionAnimator = ValueAnimator.ofInt(screenshotParams!!.x, targetX)
        positionAnimator.addUpdateListener {
            screenshotParams?.x = it.animatedValue as Int
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val sizeAnimator = ValueAnimator.ofInt(screenshotParams!!.width, expandedSizePx)
        sizeAnimator.addUpdateListener {
            val value = it.animatedValue as Int
            screenshotParams?.width = value
            screenshotParams?.height = value
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val alphaAnimator = ValueAnimator.ofFloat(screenshotBinding!!.btnScreenshot.alpha, 1f)
        alphaAnimator.addUpdateListener {
            screenshotBinding?.btnScreenshot?.alpha = it.animatedValue as Float
            screenshotBinding?.screenshotRoot?.alpha = it.animatedValue as Float
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
    private fun setupScreenshotDragging() {
        // On the icon AND the circle around it: the 8dp ring (most of a collapsed bubble) had
        // no listener, so touching it did nothing.
        val touchListener = object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialX = 0
            private var initialY = 0
            private var isMoving: Boolean = false
            private var wasDragging = false
            private var offsetX = 0
            private var offsetY = 0

            override fun onTouch(view: View?, motionEvent: MotionEvent): Boolean {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Wake at once: no idle collapse while the finger is down, and full
                        // size/opacity now — the drag never waits for a grow animation.
                        mainHandler.removeCallbacks(screenshotIdleCollapse)
                        currentAnimator?.cancel()
                        deleteZone.restoreFullSizeOnScreen(
                            screenshotBinding!!.root, screenshotParams!!,
                            (SIZE_EXPANDED * Resources.getSystem().displayMetrics.density).toInt()
                        )
                        screenshotBinding!!.btnScreenshot.alpha = 1f
                        screenshotBinding!!.screenshotRoot.alpha = 1f
                        isExpanded = true
                        isMoving = false
                        wasDragging = false
                        initialX = screenshotParams!!.x
                        initialY = screenshotParams!!.y
                        initialTouchX = motionEvent.rawX
                        initialTouchY = motionEvent.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val rawX = (motionEvent.rawX - initialTouchX).toInt()
                        val rawY = (motionEvent.rawY - initialTouchY).toInt()

                        if (abs(rawX) > 10 || abs(rawY) > 10) {
                            if (!isMoving) {
                                // On-screen vs params offset of this window (for the magnet)
                                val off = deleteZone.screenOffsetOf(screenshotBinding!!.root, screenshotParams!!)
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
                            deleteZone.dragWithMagnet(
                                rootView = screenshotBinding!!.root,
                                params = screenshotParams!!,
                                desiredX = initialX + rawX,
                                desiredY = initialY + rawY,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                // The visible bubble is the root (orange circle), not the 24dp icon
                                bubbleSizePx = screenshotBinding!!.screenshotRoot.width,
                                hapticView = view
                            )
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val removeIt = wasDragging && deleteZone.isOver
                        deleteZone.hide()

                        if (wasDragging) {
                            if (removeIt) {
                                // Magnetized on the ✕: the floating button's close sequence on
                                // the whole circle (screenshotRoot), then the existing removal.
                                currentAnimator?.cancel()   // e.g. the 1s post-show collapse
                                deleteZone.closeBubble(screenshotBinding!!.screenshotRoot) {
                                    hideFloatingButton()
                                    isScreenshotButtonVisible = false
                                    checkShouldStopService()
                                }
                            } else {
                                snapToEdgeAndCollapse()
                                updateScreenshotButtonAppearance(false)
                            }
                        } else {
                            screenshotBinding?.btnScreenshot?.performClick()
                            mainHandler.postDelayed(screenshotIdleCollapse, 1000)
                        }

                        isMoving = false
                        deleteZone.isOver = false
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // Gesture taken away (system gesture, shade, screen off): never leave
                        // the delete zone showing, and never treat it as a delete.
                        deleteZone.hide()
                        if (wasDragging && screenshotBinding != null && screenshotParams != null) {
                            snapToEdgeAndCollapse()
                            updateScreenshotButtonAppearance(false)
                        } else {
                            mainHandler.postDelayed(screenshotIdleCollapse, 1000)
                        }
                        isMoving = false
                        wasDragging = false
                        deleteZone.isOver = false
                        return true
                    }

                    else -> return false
                }
            }
        }
        screenshotBinding?.btnScreenshot?.setOnTouchListener(touchListener)
        screenshotBinding?.screenshotRoot?.setOnTouchListener(touchListener)
    }

    private fun snapToEdgeAndCollapse() {
        currentAnimator?.cancel()

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val viewWidth = screenshotBinding!!.root.width
        val middle = screenWidth / 2
        val collapsedSizePx = (SIZE_COLLAPSED * displayMetrics.density).toInt()
        val peekAmountPx = (EDGE_PEEK_AMOUNT * displayMetrics.density).toInt()

        isOnRightEdge = screenshotParams!!.x + viewWidth / 2 >= middle

        val targetX = if (isOnRightEdge) {
            screenWidth - peekAmountPx
        } else {
            -collapsedSizePx + peekAmountPx
        }

        val animatorSet = AnimatorSet()

        val positionAnimator = ValueAnimator.ofInt(screenshotParams!!.x, targetX)
        positionAnimator.addUpdateListener {
            screenshotParams?.x = it.animatedValue as Int
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val sizeAnimator = ValueAnimator.ofInt(screenshotParams!!.width, collapsedSizePx)
        sizeAnimator.addUpdateListener {
            val value = it.animatedValue as Int
            screenshotParams?.width = value
            screenshotParams?.height = value
            try {
                windowManager.updateViewLayout(screenshotBinding!!.root, screenshotParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating view layout", e)
            }
        }

        val alphaAnimator = ValueAnimator.ofFloat(1f, 0.7f)
        alphaAnimator.addUpdateListener {
            screenshotBinding?.btnScreenshot?.alpha = it.animatedValue as Float
            screenshotBinding?.screenshotRoot?.alpha = it.animatedValue as Float
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

    // ============ DELETE ZONE (XRecorder style, with magnet) ============
    // Shared with DrawingService's bubble — see BubbleDeleteZone.
    private val deleteZone by lazy { BubbleDeleteZone(this, windowManager, mainHandler) }

    private fun updateScreenshotButtonAppearance(isDanger: Boolean) {
        screenshotBinding?.btnScreenshot?.let { button ->
            if (isDanger) {
                button.setBackgroundResource(R.drawable.screenshot_button_danger)
                button.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
            } else {
                button.background = null
                button.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
        }
    }

    // ============ TOOLBOX METHODS - IMPROVED ANIMATIONS ============

    // ============ TOOLBOX METHODS - WITH DELETE ZONE ============

    private val toolboxAnimationDuration = 300L

    private fun setupToolbox() {
        // Already attached (e.g. a second ACTION_SHOW_TOOLBOX / ACTION_OPEN_TOOLS raced in)?
        // Don't inflate and add a second window on top of the live one.
        if (toolboxBinding?.rootContainer?.isAttachedToWindow == true) {
            isToolboxVisible = true
            return
        }

        // Overlay permission can be revoked at any time (system Settings, another app, an OEM
        // "background pop-up" toggle) without this service being notified — WindowManager.addView()
        // would then throw. Check first so a missing permission is a graceful no-op, not a crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "setupToolbox: overlay permission not granted, cannot show toolbox")
            toolboxBinding = null
            toolboxParams = null
            isToolboxVisible = false
            checkShouldStopService()
            return
        }

        val binding = LayoutRecordingFlipBinding.inflate(LayoutInflater.from(this), null, false)

        // Get initial button size
        val buttonSizeDp = 48 // Main button size in dp
        val buttonSizePx = buttonSizeDp.dpToPx()

        val params = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -20
            y = initialToolboxY(buttonSizePx)   // left edge, vertically centred
        }

        deleteZone.ensureWindow()   // delete zone must sit BELOW the bubble (window order)

        try {
            windowManager.addView(binding.rootContainer, params)
        } catch (e: WindowManager.BadTokenException) {
            // Window token went bad between the permission check above and this call (service
            // torn down mid-start, display/token invalidated, OEM quirk, etc). Leave the service
            // in a clean "not shown" state instead of crashing.
            Log.e(TAG, "setupToolbox: addView failed with BadTokenException", e)
            isToolboxVisible = false
            checkShouldStopService()
            return
        } catch (e: SecurityException) {
            // Overlay permission revoked in the gap between the check above and this call.
            Log.e(TAG, "setupToolbox: addView failed with SecurityException", e)
            isToolboxVisible = false
            checkShouldStopService()
            return
        }

        // Only now that the window is actually attached do we publish the binding/params —
        // keeps toolboxBinding non-null iff the view is really on screen.
        toolboxBinding = binding
        toolboxParams = params

        toolboxBinding!!.btnMain.alpha = 0.85f

        // Tap on the bubble → open / close the full-screen XRecorder-style menu
        toolboxBinding!!.btnMain.setOnClickListener {
            if (bubbleMenuBinding != null) hideBubbleMenu() else showBubbleMenu()
        }

        setupToolboxDragging()

        isToolboxVisible = true
        isToolboxExpanded = false
        scheduleBubbleDim()
        applyFloatingViewsHidden()   // added while a recording is already running
    }

    /**
     * Starting y of the floating button: the middle of the screen height (portrait or
     * landscape), kept between the status bar and the navigation bar.
     */
    private fun initialToolboxY(sizePx: Int): Int {
        val screenH: Int
        val topInset: Int
        val bottomInset: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            screenH = metrics.bounds.height()
            topInset = bars.top
            bottomInset = bars.bottom
        } else {
            screenH = resources.displayMetrics.heightPixels
            topInset = 24.dpToPx()
            bottomInset = 0   // heightPixels already excludes the navigation bar here
        }
        val minY = topInset
        val maxY = maxOf(minY, screenH - bottomInset - sizePx)
        return ((screenH - sizePx) / 2).coerceIn(minY, maxY)
    }

    private fun hideToolbox() {
        try {
            // Bubble removed → the full-screen menu window must go immediately too
            hideBubbleMenu(animate = false)

            toolboxBinding?.let {
                if (it.rootContainer.isAttachedToWindow) {
                    windowManager.removeView(it.rootContainer)
                }
            }

            toolboxBinding = null
            toolboxParams = null
            isToolboxVisible = false
            isToolboxExpanded = false
            val intent = Intent().apply {
                action = ACTION_HIDE_TOOLBOX
                putExtra("is_running", false)
            }
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding toolbox", e)
        }
    }

    // ── Floating bubble menu (XRecorder style) ─────────────────────────────
    // A separate FULL-SCREEN window, added only while the menu is open: dim layer, ✕ over
    // the bubble, items fanned out in a half-circle. The collapsed bubble window itself
    // (drag, edge snap, delete zone) is untouched — it is just hidden while the menu shows.

    /** The ONE reference to the menu window (null = closed). */
    private var bubbleMenuBinding: LayoutBubbleMenuBinding? = null
    private var bubbleMenuClosing = false
    private val bubbleMenuAnimMs = 180L
    private val bubbleMenuSafetyTimeoutMs = 60_000L
    private val bubbleMenuOrange = 0xFFFF6D00.toInt()

    /** Safety net: never leave a full-screen touch-blocking window behind. */
    private val bubbleMenuSafetyTimeout = Runnable { hideBubbleMenu(animate = false) }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) hideBubbleMenu(animate = false)
        }
    }


    private val bubbleIdleDelayMs = 3000L
    private val bubbleMenuAutoCloseMs = 3000L
    private val bubbleDimRunnable = Runnable { dimBubble() }
    private val bubbleMenuAutoClose = Runnable { hideBubbleMenu() }

    private fun isBubbleOnLeft(): Boolean {
        val p = toolboxParams ?: return true
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        return p.x + p.width / 2 < screenW / 2
    }

    /** Bubble ko halka karke thoda kinare ki taraf khiska do. */
    private fun dimBubble() {
        if (bubbleMenuBinding != null) return
        val b = toolboxBinding?.btnMain ?: return
        val shift = b.width * 0.2f
        b.animate().alpha(0.5f)
            .translationX(if (isBubbleOnLeft()) -shift else shift)
            .setDuration(250).start()
    }

    /** Chhoone par bubble turant poora dikhe. */
    private fun wakeBubble() {
        mainHandler.removeCallbacks(bubbleDimRunnable)
        // At once (not a 150ms fade-in): the dimmed bubble is shifted toward the edge, and the
        // drag must start from where the finger actually is.
        toolboxBinding?.btnMain?.apply {
            animate().cancel()
            alpha = 1f
            translationX = 0f
        }
    }

    /** 2 second baad dhundla karne ka timer (purana timer hata ke). */
    private fun scheduleBubbleDim() {
        mainHandler.removeCallbacks(bubbleDimRunnable)
        mainHandler.postDelayed(bubbleDimRunnable, bubbleIdleDelayMs)
    }

    // Kept for the existing callers (toggle action, dragging, hideToolbox)
    private fun expandMenu() = showBubbleMenu()
    private fun collapseMenu() = hideBubbleMenu()

    private fun showBubbleMenu() {
        if (bubbleMenuBinding != null) return            // already shown
        val main = toolboxBinding?.btnMain ?: return
        try {
            val menu = LayoutBubbleMenuBinding.inflate(LayoutInflater.from(this), null, false)
            bubbleMenuBinding = menu
            bubbleMenuClosing = false

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                // Focusable (no FLAG_NOT_FOCUSABLE) so the Back key reaches this window
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // Dim the WHOLE screen, incl. status/navigation bars and cutouts
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // ── Items: exactly today's actions ──
            val recording = RecordScreenService.isRecording
            setMenuIcon(menu.menuHome, R.drawable.ic_home, tint = true)
            updateMenuRecordIcon(menu)
            setMenuIcon(menu.menuTools, R.drawable.ic_tool_box, tint = true)
            setMenuIcon(menu.menuGif, R.drawable.ic_video_to_gif, tint = true)

            menu.btnMenuClose.setOnClickListener { hideBubbleMenu() }
            menu.dimLayer.setOnClickListener { hideBubbleMenu() }
            menu.menuRoot.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) hideBubbleMenu()
                    true
                } else false
            }
            menu.menuHome.setOnClickListener {
                hideBubbleMenu(animate = false)
                openMainActivity()
            }
            menu.menuRecord.setOnClickListener {
                hideBubbleMenu(animate = false)
                handleStartRecording()
            }
            menu.menuTools.setOnClickListener {
                hideBubbleMenu(animate = false)
                handleToolboxAction()
            }
            menu.menuGif.setOnClickListener {
                // GIF starts a new screen-capture consent, which would end a running
                // recording → disabled (dimmed + toast) while recording, like Screenshot.
                if (RecordScreenService.isRecording) {
                    android.widget.Toast.makeText(
                        this, getString(R.string.gif_unavailable_recording),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                hideBubbleMenu(animate = false)
                handleGifAction()
            }

            windowManager.addView(menu.root, params)
            isToolboxExpanded = true
            toolboxBinding?.rootContainer?.visibility = View.INVISIBLE   // ✕ takes its place
            mainHandler.postDelayed(bubbleMenuSafetyTimeout, bubbleMenuSafetyTimeoutMs)
            mainHandler.removeCallbacks(bubbleDimRunnable)
            mainHandler.removeCallbacks(bubbleMenuAutoClose)
            mainHandler.postDelayed(bubbleMenuAutoClose, bubbleMenuAutoCloseMs)
            menu.menuRoot.requestFocus()

            // Positions need the laid-out window size + the bubble's on-screen position
            val bubbleLoc = IntArray(2).also { main.getLocationOnScreen(it) }
            val bubbleCx = bubbleLoc[0] + main.width / 2f
            val bubbleCy = bubbleLoc[1] + main.height / 2f
            menu.root.post {
                try {
                    if (bubbleMenuBinding === menu) layoutAndAnimateMenuIn(menu, bubbleCx, bubbleCy, recording)
                } catch (e: Exception) {
                    Log.e(TAG, "Bubble menu layout failed", e)
                    hideBubbleMenu(animate = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing bubble menu", e)
            hideBubbleMenu(animate = false)
        }
    }

    /**
     * ✕ on the bubble's centre (clamped fully on screen); items on a half-circle (radius
     * 100dp, smaller if the screen is short) that opens AWAY from the screen edge, shifted
     * up/down so every item stays inside the usable area (status/nav bars, cutouts).
     */
    private fun layoutAndAnimateMenuIn(
        menu: LayoutBubbleMenuBinding, bubbleCx: Float, bubbleCy: Float, recording: Boolean
    ) {
        val root = menu.root
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val itemSize = 44f * density
        val half = itemSize / 2f
        val margin = 8f * density

        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        )
        val leftLimit = (insets?.left ?: 0) + margin
        val rightLimit = w - (insets?.right ?: 0) - margin
        val topLimit = (insets?.top ?: 0) + margin
        val bottomLimit = h - (insets?.bottom ?: 0) - margin

        // ✕ = bubble centre in this window's coordinates, kept fully visible
        val cx = (bubbleCx - rootLoc[0]).coerceIn(leftLimit + half, maxOf(leftLimit + half, rightLimit - half))
        val cy = (bubbleCy - rootLoc[1]).coerceIn(topLimit + half, maxOf(topLimit + half, bottomLimit - half))

        val items = listOf(menu.menuHome, menu.menuRecord, menu.menuTools, menu.menuGif)
        val onRightEdge = cx > w / 2f
        val gap = 6f * density

        // ✅ Saare gol buttons (✕ bhi) 44dp, andar icon ~22dp
        val sizePx = itemSize.toInt()
        val padPx = (11f * density).toInt()
        (items + menu.btnMenuClose).forEach { v ->
            v.layoutParams = v.layoutParams.apply { width = sizePx; height = sizePx }
            v.setPadding(padPx, padPx, padPx, padPx)
        }

        // Half-circle radius: 100dp, or smaller so the arc fits a short screen (landscape)
        val usableH = bottomLimit - topLimit
        val halfRadius = minOf(68f * density, (usableH - itemSize) / 2f).coerceAtLeast(itemSize)
        // Quarter-circle radius: big enough that neighbouring items (30° apart) don't touch
        val quarterStep = 90.0 / (items.size - 1)
        val quarterRadius = maxOf(
            68f * density,
            ((itemSize + gap) / (2 * sin(Math.toRadians(quarterStep / 2)))).toFloat()
        )

        // Arc is always centred on the ✕ (never overlaps it). Angles: 0° = right, 90° = down.
        // Order of items = top → bottom. Opens AWAY from the screen edge.
        val (radius, startDeg, stepDeg) = when {
            // Room for the full half-circle
            cy - halfRadius - half >= topLimit && cy + halfRadius + half <= bottomLimit ->
                if (onRightEdge) Triple(halfRadius, 270.0, -180.0 / (items.size - 1))     // up → left → down
                else Triple(halfRadius, -90.0, 180.0 / (items.size - 1))                  // up → right → down
            // Near the TOP: quarter arc going sideways-inward → down
            cy - halfRadius - half < topLimit ->
                if (onRightEdge) Triple(quarterRadius, 180.0, -quarterStep)               // left → down
                else Triple(quarterRadius, 0.0, quarterStep)                              // right → down
            // Near the BOTTOM: quarter arc going up → sideways-inward
            else ->
                if (onRightEdge) Triple(quarterRadius, 270.0, -quarterStep)               // up → left
                else Triple(quarterRadius, -90.0, quarterStep)                            // up → right
        }
        val arcCy = cy

        menu.btnMenuClose.x = cx - half
        menu.btnMenuClose.y = cy - half
        menu.btnMenuClose.scaleX = 0.6f
        menu.btnMenuClose.scaleY = 0.6f
        menu.btnMenuClose.animate().scaleX(1f).scaleY(1f).setDuration(bubbleMenuAnimMs).start()
        menu.dimLayer.animate().alpha(1f).setDuration(bubbleMenuAnimMs).start()

        items.forEachIndexed { i, item ->
            val a = Math.toRadians(startDeg + i * stepDeg)
            val fx = (cx + radius * cos(a)).toFloat().coerceIn(leftLimit + half, rightLimit - half) - half
            val fy = (arcCy + radius * sin(a)).toFloat().coerceIn(topLimit + half, bottomLimit - half) - half
            val targetAlpha = if (item === menu.menuGif && recording) 0.4f else 1f
            item.x = cx - half
            item.y = cy - half
            item.scaleX = 0f
            item.scaleY = 0f
            item.alpha = 0f
            item.visibility = View.VISIBLE
            item.animate().x(fx).y(fy).scaleX(1f).scaleY(1f).alpha(targetAlpha)
                .setDuration(bubbleMenuAnimMs)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** Safe to call any number of times. animate=false removes the window immediately. */
    private fun hideBubbleMenu(animate: Boolean = true) {
        mainHandler.removeCallbacks(bubbleMenuSafetyTimeout)
        mainHandler.removeCallbacks(bubbleMenuAutoClose)
        val menu = bubbleMenuBinding ?: return
        if (animate && bubbleMenuClosing) return

        fun removeNow() {
            try {
                listOf(menu.menuHome, menu.menuRecord, menu.menuTools, menu.menuGif, menu.btnMenuClose, menu.dimLayer)
                    .forEach { it.animate().cancel() }
            } catch (_: Exception) {
            }
            try {
                windowManager.removeView(menu.root)
            } catch (e: Exception) {
                Log.w(TAG, "Bubble menu already removed: ${e.message}")
            }
            if (bubbleMenuBinding === menu) bubbleMenuBinding = null
            bubbleMenuClosing = false
            isToolboxExpanded = false
            toolboxBinding?.rootContainer?.visibility = View.VISIBLE
            toolboxBinding?.btnMain?.apply { alpha = 1f; translationX = 0f }
            scheduleBubbleDim()
        }

        if (!animate || !menu.root.isAttachedToWindow) {
            removeNow()
            return
        }
        bubbleMenuClosing = true
        try {
            val cx = menu.btnMenuClose.x
            val cy = menu.btnMenuClose.y
            listOf(menu.menuHome, menu.menuRecord, menu.menuTools, menu.menuGif).forEach { item ->
                item.animate().x(cx).y(cy).scaleX(0f).scaleY(0f).alpha(0f)
                    .setDuration(bubbleMenuAnimMs)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .start()
            }
            menu.dimLayer.animate().alpha(0f).setDuration(bubbleMenuAnimMs).start()
            menu.btnMenuClose.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(bubbleMenuAnimMs).start()
        } catch (e: Exception) {
            Log.w(TAG, "Bubble menu close animation failed: ${e.message}")
        }
        // Handler (not view.post) so the removal runs even if the view gets detached
        mainHandler.postDelayed({ if (bubbleMenuBinding === menu) removeNow() }, bubbleMenuAnimMs)
    }

    private fun setMenuIcon(view: androidx.appcompat.widget.AppCompatImageView, res: Int, tint: Boolean) {
        view.setImageResource(res)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            view,
            if (tint) android.content.res.ColorStateList.valueOf(bubbleMenuOrange) else null
        )
    }

    /** Record (red dot) when idle, Stop (orange) while recording — updated live. */
    private fun updateMenuRecordIcon(menu: LayoutBubbleMenuBinding? = bubbleMenuBinding) {
        val m = menu ?: return
        if (RecordScreenService.isRecording) {
            setMenuIcon(m.menuRecord, R.drawable.ic_stop, tint = true)
        } else {
            setMenuIcon(m.menuRecord, R.drawable.ic_bubble_record, tint = false)
        }
        if (m.menuGif.visibility == View.VISIBLE && !bubbleMenuClosing) {
            m.menuGif.alpha = if (RecordScreenService.isRecording) 0.4f else 1f
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBubbleMenu(animate = false)   // rotation: positions are no longer valid
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun setupToolboxDragging() {
        val dragView = toolboxBinding?.btnMain

        // On the bubble AND the rest of its window: touches on the ring around the (dimmed,
        // edge-shifted) bubble used to do nothing.
        val touchListener = object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialWidth = 0
            var initialHeight = 0
            var touchX = 0f
            var touchY = 0f
            var isDragging = false
            var hasMoved = false
            var isOverToolboxDeleteZone = false
            var offsetX = 0
            var offsetY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        wakeBubble()
                        initialX = toolboxParams!!.x
                        initialY = toolboxParams!!.y
                        initialWidth = toolboxParams!!.width
                        initialHeight = toolboxParams!!.height
                        touchX = event.rawX
                        touchY = event.rawY
                        isDragging = false
                        hasMoved = false

                        // Scale up slightly on touch
                        if (!isToolboxExpanded) {
                            toolboxBinding?.btnMain?.animate()?.scaleX(1.15f)?.scaleY(1.15f)
                                ?.alpha(1f)?.setDuration(150)
                                ?.setInterpolator(OvershootInterpolator(0.5f))?.start()
                        }

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - touchX
                        val deltaY = event.rawY - touchY

                        if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                            if (!isDragging) {
                                // On-screen vs params offset of this window (for the magnet)
                                val off = deleteZone.screenOffsetOf(toolboxBinding!!.rootContainer, toolboxParams!!)
                                offsetX = off.first
                                offsetY = off.second
                            }
                            hasMoved = true
                            isDragging = true

                            // Show delete zone when dragging starts
                            if (!deleteZone.isVisible) {
                                deleteZone.show()
                            }

                            // Collapse if expanded while dragging
                            if (isToolboxExpanded) {
                                collapseMenu()
                                isToolboxExpanded = false

                                // Wait for collapse animation, then continue dragging
                                mainHandler.postDelayed({
                                    initialWidth = toolboxParams!!.width
                                    initialHeight = toolboxParams!!.height
                                }, toolboxAnimationDuration)
                            }
                        }

                        if (isDragging) {
                            // Follow the finger, or stick to the delete circle (magnet)
                            isOverToolboxDeleteZone = deleteZone.dragWithMagnet(
                                rootView = toolboxBinding!!.rootContainer,
                                params = toolboxParams!!,
                                desiredX = initialX + deltaX.toInt(),
                                desiredY = initialY + deltaY.toInt(),
                                offsetX = offsetX,
                                offsetY = offsetY,
                                bubbleSizePx = toolboxBinding!!.btnMain.width,
                                hapticView = v
                            )
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        deleteZone.hide()

                        if (hasMoved) {
                            if (isOverToolboxDeleteZone) {
                                // Magnetized on the ✕: shrink + fade, then the existing removal
                                isOverToolboxDeleteZone = false
                                deleteZone.closeBubble(toolboxBinding!!.btnMain) {
                                    hideToolbox()
                                    isToolboxVisible = false
                                    checkShouldStopService()
                                }
                                return true
                            } else {
                                snapToEdge()

                                // Scale back down after drag
                                if (!isToolboxExpanded) {
                                    toolboxBinding?.btnMain?.animate()?.scaleX(1f)?.scaleY(1f)
                                        ?.alpha(0.85f)?.setDuration(200)
                                        ?.setInterpolator(AccelerateDecelerateInterpolator())
                                        ?.start()
                                }
                            }

                            updateToolboxButtonAppearance(false)
                            isOverToolboxDeleteZone = false
                            scheduleBubbleDim()
                            return true
                        } else {
                            // Let the click event propagate
                            toolboxBinding?.btnMain?.performClick()
                            return false
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // Gesture taken away: hide the delete zone, no delete
                        deleteZone.hide()
                        if (hasMoved && toolboxBinding != null && toolboxParams != null) snapToEdge()
                        updateToolboxButtonAppearance(false)
                        isOverToolboxDeleteZone = false
                        isDragging = false
                        hasMoved = false
                        scheduleBubbleDim()
                        return true
                    }
                }
                return false
            }
        }
        dragView?.setOnTouchListener(touchListener)
        toolboxBinding?.rootContainer?.setOnTouchListener(touchListener)
    }

    private fun snapToEdge() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels

        // Get center of container
        val containerCenterX = toolboxParams!!.x + (toolboxParams!!.width / 2)
        val isOnLeftSide = containerCenterX < screenWidth / 2

        // Calculate target position for edge snapping
        val buttonSizePx = 48.dpToPx()
        val margin = -10.dpToPx()

        val targetX = if (isOnLeftSide) {
            margin
        } else {
            screenWidth - buttonSizePx - margin
        }

        // Animate to edge
        ValueAnimator.ofInt(toolboxParams!!.x, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(0.5f)

            addUpdateListener { animator ->
                toolboxParams?.x = animator.animatedValue as Int
                try {
                    windowManager.updateViewLayout(
                        toolboxBinding!!.rootContainer, toolboxParams
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating layout during snap", e)
                }
            }
            start()
        }
    }

    private fun updateToolboxButtonAppearance(isDanger: Boolean) {
        toolboxBinding?.btnMain?.let { button ->
            if (isDanger) {
                button.setBackgroundResource(R.drawable.screenshot_button_danger)
                button.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
            } else {
                button.setBackgroundResource(R.drawable.circle_button_bg)
                button.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
        }
    }

    private fun toggleToolboxExpansion() {
        if (isToolboxExpanded) {
            collapseMenu()
            isToolboxExpanded = false
        } else {
            expandMenu()
            isToolboxExpanded = true
        }
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening main activity", e)
        }
    }

    private fun handleStartRecording() {
        if (RecordScreenService.isRecording) {
            // Stop recording via RecordScreenService
            val intent = Intent(this, RecordScreenService::class.java).apply {
                action = RecordScreenService.ACTION_STOP_RECORDING
            }
            startService(intent)
        } else {
            // Launch permission activity to start recording
            try {
                val intent = Intent(
                    this, ProjectionPermissionActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("REQUEST_TYPE", "RECORDING")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching recording permission activity", e)
            }
        }
    }

    private fun handleToolboxAction(onDismiss: (() -> Unit)? = null) {
        val themeContext = ContextThemeWrapper(applicationContext, R.style.Theme_NkdSR)
        val builder: AlertDialog.Builder = AlertDialog.Builder(themeContext)

        val dialogBinding =
            DialogToolboxLayoutBinding.inflate(LayoutInflater.from(themeContext), null, false)

        builder.setView(dialogBinding.root)

        val toolboxDialog = builder.create()
        dialogBinding.apply {
            switchFordrawing.isChecked = DrawingService.isRunning
            switchForFaceCame.isChecked = CameraService.isRunning
            switchForScreenshot.isChecked = isScreenshotButtonVisible
            switchForCountDown.isChecked = preference.getBoolean(SHOW_COUNT_DOWN, true)
            countdownSeconds.isVisible = switchForCountDown.isChecked

            // ✅ Screenshot option disabled (dimmed + toast) while a recording is running
            switchForScreenshot.alpha = if (RecordScreenService.isRecording) 0.5f else 1f
            var revertingScreenshotSwitch = false
            switchForScreenshot.setOnCheckedChangeListener { buttonView, isChecked ->
                if (revertingScreenshotSwitch) return@setOnCheckedChangeListener
                if (RecordScreenService.isRecording) {
                    revertingScreenshotSwitch = true
                    buttonView.isChecked = !isChecked
                    revertingScreenshotSwitch = false
                    showScreenshotUnavailableToast()
                    return@setOnCheckedChangeListener
                }
                toolboxDialog.dismiss()
                if (isChecked) {
                    val intent = Intent(this@SSViewService, SSViewService::class.java).apply {
                        action = ACTION_SHOW_SCREENSHOT_BUTTON
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } else {
                    val intent = Intent(this@SSViewService, SSViewService::class.java).apply {
                        action = ACTION_HIDE_SCREENSHOT_BUTTON
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
            switchForFaceCame.setOnCheckedChangeListener { buttonView, isChecked ->
                if (syncingFacecamSwitch) return@setOnCheckedChangeListener
                toolboxDialog.dismiss()
                if (isChecked && !hasFacecamPermissions()) {
                    // Missing CAMERA or overlay: don't start the service — the app's facecam
                    // flow asks for them and then starts it. The switch stays off until the
                    // facecam really opens (re-read from CameraService.isRunning).
                    syncingFacecamSwitch = true
                    buttonView.isChecked = false
                    syncingFacecamSwitch = false
                    openAppFacecamFlow()
                } else if (isChecked) {
                    val intent = Intent(this@SSViewService, CameraService::class.java)
                    // ✅ Default to the front camera — no saved facing preference exists yet.
                    // This used to hardcode facing=1 (back), always opening the rear camera.
                    intent.putExtra(CameraService.EXTRA_FACING, CameraService.FACING_FRONT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } else {
                    val intent = Intent(this@SSViewService, CameraService::class.java)
                    stopService(intent)
                }
            }
            switchFordrawing.setOnCheckedChangeListener { buttonView, isChecked ->
                toolboxDialog.dismiss()
                if (!DrawingService.isRunning) {
                    val intent = Intent(this@SSViewService, DrawingService::class.java)
                    intent.action = DrawingService.ACTION_SHOW_BRUSH
                    intent.putExtra("show_drawing_board", true)
                    startService(intent)
                } else {
                    val intent = Intent(this@SSViewService, DrawingService::class.java)
                    intent.action = DrawingService.ACTION_HIDE_BRUSH
                    startService(intent)
                }
            }
            switchForCountDown.setOnCheckedChangeListener { buttonView, isChecked ->
                preference.edit { putBoolean(SHOW_COUNT_DOWN, isChecked) }
                countdownSeconds.isVisible = isChecked
            }
            countdownSeconds.text =
                getString(R.string.countdown_time, SettingFragment.countdownSeconds(preference))
            countdownSeconds.setOnClickListener {
                // Same dialog as Settings: 1–10 seconds, stays open with an error on bad input
                SettingFragment.showCountdownDialog(
                    themeContext,
                    preference,
                    windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE
                ) { value ->
                    countdownSeconds.text = getString(R.string.countdown_time, value)
                    toolboxDialog.dismiss()
                }
            }
            // Video quality (VideoQualityPrefs — same key/sheet as Home and Settings)
            selectedVideoQuality.text = VideoQualityPrefs.qualitySummary(themeContext)

            selectVideoQualityCard.setOnClickListener {
                RecordingOptionsSheet.showVideoQuality(themeContext, overlay = true) {
                    selectedVideoQuality.text = VideoQualityPrefs.qualitySummary(themeContext)
                    toolboxDialog.dismiss()
                }
            }

            // Audio source (AudioSourcePrefs — same key/sheet as Home and Settings)
            selectedAudioSource.text = AudioSourcePrefs.summary(themeContext)

            selectAudioSourceCard.setOnClickListener {
                RecordingOptionsSheet.showAudioSource(
                    themeContext,
                    overlay = true,
                    // A service can't ask for RECORD_AUDIO — the app asks, then saves Mic / Mute
                    requestMicPermission = {
                        toolboxDialog.dismiss()
                        openAppAudioMicFlow()
                    }
                ) {
                    selectedAudioSource.text = AudioSourcePrefs.summary(themeContext)
                    toolboxDialog.dismiss()
                }
            }

            // Whole rows tappable (like Settings): switch rows toggle → same listener,
            // chip rows open their dialog.
            screenshotView.setOnClickListener { switchForScreenshot.toggle() }
            faceCameView.setOnClickListener { switchForFaceCame.toggle() }
            drawingView.setOnClickListener { switchFordrawing.toggle() }
            countdownBeforeStart.setOnClickListener { switchForCountDown.toggle() }
            videoQualitySection.setOnClickListener { selectVideoQualityCard.performClick() }
            audioSourceSection.setOnClickListener { selectAudioSourceCard.performClick() }
        }
        toolboxDialog.window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            // Rounded card is drawn by the layout → transparent window, ~90% of the screen width
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            setGravity(Gravity.CENTER)
        }
        toolboxDialog.setCancelable(true)
        // Facecam switch follows the real state (opened / failed / closed) while showing
        val facecamStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                syncingFacecamSwitch = true
                dialogBinding.switchForFaceCame.isChecked = CameraService.isRunning
                syncingFacecamSwitch = false
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, facecamStateReceiver, IntentFilter(CameraService.PREFS_TOOLS_CAMERA),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        toolboxDialog.setOnDismissListener {
            try {
                unregisterReceiver(facecamStateReceiver)
            } catch (_: Exception) {
            }
            onDismiss?.invoke()
        }
        toolboxDialog.show()

    }

    /** True while the facecam switch is set from code (not by the user). */
    private var syncingFacecamSwitch = false

    private fun hasFacecamPermissions(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))

    /** Bring the app to the front and run its facecam permission flow (it starts the facecam). */
    private fun openAppFacecamFlow() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    .putExtra(MainActivity.EXTRA_START_FACECAM, true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening the facecam permission flow", e)
        }
    }

    /** Bring the app to the front to ask for RECORD_AUDIO (audio source → Microphone). */
    private fun openAppAudioMicFlow() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    .putExtra(MainActivity.EXTRA_REQUEST_AUDIO_MIC, true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening the microphone permission flow", e)
        }
    }

    private fun handleGifAction() {
        try {
            val intent = Intent(this, GifCropService::class.java).apply {
                setAction(GifCropService.ACTION_SHOW_GIF_CROP)
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GIF activity", e)
        }
    }

    // ============ HELPER METHODS ============


    private fun checkShouldStopService() {
        if (!isScreenshotButtonVisible && !isToolboxVisible) {
            Log.d(TAG, "No active views, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotification(contentText: String = getString(R.string.notif_service_running)): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.notif_views_title))
            .setContentText(contentText).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_views_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_views_desc)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isScreenshotButtonVisible = false
        isToolboxVisible = false

        if (instance === this) instance = null
        try {
            unregisterReceiver(recordingStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering recording state receiver", e)
        }
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        hideBubbleMenu(animate = false)   // service stop → never leave the menu window behind

        try {
            currentAnimator?.cancel()
            hideFloatingButton()
            deleteZone.removeWindow()
            hideToolbox()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}