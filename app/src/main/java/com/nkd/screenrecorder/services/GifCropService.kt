package com.nkd.screenrecorder.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.widget.Toast
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.ProjectionPermissionActivity
import com.nkd.screenrecorder.databinding.LayoutGifCropServiceBinding
import com.nkd.screenrecorder.utils.LocaleHelper

class GifCropService : Service() {

    // Use the language chosen in the app for every text this service shows (overlays, toasts, notifications)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val TAG: String = GifCropService::class.java.simpleName

    private lateinit var windowManager: WindowManager
    private var params: WindowManager.LayoutParams? = null

    private var binding: LayoutGifCropServiceBinding? = null

    companion object {
        var isRunning = false
        const val ACTION_SHOW_GIF_CROP = "action_show_gif_crop"
        const val ACTION_HIDE_GIF_CROP = "action_hide_gif_crop"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_GIF_CROP -> {
                // ✅ Recording chal rahi ho to GIF nahi (naya screen-capture consent recording band kar deta)
                if (RecordScreenService.isRecording) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.gif_unavailable_recording),
                        Toast.LENGTH_SHORT
                    ).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                setUpTool()
            }

            ACTION_HIDE_GIF_CROP -> {
                hideGifCropService()
            }
        }
        return START_NOT_STICKY
    }

    private fun setUpTool() {
        // ✅ Pehle se khuli ho to dobara mat jodo (double tap crash fix)
        if (binding != null) return

        val b = LayoutGifCropServiceBinding.inflate(LayoutInflater.from(this), null, false)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // ✅ Android 7 (API 24–25) par TYPE_APPLICATION_OVERLAY nahi hota
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        try {
            windowManager.addView(b.root, p)
        } catch (e: Exception) {
            // Overlay permission nahi / window add nahi hui → crash ki jagah chupchaap band
            Log.e(TAG, "GIF crop overlay could not be shown", e)
            stopSelf()
            return
        }
        binding = b
        params = p
        isRunning = true

        b.cancelButton.setOnClickListener {
            hideGifCropService()
        }
        b.acceptButton.setOnClickListener {
            val cropCoordinates = b.cropOverLay.getCropCoordinates()
            val intent = Intent(this, ProjectionPermissionActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("coordinates", cropCoordinates)
            Log.d(TAG, "RECT ${b.cropOverLay.width} ${b.cropOverLay.height}")
            intent.putExtra("rect", b.cropOverLay.getCropArea())
            intent.putExtra("width", b.cropOverLay.width)
            intent.putExtra("height", b.cropOverLay.height)
            intent.putExtra("REQUEST_TYPE", "GIF")
            startActivity(intent)
            hideGifCropService()
        }
    }

    /** Kitni bhi baar call ho, safe hai. */
    fun hideGifCropService() {
        binding?.let {
            try {
                windowManager.removeView(it.root)
            } catch (e: Exception) {
                Log.w(TAG, "GIF crop overlay already removed: ${e.message}")
            }
        }
        binding = null
        params = null
        isRunning = false

        val intent = Intent(ACTION_HIDE_GIF_CROP).apply {
            `package` = packageName
        }
        sendBroadcast(intent)
        stopSelf()
    }

    override fun onDestroy() {
        // ✅ Service band ho to overlay kabhi peeche na rahe
        binding?.let {
            try {
                windowManager.removeView(it.root)
            } catch (_: Exception) {
            }
        }
        binding = null
        isRunning = false
        super.onDestroy()
    }
}