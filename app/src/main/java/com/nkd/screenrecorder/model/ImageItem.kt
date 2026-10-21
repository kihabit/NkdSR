package com.nkd.screenrecorder.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImageItem(
    override val id: Long,
    override val name: String,
    override val uri: Uri,
    override val size: Long, override val dateAdded: Long
) : MediaItem(), Parcelable

