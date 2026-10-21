package com.nkd.screenrecorder.model

import com.nkd.screenrecorder.databinding.ItemOverlayBinding
import com.nkd.screenrecorder.fragments.AddStickerFragment.StickerOverlay

data class StickerTrack(
    val overlay: StickerOverlay, val overlayBinding: ItemOverlayBinding
)
