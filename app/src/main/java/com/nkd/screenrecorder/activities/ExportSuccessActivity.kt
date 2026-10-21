package com.nkd.screenrecorder.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.databinding.ActivityExportSuccessBinding

/**
 * Shown after a successful final export/save in EditVideoActivity — mirrors
 * InShot's post-export "Saved" screen (thumbnail preview + a 4-icon share row).
 */
class ExportSuccessActivity : BaseActivity() {

    private lateinit var binding: ActivityExportSuccessBinding
    private var videoUri: Uri? = null

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Draw edge-to-edge and pad the root by the status bar / display cutout inset
        // ourselves, so the top bar (back arrow, "Saved" label, home icon) never sits
        // flush against or under the status bar/notch on any device.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, systemBars.bottom)
            insets
        }

        videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VIDEO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VIDEO_URI)
        }

        setupTopBar()
        setupThumbnail()
        setupShareRow()
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnHome.setOnClickListener { goHome() }
    }

    private fun goHome() {
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
    }

    private fun setupThumbnail() {
        val uri = videoUri ?: return

        Glide.with(this)
            .asBitmap()
            .load(uri)
            .centerCrop()
            .into(binding.previewThumbnail)

        binding.previewCard.setOnClickListener {
            val playerIntent = Intent(this, VideoPlayerActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(playerIntent)
        }
    }

    private fun setupShareRow() {
        binding.btnShareOther.setOnClickListener { shareGeneric() }
        binding.btnShareWhatsapp.setOnClickListener { shareToPackage("com.whatsapp") }
        binding.btnShareEmail.setOnClickListener { shareViaEmail() }
        binding.btnShareInstagram.setOnClickListener { shareToPackage("com.instagram.android") }
    }

    private fun shareGeneric() {
        val uri = videoUri ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_label)))
    }

    private fun shareToPackage(packageName: String) {
        val uri = videoUri ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageName)
        }
        try {
            startActivity(shareIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.app_not_installed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareViaEmail() {
        val uri = videoUri ?: return
        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(emailIntent, getString(R.string.share_label)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.app_not_installed), Toast.LENGTH_SHORT).show()
        }
    }
}
