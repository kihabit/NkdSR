package com.nkd.screenrecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.adapter.VideoTrimmerAdapter
import com.nkd.screenrecorder.databinding.FragmentVideoMergerBinding
import com.nkd.screenrecorder.utils.Utils
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoMergerFragment : Fragment(), MenuProvider {
    private enum class PlayerState { IDLE, PLAYING, PAUSED, FINISHED }

    private lateinit var binding: FragmentVideoMergerBinding
    private val navArgs: VideoMergerFragmentArgs by navArgs()
    private var mVideoThumbAdapter: VideoTrimmerAdapter = VideoTrimmerAdapter(ArrayList())
    private val arrayList: ArrayList<Uri> = arrayListOf()
    private lateinit var preference: SharedPreferences

    // Duration (ms) of each clip in arrayList, same order/index — the single source
    // of truth for both the proportional thumbnail layout and clip-boundary seeking.
    private val clipDurationsMs: MutableList<Long> = mutableListOf()
    private var totalMergedDurationMs: Long = 0L

    // Index into arrayList of the clip currently loaded into videoView. -1 means
    // nothing has been loaded yet (before the first play/seek).
    private var currentPlayingPosition: Int = -1

    // Sum of durations of every clip BEFORE currentPlayingPosition — added to
    // videoView's own currentPosition to get the merged-timeline elapsed time.
    private var previousVideosDurationMs: Long = 0L

    // A seek to apply (ms into the newly-loaded clip) once its onPrepared fires,
    // set whenever seekToMergedPosition() has to switch videoView to a different clip.
    private var pendingSeekMs: Long? = null

    private var isUserScrubbing = false
    private var isLoaded = false
    private var playerState = PlayerState.IDLE

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!isUserScrubbing) {
                val elapsed = previousVideosDurationMs + binding.videoView.currentPosition.toLong()
                updateMarkerPosition(elapsed)
            }
            if (binding.videoView.isPlaying) {
                progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 16L // ~60fps, matches the trim screen

        // Fixed thumbnail budget for the WHOLE merged timeline (analogous to the trim
        // screen's VideoTrimmerUtil.MAX_COUNT_RANGE for a single video) — distributed
        // across clips proportionally to their duration so the static strip stays
        // duration-proportional regardless of how many clips are merged.
        private const val TOTAL_THUMBNAIL_COUNT = 18
        private const val MIN_THUMBS_PER_CLIP = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentVideoMergerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preference = requireContext().getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        if (savedInstanceState == null) {
            navArgs.videoUrls.forEach {
                if (!arrayList.contains(it.toUri()))
                    arrayList.add(it.toUri())
            }
        }

        // ✅ Static strip, mirroring VideoTrimmerView: the thumbnail strip never scrolls —
        // a marker/playhead view moves across it instead. Sizing (item width) is measured
        // once real layout is known, then the initial thumbnail load runs right after so
        // thumbnails are always shot at the correct, final item size.
        binding.rvThumbnails.apply {
            layoutManager = object : LinearLayoutManager(
                requireContext(), HORIZONTAL, false
            ) {
                override fun canScrollHorizontally(): Boolean = false
            }
            isNestedScrollingEnabled = false
            adapter = mVideoThumbAdapter
            doOnLayout {
                val stripWidth = it.width
                if (stripWidth > 0) {
                    val itemWidth = stripWidth / TOTAL_THUMBNAIL_COUNT
                    val itemHeight = 60.dpToPx()
                    VideoTrimmerUtil.THUMB_WIDTH = itemWidth
                    VideoTrimmerUtil.THUMB_HEIGHT = itemHeight
                    mVideoThumbAdapter.setItemSize(itemWidth, itemHeight)
                }
                if (!isLoaded) {
                    loadAllVideoThumbnails()
                }
            }
        }
        setupThumbnailScrubbing()

        binding.playPauseButton.setOnClickListener {
            when (playerState) {
                PlayerState.IDLE, PlayerState.PAUSED -> resumeVideoAndScroll()
                PlayerState.PLAYING -> pauseVideoAndScroll()
                PlayerState.FINISHED -> replayAllVideos()
            }
        }

        setupVideoView()
        setupAddVideoButton()
        observeNewVideoResult()
    }

    private fun setupVideoView() {
        arrayList.firstOrNull()?.let { firstUri ->
            Glide.with(requireContext())
                .asBitmap()
                .load(firstUri)
                .frame(1000000)
                .into(binding.thumbnail)
        }
        binding.videoView.apply {
            setOnPreparedListener { mp ->
                mp.isLooping = false
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

                val seekMs = pendingSeekMs
                if (seekMs != null) {
                    pendingSeekMs = null
                    // start() then (maybe) pause() so the frame at seekMs is actually
                    // rendered — VideoView doesn't reliably draw a seeked frame before
                    // playback has started at least once.
                    binding.videoView.seekTo(seekMs.toInt())
                    binding.videoView.start()
                    if (playerState == PlayerState.PLAYING) {
                        startProgressTracking()
                    } else {
                        binding.videoView.pause()
                    }
                } else {
                    start()
                    startProgressTracking()
                }
            }
            setOnCompletionListener {
                stopProgressTracking()
                if (currentPlayingPosition + 1 < arrayList.size) {
                    val nextClipStart = clipDurationsMs.take(currentPlayingPosition + 1).sum()
                    seekToMergedPosition(nextClipStart)
                } else {
                    playerState = PlayerState.FINISHED
                    binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    updateMarkerPosition(totalMergedDurationMs)
                }
            }
        }
    }

    /**
     * Single source of truth for both playback and marker position. Given a target
     * time on the MERGED timeline, finds which clip it falls in, switches videoView's
     * URI if that clip isn't already loaded (queuing the seek until onPrepared fires),
     * and keeps previousVideosDurationMs in sync — used by the automatic progress
     * loop's clip-advance, the play button, replay, and user scrubbing alike.
     */
    private fun seekToMergedPosition(targetMs: Long) {
        if (totalMergedDurationMs <= 0L || clipDurationsMs.isEmpty()) return
        val clamped = targetMs.coerceIn(0L, totalMergedDurationMs)

        var acc = 0L
        var targetIndex = clipDurationsMs.size - 1
        var offsetInClip = clamped
        for (i in clipDurationsMs.indices) {
            val duration = clipDurationsMs[i]
            if (clamped < acc + duration || i == clipDurationsMs.size - 1) {
                targetIndex = i
                offsetInClip = (clamped - acc).coerceIn(0L, duration.coerceAtLeast(0L))
                break
            }
            acc += duration
        }

        previousVideosDurationMs = acc
        updateMarkerPosition(clamped)

        if (targetIndex == currentPlayingPosition) {
            binding.videoView.seekTo(offsetInClip.toInt())
        } else {
            pendingSeekMs = offsetInClip
            currentPlayingPosition = targetIndex
            binding.thumbnail.isGone = true
            binding.videoView.setVideoURI(arrayList[targetIndex])
            binding.videoView.requestFocus()
            // onPrepared (setupVideoView()) applies pendingSeekMs once ready.
        }
    }

    private fun resumeVideoAndScroll() {
        playerState = PlayerState.PLAYING
        binding.playPauseButton.setImageResource(R.drawable.ic_stop)
        if (currentPlayingPosition == -1) {
            seekToMergedPosition(0L)
        } else {
            binding.videoView.start()
            startProgressTracking()
        }
    }

    private fun pauseVideoAndScroll() {
        binding.videoView.pause()
        stopProgressTracking()
        playerState = PlayerState.PAUSED
        binding.playPauseButton.setImageResource(R.drawable.ic_play)
    }

    private fun replayAllVideos() {
        currentPlayingPosition = -1
        previousVideosDurationMs = 0L
        playerState = PlayerState.IDLE
        updateMarkerPosition(0L)
        resumeVideoAndScroll()
    }

    private fun startProgressTracking() {
        if (isUserScrubbing) return
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressTracking() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    /** Moves the static strip's playhead to the pixel position for totalElapsedMs. */
    private fun updateMarkerPosition(totalElapsedMs: Long) {
        val stripWidth = binding.rvThumbnails.width
        if (stripWidth <= 0 || totalMergedDurationMs <= 0L) return

        val clamped = totalElapsedMs.coerceIn(0L, totalMergedDurationMs)
        val markerPx = ((clamped.toFloat() / totalMergedDurationMs.toFloat()) * stripWidth)
            .toInt()
            .coerceIn(0, stripWidth)

        val params = binding.markerPlayhead.layoutParams as ConstraintLayout.LayoutParams
        params.marginStart = markerPx
        binding.markerPlayhead.layoutParams = params
        if (binding.markerPlayhead.isGone) {
            binding.markerPlayhead.visibility = View.VISIBLE
        }
    }

    /** Dragging or tapping the (now static) thumbnail strip scrubs playback. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupThumbnailScrubbing() {
        binding.rvThumbnails.setOnTouchListener { v, event ->
            if (totalMergedDurationMs <= 0L) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isUserScrubbing = true
                    stopProgressTracking()
                    val fraction = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
                    val targetMs = (fraction * totalMergedDurationMs).toLong()
                    seekToMergedPosition(targetMs)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserScrubbing = false
                    if (playerState == PlayerState.PLAYING) startProgressTracking()
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun getVideoDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(requireContext(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun observeNewVideoResult() {
        val backEntry = findNavController().getBackStackEntry(R.id.videoMergerFragment)
        backEntry.savedStateHandle
            .getLiveData<String>("trimmed_video_uri")
            .observe(viewLifecycleOwner) { uriString ->
                if (uriString != null) {
                    val newUri = uriString.toUri()
                    if (arrayList.contains(newUri)) {
                        backEntry.savedStateHandle.remove<String>("trimmed_video_uri")
                        return@observe
                    }
                    arrayList.add(newUri)
                    backEntry.savedStateHandle.remove<String>("trimmed_video_uri")
                    Log.e("VideoMergerFragment", "Observing")
                    // Reload the whole strip so thumbnail counts stay duration-proportional
                    // across ALL clips, not just the newly added one.
                    loadAllVideoThumbnails()
                }
            }
    }

    private fun setupAddVideoButton() {
        binding.btnAddVideo.setOnClickListener {
            findNavController().navigate(
                VideoMergerFragmentDirections.actionVideoMergerFragmentToVideoTrimmerFragment(
                    isTrim = false,
                    isEditVideo = false,
                    isVideoCompress = false,
                    isVideoToGIF = false,
                    isVideoMerger = false
                )
            )
        }
    }

    private fun loadAllVideoThumbnails() {
        isLoaded = true
        mVideoThumbAdapter.clearBitmaps()

        lifecycleScope.launch(Dispatchers.IO) {
            val durations = arrayList.map { getVideoDurationMs(it) }
            val total = durations.sum()

            withContext(Dispatchers.Main) {
                clipDurationsMs.clear()
                clipDurationsMs.addAll(durations)
                totalMergedDurationMs = total
                binding.totalTimeTextview.text =
                    getString(R.string.label_total_time, Utils.formatDuration(total))
            }

            if (total <= 0L) return@launch

            val thumbCounts = durations.map { duration ->
                val share = (duration.toDouble() / total.toDouble()) * TOTAL_THUMBNAIL_COUNT
                share.toInt().coerceAtLeast(MIN_THUMBS_PER_CLIP)
            }

            arrayList.forEachIndexed { index, uri ->
                val duration = durations[index]
                if (duration <= 0L) return@forEachIndexed
                val count = thumbCounts.getOrElse(index) { MIN_THUMBS_PER_CLIP }
                VideoTrimmerUtil.shootVideoThumbInBackground(
                    requireActivity(), uri, count, 0L, duration
                ) { bitmap, _ ->
                    launch(Dispatchers.Main) {
                        mVideoThumbAdapter.addBitmaps(bitmap)
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            stopProgressTracking()
        }
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
        }
        Log.e("VideoMergerFragment", "onPause")
    }

    override fun onResume() {
        super.onResume()
        if (!binding.videoView.isPlaying) {
            binding.videoView.start()
            startProgressTracking()
        }
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopProgressTracking()
        binding.videoView.stopPlayback()
        Log.e("VideoMergerFragment", "onDestroyView")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e("VideoMergerFragment", "onDestroy")
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.crop_menu, menu)
        menu.findItem(R.id.cropSave).title = getString(R.string.menu_merge)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.cropSave) mergeVideos()
        return false
    }

    fun createConcatFile(context: Context, files: List<File>): File {
        val concatFile = File(context.cacheDir, "concat_list.txt")
        concatFile.bufferedWriter().use { writer ->
            files.forEach {
                Log.e("VideoMergerFragment", "Merge file is ${it.name} and path: ${it.absolutePath}")
                writer.write("file '${it.absolutePath}'\n")
            }
        }
        return concatFile
    }

    fun copyUriToFile(context: Context, uri: Uri, fileName: String): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val tempFile = File(context.cacheDir, fileName)
        inputStream.use { input -> tempFile.outputStream().use { output -> input?.copyTo(output) } }
        return tempFile
    }

    fun mergeVideos() {
        val fileArray = arrayListOf<File>()
        arrayList.forEach {
            fileArray.add(
                copyUriToFile(
                    requireActivity(),
                    it,
                    "Temp_${System.currentTimeMillis()}.mp4"
                )
            )
        }

        val concatFile = createConcatFile(requireActivity(), fileArray)
        val tempOutputFile =
            File(requireContext().cacheDir, "merged_${System.currentTimeMillis()}.mp4")
        val command =
            "-f concat -safe 0 -i ${concatFile.absolutePath} -c copy ${tempOutputFile.absolutePath}"

        FFmpegKit.executeAsync(command) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val outputSafUri = createOutputFileInSameFolder() ?: return@executeAsync
                requireContext().contentResolver.openOutputStream(outputSafUri)?.use { output ->
                    tempOutputFile.inputStream().use { input -> input.copyTo(output) }
                }
                Log.e("VideoMergerFragment", "Video Merged Successfully")
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_video_merged),
                        Toast.LENGTH_SHORT
                    ).show()
                    findNavController().navigateUp()
                }
            } else {
                Log.e("VideoMergerFragment", "Error: ${session.allLogsAsString}")
            }
        }
    }

    private fun createOutputFileInSameFolder(): Uri? {
        val parent = getSavedDirectoryUri() ?: return null
        val doc = DocumentFile.fromTreeUri(requireContext(), parent)
        val newFile = doc?.createFile("video/mp4", "merged_${System.currentTimeMillis()}.mp4")
        return newFile?.uri
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }
}
