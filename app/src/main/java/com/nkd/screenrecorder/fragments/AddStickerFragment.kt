package com.nkd.screenrecorder.fragments

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.adapter.StickerViewPageAdapter
import com.nkd.screenrecorder.adapter.VideoTrimmerAdapter
import com.nkd.screenrecorder.databinding.FragmentAddStickerBinding
import com.nkd.screenrecorder.databinding.ItemOverlayBinding
import com.nkd.screenrecorder.databinding.TimelineTextClipBinding
import com.nkd.screenrecorder.model.StickerBurnData
import com.nkd.screenrecorder.model.StickerTrack
import com.nkd.screenrecorder.utils.VideoTextBurner
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.convertToDurationForVideo
import com.nkd.screenrecorder.utils.dpToPx
import java.io.File
import java.io.FileOutputStream

class AddStickerFragment : Fragment(), MenuProvider {
    private lateinit var binding: FragmentAddStickerBinding
    private val navArgs by navArgs<AddStickerFragmentArgs>()
    private lateinit var handler: Handler
    private lateinit var updateTimeTask: Runnable

    // ── Native video dimensions (set in onPrepared) ────────────────────────
    private var videoNativeWidth: Int = 0
    private var videoNativeHeight: Int = 0
    private var isPanelVisible = false
    private var totalWidth = 0
    private var halfWidth: Int = 0
    private var pxPerSecond = 150f

    // ── Sticker overlay state ──────────────────────────────────────────────
    private data class TextOverlayState(
        val overlayBinding: ItemOverlayBinding,
        var selectedFontResId: Int,
        val savedBackground: Drawable?,
        var controlsVisible: Boolean = true,
        val stickerDrawable: Drawable? = null,
        val stickerWidthPx: Int = 0,
        val stickerHeightPx: Int = 0
    )

    data class StickerOverlay(
        val drawable: Drawable,
        var startTimeMs: Long = 0L,
        var endTimeMs: Long = 0L,
    )

    private val stickerTracks = mutableListOf<StickerTrack>()

    private var textBurnProgressDialog: ProgressDialog? = null
    private var thumbnailScrollOffset = 0

    // ✅ Multiple stickers support
    private val stickerStateList: ArrayList<TextOverlayState> = ArrayList()
    private val trackList = mutableListOf<FrameLayout>()
    private lateinit var preference: SharedPreferences
    private lateinit var displayMetrics: DisplayMetrics

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentAddStickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preference =
            requireActivity().getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)
        requireActivity().addMenuProvider(this, this, Lifecycle.State.RESUMED)
        displayMetrics = DisplayMetrics()
        binding.apply {
            videoView.setVideoPath(navArgs.videoUrl)
            videoView.setOnPreparedListener { mp ->
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                videoNativeWidth = mp.videoWidth
                videoNativeHeight = mp.videoHeight
                handler = Handler(Looper.getMainLooper())
                updateProgressBar(videoView, seekBar)
                progressBarPrepare(mp)

                container.post {
                    // ✅ Single source of truth for videoView sizing
                    recalculateVideoViewSize()
                    recalculatePxPerSecond()
                    val totalDuration = binding.videoView.duration.toLong()
                    totalWidth = timeToPx(totalDuration)
                    val containerWidth = totalWidth + halfWidth * 2
                    binding.overlayTrackContainer.layoutParams.width = containerWidth
                    binding.overlayTrackContainer.requestLayout()
                }
            }

            Glide.with(requireContext())
                .asBitmap()
                .load(navArgs.videoUrl)
                .frame(1000000)
                .into(thumbnail)

            videoView.setOnCompletionListener {
                playStopButton.setImageDrawable(
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_play, null)
                )
                setOverlayControlsVisible(true)
            }

            playStopButton.setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_play, null)
                    )
                } else {
                    thumbnail.isGone = true
                    videoView.start()
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_stop, null)
                    )
                    setOverlayControlsVisible(false)
                }
            }

            val fragmentList = arrayListOf<Fragment>()
            fragmentList.add(StickerViewFragment("1") {
                addStickerToTimeline(
                    StickerOverlay(
                        drawable = ContextCompat.getDrawable(requireContext(), it)!!,
                        startTimeMs = 0L,
                        endTimeMs = videoView.duration.toLong() / 2
                    )
                )
            })
            val stickerViewPageAdapter =
                StickerViewPageAdapter(fragmentList, childFragmentManager, lifecycle)
            viewPager.adapter = stickerViewPageAdapter
            TabLayoutMediator(tabLayout, viewPager, true, true) { tab, position ->
                when (position) {
                    0 -> tab.text = getString(R.string.common)
                    1 -> tab.setIcon(R.drawable.ic_sticker)
                    2 -> tab.setIcon(R.drawable.ic_edit_video)
                }
            }.attach()
        }


// ── Show panel immediately on first open ──────────────────────────────
        binding.stickerPanel.post {
            showStickerPanel()
        }
    }

    /**
     * Called after every container resize.
     * Recomputes VideoView dimensions to fit new container while keeping aspect ratio.
     */
    private fun recalculateVideoViewSize() {
        if (videoNativeWidth <= 0 || videoNativeHeight <= 0) return

        val parentWidth = binding.container.width
        val parentHeight = binding.container.height
        if (parentWidth <= 0 || parentHeight <= 0) return

        val videoRatio = videoNativeWidth.toFloat() / videoNativeHeight
        val viewRatio = parentWidth.toFloat() / parentHeight

        val lp = binding.videoView.layoutParams
        if (videoRatio > viewRatio) {
            lp.width = parentWidth
            lp.height = (parentWidth / videoRatio).toInt()
        } else {
            lp.height = parentHeight
            lp.width = (parentHeight * videoRatio).toInt()
        }
        binding.videoView.layoutParams = lp

        // Sync thumbnail to match
        binding.thumbnail.layoutParams.width = lp.width
        binding.thumbnail.layoutParams.height = lp.height
        binding.thumbnail.requestLayout()

        binding.overlayContainer.layoutParams.width = lp.width
        binding.overlayContainer.layoutParams.height = lp.height

    }

    /**
     * Recalculates pxPerSecond so the entire timeline fits within a
     * reasonable width. Target: full video = screen width * 3 (scrollable
     * but not infinite).
     */
    private fun recalculatePxPerSecond() {
        val durationSec = binding.videoView.duration / 1000f
        if (durationSec <= 0f) return

        val screenWidth = resources.displayMetrics.widthPixels

        // Target total timeline width = screenWidth * 3 for short videos
        // For long videos cap so timeline stays manageable
        val targetTotalWidth = when {
            durationSec <= 30f -> screenWidth * 2      // short: 2 screens wide
            durationSec <= 120f -> screenWidth * 3      // medium: 3 screens wide
            durationSec <= 600f -> screenWidth * 4      // long: 4 screens wide
            else -> screenWidth * 5      // very long: 5 screens wide
        }

        pxPerSecond = targetTotalWidth / durationSec

    }

    // ── Timeline / Thumbnail editor view ──────────────────────────────────
    private fun timeToPx(timeMs: Long): Int = ((timeMs / 1000f) * pxPerSecond).toInt()
    private fun pxToTime(px: Int): Long = ((px / pxPerSecond) * 1000).toLong()

    // ── Add helper methods ─────────────────────────────────────────────────
    private fun showStickerPanel() {
        isPanelVisible = true
        binding.stickerPanel.visibility = View.VISIBLE
        binding.stickerPanel.animate()
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun hideStickerPanel() {
        isPanelVisible = false
        binding.stickerPanel.animate()
            .translationY(binding.stickerPanel.height.toFloat())
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                binding.stickerPanel.visibility = View.GONE
            }
            .start()
    }

    private fun toggleStickerPanel() {
        if (isPanelVisible) hideStickerPanel() else showStickerPanel()
    }

    // ── 2. addStickerOverlay — replace existing one ────────────────────────
    private fun addStickerToTimeline(overlay: StickerOverlay) {
        binding.stickerPanel.post {
            binding.stickerPanel.translationY = binding.stickerPanel.height.toFloat()
            binding.stickerPanel.visibility = View.GONE
        }

        if (halfWidth == 0) {
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            halfWidth = (displayMetrics.widthPixels / 2) - 10
        }
        val track = createNewTrack()
        val clipBinding = TimelineTextClipBinding.inflate(layoutInflater)

        val duration = overlay.endTimeMs - overlay.startTimeMs
        val clipWidth = timeToPx(duration)

        val params = FrameLayout.LayoutParams(clipWidth, 40.dpToPx()).apply {
            leftMargin = halfWidth  // t=0
            topMargin = 5
        }
        clipBinding.clipRoot.layoutParams = params
        clipBinding.clipLabel.isGone = true
        clipBinding.sticker.isVisible = true
        clipBinding.sticker.setImageDrawable(overlay.drawable)
        track.addView(clipBinding.root)

        // ✅ FIX 2: Create the visual sticker on the video overlay
        val overlayBinding =
            ItemOverlayBinding.inflate(layoutInflater, binding.overlayContainer, false)
        val stickerSize = 80.dpToPx()
        overlayBinding.root.layoutParams =
            FrameLayout.LayoutParams(stickerSize, stickerSize).apply {
                leftMargin = 20
                topMargin = 20
            }
        overlayBinding.overlayText.isGone = true
        binding.overlayContainer.isVisible = true

        binding.overlayContainer.addView(overlayBinding.root)
        overlayBinding.overlayImageView.isVisible = true
        overlayBinding.overlayImageView.setImageDrawable(overlay.drawable)
        // ✅ Drawable was set before attachment — force redraw now that view is in window
        overlayBinding.overlayImageView.post {
            overlayBinding.overlayImageView.invalidate()
            overlayBinding.overlayImageView.requestLayout()
        }
        // ✅ Show only if current time is within range
        val currentMs = pxToTime(thumbnailScrollOffset.coerceAtLeast(0))
        overlayBinding.root.isVisible = currentMs in overlay.startTimeMs..overlay.endTimeMs

        // Store the track so we can update visibility later
        val stickerTrack = StickerTrack(overlay, overlayBinding)
        stickerTracks.add(stickerTrack)

        // Close button removes both the visual and the track entry
        overlayBinding.closeContainer.setOnClickListener {
            binding.overlayContainer.removeView(overlayBinding.root)
            // ✅ Remove timeline clip track row
            binding.overlayTrackContainer.removeView(track)
            trackList.remove(track)

            // ✅ Remove from data list
            stickerTracks.removeAll { it === stickerTrack }

            // ✅ Update menu visibility
//            cropMenu?.isVisible = stickerTracks.isNotEmpty()

            // ✅ Resize video container now that a track row is gone
            if (stickerTracks.isEmpty()) {
                binding.frameLayoutEditor.isVisible = false
            }

            // ✅ Re-apply translation after track removal re-layout
            binding.overlayTrackContainer.post {
                binding.overlayTrackContainer.translationX = -thumbnailScrollOffset.toFloat()
            }
        }

        // Get native sticker dimensions before calling enableStickerResize
        val stickerW = overlay.drawable.intrinsicWidth.takeIf { it > 0 } ?: 0
        val stickerH = overlay.drawable.intrinsicHeight.takeIf { it > 0 } ?: 0
        enableDrag(overlayBinding.root, binding.overlayContainer)
        enableStickerResize(
            overlayBinding,
            binding.overlayContainer,
            stickerNativeWidth = stickerW,   // ✅ pass dimensions, no textOverlayState needed
            stickerNativeHeight = stickerH
        )
        binding.frameLayoutEditor.isVisible = true
        // ── Thumbnails ──────────────────────────────────────────────────────
        binding.thumbnailRecyclerView.post {
            // ✅ post{} ensures RecyclerView height is measured before we read it
            setupThumbnails()
        }
        // ✅ FIX 1: Single scroll listener guards against duplicate registration
        if (binding.thumbnailRecyclerView.tag != "scroll_linked") {
            binding.thumbnailRecyclerView.tag = "scroll_linked"

            binding.thumbnailRecyclerView.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {

                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    thumbnailScrollOffset = (thumbnailScrollOffset + dx).coerceAtLeast(0)

                    binding.overlayTrackContainer.translationX = -thumbnailScrollOffset.toFloat()

                    val currentTimeMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
                    val videoDurationMs = binding.videoView.duration.toLong()

                    if (currentTimeMs >= videoDurationMs) {
                        if (!binding.videoView.isPlaying) {
                            binding.videoView.seekTo(videoDurationMs.toInt())
                        }
                        return
                    }

                    if (!binding.videoView.isPlaying) {
                        binding.videoView.seekTo(currentTimeMs.toInt())
                    }

                    updateAllStickerVisibility(currentTimeMs)
                }
            })
        }
        updateAllStickerVisibility(pxToTime(thumbnailScrollOffset).coerceAtLeast(0))
        setupClipDraggingForSticker(clipBinding, overlay)
        binding.overlayTrackContainer.post {
            binding.overlayTrackContainer.translationX = -thumbnailScrollOffset.toFloat()
        }

    }

    private fun updateAllStickerVisibility(currentTimeMs: Long) {
        val duration = binding.videoView.duration.toLong()
        for (track in stickerTracks) {
            val shouldBeVisible =
                currentTimeMs in track.overlay.startTimeMs..track.overlay.endTimeMs && currentTimeMs < duration  // ✅ never show past video end

            track.overlayBinding.root.isVisible = shouldBeVisible
            // ✅ Only invalidate when transitioning invisible → visible
            // Not on every move event — prevents flicker
            if (shouldBeVisible) {
                track.overlayBinding.overlayImageView.post {
                    track.overlayBinding.overlayImageView.setImageDrawable(null)
                    track.overlayBinding.overlayImageView.setImageDrawable(track.overlay.drawable)
                    track.overlayBinding.overlayImageView.invalidate()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setupClipDraggingForSticker(
        clipView: TimelineTextClipBinding,
        overlay: StickerOverlay,
    ) {
        val root = clipView.clipRoot
        val leftHandle = clipView.leftHandle
        val rightHandle = clipView.rightHandle

        var isDraggingClip = false
        var isDraggingLeft = false
        var isDraggingRight = false
        var lastRawX = 0f
        var initialMargin = 0
        var initialWidth = 0
        var lastClipRawX = 0f
        var initialClipMargin = 0

        fun syncOverlayTimes(params: FrameLayout.LayoutParams) {
            overlay.startTimeMs = pxToTime((params.leftMargin - halfWidth).coerceAtLeast(0))
            overlay.endTimeMs =
                pxToTime((params.leftMargin + params.width - halfWidth).coerceAtLeast(0))
            val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
            updateAllStickerVisibility(currentMs)
        }

        root.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x < 50f || event.x > v.width - 50f) return@setOnTouchListener false
                    isDraggingClip = true
                    lastClipRawX = event.rawX
                    initialClipMargin = params.leftMargin
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingClip) return@setOnTouchListener false
                    val dx = (event.rawX - lastClipRawX).toInt()
                    lastClipRawX = event.rawX
                    initialClipMargin += dx
                    // ✅ Can't go before t=0 OR push right edge past video end
                    val maxLeft = maxClipRightEdge() - params.width
                    params.leftMargin = initialClipMargin.coerceIn(halfWidth, maxLeft)
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClip = false
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)

                    true
                }

                else -> false
            }
        }

        leftHandle.setOnTouchListener { _, event ->
            val params = root.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingLeft = true
                    lastRawX = event.rawX
                    initialMargin = params.leftMargin
                    initialWidth = params.width
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingLeft) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialMargin += dx
                    initialWidth -= dx
                    // ✅ Left edge can't go before t=0
                    params.leftMargin = initialMargin.coerceAtLeast(halfWidth)
                    params.width = initialWidth.coerceAtLeast(100)
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeft = false
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    true
                }

                else -> false
            }
        }

        rightHandle.setOnTouchListener { _, event ->
            val params = root.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingRight = true
                    lastRawX = event.rawX
                    initialWidth = params.width
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingRight) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialWidth += dx
                    // ✅ Right edge can't exceed video end
                    val maxWidth = maxClipRightEdge() - params.leftMargin
                    params.width = initialWidth.coerceIn(100, maxWidth)
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRight = false
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Maximum right edge a clip can reach = halfWidth (t=0 offset) + totalWidth (full video duration)
     * This matches exactly the RecyclerView's content width.
     */
    private fun maxClipRightEdge(): Int = halfWidth + totalWidth
    private fun setupThumbnails() {
        if (binding.thumbnailRecyclerView.adapter != null) return

        // ✅ Fixed thumb size — set on VideoTrimmerUtil before calling
        val thumbW = 60.dpToPx()   // fixed width per thumbnail
        val thumbH = binding.thumbnailRecyclerView.height
            .takeIf { it > 0 } ?: 45.dpToPx()  // match RecyclerView height

        VideoTrimmerUtil.THUMB_WIDTH = thumbW
        // THUMB_HEIGHT is val so we can't set it — use thumbH in adapter only

        // ✅ Count = how many thumbs fit across totalWidth
        // totalWidth = full video in px at pxPerSecond scale
        val thumbCount = if (totalWidth > 0 && thumbW > 0) {
            (totalWidth / thumbW).coerceIn(5, 50)
        } else {
            10
        }

        val mVideoThumbAdapter = VideoTrimmerAdapter(ArrayList())
        // ✅ Tell adapter to render at fixed thumb size
        mVideoThumbAdapter.setItemSize(thumbW, thumbH)

        binding.thumbnailRecyclerView.setAdapter(mVideoThumbAdapter)
        binding.thumbnailRecyclerView.apply {
            clipToPadding = false
            setPadding(halfWidth, 0, halfWidth, 0)
        }

        VideoTrimmerUtil.shootVideoThumbInBackground(
            requireContext(),
            navArgs.videoUrl.toUri(),
            thumbCount,
            0,
            binding.videoView.duration.toLong()
        ) { bitmap, _ -> mVideoThumbAdapter.addBitmaps(bitmap) }
    }

    fun createNewTrack(): FrameLayout {
        val trackWidth = (totalWidth + halfWidth * 2).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels * 3)

        val track = FrameLayout(requireContext()).apply {
            // ✅ LinearLayout.LayoutParams — overlayTrackContainer is a LinearLayout
            layoutParams =
                LinearLayout.LayoutParams(trackWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        binding.overlayTrackContainer.addView(track)
        trackList.add(track)
        binding.overlayTrackContainer.post {
            binding.overlayTrackContainer.translationX = -thumbnailScrollOffset.toFloat()
        }
        return track
    }

    private fun keepInsideBounds(view: View, container: FrameLayout) {
        val params = view.layoutParams as FrameLayout.LayoutParams

        val maxX = (container.width - view.width).coerceAtLeast(0)
        val maxY = (container.height - view.height).coerceAtLeast(0)

        params.leftMargin = params.leftMargin.coerceIn(0, maxX)
        params.topMargin = params.topMargin.coerceIn(0, maxY)

        view.layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: View, container: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startMarginLeft = 0
        var startMarginTop = 0
        var hasMoved = false

        view.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startMarginLeft = params.leftMargin
                    startMarginTop = params.topMargin
                    hasMoved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val diffX = (event.rawX - downRawX).toInt()
                    val diffY = (event.rawY - downRawY).toInt()
                    // Only consider it a move if dragged more than 8px
                    if (!hasMoved && (kotlin.math.abs(diffX) > 8 || kotlin.math.abs(diffY) > 8)) {
                        hasMoved = true
                    }
                    if (hasMoved) {
                        params.leftMargin =
                            (startMarginLeft + diffX).coerceIn(0, container.width - v.width)
                        params.topMargin =
                            (startMarginTop + diffY).coerceIn(0, container.height - v.height)
                        v.layoutParams = params
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        // Pure tap → show controls back
                        setOverlayControlsVisible(true)
                    }
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableStickerResize(
        overlayBinding: ItemOverlayBinding,
        container: FrameLayout,
        stickerNativeWidth: Int = 0,   // ✅ pass dimensions directly, no textOverlayState
        stickerNativeHeight: Int = 0
    ) {
        val root = overlayBinding.overlayRoot
        var downRawX = 0f
        var downRawY = 0f
        var startWidth = 0
        var startHeight = 0
        val minSize = 60.dpToPx()

        overlayBinding.resizeContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = root.width
                    startHeight = root.height
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()

                    // ✅ Max size: either the video rendered area, or native sticker size scaled to view
                    val (videoMaxW, videoMaxH) = getMaxStickerSizeInView()

                    val finalMaxW: Int
                    val finalMaxH: Int

                    if (stickerNativeWidth > 0 && stickerNativeHeight > 0 && videoNativeWidth > 0 && videoNativeHeight > 0) {
                        // ✅ Scale native sticker px → view px safely
                        val (fileMaxW, fileMaxH) = stickerFileToViewSize(
                            stickerNativeWidth, stickerNativeHeight
                        )
                        finalMaxW = minOf(videoMaxW, fileMaxW)
                        finalMaxH = minOf(videoMaxH, fileMaxH)
                    } else {
                        // No native dimensions known — just cap to video area
                        finalMaxW = videoMaxW
                        finalMaxH = videoMaxH
                    }

                    val newWidth = (startWidth + dx).coerceAtLeast(minSize)
                        .coerceAtMost(finalMaxW.coerceAtLeast(minSize))

                    val aspectRatio = if (startWidth > 0) startHeight.toFloat() / startWidth else 1f
                    val newHeight = (newWidth * aspectRatio).toInt()
                        .coerceAtMost(finalMaxH.coerceAtLeast(minSize))

                    val params = root.layoutParams as FrameLayout.LayoutParams
                    params.width = newWidth
                    params.height = newHeight
                    root.layoutParams = params
                    keepInsideBounds(root, container)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    false
                }

                else -> false
            }
        }
    }

    private fun getMaxStickerSizeInView(): Pair<Int, Int> {
        val containerW = binding.overlayContainer.width.toFloat()
        val containerH = binding.overlayContainer.height.toFloat()

        val videoAspect = videoNativeWidth.toFloat() / videoNativeHeight
        val containerAspect = containerW / containerH

        val renderedW: Float
        val renderedH: Float

        if (videoAspect > containerAspect) {
            renderedW = containerW
            renderedH = containerW / videoAspect
        } else {
            renderedH = containerH
            renderedW = containerH * videoAspect
        }

        return Pair(renderedW.toInt(), renderedH.toInt())
    }

    private fun stickerFileToViewSize(
        stickerW: Int, stickerH: Int
    ): Pair<Int, Int> {

        val containerW = binding.overlayContainer.width.toFloat()
        val containerH = binding.overlayContainer.height.toFloat()

        val maxW = (stickerW.toFloat() / videoNativeWidth) * containerW
        val maxH = (stickerH.toFloat() / videoNativeHeight) * containerH

        return Pair(maxW.toInt(), maxH.toInt())
    }

    private fun progressBarPrepare(mediaPlayer: MediaPlayer) {
        binding.seekBar.max = mediaPlayer.duration
        binding.timeDurationTextView.text = convertToDurationForVideo(0, mediaPlayer.duration)
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
                binding.timeDurationTextView.text =
                    convertToDurationForVideo(videoView.currentPosition, videoView.duration)
                handler.postDelayed(this, 33)
            }
        }
        handler.postDelayed(updateTimeTask, 100)
    }


    private fun setOverlayControlsVisible(visible: Boolean) {
        stickerStateList.forEach { state ->
            if (state.controlsVisible == visible) return@forEach
            state.controlsVisible = visible
            state.overlayBinding.apply {
                closeContainer.isVisible = visible
                resizeContainer.isVisible = visible
                overlayRoot.background = if (visible) state.savedBackground else null
            }
        }
    }

    private fun startBurningStickers() {
        if (stickerTracks.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_stickers_added), Toast.LENGTH_SHORT).show()
            return
        }
        if (navArgs.videoUrl.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.no_video_loaded), Toast.LENGTH_SHORT).show()
            return
        }

        // ── Build list of all sticker params in one pass ───────────────────
        val stickerDataList = mutableListOf<StickerBurnData>()

        for ((index, track) in stickerTracks.withIndex()) {
            val overlay = track.overlay
            val overlayBinding = track.overlayBinding

            val params = overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams
            if (params == null) {
                Log.w("BurnSticker", "Skipping sticker #$index — no layout params")
                continue
            }

            val (videoX, videoY) = overlayViewToVideoPixels(
                params.leftMargin.toFloat(), params.topMargin.toFloat()
            )

            val imageView = overlayBinding.overlayImageView
            val videoW = overlaySizeToVideoScale(
                imageView.width.toFloat(),
                binding.overlayContainer.width.toFloat(),
                videoNativeWidth
            )
            val videoH = overlaySizeToVideoScale(
                imageView.height.toFloat(),
                binding.overlayContainer.height.toFloat(),
                videoNativeHeight
            )

            val imagePath = drawableToFile(requireContext(), overlay.drawable)

            Log.d(
                "BurnSticker", """
            Sticker #$index → x=$videoX y=$videoY 
            w=$videoW h=$videoH 
            start=${overlay.startTimeMs}ms end=${overlay.endTimeMs}ms
        """.trimIndent()
            )

            stickerDataList.add(
                StickerBurnData(
                    imagePath = imagePath,
                    xPx = videoX,
                    yPx = videoY,
                    width = videoW,
                    height = videoH,
                    startSec = overlay.startTimeMs / 1000.0,
                    endSec = overlay.endTimeMs / 1000.0
                )
            )
        }

        if (stickerDataList.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_valid_stickers), Toast.LENGTH_SHORT).show()
            return
        }

        showTextBurnProgress()
        binding.overlayContainer.visibility = View.GONE

        // ── Single FFmpeg call with ALL stickers ───────────────────────────
        VideoTextBurner.burnAllStickers(
            context = requireContext(),
            inputPath = navArgs.videoUrl,
            stickers = stickerDataList,
            videoDurationMs = binding.videoView.duration.toLong(),
            onProgress = { percent ->
                requireActivity().runOnUiThread {
                    textBurnProgressDialog?.progress = percent
                    textBurnProgressDialog?.setMessage(getString(R.string.rendering, percent))
                }
            },
            onSuccess = { outputPath ->
                requireActivity().runOnUiThread {
                    // ✅ burnAllStickers() now only writes to local app cache — this
                    // screen has no further "export" step of its own, so it must
                    // promote the result into the SAF save folder itself here,
                    // mirroring EditVideoActivity.exportFinalToOutput().
                    val savedPath = promoteStickerOutputToSaveFolder(outputPath) ?: outputPath
                    dismissTextBurnProgress()
                    Toast.makeText(requireContext(), getString(R.string.saved, savedPath), Toast.LENGTH_LONG).show()
                    binding.overlayContainer.removeAllViews()
                    stickerTracks.clear()
                    binding.videoView.setVideoPath(savedPath)
                    binding.videoView.start()
                    findNavController().navigateUp()
                }
            },
            onError = { errorMessage ->
                requireActivity().runOnUiThread {
                    dismissTextBurnProgress()
                    Log.e("Export", "burn failed: $errorMessage"); Toast.makeText(requireContext(), getString(R.string.burn_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    binding.overlayContainer.visibility = View.VISIBLE
                }
            })
    }

    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }

    /**
     * Copies the sticker burn's local cache output into the user's chosen save
     * folder. Returns the new content:// URI string on success, or null if there's
     * no save folder configured or the copy failed (caller falls back to the local
     * cache path so playback still works even if permanent saving didn't happen).
     */
    private fun promoteStickerOutputToSaveFolder(localPath: String): String? {
        val outputDirUri = getSavedDirectoryUri() ?: return null
        val cacheFile = File(localPath)
        if (!cacheFile.exists()) return null

        val documentFile = DocumentFile.fromTreeUri(requireContext(), outputDirUri)
            ?.createFile("video/mp4", "Sticker_${System.currentTimeMillis()}.mp4")
            ?: return null

        return try {
            requireContext().contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                cacheFile.inputStream().use { input -> input.copyTo(out) }
            }
            cacheFile.delete()
            documentFile.uri.toString()
        } catch (e: Exception) {
            Log.e("AddStickerFragment", "Failed to save sticker output to save folder", e)
            null
        }
    }

    private fun overlayViewToVideoPixels(viewX: Float, viewY: Float): Pair<Int, Int> {
        val containerW = binding.overlayContainer.width.toFloat()
        val containerH = binding.overlayContainer.height.toFloat()
        if (videoNativeWidth <= 0 || videoNativeHeight <= 0 || containerW <= 0f || containerH <= 0f)
            return Pair(viewX.toInt(), viewY.toInt())

        val videoAspect = videoNativeWidth.toFloat() / videoNativeHeight
        val containerAspect = containerW / containerH
        val renderedW: Float
        val renderedH: Float
        val offsetX: Float
        val offsetY: Float

        if (videoAspect > containerAspect) {
            renderedW = containerW
            renderedH = containerW / videoAspect
            offsetX = 0f
            offsetY = (containerH - renderedH) / 2f
        } else {
            renderedH = containerH
            renderedW = containerH * videoAspect
            offsetX = (containerW - renderedW) / 2f
            offsetY = 0f
        }

        val relX = ((viewX - offsetX) / renderedW).coerceIn(0f, 1f)
        val relY = ((viewY - offsetY) / renderedH).coerceIn(0f, 1f)
        return Pair(
            (relX * videoNativeWidth).toInt(),
            (relY * videoNativeHeight).toInt()
        )
    }

    private fun overlaySizeToVideoScale(
        viewSize: Float,
        containerSize: Float,
        videoSize: Int
    ): Int {
        if (containerSize <= 0f) return viewSize.toInt()
        val ratio = viewSize / containerSize
        return (ratio * videoSize).toInt()
    }

    fun drawableToFile(context: Context, drawable: Drawable): String {
        val bitmap = (drawable as BitmapDrawable).bitmap
        val file = File(context.cacheDir, "sticker_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return file.absolutePath
    }

    private fun showTextBurnProgress() {
        textBurnProgressDialog?.dismiss()
        textBurnProgressDialog = ProgressDialog(requireContext()).apply {
            setTitle(getString(R.string.adding_sticker))
            setMessage(getString(R.string.starting))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100; progress = 0; isIndeterminate = false
            setCancelable(true)
            setOnCancelListener { VideoTextBurner.cancel() }
            show()
        }
    }

    private fun dismissTextBurnProgress() {
        textBurnProgressDialog?.dismiss()
        textBurnProgressDialog = null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.crop_menu, menu)
        menu.findItem(R.id.cropSave).title = getString(R.string.save)
        menu.findItem(R.id.addSticker).isVisible = true
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.cropSave) {
            startBurningStickers()
            return true
        } else if (menuItem.itemId == R.id.addSticker) {
            toggleStickerPanel()
            return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
        }
    }
}