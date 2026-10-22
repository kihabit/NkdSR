package com.nkd.screenrecorder.activities

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nkd.screenrecorder.R
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.content.Context
import com.nkd.screenrecorder.utils.LocaleHelper
import android.media.projection.MediaProjectionConfig
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.services.RecordScreenService
import com.nkd.screenrecorder.utils.AudioSourcePrefs
import com.nkd.screenrecorder.utils.CropCoordinates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectionPermissionActivity : BaseActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var requestType: String = "SCREENSHOT" // Default to screenshot
    private lateinit var preference: SharedPreferences
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // ✅ Root-cause fix for the reported NPE: validate resultCode/data (already done above)
        // AND the save-directory Uri before using either. getSavedDirectoryUri() can return
        // null here even for request types normally guarded in onCreate() (e.g. a race where
        // the user revokes the persisted folder permission while the system consent dialog is
        // showing) — the previous code unconditionally force-unwrapped it with `!!`, crashing
        // with NullPointerException as soon as that assumption didn't hold.
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d(TAG, "Permission granted for $requestType")
            when (requestType) {
                "RECORDING" -> {
                    val directoryUri = getSavedDirectoryUri()
                    val outputUri = directoryUri?.let { createFileInDirectory(it) }

                    if (directoryUri == null || outputUri == null) {
                        // ✅ Do not start screen capture with invalid/incomplete data — notify
                        // the service instead of calling it with a null output file.
                        Toast.makeText(this, getString(R.string.failed_to_create_file), Toast.LENGTH_SHORT)
                            .show()
                        notifyServicePermissionDenied()
                    } else {
                        // Send the permission result to the service based on request type
                        val serviceIntent = Intent(this, RecordScreenService::class.java).apply {
                            action = RecordScreenService.ACTION_START_RECORDING
                            putExtra(RecordScreenService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(RecordScreenService.EXTRA_DATA, result.data)
                            putExtra(RecordScreenService.EXTRA_OUTPUT_URI, outputUri)
                        }
                        startService(serviceIntent)
                    }
                }

                "GIF" -> {
                    val directoryUri = getSavedDirectoryUri()
                    val outputUri = directoryUri?.let { createFileInDirectory(it) }

                    if (directoryUri == null || outputUri == null) {
                        Toast.makeText(this, getString(R.string.failed_to_create_file), Toast.LENGTH_SHORT)
                            .show()
                        notifyServicePermissionDenied()
                    } else {
                        Log.e(TAG, "GIF started")
                        // Send the permission result to the service based on request type
                        val serviceIntent = Intent(this, RecordScreenService::class.java).apply {
                            action = RecordScreenService.ACTION_START_GIF
                            putExtra(RecordScreenService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(RecordScreenService.EXTRA_DATA, result.data)
                            putExtra(RecordScreenService.EXTRA_OUTPUT_URI, outputUri)
                            putExtra("width", intent.getIntExtra("width", 0))
                            putExtra("height", intent.getIntExtra("height", 0))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                putExtra(
                                    RecordScreenService.EXTRA_CROP_COORDINATES,
                                    intent.getParcelableExtra<CropCoordinates>(
                                        "coordinates",
                                        CropCoordinates::class.java
                                    )
                                )
                            } else {
                                putExtra(
                                    RecordScreenService.EXTRA_CROP_COORDINATES,
                                    intent.getParcelableExtra<CropCoordinates>("coordinates")
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                putExtra(
                                    RecordScreenService.EXTRA_CROP_RECT,
                                    intent.getParcelableExtra<Rect>("rect", Rect::class.java)
                                )
                            } else {
                                putExtra(
                                    RecordScreenService.EXTRA_CROP_RECT,
                                    intent.getParcelableExtra<Rect>("rect")
                                )
                            }
                        }
                        startService(serviceIntent)
                    }
                }

                else -> {
                    Log.e(TAG, "Screenshot started")
                    // Send the permission result to the service based on request type
                    val serviceIntent = Intent(this, RecordScreenService::class.java).apply {
                        action = RecordScreenService.ACTION_TAKE_SCREENSHOT
                        putExtra(RecordScreenService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(RecordScreenService.EXTRA_DATA, result.data)
                    }
                    startService(serviceIntent)
                }
            }

        } else {
            Log.d(TAG, "Permission denied or cancelled for $requestType")
            notifyServicePermissionDenied()
        }

        // Always finish the activity
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preference = getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
        // Get request type from intent (SCREENSHOT or RECORDING)
        requestType = intent.getStringExtra("REQUEST_TYPE") ?: "SCREENSHOT"

        // ✅ Screenshots aren't available while a recording is active — requesting a new
        // consent flow here would revoke recordingMediaProjection and kill the recording.
        // Hard early return: no createScreenCaptureIntent() call, no MediaProjection touched.
        if (requestType == "SCREENSHOT" && RecordScreenService.isRecording) {
            Toast.makeText(this, getString(R.string.screenshot_unavailable_recording), Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }

        // ✅ Record from the notification toolbar / bubble without the in-app setup done
        // (save folder or microphone permission missing): open the app to finish setup
        // instead of crashing on the missing save folder.
        if (requestType == "RECORDING" && !isRecordingSetupReady()) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            finish()
            return
        }

        // ✅ GIF needs the same save folder as RECORDING (createFileInDirectory() in the result
        // callback below requires it) — the crop overlay (GifCropService) never checks this
        // before launching here, so without this guard a user who never finished in-app setup
        // would trigger createScreenCaptureIntent()/the consent dialog for nothing, then crash
        // in the callback when getSavedDirectoryUri() turned out to be null.
        if (requestType == "GIF" && getSavedDirectoryUri() == null) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            finish()
            return
        }

        // ✅ Only request a fresh consent on the FIRST creation of this Activity instance.
        // This Activity can be recreated (e.g. the process is killed in the background while
        // the system's screen-capture consent dialog — a separate activity/process — is still
        // showing) while a request is already pending; registerForActivityResult() always
        // re-registers the launcher on recreation and the platform still delivers the pending
        // result to it, so calling launch() again here would fire a second consent dialog and
        // could lead to a duplicate RecordScreenService start.
        if (savedInstanceState == null) {
            // Request media projection permission (Android 14+: full screen only, no "single app")
            projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            requestPermissionLauncher.launch(captureIntent)
        }
    }

    /** Same checks as the in-app start: writable save folder + RECORD_AUDIO granted
     *  (RECORD_AUDIO only when the audio source is Microphone — Mute records video only). */
    private fun isRecordingSetupReady(): Boolean {
        val dir = getSavedDirectoryUri() ?: return false
        val hasFolder = contentResolver.persistedUriPermissions.any {
            it.uri == dir && it.isWritePermission
        }
        val hasMic = AudioSourcePrefs.source(this) == AudioSourcePrefs.Source.MUTE ||
                ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
        return hasFolder && hasMic
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
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

    /** Permission denied/cancelled, or granted but unusable (missing folder/output file) — tell
     *  the service, never start capture. Shared by the result callback and [onBackPressed]. */
    private fun notifyServicePermissionDenied() {
        val serviceIntent = Intent(this, RecordScreenService::class.java).apply {
            action = RecordScreenService.ACTION_SCREENSHOT_PERMISSION_DENIED
        }
        startService(serviceIntent)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // User pressed back - notify service
        notifyServicePermissionDenied()
    }

    companion object {
        private const val TAG = "ProjectionPermission"
    }
}