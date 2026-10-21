package com.nkd.screenrecorder.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nkd.screenrecorder.R

/**
 * The ONE way the app sends the user to "Display over other apps".
 *
 * [request] shows a short hint, opens the overlay Settings screen and watches for the grant
 * (AppOpsManager.startWatchingMode + a 300ms canDrawOverlays fallback). As soon as the switch
 * is turned on, the watcher stops and [activity] is brought back to the front (CLEAR_TOP |
 * SINGLE_TOP: the same instance gets onNewIntent, it is NOT recreated). The pending action is
 * then resolved in the activity's next onResume — whether it came back by itself or was
 * brought back: granted → onGranted, otherwise → onDenied.
 *
 * Watching stops on grant, on onResume, after [WATCH_TIMEOUT_MS] and in onDestroy.
 */
class OverlayPermissionHelper(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "OverlayPermission"
        private const val POLL_INTERVAL_MS = 300L
        private const val WATCH_TIMEOUT_MS = 2 * 60 * 1000L

        fun isGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            if (Settings.canDrawOverlays(context)) return true
            // canDrawOverlays can lag behind the switch for a moment (Android 8) — ask AppOps too
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val appOps by lazy { activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }
    private var opListener: AppOpsManager.OnOpChangedListener? = null
    private var watching = false

    private var onGranted: (() -> Unit)? = null
    private var onDenied: (() -> Unit)? = null
    /** Set once the activity was paused after opening Settings, so its next onResume is a return. */
    private var leftForSettings = false

    init {
        activity.lifecycle.addObserver(this)
    }

    /** Granted → [onGranted] right away. Otherwise opens Settings; one of the two runs on return. */
    fun request(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (isGranted(activity)) {
            onGranted()
            return
        }
        Toast.makeText(activity, R.string.overlay_permission_hint, Toast.LENGTH_LONG).show()
        if (!openSettings()) {
            onDenied()
            return
        }
        this.onGranted = onGranted
        this.onDenied = onDenied
        leftForSettings = false
        startWatching()
    }

    private fun openSettings(): Boolean {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri()
        )
        return try {
            activity.startActivity(withPackage)
            true
        } catch (_: Exception) {
            try {   // some OEMs reject the package Uri
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open the overlay permission screen", e)
                false
            }
        }
    }

    // ---- watching while the user is in Settings

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!watching) return
            if (isGranted(activity)) onGrantDetected() else handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val timeoutRunnable = Runnable { stopWatching() }

    private fun startWatching() {
        stopWatching()
        watching = true
        try {
            val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
                // May arrive on a binder thread — check on the main thread
                if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == activity.packageName) {
                    handler.post { if (watching && isGranted(activity)) onGrantDetected() }
                }
            }
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, activity.packageName, listener)
            opListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "AppOps watch unavailable, polling only", e)
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        handler.postDelayed(timeoutRunnable, WATCH_TIMEOUT_MS)
    }

    private fun stopWatching() {
        watching = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(timeoutRunnable)
        opListener?.let { listener ->
            try {
                appOps.stopWatchingMode(listener)
            } catch (_: Exception) {
            }
        }
        opListener = null
    }

    /** Switch turned on: stop watching and bring the existing activity instance back. */
    private fun onGrantDetected() {
        stopWatching()
        try {
            activity.startActivity(
                Intent(activity, activity.javaClass).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot bring the app back", e)   // the user can still press Back
        }
    }

    // ---- lifecycle

    override fun onPause(owner: LifecycleOwner) {
        if (onGranted != null) leftForSettings = true
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!leftForSettings) return
        stopWatching()
        val granted = onGranted
        val denied = onDenied
        onGranted = null
        onDenied = null
        leftForSettings = false
        if (isGranted(activity)) granted?.invoke() else denied?.invoke()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopWatching()
        onGranted = null
        onDenied = null
        owner.lifecycle.removeObserver(this)
    }
}
