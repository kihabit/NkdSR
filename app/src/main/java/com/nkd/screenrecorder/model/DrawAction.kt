package com.nkd.screenrecorder.model

import android.graphics.Paint
import android.graphics.Path
import com.nkd.screenrecorder.utils.DrawMode

data class DrawAction(
    val path: Path,
    val paint: Paint,
    val mode: DrawMode
)
