package com.thingsapart.videopt

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Moved from SettingsActivity
    internal val STANDARD_RESOLUTIONS_MAP = linkedMapOf(
        "Original" to "Original", // Keep "Original" as a special case if it means pass-through
        "480p (854x480)" to "854x480",
        "720p (1280x720)" to "1280x720",
        "1080p (1920x1080)" to "1920x1080",
        "2K (2560x1440)" to "2560x1440",
        "4K (3840x2160)" to "3840x2160"
    )

    // Moved and refined from SettingsActivity
    internal fun parseResolutionValue(resolutionSettingString: String): Pair<Int, Int>? {
        if (resolutionSettingString.equals("Original", ignoreCase = true)) {
            return null // "Original" means use source dimensions, not specific target ones here.
        }

        // Check if it's a display name from our standard map's keys (e.g., "1080p (1920x1080)")
        STANDARD_RESOLUTIONS_MAP[resolutionSettingString]?.let { valueString ->
            // valueString is like "1920x1080" or "Original"
            if (valueString.equals("Original", ignoreCase = true)) return null
            return valueString.split("x").takeIf { it.size == 2 }?.let {
                try { it[0].toInt() to it[1].toInt() } catch (e: NumberFormatException) { null }
            }
        }

        // Check for "Custom (WidthxHeight)" format (e.g., "Custom (1920x1080)")
        if (resolutionSettingString.startsWith("Custom (", ignoreCase = true) && resolutionSettingString.endsWith(")")) {
            val dimsStr = resolutionSettingString.substringAfter("Custom (").dropLast(1)
            return dimsStr.split("x").takeIf { it.size == 2 }?.let {
                try { it[0].toInt() to it[1].toInt() } catch (e: NumberFormatException) { null }
            }
        }

        // Check for direct "WidthxHeight" format (e.g., "1920x1080" if a custom value was saved directly as WxH)
        if (resolutionSettingString.contains("x") && !resolutionSettingString.contains("(") && !resolutionSettingString.startsWith("Original")) {
             return resolutionSettingString.split("x").takeIf { it.size == 2 }?.let {
                try { it[0].toInt() to it[1].toInt() } catch (e: NumberFormatException) { null }
            }
        }

        android.util.Log.w("SettingsManager", "Could not parse resolution string to dimensions: $resolutionSettingString")
        return null
    }


    companion object {
        private const val PREFS_NAME = "video_transcoder_settings"
        const val PREF_RESOLUTION = "pref_resolution"
        const val PREF_FORMAT = "pref_format"
        const val PREF_QUALITY = "pref_quality"
        const val PREF_FRAME_RATE = "pref_frame_rate"
        const val PREF_AUDIO_BITRATE = "pref_audio_bitrate"

        // Default values
        // DEFAULT_RESOLUTION should be a key from STANDARD_RESOLUTIONS_MAP for consistency
        const val DEFAULT_RESOLUTION = "720p (1280x720)"
        const val DEFAULT_FORMAT = "video/avc" // H.264
        const val DEFAULT_QUALITY = "Medium"
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_AUDIO_BITRATE = 128000 // Store in BPS, was 128 (kbps)
    }

    fun saveResolution(resolution: String) {
        prefs.edit().putString(PREF_RESOLUTION, resolution).apply()
    }

    fun loadResolution(): String {
        return prefs.getString(PREF_RESOLUTION, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
    }

    fun saveFormat(format: String) {
        prefs.edit().putString(PREF_FORMAT, format).apply()
    }

    fun loadFormat(): String {
        return prefs.getString(PREF_FORMAT, DEFAULT_FORMAT) ?: DEFAULT_FORMAT
    }

    fun saveQuality(quality: String) {
        prefs.edit().putString(PREF_QUALITY, quality).apply()
    }

    fun loadQuality(): String {
        return prefs.getString(PREF_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
    }

    fun saveFrameRate(frameRate: Int) {
        prefs.edit().putInt(PREF_FRAME_RATE, frameRate).apply()
    }

    fun loadFrameRate(): Int {
        return prefs.getInt(PREF_FRAME_RATE, DEFAULT_FRAME_RATE)
    }

    fun saveAudioBitrate(audioBitrate: Int) {
        prefs.edit().putInt(PREF_AUDIO_BITRATE, audioBitrate).apply()
    }

    fun loadAudioBitrate(): Int {
        return prefs.getInt(PREF_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
    }
}
