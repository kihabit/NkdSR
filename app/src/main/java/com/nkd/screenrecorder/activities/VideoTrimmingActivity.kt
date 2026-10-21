package com.nkd.screenrecorder.activities

import android.app.Dialog
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.databinding.ActivityVideoTrimmerBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.interfaces.VideoTrimListener
import com.nkd.screenrecorder.utils.formatTimeForFfmpeg
import java.io.File

class VideoTrimmingActivity : BaseActivity(), VideoTrimListener {

    companion object {
        /** Optional: open with these handles preselected (ms, original-video time). */
        const val EXTRA_INITIAL_START_MS = "INITIAL_START_MS"
        const val EXTRA_INITIAL_END_MS = "INITIAL_END_MS"
    }
    private lateinit var binding: ActivityVideoTrimmerBinding
    private lateinit var preference: SharedPreferences
    private lateinit var url: String
    private var isVideoToGIF: Boolean = false
    private var progressDialog: Dialog? = null
    private var progressBar: ProgressBar? = null
    private var tvPercent: TextView? = null
    private var tvStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoTrimmerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.constraint) { view, insets ->
            val bottomNavInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bottomNavInsets.top, 0, bottomNavInsets.bottom)
            insets
        }

        preference = getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)

        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    android.R.id.home -> {
                        finish()
                        true
                    }
                    else -> false
                }
            }
        }, this, Lifecycle.State.RESUMED)

        binding.apply {
            setSupportActionBar(binding.toolBar)
            supportActionBar?.setHomeButtonEnabled(true)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            isVideoToGIF = intent.getBooleanExtra("isVideoToGIF", false)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.setLogo(null)
            toolBar.navigationIcon?.setTint(android.graphics.Color.WHITE)

            videoTrimming.setOnTrimVideoListener(this@VideoTrimmingActivity)
            videoTrimming.setOnDoneClickListener { applyTrim() }
            url = intent.getStringExtra("Url")!!
            // Optional: open with the editor's current trim range preselected
            val initialStart = intent.getLongExtra(EXTRA_INITIAL_START_MS, -1L)
            val initialEnd = intent.getLongExtra(EXTRA_INITIAL_END_MS, -1L)
            if (initialStart >= 0L && initialEnd > initialStart) {
                videoTrimming.setInitialRange(initialStart, initialEnd)
            }
            videoTrimming.initVideoByURI(url.toUri())
        }
    }

    private fun applyTrim() {
        if (isVideoToGIF) {
            createGif(
                url,
                binding.videoTrimming.mLeftProgressPos,
                binding.videoTrimming.mRightProgressPos,
            )
        } else {
            val resultIntent = android.content.Intent()
            resultIntent.putExtra("TRIM_START_MS", binding.videoTrimming.mLeftProgressPos)
            resultIntent.putExtra("TRIM_END_MS", binding.videoTrimming.mRightProgressPos)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }

    private fun showProgressDialog() {
        progressDialog = Dialog(this)
        progressDialog!!.setContentView(R.layout.dialog_trim_progress)
        progressDialog!!.setCancelable(false)
        progressDialog!!.window?.setBackgroundDrawableResource(android.R.color.transparent)

        progressBar = progressDialog!!.findViewById(R.id.progress_bar)
        tvPercent = progressDialog!!.findViewById(R.id.tv_percent)
        tvStatus = progressDialog!!.findViewById(R.id.tv_status)

        progressBar?.progress = 0
        tvPercent?.text = getString(R.string.percent_value, 0)
        tvStatus?.text = if (isVideoToGIF) getString(R.string.converting_to_gif) else getString(R.string.trimming_video)

        progressDialog!!.show()
    }

    private fun dismissProgressDialog() {
        if (progressDialog?.isShowing == true) {
            progressDialog?.dismiss()
        }
        progressDialog = null
        progressBar = null
        tvPercent = null
        tvStatus = null
    }

    private fun updateProgress(percent: Int) {
        runOnUiThread {
            progressBar?.progress = percent
            tvPercent?.text = getString(R.string.percent_value, percent)
            if (percent >= 90) {
                tvStatus?.text = getString(R.string.almost_done)
            }
        }
    }

    fun createGif(
        inputFile: String,
        startMs: Long,
        endMs: Long,
    ) {
        val inputUri = inputFile.toUri()

        val inputSaf = FFmpegKitConfig.getSafParameterForRead(this, inputUri)
        val pictureGifFolder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "GIF"
        )
        val timestamp = System.currentTimeMillis()
        if (!pictureGifFolder.exists()) {
            val created = pictureGifFolder.mkdirs()
            Log.d("VideoTrimmingActivity", "Folder created: $created")
        }

        val outputFile = File(pictureGifFolder, "temp_$timestamp.gif")
        val outputPath = outputFile.absolutePath
        val startTime = formatTimeForFfmpeg(startMs)
        val durationMs = endMs - startMs
        val duration = formatTimeForFfmpeg(durationMs)

        showProgressDialog()
        var currentProgress = 0
        var isCompleted = false
        val handler = Handler(Looper.getMainLooper())

        val progressRunnable = object : Runnable {
            override fun run() {
                if (!isCompleted && currentProgress < 90) {
                    currentProgress++
                    updateProgress(currentProgress)
                    val delay = when {
                        currentProgress < 30 -> 40L
                        currentProgress < 60 -> 60L
                        currentProgress < 80 -> 100L
                        else -> 150L
                    }
                    handler.postDelayed(this, delay)
                }
            }
        }
        handler.post(progressRunnable)

        val filter = "[0:v] fps=10,scale=480:-1:flags=lanczos,split [a][b];" +
                "[a] palettegen [p];" +
                "[b][p] paletteuse"

        val command = "-ss $startTime -t $duration -i $inputSaf " +
                "-vf \"$filter\" " +
                "-loop 0 " +
                "-y $outputPath"

        FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode
            isCompleted = true
            handler.removeCallbacks(progressRunnable)

            runOnUiThread {
                dismissProgressDialog()
                val finishHandler = Handler(Looper.getMainLooper())
                val finishRunnable = object : Runnable {
                    override fun run() {
                        if (currentProgress < 100) {
                            currentProgress++
                            updateProgress(currentProgress)
                            finishHandler.postDelayed(this, 20L)
                        } else {
                            dismissProgressDialog()
                            if (ReturnCode.isSuccess(returnCode)) {
                                MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(outputPath),
                                    arrayOf("image/gif"),
                                    null
                                )
                                Toast.makeText(
                                    this@VideoTrimmingActivity,
                                    getString(R.string.gif_created),
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            } else {
                                Toast.makeText(
                                    this@VideoTrimmingActivity,
                                    getString(R.string.gif_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                finishHandler.post(finishRunnable)
            }
        }
    }

    fun trimVideo(
        inputFile: String,
        outputFileName: String,
        startMs: Long,
        endMs: Long,
        outputFolderUriString: String
    ) {
        Log.e("TrimmingActivity", " $inputFile")
        val inputUri = inputFile.toUri()
        val outputFolderUri = outputFolderUriString.toUri()

        val documentFile =
            DocumentFile.fromTreeUri(this, outputFolderUri)
                ?.createFile("video/mp4", outputFileName)

        if (documentFile == null) {
            Toast.makeText(this, getString(R.string.failed_to_create_file), Toast.LENGTH_SHORT).show()
            return
        }

        val inputSaf = FFmpegKitConfig.getSafParameterForRead(this, inputUri)
        val outputSaf = FFmpegKitConfig.getSafParameterForWrite(this, documentFile.uri)

        val startTime = formatTimeForFfmpeg(startMs)
        val durationMs = endMs - startMs
        val duration = formatTimeForFfmpeg(durationMs)

        showProgressDialog()

        var currentProgress = 0
        var isCompleted = false
        val handler = Handler(Looper.getMainLooper())

        val progressRunnable = object : Runnable {
            override fun run() {
                if (!isCompleted && currentProgress < 90) {
                    currentProgress++
                    updateProgress(currentProgress)
                    val delay = when {
                        currentProgress < 30 -> 40L
                        currentProgress < 60 -> 60L
                        currentProgress < 80 -> 100L
                        else -> 150L
                    }
                    handler.postDelayed(this, delay)
                }
            }
        }
        handler.post(progressRunnable)

        val command = "-ss $startTime -i $inputSaf -t $duration -c copy $outputSaf"

        FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode
            isCompleted = true
            handler.removeCallbacks(progressRunnable)

            runOnUiThread {
                val finishHandler = Handler(Looper.getMainLooper())
                val finishRunnable = object : Runnable {
                    override fun run() {
                        if (currentProgress < 100) {
                            currentProgress++
                            updateProgress(currentProgress)
                            finishHandler.postDelayed(this, 20L)
                        } else {
                            dismissProgressDialog()
                            FFmpegKitConfig.enableStatisticsCallback(null)
                            if (ReturnCode.isSuccess(returnCode)) {
                                Toast.makeText(
                                    this@VideoTrimmingActivity,
                                    getString(R.string.trim_successful),
                                    Toast.LENGTH_SHORT
                                ).show()
                                val resultIntent = android.content.Intent()
                                resultIntent.putExtra("TRIMMED_VIDEO_URI", documentFile.uri.toString())
                                setResult(RESULT_OK, resultIntent)
                                finish()
                            } else {
                                Toast.makeText(
                                    this@VideoTrimmingActivity,
                                    getString(R.string.trim_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                finishHandler.post(finishRunnable)
            }
        }
    }

    override fun onStartTrim() {}

    override fun onFinishTrim(url: String) {}

    override fun onCancel() {}

    override fun onPause() {
        super.onPause()
        binding.videoTrimming.onVideoPause()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
        FFmpegKitConfig.enableStatisticsCallback(null)
        binding.videoTrimming.onDestroy()
    }
}