package com.nkd.screenrecorder.utils

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.edit
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.fragments.SettingFragment
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video quality + frame rate for screen recording — the ONE source of truth.
 * Home, Settings and the floating toolbox read/write these keys (via [setQuality] /
 * [setFrameRate]); RecordScreenService calls [resolve] fresh for every recording.
 */
object VideoQualityPrefs {

    /** The only saved keys (String values = [Quality.key] / [FrameRate.key]). */
    const val KEY_VIDEO_QUALITY = "rec_video_quality"
    const val KEY_FRAME_RATE = "rec_frame_rate"
    private const val KEY_MIGRATED = "rec_video_prefs_migrated"

    // Old keys, read once by [migrate] and then removed
    private const val LEGACY_VIDEO_QUALITY = "video_quality"   // Int: 0 Low · 1 Medium · 2 High · 3 Original
    private const val LEGACY_FRAME_RATE = "frame_rate"         // Int index, see migrate()
    private const val LEGACY_FRAME_RATE_V2 = "frame_rate_v2"   // Boolean: index uses the 2nd list

    enum class Quality(val key: String, val shortSide: Int) {
        AUTO("auto", 0), P1080("1080", 1080), P720("720", 720), P480("480", 480)
    }

    enum class FrameRate(val key: String, val fps: Int) {
        AUTO("auto", 0), FPS24("24", 24), FPS30("30", 30), FPS60("60", 60)
    }

    /** What a recording actually uses. */
    data class Spec(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    private const val GIB = 1024L * 1024 * 1024

    // ── Saved values ────────────────────────────────────────────────────────

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SettingFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .also { migrate(it) }

    fun quality(context: Context): Quality {
        val saved = prefs(context).getString(KEY_VIDEO_QUALITY, null)
        val q = Quality.entries.firstOrNull { it.key == saved } ?: Quality.AUTO
        // 1080p is not offered on a screen smaller than 1080p
        return if (q == Quality.P1080 && !is1080pAvailable(context)) Quality.AUTO else q
    }

    fun setQuality(context: Context, quality: Quality) {
        prefs(context).edit { putString(KEY_VIDEO_QUALITY, quality.key) }
    }

    /** Saved frame rate; a saved 60 the encoder can't do at the current quality → 30 (saved). */
    fun frameRate(context: Context): FrameRate {
        val saved = prefs(context).getString(KEY_FRAME_RATE, null)
        val fr = FrameRate.entries.firstOrNull { it.key == saved } ?: FrameRate.AUTO
        if (fr == FrameRate.FPS60 && !is60FpsAvailable(context)) {
            setFrameRate(context, FrameRate.FPS30)
            return FrameRate.FPS30
        }
        return fr
    }

    fun setFrameRate(context: Context, frameRate: FrameRate) {
        prefs(context).edit { putString(KEY_FRAME_RATE, frameRate.key) }
    }

    /**
     * One-time move from the old keys: Original → Auto, High → 1080p, Medium → 720p,
     * Low → 480p; an old 24 / 30 / 60 FPS is kept, anything else (50, 48, 25,
     * "30 FPS (Recommended)") → Auto. New installs: Auto + Auto.
     */
    private fun migrate(p: SharedPreferences) {
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val oldQuality = runCatching { p.getInt(LEGACY_VIDEO_QUALITY, -1) }.getOrDefault(-1)
        val quality = when (oldQuality) {
            0 -> Quality.P480
            1 -> Quality.P720
            2 -> Quality.P1080
            else -> Quality.AUTO
        }
        val oldFps = runCatching { p.getInt(LEGACY_FRAME_RATE, -1) }.getOrDefault(-1)
        val fps = if (p.getBoolean(LEGACY_FRAME_RATE_V2, false)) {
            // 60 · 50 · 48 · 25 · 24 · 30 (Recommended)
            when (oldFps) { 0 -> 60; 4 -> 24; else -> 0 }
        } else {
            // first list: 60 · 50 · 48 · 30 · 25 · 24 · Default
            when (oldFps) { 0 -> 60; 3 -> 30; 5 -> 24; else -> 0 }
        }
        val frameRate = FrameRate.entries.firstOrNull { it.fps == fps && it != FrameRate.AUTO } ?: FrameRate.AUTO
        p.edit {
            if (!p.contains(KEY_VIDEO_QUALITY)) putString(KEY_VIDEO_QUALITY, quality.key)
            if (!p.contains(KEY_FRAME_RATE)) putString(KEY_FRAME_RATE, frameRate.key)
            remove(LEGACY_VIDEO_QUALITY)
            remove(LEGACY_FRAME_RATE)
            remove(LEGACY_FRAME_RATE_V2)
            putBoolean(KEY_MIGRATED, true)
        }
    }

    // ── What a recording uses ───────────────────────────────────────────────

    /** Size, FPS and bitrate for a recording of a [screenW]×[screenH] screen, from the saved values. */
    fun resolve(context: Context, screenW: Int, screenH: Int): Spec {
        val caps = avcEncoderCaps()
        val (w, h) = videoSize(effectiveShortSide(context, quality(context), screenW, screenH), screenW, screenH, caps)
        val fps = effectiveFps(context, frameRate(context), w, h, caps)
        return Spec(w, h, fps, bitrate(w, h, fps, caps))
    }

    /** Short side the quality asks for (Auto: ≤3 GB RAM / weak → 720, else 1080), capped by the screen. */
    private fun effectiveShortSide(context: Context, quality: Quality, screenW: Int, screenH: Int): Int {
        val wanted = when (quality) {
            Quality.AUTO -> if (isWeakDevice(context)) 720 else 1080
            else -> quality.shortSide
        }
        return min(wanted, min(screenW, screenH))
    }

    /**
     * Screen aspect ratio kept, both sides multiples of 16, never above the screen; steps down
     * (16px at a time) until the H.264 encoder supports the size.
     */
    private fun videoSize(
        shortSide: Int, screenW: Int, screenH: Int, caps: MediaCodecInfo.VideoCapabilities?
    ): Pair<Int, Int> {
        val screenShort = min(screenW, screenH).coerceAtLeast(1)
        var s = align16(shortSide)
        var size = scaled(s, screenShort, screenW, screenH)
        while (caps != null && !caps.isSizeSupported(size.first, size.second) && s > 160) {
            s -= 16
            size = scaled(s, screenShort, screenW, screenH)
        }
        return size
    }

    private fun scaled(short16: Int, screenShort: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val scale = short16.toDouble() / screenShort
        return if (screenW <= screenH) short16 to align16(screenH * scale)
        else align16(screenW * scale) to short16
    }

    private fun align16(v: Number): Int = ((v.toDouble().roundToInt() / 16) * 16).coerceAtLeast(16)

    private fun effectiveFps(
        context: Context, frameRate: FrameRate, w: Int, h: Int, caps: MediaCodecInfo.VideoCapabilities?
    ): Int {
        val supports60 = supports60(caps, w, h)
        return when (frameRate) {
            FrameRate.FPS60 -> if (supports60) 60 else 30
            FrameRate.AUTO -> if (supports60 && isStrongDevice(context) && maxRefreshRate(context) >= 89f) 60 else 30
            else -> frameRate.fps
        }
    }

    /** ~8 Mbps 1080p · ~5 Mbps 720p · ~2.5 Mbps 480p; ×1.5 at 60 FPS, ×0.8 at 24 FPS. */
    private fun bitrate(w: Int, h: Int, fps: Int, caps: MediaCodecInfo.VideoCapabilities?): Int {
        val short = min(w, h)
        val base = when {
            short >= 1000 -> 8_000_000
            short >= 700 -> 5_000_000
            else -> 2_500_000
        }
        val factor = when (fps) {
            60 -> 1.5
            24 -> 0.8
            else -> 1.0
        }
        val bitrate = (base * factor).toInt()
        return caps?.bitrateRange?.clamp(bitrate) ?: bitrate
    }

    private fun supports60(caps: MediaCodecInfo.VideoCapabilities?, w: Int, h: Int): Boolean =
        caps != null && runCatching { caps.areSizeAndRateSupported(w, h, 60.0) }.getOrDefault(false)

    // ── For the option sheets ───────────────────────────────────────────────

    /** 1080p is offered only on a screen at least 1080 px on its short side. */
    fun is1080pAvailable(context: Context): Boolean {
        val (w, h) = realScreenSize(context)
        return min(w, h) >= 1080
    }

    /** 60 FPS is offered only if the encoder can do it at the chosen quality. */
    fun is60FpsAvailable(context: Context): Boolean {
        val (sw, sh) = realScreenSize(context)
        val caps = avcEncoderCaps()
        val (w, h) = videoSize(effectiveShortSide(context, quality(context), sw, sh), sw, sh, caps)
        return supports60(caps, w, h)
    }

    /** "1080p" — what Auto quality picks on this phone. */
    fun autoQualityLabel(context: Context): String {
        val (sw, sh) = realScreenSize(context)
        return context.getString(R.string.quality_p_value, effectiveShortSide(context, Quality.AUTO, sw, sh))
    }

    /** 30 or 60 — what Auto frame rate picks on this phone at the chosen quality. */
    fun autoFps(context: Context): Int {
        val (sw, sh) = realScreenSize(context)
        val caps = avcEncoderCaps()
        val (w, h) = videoSize(effectiveShortSide(context, quality(context), sw, sh), sw, sh, caps)
        return effectiveFps(context, FrameRate.AUTO, w, h, caps)
    }

    fun qualityLabel(context: Context, quality: Quality): String =
        if (quality == Quality.AUTO) context.getString(R.string.option_auto)
        else context.getString(R.string.quality_p_value, quality.shortSide)

    fun frameRateLabel(context: Context, frameRate: FrameRate): String =
        if (frameRate == FrameRate.AUTO) context.getString(R.string.option_auto)
        else context.getString(R.string.fps_value, frameRate.fps)

    /** Settings video-quality chip: "Auto · 1080p" or "720p". */
    fun qualitySummary(context: Context): String {
        val q = quality(context)
        return if (q == Quality.AUTO) {
            context.getString(R.string.option_summary, context.getString(R.string.option_auto), autoQualityLabel(context))
        } else qualityLabel(context, q)
    }

    /** Settings frame-rate chip: "Auto · 30 FPS" or "60 FPS". */
    fun frameRateSummary(context: Context): String {
        val fr = frameRate(context)
        return if (fr == FrameRate.AUTO) {
            context.getString(
                R.string.option_summary,
                context.getString(R.string.option_auto),
                context.getString(R.string.fps_value, autoFps(context))
            )
        } else frameRateLabel(context, fr)
    }

    /** Home card: "Auto · 1080p", "720p" or "720p · 60 FPS" (FPS only when not Auto). */
    fun homeSummary(context: Context): String {
        val fr = frameRate(context)
        val quality = qualitySummary(context)
        return if (fr == FrameRate.AUTO) quality
        else context.getString(R.string.option_summary, quality, frameRateLabel(context, fr))
    }

    // ── Device ──────────────────────────────────────────────────────────────

    private fun totalRam(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    /** ≤3 GB RAM (reported as < 3.5 GiB), a low-RAM device or < 4 CPU cores. */
    private fun isWeakDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isLowRamDevice || totalRam(context) < (3.5 * GIB).toLong() ||
                Runtime.getRuntime().availableProcessors() < 4
    }

    /** ≥6 GB RAM (reported as ≥ 5.5 GiB) and ≥ 8 CPU cores. */
    private fun isStrongDevice(context: Context): Boolean =
        !isWeakDevice(context) && totalRam(context) >= (5.5 * GIB).toLong() &&
                Runtime.getRuntime().availableProcessors() >= 8

    private fun defaultDisplay(context: Context): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)

    /** Highest refresh rate the screen supports (90/120 Hz screens may idle at 60). */
    private fun maxRefreshRate(context: Context): Float {
        val display = defaultDisplay(context) ?: return 60f
        return display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }

    /** Full screen size in pixels, current orientation. */
    fun realScreenSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay(context)?.getRealMetrics(metrics)
        return if (metrics.widthPixels > 0) metrics.widthPixels to metrics.heightPixels
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    /** The H.264 encoder MediaRecorder will use (first one listed). */
    private fun avcEncoderCaps(): MediaCodecInfo.VideoCapabilities? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities
    }.getOrNull()
}
