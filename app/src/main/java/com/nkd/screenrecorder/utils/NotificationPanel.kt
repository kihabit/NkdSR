package com.nkd.screenrecorder.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.ProjectionPermissionActivity
import com.nkd.screenrecorder.activities.ToolsLauncherActivity
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.services.RecordScreenService

/**
 * XRecorder-style notification toolbar (custom RemoteViews), shared by
 * NotificationPanelService (IDLE) and RecordScreenService (RECORDING).
 *
 * Every button points DIRECTLY at its target (activity / service PendingIntent) — Android 12+
 * blocks notification "trampolines" (starting an activity from a service/receiver after a tap).
 */
object NotificationPanel {

    const val CHANNEL_ID = "notification_toolbar_channel"
    const val PANEL_NOTIFICATION_ID = 1003
    private const val PANEL_COLOR = 0xFF1E1E1E.toInt()

    // Request codes — one per button so PendingIntents never overwrite each other
    private const val RC_CONTENT = 300
    private const val RC_RECORD = 301
    private const val RC_SCREENSHOT = 302
    private const val RC_TOOLS = 303
    private const val RC_HOME = 304
    private const val RC_EXIT = 305
    private const val RC_PAUSE = 306
    private const val RC_RESUME = 307
    private const val RC_STOP = 308

    /** Settings → "Notification toolbar" switch (default ON). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(SettingFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SettingFragment.NOTIFICATION_TOOLBAR, true)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Re-creating an existing channel only updates its name → follows the app language
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                LocaleHelper.wrap(context).getString(R.string.notif_toolbar_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** IDLE: [app icon below Android 12] | Record | Capture | Tools | Home | Exit */
    fun buildIdle(context: Context): Notification =
        build(
            context,
            collapsed = idleViews(context),
            expanded = idleViews(context),   // same layout, separate object (never reused)
            title = LocaleHelper.wrap(context).getString(R.string.notif_toolbar_title)
        )

    /** RECORDING: [app icon below 12] | Pause or Resume (both GONE for GIF capture) | Stop | Tools.
     *  No Screenshot button — screenshots are disabled while recording. */
    fun buildRecording(context: Context, paused: Boolean, allowPause: Boolean): Notification =
        build(
            context,
            collapsed = recordingViews(context, paused, allowPause),
            expanded = recordingViews(context, paused, allowPause),
            title = LocaleHelper.wrap(context).getString(if (paused) R.string.notif_paused else R.string.notif_recording)
        )

    // ── RemoteViews: FIXED XML cells — only click intents + visibility are set here.
    // (Dynamic RemoteViews.addView() duplicated the buttons on Android 9: when SystemUI
    //  re-applies an updated notification onto the existing view, the addView actions run
    //  again and append the cells a second time.) A NEW RemoteViews per build, never reused.
    private fun idleViews(context: Context) =
        RemoteViews(context.packageName, R.layout.notification_panel_idle).apply {
            setAppIconClick(context)
            // Labels are set here: XML @string in RemoteViews would be inflated by SystemUI in
            // the PHONE language, not the language chosen in the app.
            val loc = LocaleHelper.wrap(context)
            setLabel(loc, R.id.labelRecord, R.id.iconRecord, R.string.notif_record)
            setLabel(loc, R.id.labelCapture, R.id.iconCapture, R.string.notif_screenshot_short)
            setLabel(loc, R.id.labelTools, R.id.iconTools, R.string.notif_tools)
            setLabel(loc, R.id.labelHome, R.id.iconHome, R.string.notif_home)
            setLabel(loc, R.id.labelExit, R.id.iconExit, R.string.notif_exit)
            setOnClickPendingIntent(R.id.cellRecord, recordIntent(context))
            setOnClickPendingIntent(R.id.cellCapture, screenshotIntent(context))
            setOnClickPendingIntent(R.id.cellTools, toolsIntent(context))
            setOnClickPendingIntent(R.id.cellHome, homeIntent(context, RC_HOME))
            setOnClickPendingIntent(R.id.cellExit, exitIntent(context))
        }

    private fun recordingViews(context: Context, paused: Boolean, allowPause: Boolean) =
        RemoteViews(context.packageName, R.layout.notification_panel_recording).apply {
            setAppIconClick(context)
            val loc = LocaleHelper.wrap(context)
            setLabel(loc, R.id.labelPause, R.id.iconPause, R.string.notif_pause)
            setLabel(loc, R.id.labelResume, R.id.iconResume, R.string.notif_resume)
            setLabel(loc, R.id.labelStop, R.id.iconStop, R.string.notif_stop)
            setLabel(loc, R.id.labelTools, R.id.iconTools, R.string.notif_tools)
            setViewVisibility(R.id.cellPause, if (allowPause && !paused) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.cellResume, if (allowPause && paused) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(
                R.id.cellPause,
                recordServiceIntent(context, RecordScreenService.ACTION_PAUSE_RECORDING, RC_PAUSE)
            )
            setOnClickPendingIntent(
                R.id.cellResume,
                recordServiceIntent(context, RecordScreenService.ACTION_RESUME_RECORDING, RC_RESUME)
            )
            setOnClickPendingIntent(
                R.id.cellStop,
                recordServiceIntent(context, RecordScreenService.ACTION_STOP_RECORDING, RC_STOP)
            )
            setOnClickPendingIntent(R.id.cellTools, toolsIntent(context))
        }

    private fun RemoteViews.setLabel(loc: Context, labelId: Int, iconId: Int, textRes: Int) {
        val text = loc.getString(textRes)
        setTextViewText(labelId, text)
        setContentDescription(iconId, text)
    }

    /** Below Android 12 the row has its own small app icon (12+ shows the system's instead). */
    private fun RemoteViews.setAppIconClick(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            setOnClickPendingIntent(R.id.panelAppIcon, homeIntent(context, RC_CONTENT))
        }
    }

    private fun build(
        context: Context,
        collapsed: RemoteViews,
        expanded: RemoteViews,
        title: String
    ): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(title)                      // accessibility / system fallbacks
            .setContentIntent(homeIntent(context, RC_CONTENT))   // tap on empty area → app
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)   // same layout → nothing changes on expand
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Foreground-service notifications may be colorized → the system card stays dark
            // in both light and dark system themes (ignored by some OEM skins).
            .setColor(PANEL_COLOR)
            .setColorized(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Android 12+ always wraps custom views in the system template; use the decorated style
        // explicitly so the result is predictable. Below 12 the row fills the whole notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }
        return builder.build()
    }

    // ── PendingIntents (direct targets, no trampolines) ────────────────────
    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun homeIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            flags()
        )

    /** Same transparent consent activity the floating bubble uses. It opens MainActivity
     *  instead if the save folder / mic permission isn't set up yet. */
    private fun recordIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_RECORD,
            Intent(context, ProjectionPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("REQUEST_TYPE", "RECORDING"),
            flags()
        )

    private fun screenshotIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_SCREENSHOT,
            Intent(context, ProjectionPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("REQUEST_TYPE", "SCREENSHOT"),
            flags()
        )

    /** Tools → toolbox dialog. ACTIVITY PendingIntent (invisible ToolsLauncherActivity) so the
     *  notification shade collapses; it keeps the overlay-permission check (else opens the app). */
    private fun toolsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_TOOLS,
            ToolsLauncherActivity.intent(context, ToolsLauncherActivity.ACTION_TOOLS),
            flags()
        )

    /** Exit → same invisible activity (collapses the shade), which calls the existing exit action. */
    private fun exitIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_EXIT,
            ToolsLauncherActivity.intent(context, ToolsLauncherActivity.ACTION_EXIT),
            flags()
        )

    private fun recordServiceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, RecordScreenService::class.java).setAction(action),
            flags()
        )
}
