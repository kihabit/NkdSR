package com.nkd.screenrecorder.activities

import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nkd.screenrecorder.R

class VideoPlayerActivity : BaseActivity() {

    private lateinit var videoView: VideoView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var controlsLayout: View

    private val handler = Handler(Looper.getMainLooper())
    private var videoList = mutableListOf<Uri>()
    private var currentIndex = 0
    private var isControlsVisible = true
    private lateinit var gestureDetector: GestureDetector

    private val hideControlsRunnable = Runnable { hideControls() }

    private val seekBarUpdater = object : Runnable {
        override fun run() {
            if (videoView.isPlaying) {
                val current = videoView.currentPosition
                seekBar.progress = current
                tvCurrentTime.text = formatTime(current)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        videoView = findViewById(R.id.videoView)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnClose = findViewById(R.id.btnClose)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        controlsLayout = findViewById(R.id.controlsLayout)

        // FIX 1: Pehle intent URI lo
        val intentUri: Uri? = intent.data ?: intent.getParcelableExtra("uri")

        // FIX 2: MediaStore se list load karo
        loadVideoList()

        // FIX 3: URI string compare se dhundo (object compare kaam nahi karta)
        if (intentUri != null) {
            val found = videoList.indexOfFirst {
                it.toString() == intentUri.toString()
            }
            currentIndex = if (found >= 0) {
                found
            } else {
                // SAF URI hai jo MediaStore mein nahi — seedha add karo
                videoList.add(0, intentUri)
                0
            }
        }

        setupGestureDetector()
        setupControls()
        playVideo(currentIndex)
        showControls()
        scheduleHideControls()
    }

    private fun loadVideoList() {
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                videoList.add(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                )
            }
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }
        })
        videoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupControls() {
        btnClose.setOnClickListener {
            finish()
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    tvCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                scheduleHideControls()
            }
        })
    }

    private fun playVideo(index: Int) {
        if (videoList.isEmpty()) return
        currentIndex = index.coerceIn(0, videoList.size - 1)
        val uri = videoList[currentIndex]

        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            val duration = mp.duration
            seekBar.max = duration
            tvTotalTime.text = formatTime(duration)
            tvCurrentTime.text = formatTime(0)
            videoView.start()
            updatePlayPauseButton()
            handler.post(seekBarUpdater)
            scheduleHideControls()
        }
        videoView.setOnCompletionListener {
            if (currentIndex < videoList.size - 1) {
                playNext()
            } else {
                updatePlayPauseButton()
                showControls()
            }
        }
    }

    private fun togglePlayPause() {
        if (videoView.isPlaying) {
            videoView.pause()
            showControls()
        } else {
            videoView.start()
            scheduleHideControls()
        }
        updatePlayPauseButton()
    }

    private fun playNext() {
        if (currentIndex < videoList.size - 1) {
            playVideo(currentIndex + 1)
        }
    }

    /**
     * Updates both the play/pause icon and the button's own instant visibility —
     * it hides the moment playback starts and shows the moment it's paused/ended,
     * with no animation/delay (independent of the top bar's animated 3s auto-hide).
     */
    private fun updatePlayPauseButton() {
        val isPlaying = videoView.isPlaying
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play1)
        btnPlayPause.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }


    private fun showControls() {
        isControlsVisible = true
        controlsLayout.animate().alpha(1f).setDuration(200).start()
        // Instant, no animation — lets the user pause even while a peek-reveal
        // happens mid-playback, independent of the top bar's fade above.
        btnPlayPause.visibility = View.VISIBLE
    }

    private fun hideControls() {
        isControlsVisible = false
        controlsLayout.animate().alpha(0f).setDuration(300).start()
        hideSystemUI()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (videoView.isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3000)
        }
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, min, sec)
        else String.format("%d:%02d", min, sec)
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) videoView.pause()
        updatePlayPauseButton()
        handler.removeCallbacks(seekBarUpdater)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        handler.post(seekBarUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }
}