package com.nkd.screenrecorder.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nkd.screenrecorder.services.NotificationPanelService
import com.nkd.screenrecorder.services.SSViewService

/**
 * Invisible trampoline for the notification toolbar's Tools / Exit buttons.
 *
 * A service PendingIntent doesn't collapse the notification shade (and
 * ACTION_CLOSE_SYSTEM_DIALOGS is blocked on Android 12+), so the toolbox dialog would open
 * underneath the shade. Launching an ACTIVITY from the notification collapses the shade
 * automatically — this activity just forwards the action and finishes immediately.
 */
class ToolsLauncherActivity : Activity() {

    companion object {
        private const val TAG = "ToolsLauncherActivity"
        private const val EXTRA_ACTION = "extra_panel_action"
        const val ACTION_TOOLS = "tools"
        const val ACTION_EXIT = "exit"

        fun intent(context: Context, action: String): Intent =
            Intent(context, ToolsLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ACTION, action)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent?.getStringExtra(EXTRA_ACTION)) {
                ACTION_TOOLS -> openTools()
                ACTION_EXIT -> startService(
                    Intent(this, NotificationPanelService::class.java)
                        .setAction(NotificationPanelService.ACTION_EXIT)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification panel action failed", e)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Toolbox dialog is an overlay — needs "draw over other apps"; otherwise open the app. */
    private fun openTools() {
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SSViewService::class.java).setAction(SSViewService.ACTION_OPEN_TOOLS)
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }
}
