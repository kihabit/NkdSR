package com.nkd.screenrecorder.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.fragments.SettingFragment
import com.nkd.screenrecorder.utils.VideoQualityPrefs.Quality

/**
 * Audio bitrate for recordings: Good 96 / Best 128 / Excellent 256 kbps.
 * Saved PER video quality (Auto / 1080p / 720p / 480p), in the same prefs file as [AudioSourcePrefs].
 * RecordScreenService calls [bitrate] fresh for every recording; it uses the audio quality of the
 * currently selected video quality.
 * Nothing saved = Excellent (same as the old fixed 256 kbps).
 */
object AudioQualityPrefs {

    /** Old single setting (before per-quality). Used only as the starting value if present. */
    private const val KEY_LEGACY = "rec_audio_quality"
    private const val KEY_PREFIX = "rec_audio_quality_"

    enum class Level(val key: String, val kbps: Int) {
        GOOD("good", 96),
        BEST("best", 128),
        EXCELLENT("excellent", 256)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SettingFragment.PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(quality: Quality) = KEY_PREFIX + quality.name.lowercase()

    private fun fromKey(saved: String?): Level? = Level.entries.firstOrNull { it.key == saved }

    /** Audio quality saved for this video quality. */
    fun level(context: Context, quality: Quality): Level {
        val p = prefs(context)
        return fromKey(p.getString(key(quality), null))
            ?: fromKey(p.getString(KEY_LEGACY, null))
            ?: Level.EXCELLENT
    }

    fun setLevel(context: Context, quality: Quality, level: Level) {
        prefs(context).edit { putString(key(quality), level.key) }
    }

    /** For MediaRecorder.setAudioEncodingBitRate(...) — uses the currently selected video quality. */
    fun bitrate(context: Context): Int =
        level(context, VideoQualityPrefs.quality(context)).kbps * 1000

    fun label(context: Context, level: Level): String = context.getString(
        when (level) {
            Level.GOOD -> R.string.audio_quality_good
            Level.BEST -> R.string.audio_quality_best
            Level.EXCELLENT -> R.string.audio_quality_excellent
        }
    )

    fun kbpsLabel(context: Context, level: Level): String =
        context.getString(R.string.audio_kbps_value, level.kbps)

    /** "Good" / "Best" / "Excellent" for the currently selected video quality. */
    fun summary(context: Context): String =
        label(context, level(context, VideoQualityPrefs.quality(context)))

    /** Text shown in the chip of one row, e.g. "Best ▾" */
    fun chipText(context: Context, quality: Quality): String =
        context.getString(R.string.audio_chip_format, label(context, level(context, quality)))
}