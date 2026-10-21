package com.nkd.screenrecorder.widgets

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.adapter.VideoTrimmerAdapter
import com.nkd.screenrecorder.databinding.VideoTrimmerViewBinding
import com.nkd.screenrecorder.interfaces.IVideoTrimmerView
import com.nkd.screenrecorder.interfaces.VideoTrimListener
import com.nkd.screenrecorder.utils.SpacesItemDecoration2
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.formatTime
import com.nkd.screenrecorder.widgets.RangeSeekBarView.OnRangeSeekBarChangeListener
import com.nkd.screenrecorder.widgets.RangeSeekBarView.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class VideoTrimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IVideoTrimmerView {

    private var mRangeSeekBarView: RangeSeekBarView? = null
    private var mSourceUri: Uri? = null
    private var mOnTrimVideoListener: VideoTrimListener? = null
    private var mDuration = 0
    private var mVideoThumbAdapter: VideoTrimmerAdapter = VideoTrimmerAdapter(ArrayList())
    private var restoreState = false

    var mLeftProgressPos: Long = 0
    var mRightProgressPos: Long = 0

    /** Optional preselected range (ms) — the editor's current non-destructive trim. */
    private var initialStartMs: Long = -1L
    private var initialEndMs: Long = -1L

    /** Open with the handles preselected. Call before [initVideoByURI]. */
    fun setInitialRange(startMs: Long, endMs: Long) {
        initialStartMs = startMs
        initialEndMs = endMs
    }
    private var mRedProgressBarPos: Long = 0

    private var mItemWidth: Int = 0
    private var mItemHeight: Int = 0

    private val mOnRangeSeekBarChangeListener: OnRangeSeekBarChangeListener =
        object : OnRangeSeekBarChangeListener {
            override fun onRangeSeekBarValuesChanged(
                bar: RangeSeekBarView?, minValue: Long, maxValue: Long, action: Int, isMin: Boolean,
                pressedThumb: Thumb?
            ) {
                mLeftProgressPos = minValue
                mRightProgressPos = maxValue

                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (pressedThumb == Thumb.MIN) {
                            mRedProgressBarPos = mLeftProgressPos
                            seekTo(mLeftProgressPos)
                            updateMarkerPosition(mLeftProgressPos)
                            if (binding.positionIcon.isGone) {
                                binding.positionIcon.visibility = VISIBLE
                            }
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (pressedThumb == Thumb.MIN) {
                            mRedProgressBarPos = mLeftProgressPos
                            seekTo(mLeftProgressPos)
                            updateMarkerPosition(mLeftProgressPos)
                        } else {
                            seekTo(mRightProgressPos)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (pressedThumb == Thumb.MIN) {
                            mRedProgressBarPos = mLeftProgressPos
                            seekTo(mLeftProgressPos)
                            updateMarkerPosition(mLeftProgressPos)
                        }
                    }

                    else -> {}
                }

                updateTimeLabels()
            }
        }

    // Fixed number of thumbnail slots that always spans the entire video, no matter the duration.
    private var mThumbsTotalCount = VideoTrimmerUtil.MAX_COUNT_RANGE
    private val mAnimationHandler = Handler(Looper.getMainLooper())

    private var lifecycleOwner: LifecycleOwner? = null
    private var viewLifecycleJob: Job? = null
    private val mAnimationRunnable = Runnable { this.updateVideoProgress() }
    private var binding: VideoTrimmerViewBinding =
        VideoTrimmerViewBinding.inflate(LayoutInflater.from(context), this, true)

    init {

        val layoutManager = object : LinearLayoutManager(
            context,
            HORIZONTAL,
            false
        ) {
            override fun canScrollHorizontally(): Boolean = false
        }
        binding.videoFramesRecyclerView.layoutManager = layoutManager
        binding.videoFramesRecyclerView.isNestedScrollingEnabled = false
        binding.videoFramesRecyclerView.setAdapter(mVideoThumbAdapter)


        binding.videoFramesRecyclerView.doOnLayout {
            val actualWidth = it.width
            val actualHeight = it.height
            if (actualWidth > 0 && actualHeight > 0) {
                mItemWidth = actualWidth / VideoTrimmerUtil.MAX_COUNT_RANGE
                mItemHeight = actualHeight
                VideoTrimmerUtil.THUMB_WIDTH = mItemWidth
                VideoTrimmerUtil.THUMB_HEIGHT = mItemHeight
                mVideoThumbAdapter.setItemSize(mItemWidth, mItemHeight)
            }
        }

        setUpListeners()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleOwner = findViewTreeLifecycleOwner()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewLifecycleJob?.cancel()
        lifecycleOwner = null
    }

    private fun initRangeSeekBarView() {
        if (mRangeSeekBarView != null) return
        mLeftProgressPos = 0
        // Always represent the full video, however long it is, with a fixed thumbnail count.
        mThumbsTotalCount = VideoTrimmerUtil.MAX_COUNT_RANGE
        mRightProgressPos = mDuration.toLong()

        // Preselected range from the editor (clamped to the video); default = whole video
        if (initialStartMs >= 0L && initialEndMs > initialStartMs) {
            mLeftProgressPos = initialStartMs.coerceIn(0L, mDuration.toLong())
            mRightProgressPos = initialEndMs.coerceIn(mLeftProgressPos, mDuration.toLong())
            mRedProgressBarPos = mLeftProgressPos
            seekTo(mLeftProgressPos)
        }

        binding.videoFramesRecyclerView.addItemDecoration(
            SpacesItemDecoration2(
                VideoTrimmerUtil.RECYCLER_VIEW_PADDING,
                mThumbsTotalCount
            )
        )
        // Absolute bounds = the whole video; the selection may start preselected
        mRangeSeekBarView = RangeSeekBarView(context, 0L, mDuration.toLong())
        mRangeSeekBarView!!.selectedMinValue = mLeftProgressPos
        mRangeSeekBarView!!.selectedMaxValue = mRightProgressPos
        mRangeSeekBarView!!.setMinShootTime(VideoTrimmerUtil.MIN_SHOOT_DURATION)
        mRangeSeekBarView!!.isNotifyWhileDragging = true
        mRangeSeekBarView!!.setOnRangeSeekBarChangeListener(mOnRangeSeekBarChangeListener)
        mRangeSeekBarView!!.onSeekTap = { seekValue ->
            mRedProgressBarPos = seekValue
            seekTo(seekValue)
            updateMarkerPosition(seekValue)
            if (binding.positionIcon.isGone) {
                binding.positionIcon.visibility = VISIBLE
            }
        }

        mRangeSeekBarView!!.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.seekBarLayout.addView(mRangeSeekBarView)
        updateTimeLabels()
    }

    /** Updates the start / total / end time row above the thumbnail strip */
    private fun updateTimeLabels() {
        binding.tvStartTime.text = formatTime(mLeftProgressPos)
        binding.tvEndTime.text = formatTime(mRightProgressPos)
        binding.tvTotalTime.text =
            context.getString(R.string.label_total_time, formatTime(mDuration.toLong()))
    }

    fun initVideoByURI(videoURI: Uri) {
        mSourceUri = videoURI
        binding.videoLoader.setVideoURI(videoURI)
        binding.videoLoader.requestFocus()
    }

    private fun startShootVideoThumbs(
        context: Context,
        videoUri: Uri,
        totalThumbsCount: Int,
        startPosition: Long,
        endPosition: Long
    ) {
        val owner = findViewTreeLifecycleOwner() ?: return

        viewLifecycleJob = owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {

                VideoTrimmerUtil.shootVideoThumbInBackground(
                    context,
                    videoUri,
                    totalThumbsCount,
                    startPosition,
                    endPosition
                ) { bitmap, interval ->

                    // Switch to Main thread for adapter update
                    launch(Dispatchers.Main) {
                        mVideoThumbAdapter.addBitmaps(bitmap)
                    }
                }
            }
        }
    }

    private fun videoPrepared(mp: MediaPlayer) {
        val lp = binding.videoLoader.layoutParams
        val videoWidth = mp.videoWidth
        val videoHeight = mp.videoHeight

        val screenWidth = binding.videoLoader.width
        val screenHeight = binding.videoLoader.height

        if (videoHeight > videoWidth) {
            lp.width = screenWidth
            lp.height = screenHeight
        } else {
            lp.width = screenWidth
            val r = videoHeight / videoWidth.toFloat()
            lp.height = (lp.width * r).toInt()
        }
        binding.videoLoader.setLayoutParams(lp)
        mDuration = binding.videoLoader.getDuration()
        if (!this.restoreState) {
            seekTo(mRedProgressBarPos.toInt().toLong())
        } else {
            this.restoreState = false
            seekTo(mRedProgressBarPos.toInt().toLong())
        }
        initRangeSeekBarView()


        if (mItemWidth <= 0 || mItemHeight <= 0) {
            val actualWidth = binding.videoFramesRecyclerView.width
            val actualHeight = binding.videoFramesRecyclerView.height
            if (actualWidth > 0 && actualHeight > 0) {
                mItemWidth = actualWidth / VideoTrimmerUtil.MAX_COUNT_RANGE
                mItemHeight = actualHeight
                VideoTrimmerUtil.THUMB_WIDTH = mItemWidth
                VideoTrimmerUtil.THUMB_HEIGHT = mItemHeight
                mVideoThumbAdapter.setItemSize(mItemWidth, mItemHeight)
            }
        }

        // Thumbnails always span the FULL duration of the video (0 -> mDuration),
        // spread across a fixed number of slots, regardless of how long the video is.
        startShootVideoThumbs(context!!, mSourceUri!!, mThumbsTotalCount, 0, mDuration.toLong())
    }

    private fun videoCompleted() {
        seekTo(mLeftProgressPos)
        setPlayPauseViewIcon(false)
    }

    private fun onVideoReset() {
        binding.videoLoader.pause()
        setPlayPauseViewIcon(false)
    }

    private fun playVideoOrPause() {
        if (binding.videoLoader.isPlaying) {
            mRedProgressBarPos = binding.videoLoader.getCurrentPosition().toLong()
            binding.videoLoader.pause()
            pauseRedProgressAnimation()
            setPlayPauseViewIcon(false)
        } else {
            val currentPos = binding.videoLoader.getCurrentPosition().toLong()
            if (currentPos < mLeftProgressPos || currentPos >= mRightProgressPos) {
                mRedProgressBarPos = mLeftProgressPos
                seekTo(mLeftProgressPos)
            } else {
                mRedProgressBarPos = currentPos
            }
            binding.videoLoader.start()
            playingRedProgressAnimation()
            setPlayPauseViewIcon(true)
        }
    }

    fun onVideoPause() {
        if (binding.videoLoader.isPlaying) {
            seekTo(mLeftProgressPos) //复位
            binding.videoLoader.pause()
            setPlayPauseViewIcon(false)
            binding.positionIcon.setVisibility(GONE)
        }
    }

    fun setOnTrimVideoListener(onTrimVideoListener: VideoTrimListener) {
        mOnTrimVideoListener = onTrimVideoListener
    }

    fun setOnDoneClickListener(listener: () -> Unit) {
        binding.btnTrimDone.setOnClickListener { listener() }
    }

    private fun setUpListeners() {

        binding.videoLoader.setOnPreparedListener { mp: MediaPlayer ->
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            videoPrepared(mp)
            val videoWidth = mp.videoWidth
            val videoHeight = mp.videoHeight

            val layoutParams = binding.videoLoader.layoutParams

            val parentWidth = binding.videoLoader.width
            val parentHeight = binding.videoLoader.height

            val videoRatio = videoWidth.toFloat() / videoHeight
            val viewRatio = parentWidth.toFloat() / parentHeight

            if (videoRatio > viewRatio) {
                layoutParams.width = parentWidth
                layoutParams.height = (parentWidth / videoRatio).toInt()
            } else {
                layoutParams.height = parentHeight
                layoutParams.width = (parentHeight * videoRatio).toInt()
            }

            binding.videoLoader.layoutParams = layoutParams
        }
        binding.videoLoader.setOnCompletionListener { mp: MediaPlayer -> videoCompleted() }
        binding.iconVideoPlay.setOnClickListener { playVideoOrPause() }
    }

    private fun seekTo(msec: Long) {
        binding.videoLoader.seekTo(msec.toInt())
    }

    private fun setPlayPauseViewIcon(isPlaying: Boolean) {
        binding.iconVideoPlay.setImageResource(if (isPlaying) R.drawable.ellipse_4 else R.drawable.ic_play)
    }

    private fun playingRedProgressAnimation() {
        pauseRedProgressAnimation()
        if (binding.positionIcon.isGone) {
            binding.positionIcon.visibility = VISIBLE
        }
        mAnimationHandler.post(mAnimationRunnable)
    }

    private fun pauseRedProgressAnimation() {
        binding.positionIcon.clearAnimation()
        mAnimationHandler.removeCallbacks(mAnimationRunnable)
    }

    private fun updateVideoProgress() {
        val currentPosition = binding.videoLoader.getCurrentPosition().toLong()

        // Trigger stop slightly early so we catch it before MediaPlayer's next
        // position jump overshoots the boundary. The marker itself is hard-clamped
        // in updateMarkerPosition(), so this only affects *when* we react, not
        // where the line can visually appear.
        if (currentPosition >= mRightProgressPos - 50) {
            pauseRedProgressAnimation()
            binding.videoLoader.pause()
            // Draw the marker pinned to the right thumb first (in case this frame
            // already overshot slightly), then reset back to the start of the
            // trim selection so the next Play press replays the selected range.
            updateMarkerPosition(mRightProgressPos)
            mRedProgressBarPos = mLeftProgressPos
            seekTo(mLeftProgressPos)
            updateMarkerPosition(mLeftProgressPos)
            setPlayPauseViewIcon(false)
            binding.positionIcon.setVisibility(GONE)
            return
        }

        updateMarkerPosition(currentPosition)
        mAnimationHandler.postDelayed(mAnimationRunnable, 16L) // ~60fps refresh
    }


    private fun updateMarkerPosition(currentPosition: Long) {
        val rangeSeekBar = mRangeSeekBarView ?: return
        val seekBarWidth = rangeSeekBar.width.toFloat()
        if (seekBarWidth <= 0) return

        val leftThumbPx = (rangeSeekBar.normalizedMinValue * seekBarWidth).toInt()
        val rightThumbPx = (rangeSeekBar.normalizedMaxValue * seekBarWidth).toInt()

        val totalTrimDuration = mRightProgressPos - mLeftProgressPos
        if (totalTrimDuration <= 0) return

        val clampedPos = currentPosition.coerceIn(mLeftProgressPos, mRightProgressPos)
        val elapsed = clampedPos - mLeftProgressPos
        val rawMarkerPx = leftThumbPx +
                ((elapsed.toFloat() / totalTrimDuration) * (rightThumbPx - leftThumbPx)).toInt()

        // Hard pixel-level clamp: no matter what rounding/timing glitches happen above,
        // the marker can NEVER be drawn outside the [leftThumbPx, rightThumbPx] range.
        val lowPx = min(leftThumbPx, rightThumbPx)
        val highPx = max(leftThumbPx, rightThumbPx)
        val markerPx = rawMarkerPx.coerceIn(lowPx, highPx)

        val params = binding.positionIcon.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.marginStart = markerPx
        binding.positionIcon.layoutParams = params
    }

    /**
     * Cancel trim thread execut action when finish
     */
    override fun onDestroy() {
    }


    companion object {
        private val TAG: String = VideoTrimmerView::class.java.getSimpleName()
    }
}