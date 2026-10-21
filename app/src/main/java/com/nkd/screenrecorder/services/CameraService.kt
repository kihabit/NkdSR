package com.nkd.screenrecorder.services

import com.nkd.screenrecorder.utils.LocaleHelper
import android.content.Context
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.databinding.DialogCameraServiceSettingBinding
import com.nkd.screenrecorder.databinding.LayoutFloatingCameraViewBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraOptions
import com.otaliastudios.cameraview.controls.Audio
import com.otaliastudios.cameraview.controls.Facing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    private lateinit var preferences: SharedPreferences
    private lateinit var cameraViewBinding: LayoutFloatingCameraViewBinding
    private lateinit var mWindowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var values: Values
    private var isCircleMode = false

    /** Coarse facecam lifecycle state — distinguishes "window attached, camera opening" from
     *  "camera actually opened and streaming" (see [CameraListener.onCameraOpened] above).
     *  Only used internally to avoid duplicate open()/report-ready work; not surfaced as UI. */
    private enum class FacecamState { IDLE, OPENING_CAMERA, PREVIEW_READY, FAILED }
    private var facecamState: FacecamState = FacecamState.IDLE

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The camera must report onCameraOpened within this time, or the attempt has failed. */
    private val CAMERA_OPEN_TIMEOUT_MS = 5_000L
    private val cameraOpenTimeout = Runnable {
        if (facecamState == FacecamState.OPENING_CAMERA) {
            Log.e("CameraService", "Camera did not open within ${CAMERA_OPEN_TIMEOUT_MS}ms")
            failFacecam(R.string.facecam_open_failed)
        }
    }

    companion object {
        var isRunning: Boolean = false
        private var isWindowViewAdded: Boolean = false

        const val PREFS_CAMERA_OVERLAY_POS = "camera_overlay_pos"
        const val PREFS_TOOLS_CAMERA: String = "tools_camera"

        /** Intent extra selecting the initial camera. No saved user preference exists for this
         *  yet, so every caller should pass [FACING_FRONT] (or omit the extra — [FACING_FRONT]
         *  is also this service's own default) to open Face Cam on the front camera. */
        const val EXTRA_FACING = "facing"
        const val FACING_FRONT = 0
        const val FACING_BACK = 1
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // isRunning only turns true once the camera has opened — also skip while the window is
        // already added (camera still opening), or a second tap adds a second, orphaned window.
        if (!isRunning && !isWindowViewAdded) {
            // CAMERA first: on Android 14+ a camera-type startForeground() without it throws.
            // (Callers — Home and the toolbox — already make sure it is granted.)
            if (!hasCameraPermission()) {
                failFacecam(R.string.facecam_camera_permission_required)
                return START_NOT_STICKY
            }
            if (!startForegroundCompat()) {
                failFacecam(R.string.facecam_open_failed)
                return START_NOT_STICKY
            }

            // ✅ A bare Service context does not reliably resolve the app's AppCompat/
            // MaterialComponents theme attributes (e.g. windowActionBar) for widgets
            // constructed during inflation — AppCompatImageButton/AppCompatImageView each
            // check this in their own constructor (ThemeUtils.checkAppCompatTheme) and log
            // "... is an AppCompat widget that can only be used with a Theme.AppCompat theme
            // (or descendant)" when it fails to resolve, even though construction otherwise
            // succeeds. Wrapping in the app's own theme guarantees correct resolution here —
            // the same pattern already used by SSViewService.handleToolboxAction() for its
            // settings dialog.
            val themedContext = ContextThemeWrapper(this, R.style.Theme_NkdSR)
            cameraViewBinding =
                LayoutFloatingCameraViewBinding.inflate(LayoutInflater.from(themedContext), null, false)

            cameraViewBinding.apply {
                setting.isGone = true
                switchCamera.isGone = true
                overlayResize.isGone = true
                hideCamera.isGone = true
                cameraControls.isGone = true
                cameraRootLayout.setOnClickListener {
                    setting.isVisible = true
                    switchCamera.isVisible = true
                    overlayResize.isVisible = true
                    hideCamera.isVisible = true
                    object : CountDownTimer(1000L, 200L) {
                        override fun onFinish() {
                            setting.isGone = true
                            switchCamera.isGone = true
                            overlayResize.isGone = true
                            hideCamera.isGone = true
                        }

                        override fun onTick(millisUntilFinished: Long) {
                        }


                    }.start()
                }

                var facing = Facing.FRONT
                try {
                    facing =
                        if (intent!!.getIntExtra(EXTRA_FACING, FACING_FRONT) == FACING_FRONT)
                            Facing.FRONT else Facing.BACK
                } catch (e: Exception) {
                }
                cameraView.facing = facing
                cameraView.clearCameraListeners()
                cameraView.addCameraListener(
                    object : CameraListener() {
                        override fun onCameraOpened(options: CameraOptions) {
                            super.onCameraOpened(options)
                            // ✅ This — not "addView() succeeded" / "open() was called" — is
                            // the actual PREVIEW_READY signal: the camera has opened and the
                            // preview surface is live. Only now is the facecam genuinely
                            // showing something, so only now do we report success.
                            // Guard against a stale/late callback arriving after the facecam
                            // was already closed (hideCamera/onDestroy → IDLE) or already
                            // failed (onCameraError → FAILED): only act while we're actually
                            // still in the "camera opening" phase we requested.
                            if (facecamState != FacecamState.OPENING_CAMERA) return
                            mainHandler.removeCallbacks(cameraOpenTimeout)
                            facecamState = FacecamState.PREVIEW_READY
                            Log.d("CameraService", "Camera opened (facing=$facing)")
                            isRunning = true
                            val stateIntent = Intent(PREFS_TOOLS_CAMERA)
                            stateIntent.putExtra("is_running", true)
                            stateIntent.setPackage(packageName)
                            sendBroadcast(stateIntent)
                        }

                        override fun onCameraError(exception: CameraException) {
                            super.onCameraError(exception)
                            // Recoverable state: the preview never came up (camera unavailable,
                            // in use by another app, disconnected, etc.) — tear it down instead
                            // of leaving a blank overlay that still claims to be running, and
                            // flip the toggle back off so the user has a clear way to retry.
                            Log.e(
                                "CameraService",
                                "Camera failed to open (facing=$facing): ${exception.message}",
                                exception
                            )
                            // Already failed, or closed by the user → nothing left to tear down
                            if (facecamState == FacecamState.FAILED || facecamState == FacecamState.IDLE) return
                            failFacecam(R.string.facecam_open_failed)
                        }
                    })
                hideCamera.setOnClickListener {
                    mainHandler.removeCallbacks(cameraOpenTimeout)
                    if (isWindowViewAdded) {
                        removeCameraWindow()
                    }
                    cameraView.close()
                    facecamState = FacecamState.IDLE
                    isRunning = false
                    stopSelf()
                    val intent = Intent(PREFS_TOOLS_CAMERA)
                    intent.putExtra("is_running", isRunning)
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    preferences.edit { putBoolean(PREFS_TOOLS_CAMERA, false) }
                }
                switchCamera.setOnClickListener {
                    Log.e(
                        "in",
                        "in switch" + cameraView.facing
                    )
                    //swap the id of the camera to be used
                    if (cameraView.facing == Facing.BACK) {
                        cameraView.facing = Facing.FRONT
                    } else {
                        cameraView.facing = Facing.BACK
                    }
                }
                setting.setOnClickListener {

                    // Same bare-Service-context theme-resolution issue as the main overlay
                    // (see themedContext above) — this dialog's layout also uses
                    // AppCompatImageView for its two mode icons.
                    val dialogThemedContext = ContextThemeWrapper(this@CameraService, R.style.Theme_NkdSR)
                    val cameraSettingBinding = DialogCameraServiceSettingBinding.inflate(
                        LayoutInflater.from(dialogThemedContext), null, false
                    )
                    val alertBuilder = AlertDialog.Builder(dialogThemedContext)
                        .setView(cameraSettingBinding.root)
                        .create()
                    if (isCircleMode) {
                        cameraSettingBinding.circleImage.setColorFilter(resources.getColor(R.color.primaryColor))
                        cameraSettingBinding.rectangleImage.setColorFilter(resources.getColor(R.color.black))
                        cameraSettingBinding.circleTextView.setTextColor(resources.getColor(R.color.primaryColor))
                        cameraSettingBinding.rectangleTextView.setTextColor(resources.getColor(R.color.black))
                        cameraSettingBinding.circleImage.isSelected = true
                        cameraSettingBinding.rectangleImage.isSelected = false
                    } else {
                        cameraSettingBinding.circleImage.isSelected = false
                        cameraSettingBinding.rectangleImage.isSelected = true
                        cameraSettingBinding.circleImage.setColorFilter(resources.getColor(R.color.black))
                        cameraSettingBinding.rectangleImage.setColorFilter(resources.getColor(R.color.primaryColor))
                        cameraSettingBinding.circleTextView.setTextColor(resources.getColor(R.color.black))
                        cameraSettingBinding.rectangleTextView.setTextColor(resources.getColor(R.color.primaryColor))
                    }
                    alertBuilder.window?.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE
                    )
                    cameraSettingBinding.rectangleImage.setOnClickListener {
                        applyRectangleMode()
                        alertBuilder.dismiss()
                    }
                    cameraSettingBinding.circleImage.setOnClickListener {
                        setCircleMode()
                        alertBuilder.dismiss()
                    }
                    alertBuilder.show()
                }
                values = Values()

                val xPos = getXPos()
                val yPos = getYPos()

                params = WindowManager.LayoutParams(
                    values.bigCameraX, values.bigCameraY,
                    if (Build.VERSION.SDK_INT < 26) 2002 else 2038,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    -3
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.x = xPos
                params.y = yPos

                // ✅ The floating preview is an overlay window (TYPE_APPLICATION_OVERLAY) —
                // adding it without "draw over other apps" granted throws
                // WindowManager.BadTokenException ("permission denied for window type 2038"),
                // confirmed by an on-device repro. This was previously never checked here,
                // unlike the CAMERA check below — only CAMERA being granted was not sufficient
                // to safely reach addView().
                val hasOverlayPermission = android.provider.Settings.canDrawOverlays(this@CameraService)

                if (hasCameraPermission() && hasOverlayPermission) {
                    mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                    val windowAdded = try {
                        mWindowManager.addView(cameraViewBinding.root, params)
                        true
                    } catch (e: WindowManager.BadTokenException) {
                        Log.e("CameraService", "Failed to add facecam overlay window", e)
                        false
                    } catch (e: SecurityException) {
                        Log.e("CameraService", "Failed to add facecam overlay window", e)
                        false
                    }

                    if (windowAdded) {
                        isWindowViewAdded = true
                        facecamState = FacecamState.OPENING_CAMERA
                        // ✅ Guard against opening twice if onStartCommand were somehow
                        // re-entered (e.g. a stray duplicate start intent) while a previous
                        // open() is still pending.
                        // Preview only — never records video/audio, so it must not need
                        // RECORD_AUDIO (CameraView's default Audio.ON silently blocked open()).
                        cameraView.audio = Audio.OFF
                        mainHandler.postDelayed(cameraOpenTimeout, CAMERA_OPEN_TIMEOUT_MS)
                        if (!cameraView.isOpened()) {
                            try {
                                cameraView.open()
                            } catch (e: java.lang.Exception) {
                                Log.d(
                                    "CameraService",
                                    "onStartCommand: " + e.localizedMessage
                                )
                            }
                        }
                        setupDragListener()
                        // isRunning and the success broadcast are reported from
                        // CameraListener.onCameraOpened() above — only once the camera has
                        // actually opened, not merely once the window is attached and open()
                        // has been requested.
                    } else {
                        failFacecam(R.string.facecam_open_failed)
                    }
                } else {
                    // ✅ Recoverable state: nothing was actually opened (no window, no camera) —
                    // don't report isRunning=true for a facecam that isn't showing anything.
                    // Stop the pointless foreground service so the toggle reflects reality and
                    // the user has a clear path to retry after granting the missing permission.
                    val missingReasonRes = if (!hasOverlayPermission)
                        R.string.facecam_overlay_permission_required
                    else
                        R.string.facecam_camera_permission_required
                    Log.w(
                        "CameraService",
                        if (!hasOverlayPermission) "Overlay permission not granted — stopping facecam"
                        else "Camera permission not granted — stopping facecam"
                    )
                    failFacecam(missingReasonRes)
                }
            }
        }
        // Not sticky: a facecam must never be restarted from the background after the app
        // was killed (Android 12+ blocks that anyway).
        return START_NOT_STICKY
    }

    /** Foreground on every API level (started with startForegroundService on 8+). Camera
     *  type from Android 11 (API 30); before that the plain call. False if it failed. */
    private fun startForegroundCompat(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, createNotification())
        }
        true
    } catch (e: Exception) {
        Log.e("CameraService", "startForeground failed", e)
        false
    }

    /**
     * Failed attempt (no permission, window not added, camera error, or no onCameraOpened in
     * time): remove the window, close the camera, report is_running=false, stop. Resets the
     * window-added guard, so the next tap starts a fresh attempt.
     */
    private fun failFacecam(messageRes: Int) {
        mainHandler.removeCallbacks(cameraOpenTimeout)
        facecamState = FacecamState.FAILED
        isRunning = false
        if (isWindowViewAdded) removeCameraWindow()
        isWindowViewAdded = false
        if (::cameraViewBinding.isInitialized) {
            try {
                cameraViewBinding.cameraView.close()
            } catch (_: Exception) {
            }
        }
        android.widget.Toast.makeText(this, getString(messageRes), android.widget.Toast.LENGTH_SHORT).show()
        sendBroadcast(Intent(PREFS_TOOLS_CAMERA).putExtra("is_running", false).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setCircleMode() {
        isCircleMode = true

        // Force square dimensions for perfect circle
        val currentSize = min(params.width, params.height)
        params.width = currentSize
        params.height = currentSize

        try {
            mWindowManager.updateViewLayout(cameraViewBinding.root, params)
        } catch (_: Exception) {
        }

        cameraViewBinding.apply {
            // Apply circle clipping to camera frame
            cameraFrameLayout.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            cameraFrameLayout.clipToOutline = true

            // Apply circle clipping to camera view
            cameraView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            cameraView.clipToOutline = true

            // Reposition buttons to be inside the circle
            // Calculate inset needed: for a circle, corner distance = radius * sqrt(2) ≈ radius * 1.414
            // To keep buttons inside, we need to move them inward by approximately: (radius - radius/1.414)
            // ~15% inset from edges, but never so much that two 40dp buttons on one side
            // overlap (inner width = window − 2 × 7dp root padding).
            val density = resources.displayMetrics.density
            val inner = currentSize - (14 * density).toInt()
            val inset = min((currentSize * 0.15f).toInt(), max(0, (inner - (80 * density).toInt()) / 2))

            (setting.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(inset, inset, 0, 0)
            }
            setting.requestLayout()

            (hideCamera.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, inset, inset, 0)
            }
            hideCamera.requestLayout()

            (switchCamera.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(inset, 0, 0, inset)
            }
            switchCamera.requestLayout()

            (overlayResize.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, 0, inset, inset)
            }
            overlayResize.requestLayout()
        }
    }

    fun applyRectangleMode() {
        isCircleMode = false

        cameraViewBinding.apply {
            // Remove circle clipping
            cameraFrameLayout.outlineProvider = null
            cameraFrameLayout.clipToOutline = false

            cameraView.outlineProvider = null
            cameraView.clipToOutline = false

            // Reset button positions to the corners (the 8dp gap is the button background's inset)
            (setting.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, 0, 0, 0)
            }
            setting.requestLayout()

            (hideCamera.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, 0, 0, 0)
            }
            hideCamera.requestLayout()

            (switchCamera.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, 0, 0, 0)
            }
            switchCamera.requestLayout()

            (overlayResize.layoutParams as ConstraintLayout.LayoutParams).apply {
                setMargins(0, 0, 0, 0)
            }
            overlayResize.requestLayout()
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "channel_id_123",
                getString(R.string.channel_camera_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // Create an intent that will be fired when the user taps the notification
        val notificationIntent: Intent = Intent(this, MainActivity::class.java)
        // Add FLAG_IMMUTABLE or FLAG_MUTABLE
        PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // or PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE if you want to update the existing PendingIntent
        )

        val notification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(this, "channel_id_123")
                .setContentTitle(getString(R.string.notif_camera_title))
                .setContentText(getString(R.string.notif_camera_text))
                .setSmallIcon(R.drawable.icon)
                .build()
        } else {
            notification = Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_camera_title))
                .setContentText(getString(R.string.notif_camera_text))
                .setSmallIcon(R.drawable.icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        return notification
    }

    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this.applicationContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getXPos(): Int {
        return preferences.getString(PREFS_CAMERA_OVERLAY_POS, "0X100")!!
            .split("X".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0].toInt()
    }

    private fun getYPos(): Int {
        return preferences.getString(PREFS_CAMERA_OVERLAY_POS, "0X100")!!
            .split("X".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].toInt()
    }

    fun persistCoordinates(i: Int, i2: Int) {
        preferences.edit {
            putString(PREFS_CAMERA_OVERLAY_POS, i.toString() + "X" + i2)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        cameraViewBinding.cameraView.setOnTouchListener(
            object : OnTouchListener {
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var initialX = 0
                private var initialY = 0
                var isMoving: Boolean = false
                private var paramsF: WindowManager.LayoutParams? = null

                init {
                    paramsF = params
                }

                override fun onTouch(view: View?, motionEvent: MotionEvent): Boolean {
                    val action = motionEvent.action
                    when (action) {
                        MotionEvent.ACTION_UP -> {
                            Log.e("CameraService", "up action performed")
                            Log.e("CameraService", "Camera Frame layout surface clicked")
                            cameraViewBinding.setting.isVisible = true
                            cameraViewBinding.switchCamera.isVisible = true
                            cameraViewBinding.overlayResize.isVisible = true
                            cameraViewBinding.hideCamera.isVisible = true
                            cameraViewBinding.cameraControls.isVisible = true
                            object : CountDownTimer(5000L, 1000L) {
                                override fun onFinish() {
                                    cameraViewBinding.setting.isGone = true
                                    cameraViewBinding.switchCamera.isGone = true
                                    cameraViewBinding.overlayResize.isGone = true
                                    cameraViewBinding.hideCamera.isGone = true
                                }

                                override fun onTick(millisUntilFinished: Long) {
                                }


                            }.start()
                            return true
                        }

                        MotionEvent.ACTION_DOWN -> {
                            isMoving = false
                            initialX = paramsF!!.x
                            initialY = paramsF!!.y
                            initialTouchX = motionEvent.rawX
                            initialTouchY = motionEvent.rawY
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val rawX = (motionEvent.rawX - initialTouchX).toInt()
                            val rawY = (motionEvent.rawY - initialTouchY).toInt()
                            paramsF!!.x = initialX + rawX
                            paramsF!!.y = initialY + rawY
                            if (abs(rawX) > 10 || abs(rawY) > 10) {
                                isMoving = true
                            }
                            try {
                                mWindowManager.updateViewLayout(cameraViewBinding.root, paramsF)
                            } catch (unused: java.lang.Exception) {
                            }
                            persistCoordinates(initialX + rawX, initialY + rawY)
                            return true
                        }

                        else -> {
                            return false
                        }
                    }
                }
            })
        cameraViewBinding.overlayResize.setOnTouchListener(object : OnTouchListener {

            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialWidth = 0
            private var initialHeight = 0

            override fun onTouch(view: View?, motionEvent: MotionEvent): Boolean {

                when (motionEvent.action) {

                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = motionEvent.rawX
                        initialTouchY = motionEvent.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val dx = (motionEvent.rawX - initialTouchX).toInt()
                        val dy = (motionEvent.rawY - initialTouchY).toInt()

                        // Lower limit: 200px, or 110dp if bigger — the smallest size where the
                        // four 40dp corner buttons still fit without overlapping.
                        val minSize = max(200, (110 * resources.displayMetrics.density).roundToInt())
                        val maxSize = max(800, minSize)

                        if (isCircleMode) {
                            // Use bigger delta for smooth scaling
                            val newSize = max(initialWidth + dx, initialHeight + dy)

                            params.width = newSize.coerceIn(minSize, maxSize)
                            params.height = newSize.coerceIn(minSize, maxSize)

                        } else {
                            params.width = (initialWidth + dx).coerceIn(minSize, maxSize)
                            params.height = (initialHeight + dy).coerceIn(minSize, maxSize)
                        }

                        try {
                            mWindowManager.updateViewLayout(cameraViewBinding.root, params)
                        } catch (_: Exception) {
                        }

                        // Reapply circle mask because size changed
                        if (isCircleMode) {
                            setCircleMode()
                        }

                        return true
                    }
                }
                return false
            }
        })

    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(cameraOpenTimeout)
        isRunning = false
        facecamState = FacecamState.IDLE
        super.onDestroy()
        preferences.edit { putBoolean(PREFS_TOOLS_CAMERA, false) }
        if (::cameraViewBinding.isInitialized) {
            if (isWindowViewAdded) {
                removeCameraWindow()
            }
            cameraViewBinding.cameraView.close()
            cameraViewBinding.cameraView.destroy()
        }
        Log.e("FloatingCameraService", " OnDestroy $isRunning")
    }

    /** Removes the facecam window if it is attached; never throws. */
    private fun removeCameraWindow() {
        try {
            if (cameraViewBinding.root.isAttachedToWindow) {
                mWindowManager.removeViewImmediate(cameraViewBinding.root)
            }
        } catch (e: Exception) {
            Log.w("CameraService", "Facecam window already removed: ${e.message}")
        }
        isWindowViewAdded = false
    }

    inner class Values {
        var bigCameraX: Int = 0
        var bigCameraY: Int = 0
        var cameraHideX: Int = dpToPx(60)
        var cameraHideY: Int = dpToPx(60)
        var smallCameraX: Int = 0
        var smallCameraY: Int = 0

        init {
            buildValues()
        }

        private fun dpToPx(i: Int): Int {
            return (i * (resources.displayMetrics.xdpi / 140.0f)).roundToInt()
        }

        fun buildValues() {
            if (resources.configuration.orientation == 2) {
                smallCameraX = dpToPx(160)
                smallCameraY = dpToPx(120)
                bigCameraX = dpToPx(200)
                bigCameraY = dpToPx(150)
            } else {
                smallCameraX = dpToPx(120)
                smallCameraY = dpToPx(160)
                bigCameraX = dpToPx(150)
                bigCameraY = dpToPx(200)
            }
        }
    }
}