package com.nkd.screenrecorder.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.ActivityMainBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.services.CameraService
import com.nkd.screenrecorder.services.DrawingService
import com.nkd.screenrecorder.services.NotificationPanelService
import com.nkd.screenrecorder.services.RecordScreenService
import com.nkd.screenrecorder.services.SSViewService
import com.nkd.screenrecorder.utils.StorageDetailsDialog
import com.nkd.screenrecorder.utils.LanguageDialog
import com.nkd.screenrecorder.utils.LocaleHelper
import com.nkd.screenrecorder.utils.NotificationPanel
import com.nkd.screenrecorder.utils.OverlayPermissionHelper
import com.nkd.screenrecorder.utils.PrefsHelper
import com.nkd.screenrecorder.utils.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {


    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var pendingScreenCaptureData: Intent? = null
    private var pendingResultCode: Int = -1
    private lateinit var preference: SharedPreferences
    private lateinit var projectionManager: MediaProjectionManager

    /** A facecam start was requested but is blocked on a permission round-trip (runtime
     *  CAMERA request, or a trip to the overlay/app-details Settings screen). Only used to
     *  debounce rapid re-taps of the facecam button while a request is in flight — each
     *  resumption path below re-checks the *real* permission state itself rather than
     *  trusting this flag, so it does not need to survive process death to stay correct. */
    private var isFacecamStartPending = false

    companion object {
        /** Toolbox → Facecam with CAMERA/overlay missing: run the facecam permission flow. */
        const val EXTRA_START_FACECAM = "extra_start_facecam"
        /** The user really denied CAMERA at least once (a dismissed dialog doesn't count). */
        private const val KEY_FACECAM_CAMERA_DENIED_ONCE = "facecam_camera_denied_once"
        const val KEY_DIRECTORY_URI = "directory_uri"
        private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            when (intent?.action) {
                RecordScreenService.ACTION_CLOSE_ACTIVITY -> {
                    finish()
                }

                RecordScreenService.ACTION_RECORDING_STATE -> {
                    val recording = intent.getBooleanExtra(RecordScreenService.EXTRA_STATE, false)
                    updateFabIcon(recording)
                }

                SSViewService.ACTION_SHOW_SCREENSHOT_BUTTON -> {
                    changeScreenshotBackground(true)
                }

                SSViewService.ACTION_HIDE_SCREENSHOT_BUTTON -> {
                    changeScreenshotBackground(false)
                }

                SSViewService.ACTION_HIDE_TOOLBOX -> {
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    changeFaceCameBackground(isRunning, binding.toolBoxImage)
                }

                CameraService.PREFS_TOOLS_CAMERA -> {
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    changeFaceCameBackground(isRunning, binding.faceCameIcon)
                }

                DrawingService.ACTION_HIDE_BRUSH -> {
                    changeFaceCameBackground(false, binding.drawingImage)
                }

                DrawingService.ACTION_SHOW_BRUSH -> {
                    changeFaceCameBackground(true, binding.drawingImage)
                }
            }
        }
    }

    /** Runtime CAMERA permission for Face Cam. On grant, resumes the pending start (which
     *  will then also check overlay permission before actually starting the service) —
     *  no second tap needed. On denial, distinguishes a regular "ask again later" denial
     *  from a permanent one (user also unchecked "ask again", or denied before) and routes
     *  the latter to the app's Settings page instead of silently failing. */
    private val cameraPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                preference.edit { remove(KEY_FACECAM_CAMERA_DENIED_ONCE) }
                startFacecamIfPermitted()
            } else {
                isFacecamStartPending = false
                changeFaceCameBackground(false, binding.faceCameIcon)
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    // A real deny (rationale only becomes true after one)
                    preference.edit { putBoolean(KEY_FACECAM_CAMERA_DENIED_ONCE, true) }
                    Toast.makeText(
                        this, getString(R.string.facecam_camera_permission_required), Toast.LENGTH_SHORT
                    ).show()
                } else if (preference.getBoolean(KEY_FACECAM_CAMERA_DENIED_ONCE, false)) {
                    // Denied before AND no rationale now → "don't ask again" / permanently denied
                    showFacecamCameraPermissionSettingsDialog()
                }
                // else: dialog dismissed without a choice — nothing to do, the next tap asks again
            }
        }

    /** Every trip to "Display over other apps" (startup, facecam, screenshot, toolbox, drawing):
     *  comes back by itself as soon as the switch is on, then continues the pending action. */
    private val overlayPermission = OverlayPermissionHelper(this)

    /** Returning from the app's own Settings page (routed here only after CAMERA was
     *  permanently denied) — resume the pending start if the user granted it there. */
    private val facecamAppSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startFacecamIfPermitted()
        } else {
            isFacecamStartPending = false
        }
    }

    /** Android 13+: notification permission for the "Notification toolbar" (asked once). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationPanelService.start(this)
        }

    /** Starts the always-on notification toolbar (Settings → "Notification toolbar", default ON). */
    private fun startNotificationToolbar() {
        if (!LocaleHelper.isLanguageSelected(this)) return
        if (!NotificationPanel.isEnabled(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preference.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
        ) {
            preference.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true) }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        NotificationPanelService.start(this)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // ✅ API 33+: POST_NOTIFICATIONS must never block recording — denying it only means the
        // foreground-service notification may not be shown; the recording itself still works.
        // Every other requested permission (RECORD_AUDIO, storage on API ≤32) is still required.
        val requiredGranted = permissions.entries.all { (permission, granted) ->
            granted || permission == Manifest.permission.POST_NOTIFICATIONS
        }
        if (requiredGranted) {
            checkDirectoryAndProceed()
        } else {
            Toast.makeText(this, getString(R.string.permission_required_recording), Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingScreenCaptureData = result.data
            pendingResultCode = result.resultCode
            // Directory already selected, start recording
            startRecordingWithSelectedDirectory()
        }
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable URI permission
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Save the directory URI
            saveDirectoryUri(it)
            Toast.makeText(this, getString(R.string.directory_selected), Toast.LENGTH_SHORT).show()

            // If we have pending screen capture data, start recording
            if (pendingScreenCaptureData != null) {
                startRecordingWithSelectedDirectory()
            } else {
                recreate()
            }
        } ?: run {
            Toast.makeText(this, getString(R.string.no_directory_selected), Toast.LENGTH_SHORT).show()
            pendingScreenCaptureData = null
            pendingResultCode = -1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preference = getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
        setSupportActionBar(binding.toolbar)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment

        navController = navHostFragment.navController

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val bottomNavInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, bottomNavInsets.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.fab) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val params = view.layoutParams as CoordinatorLayout.LayoutParams
            val originalBottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin_bottom)
            params.bottomMargin = originalBottomMargin + navBarInsets.bottom
            view.layoutParams = params
            insets
        }

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.video_item,
                R.id.image_item,
                R.id.gif_item,
                R.id.menu_edit
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.bottomNav.setupWithNavController(navController)
        binding.fab.setOnClickListener {
            if (isRecordingRunning()) {
                stopRecording()
            } else {
                checkPermissionsAndStart()
            }
        }
        updateFabIcon(isRecordingRunning())

        // ✅ Wired here — before the language-selection early return below — so these
        // listeners are attached on every onCreate() pass, including the very first one
        // (where isLanguageSelected() is still false and onCreate() returns early right
        // after this). They used to live after that return, which meant a first-time user
        // could never trigger facecam/screenshot/toolbox/drawing: the buttons were already
        // visible (inflated above) but had no click listener at all until the language
        // dialog was completed and recreate() ran a second onCreate() pass.
        binding.btnFacecam.setOnClickListener {
            if (CameraService.isRunning) {
                stopService(Intent(this, CameraService::class.java))
                changeFaceCameBackground(false, binding.faceCameIcon)
            } else if (!isFacecamStartPending) {
                startFacecamIfPermitted()
            }
            // else: a permission request/Settings round-trip is already in flight for this
            // button — ignore the extra tap instead of launching a second
            // ActivityResultLauncher.launch() on top of a pending one (unsafe per AndroidX's
            // own contract) or showing a duplicate overlay-permission dialog.
        }

        binding.btnScreenshot.setOnClickListener {
            // ✅ Screenshots are disabled while a recording is running
            if (RecordScreenService.isRecording) {
                Toast.makeText(this, getString(R.string.screenshot_unavailable_recording), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    return@setOnClickListener
                }
            }
            if (!SSViewService.isScreenshotButtonVisible) {
                withOverlayPermission(onDenied = { changeScreenshotBackground(false) }) {
                    // Show screenshot floating button
                    val intent = Intent(this, SSViewService::class.java).apply {
                        action = SSViewService.ACTION_SHOW_SCREENSHOT_BUTTON
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    changeScreenshotBackground(true)
                }
            } else {
                // Hide screenshot floating button
                val intent = Intent(this, SSViewService::class.java).apply {
                    action = SSViewService.ACTION_HIDE_SCREENSHOT_BUTTON
                }
                startService(intent)
                changeScreenshotBackground(false)
            }
        }

        binding.btnVideo.setOnClickListener {
            if (!SSViewService.isToolboxVisible) {
                withOverlayPermission(onDenied = { changeFaceCameBackground(false, binding.toolBoxImage) }) {
                    val intent = Intent(this, SSViewService::class.java).apply {
                        action = SSViewService.ACTION_SHOW_TOOLBOX
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    changeFaceCameBackground(true, binding.toolBoxImage)
                }
            } else {

                val intent = Intent(this, SSViewService::class.java).apply {
                    action = SSViewService.ACTION_HIDE_TOOLBOX
                }
                startService(intent)
                changeFaceCameBackground(false, binding.toolBoxImage)
            }
        }

        binding.btnDrawing.setOnClickListener {
            if (!DrawingService.isRunning) {
                withOverlayPermission(onDenied = { changeFaceCameBackground(false, binding.drawingImage) }) {
                    val intent = Intent(this, DrawingService::class.java)
                    intent.action = DrawingService.ACTION_SHOW_BRUSH
                    startService(intent)
                    changeFaceCameBackground(true, binding.drawingImage)
                }
            } else {
                val intent = Intent(this, DrawingService::class.java)
                intent.action = DrawingService.ACTION_HIDE_BRUSH
                startService(intent)
                changeFaceCameBackground(false, binding.drawingImage)
            }

        }

        if (!LocaleHelper.isLanguageSelected(this)) {
            LanguageDialog.show(this, cancelable = false) { recreate() }
            return
        }

        if (savedInstanceState == null && consumeStartFacecamExtra(intent)) {
            startFacecamIfPermitted()   // asks for overlay / CAMERA itself, then starts
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val alertBuilder = AlertDialog.Builder(this)
                .setTitle(getString(R.string.overlay_permission_title))
                .setMessage(getString(R.string.overlay_permission_message))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.grant)) { dialog, which ->
                    dialog.dismiss()
                    // Comes back by itself once granted (no restart); then the directory step
                    overlayPermission.request(
                        onGranted = { askDirectoryIfNeeded() },
                        onDenied = {
                            Toast.makeText(
                                this, getString(R.string.overlay_permission_message), Toast.LENGTH_LONG
                            ).show()
                            askDirectoryIfNeeded()
                        }
                    )
                }.setNegativeButton(getString(R.string.deny)) { dialog, which ->
                    dialog.dismiss()
                    askDirectoryIfNeeded()
                }
            alertBuilder.create().show()
        } else {
            askDirectoryIfNeeded()
        }
    }

    private fun changeFaceCameBackground(isRunning: Boolean, view: AppCompatImageView) {

        if (isRunning) {
            view.setColorFilter(ContextCompat.getColor(this, R.color.primaryColor))
        } else {
            view.setColorFilter(ContextCompat.getColor(this, R.color.icon_color))
        }
    }

    /**
     * Single, idempotent entry point for every facecam-start path (button tap, CAMERA grant,
     * returning from the overlay or app-details Settings screen). Always re-checks both
     * permissions itself — each can change independently of how this function was reached —
     * and only ever starts [CameraService] once both are actually held.
     *
     * Does NOT touch the facecam icon: [changeFaceCameBackground] for it is driven solely by
     * the [CameraService.PREFS_TOOLS_CAMERA] broadcast, which [CameraService] now only sends
     * with `is_running=true` once the camera has actually opened — not merely once this
     * function has asked the service to start.
     */
    private fun startFacecamIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            isFacecamStartPending = true
            showFacecamOverlayPermissionDialog()
            return
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            isFacecamStartPending = true
            cameraPermissionsLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        Log.d("camerarunning","value="+CameraService.isRunning);
        if (CameraService.isRunning) {
            isFacecamStartPending = false
            return
        }

        isFacecamStartPending = false
        val intent = Intent(this, CameraService::class.java).apply {
            putExtra(CameraService.EXTRA_FACING, CameraService.FACING_FRONT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Explains why Face Cam needs "draw over other apps" before routing to the Settings
     *  screen that grants it — only shown as a direct result of the user tapping the
     *  facecam button, never automatically. */
    private fun showFacecamOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.overlay_permission_title))
            .setMessage(getString(R.string.facecam_overlay_permission_required))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.grant)) { dialog, _ ->
                dialog.dismiss()
                overlayPermission.request(
                    onGranted = { startFacecamIfPermitted() },   // → CAMERA next, then starts
                    onDenied = {
                        isFacecamStartPending = false
                        changeFaceCameBackground(false, binding.faceCameIcon)
                        Toast.makeText(
                            this, getString(R.string.facecam_overlay_permission_required), Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            .setNegativeButton(getString(R.string.deny)) { dialog, _ ->
                dialog.dismiss()
                isFacecamStartPending = false
            }
            .show()
    }

    /** CAMERA was permanently denied (user also chose "don't ask again", or has denied it
     *  before) — the system won't show the request dialog again, so the only way forward
     *  is the app's own Settings page. */
    private fun showFacecamCameraPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.facecam_camera_permission_permanently_denied))
            .setPositiveButton(getString(R.string.settings)) { dialog, _ ->
                dialog.dismiss()
                facecamAppSettingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                )
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                isFacecamStartPending = false
            }
            .show()
    }

    private fun changeScreenshotBackground(isRunning: Boolean) {
        if (isRunning) {
            binding.screenshotIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.primaryColor)
            )
        } else {
            binding.screenshotIcon.setColorFilter(ContextCompat.getColor(this, R.color.icon_color))
        }
    }

    /**
     * Floating windows (screenshot button, toolbox, drawing) need "draw over other apps", which
     * can be revoked at any time. Granted → [action] now; otherwise the user is sent to Settings,
     * brought back automatically once it's on, and [action] then runs. Not granted on return →
     * the "permission needed" message and [onDenied] (switch back off).
     */
    private fun withOverlayPermission(onDenied: () -> Unit, action: () -> Unit) {
        overlayPermission.request(
            onGranted = action,
            onDenied = {
                Toast.makeText(this, getString(R.string.overlay_permission_message), Toast.LENGTH_LONG).show()
                onDenied()
            }
        )
    }

    /** Toolbox asked for the facecam (CAMERA/overlay missing): true once, extra removed. */
    private fun consumeStartFacecamExtra(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_START_FACECAM, false) != true) return false
        intent.removeExtra(EXTRA_START_FACECAM)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (consumeStartFacecamExtra(intent) && !isFacecamStartPending) {
            startFacecamIfPermitted()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.e("MainActivity", "permission " + permissionsToRequest.isEmpty())
        if (permissionsToRequest.isEmpty()) {
            checkDirectoryAndProceed()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun askDirectoryIfNeeded() {
        val uri = getSavedDirectoryUri()
        if (uri == null || !isUriValid(uri)) {
            showDirectorySelectionDialog()
        }
    }

    private fun checkDirectoryAndProceed() {
        val directoryUri = getSavedDirectoryUri()

        if (directoryUri == null || !isUriValid(directoryUri)) {
            // No directory selected or permission lost, ask user to select
            showDirectorySelectionDialog()
        } else {
            // Directory already selected, proceed with screen capture
            requestScreenCapture()
        }
    }

    private fun showDirectorySelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_directory_title))
            .setMessage(getString(R.string.select_directory_message))
            .setPositiveButton(getString(R.string.select_directory_button)) { dialog, _ ->
                directoryPickerLauncher.launch(null)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                pendingScreenCaptureData = null
                pendingResultCode = -1
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestScreenCapture() {
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startRecordingWithSelectedDirectory() {
        val directoryUri = getSavedDirectoryUri()

        if (directoryUri == null) {
            Toast.makeText(this, getString(R.string.no_directory_selected), Toast.LENGTH_SHORT).show()
            return
        }

        if (pendingScreenCaptureData == null || pendingResultCode != RESULT_OK) {
            Toast.makeText(this, getString(R.string.screen_capture_data_missing), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Create a new file in the selected directory
            val outputUri = createFileInDirectory(directoryUri)

            if (outputUri == null) {
                Toast.makeText(this, getString(R.string.failed_to_create_file), Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(this, RecordScreenService::class.java).apply {
                action = RecordScreenService.ACTION_START_RECORDING
                putExtra(RecordScreenService.EXTRA_RESULT_CODE, pendingResultCode)
                putExtra(RecordScreenService.EXTRA_DATA, pendingScreenCaptureData)
                putExtra(RecordScreenService.EXTRA_OUTPUT_URI, outputUri)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            updateFabIcon(isRecordingRunning())
            Toast.makeText(this, getString(R.string.recording_started), Toast.LENGTH_SHORT).show()

            // Clear pending data
            pendingScreenCaptureData = null
            pendingResultCode = -1
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting recording", e)
            Toast.makeText(this, getString(R.string.recording_start_error), Toast.LENGTH_SHORT)
                .show()
            pendingScreenCaptureData = null
            pendingResultCode = -1
        }
    }

    private fun createFileInDirectory(directoryUri: Uri): Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "ScreenRecord_$timestamp.mp4"

            val directory = DocumentFile.fromTreeUri(this, directoryUri)
            val file = directory?.createFile("video/mp4", fileName)

            file?.uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveDirectoryUri(uri: Uri) {
        preference.edit {
            putString(KEY_DIRECTORY_URI, uri.toString())
        }
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }

    private fun isUriValid(uri: Uri): Boolean {
        return try {
            // Check if we still have permission
            val persistedUris = contentResolver.persistedUriPermissions
            val hasPermission = persistedUris.any { it.uri == uri }

            if (!hasPermission) {
                return false
            }

            // Check if directory still exists
            val directory = DocumentFile.fromTreeUri(this, uri)
            directory?.exists() == true && directory.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordScreenService::class.java).apply {
            action = RecordScreenService.ACTION_STOP_RECORDING
        }
        startService(intent)

        updateFabIcon(isRecordingRunning())
        Toast.makeText(this, getString(R.string.recording_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun updateFabIcon(isRecording: Boolean) {
        if (isRecording) {
            binding.fab.setImageResource(R.drawable.ellipse_4)
        } else {
            binding.fab.setImageResource(R.drawable.ic_play)
        }
    }

    private fun showStorageDetailsDialog(total: Long, used: Long, free: Long) {
        StorageDetailsDialog.show(this, total, used, free)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                navController.navigate(R.id.settingFragment)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    fun hideTopCardView(visibility: Boolean) {
        binding.topCardView.isVisible = visibility
    }

    fun hideBottomNavAndFab(visibility: Boolean) {
        binding.bottomNav.isVisible = visibility
        binding.fab.isVisible = visibility
    }

    fun hideToolbar(visibility: Boolean) {
        binding.toolbar.isVisible = visibility
    }

    // Public method to change directory from settings
    fun changeOutputDirectory() {
        directoryPickerLauncher.launch(null)
    }

    // Public method to get current directory path for display in settings
    fun getCurrentDirectoryPath(): String? {
        val uri = getSavedDirectoryUri() ?: return null
        return try {
            val directory = DocumentFile.fromTreeUri(this, uri)
            directory?.name ?: uri.lastPathSegment
        } catch (e: Exception) {
            null
        }
    }

    private fun isRecordingRunning(): Boolean {
        return RecordScreenService.isRecording ||
                preference.getBoolean("recording_state", false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(RecordScreenService.ACTION_CLOSE_ACTIVITY)
            addAction(RecordScreenService.ACTION_RECORDING_STATE)
            addAction(SSViewService.ACTION_SHOW_SCREENSHOT_BUTTON)
            addAction(SSViewService.ACTION_HIDE_SCREENSHOT_BUTTON)
            addAction(CameraService.PREFS_TOOLS_CAMERA)
            addAction(SSViewService.ACTION_HIDE_TOOLBOX)
            addAction(DrawingService.ACTION_SHOW_BRUSH)
            addAction(DrawingService.ACTION_HIDE_BRUSH)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }

        // Update UI states
        changeFaceCameBackground(CameraService.isRunning, binding.faceCameIcon)
        changeFaceCameBackground(SSViewService.isToolboxVisible, binding.toolBoxImage)
        changeScreenshotBackground(SSViewService.isScreenshotButtonVisible)
        changeFaceCameBackground(DrawingService.isRunning, binding.drawingImage)
        updateFabIcon(isRecordingRunning())

        // ✅ Always-on notification toolbar (comes back after "Exit" on the next app open)
        startNotificationToolbar()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(closeReceiver)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        PrefsHelper.incrementCounter(this)
        return super.dispatchTouchEvent(ev)
    }}