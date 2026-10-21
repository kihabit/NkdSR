package com.nkd.screenrecorder.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoItem(
    override val id: Long,
    override val name: String,
    override val uri: Uri,
    val duration: Long,
    override val size: Long, override val dateAdded: Long
) : Parcelable, MediaItem()
