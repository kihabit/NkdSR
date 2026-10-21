package com.nkd.screenrecorder.activities

import android.app.ProgressDialog
import android.content.ContentUris
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.adapter.MusicListAdapter
import com.nkd.screenrecorder.databinding.ActivityMusicPickerBinding
import com.nkd.screenrecorder.utils.AudioExtractor
import com.nkd.screenrecorder.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MusicPickerActivity : BaseActivity() {

    private val musicList = mutableListOf<MusicItem>()
    private val TAG = MusicPickerActivity::class.simpleName

    data class MusicItem(
        val id: Long,
        val title: String,
        val artist: String,
        val duration: Long,
        val uri: Uri,
        val artworkUri: Uri,
        val size: String,
    )

    private lateinit var binding: ActivityMusicPickerBinding

    private var isMusicLoaded = false
    private var isSearchOpen = false

    // ── "Extract audio from video": system Photo Picker, videos only (no permission) ──
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onVideoPickedForExtract(uri)
        }

    // ── "OPEN FROM ›": system file picker for any audio file ──
    private val openFromLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onOpenFromPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.constraint) { view, insets ->
            val bottomNavInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bottomNavInsets.top, 0, bottomNavInsets.bottom)
            insets
        }
        // Dark screen → light status/navigation bar icons
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupTopBar()
        binding.cardExtract.setOnClickListener { launchExtractFromVideo() }
        binding.tvOpenFrom.setOnClickListener { launchOpenFrom() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSearchOpen) {
                    closeSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Use your own layout with a RecyclerView
        lifecycleScope.launch(Dispatchers.IO) {
            loadMusicFromStorage()
        }

    }

    private suspend fun loadMusicFromStorage() {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )
        Log.e(TAG, "cursor ${cursor != null} ${cursor?.count}")
        cursor?.use {

            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val size = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val albumId = it.getLong(albumIdCol)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )

                val albumArtUri = ContentUris.withAppendedId(
                    "content://media/external/audio/albumart".toUri(),
                    albumId
                )
                val sizeString = Utils.formatFileSize(it.getLong(size))
                musicList.add(
                    MusicItem(
                        id = id,
                        // MediaStore can return null title/artist — never crash on it
                        title = it.getString(titleCol) ?: "",
                        artist = it.getString(artistCol) ?: "",
                        duration = it.getLong(durationCol),
                        uri = contentUri,
                        artworkUri = albumArtUri, // 👈 add this field
                        size = sizeString
                    )
                )
            }
        }
        cursor?.close()
        withContext(Dispatchers.Main) {
            binding.progressBar.isGone = true
            isMusicLoaded = true
            val adapter = MusicListAdapter(musicList)
            musicListAdapter = adapter
            binding.recyclerView.adapter = adapter
            adapter.onRowClick = { position, musicItem ->
                onRowTapped(position, musicItem)
            }
            adapter.onItemCLick = { musicItem ->
                onMusicSelected(musicItem)
            }
            applyFilter()
        }
    }

    // ── Top bar + inline search ────────────────────────────────────────────
    private fun setupTopBar() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
        binding.etSearch.doAfterTextChanged { if (isSearchOpen) applyFilter() }
    }

    private fun openSearch() {
        if (isSearchOpen) return
        isSearchOpen = true
        binding.titleContainer.isVisible = false
        binding.btnSearch.isVisible = false
        binding.searchContainer.isVisible = true
        binding.etSearch.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        if (!isSearchOpen) return
        isSearchOpen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.setText("")
        binding.searchContainer.isVisible = false
        binding.titleContainer.isVisible = true
        binding.btnSearch.isVisible = true
        applyFilter()
    }

    /** All songs, filtered by the search text (title or artist, case-insensitive). */
    private fun applyFilter() {
        val adapter = musicListAdapter ?: return
        val query = if (isSearchOpen) binding.etSearch.text?.toString()?.trim().orEmpty() else ""
        val shown = if (query.isEmpty()) musicList.toList()
        else musicList.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        releasePreview()               // list positions change → stop the preview
        adapter.submitList(shown)      // also clears the selected row
        binding.tvEmpty.isVisible = isMusicLoaded && shown.isEmpty()
    }

    // ── Song preview ───────────────────────────────────────────────────────
    private var musicListAdapter: MusicListAdapter? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewPosition = RecyclerView.NO_POSITION

    /** Tap a song → play it; tap it again → pause/resume; tap another → switch. */
    private fun onRowTapped(position: Int, item: MusicItem) {
        if (position == RecyclerView.NO_POSITION) return
        val adapter = musicListAdapter ?: return
        val player = previewPlayer
        if (player != null && position == previewPosition) {
            try {
                if (player.isPlaying) player.pause() else player.start()
                adapter.setSelection(position, player.isPlaying)
            } catch (e: IllegalStateException) {
                // Still preparing — ignore the tap
            }
            return
        }

        releasePreview()
        previewPosition = position
        adapter.setSelection(position, false)
        val newPlayer = MediaPlayer()
        previewPlayer = newPlayer
        try {
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            newPlayer.setDataSource(this, item.uri)
            newPlayer.setOnPreparedListener { mp ->
                if (previewPlayer === mp) {
                    mp.start()
                    musicListAdapter?.setSelection(position, true)
                }
            }
            newPlayer.setOnCompletionListener { mp ->
                if (previewPlayer === mp) musicListAdapter?.setSelection(position, false)
            }
            newPlayer.setOnErrorListener { mp, _, _ ->
                if (previewPlayer === mp) {
                    releasePreview()
                    musicListAdapter?.setSelection(position, false)
                }
                true
            }
            newPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Preview failed: ${e.message}")
            releasePreview()
            adapter.setSelection(position, false)
        }
    }

    private fun releasePreview() {
        previewPlayer?.let { mp ->
            try {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.stop()
            } catch (_: IllegalStateException) {
            }
            mp.release()
        }
        previewPlayer = null
        previewPosition = RecyclerView.NO_POSITION
    }

    /** Stops the preview but keeps the selected row highlighted (not playing). */
    private fun stopPreview() {
        val selected = musicListAdapter?.selectedPosition ?: RecyclerView.NO_POSITION
        releasePreview()
        musicListAdapter?.setSelection(selected, false)
    }

    /** Leaving the screen (home, another app, back, Use) always stops the preview. */
    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroy() {
        releasePreview()
        AudioExtractor.cancel()
        super.onDestroy()
    }

    // Call this when user taps a music item
    fun onMusicSelected(item: MusicItem) {
        returnAudioResult(item.uri, item.title, item.duration)
    }

    /** The single result contract with EditVideoActivity (unchanged extras). */
    private fun returnAudioResult(uri: Uri, title: String, durationMs: Long) {
        releasePreview()
        val resultIntent = Intent().apply {
            putExtra("MUSIC_URI", uri)
            putExtra("MUSIC_TITLE", title)
            putExtra("MUSIC_DURATION", durationMs)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    // ── OPEN FROM › (system file picker) ───────────────────────────────────
    private fun launchOpenFrom() {
        stopPreview()
        openFromLauncher.launch(arrayOf("audio/*"))
    }

    /** Same handling as the editor's old fallback: persist read access so drafts/export work. */
    private fun onOpenFromPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }
        lifecycleScope.launch {
            val (title, durationMs) = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                (name ?: getString(R.string.selected_music)) to AudioExtractor.readDurationMs(this@MusicPickerActivity, uri)
            }
            returnAudioResult(uri, title, durationMs)
        }
    }

    // ── Extract audio from video ───────────────────────────────────────────
    private fun launchExtractFromVideo() {
        stopPreview()
        pickVideoLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    @Suppress("DEPRECATION")
    private fun onVideoPickedForExtract(videoUri: Uri) {
        stopPreview()
        lifecycleScope.launch {
            val ctx = this@MusicPickerActivity
            val (hasAudio, durationMs, displayName) = withContext(Dispatchers.IO) {
                Triple(
                    AudioExtractor.hasAudioTrack(ctx, videoUri),
                    AudioExtractor.readDurationMs(ctx, videoUri),
                    queryDisplayName(videoUri)
                )
            }
            if (!hasAudio) {
                Toast.makeText(ctx, getString(R.string.no_audio_in_video), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "video_${System.currentTimeMillis()}"
            val outDir = File(filesDir, "extracted_audio").apply { mkdirs() }
            val outFile = uniqueFile(outDir, sanitizeFileName(title), "m4a")

            val dialog = ProgressDialog(ctx).apply {
                setTitle(getString(R.string.extracting_audio))
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setCancelable(false)
                setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel)) { _, _ ->
                    AudioExtractor.cancel()
                }
                show()
            }

            AudioExtractor.extract(
                context = ctx,
                videoUri = videoUri,
                outFile = outFile,
                durationMs = durationMs,
                onProgress = { pct -> runOnUiThread { dialog.progress = pct } },
                onSuccess = { file ->
                    val audioMs = AudioExtractor.readDurationMs(ctx, Uri.fromFile(file))
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog.dismiss()
                            returnAudioResult(Uri.fromFile(file), title, audioMs)
                        }
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        dialog.dismiss()
                        if (error != "Cancelled") {
                            Toast.makeText(ctx, getString(R.string.extract_audio_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "queryDisplayName failed: ${e.message}")
        null
    }

    /** Keeps letters/digits/space/._- ; everything else → '_' (max 60 chars). */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().take(60).ifBlank { "audio" }

    /** name.m4a, or name_2.m4a, name_3.m4a … if it already exists. */
    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var file = File(dir, "$base.$ext")
        var n = 2
        while (file.exists()) {
            file = File(dir, "${base}_$n.$ext")
            n++
        }
        return file
    }
}
