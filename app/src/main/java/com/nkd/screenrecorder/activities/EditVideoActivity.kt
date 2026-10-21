package com.nkd.screenrecorder.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.adapter.StickerPagerAdapter
import com.nkd.screenrecorder.adapter.TextViewFontAdapter
import com.nkd.screenrecorder.adapter.VideoEditOptionAdapter
import com.nkd.screenrecorder.adapter.VideoTrimmerAdapter
import com.nkd.screenrecorder.databinding.ActivityEditVideoBinding
import com.nkd.screenrecorder.databinding.BottomSheetDialogTextviewFontBinding
import com.nkd.screenrecorder.databinding.ItemOverlayBinding
import com.nkd.screenrecorder.databinding.TimelineTextClipBinding
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.fragments.VideoEditOption
import com.nkd.screenrecorder.model.StickerBurnData
import com.nkd.screenrecorder.utils.CropRatioSelectorHelper
import com.nkd.screenrecorder.utils.DraftEntry
import com.nkd.screenrecorder.utils.DraftManager
import com.nkd.screenrecorder.utils.EmojiData
import com.nkd.screenrecorder.utils.TextFontModel
import com.nkd.screenrecorder.utils.VideoCropper
import com.nkd.screenrecorder.utils.VideoTextBurner
import com.nkd.screenrecorder.utils.VideoTrimmerUtil
import com.nkd.screenrecorder.utils.VoiceRecorder
import com.nkd.screenrecorder.utils.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt

enum class EditorMode { NONE, CROP, TEXT_OVERLAY, STICKER, MUSIC }

class EditVideoActivity : BaseActivity(), MenuProvider {
    private val TAG = EditVideoActivity::class.java.simpleName

    private lateinit var binding: ActivityEditVideoBinding
    private var videoUrl: String = ""
    private var originalVideoUrl: String = ""
    private lateinit var handler: Handler
    private lateinit var updateTimeTask: Runnable
    private lateinit var adapter: VideoEditOptionAdapter
    private val arrayList: ArrayList<VideoEditOption> = ArrayList()
    private var pxPerSecond = 150f
    private lateinit var imm: InputMethodManager
    private var halfWidth: Int = 0
    private lateinit var cropRatioHelper: CropRatioSelectorHelper
    private var currentVideoPath: String = ""
    private var cropProgressDialog: ProgressDialog? = null
    private var textBurnProgressDialog: ProgressDialog? = null
    private var currentMode: EditorMode = EditorMode.NONE
        set(value) {
            field = value
            applyTimelineTouchLock()
            updateTransportControls()
        }


    private fun updateTransportControls() {
        if (!::binding.isInitialized) return
        if (isFullscreen) return
        val show = currentMode != EditorMode.TEXT_OVERLAY
        binding.apply {
            // ✅ isGone: taaki jagah free ho aur video bada ho jaye (InShot/CapCut jaisa)
            appBarLayout.isGone = !show
            btnUndo.isGone = !show
            btnRedo.isGone = !show
            playStopButton.isGone = !show
            btnFullscreen.isGone = !show
            tvCurrentTime.isGone = !show
            tvTotalDuration.isGone = !show
            frameLayoutEditor.isGone = !show
            // container ko naye size par refit karo
            container.post { recalculateVideoViewSize() }
        }
    }

    /** Timeline drag-scroll block. In MUSIC mode it is open while the record panel is
     *  showing (to set the playhead) and locked during countdown/recording. */
    private fun applyTimelineTouchLock() {
        if (!::binding.isInitialized) return
        val lock = voiceBusy ||
                currentMode == EditorMode.STICKER ||
                currentMode == EditorMode.TEXT_OVERLAY
        binding.thumbnailRecyclerView.setOnTouchListener(
            if (lock) View.OnTouchListener { _, _ -> true } else null
        )
    }

    private var imeWasVisible = false
    private var pendingCropApply = false
    private var pendingCropRect: RectF? = null
    /** Non-destructive trim: the active range in ORIGINAL-video time (null = untrimmed).
     *  The editor works in "editor time" = original time − pendingTrimStartMs; the real cut
     *  happens only at export (exportAllEdits). */
    private var pendingTrimStartMs: Long? = null
    private var pendingTrimEndMs: Long? = null

    private var cropMenu: MenuItem? = null
    private var shouldShowCropMenu = true
    private var cropMenuTitle = ""
    private var thumbnailScrollOffset = 0

    // ── Native video dimensions (set in onPrepared) ────────────────────────
    private var videoNativeWidth: Int = 0
    private var videoNativeHeight: Int = 0
    private var videoDurationMsCached: Long = 0L
    private val trackList = mutableListOf<FrameLayout>()
    // ── Text overlay state ─────────────────────────────────────────────────

    /**
     * Holds all mutable state for the active text overlay chip.
     */
    private data class TextOverlayState(
        val overlayBinding: ItemOverlayBinding,
        var selectedFontResId: Int,
        var selectedFontAssetFileName: String = "roboto_serif.ttf",
        /** The dotted-border background saved so we can restore it later. */
        val savedBackground: Drawable?,
        var controlsVisible: Boolean = true,
        val stickerDrawable: Drawable? = null,
        val stickerWidthPx: Int = 0,
        val stickerHeightPx: Int = 0,
        var selectedColorHex: String = "#FFFFFF",
        /** Hex color for the text's background "label" pill, or null for no background. */
        var selectedLabelColorHex: String? = null,
        /** [LabelShape] name, or null = default ROUNDED_RECT. */
        var selectedLabelShape: String? = null,
        /** Label opacity 0–100, or null = 100. */
        var selectedLabelOpacity: Int? = null,
        /** Non-null when the panel is editing an EXISTING text overlay (pencil icon) instead
         *  of creating a new one — hideTextEditorPanel() updates this track in place. */
        val editingExistingTrack: TextTrack? = null
    )
    data class TextOverlayData(
        var text: String,
        var fontResId: Int,
        var fontAssetFileName: String,
        var color: String = "white",
        var labelColor: String? = null,
        var startTimeMs: Long = 0L,
        var endTimeMs: Long = 0L,
        /** [LabelShape] name; null = ROUNDED_RECT (the original label look). */
        var labelShape: String? = null,
        /** 0–100; null = 100. Nullable so old drafts (Gson, field missing) stay opaque. */
        var labelOpacity: Int? = null,
    )

    /** Shapes for a text's background label. ROUNDED_RECT is the original look. */
    private enum class LabelShape {
        NONE, FILLED_RECT, ROUNDED_RECT, PILL, OUTLINE_RECT, OUTLINE_ROUNDED, DASHED_OUTLINE
    }

    data class TextTrack(
        val data: TextOverlayData,
        val overlayBinding: ItemOverlayBinding,
        val clipBinding: TimelineTextClipBinding
    )

    private val textTracks = mutableListOf<TextTrack>()

    data class StickerOverlay(
        var drawable: Drawable,
        var startTimeMs: Long = 0L,
        var endTimeMs: Long = 0L,
        val drawableResId: Int = 0   //
    )

    data class StickerTrack(
        val overlay: StickerOverlay,
        val overlayBinding: ItemOverlayBinding,
        val clipBinding: TimelineTextClipBinding
    )

    private val stickerTracks = mutableListOf<StickerTrack>()

    private var textOverlayState: TextOverlayState? = null
    private var selectedOverlayBinding: ItemOverlayBinding? = null
    private val overlayBorderCache = mutableMapOf<ItemOverlayBinding, Drawable?>()
    private var selectedClipRoot: View? = null
    private lateinit var preference: SharedPreferences
    private val fontList: ArrayList<TextFontModel> = ArrayList()
    private lateinit var fontAdapter: TextViewFontAdapter
    private var totalWidth = 0
    private var activeTextWatcher: android.text.TextWatcher? = null

    data class MusicOverlay(
        val uri: Uri,
        val title: String,
        var durationMs: Long,         // real audio length; 0 = unknown (resolved in addMusicToTimeline)
        var startTimeMs: Long = 0L,   // where in VIDEO the music starts
        var endTimeMs: Long = 0L,     // where in VIDEO the music ends
        var musicOffsetMs: Long = 0L, // trim start from music file itself
        var startPx: Int = 0,
        var endPx: Int = 0,
        val isVoice: Boolean = false,
        var volume: Int = 100,
        var speed: Float = 1f
    )

    data class MusicTrack(
        val overlay: MusicOverlay,
        val clipBinding: TimelineTextClipBinding
    )

    data class MusicBurnData(
        val musicUri: Uri,
        val title: String,
        val videoStartSec: Double,    // when in video music starts playing
        val videoEndSec: Double,      // when in video music stops playing
        val musicOffsetSec: Double,   // trim start from music file itself
        val volume: Int = 100,        // 0..100
        val speed: Float = 1f         // 0.5..2.0
    )

    private val musicTracks = mutableListOf<MusicTrack>()

    // ── Music preview playback ─────────────────────────────────────────────
    private val musicPlayers = mutableListOf<MediaPlayer>()
    private val musicScheduleHandlers = mutableListOf<Handler>()
    private val musicRunnables = mutableListOf<Runnable>()
    private var videoViewVolume = 1f
    private var videoMediaPlayer: MediaPlayer? = null
    private var isVideoMuted = false
    private val musicPickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult<Intent, ActivityResult>(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // ✅ Music menu band NAHI karna — gaana lagake wahin rehna hai
                val uri = result.data?.getParcelableExtra<Uri>("MUSIC_URI")
                    ?: return@registerForActivityResult
                val title = result.data?.getStringExtra("MUSIC_TITLE") ?: "Music"
                val duration = result.data?.getLongExtra("MUSIC_DURATION", 0L) ?: 0L
                addMusicToTimeline(
                    MusicOverlay(
                        uri = uri,
                        title = title,
                        durationMs = duration
                    )
                )
                selectNewestMusicClip()
            }
        }


    private val trimLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val startMs = result.data?.getLongExtra("TRIM_START_MS", -1L) ?: -1L
            val endMs = result.data?.getLongExtra("TRIM_END_MS", -1L) ?: -1L
            if (startMs >= 0 && endMs > startMs) {
                // ✅ Non-destructive: store the range (original-video time), no FFmpeg here
                applyTrimRange(startMs, endMs)
            }
        }
    }
    private val audioPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->

            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // ✅ Music menu band NAHI karna — gaana lagake wahin rehna hai
                addMusicToTimeline(
                    MusicOverlay(uri = it, title = getString(R.string.selected_music), durationMs = 0L)
                )
                selectNewestMusicClip()

            }
        }
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val intent = Intent(this@EditVideoActivity, MusicPickerActivity::class.java)
                musicPickerLauncher.launch(intent)
            } else {
                // Permission denied → fallback to system picker
                openSystemAudioPicker()
            }
        }


    private var stickerTabMediator: TabLayoutMediator? = null

    /** Non-null while the sticker panel was opened from a sticker's pencil icon —
     *  the next pick REPLACES that sticker in place instead of adding a new clip. */
    private var stickerEditTarget: StickerTrack? = null

    private fun addStickerFromPanel(drawable: Drawable, resId: Int = 0) {
        stickerEditTarget?.let { target ->
            replaceSticker(target, drawable)
            closeStickerPanel()
            return
        }
        val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0L)
        val videoDurationMs = safeVideoDurationMs()
        val remainingMs = (videoDurationMs - currentMs).coerceAtLeast(1000L)
        val clipDurationMs = 3000L.coerceAtMost(remainingMs)
        addStickerToTimeline(
            StickerOverlay(
                drawable = drawable,
                startTimeMs = currentMs,
                endTimeMs = (currentMs + clipDurationMs).coerceAtMost(videoDurationMs),
                drawableResId = resId
            )
        )
    }

    private fun closeStickerPanel() {
        stickerEditTarget = null
        binding.editorStickerPanel.stickerEditorPanel.isGone = true
        binding.featureRecyclerView.isVisible = true
        currentMode = EditorMode.NONE
        refreshSaveButtonVisibility()
    }

    /** Pencil icon on a sticker overlay — opens the sticker panel in "replace" mode. */
    private fun editStickerOverlay(track: StickerTrack) {
        stickerEditTarget = track
        if (currentMode == EditorMode.STICKER) return
        overlayStickerView()
    }

    /** Swaps [track]'s image in place — same position, size and timeline clip. */
    private fun replaceSticker(track: StickerTrack, drawable: Drawable) {
        track.overlay.drawable = drawable
        track.overlayBinding.overlayImageView.setImageDrawable(drawable)
        track.clipBinding.sticker.setImageDrawable(
            drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable
        )
        saveEditStateToCache()
    }

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preference = getSharedPreferences(SettingFragment.PREFS_NAME, MODE_PRIVATE)

        addMenuProvider(this, this, Lifecycle.State.RESUMED)

        ViewCompat.setOnApplyWindowInsetsListener(binding.constraint) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, systemBars.top, 0, maxOf(systemBars.bottom, ime.bottom))
            val panel = binding.editorTextPanel.textEditorPanel
            if (ime.bottom > 0) {

                imeWasVisible = true
            } else {
                if (imeWasVisible && panel.isVisible && currentMode == EditorMode.TEXT_OVERLAY) {
                    hideTextEditorPanel()
                }
                imeWasVisible = false
            }
            insets
        }

        videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        originalVideoUrl = videoUrl
        imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        // ✅ Timeline badhe/ghate to video box ka size badle, video turant refit ho
        binding.container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                binding.container.post { recalculateVideoViewSize() }
            }
        }

        binding.editorTextPanel.textEditorPanel.post {
            binding.editorTextPanel.textEditorPanel.translationY =
                binding.editorTextPanel.textEditorPanel.height.toFloat()
        }
        binding.apply {
            setSupportActionBar(toolBar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)   // sirf back arrow
            supportActionBar?.setDisplayShowHomeEnabled(false)  // app icon/logo hatao
            supportActionBar?.setDisplayUseLogoEnabled(false)
            supportActionBar?.setLogo(null)
            supportActionBar?.setDisplayShowTitleEnabled(false) // "NKD-SR" hatao
            supportActionBar?.title = ""
            toolBar.setBackgroundColor(ContextCompat.getColor(this@EditVideoActivity, R.color.editor_inshot_bg))
            appBarLayout.setBackgroundColor(ContextCompat.getColor(this@EditVideoActivity, R.color.editor_inshot_bg))
            toolBar.navigationIcon?.setTint(android.graphics.Color.WHITE)

            videoView.setVideoPath(videoUrl)
            videoView.setOnPreparedListener { mp ->
                videoMediaPlayer = mp
                isVideoMuted = false
                applyVideoVolume()
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)

                mp.setOnSeekCompleteListener {
                    if (!binding.videoView.isPlaying) {
                        binding.videoView.start()
                        binding.videoView.pause()
                    }
                }
                videoNativeWidth = mp.videoWidth
                videoNativeHeight = mp.videoHeight
                videoDurationMsCached = mp.duration.toLong()

                // ✅ VideoView re-prepares after returning from another Activity (e.g. the
                // trimmer). Keep ONE handler so the old progress tick can be removed —
                // otherwise two ticks would scroll the timeline at the same time (jerky).
                if (!::handler.isInitialized) handler = Handler(Looper.getMainLooper())

                updateProgressBar(videoView)
                progressBarPrepare(mp)
                // A re-prepared player starts at 0 — put it back on the playhead inside the trim
                if (trimStart() > 0L) seekEditorTo(pxToTime(thumbnailScrollOffset).coerceAtLeast(0L))
                cropView.setVideoSize(mp.videoWidth, mp.videoHeight)

                // ✅ Timeline replaces the old SeekBar — it must be visible and scroll-seekable
                // as soon as the video is ready, even before any text/sticker/music is added.
                Log.d("TimelineDebug", "onPrepared() mp.duration=${mp.duration}")
                binding.frameLayoutEditor.isVisible = true
                container.doOnLayout { recalculateVideoViewSize() }
                // ✅ Wait for the timeline row to actually be laid out AND the video duration
                // to be reliably readable, then derive halfWidth from the row's real measured
                // width (see initializeTimelineLayout doc below).
                binding.frameLayoutEditor.doOnLayout {
                    // ✅ doOnLayout's callback still runs INSIDE the layout traversal — any
                    // width change + requestLayout() done directly here gets silently dropped
                    // ("requestLayout() improperly called ... during layout"). Post it so it
                    // runs on the next message-queue tick, safely after layout has finished.
                    binding.frameLayoutEditor.post {
                        runWhenVideoDurationKnown {
                            initializeTimelineLayout()
                        }
                    }
                }
            }
            Glide.with(this@EditVideoActivity).asBitmap().load(videoUrl).frame(1000000)
                .into(thumbnail)
            videoView.setOnCompletionListener {
                onPlaybackReachedEnd()
            }
            playStopButton.setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_play1, null)
                    )

                    stopMusicPreview()
                } else {
                    thumbnail.isGone = true


                    val isAtEnd = editorPosMs() >= safeVideoDurationMs() - 200
                    if (isAtEnd) {
                        seekEditorTo(0L)
                        if (binding.thumbnailRecyclerView.isVisible) {
                            binding.thumbnailRecyclerView.scrollBy(-thumbnailScrollOffset, 0)

                        }
                        updateAllStickerVisibility(0L)
                        updateAllTextVisibility(0L)
                    } else if (videoView.currentPosition < trimStart()) {
                        // Player sits before the trim start (e.g. just re-prepared) — start at the playhead
                        seekEditorTo(pxToTime(thumbnailScrollOffset).coerceAtLeast(0L))
                    }

                    videoView.start()
                    playStopButton.setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_pause, null)
                    )
                    setOverlayControlsVisible(false)

                    startMusicPreview()
                }
            }
        }
        arrayList.add(VideoEditOption(getString(R.string.crop_video), R.drawable.ic_crop_new))
        arrayList.add(VideoEditOption(getString(R.string.trim_short), R.drawable.ic_trim_scissors))
        arrayList.add(VideoEditOption(getString(R.string.text_label), R.drawable.ic_text))
        arrayList.add(VideoEditOption(getString(R.string.sticker_label), R.drawable.ic_sticker_smiley))
        arrayList.add(VideoEditOption(getString(R.string.music), R.drawable.ic_music))
        arrayList.add(VideoEditOption(getString(R.string.gif_short), R.drawable.ic_video_to_gif))

        adapter = VideoEditOptionAdapter(arrayList)
        binding.featureRecyclerView.adapter = adapter
        adapter.onItemClick = { position ->
            when (position) {
                0 -> enterCropMode()
                1 -> {
                    // Opens on the ORIGINAL video with the current range preselected
                    val trimIntent = Intent(this, VideoTrimmingActivity::class.java).putExtra("Url", videoUrl)
                    val s = pendingTrimStartMs
                    val e = pendingTrimEndMs
                    if (s != null && e != null) {
                        trimIntent.putExtra(VideoTrimmingActivity.EXTRA_INITIAL_START_MS, s)
                        trimIntent.putExtra(VideoTrimmingActivity.EXTRA_INITIAL_END_MS, e)
                    }
                    trimLauncher.launch(trimIntent)
                }

                2 -> overLayTextView()
                3 -> {
                    stickerEditTarget = null
                    overlayStickerView()
                }

                4 -> {
                    showMusicSheet()
                }

                5 -> {
                    val intent = Intent(this, VideoTrimmingActivity::class.java)
                    intent.putExtra("Url", videoUrl)
                    intent.putExtra("isVideoToGIF", true)
                    startActivity(intent)
                }
            }
        }

        cropRatioHelper = CropRatioSelectorHelper(binding)
        cropMenuTitle = getString(R.string.save)

        binding.btnFullscreen.setOnClickListener { enterFullscreen() }
        binding.btnExitFullscreen.setOnClickListener { exitFullscreen() }
        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }


        listOf(binding.btnUndo, binding.btnRedo, binding.btnFullscreen, binding.btnExitFullscreen).forEach { btn ->
            btn.layoutParams = btn.layoutParams.apply {
                width = 32.dpToPx()
                height = 32.dpToPx()
            }
            btn.setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
            (btn as? android.widget.ImageView)?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        refreshUndoRedoButtons()

        // ── OK / Confirm button ────────────────────────────────────────────
        binding.okTextOverlay.setOnClickListener {
            when (currentMode) {
                EditorMode.CROP -> {
                    val normRect = binding.cropView.getNormalisedCropRect()
                    cropRatioHelper.hide()
                    binding.cropView.visibility = View.GONE
                    pendingCropApply = true
                    pendingCropRect = normRect
                    currentMode = EditorMode.NONE
                    binding.okTextOverlay.visibility = View.GONE


                    binding.videoView.post {
                        applyCropPreview(normRect)
                    }
                    saveEditStateToCache()   //
                }

                EditorMode.TEXT_OVERLAY -> {
                    imm.hideSoftInputFromWindow(binding.editorTextPanel.enterTextEditText.windowToken, 0)
                    hideTextEditorPanel()
                }

                EditorMode.STICKER -> {

                }

                EditorMode.MUSIC -> {

                }

                EditorMode.NONE -> { /* no-op */
                }
            }
            currentMode = EditorMode.NONE
            binding.okTextOverlay.visibility = View.GONE
            refreshSaveButtonVisibility()
        }
        // ✅ Text-panel tick (right end of the tab row) — same as okTextOverlay's TEXT_OVERLAY case
        binding.editorTextPanel.btnTextDone.setOnClickListener {
            imm.hideSoftInputFromWindow(binding.editorTextPanel.enterTextEditText.windowToken, 0)
            hideTextEditorPanel()
        }
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // ✅ Exporting → same as ✕ on the "Converting Video" screen
                if (isExportActive) { confirmStopExport(); return }
                if (isFullscreen) { exitFullscreen(); return }
                if (voiceBusy) { cancelVoiceRecording(); return }
                // ✅ Record panel → back to the audio menu; audio menu → back to the icons row
                if (binding.recordPanel.root.isVisible) {
                    hideRecordPanel()
                    return
                }
                if (currentMode == EditorMode.MUSIC) {
                    hideAudioMenu()
                    return
                }

                if (cropRatioHelper.isVisible) {
                    cropRatioHelper.hide()
                    binding.cropView.visibility = View.GONE
                    binding.okTextOverlay.visibility = View.GONE
                    return
                }


                if (hasUnsavedChanges()) {
                    showSaveOrDiscardDialog()
                    return
                }


                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }


    // ── Fullscreen preview ─────────────────────────────────────────────────
    private var isFullscreen = false

    private fun enterFullscreen() {
        if (isFullscreen) return
        if (currentMode != EditorMode.NONE || voiceBusy) return
        isFullscreen = true
        clearOverlaySelection()

        binding.apply {
            appBarLayout.isGone = true
            featureRecyclerView.isGone = true
            frameLayoutEditor.isGone = true
            tvCurrentTime.isGone = true
            tvTotalDuration.isGone = true
            playStopButton.isGone = true
            btnFullscreen.isGone = true
            btnUndo.isGone = true
            btnRedo.isGone = true
            btnExitFullscreen.isVisible = true
        }

        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (!binding.videoView.isPlaying) binding.playStopButton.performClick()
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false

        binding.apply {
            appBarLayout.isVisible = true
            featureRecyclerView.isVisible = true
            frameLayoutEditor.isVisible = true
            tvCurrentTime.isVisible = true
            tvTotalDuration.isVisible = true
            playStopButton.isVisible = true
            btnFullscreen.isVisible = true
            btnUndo.isVisible = true
            btnRedo.isVisible = true
            btnExitFullscreen.isGone = true
        }

        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        val pos = editorPosMs()
        binding.thumbnailRecyclerView.post {
            binding.thumbnailRecyclerView.scrollBy(timeToPx(pos) - thumbnailScrollOffset, 0)
            syncTimelineTranslation()
        }
    }

    // ── Undo / Redo ────────────────────────────────────────────────────────
    private class UndoAction(val undo: () -> Unit, val redo: () -> Unit)

    private val undoStack = ArrayList<UndoAction>()
    private val redoStack = ArrayList<UndoAction>()
    private var isUndoRedoRunning = false

    private fun pushUndo(action: UndoAction) {
        if (isUndoRedoRunning) return
        undoStack.add(action)
        redoStack.clear()
        refreshUndoRedoButtons()
    }

    private fun performUndo() {
        if (voiceBusy || isFullscreen || currentMode == EditorMode.TEXT_OVERLAY ||
            currentMode == EditorMode.STICKER || currentMode == EditorMode.CROP) return
        val action = undoStack.removeLastOrNull() ?: return
        isUndoRedoRunning = true
        try { action.undo() } finally { isUndoRedoRunning = false }
        redoStack.add(action)
        refreshUndoRedoButtons()
    }

    private fun performRedo() {
        if (voiceBusy || isFullscreen || currentMode == EditorMode.TEXT_OVERLAY ||
            currentMode == EditorMode.STICKER || currentMode == EditorMode.CROP) return
        val action = redoStack.removeLastOrNull() ?: return
        isUndoRedoRunning = true
        try { action.redo() } finally { isUndoRedoRunning = false }
        undoStack.add(action)
        refreshUndoRedoButtons()
    }

    private fun refreshUndoRedoButtons() {
        if (!::binding.isInitialized) return
        binding.btnUndo.isEnabled = undoStack.isNotEmpty()
        binding.btnUndo.alpha = if (undoStack.isNotEmpty()) 1f else 0.3f
        binding.btnRedo.isEnabled = redoStack.isNotEmpty()
        binding.btnRedo.alpha = if (redoStack.isNotEmpty()) 1f else 0.3f
    }

    private fun hasUnsavedChanges(): Boolean {
        val hasTrim = pendingTrimStartMs != null && pendingTrimEndMs != null
        val videoChanged = videoUrl != originalVideoUrl
        return hasTrim || pendingCropApply || videoChanged || videoVolumePercent != 100 ||
                textTracks.isNotEmpty() || stickerTracks.isNotEmpty() || musicTracks.isNotEmpty()
    }


    private fun showSaveOrDiscardDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exit_before_saving_title))
            .setPositiveButton(getString(R.string.save_as_draft)) { dialog, _ ->
                dialog.dismiss()
                saveAsDraftAndExit()
            }
            .setNegativeButton(getString(R.string.exit_directly)) { dialog, _ ->
                dialog.dismiss()
                clearEditStateCache()
                deleteAllVoiceFiles()
                if (videoUrl != originalVideoUrl && videoUrl.startsWith(cacheDir.absolutePath)) {
                    File(videoUrl).delete()
                }
                finish()
            }
            .setCancelable(true)
            .show()
    }


    fun enterTimelineMode() {
        // ✅ Rows are shared across clip types now — size the preview by actual
        // visible row count, not raw clip count (multiple clips can share a row).
        val trackCount = trackList.size.coerceAtLeast(1)
        val percent = when {
            trackCount >= 3 -> 0.35f
            trackCount == 2 -> 0.38f
            else -> 0.42f   // ✅ never too small
        }
        Log.e(TAG, "454 ${percent}")
        resizeVideoContainer(percent)

    }

    fun exitTimelineMode() {
        resizeVideoContainer(0.45f)
    }

    fun resizeVideoContainer(percent: Float) {
        // ✅ Video box ab XML se bachi hui jagah khud leta hai — sirf video refit
        binding.container.post {
            recalculateVideoViewSize()
        }
    }


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

    private fun applyCropPreview(normRect: RectF?) {
        if (normRect == null) {
            binding.videoView.scaleX = 1f
            binding.videoView.scaleY = 1f
            binding.videoView.translationX = 0f
            binding.videoView.translationY = 0f
            binding.thumbnail.scaleX = 1f
            binding.thumbnail.scaleY = 1f
            binding.thumbnail.translationX = 0f
            binding.thumbnail.translationY = 0f
            return
        }
        val cropW = normRect.width().coerceAtLeast(0.01f)
        val cropH = normRect.height().coerceAtLeast(0.01f)
        val scaleX = 1f / cropW
        val scaleY = 1f / cropH


        binding.videoView.pivotX = 0f
        binding.videoView.pivotY = 0f
        binding.videoView.scaleX = scaleX
        binding.videoView.scaleY = scaleY
        val videoW = binding.videoView.width.toFloat()
        val videoH = binding.videoView.height.toFloat()
        binding.videoView.translationX = -(normRect.left * videoW * scaleX)
        binding.videoView.translationY = -(normRect.top * videoH * scaleY)


        binding.thumbnail.pivotX = 0f
        binding.thumbnail.pivotY = 0f
        binding.thumbnail.scaleX = scaleX
        binding.thumbnail.scaleY = scaleY
        val thumbW = binding.thumbnail.width.toFloat()
        val thumbH = binding.thumbnail.height.toFloat()
        binding.thumbnail.translationX = -(normRect.left * thumbW * scaleX)
        binding.thumbnail.translationY = -(normRect.top * thumbH * scaleY)

        binding.container.clipChildren = true
        binding.container.clipToPadding = true
    }


    private fun setOverlayControlsVisible(visible: Boolean) {

        textOverlayState?.let { state ->
            if (state.controlsVisible != visible) {
                state.controlsVisible = visible
                state.overlayBinding.apply {
                    closeContainer.isVisible = visible
                    resizeContainer.isVisible = visible
                    editContainer.isVisible = visible
                    overlayRoot.background = if (visible) state.savedBackground else null
                }
            }
        }


        for (track in textTracks) {
            track.overlayBinding.apply {
                closeContainer.isVisible = visible
                resizeContainer.isVisible = visible
                editContainer.isVisible = visible
                overlayRoot.background = if (visible) overlayBorderCache[track.overlayBinding] else null
            }
        }


        for (track in stickerTracks) {
            track.overlayBinding.apply {
                closeContainer.isVisible = visible
                resizeContainer.isVisible = visible
                editContainer.isVisible = visible
                overlayRoot.background = if (visible) overlayBorderCache[track.overlayBinding] else null
            }
        }
    }


    /** Rounded-corner (4dp) fill for a timeline clip in its type color. */
    private fun clipBackground(colorRes: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 4.dpToPx().toFloat()
            setColor(ContextCompat.getColor(this@EditVideoActivity, colorRes))
        }

    /** Shows/hides the left/right trim handles and the white selection border on [clipRoot]. */
    private fun setClipSelected(clipRoot: View, selected: Boolean) {
        clipRoot.findViewById<View>(R.id.leftHandle)?.isVisible = selected
        clipRoot.findViewById<View>(R.id.rightHandle)?.isVisible = selected
        (clipRoot.background as? GradientDrawable)?.setStroke(
            if (selected) 2.dpToPx() else 0,
            "#FFFFFFFF".toColorInt()
        )
    }

    /** Selects a timeline clip — handles+border only, no video-preview overlay controls.
     *  Used directly by music clips (no video overlay exists for audio). */
    private fun selectClip(clipRoot: View) {
        if (selectedClipRoot === clipRoot) return
        selectedClipRoot?.let { setClipSelected(it, false) }
        setClipSelected(clipRoot, true)
        selectedClipRoot = clipRoot
    }

    private fun selectOverlayForEditing(overlayBinding: ItemOverlayBinding, clipRoot: View, startTimeMs: Long) {
        if (binding.videoView.isPlaying) return

        for (t in textTracks) {
            if (t.overlayBinding !== overlayBinding) {
                t.overlayBinding.closeContainer.isVisible = false
                t.overlayBinding.resizeContainer.isVisible = false
                t.overlayBinding.editContainer.isVisible = false
            }
        }
        for (t in stickerTracks) {
            if (t.overlayBinding !== overlayBinding) {
                t.overlayBinding.closeContainer.isVisible = false
                t.overlayBinding.resizeContainer.isVisible = false
                t.overlayBinding.editContainer.isVisible = false
            }
        }

        overlayBinding.closeContainer.isVisible = true
        overlayBinding.resizeContainer.isVisible = true
        overlayBinding.editContainer.isVisible = true
        selectedOverlayBinding = overlayBinding

        hideClipEditBar()
        selectClip(clipRoot)

        val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
        val videoDurationMs = safeVideoDurationMs()
        if (currentMs < startTimeMs || currentMs > startTimeMs + 50) {
            val targetPx = timeToPx(startTimeMs.coerceAtMost(videoDurationMs))
            binding.thumbnailRecyclerView.scrollBy(targetPx - thumbnailScrollOffset, 0)
        }
    }


    private fun clearOverlaySelection() {
        hideClipEditBar()
        selectedClipRoot?.let { setClipSelected(it, false) }
        selectedClipRoot = null
        selectedOverlayBinding = null
    }


    private fun showTextEditorPanel() {
        val panel = binding.editorTextPanel.textEditorPanel
        val sheet = binding.bottomSheetView


        sheet.bringToFront()
        sheet.elevation = 24f.dpToPx()
        panel.bringToFront()
        panel.elevation = 24f.dpToPx()
        sheet.requestLayout()

        panel.visibility = View.VISIBLE
        panel.animate().translationY(0f).setInterpolator(DecelerateInterpolator()).setDuration(250)
            .start()


        val et = binding.editorTextPanel.enterTextEditText
        et.post {
            et.requestFocus()
            androidx.core.view.WindowCompat.getInsetsController(window, et)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideTextEditorPanel() {
        removeTextFitListener()
        // ✅ Layout badalne se PEHLE position margin mein lock karo
        textOverlayState?.overlayBinding?.root?.let { normalizeOverlayPosition(it) }
        currentMode = EditorMode.NONE
        updateTransportControls()
        val panel = binding.editorTextPanel.textEditorPanel

        panel.animate().cancel()
        panel.translationY = panel.height.toFloat()
        panel.visibility = View.GONE
        binding.featureRecyclerView.isVisible = true

        enterTimelineMode()

        binding.okTextOverlay.isVisible = false
        binding.frameLayoutEditor.isVisible = true

        val state = textOverlayState ?: return

        // ── Editing an EXISTING text overlay (pencil icon) — update in place, no new clip ──
        val editingTrack = state.editingExistingTrack
        if (editingTrack != null) {
            val editedText = binding.editorTextPanel.enterTextEditText.text?.toString()
                ?.takeIf { it.isNotBlank() && it != getString(R.string.enter_text) }
            if (editedText != null) {
                editingTrack.data.text = editedText
                editingTrack.data.fontResId = state.selectedFontResId
                editingTrack.data.fontAssetFileName = state.selectedFontAssetFileName
                editingTrack.data.color = state.selectedColorHex
                editingTrack.data.labelColor = state.selectedLabelColorHex
                editingTrack.data.labelShape = state.selectedLabelShape
                editingTrack.data.labelOpacity = state.selectedLabelOpacity

                editingTrack.overlayBinding.overlayText.text = editedText
                editingTrack.overlayBinding.overlayText.setTextColor(state.selectedColorHex.toColorInt())
                editingTrack.overlayBinding.overlayText.typeface =
                    ResourcesCompat.getFont(this, state.selectedFontResId)
                applyLabelBackground(
                    editingTrack.overlayBinding.overlayText, state.selectedLabelColorHex,
                    state.selectedLabelShape, state.selectedLabelOpacity
                )

                editingTrack.clipBinding.clipLabel.text = editedText

                saveEditStateToCache()
            } else {
                // ✅ Cleared to blank, or cancelled/backed out — the live preview may already
                // show mid-edit text/color/font/label changes; restore exactly what's saved so
                // the overlay never shows the "Enter text" placeholder or an uncommitted change.
                editingTrack.overlayBinding.overlayText.text = editingTrack.data.text
                editingTrack.overlayBinding.overlayText.setTextColor(editingTrack.data.color.toColorInt())
                editingTrack.overlayBinding.overlayText.typeface =
                    ResourcesCompat.getFont(this, editingTrack.data.fontResId)
                applyLabelBackground(
                    editingTrack.overlayBinding.overlayText, editingTrack.data.labelColor,
                    editingTrack.data.labelShape, editingTrack.data.labelOpacity
                )
            }
            textOverlayState = null
            return
        }

        val text = binding.editorTextPanel.enterTextEditText.text?.toString()
            ?.takeIf { it.isNotBlank() && it != getString(R.string.enter_text) } ?: return
        val assetFileName = state.selectedFontAssetFileName


        val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0L)
        val videoDurationMs = safeVideoDurationMs()
        val remainingMs = (videoDurationMs - currentMs).coerceAtLeast(1000L)
        // ✅ New clips default to 3s (or less if that would run past the video end)
        val clipDurationMs = 3000L.coerceAtMost(remainingMs)

        val textData = TextOverlayData(
            text = text,
            fontResId = state.selectedFontResId,
            fontAssetFileName = assetFileName,
            color = state.selectedColorHex,
            labelColor = state.selectedLabelColorHex,
            startTimeMs = currentMs,
            endTimeMs = (currentMs + clipDurationMs).coerceAtMost(videoDurationMs),
            labelShape = state.selectedLabelShape,
            labelOpacity = state.selectedLabelOpacity
        )

        addTextToTimeline(state.overlayBinding, textData)
        textOverlayState = null
    }

    private fun addTextToTimeline(
        overlayBinding: ItemOverlayBinding,
        textData: TextOverlayData,
        resizeAfter: Boolean = true
    ) {

        if (!overlayBorderCache.containsKey(overlayBinding)) {
            overlayBorderCache[overlayBinding] = overlayBinding.overlayRoot.background
        }

        // ✅ Same source as initializeTimelineLayout() — thumbnailRecyclerView's real width,
        // never a screen-metrics guess, so this can never drift from thumbnails/ruler.
        if (halfWidth == 0 && binding.thumbnailRecyclerView.width > 0) {
            halfWidth = binding.thumbnailRecyclerView.width / 2
        }

        if (totalWidth == 0) {

            binding.container.post {
                addTextToTimeline(overlayBinding, textData, resizeAfter)
            }
            return
        }

        // ✅ Never let a clip (new OR restored from a draft) sit before t=0 or after
        // video end — this is what keeps it from being drawn left of the first thumbnail.
        val (clampedStart, clampedEnd) = clampClipRange(textData.startTimeMs, textData.endTimeMs)
        textData.startTimeMs = clampedStart
        textData.endTimeMs = clampedEnd

        // ── Timeline clip ──────────────────────────────────────────────────
        val track = findOrCreateRowForClip(textData.startTimeMs, textData.endTimeMs)
        val clipBinding = TimelineTextClipBinding.inflate(layoutInflater)

        val duration = textData.endTimeMs - textData.startTimeMs
        val clipWidth = timeToPx(duration)

        val params = FrameLayout.LayoutParams(clipWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = halfWidth + timeToPx(textData.startTimeMs)   // ✅ actual start time se position
        }
        clipBinding.clipRoot.layoutParams = params
        clipBinding.clipLabel.text = textData.text
        clipBinding.clipRoot.background = clipBackground(R.color.text_track_color)
        track.addView(clipBinding.root)
        Log.d(
            "TimelineDebug",
            "add type=TEXT startTimeMs=${textData.startTimeMs} endTimeMs=${textData.endTimeMs} row=${trackList.indexOf(track)}"
        )

        // ── Overlay on video ───────────────────────────────────────────────
        binding.overlayContainer.isVisible = true
        // Only add if not already attached
        if (overlayBinding.root.parent == null) {
            binding.overlayContainer.addView(overlayBinding.root)
        }

        // ── Initial visibility ─────────────────────────────────────────────
        val currentMs = pxToTime(
            binding.thumbnailRecyclerView.computeHorizontalScrollOffset().coerceAtLeast(0)
        )
        overlayBinding.root.isVisible = currentMs in textData.startTimeMs..textData.endTimeMs

        // ── Store track ────────────────────────────────────────────────────
        val textTrack = TextTrack(textData, overlayBinding, clipBinding)
        textTracks.add(textTrack)

        // ── Edit (pencil) button — text overlays only ───────────────────────
        overlayBinding.editContainer.isVisible = true
        overlayBinding.editContainer.setOnClickListener {
            editTextOverlay(textTrack)
        }

        // ── Close button ───────────────────────────────────────────────────
        overlayBinding.closeContainer.setOnClickListener {
            if (selectedOverlayBinding === overlayBinding) clearOverlaySelection()

            if (!isUndoRedoRunning) pushUndo(UndoAction(
                undo = { addTextToTimeline(overlayBinding, textTrack.data) },
                redo = { textTracks.firstOrNull { it.overlayBinding === overlayBinding }?.overlayBinding?.closeContainer?.performClick() }
            ))

            //  Remove overlay from video
            binding.overlayContainer.removeView(overlayBinding.root)

            //  Remove timeline clip (and its row, if now empty)
            removeClipAndRowIfEmpty(clipBinding.clipRoot)

            //  Remove from data list
            textTracks.removeAll { it === textTrack }
            overlayBorderCache.remove(overlayBinding)

            //  Update menu visibility
            refreshSaveButtonVisibility()
            saveEditStateToCache()   //

            //  Resize video container now that a track row may be gone
            if (trackList.isEmpty()) {
                exitTimelineMode()
            } else {
                enterTimelineMode()
            }

            //  Re-apply translation after track removal re-layout
            binding.overlayTrackContainer.post {
                syncTimelineTranslation()
            }
        }

        // ── Thumbnails (only setup once) ───────────────────────────────────
        /*  if (binding.thumbnailRecyclerView.adapter == null) {
              val thumbCount = thumbnailCount()
              val mVideoThumbAdapter = VideoTrimmerAdapter(ArrayList())

              mVideoThumbAdapter.setItemSize(thumbnailItemWidth(), thumbnailItemWidth())

              binding.thumbnailRecyclerView.setAdapter(mVideoThumbAdapter)
              binding.thumbnailRecyclerView.apply {
                  clipToPadding = false
                  setPadding(halfWidth, 0, halfWidth, 0)
              }

              // ✅ Dynamic count instead of fixed 10
              VideoTrimmerUtil.shootVideoThumbInBackground(
                  this@EditVideoActivity,
                  videoUrl.toUri(),
                  thumbCount,
                  0,
                  binding.videoView.duration.toLong()
              ) { bitmap, _ -> mVideoThumbAdapter.addBitmaps(bitmap) }
          }*/
        binding.thumbnailRecyclerView.post {
            // ✅ post{} ensures RecyclerView height is measured before we read it
            setupThumbnails()
        }

        setupTimelineScrollSync()



        setupClipDraggingForText(clipBinding, textTrack)
        if (resizeAfter) pushUndo(UndoAction(
            undo = { textTracks.firstOrNull { it.overlayBinding === overlayBinding }?.overlayBinding?.closeContainer?.performClick() },
            redo = { addTextToTimeline(overlayBinding, textData) }
        ))
        if (resizeAfter) enterTimelineMode()   //
        binding.overlayTrackContainer.post {
            syncTimelineTranslation()
        }
        if (resizeAfter) saveEditStateToCache()
    }

    /**
     * VideoView.getDuration() can still report <=0 for a frame or two right after
     * onPrepared() fires, even though the underlying MediaPlayer is already prepared
     * (VideoView tracks its own internal state separately). Computing totalWidth from
     * that stale/zero duration is what left thumbnails/ruler empty at startup — they'd
     * only get set up correctly later, once totalWidth was recomputed by chance (e.g. on
     * reloadVideo() after a trim). This retries one frame at a time until the duration is
     * actually available, then runs [action] — same retry-via-post pattern already used
     * elsewhere in this file for totalWidth==0 guards.
     */
    private fun runWhenVideoDurationKnown(action: () -> Unit) {
        val d = binding.videoView.duration
        Log.d("TimelineDebug", "runWhenVideoDurationKnown check videoView.duration=$d")
        if (d > 0) {
            action()
        } else {
            binding.frameLayoutEditor.post { runWhenVideoDurationKnown(action) }
        }
    }

    /**
     * halfWidth/totalWidth/thumbnails/ruler setup shared by both the initial video-open path
     * (initializeTimelineLayout) and the post-trim reloadVideo() path.
     *
     * halfWidth is derived from thumbnailRecyclerView's real measured width instead of a
     * screen-metrics guess (windowManager.defaultDisplay) — thumbnails, the ruler and the
     * clip rows all sit under identical 8dp side margins, so they share the exact same
     * width, and therefore the exact same halfWidth/origin. That's what previously drifted:
     * halfWidth was computed from displayMetrics.widthPixels (the raw device width) instead
     * of the actual (slightly narrower, margin-adjusted) width of the row it needs to match.
     * Caller must ensure thumbnailRecyclerView has already been laid out (non-zero width).
     */
    private fun setupTimelineCore() {
        recalculatePxPerSecond()
        halfWidth = (binding.thumbnailRecyclerView.width / 2).coerceAtLeast(1)
        val totalDuration = safeVideoDurationMs()   // editor (trimmed) length
        totalWidth = timeToPx(totalDuration)
        binding.overlayTrackContainer.layoutParams.width = totalWidth + halfWidth * 2
        binding.overlayTrackContainer.requestLayout()
        binding.overlayTrackContainer.invalidate()

        Log.d(
            "TimelineDebug",
            "setupTimelineCore CALLED totalDuration=$totalDuration totalWidth=$totalWidth " +
                    "halfWidth=$halfWidth pxPerSecond=$pxPerSecond " +
                    "thumbRvWidth=${binding.thumbnailRecyclerView.width} " +
                    "thumbRvHeight=${binding.thumbnailRecyclerView.height} " +
                    "thumbRvVisibility=${binding.thumbnailRecyclerView.visibility} " +
                    "frameLayoutEditorVisibility=${binding.frameLayoutEditor.visibility}"
        )

        setupThumbnails()
        setupTimeRuler()
        setupTimelineScrollSync()
        setupTimelineTapAndVerticalScroll()
        setupThumbnailTapSelect()

        binding.thumbnailRecyclerView.post {
            // ✅ Bitmaps stream in asynchronously now (Dispatchers.IO), so adapter item
            // count here is only a snapshot, not final — see "thumbnails applied" log below
            // for the authoritative main-thread count once extraction completes.
            Log.d(
                "TimelineDebug",
                "setupTimelineCore AFTER-POST thumbRvWidth=${binding.thumbnailRecyclerView.width} " +
                        "rulerChildCount=${binding.rulerContainer.childCount}"
            )
        }
    }

    /** Single reliable entry point for the initial video open, called once the timeline row
     *  has ACTUALLY been laid out (frameLayoutEditor.doOnLayout). See setupTimelineCore(). */
    private fun initializeTimelineLayout() {
        // ✅ The draft's trim range must be known BEFORE the timeline is built, so the
        // timeline/thumbnails/ruler are created at the trimmed length from the start.
        if (isResumingDraft) loadDraftTrimRange()

        setupTimelineCore()

        if (isResumingDraft) {
            restoreEditStateFromCache()
        }
        if (trimStart() > 0L) {
            refreshPreviewThumbnail()
            seekEditorTo(0L)
        }

        logTimelineAlignmentDebug()
    }

    /** Logs the on-screen x of thumbnail0's left edge, the "00:00" ruler tick, and where a
     *  0ms clip would render — these must all be equal. Requested for verifying BUG 1. */
    private fun logTimelineAlignmentDebug() {
        val thumbLoc = IntArray(2)
        binding.thumbnailRecyclerView.getLocationOnScreen(thumbLoc)
        val thumbStartX = thumbLoc[0] + binding.thumbnailRecyclerView.paddingLeft

        val rulerLoc = IntArray(2)
        binding.rulerContainer.getLocationOnScreen(rulerLoc)
        val rulerZeroX = rulerLoc[0] + halfWidth

        val rowLoc = IntArray(2)
        binding.overlayTrackContainer.getLocationOnScreen(rowLoc)
        val clipZeroX = rowLoc[0] + halfWidth

        Log.d(
            "TimelineDebug",
            "ALIGN CHECK thumbStartX=$thumbStartX rulerZeroX=$rulerZeroX clipZeroX=$clipZeroX " +
                    "halfWidth=$halfWidth thumbWidth=${binding.thumbnailRecyclerView.width} " +
                    "thumbPaddingLeft=${binding.thumbnailRecyclerView.paddingLeft}"
        )
    }

    private fun setupThumbnails() {
        if (binding.thumbnailRecyclerView.adapter != null) {
            Log.d("TimelineDebug", "setupThumbnails EARLY-RETURN adapter already set")
            return
        }
        // ✅ Wait until totalWidth (the same value the ruler and clip rows are
        // positioned against) is known, so the thumbnail strip's content width can
        // be made to match it exactly — video end = right edge of the last thumbnail.
        if (totalWidth <= 0) {
            Log.d("TimelineDebug", "setupThumbnails EARLY-RETURN totalWidth<=0 totalWidth=$totalWidth")
            return
        }

        val thumbH = binding.thumbnailRecyclerView.height.takeIf { it > 0 }
            ?: 56.dpToPx()

        val targetThumbW = 60.dpToPx()
        val thumbCount = (totalWidth / targetThumbW).coerceIn(5, 50)
        // ✅ thumbCount * thumbW == totalWidth exactly, so the thumbnail strip's
        // right edge lines up with the ruler's/clip rows' right edge regardless of
        // video length (targetThumbW is only a starting point for choosing count).
        val thumbW = (totalWidth / thumbCount).coerceAtLeast(1)

        Log.d(
            "TimelineDebug",
            "setupThumbnails PROCEEDING totalWidth=$totalWidth thumbCount=$thumbCount thumbW=$thumbW thumbH=$thumbH " +
                    "thumbRvWidth=${binding.thumbnailRecyclerView.width}"
        )

        VideoTrimmerUtil.THUMB_WIDTH = thumbW

        val mVideoThumbAdapter = VideoTrimmerAdapter(ArrayList())

        mVideoThumbAdapter.setItemSize(thumbW, thumbH)

        binding.thumbnailRecyclerView.setAdapter(mVideoThumbAdapter)
        binding.thumbnailRecyclerView.apply {
            clipToPadding = false
            setPadding(halfWidth, 0, halfWidth, 0)
        }

        // ✅ Thumbnails only from the trimmed range [trimStart, trimEnd] of the original video
        val shootStartPosition = trimStart()
        val shootEndPosition = shootStartPosition + safeVideoDurationMs()
        Log.d(
            "TimelineDebug",
            "setupThumbnails shootVideoThumbInBackground CALL thumbCount=$thumbCount shootEndPosition=$shootEndPosition"
        )
        // ✅ MediaMetadataRetriever.getFrameAtTime() is a blocking decode call — run it on
        // Dispatchers.IO (same pattern already used in VideoMergerFragment.loadAllVideoThumbnails).
        // Bitmaps are collected on the IO thread ONLY — the adapter/view are never touched
        // from a background thread; they're all applied together in one withContext(Main)
        // hop once extraction is done, tied to lifecycleScope so it's cancelled if the
        // activity closes mid-extraction.
        lifecycleScope.launch(Dispatchers.IO) {
            val shotBitmaps = mutableListOf<Bitmap>()
            VideoTrimmerUtil.shootVideoThumbInBackground(
                this@EditVideoActivity,
                videoUrl.toUri(),
                thumbCount,
                shootStartPosition,
                shootEndPosition
            ) { bitmap, _ -> shotBitmaps.add(bitmap) }

            Log.d(
                "TimelineDebug",
                "setupThumbnails shootVideoThumbInBackground RETURNED shotCount=${shotBitmaps.size} " +
                        "thread=${Thread.currentThread().name}"
            )

            withContext(Dispatchers.Main) {
                shotBitmaps.forEach { mVideoThumbAdapter.addBitmaps(it) }
                binding.thumbnailRecyclerView.requestLayout()
                binding.thumbnailRecyclerView.invalidate()
                Log.d(
                    "TimelineDebug",
                    "thumbnails applied adapterItemCount=${mVideoThumbAdapter.itemCount} thread=main"
                )
            }
        }
    }


    private fun recalculatePxPerSecond() {
        val durationSec = safeVideoDurationMs() / 1000f   // editor (trimmed) length
        if (durationSec <= 0f) return

        val screenWidth = resources.displayMetrics.widthPixels


        val targetTotalWidth = when {
            durationSec <= 30f -> screenWidth * 2      // short: 2 screens wide
            durationSec <= 120f -> screenWidth * 3      // medium: 3 screens wide
            durationSec <= 600f -> screenWidth * 4      // long: 4 screens wide
            else -> screenWidth * 5      // very long: 5 screens wide
        }

        pxPerSecond = targetTotalWidth / durationSec

        Log.d(
            "Timeline",
            "duration=${durationSec}s pxPerSecond=$pxPerSecond totalWidth=${timeToPx(safeVideoDurationMs())}px"
        )
    }

    private fun hideAllOverlays() {
        stickerTracks.forEach { it.overlayBinding.root.isVisible = false }
        textTracks.forEach { it.overlayBinding.root.isVisible = false }
        textOverlayState?.overlayBinding?.root?.isVisible = false
    }

    private fun updateAllTextVisibility(currentTimeMs: Long) {
        val duration = safeVideoDurationMs()
        for (track in textTracks) {
            track.overlayBinding.root.isVisible =
                currentTimeMs in track.data.startTimeMs..track.data.endTimeMs && currentTimeMs < duration  // ✅ never show past video end
        }
    }


    private fun maxClipRightEdge(): Int = halfWidth + totalWidth

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClipDraggingForText(clipView: TimelineTextClipBinding, textTrack: TextTrack) {
        val root = clipView.clipRoot
        val leftHandle = clipView.leftHandle
        val rightHandle = clipView.rightHandle
        val data = textTrack.data

        var isDraggingClip = false
        var isDraggingLeft = false
        var isDraggingRight = false
        var lastRawX = 0f
        var initialMargin = 0
        var initialWidth = 0
        var neighborLeftBound = halfWidth
        var neighborRightBound = maxClipRightEdge()

        fun syncTimes(params: FrameLayout.LayoutParams) {
            data.startTimeMs = pxToTime((params.leftMargin - halfWidth).coerceAtLeast(0))
            data.endTimeMs =
                pxToTime((params.leftMargin + params.width - halfWidth).coerceAtLeast(0))
            val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
            updateAllTextVisibility(currentMs)
        }

        fun logMoveResize() {
            val row = root.parent as? FrameLayout
            Log.d(
                "TimelineDebug",
                "move/resize type=TEXT startTimeMs=${data.startTimeMs} endTimeMs=${data.endTimeMs} row=${row?.let { trackList.indexOf(it) }}"
            )
        }

        var downRawXForTap = 0f
        var hasDraggedClip = false

        root.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val edge = minOf(50f, v.width * 0.25f)
                    if (event.x < edge || event.x > v.width - edge) return@setOnTouchListener false
                    isDraggingClip = true
                    lastRawX = event.rawX
                    downRawXForTap = event.rawX
                    hasDraggedClip = false
                    initialMargin = params.leftMargin
                    val row = v.parent as? FrameLayout
                    if (row != null) {
                        val bounds = neighborBoundsFor(row, v)
                        neighborLeftBound = bounds.first
                        neighborRightBound = bounds.second
                    }
                    (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                // NEW
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingClip) return@setOnTouchListener false
                    if (kotlin.math.abs(event.rawX - downRawXForTap) > 12f) hasDraggedClip = true
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialMargin += dx

                    val maxLeft = maxClipRightEdge() - params.width
                    val minLeft = maxOf(halfWidth, neighborLeftBound)
                    val maxLeftClamped = minOf(maxLeft, neighborRightBound - params.width)
                    val clampedMargin = initialMargin.coerceIn(minLeft, maxOf(minLeft, maxLeftClamped))
                    initialMargin = clampedMargin   //
                    params.leftMargin = clampedMargin
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClip = false
                    (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    if (hasDraggedClip) logMoveResize()
                    if (!hasDraggedClip && event.action == MotionEvent.ACTION_UP) {
                        selectOverlayForEditing(textTrack.overlayBinding, root, data.startTimeMs)
                    }
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
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborLeftBound = neighborBoundsFor(row, root).first
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                // NEW
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingLeft) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialMargin += dx
                    initialWidth -= dx
                    // ✅ Left edge can't go before t=0 or into the left neighbour, width must stay >= 100
                    val clampedMargin = initialMargin.coerceAtLeast(maxOf(halfWidth, neighborLeftBound))
                    val clampedWidth = initialWidth.coerceAtLeast(100)
                    initialMargin = clampedMargin   // ✅ drift fix
                    initialWidth = clampedWidth     // ✅ drift fix
                    params.leftMargin = clampedMargin
                    params.width = clampedWidth
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeft = false
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    logMoveResize()
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
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborRightBound = neighborBoundsFor(row, root).second
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                // NEW
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingRight) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialWidth += dx
                    // ✅ Right edge can't exceed video end or the right neighbour
                    val maxWidth = minOf(maxClipRightEdge(), neighborRightBound) - params.leftMargin
                    val clampedWidth = initialWidth.coerceIn(100, maxOf(100, maxWidth))
                    initialWidth = clampedWidth   // ✅ drift fix
                    params.width = clampedWidth
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRight = false
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    logMoveResize()
                    true
                }

                else -> false
            }
        }
    }

    // ── Text overlay setup ─────────────────────────────────────────────────
    private fun overLayTextView() {
        if (currentMode == EditorMode.TEXT_OVERLAY) {
            binding.editorTextPanel.textEditorPanel.isGone = true
            currentMode = EditorMode.NONE
            refreshSaveButtonVisibility()
            return
        } else if (currentMode == EditorMode.CROP) {
            currentMode = EditorMode.NONE
            cropRatioHelper.hide()
            binding.cropView.visibility = View.GONE
            binding.okTextOverlay.visibility = View.GONE
        }

        currentMode = EditorMode.TEXT_OVERLAY
        refreshSaveButtonVisibility()   //

        enterTimelineMode()

        binding.featureRecyclerView.isGone = true

        val textBinding = ItemOverlayBinding.inflate(layoutInflater, null, false)
        textBinding.overlayText.isVisible = true
        textBinding.overlayImageView.isGone = true

        textOverlayState = TextOverlayState(
            overlayBinding = textBinding,
            selectedFontResId = R.font.roboto_serif,
            selectedFontAssetFileName = "roboto_serif.ttf",
            savedBackground = textBinding.overlayRoot.background
        )

        textBinding.overlayText.typeface = ResourcesCompat.getFont(this, R.font.roboto_serif)

        // ── Always-visible color swatches row ──────────────────────────────
        val editorPanel = binding.editorTextPanel
        editorPanel.colorSwatchRow.removeAllViews()
        val colorOptions = listOf("#FFFFFF", "#000000", "#FF0000", "#FFFF00", "#00FF00", "#00FFFF")
        var selectedColorSwatch: View? = null
        fun markColorSelected(swatch: View) {
            (selectedColorSwatch?.background as? android.graphics.drawable.GradientDrawable)
                ?.setStroke(2.dpToPx(), "#66FFFFFF".toColorInt())
            (swatch.background as? android.graphics.drawable.GradientDrawable)
                ?.setStroke(4.dpToPx(), "#FFFFFFFF".toColorInt())
            selectedColorSwatch = swatch
        }
        colorOptions.forEach { hex ->
            val swatch = View(this).apply {
                val size = 32.dpToPx()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(hex.toColorInt())
                    setStroke(2.dpToPx(), "#66FFFFFF".toColorInt())
                }
                setOnClickListener {

                    textOverlayState?.selectedColorHex = hex
                    textOverlayState?.overlayBinding?.overlayText?.setTextColor(hex.toColorInt())
                    markColorSelected(this)
                }
            }
            editorPanel.colorSwatchRow.addView(swatch)
            if (hex == "#FFFFFF") markColorSelected(swatch)   // ✅ default selected state
        }

        // ── Always-visible font name previews row ───────────────────────────
        editorPanel.fontPreviewRow.removeAllViews()
        val fontOptions = listOf(
            TextFontModel(getString(R.string.font_default), R.font.roboto_serif, "roboto_serif.ttf"),
            TextFontModel("Aladin", R.font.aladin, "aladin.ttf"),
            TextFontModel("Aguafina", R.font.aguafina_script, "aguafina_script.ttf")
        )
        var selectedFontPreview: android.widget.TextView? = null
        fun markFontSelected(preview: android.widget.TextView) {
            selectedFontPreview?.alpha = 0.5f
            preview.alpha = 1f
            selectedFontPreview = preview
        }
        fontOptions.forEach { model ->
            val preview = android.widget.TextView(this).apply {
                text = model.name
                setTextColor(ContextCompat.getColor(this@EditVideoActivity, R.color.white))
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@EditVideoActivity, model.font)
                setPadding(20, 8, 20, 8)
                alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12.dpToPx() }
                setOnClickListener {
                    textOverlayState?.selectedFontResId = model.font
                    textOverlayState?.selectedFontAssetFileName = model.assetFileName
                    textOverlayState?.overlayBinding?.overlayText?.typeface =
                        ResourcesCompat.getFont(this@EditVideoActivity, model.font)
                    markFontSelected(this)
                }
            }
            editorPanel.fontPreviewRow.addView(preview)
            if (model.font == R.font.roboto_serif) markFontSelected(preview)   // ✅ default selected state
        }

        // ── Label swatches — each is a colored card with a small decorative pill inside,
        // matching the reference design. Tapping applies ONLY that card's color as the
        // text's background — the inner pill is purely visual, nothing else changes. ──
        data class LabelPreset(
            val hex: String?,
            val cardColor: Int,
            val pillColor: Int,
            val cardGradient: IntArray? = null
        )
        val labelPresets = listOf(
            LabelPreset(null, "#FFFFFF".toColorInt(), "#00000000".toColorInt()),
            LabelPreset("#FFFFFF", "#FFFFFF".toColorInt(), "#E6E6E6".toColorInt()),
            LabelPreset("#000000", "#000000".toColorInt(), "#FFFFFF".toColorInt()),
            LabelPreset("#FF9800", "#FF9800".toColorInt(), "#000000".toColorInt()),
            LabelPreset("#FFEB3B", "#FFEB3B".toColorInt(), "#000000".toColorInt()),
            LabelPreset("#F4433A", "#F4433A".toColorInt(), "#FFFFFF".toColorInt()),
            LabelPreset("#808080", "#808080".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#4B4B4B".toColorInt(), "#B05C5C".toColorInt())),
            LabelPreset("#00E5A0", "#00E5A0".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#00C6FF".toColorInt(), "#7CFFB2".toColorInt())),
            LabelPreset("#DAA520", "#DAA520".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#E8B04B".toColorInt(), "#C98A2C".toColorInt())),
            LabelPreset("#00C853", "#000000".toColorInt(), "#00C853".toColorInt()),
            LabelPreset("#7C4DFF", "#000000".toColorInt(), "#7C4DFF".toColorInt())
        )
        editorPanel.labelSwatchRow.removeAllViews()
        var selectedLabelSwatch: View? = null
        fun markLabelSelected(swatch: View) {
            selectedLabelSwatch?.foreground = null
            (swatch.background as? android.graphics.drawable.GradientDrawable)
            swatch.foreground = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8.dpToPx().toFloat()
                setStroke(2.dpToPx(), "#FFFFFFFF".toColorInt())
            }
            selectedLabelSwatch = swatch
        }
        val labelCards = mutableMapOf<String?, View>()
        var markShapeSelected: (LabelShape) -> Unit = {}
        labelPresets.forEach { preset ->
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 22.dpToPx()).apply { marginEnd = 6.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8.dpToPx().toFloat()
                    if (preset.cardGradient != null) {
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                        colors = preset.cardGradient
                    } else {
                        setColor(preset.cardColor)
                    }
                }
            }
            if (preset.hex == null) {
                // "None" — draw a red diagonal line across the white card
                val line = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(16.dpToPx(), 2.dpToPx()).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    rotation = -35f
                    setBackgroundColor("#E53935".toColorInt())
                }
                card.addView(line)
            } else {
                val pill = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(10.dpToPx(), 5.dpToPx()).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 5.dpToPx().toFloat()
                        setColor(preset.pillColor)
                    }
                }
                card.addView(pill)
            }
            card.setOnClickListener {
                val st = textOverlayState ?: return@setOnClickListener
                st.selectedLabelColorHex = preset.hex
                applyLabelBackground(st.overlayBinding.overlayText, preset.hex, st.selectedLabelShape, st.selectedLabelOpacity)
                markLabelSelected(card)
                markShapeSelected(resolveLabelShape(preset.hex, st.selectedLabelShape))
            }
            editorPanel.labelSwatchRow.addView(card)
            labelCards[preset.hex] = card
            if (preset.hex == null) markLabelSelected(card)   // ✅ default: no label background
        }
        markShapeSelected = setupLabelShapeAndOpacity(editorPanel, textBinding.overlayText) { hex ->
            labelCards[hex]?.let { markLabelSelected(it) }
        }

        // ── Text / Color / Font / Label tab row — underline shows which one is active ──
        fun setActiveTab(showColor: Boolean, showFont: Boolean, showLabel: Boolean, showLabelStyle: Boolean = false) {
            editorPanel.colorSwatchScroll.isVisible = showColor
            editorPanel.fontPreviewScroll.isVisible = showFont
            editorPanel.labelOptionsContainer.isVisible = showLabel
            editorPanel.labelStyleContainer.isVisible = showLabelStyle
            editorPanel.tabTextUnderline.isInvisible = showColor || showFont || showLabel || showLabelStyle
            editorPanel.tabColorUnderline.isInvisible = !showColor
            editorPanel.tabFontUnderline.isInvisible = !showFont
            editorPanel.tabLabelUnderline.isInvisible = !showLabel
            editorPanel.tabLabelStyleUnderline.isInvisible = !showLabelStyle
        }
        setActiveTab(showColor = false, showFont = false, showLabel = false)   // ✅ start on "Text"

        editorPanel.tabTextContainer.setOnClickListener {
            setActiveTab(showColor = false, showFont = false, showLabel = false)
        }
        editorPanel.tabColorContainer.setOnClickListener {
            setActiveTab(showColor = true, showFont = false, showLabel = false)
        }
        editorPanel.tabFontContainer.setOnClickListener {
            setActiveTab(showColor = false, showFont = true, showLabel = false)
        }
        editorPanel.tabLabelContainer.setOnClickListener {
            setActiveTab(showColor = false, showFont = false, showLabel = true)
        }
        editorPanel.tabLabelStyleContainer.setOnClickListener {
            setActiveTab(showColor = false, showFont = false, showLabel = false, showLabelStyle = true)
        }

        // ── Keyboard-toggle icon on the pill's right edge ───────────────────────────
        editorPanel.ivKeyboardToggle.setOnClickListener {
            editorPanel.enterTextEditText.requestFocus()
            imm.showSoftInput(editorPanel.enterTextEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 0
            topMargin = 24.dpToPx()
            // ✅ gravity se center: poori width milti hai, letter-by-letter wrap nahi hoga
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        }
        textBinding.root.layoutParams = params


        clearOverlayContainerKeepThumbnail()
        binding.overlayContainer.addView(textBinding.root)

        textBinding.overlayText.text =
            if (binding.editorTextPanel.enterTextEditText.text.isNullOrEmpty()) getString(R.string.font_default)
            else binding.editorTextPanel.enterTextEditText.text
        textBinding.overlayText.textSize = 20F

        textBinding.closeContainer.setOnClickListener {
            binding.overlayContainer.removeView(textBinding.root)
            textOverlayState = null
        }

        enableDrag(textBinding.root, binding.overlayContainer)
        enableTextResize(textBinding, binding.overlayContainer)

        activeTextWatcher?.let {
            binding.editorTextPanel.enterTextEditText.removeTextChangedListener(it)
        }
        binding.editorTextPanel.enterTextEditText.setText("")
        activeTextWatcher =
            binding.editorTextPanel.enterTextEditText.doOnTextChanged { text, _, _, _ ->
                textBinding.overlayText.text =
                    if (text.isNullOrBlank()) getString(R.string.enter_text) else text.toString()
            }

        // ✅ Direct "Done" trigger — don't rely solely on the WindowInsets ime()
        // detection below to dismiss the panel (proven unreliable on API 30/31 in
        // this activity's history). Tapping Done explicitly hides the panel right
        // away; the insets-based path below still runs as a backup for the
        // keyboard-closed-another-way cases (back button, tapping elsewhere) and
        // is a safe no-op here since hideTextEditorPanel() already guards itself
        // on panel.isVisible/currentMode/textOverlayState.
        editorPanel.enterTextEditText.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                hideTextEditorPanel()
                true
            } else {
                false
            }
        }
        showTextEditorPanel()
        // ✅ REMOVED cropMenu lines — already handled by refreshSaveButtonVisibility() above
    }

    /**
     * Opens the SAME text editor panel used for creating new text, but pre-filled with
     * [track]'s current text/color/font, and wired to UPDATE that track in place instead
     * of creating a new overlay/clip. See the editingExistingTrack branch in
     * hideTextEditorPanel(). Called from the pencil icon (editContainer) only.
     */
    private fun editTextOverlay(track: TextTrack) {
        // Tapping the pencil again while this same track's panel is already open just closes it.
        if (currentMode == EditorMode.TEXT_OVERLAY && textOverlayState?.editingExistingTrack === track) {
            binding.editorTextPanel.textEditorPanel.isGone = true
            currentMode = EditorMode.NONE
            textOverlayState = null
            refreshSaveButtonVisibility()
            return
        }

        if (currentMode == EditorMode.CROP) {
            currentMode = EditorMode.NONE
            cropRatioHelper.hide()
            binding.cropView.visibility = View.GONE
            binding.okTextOverlay.visibility = View.GONE
        }

        currentMode = EditorMode.TEXT_OVERLAY
        refreshSaveButtonVisibility()
        enterTimelineMode()
        binding.featureRecyclerView.isGone = true

        val overlayBinding = track.overlayBinding
        val data = track.data

        textOverlayState = TextOverlayState(
            overlayBinding = overlayBinding,
            selectedFontResId = data.fontResId,
            selectedFontAssetFileName = data.fontAssetFileName,
            savedBackground = overlayBorderCache[overlayBinding],
            selectedColorHex = data.color,
            selectedLabelColorHex = data.labelColor,
            selectedLabelShape = data.labelShape,
            selectedLabelOpacity = data.labelOpacity,
            editingExistingTrack = track
        )

        // ── Color swatches row — pre-select current color ──
        val editorPanel = binding.editorTextPanel
        editorPanel.colorSwatchRow.removeAllViews()
        val colorOptions = listOf("#FFFFFF", "#000000", "#FF0000", "#FFFF00", "#00FF00", "#00FFFF")
        var selectedColorSwatch: View? = null
        fun markColorSelected(swatch: View) {
            (selectedColorSwatch?.background as? android.graphics.drawable.GradientDrawable)
                ?.setStroke(2.dpToPx(), "#66FFFFFF".toColorInt())
            (swatch.background as? android.graphics.drawable.GradientDrawable)
                ?.setStroke(4.dpToPx(), "#FFFFFFFF".toColorInt())
            selectedColorSwatch = swatch
        }
        colorOptions.forEach { hex ->
            val swatch = View(this).apply {
                val size = 32.dpToPx()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(hex.toColorInt())
                    setStroke(2.dpToPx(), "#66FFFFFF".toColorInt())
                }
                setOnClickListener {
                    textOverlayState?.selectedColorHex = hex
                    textOverlayState?.overlayBinding?.overlayText?.setTextColor(hex.toColorInt())
                    markColorSelected(this)
                }
            }
            editorPanel.colorSwatchRow.addView(swatch)
            if (hex.equals(data.color, ignoreCase = true)) markColorSelected(swatch)
        }

        // ── Font previews row — pre-select current font ──
        editorPanel.fontPreviewRow.removeAllViews()
        val fontOptions = listOf(
            TextFontModel(getString(R.string.font_default), R.font.roboto_serif, "roboto_serif.ttf"),
            TextFontModel("Aladin", R.font.aladin, "aladin.ttf"),
            TextFontModel("Aguafina", R.font.aguafina_script, "aguafina_script.ttf")
        )
        var selectedFontPreview: android.widget.TextView? = null
        fun markFontSelected(preview: android.widget.TextView) {
            selectedFontPreview?.alpha = 0.5f
            preview.alpha = 1f
            selectedFontPreview = preview
        }
        fontOptions.forEach { model ->
            val preview = android.widget.TextView(this).apply {
                text = model.name
                setTextColor(ContextCompat.getColor(this@EditVideoActivity, R.color.white))
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@EditVideoActivity, model.font)
                setPadding(20, 8, 20, 8)
                alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12.dpToPx() }
                setOnClickListener {
                    textOverlayState?.selectedFontResId = model.font
                    textOverlayState?.selectedFontAssetFileName = model.assetFileName
                    textOverlayState?.overlayBinding?.overlayText?.typeface =
                        ResourcesCompat.getFont(this@EditVideoActivity, model.font)
                    markFontSelected(this)
                }
            }
            editorPanel.fontPreviewRow.addView(preview)
            if (model.font == data.fontResId) markFontSelected(preview)
        }

        // ── Label swatches — card + decorative pill design, pre-select current ──
        data class LabelPreset(
            val hex: String?,
            val cardColor: Int,
            val pillColor: Int,
            val cardGradient: IntArray? = null
        )
        val labelPresets = listOf(
            LabelPreset(null, "#FFFFFF".toColorInt(), "#00000000".toColorInt()),
            LabelPreset("#FFFFFF", "#FFFFFF".toColorInt(), "#E6E6E6".toColorInt()),
            LabelPreset("#000000", "#000000".toColorInt(), "#FFFFFF".toColorInt()),
            LabelPreset("#FF9800", "#FF9800".toColorInt(), "#000000".toColorInt()),
            LabelPreset("#FFEB3B", "#FFEB3B".toColorInt(), "#000000".toColorInt()),
            LabelPreset("#F4433A", "#F4433A".toColorInt(), "#FFFFFF".toColorInt()),
            LabelPreset("#808080", "#808080".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#4B4B4B".toColorInt(), "#B05C5C".toColorInt())),
            LabelPreset("#00E5A0", "#00E5A0".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#00C6FF".toColorInt(), "#7CFFB2".toColorInt())),
            LabelPreset("#DAA520", "#DAA520".toColorInt(), "#FFFFFF".toColorInt(),
                cardGradient = intArrayOf("#E8B04B".toColorInt(), "#C98A2C".toColorInt())),
            LabelPreset("#00C853", "#000000".toColorInt(), "#00C853".toColorInt()),
            LabelPreset("#7C4DFF", "#000000".toColorInt(), "#7C4DFF".toColorInt())
        )
        editorPanel.labelSwatchRow.removeAllViews()
        var selectedLabelSwatch: View? = null
        fun markLabelSelected(swatch: View) {
            selectedLabelSwatch?.foreground = null
            swatch.foreground = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8.dpToPx().toFloat()
                setStroke(2.dpToPx(), "#FFFFFFFF".toColorInt())
            }
            selectedLabelSwatch = swatch
        }
        val labelCards = mutableMapOf<String?, View>()
        var markShapeSelected: (LabelShape) -> Unit = {}
        labelPresets.forEach { preset ->
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 22.dpToPx()).apply { marginEnd = 6.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8.dpToPx().toFloat()
                    if (preset.cardGradient != null) {
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                        colors = preset.cardGradient
                    } else {
                        setColor(preset.cardColor)
                    }
                }
            }
            if (preset.hex == null) {
                val line = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(16.dpToPx(), 2.dpToPx()).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    rotation = -35f
                    setBackgroundColor("#E53935".toColorInt())
                }
                card.addView(line)
            } else {
                val pill = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(10.dpToPx(), 5.dpToPx()).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 5.dpToPx().toFloat()
                        setColor(preset.pillColor)
                    }
                }
                card.addView(pill)
            }
            card.setOnClickListener {
                val st = textOverlayState ?: return@setOnClickListener
                st.selectedLabelColorHex = preset.hex
                applyLabelBackground(overlayBinding.overlayText, preset.hex, st.selectedLabelShape, st.selectedLabelOpacity)
                markLabelSelected(card)
                markShapeSelected(resolveLabelShape(preset.hex, st.selectedLabelShape))
            }
            editorPanel.labelSwatchRow.addView(card)
            labelCards[preset.hex] = card
            if (preset.hex == data.labelColor ||
                (preset.hex == null && data.labelColor == null)
            ) {
                markLabelSelected(card)
            }
        }
        markShapeSelected = setupLabelShapeAndOpacity(editorPanel, overlayBinding.overlayText) { hex ->
            labelCards[hex]?.let { markLabelSelected(it) }
        }

        // ── Tab row — start on "Text" tab ──
        fun setActiveTab(showColor: Boolean, showFont: Boolean, showLabel: Boolean, showLabelStyle: Boolean = false) {
            editorPanel.colorSwatchScroll.isVisible = showColor
            editorPanel.fontPreviewScroll.isVisible = showFont
            editorPanel.labelOptionsContainer.isVisible = showLabel
            editorPanel.labelStyleContainer.isVisible = showLabelStyle
            editorPanel.tabTextUnderline.isInvisible = showColor || showFont || showLabel || showLabelStyle
            editorPanel.tabColorUnderline.isInvisible = !showColor
            editorPanel.tabFontUnderline.isInvisible = !showFont
            editorPanel.tabLabelUnderline.isInvisible = !showLabel
            editorPanel.tabLabelStyleUnderline.isInvisible = !showLabelStyle
        }
        setActiveTab(showColor = false, showFont = false, showLabel = false)
        editorPanel.tabTextContainer.setOnClickListener { setActiveTab(false, false, false) }
        editorPanel.tabColorContainer.setOnClickListener { setActiveTab(true, false, false) }
        editorPanel.tabFontContainer.setOnClickListener { setActiveTab(false, true, false) }
        editorPanel.tabLabelContainer.setOnClickListener { setActiveTab(false, false, true) }
        editorPanel.tabLabelStyleContainer.setOnClickListener { setActiveTab(false, false, false, true) }

        editorPanel.ivKeyboardToggle.setOnClickListener {
            editorPanel.enterTextEditText.requestFocus()
            imm.showSoftInput(editorPanel.enterTextEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        // ── Pre-fill existing text, live-edit writes into the SAME overlay view ──
        activeTextWatcher?.let {
            editorPanel.enterTextEditText.removeTextChangedListener(it)
        }
        editorPanel.enterTextEditText.setText(data.text)
        editorPanel.enterTextEditText.setSelection(editorPanel.enterTextEditText.text?.length ?: 0)
        activeTextWatcher = editorPanel.enterTextEditText.doOnTextChanged { text, _, _, _ ->
            overlayBinding.overlayText.text =
                if (text.isNullOrBlank()) getString(R.string.enter_text) else text.toString()
        }

        editorPanel.enterTextEditText.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                hideTextEditorPanel()
                true
            } else {
                false
            }
        }

        showTextEditorPanel()
    }

    /**
     * Removes all children from overlayContainer EXCEPT the thumbnail ImageView.
     * Thumbnail lives inside overlayContainer per XML — must never be removed.
     */
    private fun clearOverlayContainerKeepThumbnail() {
        val thumbnail = binding.thumbnail
        binding.overlayContainer.removeAllViews()
        binding.overlayContainer.addView(thumbnail) // always restore it


        for (track in stickerTracks) {
            if (track.overlayBinding.root.parent == null) {
                binding.overlayContainer.addView(track.overlayBinding.root)
            }
        }
        for (track in textTracks) {
            if (track.overlayBinding.root.parent == null) {
                binding.overlayContainer.addView(track.overlayBinding.root)
            }
        }

        // ✅ Current time ke hisaab se sahi visibility turant apply karo
        val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
        updateAllStickerVisibility(currentMs)
        updateAllTextVisibility(currentMs)
    }

    // ── Drag & resize helpers ──────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: View, container: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startMarginLeft = 0
        var startMarginTop = 0
        var hasMoved = false
        var clampedDx = 0f
        var clampedDy = 0f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    normalizeOverlayPosition(v)
                    val params = v.layoutParams as FrameLayout.LayoutParams
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startMarginLeft = params.leftMargin
                    startMarginTop = params.topMargin
                    hasMoved = false
                    clampedDx = 0f
                    clampedDy = 0f
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val diffX = event.rawX - downRawX
                    val diffY = event.rawY - downRawY
                    // Only consider it a move if dragged more than 8px
                    if (!hasMoved && (kotlin.math.abs(diffX) > 8 || kotlin.math.abs(diffY) > 8)) {
                        hasMoved = true
                    }
                    if (hasMoved) {

                        val minDx = -startMarginLeft.toFloat()
                        val maxDx = (container.width - v.width - startMarginLeft).toFloat()
                        val minDy = -startMarginTop.toFloat()
                        val maxDy = (container.height - v.height - startMarginTop).toFloat()
                        clampedDx = diffX.coerceIn(minDx, maxDx)
                        clampedDy = diffY.coerceIn(minDy, maxDy)
                        v.translationX = clampedDx
                        v.translationY = clampedDy
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    if (hasMoved) {

                        val params = v.layoutParams as FrameLayout.LayoutParams
                        params.leftMargin = (startMarginLeft + clampedDx.toInt())
                            .coerceIn(0, container.width - v.width)
                        params.topMargin = (startMarginTop + clampedDy.toInt())
                            .coerceIn(0, container.height - v.height)
                        v.translationX = 0f
                        v.translationY = 0f
                        v.layoutParams = params
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
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
    private fun enableTextResize(overlayBinding: ItemOverlayBinding, container: FrameLayout) {
        val root = overlayBinding.overlayRoot
        val textView = overlayBinding.overlayText
        var downRawY = 0f
        var startTextSize = 0f
        var pendingSizePx = 0f

        overlayBinding.resizeContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startTextSize = textView.textSize
                    pendingSizePx = startTextSize
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    pendingSizePx =
                        (startTextSize + (event.rawY - downRawY) / 5f).coerceIn(30f, 200f)

                    val ratio = pendingSizePx / startTextSize.coerceAtLeast(1f)
                    root.scaleX = ratio
                    root.scaleY = ratio
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)

                    root.scaleX = 1f
                    root.scaleY = 1f
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pendingSizePx)
                    root.requestLayout()
                    root.post { keepInsideBounds(root, container) }
                    true
                }

                else -> false
            }
        }
    }

    private var textFitListener: View.OnLayoutChangeListener? = null
    private var textFitTarget: View? = null

    /** Overlay ko container ke andar rakhta hai. Margin sirf tab badalta hai jab zarurat ho,
     *  isliye layout loop nahi banta. [center] = true to beech mein rakhta hai. */
    private fun fitOverlayInside(
        view: View,
        container: FrameLayout,
        center: Boolean = false,
        topPx: Int? = null
    ) {
        if (container.width <= 0 || view.width <= 0) return
        val p = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxX = (container.width - view.width).coerceAtLeast(0)
        val maxY = (container.height - view.height).coerceAtLeast(0)
        val newL = if (center) maxX / 2 else p.leftMargin.coerceIn(0, maxX)
        // center = pehli baar: horizontally beech mein, vertically topPx par (upar)
        val newT = if (center) (topPx ?: (maxY / 2)).coerceIn(0, maxY)
        else p.topMargin.coerceIn(0, maxY)
        if (newL != p.leftMargin || newT != p.topMargin) {
            p.leftMargin = newL
            p.topMargin = newT
            view.layoutParams = p
        }
    }

    private fun removeTextFitListener() {
        textFitListener?.let { l ->
            binding.overlayContainer.removeOnLayoutChangeListener(l)
            textFitTarget?.removeOnLayoutChangeListener(l)
        }
        textFitListener = null
        textFitTarget = null
    }

    /** Gravity-centered overlay ko asli leftMargin/topMargin mein badalta hai.
     *  Drag, export aur draft save margin se position padhte hain, isliye zaroori hai. */
    private fun normalizeOverlayPosition(view: View) {
        val p = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (p.gravity == -1 || view.width <= 0) return
        p.leftMargin = view.left
        p.topMargin = view.top
        p.gravity = -1
        view.layoutParams = p
    }

    private fun keepInsideBounds(view: View, container: FrameLayout) {
        val params = view.layoutParams as FrameLayout.LayoutParams

        val maxX = (container.width - view.width).coerceAtLeast(0)
        val maxY = (container.height - view.height).coerceAtLeast(0)

        params.leftMargin = params.leftMargin.coerceIn(0, maxX)
        params.topMargin = params.topMargin.coerceIn(0, maxY)

        view.layoutParams = params
    }

    /** Applies (or clears, if [colorHex] is null or [shape] is NONE) a "label" background
     *  behind a text overlay's text. Independent of the text's own font color.
     *  [shape]/[opacity] null = ROUNDED_RECT / 100%, i.e. the original label look.
     *  [scale] multiplies every dp size (padding, radius, stroke, dash) — 1f for the on-screen
     *  preview, the view→video pixel ratio when rendering the export PNG. */
    private fun applyLabelBackground(
        textView: android.widget.TextView,
        colorHex: String?,
        shape: String? = null,
        opacity: Int? = null,
        scale: Float = 1f
    ) {
        val unit = resources.displayMetrics.density * scale
        val drawable = colorHex?.let {
            buildLabelDrawable(resolveLabelShape(it, shape), it, opacity ?: 100, unit)
        }
        if (drawable == null) {
            textView.background = null
            val p = (4 * unit).roundToInt()
            textView.setPadding(p, p, p, p)
        } else {
            textView.background = drawable
            val h = (14 * unit).roundToInt()
            val v = (6 * unit).roundToInt()
            textView.setPadding(h, v, h, v)
        }
    }

    private fun resolveLabelShape(labelColor: String?, labelShape: String?): LabelShape {
        if (labelColor == null) return LabelShape.NONE
        return labelShape?.let { name -> LabelShape.entries.firstOrNull { it.name == name } }
            ?: LabelShape.ROUNDED_RECT
    }

    /** Builds the label drawable for [shape]; [unitPx] = pixels per dp (already scaled).
     *  Opacity only affects the label's fill/stroke, never the text. */
    private fun buildLabelDrawable(
        shape: LabelShape, colorHex: String, opacity: Int, unitPx: Float
    ): android.graphics.drawable.GradientDrawable? {
        if (shape == LabelShape.NONE) return null
        val color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            colorHex.toColorInt(), opacity.coerceIn(0, 100) * 255 / 100
        )
        val stroke = (2 * unitPx).coerceAtLeast(1f).roundToInt()
        return android.graphics.drawable.GradientDrawable().apply {
            this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            // GradientDrawable clamps the radius to half the shorter side → a huge radius = pill.
            cornerRadius = when (shape) {
                LabelShape.FILLED_RECT, LabelShape.OUTLINE_RECT, LabelShape.DASHED_OUTLINE -> 0f
                LabelShape.PILL -> 10_000f
                else -> 16 * unitPx
            }
            when (shape) {
                LabelShape.OUTLINE_RECT, LabelShape.OUTLINE_ROUNDED -> {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(stroke, color)
                }
                LabelShape.DASHED_OUTLINE -> {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(stroke, color, 8 * unitPx, 4 * unitPx)
                }
                else -> setColor(color)
            }
        }
    }

    /**
     * Wires the Label tab's shape row + opacity slider to [textOverlayState] — shared by the
     * add-text and edit-text (pencil) flows. [onLabelColorChanged] lets the caller re-highlight
     * its color card when a shape changes the color (NONE → null, any shape on "none" → white).
     * Returns a function the caller uses to re-highlight the shape row after a color tap.
     */
    private fun setupLabelShapeAndOpacity(
        editorPanel: BottomSheetDialogTextviewFontBinding,
        overlayText: android.widget.TextView,
        onLabelColorChanged: (String?) -> Unit
    ): (LabelShape) -> Unit {
        fun refreshPreview() {
            val st = textOverlayState ?: return
            applyLabelBackground(overlayText, st.selectedLabelColorHex, st.selectedLabelShape, st.selectedLabelOpacity)
        }

        editorPanel.labelShapeRow.removeAllViews()
        val cells = mutableMapOf<LabelShape, View>()
        var selectedCell: View? = null
        fun markShapeSelected(shape: LabelShape) {
            selectedCell?.foreground = null
            val cell = cells[shape] ?: return
            cell.foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 6.dpToPx().toFloat()
                setStroke(2.dpToPx(), "#FFFFFFFF".toColorInt())
            }
            selectedCell = cell
        }
        LabelShape.entries.forEach { shape ->
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 30.dpToPx()).apply { marginEnd = 8.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6.dpToPx().toFloat()
                    setColor("#333333".toColorInt())
                }
            }
            val icon = View(this).apply {
                if (shape == LabelShape.NONE) {
                    layoutParams = FrameLayout.LayoutParams(16.dpToPx(), 2.dpToPx(), android.view.Gravity.CENTER)
                    rotation = -35f
                    setBackgroundColor("#E53935".toColorInt())
                } else {
                    layoutParams = FrameLayout.LayoutParams(30.dpToPx(), 18.dpToPx(), android.view.Gravity.CENTER)
                    background = buildLabelDrawable(shape, "#FFFFFF", 100, resources.displayMetrics.density * 0.4f)
                }
            }
            cell.addView(icon)
            cell.setOnClickListener {
                val st = textOverlayState ?: return@setOnClickListener
                if (shape == LabelShape.NONE) {
                    st.selectedLabelColorHex = null
                    st.selectedLabelShape = null
                } else {
                    if (st.selectedLabelColorHex == null) st.selectedLabelColorHex = "#FFFFFF"
                    st.selectedLabelShape = shape.name
                }
                onLabelColorChanged(st.selectedLabelColorHex)
                refreshPreview()
                markShapeSelected(shape)
            }
            cells[shape] = cell
            editorPanel.labelShapeRow.addView(cell)
        }
        val state = textOverlayState
        markShapeSelected(resolveLabelShape(state?.selectedLabelColorHex, state?.selectedLabelShape))

        val seekBar = editorPanel.labelOpacitySeekBar
        val initialOpacity = state?.selectedLabelOpacity ?: 100
        seekBar.setOnSeekBarChangeListener(null)
        seekBar.progress = initialOpacity
        editorPanel.labelOpacityValue.text = getString(R.string.percent_value, initialOpacity)
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                textOverlayState?.selectedLabelOpacity = progress
                editorPanel.labelOpacityValue.text = getString(R.string.percent_value, progress)
                refreshPreview()
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })

        return ::markShapeSelected
    }

    /**
     * Renders a labeled text track (text + label) to a transparent PNG directly at VIDEO
     * scale — text size, padding, radius, stroke and dash are all in video pixels — and
     * returns it as sticker burn data at the text's on-screen position/time.
     */
    private fun labeledTextBurnData(track: TextTrack): StickerBurnData? {
        val overlayText = track.overlayBinding.overlayText
        val params = track.overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams ?: return null
        val data = track.data
        // Same view→video ratio overlayFontSizeToVideoPixels() uses (letterbox-aware).
        val k = overlayFontSizeToVideoPixels(1f)

        val tv = com.google.android.material.textview.MaterialTextView(this).apply {
            typeface = ResourcesCompat.getFont(this@EditVideoActivity, data.fontResId)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, overlayText.textSize * k)
            setTextColor(data.color.toColorInt())
            text = data.text
        }
        applyLabelBackground(tv, data.labelColor, data.labelShape, data.labelOpacity, scale = k)

        val widthSpec = if (overlayText.width > 0) {
            View.MeasureSpec.makeMeasureSpec(kotlin.math.ceil(overlayText.width * k).toInt() + 2, View.MeasureSpec.AT_MOST)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        tv.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (tv.measuredWidth <= 0 || tv.measuredHeight <= 0) return null
        tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)

        val bitmap = createBitmap(tv.measuredWidth, tv.measuredHeight)
        tv.draw(android.graphics.Canvas(bitmap))
        val file = File(cacheDir, "text_label_${System.currentTimeMillis()}_${textTracks.indexOf(track)}.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e(TAG, "labeledTextBurnData PNG write failed: ${e.message}")
            return null
        } finally {
            bitmap.recycle()
        }

        // overlayText sits inside overlayRoot's inner frame (14dp margin) — use the real
        // laid-out offset when available, else the XML margin (overlay may be GONE).
        val inner = overlayText.parent as? View
        val offX = if (inner != null && inner.width > 0) inner.left + overlayText.left else 14.dpToPx()
        val offY = if (inner != null && inner.height > 0) inner.top + overlayText.top else 14.dpToPx()
        val (videoX, videoY) = overlayViewToVideoPixels(
            (params.leftMargin + offX).toFloat(), (params.topMargin + offY).toFloat()
        )
        return StickerBurnData(
            imagePath = file.absolutePath,
            xPx = videoX,
            yPx = videoY,
            width = tv.measuredWidth,
            height = tv.measuredHeight,
            startSec = data.startTimeMs / 1000.0,
            endSec = data.endTimeMs / 1000.0
        )
    }

    // ── FFmpeg: burn text ──────────────────────────────────────────────────

    /**
     * Converts an (x, y) position inside [binding.overlayContainer] into
     * the corresponding pixel coordinates inside the native video frame,
     * accounting for the VideoView's letter-/pillar-box padding.
     */
    private fun overlayViewToVideoPixels(viewX: Float, viewY: Float): Pair<Int, Int> {
        val containerW = binding.overlayContainer.width.toFloat()
        val containerH = binding.overlayContainer.height.toFloat()
        if (videoNativeWidth <= 0 || videoNativeHeight <= 0 || containerW <= 0f || containerH <= 0f) return Pair(
            viewX.toInt(), viewY.toInt()
        )

        val videoAspect = videoNativeWidth.toFloat() / videoNativeHeight
        val containerAspect = containerW / containerH

        val renderedW: Float
        val renderedH: Float
        val offsetX: Float
        val offsetY: Float

        if (videoAspect > containerAspect) {
            // Video is wider → letterbox (black bars top & bottom)
            renderedW = containerW
            renderedH = containerW / videoAspect
            offsetX = 0f
            offsetY = (containerH - renderedH) / 2f
        } else {
            // Video is taller → pillarbox (black bars left & right)
            renderedH = containerH
            renderedW = containerH * videoAspect
            offsetX = (containerW - renderedW) / 2f
            offsetY = 0f
        }

        val relX = ((viewX - offsetX) / renderedW).coerceIn(0f, 1f)
        val relY = ((viewY - offsetY) / renderedH).coerceIn(0f, 1f)
        return Pair(
            (relX * videoNativeWidth).toInt(), (relY * videoNativeHeight).toInt()
        )
    }

    /**
     * Scales a font size measured in the overlayContainer into video pixels.
     */
    private fun overlayFontSizeToVideoPixels(viewFontSizePx: Float): Float {
        val containerH = binding.overlayContainer.height.toFloat()
        if (videoNativeHeight <= 0 || containerH <= 0f) return viewFontSizePx

        val videoAspect = videoNativeWidth.toFloat() / videoNativeHeight
        val containerAspect = binding.overlayContainer.width.toFloat() / containerH

        // The rendered video height inside the container
        val renderedH =
            if (videoAspect > containerAspect) binding.overlayContainer.width / videoAspect
            else containerH

        return (viewFontSizePx / renderedH) * videoNativeHeight
    }

    private fun startBurningStickers() {
        if (stickerTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_stickers_added), Toast.LENGTH_SHORT).show()
            return
        }
        if (videoUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.no_video_loaded), Toast.LENGTH_SHORT).show()
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

            val imagePath = drawableToFile(this, overlay.drawable)

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
            Toast.makeText(this, getString(R.string.no_valid_stickers), Toast.LENGTH_SHORT).show()
            return
        }

        showTextBurnProgress()
        binding.overlayContainer.visibility = View.GONE
        binding.okTextOverlay.visibility = View.GONE

        // ── Single FFmpeg call with ALL stickers ───────────────────────────
        VideoTextBurner.burnAllStickers(
            context = this,
            inputPath = videoUrl,
            stickers = stickerDataList,
            videoDurationMs = binding.videoView.duration.toLong(),
            onProgress = { percent ->
                runOnUiThread {
                    textBurnProgressDialog?.progress = percent
                    textBurnProgressDialog?.setMessage(getString(R.string.rendering, percent))
                }
            },
            onSuccess = { outputPath ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Toast.makeText(this, getString(R.string.saved, outputPath), Toast.LENGTH_LONG).show()
                    videoUrl = outputPath
                    binding.overlayContainer.removeAllViews()
                    stickerTracks.clear()
                    binding.videoView.setVideoPath(outputPath)
                    binding.videoView.start()
                    finish()
                }
            },
            onError = { errorMessage ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Log.e("Export", "burn failed: $errorMessage"); Toast.makeText(this, getString(R.string.burn_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    binding.overlayContainer.visibility = View.VISIBLE
                    binding.okTextOverlay.visibility = View.VISIBLE
                    currentMode = EditorMode.STICKER
                }
            })
    }


    private fun refreshSaveButtonVisibility() {
        shouldShowCropMenu = true
        cropMenuTitle = getString(R.string.save)
        cropMenu?.isVisible = true
        cropMenu?.title = cropMenuTitle
    }

    // ══════════════════════════════════════════════════════════════════════
    // ✅ DRAFT PERSISTENCE (crash-safety + visible Draft list)
    // ══════════════════════════════════════════════════════════════════════

    /** Stable per-video draft id — derived from the ORIGINAL video, or passed explicitly when resuming a draft. */
    private val draftId: String by lazy {

        intent.getStringExtra("DRAFT_ID") ?: DraftManager.newDraftId()
    }


    private val isResumingDraft: Boolean by lazy {
        intent.getStringExtra("DRAFT_ID") != null
    }

    private data class SavedTextData(
        val text: String,
        val fontResId: Int,
        val fontAssetFileName: String,
        val color: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val leftMargin: Int,
        val topMargin: Int,
        val fontSizePx: Float,
        val labelColor: String? = null,
        val labelShape: String? = null,
        val labelOpacity: Int? = null
    )

    private data class SavedStickerData(
        val drawableResId: Int,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val leftMargin: Int,
        val topMargin: Int,
        val width: Int,
        val height: Int
    )

    private data class SavedMusicData(
        val uri: String,
        val title: String,
        val durationMs: Long,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val musicOffsetMs: Long,
        val isVoice: Boolean = false,
        val volume: Int? = null,
        val speed: Float? = null
    )
    private data class SavedCropData(
        val left: Float, val top: Float, val right: Float, val bottom: Float
    )

    private data class SavedEditState(
        val videoUrl: String,
        val cropRect: SavedCropData?,
        val texts: List<SavedTextData>,
        val stickers: List<SavedStickerData>,
        val music: List<SavedMusicData>,
        val videoVolume: Int? = null,
        /** Non-destructive trim range in original-video time; null = untrimmed (old drafts). */
        val trimStartMs: Long? = null,
        val trimEndMs: Long? = null
    )

    /** Reads only the draft's trim range — called BEFORE the timeline is built. */
    private fun loadDraftTrimRange() {
        try {
            val file = DraftManager.stateFile(this, draftId)
            if (!file.exists()) return
            val state = Gson().fromJson(file.readText(), SavedEditState::class.java) ?: return
            val s = state.trimStartMs
            val e = state.trimEndMs
            if (s != null && e != null && s >= 0L && e > s) {
                pendingTrimStartMs = s
                pendingTrimEndMs = e
            }
        } catch (ex: Exception) {
            Log.e(TAG, "loadDraftTrimRange failed: ${ex.message}")
        }
    }

    /** Autosave (silent) — runs at every confirm point. Also keeps the Draft list index up to date. */
    private fun saveEditStateToCache() {
        val durationMs = safeVideoDurationMs()
        val currentVideoUrl = videoUrl
        // Grab the bitmap on the MAIN thread — Views can't be touched off-thread
        val thumbBitmap = (binding.thumbnail.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

        val stateJson = try {
            val texts = textTracks.map { track ->
                val params = track.overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams
                SavedTextData(
                    text = track.data.text,
                    fontResId = track.data.fontResId,
                    fontAssetFileName = track.data.fontAssetFileName,
                    color = track.data.color,
                    startTimeMs = track.data.startTimeMs,
                    endTimeMs = track.data.endTimeMs,
                    leftMargin = params?.leftMargin ?: 100,
                    topMargin = params?.topMargin ?: 100,
                    fontSizePx = track.overlayBinding.overlayText.textSize,
                    labelColor = track.data.labelColor,
                    labelShape = track.data.labelShape,
                    labelOpacity = track.data.labelOpacity
                )
            }

            val stickers = stickerTracks.map { track ->
                val params = track.overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams
                SavedStickerData(
                    drawableResId = track.overlay.drawableResId,
                    startTimeMs = track.overlay.startTimeMs,
                    endTimeMs = track.overlay.endTimeMs,
                    leftMargin = params?.leftMargin ?: 20,
                    topMargin = params?.topMargin ?: 20,
                    width = params?.width ?: 80.dpToPx(),
                    height = params?.height ?: 80.dpToPx()
                )
            }

            val music = musicTracks.map { track ->
                SavedMusicData(
                    uri = track.overlay.uri.toString(),
                    title = track.overlay.title,
                    durationMs = track.overlay.durationMs,
                    startTimeMs = track.overlay.startTimeMs,
                    endTimeMs = track.overlay.endTimeMs,
                    musicOffsetMs = track.overlay.musicOffsetMs,
                    isVoice = track.overlay.isVoice,
                    volume = track.overlay.volume,
                    speed = track.overlay.speed
                )
            }

            val crop = pendingCropRect?.let {
                SavedCropData(it.left, it.top, it.right, it.bottom)
            }

            val state = SavedEditState(
                videoUrl = currentVideoUrl,
                cropRect = crop,
                texts = texts,
                stickers = stickers,
                music = music,
                videoVolume = videoVolumePercent,
                trimStartMs = pendingTrimStartMs,
                trimEndMs = pendingTrimEndMs
            )

            Gson().toJson(state)
        } catch (e: Exception) {
            Log.e(TAG, "saveEditStateToCache build failed: ${e.message}")
            return
        }

        Thread {
            try {
                DraftManager.stateFile(this, draftId).writeText(stateJson)

                val existing = DraftManager.getEntry(this, draftId)
                var thumbPath = existing?.thumbnailPath?.takeIf { File(it).exists() }
                if (thumbPath == null && thumbBitmap != null) {
                    try {
                        val out = DraftManager.thumbFile(this, draftId)
                        FileOutputStream(out).use {
                            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
                        }
                        thumbPath = out.absolutePath
                    } catch (_: Exception) { }
                }

                val entry = DraftEntry(
                    draftId = draftId,
                    videoUrl = currentVideoUrl,
                    thumbnailPath = thumbPath ?: existing?.thumbnailPath ?: "",
                    displayName = existing?.displayName
                        ?: "Draft_${java.text.SimpleDateFormat("ddMMyyyy_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}",
                    lastEditTimestamp = System.currentTimeMillis(),
                    durationMs = durationMs
                )
                DraftManager.upsertEntry(this, entry)
            } catch (e: Exception) {
                Log.e(TAG, "saveEditStateToCache write failed: ${e.message}")
            }
        }.start()
    }

    private fun restoreEditStateFromCache() {
        try {
            val file = DraftManager.stateFile(this, draftId)
            if (!file.exists()) return

            val state = Gson().fromJson(file.readText(), SavedEditState::class.java) ?: return


            videoUrl = state.videoUrl
            state.videoVolume?.let {
                videoVolumePercent = it.coerceIn(0, 100)
                applyVideoVolume()
            }

            state.cropRect?.let {
                val rect = RectF(it.left, it.top, it.right, it.bottom)
                pendingCropRect = rect
                pendingCropApply = true
                binding.videoView.post { applyCropPreview(rect) }
            }

            state.texts.forEach { saved ->
                val textBinding = ItemOverlayBinding.inflate(layoutInflater, null, false)
                textBinding.overlayText.isVisible = true
                textBinding.overlayImageView.isGone = true
                textBinding.overlayText.text = saved.text
                textBinding.overlayText.setTextSize(TypedValue.COMPLEX_UNIT_PX, saved.fontSizePx)
                applyLabelBackground(textBinding.overlayText, saved.labelColor, saved.labelShape, saved.labelOpacity)
                textBinding.root.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = saved.leftMargin
                    topMargin = saved.topMargin
                }
                enableDrag(textBinding.root, binding.overlayContainer)
                enableTextResize(textBinding, binding.overlayContainer)

                val textData = TextOverlayData(
                    text = saved.text,
                    fontResId = saved.fontResId,
                    fontAssetFileName = saved.fontAssetFileName,
                    color = saved.color,
                    labelColor = saved.labelColor,
                    startTimeMs = saved.startTimeMs,
                    endTimeMs = saved.endTimeMs,
                    labelShape = saved.labelShape,
                    labelOpacity = saved.labelOpacity
                )
                addTextToTimeline(textBinding, textData, resizeAfter = false)
            }

            state.music.forEach { saved ->
                // voice file gayab ho gayi ho to skip
                if (saved.isVoice && saved.uri.toUri().path?.let { File(it).exists() } != true) return@forEach
                addMusicToTimeline(
                    MusicOverlay(
                        uri = saved.uri.toUri(),
                        title = saved.title,
                        durationMs = saved.durationMs,
                        startTimeMs = saved.startTimeMs,
                        endTimeMs = saved.endTimeMs,
                        musicOffsetMs = saved.musicOffsetMs,
                        isVoice = saved.isVoice,
                        volume = saved.volume ?: 100,
                        speed = saved.speed ?: 1f
                    ),
                    isRestore = true
                )
            }

            if (state.texts.isNotEmpty() || state.stickers.isNotEmpty() || state.music.isNotEmpty()) {
                enterTimelineMode()
                binding.frameLayoutEditor.isVisible = true
            }

            refreshSaveButtonVisibility()
        } catch (e: Exception) {
            Log.e(TAG, "restoreEditStateFromCache failed: ${e.message}")
        }
    }

    /** Called when the user explicitly chooses "Save as Draft" — refreshes thumbnail + timestamp, then exits. */
    private fun saveAsDraftAndExit() {
        saveEditStateToCache()   // writes latest state.json + index entry
        finish()
    }

    /** Called on final export success, or "Exit Directly" — removes the draft entirely. */
    private fun clearEditStateCache() {
        DraftManager.removeEntry(this, draftId)
    }


    /**
     * Recursively burns each sticker in [stickerTracks] one at a time.
     * Each FFmpeg output becomes the input for the next sticker.
     *//*  private fun burnStickerAtIndex(index: Int, inputPath: String) {
          if (index >= stickerTracks.size) {
              // ── All stickers done ──────────────────────────────────────────
              runOnUiThread {
                  dismissTextBurnProgress()
                  Toast.makeText(this, getString(R.string.saved_successfully), Toast.LENGTH_LONG).show()
                  videoUrl = inputPath
                  binding.overlayContainer.removeAllViews()
                  stickerTracks.clear()
                  binding.videoView.setVideoPath(inputPath)
                  binding.videoView.start()
                  finish()
              }
              return
          }

          val track = stickerTracks[index]
          val overlay = track.overlay
          val overlayBinding = track.overlayBinding

          // ── 1. Position: view coords → video pixel coords ──────────────────
          val params = overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams ?: run {
              // Skip broken track, move to next
              burnStickerAtIndex(index + 1, inputPath)
              return
          }

          val viewX = params.leftMargin.toFloat()
          val viewY = params.topMargin.toFloat()
          val (videoX, videoY) = overlayViewToVideoPixels(viewX, viewY)

          // ── 2. Size: view size → video pixel size ──────────────────────────
          val imageView = overlayBinding.overlayImageView
          val videoW = overlaySizeToVideoScale(
              imageView.width.toFloat(), binding.overlayContainer.width.toFloat(), videoNativeWidth
          )
          val videoH = overlaySizeToVideoScale(
              imageView.height.toFloat(), binding.overlayContainer.height.toFloat(), videoNativeHeight
          )

          // ── 3. Time range from actual clip position ────────────────────────
          val startSec = overlay.startTimeMs / 1000.0
          val endSec = overlay.endTimeMs / 1000.0

          // ── 4. Convert drawable → temp PNG file ───────────────────────────
          val imagePath = drawableToFile(this, overlay.drawable)

          Log.d(
              "BurnSticker", """
          Sticker #$index
          Position: videoX=$videoX, videoY=$videoY
          Size: videoW=$videoW, videoH=$videoH
          Time: startSec=$startSec, endSec=$endSec
          Input: $inputPath
      """.trimIndent()
          )

          // ── 5. Update progress title ───────────────────────────────────────
          runOnUiThread {
              textBurnProgressDialog?.setTitle(
                  getString(R.string.adding_sticker_progress, index + 1, stickerTracks.size)
              )
              textBurnProgressDialog?.progress = 0
          }

          // ── 6. Run FFmpeg for this sticker ─────────────────────────────────
          VideoTextBurner.burnSticker(
              context = this,
              inputPath = inputPath,
              imagePath = imagePath,
              xPx = videoX,
              yPx = videoY,
              width = videoW,
              height = videoH,
              startSec = startSec,
              endSec = endSec,
              videoDurationMs = binding.videoView.duration.toLong(),
              outputDir = getSavedDirectoryUri()!!,
              onProgress = { percent ->
                  // Scale progress to current sticker's share
                  // e.g. sticker 2 of 3 → progress spans 33%–66%
                  val totalStickers = stickerTracks.size
                  val base = (index * 100f / totalStickers).toInt()
                  val share = (percent / totalStickers)
                  val overall = (base + share).coerceIn(0, 100)

                  runOnUiThread {
                      textBurnProgressDialog?.progress = overall
                      textBurnProgressDialog?.setMessage(
                          getString(R.string.sticker_progress, index + 1, totalStickers, percent)
                      )
                  }
              },
              onSuccess = { outputPath ->
                  // ── Chain: use this output as next sticker's input ─────────
                  burnStickerAtIndex(index + 1, outputPath)
              },
              onError = { errorMessage ->
                  runOnUiThread {
                      dismissTextBurnProgress()
                      Log.e("Export", "sticker ${index + 1} failed: $errorMessage")
                      Toast.makeText(
                          this, getString(R.string.sticker_burn_failed, index + 1), Toast.LENGTH_LONG
                      ).show()
                      // Restore UI so user can retry
                      binding.overlayContainer.visibility = View.VISIBLE
                      binding.okTextOverlay.visibility = View.VISIBLE
                      currentMode = EditorMode.STICKER
                  }
              })
      }
  */
    private fun drawableToFile(context: Context, drawable: Drawable): String {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?: drawable.toBitmap()
        val file = File(context.cacheDir, "sticker_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return file.absolutePath
    }

    private fun overlaySizeToVideoScale(
        viewSize: Float, containerSize: Float, videoSize: Int
    ): Int {
        if (containerSize <= 0f) return viewSize.toInt()
        val ratio = viewSize / containerSize
        return (ratio * videoSize).toInt()
    }

    private fun startBurnText() {
        if (textTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_text_overlays_added), Toast.LENGTH_SHORT).show()
            return
        }
        if (videoUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.no_video_loaded), Toast.LENGTH_SHORT).show()
            return
        }

        val textBurnList = textTracks.mapNotNull { track ->
            val params = track.overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams
                ?: return@mapNotNull null

            val (videoX, videoY) = overlayViewToVideoPixels(
                params.leftMargin.toFloat(), params.topMargin.toFloat()
            )
            val videoFontSize = overlayFontSizeToVideoPixels(
                track.overlayBinding.overlayText.textSize
            )
            val fontPath = VideoTextBurner.extractFontFromAssets(
                this, track.data.fontAssetFileName
            )

            VideoTextBurner.TextBurnData(
                text = track.data.text,
                xPx = videoX,
                yPx = videoY,
                fontSizePx = videoFontSize,
                fontFilePath = fontPath!!,
                fontColor = track.data.color,
                startSec = track.data.startTimeMs / 1000.0,
                endSec = track.data.endTimeMs / 1000.0
            )
        }

        if (textBurnList.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_valid_text_to_burn), Toast.LENGTH_SHORT).show()
            return
        }

        showTextBurnProgress()
        binding.overlayContainer.visibility = View.GONE
        binding.okTextOverlay.visibility = View.GONE

        VideoTextBurner.burn(
            context = this,
            inputPath = videoUrl,
            texts = textBurnList,
            videoDurationMs = binding.videoView.duration.toLong(),
            onProgress = { percent ->
                runOnUiThread {
                    textBurnProgressDialog?.progress = percent
                    textBurnProgressDialog?.setMessage(getString(R.string.rendering, percent))
                }
            },
            onSuccess = { outputPath ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Toast.makeText(this, getString(R.string.text_saved, outputPath), Toast.LENGTH_LONG).show()
                    videoUrl = outputPath
                    binding.overlayContainer.removeAllViews()
                    textTracks.clear()
                    binding.videoView.setVideoPath(outputPath)
                    binding.videoView.start()
                    finish()
                }
            },
            onError = { errorMessage ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Log.e("Export", "text failed: $errorMessage"); Toast.makeText(this, getString(R.string.text_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    binding.overlayContainer.visibility = View.VISIBLE
                    currentMode = EditorMode.TEXT_OVERLAY
                }
            })
    }

    private fun showTextBurnProgress() {
        textBurnProgressDialog?.dismiss()
        textBurnProgressDialog = ProgressDialog(this).apply {
            if (currentMode == EditorMode.TEXT_OVERLAY) {
                setTitle(getString(R.string.adding_text_overlay))
            } else if (currentMode == EditorMode.STICKER) {
                setTitle(getString(R.string.adding_sticker))
            }
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
        // ✅ Final export uses the full-screen screen instead — every success/error path of
        // the burn steps ends here, so hiding it here covers all of them.
        if (isExportActive) hideExportProgressScreen()
    }

    // ── Crop ───────────────────────────────────────────────────────────────
    private fun enterCropMode() {
        when (currentMode) {
            EditorMode.CROP -> {
                currentMode = EditorMode.NONE
                cropRatioHelper.hide()
                binding.cropView.visibility = View.GONE
                binding.okTextOverlay.visibility = View.GONE
            }

            EditorMode.TEXT_OVERLAY -> {
                binding.overlayContainer.removeAllViews()
                currentMode = EditorMode.CROP
                binding.cropView.visibility = View.VISIBLE
                binding.okTextOverlay.visibility = View.VISIBLE   // ✅ FIX
                cropRatioHelper.show()
            }

            EditorMode.NONE -> {
                currentMode = EditorMode.CROP
                binding.cropView.visibility = View.VISIBLE
                binding.okTextOverlay.visibility = View.VISIBLE   // ✅ FIX
                cropRatioHelper.show()
            }

            EditorMode.STICKER -> {
                currentMode = EditorMode.STICKER
                binding.cropView.visibility = View.GONE
                binding.okTextOverlay.visibility = View.GONE
            }

            EditorMode.MUSIC -> {

            }
        }
        refreshSaveButtonVisibility()
    }

    private fun showCropProgress() {
        cropProgressDialog?.dismiss()
        cropProgressDialog = ProgressDialog(this).apply {
            setTitle(getString(R.string.cropping_video_title))
            setMessage(getString(R.string.starting))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100; progress = 0; isIndeterminate = false
            setCancelable(true)
            setOnCancelListener { dismissCropProgress() }
            show()
        }
    }

    private fun dismissCropProgress() {
        cropProgressDialog?.dismiss()
        cropProgressDialog = null
    }

    private fun startCrop() {
        val normRect = binding.cropView.getNormalisedCropRect() ?: run {
            Toast.makeText(this, getString(R.string.crop_area_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (videoUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.no_video_loaded), Toast.LENGTH_SHORT).show()
            return
        }
        showCropProgress()
        binding.cropView.visibility = View.GONE
        binding.okTextOverlay.visibility = View.GONE
        cropRatioHelper.hide()

        VideoCropper.crop(
            context = this,
            inputPath = videoUrl,
            normalisedRect = normRect,
            onProgress = { percent ->
                runOnUiThread {
                    cropProgressDialog?.progress = percent
                    cropProgressDialog?.setMessage(getString(R.string.cropping_progress, percent))
                }
            },
            onSuccess = { outputPath ->
                runOnUiThread {
                    dismissCropProgress()
                    Toast.makeText(this, getString(R.string.crop_saved, outputPath), Toast.LENGTH_LONG).show()
                    binding.videoView.setVideoPath(outputPath)
                    binding.videoView.start()
                    videoUrl = outputPath
                    finish()
                }
            },
            onError = { errorMessage ->
                runOnUiThread {
                    dismissCropProgress()
                    Log.e("Export", "crop failed: $errorMessage"); Toast.makeText(this, getString(R.string.crop_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    binding.cropView.visibility = View.VISIBLE
                    binding.okTextOverlay.visibility = View.VISIBLE
                    cropRatioHelper.show()
                }
            })
    }

    // ── Timeline / Thumbnail editor view ──────────────────────────────────
    private fun timeToPx(timeMs: Long): Int = ((timeMs / 1000f) * pxPerSecond).toInt()
    private fun pxToTime(px: Int): Long = ((px / pxPerSecond) * 1000).toLong()

    /** "M:SS.d" — e.g. 0:00.4 / 0:18.0 */
    private fun formatShortTime(ms: Int): String {
        val totalTenths = ms / 100
        val minutes = totalTenths / 600
        val seconds = (totalTenths / 10) % 60
        val tenths = totalTenths % 10
        return String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, tenths)
    }

    /** "MM:SS" ruler tick label — e.g. 00:00 / 00:02 */
    private fun formatRulerLabel(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    /** Keeps the clip-row overlay AND the time ruler synced to the thumbnail scroll. */
    private fun syncTimelineTranslation() {
        val tx = -thumbnailScrollOffset.toFloat()
        binding.overlayTrackContainer.translationX = tx
        binding.rulerContainer.translationX = tx
        Log.d(
            "TimelineDebug",
            "sync thumbOffset=$thumbnailScrollOffset rulerOffset=${-binding.rulerContainer.translationX.toInt()} rowOffset=${-binding.overlayTrackContainer.translationX.toInt()}"
        )
    }

    /** VideoView.duration can be -1 while it re-prepares (e.g. after returning from
     *  another Activity). Fall back to the cached value from onPrepared. */
    private fun safeVideoDurationMs(): Long {
        val s = pendingTrimStartMs
        val e = pendingTrimEndMs
        // ✅ Trimmed → the editor's length is the trim range
        if (s != null && e != null && e > s) return e - s
        return fullVideoDurationMs()
    }

    /** Length of the whole ORIGINAL video loaded in the VideoView (ignores the trim). */
    private fun fullVideoDurationMs(): Long {
        val d = binding.videoView.duration.toLong()
        return if (d > 0L) d else videoDurationMsCached
    }

    // ── Non-destructive trim: editor time ↔ original-video time ────────────
    // Editor time 0 = original time pendingTrimStartMs. Every VideoView seek / position read
    // goes through these helpers, so the preview behaves exactly like the trimmed video.

    /** Trim start in original-video time (0 = untrimmed). */
    private fun trimStart(): Long = pendingTrimStartMs ?: 0L

    /** Current playback position in EDITOR time, clamped to [0, editor length]. */
    private fun editorPosMs(): Long =
        (binding.videoView.currentPosition.toLong() - trimStart())
            .coerceIn(0L, safeVideoDurationMs().coerceAtLeast(0L))

    /**
     * Seeks the preview to [editorMs] (EDITOR time). [precise] = SEEK_CLOSEST on API 26+
     * (exact frame — for explicit seeks and when scrolling settles); otherwise the fast
     * keyframe seek used while the user is dragging the timeline.
     */
    private fun seekEditorTo(editorMs: Long, precise: Boolean = true) {
        val start = trimStart()
        val end = start + safeVideoDurationMs().coerceAtLeast(0L)
        val target = (start + editorMs.coerceAtLeast(0L)).coerceAtMost(end)
        val mp = videoMediaPlayer
        if (precise && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mp != null) {
            try {
                mp.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                return
            } catch (e: IllegalStateException) {
                // player not ready / released → fall back to VideoView's own seek
            }
        }
        binding.videoView.seekTo(target.toInt())
    }

    /** Playback reached the end of the video OR of the trim range. */
    private fun onPlaybackReachedEnd() {
        if (isRecordingVoice) finishVoiceRecording()   // auto-stop at video end
        if (binding.videoView.isPlaying) binding.videoView.pause()
        binding.playStopButton.setImageDrawable(
            ResourcesCompat.getDrawable(resources, R.drawable.ic_play1, null)
        )
        setOverlayControlsVisible(true)
        hideAllOverlays()
        stopMusicPreview()
    }

    /** Paused preview image = first frame of the (trimmed) video. */
    private fun refreshPreviewThumbnail() {
        val frameUs = if (trimStart() > 0L) trimStart() * 1000L else 1_000_000L
        Glide.with(this).asBitmap().load(videoUrl).frame(frameUs).into(binding.thumbnail)
    }

    /** Clamps a clip's [start, end) to [0, videoDuration] so a clip can never sit before
     *  the first thumbnail or after the last one. Used on add AND on draft restore, since
     *  both paths funnel through addTextToTimeline/addStickerToTimeline/addMusicToTimeline. */
    private fun clampClipRange(startMs: Long, endMs: Long): Pair<Long, Long> {
        val duration = safeVideoDurationMs().coerceAtLeast(0)
        val clampedStart = startMs.coerceIn(0, duration)
        val clampedEnd = endMs.coerceIn(clampedStart, duration)
        return clampedStart to clampedEnd
    }

    /** Populates the ruler with a tick label every 2 real seconds. Idempotent + re-run safe. */
    private fun setupTimeRuler() {
        if (totalWidth <= 0) {
            Log.d("TimelineDebug", "setupTimeRuler EARLY-RETURN totalWidth<=0 totalWidth=$totalWidth")
            return
        }
        binding.rulerContainer.removeAllViews()
        val totalDurationMs = safeVideoDurationMs().coerceAtLeast(0)   // editor (trimmed) length
        Log.d("TimelineDebug", "setupTimeRuler PROCEEDING totalDurationMs=$totalDurationMs totalWidth=$totalWidth")
        var tickMs = 0L
        while (tickMs <= totalDurationMs) {
            val label = com.google.android.material.textview.MaterialTextView(this).apply {
                text = formatRulerLabel(tickMs)
                textSize = 10f
                setTextColor(0x99FFFFFF.toInt())
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = halfWidth + timeToPx(tickMs)
            }
            label.layoutParams = params
            binding.rulerContainer.addView(label)
            tickMs += 2000L
        }
        binding.rulerContainer.layoutParams.width = totalWidth + halfWidth * 2
        binding.rulerContainer.requestLayout()
        binding.rulerContainer.invalidate()
    }

    /**
     * Single scroll listener that drives seeking from the timeline (replaces the old SeekBar).
     * Guarded by the "scroll_linked" tag so it only ever attaches once — the tag is also
     * checked by the addTextToTimeline/addStickerToTimeline/addMusicToTimeline call sites.
     */
    private fun setupTimelineScrollSync() {
        if (binding.thumbnailRecyclerView.tag == "scroll_linked") return
        binding.thumbnailRecyclerView.tag = "scroll_linked"
        binding.thumbnailRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val upperBound = if (totalWidth > 0) totalWidth else Int.MAX_VALUE
                thumbnailScrollOffset = (thumbnailScrollOffset + dx).coerceIn(0, upperBound)
                syncTimelineTranslation()

                val currentTimeMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
                val videoDurationMs = safeVideoDurationMs()

                // ✅ Never seek during playback (the progress tick drives the scroll then).
                // While the user scrubs, use the FAST keyframe seek; one precise seek
                // happens when scrolling becomes idle (see onScrollStateChanged).
                if (currentTimeMs >= videoDurationMs) {
                    hideAllOverlays()
                    if (!binding.videoView.isPlaying) {
                        seekEditorTo(videoDurationMs, precise = false)
                    }
                    return
                }

                if (!binding.videoView.isPlaying) {
                    seekEditorTo(currentTimeMs, precise = false)
                }

                updateAllStickerVisibility(currentTimeMs)
                updateAllTextVisibility(currentTimeMs)
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        handler.removeCallbacks(updateTimeTask)
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // One precise (SEEK_CLOSEST) seek once scrubbing has settled
                        if (!binding.videoView.isPlaying) {
                            seekEditorTo(
                                pxToTime(thumbnailScrollOffset).coerceIn(0L, safeVideoDurationMs()),
                                precise = true
                            )
                        }
                        updateProgressBar(binding.videoView)
                        if (binding.videoView.isPlaying) {
                            stopMusicPreview()
                            startMusicPreview()
                        }
                    }
                }
            }
        })
    }

    /** Sizes rowsViewport to match the actual row count — 1 row = 32dp, up to 4 rows =
     *  128dp; beyond 4 it stays capped at 128dp and rows scroll inside it. Empty = fully
     *  collapsed (GONE) so the starting screen never reserves this gap. */
    private fun updateRowsAreaHeight() {
        val rowCount = trackList.size
        if (rowCount == 0) {
            binding.rowsViewport.isGone = true
            return
        }
        binding.rowsViewport.isVisible = true
        val visibleRows = rowCount.coerceAtMost(4)
        val targetHeight = visibleRows * 32.dpToPx()
        val params = binding.rowsViewport.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.rowsViewport.layoutParams = params
            binding.rowsViewport.post {
                clampRowsScroll()
                recalculateVideoViewSize()
            }
        }
    }

    /** How far the row stack (bottom-aligned in rowsViewport) currently overflows the
     *  fixed-height viewport — 0 while <=3 rows fit, growing as more rows are added. */
    private fun rowsContentOverflowPx(): Int =
        (binding.timelineScroll.height - binding.rowsViewport.height).coerceAtLeast(0)

    /** Keeps the manual vertical scroll of the row stack within [0, +overflow].
     *  0 = rest position (bottom-gravity already shows the rows nearest the thumbnails);
     *  +overflow = fully scrolled to reveal the newest (topmost) row. */
    private fun clampRowsScroll() {
        val overflow = rowsContentOverflowPx()
        binding.timelineScroll.translationY =
            binding.timelineScroll.translationY.coerceIn(0f, overflow.toFloat())
    }

    /** Scrolls the row stack so the newest (topmost) row is visible — called right after a
     *  new overflow row is created so the user can see where their clip landed. No-op (stays
     *  at the bottom-aligned rest position) while everything still fits in the viewport. */
    private fun revealNewestRow() {
        binding.timelineScroll.post {
            binding.timelineScroll.translationY = rowsContentOverflowPx().toFloat()
        }
    }

    /**
     * Single touch listener on the (non-natively-scrolling) timelineScroll that handles:
     *  - a plain tap on empty timeline space → deselect the current clip
     *  - a vertical drag → manually scroll the row stack (translationY) when it overflows
     *    rowsViewport's fixed height; horizontal position is still driven by the thumbnail
     *    strip only (this listener never touches translationX).
     * Set up once — replaces the old per-add-call touch listener.
     */
    private fun setupTimelineTapAndVerticalScroll() {
        var downX = 0f
        var downY = 0f
        var startTranslationY = 0f
        var dragging = false
        binding.timelineScroll.apply {
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startTranslationY = translationY
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && kotlin.math.abs(dy) > 12f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                            dragging = true
                        }
                        if (dragging) {
                            val overflow = rowsContentOverflowPx()
                            translationY = (startTranslationY + dy).coerceIn(0f, overflow.toFloat())
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && kotlin.math.abs(dx) < 12f && kotlin.math.abs(dy) < 12f) {
                            clearOverlaySelection()
                        }
                        true
                    }

                    else -> true
                }
            }
        }
    }

    /** Thumbnail strip par sirf tap (drag nahi) -> video bar select. Scroll ko nahi rokta. */
    private fun setupThumbnailTapSelect() {
        if (binding.thumbnailRecyclerView.getTag(R.id.thumbnailRecyclerView) == "tap_linked") return
        binding.thumbnailRecyclerView.setTag(R.id.thumbnailRecyclerView, "tap_linked")
        var downX = 0f
        var downY = 0f
        binding.thumbnailRecyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(e.rawX - downX) < 12f &&
                            kotlin.math.abs(e.rawY - downY) < 12f &&
                            currentMode == EditorMode.MUSIC
                        ) {
                            clearOverlaySelection()
                            showVideoEditBar()
                        }
                    }
                }
                return false
            }
        })
    }

    // ── Timeline-driven progress (replaces the old SeekBar) ────────────────

    private fun progressBarPrepare(@Suppress("UNUSED_PARAMETER") mediaPlayer: MediaPlayer) {
        binding.tvTotalDuration.text = formatShortTime(safeVideoDurationMs().toInt())   // trimmed length
        binding.tvCurrentTime.text = formatShortTime(0)
    }

    private fun updateProgressBar(videoView: VideoView) {
        // ✅ Exactly ONE progress tick: remove the previous one before starting a new one
        // (two ticks = two scrollBy() calls per frame = jerky timeline).
        if (::updateTimeTask.isInitialized) handler.removeCallbacks(updateTimeTask)
        updateTimeTask = object : Runnable {
            override fun run() {
                // ✅ Stop exactly at the trim end, like the end of the (trimmed) video
                val trimEnd = pendingTrimEndMs
                if (trimEnd != null && videoView.isPlaying && videoView.currentPosition >= trimEnd) {
                    onPlaybackReachedEnd()
                }
                val currentTime = editorPosMs().toInt()   // editor (trimmed) time
                binding.tvCurrentTime.text = formatShortTime(currentTime)

                if (binding.thumbnailRecyclerView.isVisible && videoView.isPlaying) {
                    // ✅ targetPx = timeToPx(currentTime) ONLY — no halfWidth addition
                    // halfWidth is padding, not content offset; pxToTime(offset) maps directly
                    val targetPx = timeToPx(currentTime.toLong())
                    binding.thumbnailRecyclerView.scrollBy(targetPx - thumbnailScrollOffset, 0)
                    // thumbnailScrollOffset is updated by onScrolled via dx accumulation
                    syncTimelineTranslation()

                    val videoDurationMs = safeVideoDurationMs()
                    if (currentTime.toLong() >= videoDurationMs) {
                        hideAllOverlays()
                    } else {
                        updateAllTextVisibility(currentTime.toLong())
                        updateAllStickerVisibility(currentTime.toLong())
                    }
                }
                handler.postDelayed(this, 33)
            }
        }
        handler.postDelayed(updateTimeTask, 100)
    }

    // ── OverLay Sticker View ───────────────────────────────────────────────────────────────
    private fun overlayStickerView() {
        binding.apply {

            when (currentMode) {
                EditorMode.STICKER -> {
                    editorStickerPanel.stickerEditorPanel.isGone = true
                    currentMode = EditorMode.NONE
                    refreshSaveButtonVisibility()   // ✅ REPLACED — yeh return se pehle chahiye
                    return
                }

                EditorMode.CROP -> {
                    currentMode = EditorMode.STICKER
                    cropRatioHelper.hide()
                    binding.cropView.visibility = View.GONE
                    binding.okTextOverlay.visibility = View.GONE
                }

                EditorMode.TEXT_OVERLAY -> {
                    clearOverlayContainerKeepThumbnail()
                    currentMode = EditorMode.NONE
                    binding.cropView.visibility = View.GONE
                    binding.okTextOverlay.visibility = View.GONE
                    cropRatioHelper.hide()
                }

                EditorMode.MUSIC -> {

                }

                EditorMode.NONE -> {

                }
            }
            currentMode = EditorMode.STICKER
            refreshSaveButtonVisibility()
            binding.featureRecyclerView.isGone = true

            editorStickerPanel.viewPager.adapter =
                StickerPagerAdapter(this@EditVideoActivity) { drawable ->
                    addStickerFromPanel(drawable)
                }
            editorStickerPanel.viewPager.offscreenPageLimit = 1
            editorStickerPanel.stickerEditorPanel.isVisible = true

            stickerTabMediator?.detach()
            stickerTabMediator = TabLayoutMediator(
                editorStickerPanel.tabLayout, editorStickerPanel.viewPager, true, false
            ) { tab, position ->
                val size = 48.dpToPx()
                tab.customView = if (position == 0) {
                    android.widget.ImageView(this@EditVideoActivity).apply {
                        layoutParams = ViewGroup.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
                        setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                        setImageResource(R.drawable.ic_image)
                        setColorFilter(android.graphics.Color.WHITE)
                    }
                } else {
                    android.widget.TextView(this@EditVideoActivity).apply {
                        layoutParams = ViewGroup.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
                        gravity = android.view.Gravity.CENTER
                        textSize = 20f
                        text = EmojiData.categories[position - 1].second.first()
                    }
                }
            }.also { it.attach() }

            editorStickerPanel.btnStickerDone.setOnClickListener { closeStickerPanel() }
        }
    }
    private fun addStickerToTimeline(
        overlay: StickerOverlay,
        existingOverlayBinding: ItemOverlayBinding? = null,
        resizeAfter: Boolean = true
    ) {
        // ✅ Same source as initializeTimelineLayout() — thumbnailRecyclerView's real width,
        // never a screen-metrics guess, so this can never drift from thumbnails/ruler.
        if (halfWidth == 0 && binding.thumbnailRecyclerView.width > 0) {
            halfWidth = binding.thumbnailRecyclerView.width / 2
        }

        if (totalWidth == 0) {

            binding.container.post {
                addStickerToTimeline(overlay, existingOverlayBinding, resizeAfter)
            }
            return
        }
        // ✅ Never let a clip (new OR restored from a draft) sit before t=0 or after video end.
        val (clampedStart, clampedEnd) = clampClipRange(overlay.startTimeMs, overlay.endTimeMs)
        overlay.startTimeMs = clampedStart
        overlay.endTimeMs = clampedEnd

        Log.d("Timeline", "STICKER ADD: halfWidth=$halfWidth pxPerSecond=$pxPerSecond totalWidth=$totalWidth thumbnailScrollOffset=$thumbnailScrollOffset startTimeMs=${overlay.startTimeMs}")
        val track = findOrCreateRowForClip(overlay.startTimeMs, overlay.endTimeMs)
        val clipBinding = TimelineTextClipBinding.inflate(layoutInflater)

        val duration = overlay.endTimeMs - overlay.startTimeMs
        val clipWidth = timeToPx(duration)

        val params = FrameLayout.LayoutParams(clipWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = halfWidth + timeToPx(overlay.startTimeMs)   // ✅ actual start time se position
        }
        clipBinding.clipRoot.layoutParams = params
        clipBinding.clipLabel.isGone = true
        clipBinding.sticker.isVisible = true
        clipBinding.clipRoot.background = clipBackground(R.color.sticker_track_color)

        clipBinding.sticker.setImageDrawable(
            overlay.drawable.constantState?.newDrawable(resources)?.mutate() ?: overlay.drawable
        )
        track.addView(clipBinding.root)
        Log.d(
            "TimelineDebug",
            "add type=STICKER startTimeMs=${overlay.startTimeMs} endTimeMs=${overlay.endTimeMs} row=${trackList.indexOf(track)}"
        )


        val overlayBinding = existingOverlayBinding
            ?: ItemOverlayBinding.inflate(layoutInflater, binding.overlayContainer, false).apply {
                val stickerSize = 80.dpToPx()
                root.layoutParams = FrameLayout.LayoutParams(stickerSize, stickerSize).apply {
                    leftMargin = 20
                    topMargin = 20
                }
                overlayText.isGone = true
            }

        // ✅ Original border drawable ek baar cache karo
        if (!overlayBorderCache.containsKey(overlayBinding)) {
            overlayBorderCache[overlayBinding] = overlayBinding.overlayRoot.background
        }

        binding.overlayContainer.isVisible = true
        if (overlayBinding.root.parent == null) {
            binding.overlayContainer.addView(overlayBinding.root)
        }
        overlayBinding.overlayImageView.isVisible = true
        overlayBinding.overlayImageView.setImageDrawable(overlay.drawable)

        overlayBinding.overlayImageView.post {
            overlayBinding.overlayImageView.invalidate()
            overlayBinding.overlayImageView.requestLayout()
        }

        val currentMs = pxToTime(thumbnailScrollOffset.coerceAtLeast(0))
        overlayBinding.root.isVisible = currentMs in overlay.startTimeMs..overlay.endTimeMs


        val stickerTrack = StickerTrack(overlay, overlayBinding, clipBinding)
        stickerTracks.add(stickerTrack)

        // ── Edit (pencil) button — replaces this sticker in place ──────────
        overlayBinding.editContainer.isVisible = true
        overlayBinding.editContainer.setOnClickListener {
            editStickerOverlay(stickerTrack)
        }


        overlayBinding.closeContainer.setOnClickListener {
            if (selectedOverlayBinding === overlayBinding) clearOverlaySelection()

            if (!isUndoRedoRunning) pushUndo(UndoAction(
                undo = { addStickerToTimeline(overlay, overlayBinding) },
                redo = { stickerTracks.firstOrNull { it.overlayBinding === overlayBinding }?.overlayBinding?.closeContainer?.performClick() }
            ))

            binding.overlayContainer.removeView(overlayBinding.root)

            removeClipAndRowIfEmpty(clipBinding.clipRoot)


            stickerTracks.removeAll { it === stickerTrack }
            overlayBorderCache.remove(overlayBinding)


            refreshSaveButtonVisibility()
            saveEditStateToCache()   //


            if (trackList.isEmpty()) {
                exitTimelineMode()
            } else {
                enterTimelineMode()
            }


            binding.overlayTrackContainer.post {
                syncTimelineTranslation()
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

        binding.thumbnailRecyclerView.post {

            setupThumbnails()
        }

        setupTimelineScrollSync()
        updateAllStickerVisibility(pxToTime(thumbnailScrollOffset).coerceAtLeast(0))
        setupClipDraggingForSticker(clipBinding, overlay)
        if (resizeAfter) pushUndo(UndoAction(
            undo = { stickerTracks.firstOrNull { it.overlayBinding === overlayBinding }?.overlayBinding?.closeContainer?.performClick() },
            redo = { addStickerToTimeline(overlay, overlayBinding) }
        ))
        if (resizeAfter) enterTimelineMode()
        binding.overlayTrackContainer.post {
            syncTimelineTranslation()
        }
        if (resizeAfter) saveEditStateToCache()
    }

    private fun updateAllStickerVisibility(currentTimeMs: Long) {
        val duration = safeVideoDurationMs()
        for (track in stickerTracks) {
            val shouldBeVisible =
                currentTimeMs in track.overlay.startTimeMs..track.overlay.endTimeMs && currentTimeMs < duration

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
        var neighborLeftBound = halfWidth
        var neighborRightBound = maxClipRightEdge()

        fun syncOverlayTimes(params: FrameLayout.LayoutParams) {
            overlay.startTimeMs = pxToTime((params.leftMargin - halfWidth).coerceAtLeast(0))
            overlay.endTimeMs =
                pxToTime((params.leftMargin + params.width - halfWidth).coerceAtLeast(0))
            val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0)
            updateAllStickerVisibility(currentMs)
        }

        fun logMoveResize() {
            val row = root.parent as? FrameLayout
            Log.d(
                "TimelineDebug",
                "move/resize type=STICKER startTimeMs=${overlay.startTimeMs} endTimeMs=${overlay.endTimeMs} row=${row?.let { trackList.indexOf(it) }}"
            )
        }

        var downRawXForTap = 0f
        var hasDraggedClip = false

        root.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val edge = minOf(50f, v.width * 0.25f)
                    if (event.x < edge || event.x > v.width - edge) return@setOnTouchListener false
                    isDraggingClip = true
                    lastClipRawX = event.rawX
                    downRawXForTap = event.rawX
                    hasDraggedClip = false
                    initialClipMargin = params.leftMargin
                    val row = v.parent as? FrameLayout
                    if (row != null) {
                        val bounds = neighborBoundsFor(row, v)
                        neighborLeftBound = bounds.first
                        neighborRightBound = bounds.second
                    }
                    (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingClip) return@setOnTouchListener false
                    if (kotlin.math.abs(event.rawX - downRawXForTap) > 12f) hasDraggedClip = true
                    val dx = (event.rawX - lastClipRawX).toInt()
                    lastClipRawX = event.rawX
                    initialClipMargin += dx
                    // ✅ Can't go before t=0, past video end, or into a neighbour's space
                    val maxLeft = maxClipRightEdge() - params.width
                    val minLeft = maxOf(halfWidth, neighborLeftBound)
                    val maxLeftClamped = minOf(maxLeft, neighborRightBound - params.width)
                    params.leftMargin = initialClipMargin.coerceIn(minLeft, maxOf(minLeft, maxLeftClamped))
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClip = false
                    (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    if (hasDraggedClip) logMoveResize()
                    if (!hasDraggedClip && event.action == MotionEvent.ACTION_UP) {
                        val ob = stickerTracks.firstOrNull { it.overlay === overlay }?.overlayBinding
                        if (ob != null) selectOverlayForEditing(ob, root, overlay.startTimeMs)
                    }
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
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborLeftBound = neighborBoundsFor(row, root).first
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
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
                    // ✅ Left edge can't go before t=0 or into the left neighbour
                    params.leftMargin = initialMargin.coerceAtLeast(maxOf(halfWidth, neighborLeftBound))
                    params.width = initialWidth.coerceAtLeast(100)
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeft = false
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    logMoveResize()
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
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborRightBound = neighborBoundsFor(row, root).second
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        true
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingRight) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialWidth += dx
                    // ✅ Right edge can't exceed video end or the right neighbour
                    val maxWidth = minOf(maxClipRightEdge(), neighborRightBound) - params.leftMargin
                    params.width = initialWidth.coerceIn(100, maxOf(100, maxWidth))
                    root.layoutParams = params
                    syncOverlayTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRight = false
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(
                        false
                    )
                    logMoveResize()
                    true
                }

                else -> false
            }
        }
    }

    // ── Shared-row timeline model ───────────────────────────────────────────
    // Rows are shared by all clip types (text/sticker/music). A clip is placed
    // in the first existing row it doesn't time-overlap with; if none has room,
    // a brand-new row is created ABOVE the existing ones (index 0).

    private fun rangesOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart < bEnd && bStart < aEnd

    /** All (startMs, endMs) ranges of clips — of any type — currently placed in [row]. */
    private fun clipTimeRangesInRow(row: FrameLayout): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        textTracks.forEach {
            if (it.clipBinding.clipRoot.parent === row) ranges.add(it.data.startTimeMs..it.data.endTimeMs)
        }
        stickerTracks.forEach {
            if (it.clipBinding.clipRoot.parent === row) ranges.add(it.overlay.startTimeMs..it.overlay.endTimeMs)
        }
        musicTracks.forEach {
            if (it.clipBinding.clipRoot.parent === row) ranges.add(it.overlay.startTimeMs..it.overlay.endTimeMs)
        }
        return ranges
    }

    /** Finds the first existing row with no time overlap for [startMs, endMs), or creates a new one. */
    private fun findOrCreateRowForClip(startMs: Long, endMs: Long): FrameLayout {
        for (row in trackList) {
            val occupied = clipTimeRangesInRow(row)
            val fits = occupied.none { rangesOverlap(startMs, endMs, it.first, it.last) }
            if (fits) return row
        }
        return createNewTrack()
    }

    /**
     * Immediate left/right sibling clip bounds within [row] (excluding [selfRoot]),
     * used to clamp drag/resize so a clip can't overlap a neighbour in the same row.
     * Falls back to the existing halfWidth/maxClipRightEdge() global bounds on
     * whichever side has no neighbour.
     */
    private fun neighborBoundsFor(row: FrameLayout, selfRoot: View): Pair<Int, Int> {
        val selfParams = selfRoot.layoutParams as FrameLayout.LayoutParams
        var leftBound = halfWidth
        var rightBound = maxClipRightEdge()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child === selfRoot) continue
            val p = child.layoutParams as FrameLayout.LayoutParams
            val childRight = p.leftMargin + p.width
            if (childRight <= selfParams.leftMargin && childRight > leftBound) {
                leftBound = childRight
            } else if (p.leftMargin >= selfParams.leftMargin + selfParams.width && p.leftMargin < rightBound) {
                rightBound = p.leftMargin
            }
        }
        return leftBound to rightBound
    }

    /** Removes [clipRoot] from its row, and removes the row itself if it's now empty. */
    private fun removeClipAndRowIfEmpty(clipRoot: View) {
        if (selectedClipRoot === clipRoot) clearOverlaySelection()
        val row = clipRoot.parent as? FrameLayout ?: return
        row.removeView(clipRoot)
        if (row.childCount == 0) {
            binding.overlayTrackContainer.removeView(row)
            trackList.remove(row)
            updateRowsAreaHeight()
        }
        binding.overlayTrackContainer.post { clampRowsScroll() }
    }

    fun createNewTrack(): FrameLayout {
        val trackWidth = (totalWidth + halfWidth * 2).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels * 3)

        // ✅ Fixed 28dp row height + 4dp gap above each row (InShot-style row sizing).
        val track = FrameLayout(this).apply {
            // ✅ LinearLayout.LayoutParams — overlayTrackContainer is a LinearLayout
            layoutParams = LinearLayout.LayoutParams(trackWidth, 28.dpToPx()).apply {
                topMargin = 4.dpToPx()
            }
        }

        // ✅ New overflow rows stack ABOVE existing rows (index 0 = topmost).
        binding.overlayTrackContainer.addView(track, 0)
        trackList.add(0, track)
        updateRowsAreaHeight()
        binding.overlayTrackContainer.post {
            syncTimelineTranslation()
        }
        // ✅ Scroll the row stack so a newly created overflow row (4th+) is visible —
        // no-op while <=3 rows still fit inside rowsViewport.
        revealNewestRow()
        return track
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableStickerResize(
        overlayBinding: ItemOverlayBinding,
        container: FrameLayout,
        stickerNativeWidth: Int = 0,
        stickerNativeHeight: Int = 0
    ) {
        val root = overlayBinding.overlayRoot
        var downRawX = 0f
        var downRawY = 0f
        var startWidth = 0
        var startHeight = 0
        var pendingWidth = 0
        var pendingHeight = 0
        val minSize = 40.dpToPx()

        overlayBinding.resizeContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = root.width
                    startHeight = root.height
                    pendingWidth = startWidth
                    pendingHeight = startHeight
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()

                    // ✅ Sirf video area se limit — image file ka size limit nahi karega
                    val (finalMaxW, finalMaxH) = getMaxStickerSizeInView()

                    val newWidth = (startWidth + dx).coerceAtLeast(minSize)
                        .coerceAtMost(finalMaxW.coerceAtLeast(minSize))
                    val aspectRatio = if (startWidth > 0) startHeight.toFloat() / startWidth else 1f
                    val newHeight = (newWidth * aspectRatio).toInt()
                        .coerceAtMost(finalMaxH.coerceAtLeast(minSize))

                    pendingWidth = newWidth
                    pendingHeight = newHeight


                    val scaleRatio = if (startWidth > 0) newWidth.toFloat() / startWidth else 1f
                    root.scaleX = scaleRatio
                    root.scaleY = scaleRatio
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (root.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)

                    root.scaleX = 1f
                    root.scaleY = 1f
                    val params = root.layoutParams as FrameLayout.LayoutParams
                    params.width = pendingWidth
                    params.height = pendingHeight
                    root.layoutParams = params
                    root.post { keepInsideBounds(root, container) }
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

    //--- Music / Audio menu ---------------------------------------------------

    /** Seconds of countdown before voice recording starts: 0 = off, 3 = 3-second timer. */
    private var recordTimerSeconds = 0

    // ── Volume ─────────────────────────────────────────────────────────────
    private var videoVolumePercent = 100
    private var videoSpeed = 1f
    private var selectedMusicTrack: MusicTrack? = null
    private val playerOverlays = mutableListOf<Pair<MusicOverlay, MediaPlayer>>()

    private fun applyVideoVolume() {
        val v = videoVolumePercent / 100f
        try { videoMediaPlayer?.setVolume(v, v) } catch (_: IllegalStateException) { }
    }

    /** Music/voice bar select hone par neeche ki row ko [Volume][Delete] se badal deta hai. */
    private fun showClipEditBar(track: MusicTrack) {
        if (!binding.featureRecyclerView.isVisible) return
        selectedMusicTrack = track
        if (currentMode == EditorMode.MUSIC) { refreshAudioRowState(); return }
        val list = arrayListOf(
            VideoEditOption(getString(R.string.volume_label), R.drawable.ic_volume),
            VideoEditOption(getString(R.string.speed_label), R.drawable.ic_speed),
            VideoEditOption(getString(R.string.delete_label), R.drawable.ic_delete),
            VideoEditOption(getString(R.string.done_label), R.drawable.ic_check)
        )
        val editAdapter = VideoEditOptionAdapter(list)
        editAdapter.onItemClick = { pos ->
            val t = selectedMusicTrack
            if (t != null) {
                when (pos) {
                    0 -> showVolumeSheet(t.overlay.volume) { p ->
                        t.overlay.volume = p
                        val v = p / 100f
                        playerOverlays.filter { it.first === t.overlay }.forEach {
                            try { it.second.setVolume(v, v) } catch (_: IllegalStateException) { }
                        }
                    }
                    1 -> showSpeedSheet(t.overlay.speed) { s -> applyMusicSpeed(t, s) }
                    2 -> deleteMusicTrack(t)
                    3 -> clearOverlaySelection()
                }
            }
        }
        binding.featureRecyclerView.adapter = editAdapter
    }
    private var isVideoBarSelected = false

    /** Thumbnail strip (video) tap hone par [Volume][Done] row. */
    private fun showVideoEditBar() {
        if (!binding.featureRecyclerView.isVisible) return
        if (isVideoBarSelected) return
        selectedMusicTrack = null
        isVideoBarSelected = true
        binding.thumbnailRecyclerView.foreground = GradientDrawable().apply {
            setStroke(2.dpToPx(), "#FFFFFFFF".toColorInt())
            cornerRadius = 4.dpToPx().toFloat()
        }
        if (currentMode == EditorMode.MUSIC) { refreshAudioRowState(); return }
        val list = arrayListOf(
            VideoEditOption(getString(R.string.volume_label), R.drawable.ic_volume),
            VideoEditOption(getString(R.string.done_label), R.drawable.ic_check)
        )
        val editAdapter = VideoEditOptionAdapter(list)
        editAdapter.onItemClick = { pos ->
            when (pos) {
                0 -> showVolumeSheet(videoVolumePercent) { p ->
                    videoVolumePercent = p
                    applyVideoVolume()
                }
                1 -> clearOverlaySelection()
            }
        }
        binding.featureRecyclerView.adapter = editAdapter
    }
    private fun hideClipEditBar() {
        if (selectedMusicTrack == null && !isVideoBarSelected) return
        selectedMusicTrack = null
        isVideoBarSelected = false
        binding.thumbnailRecyclerView.foreground = null
        if (currentMode == EditorMode.MUSIC) { refreshAudioRowState(); return }
        if (::adapter.isInitialized && binding.featureRecyclerView.adapter !== adapter) {
            binding.featureRecyclerView.adapter = adapter
        }
    }


    private fun selectNewestMusicClip() {
        if (currentMode != EditorMode.MUSIC) {
            currentMode = EditorMode.MUSIC
            showAudioMenu()
        }
        val track = musicTracks.lastOrNull() ?: return
        track.clipBinding.clipRoot.post {
            clearOverlaySelection()
            selectClip(track.clipBinding.clipRoot)
            showClipEditBar(track)
        }
    }

    private fun deleteMusicTrack(track: MusicTrack) {
        if (!isUndoRedoRunning && !track.overlay.isVoice) pushUndo(UndoAction(
            undo = { addMusicToTimeline(track.overlay, keepPlacement = true) },
            redo = { musicTracks.firstOrNull { it.overlay === track.overlay }?.let { deleteMusicTrack(it) } }
        ))
        removeClipAndRowIfEmpty(track.clipBinding.clipRoot)
        musicTracks.removeAll { it === track }
        if (track.overlay.isVoice) deleteVoiceFile(track.overlay)
        hideClipEditBar()
        refreshSaveButtonVisibility()
        saveEditStateToCache()
        if (trackList.isEmpty()) exitTimelineMode() else enterTimelineMode()
        binding.overlayTrackContainer.doOnLayout { syncTimelineTranslation() }
    }


    private fun showSpeedSheet(initial: Float, onChange: (Float) -> Unit) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val pad = 20.dpToPx()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#2A2A2A".toColorInt())
            setPadding(pad, pad, pad, pad * 2)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.speed_label)
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx())
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(title)
        titleRow.addView(done)

        fun speedOf(p: Int) = 0.5f + p * 0.25f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
        }
        val valueText = android.widget.TextView(this).apply {
            text = "${initial}x"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            minWidth = 56.dpToPx()
            gravity = android.view.Gravity.END
        }
        val seek = android.widget.SeekBar(this).apply {
            max = 6   // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0
            styleWhiteSeekBar(this)
            progress = ((initial - 0.5f) / 0.25f).roundToInt().coerceIn(0, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    valueText.text = "${speedOf(p)}x"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                    onChange(speedOf(bar?.progress ?: 2))
                }
            })
        }
        row.addView(seek)
        row.addView(valueText)

        root.addView(titleRow)
        root.addView(row)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.15f)
        dialog.setOnDismissListener { saveEditStateToCache() }
        dialog.show()
    }

    /** Speed badalne par bar ki length bhi badalti hai (same audio, kam/zyada time me). */
    private fun applyMusicSpeed(track: MusicTrack, newSpeed: Float) {
        val o = track.overlay
        val old = o.speed
        if (old == newSpeed) return
        o.speed = newSpeed

        val root = track.clipBinding.clipRoot
        val p = root.layoutParams as FrameLayout.LayoutParams
        val row = root.parent as? FrameLayout
        val rightBound = if (row != null) neighborBoundsFor(row, root).second else maxClipRightEdge()
        val room = minOf(maxClipRightEdge(), rightBound) - p.leftMargin
        val audioMax = if (o.durationMs > 0L)
            timeToPx(((o.durationMs - o.musicOffsetMs) / newSpeed).toLong()).coerceAtLeast(100)
        else Int.MAX_VALUE
        p.width = (p.width * old / newSpeed).toInt()
            .coerceIn(100, maxOf(100, minOf(room, audioMax)))
        root.layoutParams = p

        o.startPx = (p.leftMargin - halfWidth).coerceAtLeast(0)
        o.endPx = (p.leftMargin + p.width - halfWidth).coerceAtLeast(0)
        o.startTimeMs = pxToTime(o.startPx)
        o.endTimeMs = pxToTime(o.endPx)
    }

    /** InShot jaisa slider: chala hua hissa safed, baaki halka grey, gol safed thumb. */
    private fun styleWhiteSeekBar(seek: android.widget.SeekBar) {
        val white = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        seek.progressTintList = white
        seek.thumbTintList = white
        seek.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf("#4DFFFFFF".toColorInt())
        seek.splitTrack = false
    }

    /** Slider panel (0–100). [onChange] live chalta hai, dismiss par draft save hota hai. */
    private fun showVolumeSheet(initial: Int, onChange: (Int) -> Unit) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val pad = 20.dpToPx()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#2A2A2A".toColorInt())
            setPadding(pad, pad, pad, pad * 2)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.volume_label)
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx())
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(title)
        titleRow.addView(done)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
        }
        val valueText = android.widget.TextView(this).apply {
            text = initial.toString()
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            minWidth = 44.dpToPx()
            gravity = android.view.Gravity.END
        }
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            styleWhiteSeekBar(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    valueText.text = p.toString()
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        }
        row.addView(seek)
        row.addView(valueText)

        root.addView(titleRow)
        root.addView(row)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.15f)
        dialog.setOnDismissListener { saveEditStateToCache() }
        dialog.show()
    }

    // ── Voice recording state ──────────────────────────────────────────────
    private var voiceRecorder: VoiceRecorder? = null
    private var isRecordingVoice = false
    private var isCountingDown = false
    private val voiceBusy get() = isRecordingVoice || isCountingDown
    private var recordStartVideoMs = 0L
    private var liveVoiceClip: TimelineTextClipBinding? = null
    private var countdownRunnable: Runnable? = null
    private val recordUiHandler = Handler(Looper.getMainLooper())
    private val voiceFiles = mutableSetOf<String>()

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onRecordButtonClicked()
            else Toast.makeText(this, getString(R.string.record_permission_denied), Toast.LENGTH_LONG).show()
        }

    private val recordTickRunnable = object : Runnable {
        override fun run() {
            if (!isRecordingVoice) return
            val pos = editorPosMs()
            val dur = safeVideoDurationMs()
            liveVoiceClip?.clipRoot?.let { root ->
                val p = root.layoutParams as FrameLayout.LayoutParams
                p.width = timeToPx((pos - recordStartVideoMs).coerceAtLeast(0L)).coerceAtLeast(1)
                root.layoutParams = p
            }
            if (dur > 0 && pos >= dur - 30) { finishVoiceRecording(); return }   // auto-stop at video end
            recordUiHandler.postDelayed(this, 33)
        }
    }

    private fun onRecordButtonClicked() {
        if (isRecordingVoice) { finishVoiceRecording(); return }
        if (isCountingDown) { cancelVoiceRecording(); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.permission_required_title))
                    .setMessage(getString(R.string.record_permission_message))
                    .setPositiveButton(getString(R.string.allow)) { _, _ ->
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (totalWidth <= 0 || safeVideoDurationMs() <= 0L) return
        val startMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0L)
        if (startMs >= safeVideoDurationMs() - 300) {
            Toast.makeText(this, getString(R.string.record_move_playhead), Toast.LENGTH_SHORT).show()
            return
        }
        if (recordTimerSeconds == 3) startCountdown(3) else beginVoiceRecording()
    }

    private fun startCountdown(seconds: Int) {
        isCountingDown = true
        applyTimelineTouchLock()
        binding.recordPanel.apply {
            btnRecordTimer.isEnabled = false
            btnRecordDone.isEnabled = false
            btnRecord.isSelected = true
        }
        var left = seconds
        val r = object : Runnable {
            override fun run() {
                if (!isCountingDown) return
                if (left > 0) {
                    binding.recordPanel.tvRecordTimer.text = left.toString()
                    left--
                    recordUiHandler.postDelayed(this, 1000)
                } else {
                    isCountingDown = false
                    beginVoiceRecording()
                }
            }
        }
        countdownRunnable = r
        r.run()
    }

    private fun beginVoiceRecording() {
        val startMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0L)
        if (startMs >= safeVideoDurationMs() - 300) {
            Toast.makeText(this, getString(R.string.record_move_playhead), Toast.LENGTH_SHORT).show()
            resetRecordUi(); return
        }
        val rec = VoiceRecorder(this)
        val ok = rec.start {
            runOnUiThread {
                if (isRecordingVoice) {
                    cancelVoiceRecording()
                    Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (!ok) {
            Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_SHORT).show()
            resetRecordUi(); return
        }
        voiceRecorder = rec
        isRecordingVoice = true
        recordStartVideoMs = startMs

        stopMusicPreview()                 // recording ke time music nahi (volume bhi reset)
        muteVideoAudio()                   // video ki awaaz SIRF recording me mute
        binding.thumbnail.isGone = true
        setOverlayControlsVisible(false)
        showLiveVoiceClip(startMs)
        binding.recordPanel.apply {
            btnRecord.isSelected = true
            btnRecordTimer.isEnabled = false
            btnRecordDone.isEnabled = false
        }
        applyTimelineTouchLock()
        seekEditorTo(startMs)
        binding.videoView.start()
        recordUiHandler.post(recordTickRunnable)
    }

    private fun finishVoiceRecording() {
        if (!isRecordingVoice) return
        isRecordingVoice = false
        recordUiHandler.removeCallbacks(recordTickRunnable)
        val endPos = editorPosMs()
        if (binding.videoView.isPlaying) binding.videoView.pause()
        restoreVideoAudio()
        binding.playStopButton.setImageDrawable(
            ResourcesCompat.getDrawable(resources, R.drawable.ic_play1, null)
        )
        setOverlayControlsVisible(true)
        removeLiveVoiceClip()

        val rec = voiceRecorder
        voiceRecorder = null
        val file = rec?.stop()
        resetRecordUi()
        if (file == null) {
            Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.fromFile(file)
        val realMs = readAudioDurationMs(uri)
        val start = recordStartVideoMs
        val lengthMs = if (realMs > 0L) realMs else (endPos - start)
        val end = (start + lengthMs).coerceAtMost(safeVideoDurationMs())
        if (end - start < 300L) {
            file.delete()
            Toast.makeText(this, getString(R.string.record_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        addMusicToTimeline(
            MusicOverlay(
                uri = uri,
                title = getString(R.string.voice_title),
                durationMs = if (realMs > 0L) realMs else lengthMs,
                startTimeMs = start,
                endTimeMs = end,
                isVoice = true
            ),
            keepPlacement = true
        )
    }

    /** Sab discard karta hai. Back / onPause / error / panel close se safe. */
    private fun cancelVoiceRecording() {
        val wasRecording = isRecordingVoice
        countdownRunnable?.let { recordUiHandler.removeCallbacks(it) }
        countdownRunnable = null
        recordUiHandler.removeCallbacks(recordTickRunnable)
        isCountingDown = false
        isRecordingVoice = false
        voiceRecorder?.cancel()
        voiceRecorder = null
        removeLiveVoiceClip()
        if (wasRecording) {
            if (binding.videoView.isPlaying) binding.videoView.pause()
            binding.playStopButton.setImageDrawable(
                ResourcesCompat.getDrawable(resources, R.drawable.ic_play1, null)
            )
            setOverlayControlsVisible(true)
            restoreVideoAudio()
        }
        resetRecordUi()
    }

    private fun restoreVideoAudio() {
        isVideoMuted = true      // force, taaki unmute sach me chale
        unmuteVideoAudio()
    }

    private fun resetRecordUi() {
        if (!::binding.isInitialized) return
        binding.recordPanel.apply {
            btnRecord.isSelected = false
            btnRecordTimer.isEnabled = true
            btnRecordDone.isEnabled = true
            tvRecordTimer.text = getString(
                if (recordTimerSeconds == 3) R.string.record_timer_3s else R.string.record_timer_off
            )
        }
        applyTimelineTouchLock()
    }

    private fun showLiveVoiceClip(startMs: Long) {
        removeLiveVoiceClip()
        val row = findOrCreateRowForClip(startMs, safeVideoDurationMs())
        val cb = TimelineTextClipBinding.inflate(layoutInflater)
        cb.clipRoot.layoutParams =
            FrameLayout.LayoutParams(1, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = halfWidth + timeToPx(startMs)
            }
        cb.clipLabel.isVisible = true
        cb.clipLabel.text = "🎤"
        cb.clipRoot.background = GradientDrawable().apply {
            cornerRadius = 4.dpToPx().toFloat()
            setColor("#E53935".toColorInt())          // recording ke time red
        }
        row.addView(cb.root)
        liveVoiceClip = cb
        enterTimelineMode()
    }

    private fun removeLiveVoiceClip() {
        liveVoiceClip?.let { removeClipAndRowIfEmpty(it.clipRoot) }
        liveVoiceClip = null
        if (::binding.isInitialized && trackList.isEmpty()) exitTimelineMode()
    }

    private fun deleteVoiceFile(overlay: MusicOverlay) {
        overlay.uri.path?.let { File(it).delete(); voiceFiles.remove(it) }
    }

    private fun deleteAllVoiceFiles() {
        voiceFiles.forEach { File(it).delete() }
        voiceFiles.clear()
    }

    /** Audio menu (Music / Record / Volume / ✓) shown in place of the feature icons row. */
    private fun showAudioMenu() {
        clearOverlaySelection()
        val list = arrayListOf(
            VideoEditOption(getString(R.string.music), R.drawable.ic_music),
            VideoEditOption(getString(R.string.audio_record), R.drawable.ic_mic_idle),
            VideoEditOption(getString(R.string.volume_label), R.drawable.ic_volume),
            VideoEditOption(getString(R.string.speed_label), R.drawable.ic_speed),
            VideoEditOption(getString(R.string.delete_label), R.drawable.ic_delete),
            VideoEditOption(getString(R.string.done_label), R.drawable.ic_check)
        )
        val audioAdapter = VideoEditOptionAdapter(list)
        audioAdapter.onItemClick = { pos ->
            val t = selectedMusicTrack
            val hasTarget = t != null || isVideoBarSelected
            when (pos) {
                0 -> checkAndRequestAudioPermission()
                1 -> showRecordPanel()
                2 -> if (t != null) {
                    showVolumeSheet(t.overlay.volume) { p ->
                        t.overlay.volume = p
                        val v = p / 100f
                        playerOverlays.filter { it.first === t.overlay }.forEach {
                            try { it.second.setVolume(v, v) } catch (_: IllegalStateException) { }
                        }
                    }
                } else if (hasTarget) {
                    showVolumeSheet(videoVolumePercent) { p ->
                        videoVolumePercent = p
                        applyVideoVolume()
                    }
                }
                3 -> if (t != null) {
                    showSpeedSheet(t.overlay.speed) { s -> applyMusicSpeed(t, s) }
                }
                4 -> if (t != null) deleteMusicTrack(t)
                5 -> hideAudioMenu()
            }
        }
        binding.audioMenu.root.isGone = true
        binding.featureRecyclerView.adapter = audioAdapter
        binding.featureRecyclerView.isVisible = true
        binding.featureRecyclerView.scrollToPosition(0)
        refreshAudioRowState()
    }

    /** Volume/Speed/Delete: bar select hone par active, warna dim. */
    private fun refreshAudioRowState() {
        binding.featureRecyclerView.post {
            val hasTarget = selectedMusicTrack != null || isVideoBarSelected
            val hasMusic = selectedMusicTrack != null
            for (i in 2..4) {
                val enabled = if (i == 4) hasMusic else hasTarget
                binding.featureRecyclerView.findViewHolderForAdapterPosition(i)
                    ?.itemView?.alpha = if (enabled) 1f else 0.35f
            }
        }
    }

    /** Closes the audio menu (and the record panel, if open) and shows the feature icons again. */
    private fun hideAudioMenu() {
        hideRecordPanel()
        binding.audioMenu.root.isGone = true
        currentMode = EditorMode.NONE
        clearOverlaySelection()
        if (::adapter.isInitialized && binding.featureRecyclerView.adapter !== adapter) {
            binding.featureRecyclerView.adapter = adapter
        }
        binding.featureRecyclerView.isVisible = true
        refreshSaveButtonVisibility()
    }

    /** Record panel UI (Phase A: UI + timer toggle only; recording is wired in Phase B). */
    private fun showRecordPanel() {
        binding.recordPanel.apply {
            tvRecordTimer.text = getString(
                if (recordTimerSeconds == 3) R.string.record_timer_3s else R.string.record_timer_off
            )
            btnRecordTimer.setOnClickListener {
                recordTimerSeconds = if (recordTimerSeconds == 3) 0 else 3
                tvRecordTimer.text = getString(
                    if (recordTimerSeconds == 3) R.string.record_timer_3s else R.string.record_timer_off
                )
            }
            btnRecordDone.setOnClickListener { hideRecordPanel() }
            btnRecord.setOnClickListener { onRecordButtonClicked() }
            root.isVisible = true
        }
        binding.featureRecyclerView.isInvisible = true
        binding.playStopButton.isInvisible = true
        applyTimelineTouchLock()
    }

    /** ✓ on the record panel — back to the audio menu. */
    private fun hideRecordPanel() {
        if (voiceBusy) cancelVoiceRecording()
        binding.recordPanel.root.isGone = true
        if (currentMode == EditorMode.MUSIC) binding.featureRecyclerView.isVisible = true
        binding.playStopButton.isVisible = true
        applyTimelineTouchLock()
    }

    private fun showMusicSheet() {
        binding.apply {
            when (currentMode) {
                EditorMode.STICKER -> {
                    editorStickerPanel.stickerEditorPanel.isGone = true
                    currentMode = EditorMode.NONE
                }

                EditorMode.CROP -> {
                    currentMode = EditorMode.STICKER
                    cropRatioHelper.hide()
                    binding.cropView.visibility = View.GONE
                    binding.okTextOverlay.visibility = View.GONE
                }

                EditorMode.TEXT_OVERLAY -> {
                    clearOverlayContainerKeepThumbnail()
                    currentMode = EditorMode.NONE
                    binding.cropView.visibility = View.GONE
                    binding.okTextOverlay.visibility = View.GONE
                    cropRatioHelper.hide()
                }

                EditorMode.MUSIC -> {
                    hideAudioMenu()
                    return
                }

                EditorMode.NONE -> {

                }

            }
            currentMode = EditorMode.MUSIC
            showAudioMenu()
            refreshSaveButtonVisibility()
        }
    }

    /** Real length of an audio Uri in ms, or 0 if it can't be read. */
    private fun readAudioDurationMs(uri: Uri): Long {
        // 1) MediaMetadataRetriever
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val d = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            if (d > 0L) return d
        } catch (e: Exception) {
            Log.w(TAG, "retriever failed: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
        // 2) Fallback: MediaPlayer
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(this, uri)
            mp.prepare()
            val d = mp.duration.toLong()
            mp.release()
            d.coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.w(TAG, "mediaplayer duration failed: ${e.message}")
            0L
        }
    }

    fun checkAndRequestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Already granted — audio menu stays open until a song is picked
                val intent = Intent(this@EditVideoActivity, MusicPickerActivity::class.java)
                musicPickerLauncher.launch(intent)
            }

            shouldShowRequestPermissionRationale(permission) -> {
                // Optional: show explanation dialog
                showPermissionDialog(permission)
            }

            else -> {
                // Request permission
                audioPermissionLauncher.launch(permission)
            }
        }
    }

    private fun showPermissionDialog(permission: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.permission_audio_message))
            .setPositiveButton(getString(R.string.allow)) { _, _ ->
                audioPermissionLauncher.launch(permission)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                openSystemAudioPicker()
            }
            .show()
    }

    private fun openSystemAudioPicker() {
        audioPickerLauncher.launch(arrayOf("audio/*"))
    }

    private fun addMusicToTimeline(
        overlay: MusicOverlay,
        isRestore: Boolean = false,
        keepPlacement: Boolean = isRestore
    ) {
        // ✅ Same source as initializeTimelineLayout() — thumbnailRecyclerView's real width,
        // never a screen-metrics guess, so this can never drift from thumbnails/ruler.
        if (halfWidth == 0 && binding.thumbnailRecyclerView.width > 0) {
            halfWidth = binding.thumbnailRecyclerView.width / 2
        }

        val videoDurationMs = safeVideoDurationMs()

        // ✅ Real audio length
        // The system picker / old drafts pass 0 — read it from the file once.
        val pickerDurationMs = overlay.durationMs
        if (overlay.durationMs <= 0L) {
            overlay.durationMs = readAudioDurationMs(overlay.uri)
        }
        Log.d(
            "MusicDebug",
            "add: isRestore=$isRestore title=${overlay.title} pickerDurationMs=$pickerDurationMs " +
                    "afterReadDurationMs=${overlay.durationMs} videoView.duration=$videoDurationMs " +
                    "thumbOffset=$thumbnailScrollOffset totalWidth=$totalWidth halfWidth=$halfWidth pxPerSecond=$pxPerSecond"
        )

        // ✅ Only compute default placement/duration for a genuinely NEW clip.
        // A restored (draft) clip already carries its own saved startTimeMs/endTimeMs
        // and must NOT be overwritten by the current playhead position.
        if (!keepPlacement) {
            val currentMs = pxToTime(thumbnailScrollOffset).coerceAtLeast(0L)
            val remainingMs = (videoDurationMs - currentMs).coerceAtLeast(1000L)
            // ✅ Song's real length, capped at the time left after the playhead; 3s if unknown
            val audioMs = overlay.durationMs.takeIf { it > 0L } ?: 3000L
            val clipDurationMs = audioMs.coerceAtMost(remainingMs)
            overlay.startTimeMs = currentMs
            overlay.endTimeMs = (currentMs + clipDurationMs).coerceAtMost(videoDurationMs)
            Log.d(
                "MusicDebug",
                "add: currentMs=$currentMs remainingMs=$remainingMs audioMs=$audioMs clipDurationMs=$clipDurationMs " +
                        "-> start=${overlay.startTimeMs} end=${overlay.endTimeMs}"
            )
        }

        // ✅ Never let a clip (new OR restored from a draft) sit before t=0 or after video end.
        val (clampedStart, clampedEnd) = clampClipRange(overlay.startTimeMs, overlay.endTimeMs)
        overlay.startTimeMs = clampedStart
        overlay.endTimeMs = clampedEnd

        val clipDurationPx = timeToPx(overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(100)
        val clampedStartPx = timeToPx(overlay.startTimeMs)
            .coerceAtLeast(0)
            .coerceAtMost((totalWidth - clipDurationPx).coerceAtLeast(0))
        val clampedEndPx = (clampedStartPx + clipDurationPx).coerceAtMost(totalWidth)
        val actualWidth = (clampedEndPx - clampedStartPx).coerceAtLeast(100)

        overlay.startPx = clampedStartPx
        overlay.endPx = clampedEndPx
        overlay.startTimeMs = pxToTime(clampedStartPx)
        overlay.endTimeMs = pxToTime(clampedEndPx)
        Log.d(
            "MusicDebug",
            "add: after clamp start=${overlay.startTimeMs} end=${overlay.endTimeMs} " +
                    "clipDurationPx=$clipDurationPx startPx=$clampedStartPx endPx=$clampedEndPx widthPx=$actualWidth"
        )

        // ── Create timeline track ──────────────────────────────────────────
        val track = findOrCreateRowForClip(overlay.startTimeMs, overlay.endTimeMs)
        val clipBinding = TimelineTextClipBinding.inflate(layoutInflater)

        clipBinding.clipRoot.layoutParams =
            FrameLayout.LayoutParams(actualWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = halfWidth + clampedStartPx
            }
        clipBinding.clipLabel.isVisible = true
        clipBinding.clipLabel.text =
            if (overlay.isVoice) "🎤 ${getString(R.string.voice_title)}" else "🎵 ${overlay.title}"
        clipBinding.clipRoot.background = clipBackground(
            if (overlay.isVoice) R.color.voice_track_color else R.color.music_track_color
        )
        track.addView(clipBinding.root)
        Log.d(
            "TimelineDebug",
            "add type=MUSIC startTimeMs=${overlay.startTimeMs} endTimeMs=${overlay.endTimeMs} row=${trackList.indexOf(track)}"
        )
        val musicTrack = MusicTrack(overlay, clipBinding)
        musicTracks.add(musicTrack)
        if (overlay.isVoice) overlay.uri.path?.let { voiceFiles.add(it) }

        // ── Close button ───────────────────────────────────────────────────
        clipBinding.clipRoot.setOnLongClickListener {
            deleteMusicTrack(musicTrack)
            true
        }

        // ── Thumbnails setup (only once) ───────────────────────────────────
        binding.thumbnailRecyclerView.post { setupThumbnails() }

        // ── Scroll listener (only once) ───────────────────────────────────
        setupTimelineScrollSync()



        setupClipDraggingForMusic(clipBinding, overlay)
        if (!isRestore && !overlay.isVoice) pushUndo(UndoAction(
            undo = { musicTracks.firstOrNull { it.overlay === overlay }?.let { deleteMusicTrack(it) } },
            redo = { addMusicToTimeline(overlay, keepPlacement = true) }
        ))
        enterTimelineMode()
        enterTimelineMode()

        refreshSaveButtonVisibility()
        saveEditStateToCache()

        binding.overlayTrackContainer.doOnLayout {
            syncTimelineTranslation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClipDraggingForMusic(
        clipView: TimelineTextClipBinding,
        overlay: MusicOverlay
    ) {
        val root = clipView.clipRoot

        var isDraggingClip = false
        var isDraggingLeft = false
        var isDraggingRight = false
        var lastRawX = 0f
        var initialMargin = 0
        var initialWidth = 0
        var neighborLeftBound = halfWidth
        var neighborRightBound = maxClipRightEdge()
        var downRawXForTap = 0f
        var hasDraggedClip = false
        var leftMarginAtDown = 0
        var rightEdgeAtDown = 0
        var offsetAtDown = 0L

        fun syncTimes(params: FrameLayout.LayoutParams) {
            overlay.startPx = (params.leftMargin - halfWidth).coerceAtLeast(0)
            overlay.endPx = (params.leftMargin + params.width - halfWidth).coerceAtLeast(0)
            overlay.startTimeMs = pxToTime(overlay.startPx)
            overlay.endTimeMs = pxToTime(overlay.endPx)
        }

        /** Widest the clip may be: its audio's remaining length (after the trim offset),
         *  when known — a clip can never be longer than its audio. */
        fun maxAudioWidthPx(): Int =
            if (overlay.durationMs > 0L) {
                timeToPx(((overlay.durationMs - overlay.musicOffsetMs) / overlay.speed).toLong().coerceAtLeast(0L)).coerceAtLeast(100)
            } else Int.MAX_VALUE

        fun logMoveResize() {
            val row = root.parent as? FrameLayout
            Log.d(
                "TimelineDebug",
                "move/resize type=MUSIC startTimeMs=${overlay.startTimeMs} endTimeMs=${overlay.endTimeMs} row=${row?.let { trackList.indexOf(it) }}"
            )
        }

        root.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val edge = minOf(50f, v.width * 0.25f)
                    if (event.x < edge || event.x > v.width - edge) return@setOnTouchListener false
                    isDraggingClip = true
                    lastRawX = event.rawX
                    downRawXForTap = event.rawX
                    hasDraggedClip = false
                    initialMargin = params.leftMargin
                    val row = v.parent as? FrameLayout
                    if (row != null) {
                        val bounds = neighborBoundsFor(row, v)
                        neighborLeftBound = bounds.first
                        neighborRightBound = bounds.second
                    }
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingClip) return@setOnTouchListener false
                    if (kotlin.math.abs(event.rawX - downRawXForTap) > 12f) hasDraggedClip = true
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialMargin += dx
                    val maxLeft = maxClipRightEdge() - params.width
                    val minLeft = maxOf(halfWidth, neighborLeftBound)
                    val maxLeftClamped = minOf(maxLeft, neighborRightBound - params.width)
                    params.leftMargin = initialMargin.coerceIn(minLeft, maxOf(minLeft, maxLeftClamped))
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingClip = false
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    if (hasDraggedClip) logMoveResize()
                    if (!hasDraggedClip && event.action == MotionEvent.ACTION_UP) {
                        clearOverlaySelection()
                        selectClip(root)   //
                        if (currentMode == EditorMode.MUSIC) {
                            musicTracks.firstOrNull { it.clipBinding.clipRoot === root }
                                ?.let { showClipEditBar(it) }   //
                        }
                    }
                    true
                }

                else -> false
            }
        }

        clipView.leftHandle.setOnTouchListener { _, event ->
            val params = root.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingLeft = true
                    lastRawX = event.rawX
                    initialMargin = params.leftMargin
                    leftMarginAtDown = params.leftMargin
                    rightEdgeAtDown = params.leftMargin + params.width
                    offsetAtDown = overlay.musicOffsetMs
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborLeftBound = neighborBoundsFor(row, root).first
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingLeft) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialMargin += dx
                    // t=0, left neighbour aur offset<0 se aage nahi; min width 100
                    val minLeft = maxOf(
                        halfWidth, neighborLeftBound,
                        leftMarginAtDown - timeToPx((offsetAtDown / overlay.speed).toLong())
                    )
                    val maxLeft = rightEdgeAtDown - 100
                    val newLeft = initialMargin.coerceIn(minLeft, maxOf(minLeft, maxLeft))
                    initialMargin = newLeft                       // drift nahi
                    params.leftMargin = newLeft
                    params.width = rightEdgeAtDown - newLeft      // right edge fixed
                    // start se jitna trim hua, utna audio ka start point aage (peeche drag = kam)
                    overlay.musicOffsetMs =
                        (offsetAtDown + (pxToTime(newLeft - leftMarginAtDown) * overlay.speed).toLong())
                            .coerceAtLeast(0L)
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeft = false
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    logMoveResize()
                    saveEditStateToCache()
                    true
                }

                else -> false
            }
        }
        clipView.rightHandle.setOnTouchListener { _, event ->
            val params = root.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingRight = true
                    lastRawX = event.rawX
                    initialWidth = params.width
                    val row = root.parent as? FrameLayout
                    if (row != null) neighborRightBound = neighborBoundsFor(row, root).second
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingRight) return@setOnTouchListener false
                    val dx = (event.rawX - lastRawX).toInt()
                    lastRawX = event.rawX
                    initialWidth += dx
                    val maxWidth = minOf(maxClipRightEdge(), neighborRightBound) - params.leftMargin
                    // ✅ Also capped at the audio's length
                    params.width = initialWidth.coerceIn(100, maxOf(100, minOf(maxWidth, maxAudioWidthPx())))
                    root.layoutParams = params
                    syncTimes(params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRight = false
                    (root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    logMoveResize()
                    true
                }

                else -> false
            }
        }
    }

    private fun startBurningMusic() {
        if (musicTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_music_added), Toast.LENGTH_SHORT).show()
            return
        }
        if (videoUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.no_video_loaded), Toast.LENGTH_SHORT).show()
            return
        }

        showTextBurnProgress()

        // ── Build burn data from all tracks ───────────────────────────────
        val burnDataList = musicTracks.map { track ->
            MusicBurnData(
                musicUri = track.overlay.uri,
                title = track.overlay.title,
                videoStartSec = track.overlay.startTimeMs / 1000.0,
                videoEndSec = track.overlay.endTimeMs / 1000.0,
                musicOffsetSec = track.overlay.musicOffsetMs / 1000.0,
                volume = track.overlay.volume,
                speed = track.overlay.speed
            )
        }

        showTextBurnProgress()

        VideoTextBurner.burnMusic(
            context = this,
            inputVideoPath = videoUrl,
            musicTracks = burnDataList,
            videoDurationMs = binding.videoView.duration.toLong(),
            onProgress = { percent ->
                runOnUiThread {
                    textBurnProgressDialog?.progress = percent
                    textBurnProgressDialog?.setMessage(getString(R.string.adding_music_progress, percent))
                }
            },
            onSuccess = { outputPath ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Toast.makeText(this, getString(R.string.music_saved, outputPath), Toast.LENGTH_LONG).show()
                    videoUrl = outputPath
                    musicTracks.clear()
                    binding.videoView.setVideoPath(outputPath)
                    binding.videoView.start()
                    finish()
                }
            },
            onError = { error ->
                runOnUiThread {
                    dismissTextBurnProgress()
                    Log.e("Export", "music failed: $error"); Toast.makeText(this, getString(R.string.music_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    currentMode = EditorMode.MUSIC
                }
            }
        )
    }


    private fun exportFinalToOutput(onDone: () -> Unit) {

        if (videoUrl.startsWith("content://")) {
            onDone()
            return
        }

        val cacheFile = File(videoUrl)
        if (!cacheFile.exists()) {
            onDone()
            return
        }

        val outputDirUri = getSavedDirectoryUri()
        if (outputDirUri == null) {
            Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
            onDone()
            return
        }

        val outputFileName = "Edited_${System.currentTimeMillis()}.mp4"
        val documentFile = androidx.documentfile.provider.DocumentFile
            .fromTreeUri(this, outputDirUri)
            ?.createFile("video/mp4", outputFileName)

        if (documentFile == null) {
            Log.e(TAG, "exportFinalToOutput: failed to create output file")
            onDone()
            return
        }

        // ✅ The actual byte copy (can be a large MP4) runs off the main thread so
        // it doesn't block the UI. onDone() hops back to main itself (every caller
        // wraps its continuation in runOnUiThread), so calling it from here is safe.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                    cacheFile.inputStream().use { input -> input.copyTo(out) }
                }
                videoUrl = documentFile.uri.toString()
                // ✅ Cache file ab zaroorat nahi
                cacheFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "exportFinalToOutput failed: ${e.message}")
            }
            onDone()
        }
    }

    /**
     * Starts all music tracks in sync with video playback.
     * Called every time play button is pressed.
     */
    private fun startMusicPreview() {
        stopMusicPreview()

        if (musicTracks.isEmpty()) return

        val videoPositionMs = editorPosMs()   // clip times are in editor (trimmed) time

        musicTracks.forEach { musicTrack ->
            val overlay = musicTrack.overlay

            // ── Skip tracks that have already ended ───────────────────────
            if (videoPositionMs >= overlay.endTimeMs) return@forEach

            // ── Calculate when to start this music relative to NOW ────────
            val msUntilMusicStarts = (overlay.startTimeMs - videoPositionMs).coerceAtLeast(0)

            // ── Calculate where in the music file to seek to ──────────────
            // If video is already past music start, seek into the music file
            val musicSeekMs = if (videoPositionMs > overlay.startTimeMs) {
                overlay.musicOffsetMs + ((videoPositionMs - overlay.startTimeMs) * overlay.speed).toLong()
            } else {
                overlay.musicOffsetMs
            }


            val musicPlayDurationMs =
                overlay.endTimeMs - maxOf(videoPositionMs, overlay.startTimeMs)

            // ── Prepare MediaPlayer ───────────────────────────────────────
            val player = MediaPlayer()
            try {
                player.setDataSource(this, overlay.uri)
                player.prepare()
                player.seekTo(musicSeekMs.toInt())
                player.isLooping = false
            } catch (e: Exception) {
                Log.e("MusicPreview", "Failed to prepare music: ${e.message}")
                player.release()
                return@forEach
            }

            musicPlayers.add(player)
            val vol = overlay.volume / 100f
            player.setVolume(vol, vol)
            playerOverlays.add(overlay to player)


            val handler = Handler(Looper.getMainLooper())
            musicScheduleHandlers.add(handler)

            val startRunnable = Runnable {
                if (!binding.videoView.isPlaying) return@Runnable
                player.start()
                if (overlay.speed != 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        player.playbackParams = android.media.PlaybackParams().setSpeed(overlay.speed)
                    } catch (e: Exception) {
                        Log.w("MusicPreview", "speed not applied: ${e.message}")
                    }
                }
                Log.d("MusicPreview", "Started music: ${overlay.title} at seek=${musicSeekMs}ms")
            }
            musicRunnables.add(startRunnable)
            handler.postDelayed(startRunnable, msUntilMusicStarts)

            // ── Schedule: stop music when its clip ends ───────────────────
            val stopHandler = Handler(Looper.getMainLooper())
            musicScheduleHandlers.add(stopHandler)

            val stopRunnable = Runnable {
                player.pause()
            }
            musicRunnables.add(stopRunnable)
            stopHandler.postDelayed(
                stopRunnable,
                msUntilMusicStarts + musicPlayDurationMs
            )
        }
    }

    /**
     * Stops all music players and clears all scheduled handlers.
     */
    private fun stopMusicPreview() {
        // Cancel all scheduled runnables
        musicScheduleHandlers.forEachIndexed { index, handler ->
            if (index < musicRunnables.size) {
                handler.removeCallbacks(musicRunnables[index])
            }
        }
        musicScheduleHandlers.clear()
        musicRunnables.clear()

        // Release all players
        musicPlayers.forEach { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (e: Exception) {
                Log.e("MusicPreview", "Error releasing player: ${e.message}")
            }
        }
        musicPlayers.clear()
        playerOverlays.clear()

        isVideoMuted = true  // force unmute to actually run

        unmuteVideoAudio()
    }

    private fun muteVideoAudio() {
        if (isVideoMuted) return
        try {
            videoMediaPlayer?.setVolume(0f, 0f)
            isVideoMuted = true
        } catch (e: IllegalStateException) {
            Log.w("MusicPreview", "Cannot mute — MediaPlayer in invalid state")
        }
    }

    private fun unmuteVideoAudio() {
        if (!isVideoMuted) return
        try {
            val vv = videoVolumePercent / 100f
            videoMediaPlayer?.setVolume(vv, vv)
            isVideoMuted = false
        } catch (e: IllegalStateException) {
            Log.w("MusicPreview", "Cannot unmute — MediaPlayer in invalid state")
        }
    }


    // ── Menu ───────────────────────────────────────────────────────────────

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (hasUnsavedChanges()) {
                showSaveOrDiscardDialog()
            } else {
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.clear()
        menuInflater.inflate(R.menu.crop_menu, menu)
        cropMenu = menu.findItem(R.id.cropSave)

        cropMenu?.isVisible = true
        cropMenu?.title = getString(R.string.save)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.cropSave) {
            when (currentMode) {
                EditorMode.CROP -> {
                    val normRect = binding.cropView.getNormalisedCropRect()
                    cropRatioHelper.hide()
                    binding.cropView.visibility = View.GONE
                    pendingCropApply = true
                    pendingCropRect = normRect
                    currentMode = EditorMode.NONE

                    binding.videoView.post {
                        applyCropPreview(normRect)
                    }
                    saveEditStateToCache()
                }

                EditorMode.TEXT_OVERLAY -> {
                    binding.editorTextPanel.textEditorPanel.isGone = true
                    binding.featureRecyclerView.isVisible = true
                    currentMode = EditorMode.NONE
                }

                EditorMode.STICKER -> {
                    binding.editorStickerPanel.stickerEditorPanel.isGone = true
                    binding.featureRecyclerView.isVisible = true
                    currentMode = EditorMode.NONE
                }

                EditorMode.MUSIC -> {
                    hideAudioMenu()
                }

                EditorMode.NONE -> {
                    saveAllPendingEdits()
                    return false
                }
            }

            binding.okTextOverlay.visibility = View.GONE
            refreshSaveButtonVisibility()
        }
        return false
    }

    // ── Replace updateCropMenu helper ─────────────────────────────────────
    private fun updateCropMenu(visible: Boolean, title: String = "") {
        // ✅ Update state fields first
        shouldShowCropMenu = visible
        cropMenuTitle = title
        // ✅ Try direct update first — works if menu already exists
        cropMenu?.isVisible = visible
        cropMenu?.title = title
        // ✅ Force full menu recreation — onCreateMenu will use state fields
        invalidateOptionsMenu()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    // ── Lifecycle ──────────────────────────────────────────────────────────
    private fun getSavedDirectoryUri(): Uri? {
        val uriString = preference.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }


    private fun sourceBitrateBps(): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            val src = videoUrl
            if (src.startsWith("content://") || src.startsWith("file://")) {
                r.setDataSource(this, src.toUri())
            } else {
                r.setDataSource(src)
            }
            val br = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toLongOrNull() ?: 0L
            (br * 1.1).toLong().coerceIn(4_000_000L, 40_000_000L)
        } catch (e: Exception) {
            Log.w(TAG, "bitrate read failed: ${e.message}")
            8_000_000L
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    // ── Non-destructive trim ───────────────────────────────────────────────

    /** Everything needed to put the timeline back exactly as it was (trim undo/redo). */
    private class TrimSnapshot(
        val start: Long?,
        val end: Long?,
        val texts: List<Triple<ItemOverlayBinding, TextOverlayData, LongArray>>,
        val stickers: List<Triple<ItemOverlayBinding, StickerOverlay, LongArray>>,
        /** overlay → [start, end, musicOffsetMs] */
        val music: List<Pair<MusicOverlay, LongArray>>
    )

    private fun captureTrimSnapshot() = TrimSnapshot(
        start = pendingTrimStartMs,
        end = pendingTrimEndMs,
        texts = textTracks.map {
            Triple(it.overlayBinding, it.data, longArrayOf(it.data.startTimeMs, it.data.endTimeMs))
        },
        stickers = stickerTracks.map {
            Triple(it.overlayBinding, it.overlay, longArrayOf(it.overlay.startTimeMs, it.overlay.endTimeMs))
        },
        music = musicTracks.map {
            it.overlay to longArrayOf(it.overlay.startTimeMs, it.overlay.endTimeMs, it.overlay.musicOffsetMs)
        }
    )

    /** Undo/redo of a trim: restore range + every clip's times (incl. clips the trim dropped). */
    private fun restoreTrimSnapshot(snap: TrimSnapshot) {
        pendingTrimStartMs = snap.start
        pendingTrimEndMs = snap.end
        snap.texts.forEach { (_, data, t) -> data.startTimeMs = t[0]; data.endTimeMs = t[1] }
        snap.stickers.forEach { (_, overlay, t) -> overlay.startTimeMs = t[0]; overlay.endTimeMs = t[1] }
        snap.music.forEach { (overlay, t) ->
            overlay.startTimeMs = t[0]; overlay.endTimeMs = t[1]; overlay.musicOffsetMs = t[2]
        }
        rebuildTimelineForTrim(
            snap.texts.map { it.first to it.second },
            snap.stickers.map { it.first to it.second },
            snap.music.map { it.first }
        )
        saveEditStateToCache()
    }


    private fun applyTrimRange(newStartMs: Long, newEndMs: Long) {
        if (newEndMs <= newStartMs) return
        val before = captureTrimSnapshot()

        val oldStart = trimStart()
        val newDur = newEndMs - newStartMs
        /** old editor time → new editor time (via original time); null = fully outside */
        fun shift(start: Long, end: Long): Pair<Long, Long>? {
            val s = start + oldStart - newStartMs
            val e = end + oldStart - newStartMs
            if (e <= 0L || s >= newDur) return null
            return s.coerceAtLeast(0L) to e.coerceAtMost(newDur)
        }

        val keptTexts = textTracks.mapNotNull { t ->
            shift(t.data.startTimeMs, t.data.endTimeMs)?.let { (s, e) ->
                t.data.startTimeMs = s; t.data.endTimeMs = e
                t.overlayBinding to t.data
            }
        }
        val keptStickers = stickerTracks.mapNotNull { t ->
            shift(t.overlay.startTimeMs, t.overlay.endTimeMs)?.let { (s, e) ->
                t.overlay.startTimeMs = s; t.overlay.endTimeMs = e
                t.overlayBinding to t.overlay
            }
        }

        val keptMusic = musicTracks.mapNotNull { t ->
            val o = t.overlay
            val rawStart = o.startTimeMs + oldStart - newStartMs
            shift(o.startTimeMs, o.endTimeMs)?.let { (s, e) ->
                if (rawStart < 0L) o.musicOffsetMs += ((-rawStart) * o.speed).toLong()
                o.startTimeMs = s; o.endTimeMs = e
                o
            }
        }

        val full = fullVideoDurationMs()
        if (newStartMs <= 0L && full > 0L && newEndMs >= full) {
            pendingTrimStartMs = null
            pendingTrimEndMs = null
        } else {
            pendingTrimStartMs = newStartMs
            pendingTrimEndMs = newEndMs
        }

        rebuildTimelineForTrim(keptTexts, keptStickers, keptMusic)

        // ✅ A trim clears the earlier history; the trim itself (and later actions) is undoable.
        undoStack.clear()
        redoStack.clear()
        val after = captureTrimSnapshot()
        pushUndo(UndoAction(
            undo = { restoreTrimSnapshot(before) },
            redo = { restoreTrimSnapshot(after) }
        ))
        saveEditStateToCache()
    }


    private fun rebuildTimelineForTrim(
        texts: List<Pair<ItemOverlayBinding, TextOverlayData>>,
        stickers: List<Pair<ItemOverlayBinding, StickerOverlay>>,
        music: List<MusicOverlay>
    ) {
        stopMusicPreview()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            binding.playStopButton.setImageDrawable(
                ResourcesCompat.getDrawable(resources, R.drawable.ic_play1, null)
            )
        }
        clearOverlaySelection()

        // Overlays of clips that are no longer kept → off the preview
        val keepBindings = texts.map { it.first } + stickers.map { it.first }
        (textTracks.map { it.overlayBinding } + stickerTracks.map { it.overlayBinding })
            .filter { b -> keepBindings.none { it === b } }
            .forEach { b ->
                binding.overlayContainer.removeView(b.root)
                overlayBorderCache.remove(b)
            }

        // Rows / clip views (track data objects are re-added below)
        trackList.forEach { binding.overlayTrackContainer.removeView(it) }
        trackList.clear()
        textTracks.clear()
        stickerTracks.clear()
        musicTracks.clear()
        updateRowsAreaHeight()
        binding.timelineScroll.translationY = 0f

        // Scroll offset, thumbnails adapter and ruler — NOT the scroll_linked tag
        thumbnailScrollOffset = 0
        binding.thumbnailRecyclerView.adapter = null
        binding.overlayTrackContainer.translationX = 0f
        binding.rulerContainer.translationX = 0f
        binding.rulerContainer.removeAllViews()

        // Geometry for the new (trimmed) length — same as setupTimelineCore() minus listeners
        recalculatePxPerSecond()
        if (binding.thumbnailRecyclerView.width > 0) {
            halfWidth = (binding.thumbnailRecyclerView.width / 2).coerceAtLeast(1)
        }
        totalWidth = timeToPx(safeVideoDurationMs())
        binding.overlayTrackContainer.layoutParams.width = totalWidth + halfWidth * 2
        binding.overlayTrackContainer.requestLayout()
        setupThumbnails()
        setupTimeRuler()
        binding.tvTotalDuration.text = formatShortTime(safeVideoDurationMs().toInt())
        binding.tvCurrentTime.text = formatShortTime(0)

        texts.forEach { (b, data) -> addTextToTimeline(b, data, resizeAfter = false) }
        stickers.forEach { (b, overlay) -> addStickerToTimeline(overlay, b, resizeAfter = false) }
        music.forEach { overlay -> addMusicToTimeline(overlay, isRestore = true) }

        if (trackList.isEmpty()) exitTimelineMode() else enterTimelineMode()
        binding.overlayTrackContainer.post { syncTimelineTranslation() }

        // Preview: first frame of the trimmed video, playhead at 0
        binding.thumbnail.isVisible = true
        refreshPreviewThumbnail()
        seekEditorTo(0L)
        updateAllTextVisibility(0L)
        updateAllStickerVisibility(0L)
    }

    /**
     * Single FFmpeg invocation that folds trim + crop + text + stickers/labels +
     * audio (volume/music mix) into ONE filter graph — replacing the old
     * burnTrimStep → burnCropStep → burnTextStep → burnStickerStep → burnMusicStep
     * chain, which re-encoded the whole video once per edit type. Metadata
     * (dimensions/bitrate/has-audio) is read once up front and reused throughout.
     */
    private fun exportAllEdits(onDone: () -> Unit) {
        if (videoUrl.isBlank()) { onDone(); return }

        val hasTrim = pendingTrimStartMs != null && pendingTrimEndMs != null
        val trimStartSec = (pendingTrimStartMs ?: 0L) / 1000.0
        val trimEndSec = (pendingTrimEndMs ?: safeVideoDurationMs()) / 1000.0

        val cropActive = pendingCropApply && pendingCropRect != null
        val normCropRect = pendingCropRect

        val (labeledTexts, plainTexts) = textTracks.partition {
            resolveLabelShape(it.data.labelColor, it.data.labelShape) != LabelShape.NONE
        }

        val needsAnyEdit = hasTrim || cropActive || textTracks.isNotEmpty() ||
                stickerTracks.isNotEmpty() || musicTracks.isNotEmpty() || videoVolumePercent != 100
        if (!needsAnyEdit) { onDone(); return }

        onExportPassStarted()

        // ── Metadata — read ONCE for the whole export, reused everywhere below ──
        val metadataStartMs = android.os.SystemClock.elapsedRealtime()
        val sourceWidth = videoNativeWidth
        val sourceHeight = videoNativeHeight
        val bitrateBps = sourceBitrateBps()
        val videoHasAudio = VideoTextBurner.hasAudioStream(this, videoUrl)
        Log.d(
            TAG, "METADATA_READ elapsedMs=${android.os.SystemClock.elapsedRealtime() - metadataStartMs} " +
                    "w=$sourceWidth h=$sourceHeight bitrate=$bitrateBps hasAudio=$videoHasAudio"
        )

        // ── Crop: pure filter fragment + resulting frame size ───────────────────
        var cropFragment: String? = null
        var postCropWidth = sourceWidth
        var postCropHeight = sourceHeight
        if (cropActive && normCropRect != null && sourceWidth > 0 && sourceHeight > 0) {
            VideoCropper.buildCropFilterFragment(sourceWidth, sourceHeight, normCropRect)?.let { spec ->
                cropFragment = spec.filterFragment
                postCropWidth = spec.outputWidth
                postCropHeight = spec.outputHeight
            }
        }

        // ── Text + sticker/label burn data, computed against the POST-CROP frame.
        // Matches the old pipeline: by the time burn data was built, the crop pass
        // had already reloaded the cropped video and refreshed videoNativeWidth/Height,
        // so overlay positions were always computed in cropped-frame coordinates.
        val savedNativeWidth = videoNativeWidth
        val savedNativeHeight = videoNativeHeight
        videoNativeWidth = postCropWidth
        videoNativeHeight = postCropHeight

        val textBurnList = plainTexts.mapNotNull { track ->
            val params = track.overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams
                ?: return@mapNotNull null
            val (videoX, videoY) = overlayViewToVideoPixels(
                params.leftMargin.toFloat(), params.topMargin.toFloat()
            )
            val videoFontSize = overlayFontSizeToVideoPixels(track.overlayBinding.overlayText.textSize)
            val fontPath = VideoTextBurner.extractFontFromAssets(this, track.data.fontAssetFileName)
                ?: return@mapNotNull null
            VideoTextBurner.TextBurnData(
                text = track.data.text, xPx = videoX, yPx = videoY,
                fontSizePx = videoFontSize, fontFilePath = fontPath,
                fontColor = track.data.color,
                startSec = track.data.startTimeMs / 1000.0,
                endSec = track.data.endTimeMs / 1000.0
            )
        }

        val stickerBurnList = mutableListOf<StickerBurnData>()
        for (track in stickerTracks) {
            val overlay = track.overlay
            val overlayBinding = track.overlayBinding
            val params = overlayBinding.overlayRoot.layoutParams as? FrameLayout.LayoutParams ?: continue
            val (videoX, videoY) = overlayViewToVideoPixels(
                params.leftMargin.toFloat(), params.topMargin.toFloat()
            )
            val imageView = overlayBinding.overlayImageView
            val videoW = overlaySizeToVideoScale(
                imageView.width.toFloat(), binding.overlayContainer.width.toFloat(), videoNativeWidth
            )
            val videoH = overlaySizeToVideoScale(
                imageView.height.toFloat(), binding.overlayContainer.height.toFloat(), videoNativeHeight
            )
            val imagePath = drawableToFile(this, overlay.drawable)
            stickerBurnList.add(
                StickerBurnData(
                    imagePath = imagePath, xPx = videoX, yPx = videoY,
                    width = videoW, height = videoH,
                    startSec = overlay.startTimeMs / 1000.0, endSec = overlay.endTimeMs / 1000.0
                )
            )
        }
        // Labeled text renders to its own PNG at the text's position/time — the same
        // primitive as an image sticker, so it joins the SAME overlay chain instead
        // of its own separate encode pass.
        for (track in labeledTexts) {
            labeledTextBurnData(track)?.let { stickerBurnList.add(it) }
        }

        videoNativeWidth = savedNativeWidth
        videoNativeHeight = savedNativeHeight

        val ancillaryTempFiles = mutableListOf<File>()
        stickerBurnList.forEach { ancillaryTempFiles.add(File(it.imagePath)) }

        // ── Video filter graph: trim → crop → drawtext → overlay(s) ─────────────
        val preOverlayStages = mutableListOf<String>()
        if (hasTrim) preOverlayStages.add("trim=start=$trimStartSec:end=$trimEndSec,setpts=PTS-STARTPTS")
        cropFragment?.let { preOverlayStages.add(it) }
        if (textBurnList.isNotEmpty()) preOverlayStages.add(VideoTextBurner.buildDrawTextFilterFragment(textBurnList))

        val videoFilterParts = mutableListOf<String>()
        var overlayInputLabel = "[0:v]"
        if (preOverlayStages.isNotEmpty()) {
            videoFilterParts.add("[0:v]${preOverlayStages.joinToString(",")}[base]")
            overlayInputLabel = "[base]"
        }
        var videoOutLabel = overlayInputLabel
        if (stickerBurnList.isNotEmpty()) {
            val (fragment, outLabel) = VideoTextBurner.buildStickerOverlayFilterFragment(
                inputLabel = overlayInputLabel, firstImageInputIndex = 1, stickers = stickerBurnList
            )
            videoFilterParts.add(fragment)
            videoOutLabel = outLabel
        }
        val videoIsFiltered = videoFilterParts.isNotEmpty()

        // ── Audio filter graph: trim → volume / music mix ───────────────────────
        val audioFilterParts = mutableListOf<String>()
        var audioOutLabel: String? = null
        val musicFiles = mutableListOf<File>()
        val needsAudioFilter = hasTrim || musicTracks.isNotEmpty() || videoVolumePercent != 100

        if (needsAudioFilter) {
            var baseAudioLabel = "[0:a]"
            if (hasTrim && videoHasAudio) {
                audioFilterParts.add("[0:a]atrim=start=$trimStartSec:end=$trimEndSec,asetpts=PTS-STARTPTS[atrim]")
                baseAudioLabel = "[atrim]"
            }
            if (musicTracks.isEmpty()) {
                if (videoHasAudio) {
                    val vol = videoVolumePercent.coerceIn(0, 100) / 100.0
                    audioFilterParts.add("${baseAudioLabel}volume=$vol[aout]")
                    audioOutLabel = "[aout]"
                }
            } else {
                musicTracks.forEachIndexed { index, track ->
                    val file = File(cacheDir, "music_${index}_${System.currentTimeMillis()}.mp3")
                    try {
                        contentResolver.openInputStream(track.overlay.uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        musicFiles.add(file)
                    } catch (e: Exception) {
                        Log.w(TAG, "exportAllEdits: music copy failed for ${track.overlay.title}: ${e.message}")
                    }
                }
                if (musicFiles.size == musicTracks.size) {
                    val burnDataList = musicTracks.map { track ->
                        MusicBurnData(
                            musicUri = track.overlay.uri,
                            title = track.overlay.title,
                            videoStartSec = track.overlay.startTimeMs / 1000.0,
                            videoEndSec = track.overlay.endTimeMs / 1000.0,
                            musicOffsetSec = track.overlay.musicOffsetMs / 1000.0,
                            volume = track.overlay.volume,
                            speed = track.overlay.speed
                        )
                    }
                    val (fragment, outLabel) = VideoTextBurner.buildMusicMixFilterFragment(
                        baseVideoAudioLabel = baseAudioLabel,
                        videoHasAudio = videoHasAudio,
                        videoVolumePercent = videoVolumePercent,
                        musicTracks = burnDataList,
                        firstMusicInputIndex = 1 + stickerBurnList.size
                    )
                    audioFilterParts.add(fragment)
                    audioOutLabel = outLabel
                } else {
                    dismissTextBurnProgress()
                    Toast.makeText(this, getString(R.string.music_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    musicFiles.forEach { it.delete() }
                    ancillaryTempFiles.forEach { it.delete() }
                    return
                }
            }
        }
        ancillaryTempFiles.addAll(musicFiles)

        val filterComplexFull = (videoFilterParts + audioFilterParts).joinToString(";")

        // ── Build the ONE ffmpeg command ─────────────────────────────────────────
        val outputDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "export")
            .also { it.mkdirs() }
        val outputFile = File(outputDir, "Export_${System.currentTimeMillis()}.mp4")

        val inputSaf = VideoTextBurner.resolveFfmpegInputPath(this, videoUrl)
        val args = mutableListOf<String>()
        args += listOf("-i", inputSaf)
        stickerBurnList.forEach { args += listOf("-i", it.imagePath) }
        musicFiles.forEach { args += listOf("-i", it.absolutePath) }

        if (filterComplexFull.isNotBlank()) {
            args += listOf("-filter_complex", filterComplexFull)
        }

        if (videoIsFiltered) {
            args += listOf("-map", videoOutLabel, "-c:v", "libopenh264", "-b:v", bitrateBps.toString())
        } else {
            args += listOf("-map", "0:v", "-c:v", "copy")
        }

        if (audioOutLabel != null) {
            args += listOf("-map", audioOutLabel, "-c:a", "aac", "-b:a", "192k")
        } else if (videoHasAudio) {
            args += listOf("-map", "0:a?", "-c:a", "copy")
        }

        args += listOf("-movflags", "+faststart", "-y", outputFile.absolutePath)

        Log.d(TAG, "exportAllEdits FFmpeg args: ${args.joinToString(" ")}")
        val ffmpegStartMs = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "FFMPEG_START ts=$ffmpegStartMs")

        // ✅ Exactly ONE FFmpegKit session for the whole combined export, regardless
        // of how many edit types (trim/crop/text/stickers/music/volume) are active.
        com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { session ->
                Log.d(TAG, "FFMPEG_END elapsedMs=${android.os.SystemClock.elapsedRealtime() - ffmpegStartMs}")
                ancillaryTempFiles.forEach { it.delete() }
                runOnUiThread {
                    if (!isExportActive) return@runOnUiThread   // export was stopped
                    val returnCode = session.returnCode
                    if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
                        videoUrl = outputFile.absolutePath
                        pendingTrimStartMs = null
                        pendingTrimEndMs = null
                        pendingCropApply = false
                        pendingCropRect = null
                        textTracks.clear()
                        stickerTracks.clear()
                        musicTracks.clear()
                        onDone()
                    } else {
                        Log.e(TAG, "exportAllEdits failed: rc=$returnCode logs=${session.allLogsAsString}")
                        dismissTextBurnProgress()
                        Toast.makeText(this, getString(R.string.text_failed, getString(R.string.error_try_again)), Toast.LENGTH_LONG).show()
                    }
                }
            },
            { log -> Log.d(TAG, log.message) },
            { stats ->
                val pct = ((stats.time.toFloat() / exportDurationMs.coerceAtLeast(1L)) * 100)
                    .toInt().coerceIn(0, 100)
                runOnUiThread { onExportPassProgress(pct) }
            }
        )
    }

    // ── Full-screen "Converting Video" export screen ───────────────────────
    /** True while the final export (saveAllPendingEdits → exportAllEdits) is running.
     *  Every callback checks it, so stopping the export turns late callbacks into no-ops. */
    private var isExportActive = false
    /** Editor (trimmed) length captured when the export starts — progress base for the single pass. */
    private var exportDurationMs = 0L
    private var exportTotalPasses = 1
    private var exportPassIndex = 0
    private var exportPercent = 0
    /** elapsedRealtime() when the current export started — for EXPORT_* timing logs. */
    private var exportStartTimeMs = 0L

    // Editor state before the export — restored when the user stops it.
    private var exportSnapVideoUrl = ""
    private var exportSnapTrimStart: Long? = null
    private var exportSnapTrimEnd: Long? = null
    private var exportSnapCropApply = false
    private var exportSnapCropRect: RectF? = null
    private var exportSnapText: List<TextTrack> = emptyList()
    private var exportSnapSticker: List<StickerTrack> = emptyList()
    private var exportSnapMusic: List<MusicTrack> = emptyList()
    /** Local intermediate outputs produced by earlier passes (deleted on Stop). */
    private val exportIntermediates = mutableSetOf<String>()

    private fun showExportProgressScreen(totalPasses: Int) {
        isExportActive = true
        exportTotalPasses = totalPasses.coerceAtLeast(1)
        exportPassIndex = 0
        exportPercent = 0
        exportIntermediates.clear()
        binding.exportProgress.btnExportClose.setOnClickListener { confirmStopExport() }
        binding.exportProgress.tvExportProgress.text =
            getString(R.string.export_converting_progress, 0)
        binding.exportProgress.root.isVisible = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideExportProgressScreen() {
        isExportActive = false
        binding.exportProgress.root.isGone = true
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Called when a burn pass starts: advances to its equal share of 0–100. */
    private fun onExportPassStarted() {
        if (!isExportActive) return
        rememberExportIntermediate()
        exportPassIndex = (exportPassIndex + 1).coerceAtMost(exportTotalPasses)
        setExportPercent((exportPassIndex - 1) * 100 / exportTotalPasses)
    }

    /** Maps the running pass's own 0–100 into the overall 0–100. */
    private fun onExportPassProgress(stepPercent: Int) {
        if (!isExportActive || exportPassIndex == 0) return
        val done = (exportPassIndex - 1) * 100 + stepPercent.coerceIn(0, 100)
        setExportPercent(done / exportTotalPasses)
    }

    /** Never goes backwards. */
    private fun setExportPercent(percent: Int) {
        if (!isExportActive) return
        val p = percent.coerceIn(0, 100)
        if (p < exportPercent) return
        exportPercent = p
        binding.exportProgress.tvExportProgress.text =
            getString(R.string.export_converting_progress, p)
    }

    private fun rememberExportIntermediate() {
        if (videoUrl != exportSnapVideoUrl && !videoUrl.startsWith("content://")) {
            exportIntermediates.add(videoUrl)
        }
    }

    /** ✕ / Back while exporting → "Stop exporting?" Yes/No. */
    private fun confirmStopExport() {
        if (!isExportActive) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_stop_title))
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                dialog.dismiss()
                stopExport()
            }
            .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Cancels the running FFmpeg, deletes intermediate outputs and restores the editor. */
    private fun stopExport() {
        if (!isExportActive) return
        rememberExportIntermediate()
        val cropWasBurned = exportSnapCropApply && !pendingCropApply
        hideExportProgressScreen()          // isExportActive = false → late callbacks do nothing
        VideoTextBurner.cancel()
        com.arthenica.ffmpegkit.FFmpegKit.cancel()

        videoUrl = exportSnapVideoUrl
        pendingTrimStartMs = exportSnapTrimStart
        pendingTrimEndMs = exportSnapTrimEnd
        pendingCropApply = exportSnapCropApply
        pendingCropRect = exportSnapCropRect
        textTracks.clear(); textTracks.addAll(exportSnapText)
        stickerTracks.clear(); stickerTracks.addAll(exportSnapSticker)
        musicTracks.clear(); musicTracks.addAll(exportSnapMusic)
        binding.overlayContainer.visibility = View.VISIBLE


        if (cropWasBurned) {
            binding.videoView.setOnPreparedListener { mp ->
                videoMediaPlayer = mp
                isVideoMuted = false
                applyVideoVolume()
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                videoNativeWidth = mp.videoWidth
                videoNativeHeight = mp.videoHeight
                recalculateVideoViewSize()
                binding.videoView.post { applyCropPreview(pendingCropRect) }
            }
            binding.videoView.setVideoPath(videoUrl)
        }

        exportIntermediates.filter { it != videoUrl }.forEach { File(it).delete() }
        exportIntermediates.clear()
    }

    private fun saveAllPendingEdits() {
        // ✅ Reentrancy guard — a double-tap on Save before the export overlay
        // appears must not start a second FFmpeg session.
        if (isExportActive) return

        val hasTrim = pendingTrimStartMs != null && pendingTrimEndMs != null
        val videoChanged = videoUrl != originalVideoUrl
        if (!hasTrim && !pendingCropApply && !videoChanged && videoVolumePercent == 100 && textTracks.isEmpty() && stickerTracks.isEmpty() && musicTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
            return
        }
        // ✅ Snapshot so "Stop exporting" can put the editor back exactly as it was
        exportSnapVideoUrl = videoUrl
        exportSnapTrimStart = pendingTrimStartMs
        exportSnapTrimEnd = pendingTrimEndMs
        exportSnapCropApply = pendingCropApply
        exportSnapCropRect = pendingCropRect
        exportSnapText = textTracks.toList()
        exportSnapSticker = stickerTracks.toList()
        exportSnapMusic = musicTracks.toList()

        exportDurationMs = safeVideoDurationMs()

        binding.okTextOverlay.visibility = View.GONE
        exportStartTimeMs = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "EXPORT_START ts=$exportStartTimeMs")
        // One combined FFmpeg pass handles every edit type now, so the progress
        // screen always has a single pass instead of counting active edit types.
        showExportProgressScreen(totalPasses = 1)

        exportAllEdits {
            if (!isExportActive) return@exportAllEdits
            setExportPercent(100)
            val outputCopyStartMs = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "OUTPUT_COPY_START ts=$outputCopyStartMs")
            exportFinalToOutput {
                runOnUiThread {
                    if (!isExportActive) return@runOnUiThread
                    Log.d(TAG, "OUTPUT_COPY_END elapsedMs=${android.os.SystemClock.elapsedRealtime() - outputCopyStartMs}")
                    Log.d(TAG, "EXPORT_COMPLETE totalElapsedMs=${android.os.SystemClock.elapsedRealtime() - exportStartTimeMs}")
                    dismissTextBurnProgress()
                    // ✅ Delete every local intermediate this export produced (now at
                    // most the one combined-pass output) — mirrors stopExport()'s
                    // cleanup so a SUCCESSFUL export no longer leaks temp files.
                    exportIntermediates.filter { it != videoUrl }.forEach { File(it).delete() }
                    exportIntermediates.clear()
                    clearEditStateCache()
                    deleteAllVoiceFiles()
                    val exportSuccessIntent = Intent(
                        this,
                        ExportSuccessActivity::class.java
                    ).apply {
                        putExtra(
                            ExportSuccessActivity.EXTRA_VIDEO_URI,
                            videoUrl.toUri()
                        )
                    }
                    startActivity(exportSuccessIntent)
                    finish()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (voiceBusy) cancelVoiceRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (voiceBusy) cancelVoiceRecording()
        stopMusicPreview()
        VideoTextBurner.cancel()
        dismissCropProgress()
        dismissTextBurnProgress()
    }
}