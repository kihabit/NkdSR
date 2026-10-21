package com.nkd.screenrecorder.interfaces

interface VideoTrimListener {
    fun onStartTrim()

    fun onFinishTrim(url: String)

    fun onCancel()
}