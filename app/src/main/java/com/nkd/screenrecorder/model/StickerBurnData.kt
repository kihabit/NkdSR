package com.nkd.screenrecorder.model

data class StickerBurnData(
    val imagePath: String,
    val xPx: Int,
    val yPx: Int,
    val width: Int,
    val height: Int,
    val startSec: Double,
    val endSec: Double
)
