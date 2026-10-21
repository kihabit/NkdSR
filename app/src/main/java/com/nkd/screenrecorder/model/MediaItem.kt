package com.nkd.screenrecorder.model

import android.net.Uri

sealed class MediaItem {
    abstract val id: Long
    abstract val name: String
    abstract val uri: Uri
    abstract val size: Long
    abstract val dateAdded: Long
}
