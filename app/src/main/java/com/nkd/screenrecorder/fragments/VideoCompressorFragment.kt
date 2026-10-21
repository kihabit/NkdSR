package com.nkd.screenrecorder.fragments

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.navArgs
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.adapter.QualityAdapter
import com.nkd.screenrecorder.adapter.ResolutionAdapter
import com.nkd.screenrecorder.databinding.FragmentVideoCompressorBinding
import com.nkd.screenrecorder.utils.convertToDurationForVideo
import java.io.File

data class Resolution(
    val label: String,
    val width: Int,
    val height: Int,
    val isEnabled: Boolean
)

enum class VideoQuality(val label: String, val multiplier: Double) {
    LOW("Low", 0.7),
    MEDIUM("Medium", 1.0),
    HIGH("High", 1.3)
}

class VideoCompressorFragment : Fragment(), MenuProvider {

    private lateinit var binding: FragmentVideoCompressorBinding
    private val navArgs: VideoCompressorFragmentArgs by navArgs()
    private lateinit var handler: Handler
    private lateinit var updateTimeTask: Runnable
    private var videoNativeWidth: Int = 0
    private var videoNativeHeight: Int = 0
    private var selectedResolution: Resolution? = null
    private var progressDialog: AlertDialog? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private var selectedQuality: VideoQuality = VideoQuality.MEDIUM
    private lateinit var preference: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentVideoCompressorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        preference = requireContext().getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
        binding.apply {
            videoView.setVideoURI(navArgs.videoUrl.toUri())
            videoView.setOnPreparedListener { mp ->
                videoNativeWidth = mp.videoWidth
                videoNativeHeight = mp.videoHeight

                handler = Handler(Looper.getMainLooper())
                updateProgressBar(videoView, seekBar)
                progressBarPrepare(mp)

                val videoWidth = mp.videoWidth
                val videoHeight = mp.videoHeight

                val layoutParams = binding.videoView.layoutParams

                val parentWidth = binding.videoView.width
                val parentHeight = binding.videoView.height

                val videoRatio = videoWidth.toFloat() / videoHeight
                val viewRatio = parentWidth.toFloat() / parentHeight

                if (videoRatio > viewRatio) {
                    layoutParams.width = parentWidth
                    layoutParams.height = (parentWidth / videoRatio).toInt()
                } else {
                    layoutParams.height = parentHeight
                    layoutParams.width = (parentHeight * videoRatio).toInt()
                }

                binding.videoView.layoutParams = layoutParams
                binding.thumbnail.layoutParams.width = binding.videoView.layoutParams.width
                binding.thumbnail.layoutParams.height = binding.videoView.layoutParams.height
                binding.thumbnail.requestLayout()
            }


            Glide.with(requireContext())
                .asBitmap()
                .load(navArgs.videoUrl.toUri())
                .frame(1000000)
                .into(thumbnail)

            videoView.setOnCompletionListener {
                playStopButton.setImageDrawable(
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_play, null)
                )
            }
            playStopButton.setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_play, null)
                    )
                } else {
                    videoView.start()
                    thumbnail.isGone = true
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_stop, null)
                    )
                }
            }

            val (originalW, originalH) = getVideoResolution(
                requireContext(),
                navArgs.videoUrl.toUri()
            )
            Log.e("VideoCompression", "Width ${originalW} Height $originalH");
            val allResolutions = listOf(
                Resolution("1080p", 1080, 1920, true),
                Resolution("720p", 720, 1280, true),
                Resolution("480p", 480, 854, true),
                Resolution("360p", 360, 640, true),
                Resolution("240p", 240, 426, true),
            ).map {
                it.copy(isEnabled = it.width <= originalW && it.height <= originalH)
            }
            selectedResolution = allResolutions.firstOrNull { it.width == originalW }
            resolution.text = getString(R.string.original_resolution, selectedResolution?.width ?: 0)
            val duration = getVideoDuration(requireContext(), navArgs.videoUrl.toUri())
            val adapter = ResolutionAdapter(
                requireContext(),
                allResolutions,
                if (selectedResolution != null) allResolutions.indexOf(selectedResolution) else allResolutions.size - 1,
                duration
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            resolutionSpinner.adapter = adapter
            binding.resolutionSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {

                    override fun onItemSelected(
                        parent: AdapterView<*>,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val item = allResolutions[position]

                        if (!item.isEnabled) {
                            binding.resolutionSpinner.setSelection(adapter.selectedPosition)
                            return
                        }

                        adapter.updateSelection(position)
                        selectedResolution = item
                        val bitrate = getBitrateForResolution(item.height)
                        val sizeMb = calculateSizeMB(bitrate, duration)

                        binding.videoSizeTextView.text = getString(R.string.estimated_size).format(sizeMb)

                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }

            val qualityList = listOf(
                VideoQuality.LOW,
                VideoQuality.MEDIUM,
                VideoQuality.HIGH
            )

            val qualityAdapter = QualityAdapter(requireContext(), qualityList)
            qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            outputQualitySpinner.adapter = qualityAdapter
            outputQualitySpinner.setSelection(1) // MEDIUM
            outputQualitySpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {

                    override fun onItemSelected(
                        parent: AdapterView<*>,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedQuality = qualityList[position]
                        qualityAdapter.updateSelection(position)
                        // 👉 Update size again based on quality
                        val resolution = selectedResolution ?: return
                        val bitrate = getBitrateForResolution(resolution.height)

                        val adjustedBitrate =
                            (bitrate * selectedQuality.multiplier).toInt()

                        val duration = getVideoDuration(
                            requireContext(),
                            navArgs.videoUrl.toUri()
                        )

                        val sizeMb = calculateSizeMB(adjustedBitrate, duration)

                        binding.videoSizeTextView.text = getString(R.string.estimated_size).format(sizeMb)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
        }
    }

    fun progressBarPrepare(mediaPlayer: MediaPlayer) {
        binding.seekBar.max = mediaPlayer.duration
        binding.timerDuration.text = convertToDurationForVideo(0, mediaPlayer.duration)
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.videoView.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateTimeTask)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                binding.videoView.seekTo(seekBar?.progress ?: 0)
                updateProgressBar(binding.videoView, seekBar!!)
            }
        })
    }

    private fun updateProgressBar(videoView: VideoView, seekBar: SeekBar) {
        updateTimeTask = object : Runnable {
            override fun run() {
                val currentTime = videoView.currentPosition
                seekBar.progress = currentTime
                binding.timerDuration.text =
                    convertToDurationForVideo(videoView.currentPosition, videoView.duration)

                handler.postDelayed(this, 33)
            }
        }
        handler.postDelayed(updateTimeTask, 100)
    }

    fun getVideoResolution(context: Context, uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val width =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val height =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: 0

        retriever.release()
        return Pair(width, height)
    }

    fun getVideoDuration(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLong() ?: 0

        retriever.release()
        return durationMs / 1000 // seconds
    }

    fun getBitrateForResolution(height: Int): Int {
        return when (height) {
            240 -> 400_000
            360 -> 800_000
            480 -> 1_200_000
            720 -> 2_500_000
            1080 -> 5_000_000
            else -> 1_000_000
        }
    }

    fun calculateSizeMB(bitrate: Int, durationSec: Long): Double {
        return (bitrate.toDouble() * durationSec) / (8 * 1024 * 1024)
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
            showSystemBars()
        }
    }

    private fun hideSystemUI() {
        // Access activity's window through requireActivity()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (30+)
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy API (below 30)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(
            requireActivity().window, requireActivity().window.decorView
        )
        // Show both the status bar and the navigation bar
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.compress_menu, menu)
        menu.findItem(R.id.compress)
            .actionView?.findViewById<TextView>(R.id.btn_compress)
            ?.setOnClickListener {
                compressVideo()
            }
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }

    private fun showProgressDialog() {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_progress, null)

        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)

        progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.compressing_video))
            .setView(view)
            .setCancelable(false)
            .create()

        progressDialog?.show()
    }

    private fun compressVideo() {

        val resolution = selectedResolution ?: return
        val inputUri = navArgs.videoUrl.toUri()
        val durationSec = getVideoDuration(requireContext(), inputUri)
        val totalDurationMs = durationSec * 1000

        val outputSafUri = createOutputFileInSameFolder() ?: return

        val inputPath = getPathFromUri(inputUri)
        val outputTempFile = FFmpegKitConfig.getSafParameterForWrite(requireContext(), outputSafUri)

        val baseBitrate = getBitrateForResolution(resolution.height)

        val finalBitrate =
            (baseBitrate * selectedQuality.multiplier).toInt()

        val command = arrayOf(
            "-y",
            "-i", inputPath,
            "-vf", "scale=min(${resolution.width}\\,iw):-2,setsar=1",
            "-b:v", finalBitrate.toString(),
            "-c:v", "libopenh264",
            "-preset", "fast",
            "-c:a", "aac",
            "-b:a", "128k",
            outputTempFile
        )

        showProgressDialog()

        FFmpegKit.executeAsync(
            command.joinToString(" "),
            { session ->

                if (ReturnCode.isSuccess(session.returnCode)) {

//                    copyFileToSaf(outputTempFile, outputSafUri)

                    requireActivity().runOnUiThread {
                        progressDialog?.dismiss()
                        binding.videoSizeTextView.text = getString(R.string.saved_successfully)
                    }

                } else {
                    Log.e("FFMpeg", "Logs ${session.failStackTrace}");
                    requireActivity().runOnUiThread {
                        progressDialog?.dismiss()
                        binding.videoSizeTextView.text = getString(R.string.failed)
                    }
                }

            },
            { log ->
                Log.e("FFMpeg", "Logs ${log.message}");
            },
            { statistics ->

                val progress = (statistics.time.toFloat() / totalDurationMs) * 100

                requireActivity().runOnUiThread {
                    progressBar.progress = progress.toInt()
                    progressText.text = getString(R.string.percent_value, progress.toInt())
                }
            }
        )
    }

    private fun getPathFromUri(uri: Uri): String {
        val inputStream = requireContext().contentResolver.openInputStream(uri)!!
        val file = File(requireContext().cacheDir, "input_temp.mp4")

        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    private fun createOutputFileInSameFolder(): Uri? {

        val parent = getSavedDirectoryUri() ?: return null
        val doc = DocumentFile.fromTreeUri(requireContext(), parent)
        val fileName = "compressed_${System.currentTimeMillis()}.mp4"

        val newFile = doc?.createFile("video/mp4", fileName)

        return newFile?.uri
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }

    private fun getParentDocumentFile(uri: Uri): DocumentFile? {
        val doc = DocumentFile.fromSingleUri(requireContext(), uri)
        return doc?.parentFile
    }

    private fun copyFileToSaf(sourceFile: File, destUri: Uri) {
        val resolver = requireContext().contentResolver

        resolver.openOutputStream(destUri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }
}
