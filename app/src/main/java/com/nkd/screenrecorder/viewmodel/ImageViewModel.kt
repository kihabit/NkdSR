package com.nkd.screenrecorder.viewmodel

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nkd.screenrecorder.model.GifItem
import com.nkd.screenrecorder.model.ImageItem
import com.nkd.screenrecorder.model.MediaItem
import com.nkd.screenrecorder.model.MediaListItem
import com.nkd.screenrecorder.utils.Event
import com.nkd.screenrecorder.utils.Utils.getDateTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    private val _imageList = MutableLiveData<List<ImageItem>>()
    val imageList: LiveData<List<ImageItem>> = _imageList

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectionMode = MutableLiveData<Boolean>(false)
    val selectionMode: LiveData<Boolean> = _selectionMode

    private val _selectedItems = MutableLiveData<Set<Long>>(emptySet())
    val selectedItems: LiveData<Set<Long>> = _selectedItems

    private val _deleteResult = MutableLiveData<Event<Boolean>>()
    val deleteResult: LiveData<Event<Boolean>> = _deleteResult

    // GIF
    private val _gifList = MutableLiveData<List<MediaListItem>>()
    val gifList: LiveData<List<MediaListItem>> = _gifList

    private val _isGifLoading = MutableLiveData<Boolean>()
    val isGifLoading: LiveData<Boolean> = _isGifLoading

    private val _deleteGIFResult = MutableLiveData<Event<Boolean>>()
    val deleteGIFResult: LiveData<Event<Boolean>> = _deleteGIFResult

    fun fetchImages() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val images = fetchImagesFromMediaStore()
                _imageList.value = images
            } catch (e: Exception) {
                e.printStackTrace()
                _imageList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchImagesFromMediaStore(): List<ImageItem> = withContext(Dispatchers.IO) {
        val imageList = mutableListOf<ImageItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA // Needed for Android 9
        )

        val (selection, selectionArgs) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to
                        arrayOf("%Pictures/ScreenRecorder%")
            } else {
                "${MediaStore.Images.Media.DATA} LIKE ?" to
                        arrayOf("%/Pictures/ScreenRecorder/%")
            }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"


        getApplication<Application>().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                imageList.add(
                    ImageItem(
                        id = id,
                        name = name,
                        uri = contentUri,
                        size = size,
                        dateAdded = dateAdded
                    )
                )
            }
        }

        return@withContext imageList
    }

    fun fetchGIF() {
        viewModelScope.launch {
            _isGifLoading.value = true
            try {
                val gifList = fetchGIFFromMediaStore()
                _gifList.value = buildSectionedList(gifList)
            } catch (e: Exception) {
                Log.e("ImageViewModel", " ${e.message}")
                e.printStackTrace()
                _gifList.value = emptyList()
            } finally {
                _isGifLoading.value = false
            }
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

    private suspend fun fetchGIFFromMediaStore(): List<GifItem> = withContext(Dispatchers.IO) {
        val imageList = mutableListOf<GifItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA // Needed for Android 9
        )

        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection =
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE}=?"
            selectionArgs = arrayOf("%Pictures/GIF%", "image/gif")
        } else {
            //${MediaStore.Images.Media.DATA} LIKE ? AND
//"%/Pictures/GIF/%",
//            selection =
//                "${MediaStore.Images.Media.MIME_TYPE}=?"
//            selectionArgs = arrayOf("image/gif")
             selection = "${MediaStore.Images.Media.MIME_TYPE}=?"
             selectionArgs = arrayOf("image/gif")
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"


        getApplication<Application>().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            Log.e("ImageViewModel", "${cursor.count}")

            while (cursor.moveToNext()) {

                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val filePath =
                    try {
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ""
                    }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                imageList.add(
                    GifItem(
                        id = id,
                        name = name,
                        uri = contentUri,
                        size = size,
                        dateAdded = dateAdded,
                        filePath = filePath,
                    )
                )
            }
        }

        return@withContext imageList
    }

    fun toggleSelection(imageId: Long) {
        val currentSelection = _selectedItems.value.orEmpty().toMutableSet()
        if (currentSelection.contains(imageId)) {
            currentSelection.remove(imageId)
        } else {
            currentSelection.add(imageId)
        }
        _selectedItems.value = currentSelection

        // Exit selection mode if no items selected
        if (currentSelection.isEmpty()) {
            _selectionMode.value = false
        }
    }

    fun enableSelectionMode() {
        _selectionMode.value = true
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _selectionMode.value = false
    }

    fun selectAll() {
        val allIds = _imageList.value?.map { it.id }?.toSet() ?: emptySet()
        _selectedItems.value = allIds
    }

    fun selectAllGif() {
        val allIds: HashSet<Long> = HashSet()

        _gifList.value?.forEach { item ->
            if (item is MediaListItem.Media) {
                allIds.add(item.item.id)
            }
        }
        _selectedItems.value = allIds
    }

    fun getSelectedCount(): Int {
        return _selectedItems.value?.size ?: 0
    }

    fun deleteSelectedImages(isGif: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectedIds = _selectedItems.value.orEmpty()
                val resolver = getApplication<Application>().contentResolver

                var deletedCount = 0
                selectedIds.forEach { id ->
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val deleted = resolver.delete(uri, null, null)
                    if (deleted > 0) deletedCount++
                }

                withContext(Dispatchers.Main) {
                    if (isGif) {
                        _deleteGIFResult.value = Event(deletedCount > 0)
                        fetchGIF()
                    } else {
                        _deleteResult.value = Event(deletedCount > 0)
                        fetchImages() // Refresh the list
                    }
                    clearSelection()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isGif) {
                        _deleteGIFResult.value = Event(false)
                    } else {
                        _deleteResult.value = Event(false)
                    }
                }
            }
        }
    }

    fun getSelectedImageUris(): List<Uri> {
        val selectedIds = _selectedItems.value.orEmpty()
        return _imageList.value?.filter { selectedIds.contains(it.id) }?.map { it.uri }
            ?: emptyList()
    }

    fun getSelectedGIFUris(): List<Uri> {
        val uriList: MutableList<Uri> = mutableListOf<Uri>()
        val selectedIds = _selectedItems.value.orEmpty()
        _gifList.value?.forEach { item ->
            if (item is MediaListItem.Media) {
                if (selectedIds.contains(item.item.id)) {
                    uriList.add(item.item.uri)
                }
            }
        }
        return uriList
    }
}