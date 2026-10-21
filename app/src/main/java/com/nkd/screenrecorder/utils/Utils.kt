package com.nkd.screenrecorder.utils

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import com.nkd.screenrecorder.MyApp
import com.nkd.screenrecorder.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object Utils {
    // Context in the language chosen in the app, for helpers that get no context.
    // Cached per language: getDateTitle() runs once per media item.
    private var locLang: String? = null
    private var locContext: Context? = null

    private fun loc(): Context? {
        val app = MyApp.photoApp ?: return null
        val lang = LocaleHelper.getLanguage(app)
        if (lang != locLang || locContext == null) {
            locContext = LocaleHelper.wrap(app)
            locLang = lang
        }
        return locContext
    }

    fun getDateTitle(dateAddedSeconds: Long): String {
        val dateMillis = dateAddedSeconds * 1000
        val now = System.currentTimeMillis()

        val diff = now - dateMillis
        val oneDay = 24 * 60 * 60 * 1000

        val c = loc()
        return when {
            diff < oneDay -> c?.getString(R.string.today) ?: "Today"
            diff < 2 * oneDay -> c?.getString(R.string.yesterday) ?: "Yesterday"
            // Date order/separators of the selected language
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.getDefault())
                .format(Date(dateMillis))
        }
    }

    fun formatFileSize(bytes: Long): String {
        val c = loc()
        if (bytes <= 0) return c?.getString(R.string.size_kb, "0") ?: "0 KB"

        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> c?.getString(R.string.size_gb, String.format("%.2f", gb)) ?: String.format("%.2f GB", gb)
            mb >= 1 -> c?.getString(R.string.size_mb, String.format("%.2f", mb)) ?: String.format("%.2f MB", mb)
            else -> c?.getString(R.string.size_kb, String.format("%.2f", kb)) ?: String.format("%.2f KB", kb)
        }
    }

    fun formatFileSizeToGB(bytes: Long): String {
        if (bytes <= 0) return "0"

        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return String.format("%.2f", gb)
    }

    fun formatDuration(durationMillis: Long): String {
        if (durationMillis <= 0) return "00:00"

        val totalSeconds = durationMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun getVideoThumbnailUri(videoUri: Uri): Uri {
        return Uri.withAppendedPath(videoUri, "thumbnail")
    }

    fun getStorageInfo(): Triple<Long, Long, Long> {
        return try {
            val path = Environment.getExternalStorageDirectory()
            val statFs = StatFs(path.path)

            val totalBytes = statFs.totalBytes
            val availableBytes = statFs.availableBytes
            val usedBytes = totalBytes - availableBytes

            Triple(totalBytes, usedBytes, availableBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            Triple(0L, 0L, 0L)
        }
    }
}

fun Int.dpToPx(): Int {
    return (this * Resources.getSystem().displayMetrics.density).toInt()
}

fun Float.dpToPx(): Float {
    return (this * Resources.getSystem().displayMetrics.density)
}

fun convertSecondsToTime(seconds: Long): String {
    var timeStr: String = ""
    var hour = 0
    var minute = 0
    var second = 0
    if (seconds <= 0) return "00:00"
    else {
        minute = seconds.toInt() / 60
        if (minute < 60) {
            second = seconds.toInt() % 60
            timeStr = unitFormat(minute) + ":" + unitFormat(second)
        } else {
            hour = minute / 60
            if (hour > 99) return "99:59:59"
            minute = minute % 60
            second = (seconds - hour * 3600 - minute * 60).toInt()
            timeStr = unitFormat(hour) + ":" + unitFormat(minute) + ":" + unitFormat(second)
        }
    }
    return timeStr
}

fun convertSecondsToFormat(seconds: Long, format: String?): String {
    if (TextUtils.isEmpty(format)) return ""

    val date = Date(seconds)
    val sdf = SimpleDateFormat(format, Locale.getDefault())
    return sdf.format(date)
}

private fun unitFormat(i: Int): String {
    var retStr: String = ""
    if (i >= 0 && i < 10) retStr = "0" + i.toString()
    else retStr = "" + i
    return retStr
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        hours,
        minutes,
        seconds,
    )
}

/** Same as [formatTime] but ALWAYS Latin digits (Locale.US): for FFmpeg -ss / -t arguments.
 *  The app language sets Locale.getDefault(), which gives Arabic digits in Arabic. */
fun formatTimeForFfmpeg(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

fun convertToDurationForVideo(currentPosition: Int, totalDuration: Int): String {
    val totalSeconds = currentPosition / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val totalSeconds1 = totalDuration / 1000
    val hours1 = totalSeconds1 / 3600
    val minutes1 = (totalSeconds1 % 3600) / 60
    val seconds1 = totalSeconds1 % 60

    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d / %02d:%02d:%02d",
        hours,
        minutes,
        seconds, hours1, minutes1, seconds1
    )
}