package com.thingsapart.videopt

import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.Range
import androidx.preference.*
import java.util.*

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settingsManager: SettingsManager

    private var availableCodecs: List<MediaCodecInfo> = listOf()
    private var supportedMimeTypes: MutableList<String> = mutableListOf()
    private var supportedResolutions: MutableList<String> = mutableListOf("Original", SettingsManager.DEFAULT_RESOLUTION)
    private var supportedFrameRates: Range<Int>? = null
    private var supportedVideoBitrates: Range<Int>? = null
    private var supportedAudioBitrates: Range<Int>? = null

    private lateinit var formatPreference: ListPreference
    private lateinit var resolutionPreference: ListPreference
    private lateinit var qualityPreference: ListPreference
    private lateinit var frameRatePreference: EditTextPreference
    private lateinit var audioBitratePreference: EditTextPreference
    private lateinit var estimatedSizePreference: Preference

    companion object {
        private const val TAG = "SettingsFragment"
        val COMMON_RESOLUTIONS = mapOf(
            "1080p" to Pair(1920, 1080),
            "720p" to Pair(1280, 720),
            "480p" to Pair(854, 480),
            "360p" to Pair(640, 360)
        )
        private const val BASE_BITRATE_MEDIUM_720P_AVC = 2_500_000 // 2.5 Mbps for H.264
        private const val BASE_BITRATE_MEDIUM_720P_HEVC = 1_800_000 // 1.8 Mbps for H.265
        private const val BASE_BITRATE_MEDIUM_720P_VP9 = 2_000_000  // 2.0 Mbps for VP9
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        settingsManager = SettingsManager(requireContext())

        // Use a PreferenceDataStore to link preferences directly to SettingsManager
        preferenceManager.preferenceDataStore = AppPreferenceDataStore(settingsManager)

        formatPreference = findPreference(SettingsManager.PREF_FORMAT)!!
        resolutionPreference = findPreference(SettingsManager.PREF_RESOLUTION)!!
        qualityPreference = findPreference(SettingsManager.PREF_QUALITY)!!
        frameRatePreference = findPreference(SettingsManager.PREF_FRAME_RATE)!!
        audioBitratePreference = findPreference(SettingsManager.PREF_AUDIO_BITRATE)!!
        estimatedSizePreference = findPreference("pref_estimated_size")!!

        // Set default values if not already set by DataStore
        // Note: Default values in XML are used by Preference system if DataStore returns null initially.
        // However, explicitly setting them here ensures consistency if values are not in SharedPreferences.
        if (settingsManager.loadFormat().isBlank()) {
            settingsManager.saveFormat(SettingsManager.DEFAULT_FORMAT)
        }
        if (settingsManager.loadResolution().isBlank()) {
            settingsManager.saveResolution(SettingsManager.DEFAULT_RESOLUTION)
        }
         if (settingsManager.loadQuality().isBlank()) {
            settingsManager.saveQuality(SettingsManager.DEFAULT_QUALITY)
        }
        // For EditTextPreferences, default values are typically handled by the XML or system.
        // No need to explicitly save default for frame rate/audio bitrate unless logic requires it.


        queryCodecCapabilities() // Populates supportedMimeTypes
        updateFormatPreferenceEntries() // Sets up formatPreference

        // Load initial format and update dependent capabilities
        val initialFormat = settingsManager.loadFormat()
        formatPreference.value = initialFormat
        updateCapabilitiesForFormat(initialFormat) // Populates supportedResolutions and updates resolutionPreference

        // Set initial value for resolution
        resolutionPreference.value = settingsManager.loadResolution()

        // Set initial values for EditTextPreferences (they use SimpleSummaryProvider which reflects saved value)
        frameRatePreference.text = settingsManager.loadFrameRate().toString()
        audioBitratePreference.text = settingsManager.loadAudioBitrate().toString()

        updateEstimatedSize()
    }

    private fun queryCodecCapabilities() {
        Log.d(TAG, "Querying codec capabilities...")
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        availableCodecs = codecList.codecInfos.filter { it.isEncoder }

        supportedMimeTypes.clear()
        availableCodecs.forEach { codecInfo ->
            codecInfo.supportedTypes.forEach { type ->
                if (type.startsWith("video/") && !supportedMimeTypes.contains(type)) {
                    supportedMimeTypes.add(type)
                }
            }
        }
        if (supportedMimeTypes.isEmpty()) {
            Log.w(TAG, "No video encoders found! Adding default: ${SettingsManager.DEFAULT_FORMAT}")
            supportedMimeTypes.add(SettingsManager.DEFAULT_FORMAT)
        }
        Log.d(TAG, "Supported MIME types: $supportedMimeTypes")
    }

    private fun updateFormatPreferenceEntries() {
        formatPreference.entries = supportedMimeTypes.toTypedArray()
        formatPreference.entryValues = supportedMimeTypes.toTypedArray()
        // Value is set after entries/entryValues
        val currentFormat = settingsManager.loadFormat()
        if (supportedMimeTypes.contains(currentFormat)) {
            formatPreference.value = currentFormat
        } else if (supportedMimeTypes.isNotEmpty()) {
            formatPreference.value = supportedMimeTypes.first()
            settingsManager.saveFormat(supportedMimeTypes.first()) // Save if changed
        }
    }

    private fun updateCapabilitiesForFormat(mimeType: String) {
        Log.d(TAG, "Updating capabilities for format: $mimeType")
        val currentSelectedResolution = settingsManager.loadResolution()

        supportedResolutions.clear()
        supportedResolutions.add("Original")

        val selectedCodecInfo = availableCodecs.find { it.supportedTypes.contains(mimeType) }

        if (selectedCodecInfo == null) {
            Log.w(TAG, "No codec found for MIME type: $mimeType. Using broad fallback capabilities.")
            COMMON_RESOLUTIONS.keys.forEach { supportedResolutions.add(it) }
            supportedFrameRates = Range(15, 60)
            supportedVideoBitrates = Range(250_000, 10_000_000)
            supportedAudioBitrates = Range(32_000, 512_000)
        } else {
            try {
                val capabilities = selectedCodecInfo.getCapabilitiesForType(mimeType)
                val videoCaps = capabilities.videoCapabilities
                if (videoCaps != null) {
                    supportedVideoBitrates = videoCaps.bitrateRange
                    supportedFrameRates = videoCaps.supportedFrameRates
                    COMMON_RESOLUTIONS.forEach { (name, size) ->
                        if (videoCaps.isSizeSupported(size.first, size.second)) {
                            if (!supportedResolutions.contains(name)) supportedResolutions.add(name)
                        }
                    }
                } else {
                    COMMON_RESOLUTIONS.keys.forEach { if (!supportedResolutions.contains(it)) supportedResolutions.add(it) }
                    supportedVideoBitrates = Range(500_000, 5_000_000)
                    supportedFrameRates = Range(15, 30)
                }
                val audioCaps = capabilities.audioCapabilities
                supportedAudioBitrates = audioCaps?.bitrateRange ?: Range(64_000, 320_000)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting capabilities for $mimeType. Using fallbacks.", e)
                COMMON_RESOLUTIONS.keys.forEach { if (!supportedResolutions.contains(it)) supportedResolutions.add(it) }
                supportedFrameRates = Range(15, 60)
                supportedVideoBitrates = Range(250_000, 10_000_000)
                supportedAudioBitrates = Range(32_000, 512_000)
            }
        }
        if (supportedResolutions.size == 1 && supportedResolutions[0] == "Original") {
             COMMON_RESOLUTIONS.keys.forEach { key -> if(!supportedResolutions.contains(key)) supportedResolutions.add(key) }
        }
        Log.d(TAG, "Final supported resolutions for $mimeType: $supportedResolutions")

        val distinctResolutions = supportedResolutions.distinct()
        resolutionPreference.entries = distinctResolutions.toTypedArray()
        resolutionPreference.entryValues = distinctResolutions.toTypedArray()

        if (distinctResolutions.contains(currentSelectedResolution)) {
            resolutionPreference.value = currentSelectedResolution
        } else if (distinctResolutions.isNotEmpty()) {
            resolutionPreference.value = distinctResolutions.first() // Default to first if previous not available
            settingsManager.saveResolution(distinctResolutions.first()) // Save this change
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "onSharedPreferenceChanged: key=$key")
        when (key) {
            SettingsManager.PREF_FORMAT -> {
                val newFormat = settingsManager.loadFormat()
                Log.d(TAG, "Format changed to: $newFormat. Updating capabilities.")
                updateCapabilitiesForFormat(newFormat)
                // No need to call updateEstimatedSize here if PREF_RESOLUTION will also change
            }
            SettingsManager.PREF_RESOLUTION,
            SettingsManager.PREF_QUALITY,
            SettingsManager.PREF_FRAME_RATE,
            SettingsManager.PREF_AUDIO_BITRATE -> {
                updateEstimatedSize()
            }
        }
    }

    private fun getEstimatedVideoBitrate(resolution: String, quality: String, mimeType: String): Int {
        val targetPixels = COMMON_RESOLUTIONS[resolution]?.let { it.first * it.second } ?: (COMMON_RESOLUTIONS[SettingsManager.DEFAULT_RESOLUTION]?.let { it.first * it.second } ?: (1280*720))
        val base720pPixels = 1280 * 720

        var baseBitrate = when (mimeType) {
            "video/hevc" -> BASE_BITRATE_MEDIUM_720P_HEVC
            "video/vp9" -> BASE_BITRATE_MEDIUM_720P_VP9
            else -> BASE_BITRATE_MEDIUM_720P_AVC
        }
        baseBitrate = when (quality) {
            "High" -> (baseBitrate * 1.8).toInt()
            "Low" -> (baseBitrate * 0.5).toInt()
            else -> baseBitrate
        }
        var estimatedBitrate = (baseBitrate * (targetPixels.toDouble() / base720pPixels)).toInt()
        val videoBitrateRange = supportedVideoBitrates ?: Range(250_000, 10_000_000)
        estimatedBitrate = estimatedBitrate.coerceIn(videoBitrateRange.lower, videoBitrateRange.upper)
        Log.d(TAG, "Estimated video bitrate for $resolution, $quality, $mimeType: $estimatedBitrate bps")
        return estimatedBitrate
    }

    private fun updateEstimatedSize() {
        val resolution = settingsManager.loadResolution()
        val format = settingsManager.loadFormat()
        val quality = settingsManager.loadQuality()
        val frameRate = settingsManager.loadFrameRate()
        val audioBitrateKbps = settingsManager.loadAudioBitrate()

        if (resolution == "Original") {
            estimatedSizePreference.summary = "N/A (Original resolution)"
            return
        }

        val videoBitrateBps = getEstimatedVideoBitrate(resolution, quality, format)
        val audioBitrateBps = audioBitrateKbps * 1000
        val totalBitrateBps = videoBitrateBps + audioBitrateBps
        val bytesPerSecond = totalBitrateBps / 8.0
        val bytesPerMinute = bytesPerSecond * 60

        val formattedSize = when {
            bytesPerMinute >= 1_000_000 -> String.format(Locale.US, "%.1f MB/min", bytesPerMinute / 1_000_000.0)
            bytesPerMinute >= 1_000 -> String.format(Locale.US, "%.0f KB/min", bytesPerMinute / 1_000.0)
            else -> String.format(Locale.US, "%.0f B/min", bytesPerMinute)
        }
        estimatedSizePreference.summary = "~$formattedSize"
        Log.d(TAG, "Updated estimated size: ~$formattedSize")
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        // Refresh dynamic preferences in case capabilities changed or settings were altered elsewhere
        queryCodecCapabilities()
        updateFormatPreferenceEntries()
        val currentFormat = settingsManager.loadFormat()
        formatPreference.value = currentFormat // Ensure this is set before updating dependent caps
        updateCapabilitiesForFormat(currentFormat)
        resolutionPreference.value = settingsManager.loadResolution() // Ensure this is set
        updateEstimatedSize()
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }
}

// Custom PreferenceDataStore to use SettingsManager
class AppPreferenceDataStore(private val settingsManager: SettingsManager) : PreferenceDataStore() {
    override fun putString(key: String?, value: String?) {
        if (value == null) return
        when (key) {
            SettingsManager.PREF_RESOLUTION -> settingsManager.saveResolution(value)
            SettingsManager.PREF_FORMAT -> settingsManager.saveFormat(value)
            SettingsManager.PREF_QUALITY -> settingsManager.saveQuality(value)
            // EditTextPreferences for numbers are handled as strings by default by Preference library
            // but our settings manager expects Int. We will handle conversion in getString.
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        return when (key) {
            SettingsManager.PREF_RESOLUTION -> settingsManager.loadResolution()
            SettingsManager.PREF_FORMAT -> settingsManager.loadFormat()
            SettingsManager.PREF_QUALITY -> settingsManager.loadQuality()
            SettingsManager.PREF_FRAME_RATE -> settingsManager.loadFrameRate().toString()
            SettingsManager.PREF_AUDIO_BITRATE -> settingsManager.loadAudioBitrate().toString()
            else -> defValue
        }
    }

    override fun putInt(key: String?, value: Int) {
         when (key) {
            SettingsManager.PREF_FRAME_RATE -> settingsManager.saveFrameRate(value)
            SettingsManager.PREF_AUDIO_BITRATE -> settingsManager.saveAudioBitrate(value)
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return when (key) {
            SettingsManager.PREF_FRAME_RATE -> settingsManager.loadFrameRate()
            SettingsManager.PREF_AUDIO_BITRATE -> settingsManager.loadAudioBitrate()
            else -> defValue
        }
    }
    // Implement other put/get methods (Boolean, Float, Long, Set<String>) if needed,
    // otherwise they default to not handling the type.
}
