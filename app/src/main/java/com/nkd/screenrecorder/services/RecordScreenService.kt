package com.nkd.screenrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import android.provider.DocumentsContract
import android.view.View
import android.view.Gravity
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.SessionState
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.databinding.LayoutCountdownBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.utils.AudioSourcePrefs
import com.nkd.screenrecorder.utils.VideoQualityPrefs
import com.nkd.screenrecorder.utils.CropCoordinates
import com.nkd.screenrecorder.utils.LocaleHelper
import com.nkd.screenrecorder.utils.NotificationPanel
import com.nkd.screenrecorder.utils.ScreenshotPreviewHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class RecordScreenService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    // Recording related
    private var recordingMediaProjection: MediaProjection? = null
    private var recordingVirtualDisplay: VirtualDisplay? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 0
    private var frameRate = 30
    private var videoBitrate = 8_000_000
    /** Audio source for the CURRENT recording, read once when it starts (AudioSourcePrefs):
     *  changing it mid-recording only affects the next one. */
    private var recordAudio = false
    private lateinit var preferences: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var mediaRecorder: MediaRecorder? = null

    // Screenshot related
    private var imageReader: ImageReader? = null
    private val isCapturing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Separate MediaProjection for screenshots
    private var screenshotMediaProjection: MediaProjection? = null
    private var screenshotVirtualDisplay: VirtualDisplay? = null
    private var cropCoordinates: CropCoordinates? = null

    companion object {
        private const val PREFS_NAME = "ScreenRecorderPrefs"

        // Recording actions
        const val ACTION_START_RECORDING = "action_start_recording"
        const val ACTION_STOP_RECORDING = "action_stop_recording"
        const val ACTION_PAUSE_RECORDING = "action_pause_recording"
        const val ACTION_RESUME_RECORDING = "action_resume_recording"

        // Screenshot actions
        const val ACTION_TAKE_SCREENSHOT = "action_take_screenshot"
        const val ACTION_SCREENSHOT_PERMISSION_DENIED = "action_screenshot_permission_denied"

        // GIF actions
        const val ACTION_START_GIF = "action_start_gif"
        const val EXTRA_CROP_COORDINATES = "extra_crop_coordinates"
        const val EXTRA_CROP_RECT = "extra_crop_rect"

        // Common
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"

        // Broadcasts
        const val ACTION_CLOSE_ACTIVITY = "close_activity_action"
        const val ACTION_RECORDING_STATE = "recording_state_changed"
        const val EXTRA_STATE = "extra_state"
        const val NEW_RECORDING_ADDED = "new_recording_added"

        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001
        /** Countdown window removed → recording starts this much later (never in the video). */
        private const val COUNTDOWN_REMOVE_DELAY_MS = 150L
        private const val TAG = "RecordScreenService"

        var isRecording = false

        /** True while a recording is paused (MediaRecorder.pause(), API 24+). */
        var isPaused = false
            private set

        private const val CAPTURE_DELAY = 300L
    }

    /** GIF capture uses a fixed timer — Pause is hidden in its notification. */
    private var isGifCapture = false

    // ── Recorded length for the notification timer (paused time not counted) ──
    /** Recorded time of all finished segments (a segment ends at Pause / Stop). */
    private var recordedMillis = 0L
    /** elapsedRealtime() when the running segment started; 0 = not running (countdown, paused). */
    private var segmentStartElapsed = 0L

    private fun recordedMillisNow(): Long =
        recordedMillis +
                if (segmentStartElapsed > 0L) SystemClock.elapsedRealtime() - segmentStartElapsed else 0L

    /** New recording / GIF capture: timer back to 00:00, stopped until MediaRecorder starts. */
    private fun resetRecordingTimer() {
        recordedMillis = 0L
        segmentStartElapsed = 0L
    }

    /** RECORDING panel of the notification toolbar (Pause/Resume, Stop, Tools, live timer). */
    private fun createRecordingPanel(): Notification =
        NotificationPanel.buildRecording(
            this, isPaused, allowPause = !isGifCapture,
            recordedMillis = recordedMillisNow(),
            timerRunning = isRecording && !isPaused && segmentStartElapsed > 0L
        )

    private val recordingMediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Recording MediaProjection stopped by system")
            if (isRecording) {
                stopRecording()
            }
            cleanupRecordingMediaProjection()
            checkShouldStopService()
        }
    }

    private val screenshotMediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Screenshot MediaProjection stopped")
            cleanupScreenshotMediaProjection()
        }
    }

    /** True only while WE paused the recording because the screen went off. */
    private var pausedByScreenOff = false

    /**
     * Settings → "Keep recording when screen is off" OFF (default): pause on screen off,
     * resume once the user is back (unlocked, or screen on without a lock screen).
     * A recording the USER paused is never resumed here. ON: keep recording as before.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val keepRecording = preferences.getBoolean(SettingFragment.KEEP_RECORDING, false)
                    if (!keepRecording && isRecording && !isPaused && !isGifCapture) {
                        pauseRecording()
                        pausedByScreenOff = isPaused
                        Log.d(TAG, "Screen off → recording paused (auto)")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // No lock screen → USER_PRESENT may never come: resume now
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (pausedByScreenOff && !km.isKeyguardLocked) resumeAfterScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> if (pausedByScreenOff) resumeAfterScreenOff()
            }
        }
    }

    private fun resumeAfterScreenOff() {
        pausedByScreenOff = false
        resumeRecording()
        Log.d(TAG, "Screen back → recording resumed (auto)")
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                isGifCapture = false
                isPaused = false
                resetRecordingTimer()
                recordAudio = AudioSourcePrefs.recordsMicrophone(this)   // fresh for every recording
                // Start foreground with MEDIA_PROJECTION type for recording
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Microphone type only when this recording really uses the mic
                    startForeground(
                        NOTIFICATION_ID,
                        createRecordingPanel(),
                        if (recordAudio) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        createRecordingPanel(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createRecordingPanel())
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val outputUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_URI)

                if (resultCode == -1 && data != null && outputUri != null) {
                    val showCountDown =
                        preferences.getBoolean(SettingFragment.SHOW_COUNT_DOWN, true)

                    if (showCountDown) {
                        showCountdownAndStartRecording(resultCode, data, outputUri)
                    } else {
                        startRecording(resultCode, data, outputUri)
                        sendCloseActivityBroadcast()
                    }
                } else {
                    Log.e(TAG, "Missing required data for recording")
                    stopSelf()
                }
            }

            ACTION_STOP_RECORDING -> {
                if (isCountdownActive) {   // Stop during the countdown = cancel it
                    cancelCountdown()
                    return START_NOT_STICKY
                }
                stopRecording()
                checkShouldStopService()
            }

            ACTION_PAUSE_RECORDING -> {
                pausedByScreenOff = false   // user paused: never auto-resume
                pauseRecording()
            }

            ACTION_RESUME_RECORDING -> {
                pausedByScreenOff = false
                resumeRecording()
            }

            ACTION_START_GIF -> {
                isGifCapture = true   // fixed 18s capture → no Pause button
                isPaused = false
                resetRecordingTimer()
                recordAudio = AudioSourcePrefs.recordsMicrophone(this)   // fresh for every recording

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Microphone type only when this recording really uses the mic
                    startForeground(
                        NOTIFICATION_ID,
                        createRecordingPanel(),
                        if (recordAudio) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        createRecordingPanel(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createRecordingPanel())
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val outputUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_URI)
                cropCoordinates = intent.getParcelableExtra<CropCoordinates>("coordinates")
                val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra<Rect>(EXTRA_CROP_RECT, Rect::class.java)
                } else {
                    intent.getParcelableExtra<Rect>(EXTRA_CROP_RECT)
                }
                val width = intent.getIntExtra("width", 0)
                val height = intent.getIntExtra("height", 0)


                if (resultCode == -1 && data != null && outputUri != null) {
                    val showCountDown =
                        preferences.getBoolean(SettingFragment.SHOW_COUNT_DOWN, true)

                    if (showCountDown) {
                        showCountdownAndStartRecording(resultCode, data, outputUri)
                    } else {
                        startRecording(resultCode, data, outputUri)
                        sendCloseActivityBroadcast()
                    }
                    convertVideoToGIF(outputUri, rect!!, width, height)
                } else {
                    Log.e(TAG, "Missing required data for recording")
                    stopSelf()
                }
            }

            ACTION_TAKE_SCREENSHOT -> {
                // ✅ Screenshots are disabled while a recording is running (or paused).
                if (isRecording) {
                    Log.d(TAG, "Screenshot blocked: recording in progress")
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            getString(R.string.screenshot_unavailable_recording),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return START_NOT_STICKY
                }
                Log.e(TAG, "Screenshot started 230")
                // ✅ Only (re)promote to foreground if this service isn't ALREADY a foreground
                // service — i.e. no recording is currently active. Recording's MediaProjection
                // stays valid only as long as this service's foreground promotion stays
                // continuous; calling startForeground() again here (with a different
                // notification and a narrower FOREGROUND_SERVICE_TYPE that drops the
                // MICROPHONE type recording's own promotion included) re-declares that
                // promotion and was silently invalidating the recording's MediaProjection —
                // which is what triggered recordingMediaProjectionCallback.onStop() and
                // stopped+saved the recording every time a screenshot was taken mid-recording.
                // If recording is already active, its own startForeground() call already
                // covers everything the screenshot pipeline needs; nothing further is required.
                if (!isRecording) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(getString(R.string.screenshot)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.screenshot)))
                    }
                }

                // ✅ Root cause fix: while a recording is active, the caller must not be
                // required to supply a fresh EXTRA_RESULT_CODE/EXTRA_DATA — obtaining those
                // means re-running createScreenCaptureIntent()/the consent flow, which
                // silently revokes recordingMediaProjection and kills the recording.
                // captureFromRecordingProjection() (reached via takeScreenshot() below)
                // reuses recordingMediaProjection directly and never reads resultCode/data,
                // so skip the requirement entirely in that case.
                if (isRecording && recordingMediaProjection != null) {
                    takeScreenshot(-1, Intent())
                } else {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                    if (resultCode == -1 && data != null) {
                        takeScreenshot(resultCode, data)
                    } else {
                        Log.e(TAG, "Invalid result code or data for screenshot")
                    }
                }
            }

            ACTION_SCREENSHOT_PERMISSION_DENIED -> {
                Log.d(TAG, "Screenshot permission denied")
                isCapturing.set(false)
            }
        }
        return START_NOT_STICKY
    }

    // ============ RECORDING METHODS ============

    // Countdown before a recording: the number only (small centred window, never touchable)
    private var countdownView: View? = null
    private var countdownTimer: CountDownTimer? = null
    /** File created for the recording the countdown is waiting for (deleted on cancel). */
    private var countdownOutputUri: Uri? = null
    private var pendingCountdownStart: Runnable? = null
    private val isCountdownActive get() = countdownView != null || countdownTimer != null

    private fun showCountdownAndStartRecording(
        resultCode: Int,
        data: Intent,
        outputUri: Uri,
    ) {
        removeCountdownWindow()   // never two countdowns at once
        val countDown = SettingFragment.countdownSeconds(preferences)   // always 1–10
        val countDownBinding =
            LayoutCountdownBinding.inflate(LayoutInflater.from(this), null, false)
        // WRAP_CONTENT around the number only, centred, and NOT_TOUCHABLE: it never receives
        // touches, so they all reach the apps behind (also under Android 12+ untrusted-touch
        // rules — the window covers only the number, not the screen).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        countDownBinding.tvCountDown.text =
            String.format(java.util.Locale.getDefault(), "%d", countDown)

        // Without overlay permission (or if the window can't be added) start recording right
        // away instead of crashing.
        try {
            windowManager.addView(countDownBinding.root, params)
        } catch (e: Exception) {
            Log.w(TAG, "Countdown overlay not shown, starting without it", e)
            startRecording(resultCode, data, outputUri)
            sendCloseActivityBroadcast()
            return
        }
        countdownView = countDownBinding.root
        countdownOutputUri = outputUri
        countdownTimer = object : CountDownTimer(countDown * 1000L, 1000L) {
            override fun onFinish() {
                countdownTimer = null
                removeCountdownWindow()
                countdownOutputUri = null
                // Start only after the number is gone, so it is never in the first frames
                val start = Runnable {
                    pendingCountdownStart = null
                    startRecording(resultCode, data, outputUri)
                    sendCloseActivityBroadcast()
                }
                pendingCountdownStart = start
                mainHandler.postDelayed(start, COUNTDOWN_REMOVE_DELAY_MS)
            }

            override fun onTick(millisUntilFinished: Long) {
                // +999 so it shows 3, 2, 1 (not 2, 1, 0) for a 3 s countdown
                countDownBinding.tvCountDown.text = String.format(
                    java.util.Locale.getDefault(), "%d", (millisUntilFinished + 999) / 1000
                )
            }
        }.start()
    }

    /** Stops the timer and removes the countdown window — safe in every path, never throws. */
    private fun removeCountdownWindow() {
        countdownTimer?.cancel()
        countdownTimer = null
        countdownView?.let { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.w(TAG, "Countdown window already removed: ${e.message}")
            }
        }
        countdownView = null
    }

    /**
     * Stop pressed during the countdown: no recording. The projection
     * consent is simply never used (no MediaProjection exists yet), the empty output file is
     * deleted, the state/UI go back to "not recording" and the service stops.
     */
    private fun cancelCountdown() {
        if (!isCountdownActive) return
        removeCountdownWindow()
        countdownOutputUri?.let { uri ->
            try {
                if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
                    contentResolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete the unused recording file", e)
            }
        }
        countdownOutputUri = null
        cleanupRecordingMediaProjection()   // nothing to stop unless it was already created
        resetRecordingTimer()
        isPaused = false
        updateRecordingState(false)
        sendRecordingStateBroadcast(false)
        Log.d(TAG, "Countdown cancelled — recording not started")
        checkShouldStopService()
    }

    private fun startRecording(resultCode: Int, data: Intent, outputUri: Uri) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            recordingMediaProjection = projectionManager.getMediaProjection(resultCode, data)
            recordingMediaProjection?.registerCallback(recordingMediaProjectionCallback, null)
            Log.d(TAG, "Created MediaProjection for recording")

            // Video quality / frame rate read fresh for EVERY recording (may change any time on
            // Home, in Settings or the toolbox) — used by MediaRecorder and the VirtualDisplay.
            getScreenMetrics()
            initRecorder(outputUri)
            createVirtualDisplayForRecording()

            mediaRecorder?.start()
            resetRecordingTimer()
            segmentStartElapsed = SystemClock.elapsedRealtime()   // timer starts with the recorder
            updateRecordingState(true)
            sendRecordingStateBroadcast(true)
            updateNotification(getString(R.string.notif_recording_in_progress))
            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            cleanupRecordingResources()
            checkShouldStopService()
        }
    }

    private fun initRecorder(outputUri: Uri) {
        try {
            fileDescriptor = contentResolver.openFileDescriptor(outputUri, "w")
                ?: throw IOException("Failed to open file descriptor")
            // Mute (or no RECORD_AUDIO) → video only: no audio source, no audio encoder
            val withAudio = recordAudio

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                if (withAudio) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }

                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fileDescriptor!!.fileDescriptor)
                setVideoSize(screenWidth, screenHeight)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)

                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(256_000)  // 256kbps —
                    setAudioSamplingRate(44_100)        // standard
                    setAudioChannels(2)                 // stereo
                }

                setVideoEncodingBitRate(videoBitrate)
                setVideoFrameRate(frameRate)
                setOrientationHint(0)

                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing recorder", e)
            throw e
        }
    }

    private fun createVirtualDisplayForRecording() {
        recordingVirtualDisplay = recordingMediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            mediaRecorder?.surface,
            null,
            null
        )
        Log.d(TAG, "Created VirtualDisplay for recording")
    }

    /** Notification toolbar → Pause (MediaRecorder.pause(), API 24+ = every supported device). */
    private fun pauseRecording() {
        if (!isRecording || isPaused || isGifCapture) return
        try {
            mediaRecorder?.pause()
            recordedMillis = recordedMillisNow()   // freeze the timer at the recorded time
            segmentStartElapsed = 0L
            isPaused = true
            updateNotification(getString(R.string.notif_recording_paused))
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing recording", e)
        }
    }

    /** Notification toolbar → Resume. */
    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        try {
            mediaRecorder?.resume()
            segmentStartElapsed = SystemClock.elapsedRealtime()   // continue from the frozen time
            isPaused = false
            updateNotification(getString(R.string.notif_recording_in_progress))
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
        }
    }

    private fun stopRecording() {
        resetRecordingTimer()   // stopped → the RECORDING panel (and its timer) goes away
        pausedByScreenOff = false
        isPaused = false   // stop() is valid from the paused state too
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null

            recordingVirtualDisplay?.release()
            recordingVirtualDisplay = null
            Log.d(TAG, "Released recording VirtualDisplay")

            fileDescriptor?.close()
            fileDescriptor = null

            updateRecordingState(false)
            sendRecordingStateBroadcast(false)
            notifyNewRecording()

            cleanupRecordingMediaProjection()

            updateNotification(getString(R.string.recording_stopped))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    // ============ SCREENSHOT METHODS ============

    private fun takeScreenshot(resultCode: Int, data: Intent) {
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already in progress")
            return
        }

        // ✅ Confirmed via stack trace: requesting a second MediaProjection via
        // getMediaProjection() while recordingMediaProjection is already active causes the OS
        // to revoke the recording's projection (one active MediaProjection per app), which
        // fires recordingMediaProjectionCallback.onStop() and stops+saves the recording. While
        // a recording is active, never call getMediaProjection() again — reuse
        // recordingMediaProjection (already granted, already active) to create a temporary,
        // on-demand VirtualDisplay for just this one frame instead.
        if (isRecording && recordingMediaProjection != null) {
            captureFromRecordingProjection()
        } else {
            captureWithNewProjection(resultCode, data)
        }
    }

    /**
     * Screenshot path used while recording is active — reuses recordingMediaProjection's
     * existing grant instead of requesting a new one, avoiding OS-level revocation of the
     * recording's projection. Only the temporary ImageReader/VirtualDisplay created here are
     * torn down afterwards (via cleanupScreenshotResources()); the recording's own
     * MediaRecorder/VirtualDisplay/MediaProjection are never touched.
     */
    private fun captureFromRecordingProjection() {
        try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Log.d(TAG, "Taking screenshot from recording's existing MediaProjection: ${width}x${height}@${density}dpi")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            var captureAttempted = false

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!captureAttempted) {
                    captureAttempted = true
                    mainHandler.post {
                        captureImageFromReader(reader)
                    }
                }
            }, mainHandler)

            // ✅ Extra VirtualDisplay off the SAME, already-active MediaProjection — a single
            // MediaProjection can legitimately back multiple concurrent VirtualDisplays, so
            // this doesn't require (or trigger) a new getMediaProjection() call.
            screenshotVirtualDisplay = recordingMediaProjection?.createVirtualDisplay(
                "ScreenshotCaptureFromRecording",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            if (screenshotVirtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay from recording's projection")
                isCapturing.set(false)
                cleanupScreenshotResources()
                return
            }

            Log.d(TAG, "Created VirtualDisplay from recording's projection")

            mainHandler.postDelayed({
                if (!captureAttempted) {
                    captureAttempted = true
                    Log.d(TAG, "Using fallback capture method (shared projection)")
                    captureImageDirect()
                }
            }, CAPTURE_DELAY)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot from recording's projection", e)
            isCapturing.set(false)
            cleanupScreenshotResources()
        }
    }

    /** Screenshot path used when no recording is active — safe to request a fresh, standalone MediaProjection. */
    private fun captureWithNewProjection(resultCode: Int, data: Intent) {
        // Clean up any existing standalone screenshot projection first
        cleanupScreenshotMediaProjection()

        try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Log.d(
                TAG,
                "Taking screenshot: ${width}x${height}@${density}dpi, isRecording=$isRecording"
            )

            // Create a SEPARATE MediaProjection instance for screenshots
            // This won't interfere with the recording MediaProjection
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenshotMediaProjection = projectionManager.getMediaProjection(resultCode, data)
            screenshotMediaProjection?.registerCallback(
                screenshotMediaProjectionCallback,
                mainHandler
            )
            Log.d(TAG, "Created separate MediaProjection for screenshot")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            var captureAttempted = false

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!captureAttempted) {
                    captureAttempted = true
                    mainHandler.post {
                        captureImageFromReader(reader)
                    }
                }
            }, mainHandler)

            // Create VirtualDisplay for screenshot
            screenshotVirtualDisplay = screenshotMediaProjection?.createVirtualDisplay(
                "ScreenshotCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            if (screenshotVirtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay for screenshot")
                cleanupScreenshotMediaProjection()
                isCapturing.set(false)
                cleanupScreenshotResources()
                return
            }

            Log.d(TAG, "Created VirtualDisplay for screenshot")

            // Fallback capture after delay
            mainHandler.postDelayed({
                if (!captureAttempted) {
                    captureAttempted = true
                    Log.d(TAG, "Using fallback capture method")
                    captureImageDirect()
                }
            }, CAPTURE_DELAY)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            cleanupScreenshotMediaProjection()
            isCapturing.set(false)
            cleanupScreenshotResources()
        }
    }

    private fun captureImageFromReader(reader: ImageReader) {
        try {
            mainHandler.postDelayed({
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processAndSaveImage(image)
                } else {
                    Log.e(TAG, "Failed to acquire image from reader")
                    cleanupAfterScreenshot()
                }
            }, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing from reader", e)
            cleanupAfterScreenshot()
        }
    }

    private fun captureImageDirect() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                processAndSaveImage(image)
            } else {
                Log.e(TAG, "Failed to acquire image directly")
                cleanupAfterScreenshot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct capture", e)
            cleanupAfterScreenshot()
        }
    }

    private fun processAndSaveImage(image: android.media.Image) {
        var tempBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null

        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e(TAG, "No image planes available")
                return
            }

            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = rowStride / pixelStride
            val bitmapHeight = image.height

            tempBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

            buffer.rewind()
            tempBitmap.copyPixelsFromBuffer(buffer)

            finalBitmap = if (rowPadding != 0 && bitmapWidth != image.width) {
                Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
            } else {
                tempBitmap
            }

            val bitmapToSave = finalBitmap.copy(finalBitmap.config!!, false)
            Thread {
                saveBitmapToStorage(bitmapToSave)
                bitmapToSave.recycle()
            }.start()

            Log.d(TAG, "Screenshot processed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing image", e)
            }

            if (finalBitmap != tempBitmap) {
                tempBitmap?.recycle()
            }
            finalBitmap?.recycle()

            cleanupAfterScreenshot()
        }
    }

    private fun cleanupAfterScreenshot() {
        // Clean up screenshot resources
        cleanupScreenshotResources()
        cleanupScreenshotMediaProjection()
        isCapturing.set(false)
    }

    private fun saveBitmapToStorage(bitmap: Bitmap) {
        try {
            Log.e(TAG, "SAVING SCREENSHOT")
            val timestamp = System.currentTimeMillis()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ✅ Android 10+
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "SCR_$timestamp.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenRecorder")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }

                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)

                    ScreenshotPreviewHelper.showScreenshotPreview(this, uri)
                }

            } else {
                // ✅ Android 9 and below
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ScreenRecorder"
                )
                if (!dir.exists()) dir.mkdirs()

                val file = File(dir, "SCR_$timestamp.png")
                val fos = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()
                fos.close()

                val uri = Uri.fromFile(file)
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))

                ScreenshotPreviewHelper.showScreenshotPreview(this, uri)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot", e)
        }
    }


    // ============ HELPER METHODS ============

    private fun getScreenMetrics() {
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
        Log.e(TAG, "Screen recording Display width $screenWidth height $screenHeight")
        // Size (encoder-supported, aspect kept), FPS and bitrate from the saved video quality / frame rate
        val spec = VideoQualityPrefs.resolve(this, screenWidth, screenHeight)
        screenWidth = spec.width
        screenHeight = spec.height
        frameRate = spec.fps
        videoBitrate = spec.bitrate
        Log.d(TAG, "Recording spec ${spec.width}x${spec.height} @${spec.fps}fps ${spec.bitrate}bps")
    }

    private fun updateNotification(text: String) {
        // While recording (or paused) the foreground notification is the RECORDING panel.
        val notification = if (isRecording) createRecordingPanel() else createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String = getString(R.string.notif_service_running)): Notification {
        val stopIntent = Intent(this, RecordScreenService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_recorder_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_stop_recording),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_recording_desc)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateRecordingState(state: Boolean) {
        isRecording = state
        preferences.edit { putBoolean("recording_state", state) }
    }

    private fun sendRecordingStateBroadcast(state: Boolean) {
        Log.d(TAG, "Sending recording state broadcast: $state")
        val intent = Intent(ACTION_RECORDING_STATE)
        intent.putExtra(EXTRA_STATE, state)
        intent.setPackage(packageName)
        sendBroadcast(intent)

        mainHandler.postDelayed({
            Log.d(TAG, "Re-sending recording state broadcast: $state")
            val retryIntent = Intent(ACTION_RECORDING_STATE)
            retryIntent.putExtra(EXTRA_STATE, state)
            retryIntent.setPackage(packageName)
            sendBroadcast(retryIntent)
        }, 100)
    }

    private fun sendCloseActivityBroadcast() {
        val intent = Intent(ACTION_CLOSE_ACTIVITY)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun notifyNewRecording() {
        val intent = Intent(NEW_RECORDING_ADDED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun cleanupRecordingResources() {
        try {
            mediaRecorder?.release()
            recordingVirtualDisplay?.release()
            fileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up recording resources", e)
        }
    }

    private fun cleanupScreenshotResources() {
        mainHandler.post {
            try {
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null
                screenshotVirtualDisplay?.release()
                screenshotVirtualDisplay = null
            } catch (e: Exception) {
                Log.e(TAG, "Error during screenshot cleanup", e)
            }
        }
    }

    private fun cleanupRecordingMediaProjection() {
        try {
            recordingMediaProjection?.unregisterCallback(recordingMediaProjectionCallback)
            recordingMediaProjection?.stop()
            recordingMediaProjection = null
            Log.d(TAG, "Recording MediaProjection cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up recording media projection", e)
        }
    }

    private fun cleanupScreenshotMediaProjection() {
        try {
            screenshotMediaProjection?.unregisterCallback(screenshotMediaProjectionCallback)
            screenshotMediaProjection?.stop()
            screenshotMediaProjection = null
            Log.d(TAG, "Screenshot MediaProjection cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up screenshot media projection", e)
        }
    }

    private fun checkShouldStopService() {
        if (!isRecording) {
            Log.d(TAG, "No recording active, stopping service")
            stopSelf()
        }
    }

    //--------------GIF---------------------
    fun convertVideoToGIF(
        outputUri: Uri,
        overlayRect: Rect,
        overlayWidth: Int,
        overlayHeight: Int
    ) {
        val mappedRect = mapToVideoRect(
            overlayRect,
            overlayWidth,
            overlayHeight,
            screenWidth,
            screenHeight
        )

        val cropWidth = mappedRect.width()
        val cropHeight = mappedRect.height()
        val cropLeft = mappedRect.left
        val cropTop = mappedRect.top

        val timestamp = System.currentTimeMillis()
        val pictureGifFolder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "GIF"
        )
        if (!pictureGifFolder.exists()) {
            val created = pictureGifFolder.mkdirs()
            Log.d(TAG, "Folder created: $created")
        }

        val outputFile = File(pictureGifFolder, "temp_$timestamp.gif")
        val outputPath = outputFile.absolutePath

        object : CountDownTimer(18000L, 1000L) {
            override fun onFinish() {
                cleanupScreenshotResources()
                cleanupScreenshotMediaProjection()
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null

                recordingVirtualDisplay?.release()
                recordingVirtualDisplay = null

                fileDescriptor?.close()
                fileDescriptor = null

                isGifCapture = false
                isPaused = false
                updateRecordingState(false)
                sendRecordingStateBroadcast(false)
                notifyNewRecording()
                cleanupRecordingMediaProjection()
                updateNotification(getString(R.string.recording_stopped))

                val inputFile = copyUriToFile(this@RecordScreenService, outputUri)
                val inputPath = inputFile.absolutePath

                val filter = "crop=$cropWidth:$cropHeight:$cropLeft:$cropTop," +
                        "fps=10," +
                        "scale=480:-1:flags=lanczos," +
                        "split[s0][s1];" +
                        "[s0]palettegen[p];" +
                        "[s1][p]paletteuse"

                val command = "-y -i \"$inputPath\" -vf \"$filter\" \"$outputPath\""

                Handler(Looper.getMainLooper()).postDelayed({
                    FFmpegKit.executeAsync(command) { result ->

                        when (result.state) {
                            SessionState.COMPLETED -> {
                                Log.d(TAG, "FFmpegKit success")
                                Log.d(TAG, "Output exists: ${outputFile.exists()}")
                                Log.d(TAG, "Output path: $outputPath")
                                Log.d(TAG, "Output size: ${outputFile.length()}")
                                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                    MediaScannerConnection.scanFile(
                                        applicationContext,
                                        arrayOf(outputFile.absolutePath),
                                        arrayOf("image/gif")
                                    ) { path, uri ->
                                        Log.d(TAG, "Scanned: $path")
                                        Log.d(TAG, "MediaStore Uri: $uri")
                                    }
                                }
                                inputFile.delete()
                                Handler(Looper.getMainLooper()).postDelayed({
                                    ScreenshotPreviewHelper.showScreenshotPreview(
                                        this@RecordScreenService,
                                        outputPath.toUri()
                                    )
                                }, 300)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val videoUri = DocumentFile.fromSingleUri(
                                        this@RecordScreenService,
                                        outputUri
                                    )
                                    videoUri?.delete()
                                } else {
                                    try {
                                        val videoUri = DocumentFile.fromSingleUri(
                                            this@RecordScreenService,
                                            outputUri
                                        )
                                        videoUri?.delete()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "${e.message}")
                                    }
                                }
                            }

                            SessionState.FAILED -> {
                                Log.e(TAG, "FFmpegKit failed: ${result.failStackTrace}")
                                outputFile.delete()
                                inputFile.delete()
                            }

                            else -> {}
                        }
                    }
                }, 1500)
            }

            override fun onTick(millisUntilFinished: Long) {
                Log.d(TAG, "GIF recording: ${millisUntilFinished / 1000}s left")
            }
        }.start()
    }

    fun copyUriToFile(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.cacheDir, "temp_input.mp4")
        val outputStream = FileOutputStream(file)

        inputStream?.copyTo(outputStream)

        inputStream?.close()
        outputStream.close()

        return file
    }

    fun mapToVideoRect(
        overlayRect: Rect,
        overlayWidth: Int,
        overlayHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Rect {

        val scaleX = videoWidth.toFloat() / overlayWidth
        val scaleY = videoHeight.toFloat() / overlayHeight

        return Rect(
            (overlayRect.left * scaleX).toInt(),
            (overlayRect.top * scaleY).toInt(),
            (overlayRect.right * scaleX).toInt(),
            (overlayRect.bottom * scaleY).toInt()
        )
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeCountdownWindow()
        pendingCountdownStart?.let { mainHandler.removeCallbacks(it) }
        pendingCountdownStart = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "screenStateReceiver not registered")
        }
        isRecording = false

        try {
            cleanupRecordingResources()
            cleanupScreenshotResources()
            cleanupRecordingMediaProjection()
            cleanupScreenshotMediaProjection()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}