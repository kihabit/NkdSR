package com.nkd.screenrecorder.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GifItem(
    override val id: Long,
    override val name: String,
    override val uri: Uri,
    override val size: Long,
    override val dateAdded: Long,
    val filePath: String

) : MediaItem(), Parcelable

