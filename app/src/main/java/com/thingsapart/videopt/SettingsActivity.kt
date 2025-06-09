package com.thingsapart.videopt

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var settingsManager: SettingsManager

    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFormat: Spinner
    private lateinit var spinnerQuality: Spinner
    private lateinit var editTextFrameRate: EditText
    private lateinit var editTextAudioBitrate: EditText
    private lateinit var textViewEstimatedSize: TextView

    private var availableCodecs: List<MediaCodecInfo> = listOf()
    private var supportedMimeTypes: MutableList<String> = mutableListOf()
    private var supportedResolutions: MutableList<String> = mutableListOf("Original",
        SettingsManager.DEFAULT_RESOLUTION
    )
    private var supportedQualities: Array<String> = arrayOf("High", "Medium", "Low")
    private var supportedFrameRates: Range<Int>? = null // Store as Range<Int>
    private var supportedVideoBitrates: Range<Int>? = null // Store as Range<Int> bps
    private var supportedAudioBitrates: Range<Int>? = null // Store as Range<Int> bps


    companion object {
        private const val TAG = "SettingsActivity"
        val COMMON_RESOLUTIONS = mapOf(
            "1080p" to Pair(1920, 1080),
            "720p" to Pair(1280, 720),
            "480p" to Pair(854, 480),
            "360p" to Pair(640, 360)
        )
        // Base bitrates in BPS for "Medium" quality at 720p
        private const val BASE_BITRATE_MEDIUM_720P_AVC = 2_500_000 // 2.5 Mbps for H.264
        private const val BASE_BITRATE_MEDIUM_720P_HEVC = 1_800_000 // 1.8 Mbps for H.265 (more efficient)
        private const val BASE_BITRATE_MEDIUM_720P_VP9 = 2_000_000  // 2.0 Mbps for VP9
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.d(TAG, "onCreate: Inflating layout")

        settingsManager = SettingsManager(applicationContext)

        spinnerResolution = findViewById(R.id.spinner_resolution)
        spinnerFormat = findViewById(R.id.spinner_format)
        spinnerQuality = findViewById(R.id.spinner_quality)
        editTextFrameRate = findViewById(R.id.edittext_frame_rate)
        editTextAudioBitrate = findViewById(R.id.edittext_audio_bitrate)
        textViewEstimatedSize = findViewById(R.id.textview_estimated_size)

        queryCodecCapabilities()
        setupSpinners()
        loadSettings()
        setupListeners()
        Log.d(TAG, "onCreate: UI initialized and listeners set up")
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
        // Initial update based on default or first available format
        updateCapabilitiesForFormat(settingsManager.loadFormat().takeIf { supportedMimeTypes.contains(it) } ?: supportedMimeTypes.firstOrNull() ?: SettingsManager.DEFAULT_FORMAT)
    }


    private fun updateCapabilitiesForFormat(mimeType: String) {
        Log.d(TAG, "Updating capabilities for format: $mimeType")
        val currentSelectedResolution = if(spinnerResolution.selectedItem != null) spinnerResolution.selectedItem.toString() else settingsManager.loadResolution()

        supportedResolutions.clear()
        supportedResolutions.add("Original") // Keep original resolution option

        val selectedCodecInfo = availableCodecs.find { it.supportedTypes.contains(mimeType) }

        if (selectedCodecInfo == null) {
            Log.w(TAG, "No codec found for MIME type: $mimeType. Using broad fallback capabilities.")
            COMMON_RESOLUTIONS.keys.forEach { supportedResolutions.add(it) }
            supportedFrameRates = Range(15, 60) // Broad fallback
            supportedVideoBitrates = Range(250_000, 10_000_000) // Broad fallback
            supportedAudioBitrates = Range(32_000, 512_000) // Broad fallback
        } else {
            try {
                val capabilities = selectedCodecInfo.getCapabilitiesForType(mimeType)
                val videoCaps = capabilities.videoCapabilities
                if (videoCaps != null) {
                    Log.d(TAG, "Video Caps for ${selectedCodecInfo.name} ($mimeType): Bitrate:${videoCaps.bitrateRange}, FPS:${videoCaps.supportedFrameRates}, W:${videoCaps.supportedWidths}, H:${videoCaps.supportedHeights}")
                    supportedVideoBitrates = videoCaps.bitrateRange
                    supportedFrameRates = videoCaps.supportedFrameRates // This returns a Range<Int> of all rates, or specific ranges

                    COMMON_RESOLUTIONS.forEach { (name, size) ->
                        if (videoCaps.isSizeSupported(size.first, size.second)) {
                            if (!supportedResolutions.contains(name)) supportedResolutions.add(name)
                        }
                    }
                } else {
                    Log.w(TAG, "No video capabilities for $mimeType with ${selectedCodecInfo.name}. Using fallback resolutions.")
                    COMMON_RESOLUTIONS.keys.forEach { if (!supportedResolutions.contains(it)) supportedResolutions.add(it) }
                    supportedVideoBitrates = Range(500_000, 5_000_000) // Fallback
                    supportedFrameRates = Range(15, 30) // Fallback
                }

                val audioCaps = capabilities.audioCapabilities
                if (audioCaps != null) {
                    Log.d(TAG, "Audio Caps for ${selectedCodecInfo.name} ($mimeType): Bitrate:${audioCaps.bitrateRange}")
                    supportedAudioBitrates = audioCaps.bitrateRange
                } else {
                    Log.w(TAG, "No audio capabilities for this codec type. Using fallback audio bitrates.")
                    supportedAudioBitrates = Range(64_000, 320_000) // Fallback
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting capabilities for $mimeType on ${selectedCodecInfo.name}. Using broad fallbacks.", e)
                COMMON_RESOLUTIONS.keys.forEach { if (!supportedResolutions.contains(it)) supportedResolutions.add(it) }
                supportedFrameRates = Range(15, 60)
                supportedVideoBitrates = Range(250_000, 10_000_000)
                supportedAudioBitrates = Range(32_000, 512_000)
            }
        }
        if (supportedResolutions.size == 1 && supportedResolutions[0] == "Original") { // only "Original"
             Log.w(TAG, "No common resolutions supported by codec for $mimeType. Adding defaults.")
             COMMON_RESOLUTIONS.keys.forEach { key -> if(!supportedResolutions.contains(key)) supportedResolutions.add(key) }
        }
        Log.d(TAG, "Final supported resolutions for $mimeType: $supportedResolutions")

        // Refresh resolution spinner adapter as its content has changed
        val newResolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedResolutions.distinct().toTypedArray())
        newResolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = newResolutionAdapter
        val newResolutionPos = supportedResolutions.indexOf(currentSelectedResolution).coerceAtLeast(0)
        spinnerResolution.setSelection(newResolutionPos) // Try to keep previous selection
    }


    private fun setupSpinners() {
        // Formats - populated first
        val formatAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedMimeTypes.toTypedArray())
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFormat.adapter = formatAdapter

        // Resolutions - adapter will be set/updated in updateCapabilitiesForFormat and loadSettings
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedResolutions.distinct().toTypedArray())
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resolutionAdapter

        // Quality
        val qualityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedQualities)
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerQuality.adapter = qualityAdapter
        Log.d(TAG, "Spinners setup initially. Format items: ${supportedMimeTypes.size}, Resolution items: ${supportedResolutions.size}")
    }

    private fun loadSettings() {
        val loadedFormat = settingsManager.loadFormat()
        val formatPosition = supportedMimeTypes.indexOf(loadedFormat).coerceAtLeast(0)
        spinnerFormat.setSelection(formatPosition)
        // This will also update resolution spinner based on the loaded format
        updateCapabilitiesForFormat(supportedMimeTypes.getOrElse(formatPosition) { SettingsManager.DEFAULT_FORMAT })

        val loadedResolution = settingsManager.loadResolution()
        val resolutionPosition = supportedResolutions.indexOf(loadedResolution).coerceAtLeast(0)
        spinnerResolution.setSelection(resolutionPosition)

        val currentQuality = settingsManager.loadQuality()
        spinnerQuality.setSelection(supportedQualities.indexOf(currentQuality).coerceAtLeast(0))

        editTextFrameRate.setText(settingsManager.loadFrameRate().toString())
        editTextAudioBitrate.setText(settingsManager.loadAudioBitrate().toString())

        Log.d(TAG, "Settings loaded: Res=${spinnerResolution.selectedItem}, Format=${spinnerFormat.selectedItem}, Quality=${currentQuality}, FR=${settingsManager.loadFrameRate()}, AudioBR=${settingsManager.loadAudioBitrate()}")
        updateEstimatedSize()
    }

    private fun setupListeners() {
        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val distinctResolutions = supportedResolutions.distinct()
                if (position < distinctResolutions.size) {
                    settingsManager.saveResolution(distinctResolutions[position])
                    Log.d(TAG, "Resolution saved: ${distinctResolutions[position]}")
                    updateEstimatedSize()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedFormat = supportedMimeTypes[position]
                settingsManager.saveFormat(selectedFormat)
                Log.d(TAG, "Format selected: $selectedFormat. Updating capabilities...")
                updateCapabilitiesForFormat(selectedFormat) // This will refresh resolution spinner
                updateEstimatedSize()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                settingsManager.saveQuality(supportedQualities[position])
                Log.d(TAG, "Quality saved: ${supportedQualities[position]}")
                updateEstimatedSize()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        editTextFrameRate.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val frameRateStr = s.toString()
                if (frameRateStr.isNotEmpty()) {
                    val frameRate = frameRateStr.toIntOrNull() ?: settingsManager.loadFrameRate()
                    settingsManager.saveFrameRate(frameRate)
                    Log.d(TAG, "Frame rate saved: $frameRate")
                    updateEstimatedSize()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        editTextAudioBitrate.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                 val audioBitrateStr = s.toString()
                 if (audioBitrateStr.isNotEmpty()){
                    val audioBitrate = audioBitrateStr.toIntOrNull() ?: settingsManager.loadAudioBitrate()
                    settingsManager.saveAudioBitrate(audioBitrate)
                    Log.d(TAG, "Audio bitrate saved: $audioBitrate")
                    updateEstimatedSize()
                 }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        Log.d(TAG, "Listeners setup complete")
    }

    private fun getEstimatedVideoBitrate(resolution: String, quality: String, mimeType: String): Int {
        val targetPixels = COMMON_RESOLUTIONS[resolution]?.let { it.first * it.second } ?: (COMMON_RESOLUTIONS[SettingsManager.DEFAULT_RESOLUTION]?.let { it.first * it.second } ?: (1280*720))
        val base720pPixels = 1280 * 720

        var baseBitrate = when (mimeType) {
            "video/hevc" -> BASE_BITRATE_MEDIUM_720P_HEVC // H.265
            "video/vp9" -> BASE_BITRATE_MEDIUM_720P_VP9
            else -> BASE_BITRATE_MEDIUM_720P_AVC // Default to AVC H.264
        }

        // Adjust base bitrate for quality setting
        baseBitrate = when (quality) {
            "High" -> (baseBitrate * 1.8).toInt()
            "Low" -> (baseBitrate * 0.5).toInt()
            else -> baseBitrate // Medium
        }

        // Scale bitrate by resolution (proportional to pixel count relative to 720p)
        var estimatedBitrate = (baseBitrate * (targetPixels.toDouble() / base720pPixels)).toInt()

        // Clamp to supported range or a reasonable default range if not available
        val videoBitrateRange = supportedVideoBitrates ?: Range(250_000, 10_000_000) // Default range if null
        estimatedBitrate = estimatedBitrate.coerceIn(videoBitrateRange.lower, videoBitrateRange.upper)

        Log.d(TAG, "Estimated video bitrate for $resolution, $quality, $mimeType: $estimatedBitrate bps")
        return estimatedBitrate
    }


    private fun updateEstimatedSize() {
        val resolution = if (spinnerResolution.selectedItemPosition != -1) spinnerResolution.selectedItem.toString() else settingsManager.loadResolution()
        val format = if (spinnerFormat.selectedItemPosition != -1) spinnerFormat.selectedItem.toString() else settingsManager.loadFormat()
        val quality = if (spinnerQuality.selectedItemPosition != -1) spinnerQuality.selectedItem.toString() else settingsManager.loadQuality()

        val frameRateStr = editTextFrameRate.text.toString()
        val audioBitrateStr = editTextAudioBitrate.text.toString()

        // Ensure robust parsing with defaults if text is empty or invalid
        val frameRate = if (frameRateStr.isNotEmpty()) frameRateStr.toIntOrNull() ?: settingsManager.loadFrameRate() else settingsManager.loadFrameRate()
        val audioBitrateKbps = if (audioBitrateStr.isNotEmpty()) audioBitrateStr.toIntOrNull() ?: settingsManager.loadAudioBitrate() else settingsManager.loadAudioBitrate()


        if (resolution == "Original") {
            textViewEstimatedSize.text = "Estimated size: N/A (Original resolution)"
            Log.d(TAG, "Estimation N/A for 'Original' resolution.")
            return
        }

        val videoBitrateBps = getEstimatedVideoBitrate(resolution, quality, format)
        val audioBitrateBps = audioBitrateKbps * 1000 // Convert kbps to bps

        val totalBitrateBps = videoBitrateBps + audioBitrateBps
        val bytesPerSecond = totalBitrateBps / 8.0
        val bytesPerMinute = bytesPerSecond * 60

        val formattedSize = when {
            bytesPerMinute >= 1_000_000 -> String.format(Locale.US, "%.1f MB/min", bytesPerMinute / 1_000_000.0)
            bytesPerMinute >= 1_000 -> String.format(Locale.US, "%.0f KB/min", bytesPerMinute / 1_000.0)
            else -> String.format(Locale.US, "%.0f B/min", bytesPerMinute)
        }

        textViewEstimatedSize.text = "Estimated size: ~${formattedSize}"
        Log.d(TAG, "Updated estimated size: $formattedSize for Res:$resolution, Format:$format, Quality:$quality, FR:$frameRate, AudioBR:$audioBitrateKbps kbps")
    }
}
