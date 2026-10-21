package com.nkd.screenrecorder.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nkd.screenrecorder.activities.MainActivity.Companion.KEY_DIRECTORY_URI
import com.nkd.screenrecorder.model.MediaItem
import com.nkd.screenrecorder.model.MediaListItem
import com.nkd.screenrecorder.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoViewModel : ViewModel() {

    private val _videoLiveData: MutableLiveData<List<MediaListItem>> = MutableLiveData()
    val videoLiveData: LiveData<List<MediaListItem>>
        get() = _videoLiveData


    fun fetchRecordedVideosFromSAF(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaList = mutableListOf<MediaItem>()

            val prefs = context.getSharedPreferences("ScreenRecorderPrefs", Context.MODE_PRIVATE)
            val uriString = prefs.getString(KEY_DIRECTORY_URI, null)

            val treeUri = uriString?.toUri()
            if (treeUri == null) {
                return@launch
            }
            val directory = DocumentFile.fromTreeUri(context, treeUri)
            if (directory == null) {
                return@launch
            }
            for (file in directory.listFiles()) {

                if (!file.isFile) continue

                val mime = file.type ?: continue

                if (!mime.startsWith("video")) continue

                val item = VideoItem(
                    id = file.uri.hashCode().toLong(), // SAF has no _ID
                    name = file.name ?: "video",
                    uri = file.uri,
                    size = file.length(),
                    dateAdded = file.lastModified(),
                    duration = getVideoDuration(context, file.uri)
                )

                mediaList.add(item)
            }

            // newest first
            _videoLiveData.postValue(buildSectionedList(mediaList.sortedByDescending { it.dateAdded }))
        }

    }

    fun getVideoDuration(context: Context, uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    fun buildSectionedList(media: List<MediaItem>): List<MediaListItem> {
        val result = mutableListOf<MediaListItem>()

        val grouped = media.groupBy { getDateTitle(it.dateAdded) }

        grouped.forEach { (title, items) ->
            result.add(MediaListItem.Header(title))
            items.forEach {
                result.add(MediaListItem.Media(it))
            }
        }

        return result
    }

    // Same as Utils.getDateTitle (translated "Today" / "Yesterday", local date format)
    fun getDateTitle(dateAddedSeconds: Long): String =
        com.nkd.screenrecorder.utils.Utils.getDateTitle(dateAddedSeconds)

    fun deleteVideo(context: Context, videoUri: Uri): Boolean {
        return try {
            val file = DocumentFile.fromSingleUri(context, videoUri)
            file?.delete() == true
        } catch (e: Exception) {
            false
        }
    }

    fun renameVideod(context: Context, videoUri: Uri, newName: String): Boolean {
        return try {
            val file = DocumentFile.fromSingleUri(context, videoUri)
            file?.renameTo(newName) == true
        } catch (e: Exception) {
            print(e)
            Log.e("VideoViewHolder", "${e}")
            false
        }
    }

    fun hasWritePermission(context: Context, uri: Uri): Boolean {
        val perms = context.contentResolver.persistedUriPermissions
        return perms.any { it.uri == uri && it.isWritePermission }
    }

    fun renameVideo(context: Context, videoUri: Uri, newName: String): Boolean {
        try {
            if (!hasWritePermission(context, videoUri)) {
                Log.e("VideoViewModel", "Has Write Permission")
            }
            val resolver = context.contentResolver

            val safeName = if (newName.endsWith(".mp4")) newName else "$newName.mp4"

            val newUri = DocumentsContract.renameDocument(resolver, videoUri, safeName)

            if (newUri != null) {
                Log.d("RENAME", "Rename success")
                return true
            } else {
                Log.e("RENAME", "Rename returned null")
                return false
            }

        } catch (e: Exception) {
            Log.e("RENAME", "Rename error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }


}