package com.nkd.screenrecorder.activities

import com.nkd.screenrecorder.R

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.databinding.ActivityScreenshotPreviewBinding

class ScreenshotPreviewActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenshotPreviewBinding
    private val dismissHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    companion object {
        const val EXTRA_SCREENSHOT_URI = "screenshot_uri"
        const val AUTO_DISMISS_DELAY = 3000L // 3 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity transparent and show over lockscreen if needed
        window.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            setFlags(
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            )
        }

        binding = ActivityScreenshotPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val screenshotUri = intent.getParcelableExtra<Uri>(EXTRA_SCREENSHOT_URI)

        if (screenshotUri != null) {
            setupPreview(screenshotUri)
            startEntryAnimation()
            scheduleAutoDismiss()
        } else {
            finish()
        }
    }

    private fun setupPreview(uri: Uri) {
        // Load screenshot into preview
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(binding.previewImage)

        // Setup click listeners
        binding.previewContainer.setOnClickListener {
            cancelAutoDismiss()
            openScreenshot(uri)
        }

        binding.shareButton.setOnClickListener {
            cancelAutoDismiss()
            shareScreenshot(uri)
        }

        binding.deleteButton.setOnClickListener {
            cancelAutoDismiss()
            deleteScreenshot(uri)
        }
    }

    private fun startEntryAnimation() {
        // Initial state: scaled down and translated off-screen to the right
        binding.previewContainer.apply {
            scaleX = 0.3f
            scaleY = 0.3f
            translationX = width.toFloat()
            alpha = 0f
        }

        // Animate entry: slide in from right with bounce
        val translationX =
            ObjectAnimator.ofFloat(binding.previewContainer, "translationX", 0f).apply {
                duration = 400
                interpolator = OvershootInterpolator(1.2f)
            }

        val scaleX = ObjectAnimator.ofFloat(binding.previewContainer, "scaleX", 1f).apply {
            duration = 300
        }

        val scaleY = ObjectAnimator.ofFloat(binding.previewContainer, "scaleY", 1f).apply {
            duration = 300
        }

        val alpha = ObjectAnimator.ofFloat(binding.previewContainer, "alpha", 1f).apply {
            duration = 200
        }

        AnimatorSet().apply {
            playTogether(translationX, scaleX, scaleY, alpha)
            start()
        }

        // Animate buttons sliding in
        binding.actionButtons.apply {
            translationY = -50f
        }

        ObjectAnimator.ofFloat(binding.actionButtons, "alpha", 1f).apply {
            duration = 300
            startDelay = 200
            start()
        }

        ObjectAnimator.ofFloat(binding.actionButtons, "translationY", 0f).apply {
            duration = 300
            startDelay = 200
            interpolator = OvershootInterpolator(1.0f)
            start()
        }
    }

    private fun dismissWithAnimation() {
        // Animate exit: slide out to right and scale down
        val translationX = ObjectAnimator.ofFloat(
            binding.previewContainer,
            "translationX",
            binding.previewContainer.width.toFloat()
        ).apply {
            duration = 300
        }

        val scaleX = ObjectAnimator.ofFloat(binding.previewContainer, "scaleX", 0.3f).apply {
            duration = 300
        }

        val scaleY = ObjectAnimator.ofFloat(binding.previewContainer, "scaleY", 0.3f).apply {
            duration = 300
        }

        val alpha = ObjectAnimator.ofFloat(binding.previewContainer, "alpha", 0f).apply {
            duration = 300
        }

        AnimatorSet().apply {
            playTogether(translationX, scaleX, scaleY, alpha)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }
            })
            start()
        }
    }

    private fun scheduleAutoDismiss() {
        autoDismissRunnable = Runnable {
            dismissWithAnimation()
        }
        dismissHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_DELAY)
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let {
            dismissHandler.removeCallbacks(it)
        }
    }

    private fun openScreenshot(uri: Uri) {
        // TODO: Navigate to your image viewer/gallery
        // For now, open with system viewer
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    private fun shareScreenshot(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_screenshot)))
        finish()
    }

    private fun deleteScreenshot(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            android.widget.Toast.makeText(
                this,
                getString(R.string.screenshot_deleted),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.delete_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoDismiss()
    }

    override fun onBackPressed() {
        cancelAutoDismiss()
        dismissWithAnimation()
    }
}