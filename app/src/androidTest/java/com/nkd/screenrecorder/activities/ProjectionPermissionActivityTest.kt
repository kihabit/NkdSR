package com.nkd.screenrecorder.activities

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the NullPointerException fixed in ProjectionPermissionActivity:
 * getSavedDirectoryUri()!! used to crash the result callback whenever no save folder had been
 * configured yet (reachable from the GIF crop overlay, which never checked for one). These
 * tests exercise the real onCreate() early-return guards with no save directory configured —
 * the activity must redirect to MainActivity and finish cleanly instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionPermissionActivityTest {

    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("ScreenRecorderPrefs", Context.MODE_PRIVATE)
    }

    @Before
    fun clearSavedDirectory() {
        // Simulate "setup never completed" — the exact precondition of the reported crash.
        prefs.edit().remove("directory_uri").commit()
    }

    @Test
    fun gifRequest_withNoSavedDirectory_doesNotCrash_andFinishes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
            putExtra("REQUEST_TYPE", "GIF")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // ActivityScenario.launch() itself throws if onCreate() crashes (e.g. the original
        // getSavedDirectoryUri()!! NPE) — that is the primary regression check here. The
        // activity's own onCreate() calls finish() immediately (redirect to MainActivity),
        // so by the time launch() returns it may already be DESTROYED — querying
        // scenario.state directly (not onActivity {}, which rejects a destroyed activity)
        // is what confirms a clean finish rather than a crash or a hang.
        val scenario = ActivityScenario.launch<ProjectionPermissionActivity>(intent)
        assertTrue(
            "Activity should finish itself (redirect to MainActivity) when no save " +
                    "directory is configured, not stay open or crash",
            scenario.state == Lifecycle.State.DESTROYED
        )
    }

    @Test
    fun recordingRequest_withNoSavedDirectory_doesNotCrash_andFinishes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
            putExtra("REQUEST_TYPE", "RECORDING")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val scenario = ActivityScenario.launch<ProjectionPermissionActivity>(intent)
        assertTrue(
            "Activity should finish itself (redirect to MainActivity) when recording " +
                    "setup isn't ready, not stay open or crash",
            scenario.state == Lifecycle.State.DESTROYED
        )
    }
}
