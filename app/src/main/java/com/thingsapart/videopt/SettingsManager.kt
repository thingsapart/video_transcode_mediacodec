package com.thingsapart.videopt

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "video_transcoder_settings"
        const val PREF_RESOLUTION = "pref_resolution"
        const val PREF_FORMAT = "pref_format"
        const val PREF_QUALITY = "pref_quality"
        const val PREF_FRAME_RATE = "pref_frame_rate"
        const val PREF_AUDIO_BITRATE = "pref_audio_bitrate"

        // Default values
        const val DEFAULT_RESOLUTION = "720p"
        const val DEFAULT_FORMAT = "video/avc" // H.264
        const val DEFAULT_QUALITY = "Medium"
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_AUDIO_BITRATE = 128 // kbps
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
