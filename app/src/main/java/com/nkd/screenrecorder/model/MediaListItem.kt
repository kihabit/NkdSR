package com.nkd.screenrecorder.model

sealed class MediaListItem {
    data class Header(
        val title: String   // Today / Yesterday / 10-01-2026
    ) : MediaListItem()

    data class Media(
        val item: MediaItem
    ) : MediaListItem()
}