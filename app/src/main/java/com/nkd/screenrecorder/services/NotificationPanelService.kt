package com.nkd.screenrecorder.services

import com.nkd.screenrecorder.utils.LocaleHelper
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nkd.screenrecorder.utils.NotificationPanel

/**
 * Always-on "Notification toolbar" (IDLE panel: Record / Screenshot / Tools / Home / Exit).
 *
 * specialUse foreground service. While a recording runs, RecordScreenService's own
 * foreground notification is the RECORDING panel, so this service removes its notification
 * (but keeps running) and shows the IDLE panel again when the recording stops.
 */
class NotificationPanelService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val TAG = "NotificationPanelService"
        const val ACTION_EXIT = "notification_panel_exit"
        private const val EXTRA_FOREGROUND = "extra_foreground"

        var isRunning = false
            private set

        /** Starts the panel if the Settings switch is ON. */
        fun start(context: Context) {
            if (!NotificationPanel.isEnabled(context)) return
            try {
                if (RecordScreenService.isRecording) {
                    // Recording notification is already visible — no IDLE panel needed now.
                    // Plain start (caller is in the foreground), promoted when recording stops.
                    if (!isRunning) context.startService(Intent(context, NotificationPanelService::class.java))
                } else {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, NotificationPanelService::class.java)
                            .putExtra(EXTRA_FOREGROUND, true)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start notification toolbar: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationPanelService::class.java))
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordScreenService.ACTION_RECORDING_STATE) return
            val recording = intent.getBooleanExtra(RecordScreenService.EXTRA_STATE, false)
            if (recording) {
                // Only ONE panel in the shade: RecordScreenService shows the RECORDING panel.
                ServiceCompat.stopForeground(this@NotificationPanelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                NotificationManagerCompat.from(this@NotificationPanelService)
                    .cancel(NotificationPanel.PANEL_NOTIFICATION_ID)
            } else if (NotificationPanel.isEnabled(this@NotificationPanelService)) {
                promote()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ContextCompat.registerReceiver(
            this, recordingStateReceiver,
            IntentFilter(RecordScreenService.ACTION_RECORDING_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exitAll()
            return START_NOT_STICKY
        }
        val startedAsForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        when {
            !NotificationPanel.isEnabled(this) -> {
                // startForegroundService() still requires startForeground() before stopping.
                if (startedAsForeground) promote()
                stopSelfAndRemove()
            }
            RecordScreenService.isRecording -> {
                if (startedAsForeground) {
                    promote()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
            else -> promote()
        }
        return START_NOT_STICKY
    }

    /** Shows the IDLE panel as this service's foreground notification. */
    private fun promote() {
        val notification = NotificationPanel.buildIdle(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationPanel.PANEL_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationPanel.PANEL_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+: a foreground start from the background can be refused (e.g. the
            // system itself ended the recording). Fall back to a normal ongoing notification.
            Log.w(TAG, "startForeground refused, posting as normal notification: ${e.message}")
            try {
                NotificationManagerCompat.from(this)
                    .notify(NotificationPanel.PANEL_NOTIFICATION_ID, notification)
            } catch (se: SecurityException) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted: ${se.message}")
            }
        }
    }

    /** Exit: stop the overlays/tools services, remove the panel and stop. */
    private fun exitAll() {
        // Brush/drawing: use its own hide action — it removes the overlay AND resets
        // DrawingService.isRunning (its onDestroy doesn't), so the app's toggle stays correct.
        if (DrawingService.isRunning) {
            try {
                startService(Intent(this, DrawingService::class.java).setAction(DrawingService.ACTION_HIDE_BRUSH))
            } catch (e: Exception) {
                Log.w(TAG, "Hide brush failed: ${e.message}")
            }
        }
        listOf(
            SSViewService::class.java,      // floating bubble + screenshot button
            CameraService::class.java,      // facecam
            GifCropService::class.java      // GIF crop area
        ).forEach { cls ->
            try {
                stopService(Intent(this, cls))
            } catch (e: Exception) {
                Log.w(TAG, "stopService ${cls.simpleName} failed: ${e.message}")
            }
        }
        stopSelfAndRemove()
    }

    private fun stopSelfAndRemove() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NotificationPanel.PANEL_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(recordingStateReceiver)
        } catch (_: Exception) {
        }
        NotificationManagerCompat.from(this).cancel(NotificationPanel.PANEL_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
