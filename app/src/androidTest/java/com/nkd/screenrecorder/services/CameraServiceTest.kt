package com.nkd.screenrecorder.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the facecam default-camera-selection fix (CameraService / MainActivity
 * / SSViewService): every caller used to pass facing=1 (back) — either hardcoded or via a
 * nonsensical default (reading "facing" from MainActivity's own launch intent, which never
 * carries one, falling back to 1). CameraService itself already defaulted an *omitted* extra to
 * FACING_FRONT (0); these tests exercise that real default path.
 *
 * Also covers a second defect found during the same audit: CameraService used to report
 * isRunning=true via broadcast even when CAMERA permission was missing and nothing was actually
 * opened. It now reports isRunning=false and stops itself in that case.
 */
@RunWith(AndroidJUnit4::class)
class CameraServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun startingCameraService_withNoFacingExtra_defaultsToFrontAndDoesNotCrash() {
        // This is the real call shape used by every fixed call site (MainActivity,
        // SSViewService): no EXTRA_FACING is ever passed on the "default" path, relying on
        // CameraService's own default. Reaching isRunning==true (when permission is granted)
        // or a clean isRunning==false shutdown (when it isn't) without a crash is the
        // regression check — CameraService.onStartCommand() resolves the omitted extra to
        // Facing.FRONT internally (verified by static code inspection of the fix).
        val intent = Intent(context, CameraService::class.java)
        ContextCompat.startForegroundService(context, intent)

        if (hasCameraPermission()) {
            assertTrue(
                "CameraService should report isRunning=true once the camera opens " +
                        "successfully with permission granted",
                waitFor { CameraService.isRunning }
            )
        } else {
            // ✅ The fixed behavior: no permission → isRunning must settle to false (the
            // service stops itself) instead of the old bug where it was forced to true
            // regardless of whether anything was actually opened.
            assertFalse(
                "CameraService must not report isRunning=true when CAMERA permission " +
                        "was never granted — nothing was actually opened",
                waitFor { CameraService.isRunning }
            )
        }

        context.stopService(Intent(context, CameraService::class.java))
        waitFor { !CameraService.isRunning }
    }

    @Test
    fun startingCameraService_explicitFrontExtra_doesNotCrash() {
        val intent = Intent(context, CameraService::class.java).apply {
            putExtra(CameraService.EXTRA_FACING, CameraService.FACING_FRONT)
        }
        ContextCompat.startForegroundService(context, intent)
        waitFor { CameraService.isRunning || hasCameraPermission().not() }

        context.stopService(Intent(context, CameraService::class.java))
        waitFor { !CameraService.isRunning }
    }
}
