package com.nkd.screenrecorder.widgets

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AttributeSet
import android.widget.VideoView

class ZVideoView : VideoView {
    private var videoRealW = 1
    private var videoRealH = 1

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun setVideoURI(uri: Uri) {
        super.setVideoURI(uri)
        val retr = MediaMetadataRetriever()
        retr.setDataSource(context, uri)
        val height = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val width = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        try {
            videoRealH = height!!.toInt()
            videoRealW = width!!.toInt()
        } catch (e: NumberFormatException) {
            e.printStackTrace()
        }
    }
}
