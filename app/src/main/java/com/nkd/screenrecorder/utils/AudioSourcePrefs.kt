package com.nkd.screenrecorder.utils

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.fragments.SettingFragment

/**
 * Audio source for screen recording — the ONE source of truth.
 * Home, Settings and the floating toolbox read/write [KEY_AUDIO_SOURCE] (via [setSource]);
 * RecordScreenService calls [recordsMicrophone] fresh for every recording.
 */
object AudioSourcePrefs {

    /** The only saved key (String value = [Source.key]). */
    const val KEY_AUDIO_SOURCE = "rec_audio_source"

    // Old key, read once by [migrate] and then removed
    private const val LEGACY_AUDIO_SOURCE = "audio_source"   // Int: 0 Mute · anything else Microphone

    enum class Source(val key: String) { MUTE("mute"), MICROPHONE("mic") }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SettingFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .also { migrate(it) }

    /** Saved choice; nothing saved = Microphone (as before). */
    fun source(context: Context): Source {
        val saved = prefs(context).getString(KEY_AUDIO_SOURCE, null)
        return Source.entries.firstOrNull { it.key == saved } ?: Source.MICROPHONE
    }

    fun setSource(context: Context, source: Source) {
        prefs(context).edit { putString(KEY_AUDIO_SOURCE, source.key) }
    }

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /** True only when Microphone is chosen AND RECORD_AUDIO is granted (else video only). */
    fun recordsMicrophone(context: Context): Boolean =
        source(context) == Source.MICROPHONE && hasMicPermission(context)

    fun label(context: Context, source: Source): String = context.getString(
        if (source == Source.MUTE) R.string.audio_source_mute else R.string.audio_source_microphone
    )

    fun subtitle(context: Context, source: Source): String = context.getString(
        if (source == Source.MUTE) R.string.audio_source_mute_desc else R.string.audio_source_mic_desc
    )

    /** "Microphone" / "Mute" for Home, Settings and the toolbox. */
    fun summary(context: Context): String = label(context, source(context))

    /** Old Int key → new String key, once. */
    private fun migrate(p: SharedPreferences) {
        if (!p.contains(LEGACY_AUDIO_SOURCE)) return
        val old = try {
            p.getInt(LEGACY_AUDIO_SOURCE, 1)
        } catch (_: ClassCastException) {
            1
        }
        p.edit {
            if (!p.contains(KEY_AUDIO_SOURCE)) {
                putString(KEY_AUDIO_SOURCE, if (old == 0) Source.MUTE.key else Source.MICROPHONE.key)
            }
            remove(LEGACY_AUDIO_SOURCE)
        }
    }
}
