package com.thingsapart.videopt

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.Range
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class SettingsActivity : Activity() {

    private lateinit var settingsManager: SettingsManager

    // Resolution
    private lateinit var spinnerResolution: AutoCompleteTextView
    private lateinit var resolutionAdapter: ArrayAdapter<String>
    private lateinit var btnAddCustomResolution: ImageButton
    private var currentResolutionDisplayNames: MutableList<String> = mutableListOf()

    // Format
    private lateinit var spinnerFormat: AutoCompleteTextView
    private lateinit var formatAdapter: ArrayAdapter<String>
    private var supportedMimeTypesWithFriendlyNames = mutableListOf<Pair<String, String>>()

    // Quality
    private lateinit var spinnerQuality: AutoCompleteTextView
    private var supportedQualities: Array<String> = arrayOf("High", "Medium", "Low")

    // Frame Rate
    private lateinit var spinnerFrameRate: AutoCompleteTextView
    private lateinit var frameRateAdapter: ArrayAdapter<String>
    private lateinit var btnAddCustomFrameRate: ImageButton
    private var currentFrameRateDisplayNames: MutableList<String> = mutableListOf()

    // Audio Bitrate
    private lateinit var spinnerAudioBitrate: AutoCompleteTextView
    private lateinit var audioBitrateAdapter: ArrayAdapter<String>
    private val commonAudioBitrates = linkedMapOf(
        "96 kbps" to 96000,
        "128 kbps" to 128000,
        "192 kbps" to 192000,
        "256 kbps" to 256000,
        "320 kbps" to 320000
    )

    // Other UI
    private lateinit var textViewEstimatedSize: TextView

    // Codec Info
    private var availableCodecs: List<MediaCodecInfo> = listOf()
    private var supportedFrameRatesFromCodec: Range<Int>? = null
    private var supportedVideoBitrates: Range<Int>? = null
    private var supportedAudioBitratesFromCodec: Range<Int>? = null

    companion object {
        private const val TAG = "SettingsActivity"
        // STANDARD_RESOLUTIONS_MAP moved to SettingsManager
        val COMMON_FRAME_RATES = listOf("24", "25", "30", "48", "50", "60")

        private const val BASE_BITRATE_MEDIUM_720P_AVC = 2_500_000
        private const val BASE_BITRATE_MEDIUM_720P_HEVC = 1_800_000
        private const val BASE_BITRATE_MEDIUM_720P_VP9 = 2_000_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.d(TAG, "onCreate: Inflating layout")

        settingsManager = SettingsManager(applicationContext)

        spinnerResolution = findViewById(R.id.spinner_resolution)
        btnAddCustomResolution = findViewById(R.id.btn_add_custom_resolution)
        spinnerFormat = findViewById(R.id.spinner_format)
        spinnerQuality = findViewById(R.id.spinner_quality)
        spinnerFrameRate = findViewById(R.id.spinner_frame_rate)
        btnAddCustomFrameRate = findViewById(R.id.btn_add_custom_frame_rate)
        spinnerAudioBitrate = findViewById(R.id.spinner_audio_bitrate)
        textViewEstimatedSize = findViewById(R.id.textview_estimated_size)

        // Corrected initialization order:
        // 1. Setup spinners with adapters and initial default lists
        setupSpinners()
        // 2. Query codec capabilities, which will call updateCapabilitiesForFormat.
        //    updateCapabilitiesForFormat will now safely use the initialized adapters.
        queryCodecCapabilities()
        // 3. Load settings, which might trigger listeners or update text,
        //    potentially calling updateCapabilitiesForFormat again.
        loadSettings()
        // 4. Setup remaining listeners
        setupListeners()
        Log.d(TAG, "onCreate: UI initialized and listeners set up with corrected order.")
    }

    private fun getFormatDisplayName(mimeType: String): String {
        return when (mimeType.lowercase(Locale.US)) {
            "video/avc" -> "H.264 (AVC) - $mimeType"
            "video/hevc" -> "H.265 (HEVC) - $mimeType"
            "video/x-vnd.on2.vp9" -> "VP9 - $mimeType"
            "video/av01" -> "AV1 - $mimeType"
            else -> mimeType
        }
    }

    private fun queryCodecCapabilities() {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        availableCodecs = codecList.codecInfos.filter { it.isEncoder }
        val rawMimeTypes = mutableSetOf<String>()
        availableCodecs.forEach { codecInfo ->
            codecInfo.supportedTypes.forEach { type ->
                if (type.startsWith("video/")) rawMimeTypes.add(type)
            }
        }
        if (rawMimeTypes.isEmpty()) rawMimeTypes.add(SettingsManager.DEFAULT_FORMAT)

        supportedMimeTypesWithFriendlyNames.clear()
        rawMimeTypes.forEach { mime ->
            supportedMimeTypesWithFriendlyNames.add(Pair(getFormatDisplayName(mime), mime))
        }
        val initialFormatMime = settingsManager.loadFormat().takeIf { mime -> rawMimeTypes.contains(mime) } ?: rawMimeTypes.firstOrNull() ?: SettingsManager.DEFAULT_FORMAT
        updateCapabilitiesForFormat(initialFormatMime)
    }

    private fun updateCapabilitiesForFormat(mimeType: String) {
        Log.d(TAG, "Updating capabilities for format: $mimeType")
        val currentSelectedResolutionDisplayName = spinnerResolution.text.toString().ifEmpty { settingsManager.loadResolution() }
        val currentSelectedFrameRate = spinnerFrameRate.text.toString().ifEmpty { settingsManager.loadFrameRate().toString() }

        currentResolutionDisplayNames.clear()
        currentResolutionDisplayNames.add("Original")

        val selectedCodecInfo = availableCodecs.find { it.supportedTypes.contains(mimeType) }

        if (selectedCodecInfo == null) {
            settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == "Original" }.forEach { currentResolutionDisplayNames.add(it) }
            supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
        } else {
            try {
                val capabilities = selectedCodecInfo.getCapabilitiesForType(mimeType)
                val videoCaps = capabilities.videoCapabilities
                if (videoCaps != null) {
                    supportedVideoBitrates = videoCaps.bitrateRange; supportedFrameRatesFromCodec = videoCaps.supportedFrameRates
                    settingsManager.STANDARD_RESOLUTIONS_MAP.forEach { (displayName, value) ->
                        if (value != "Original") settingsManager.parseResolutionValue(value)?.let { dims -> // Use SettingsManager.parseResolutionValue
                            if (videoCaps.isSizeSupported(dims.first, dims.second) && !currentResolutionDisplayNames.contains(displayName)) {
                                currentResolutionDisplayNames.add(displayName)
                            }
                        }
                    }
                } else {
                    settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == "Original" }.forEach { currentResolutionDisplayNames.add(it) }
                    supportedVideoBitrates = Range(500_000, 5_000_000); supportedFrameRatesFromCodec = Range(15, 30)
                }
                val audioCaps = capabilities.audioCapabilities
                if (audioCaps != null) supportedAudioBitratesFromCodec = audioCaps.bitrateRange else supportedAudioBitratesFromCodec = Range(64_000, 320_000)
            } catch (e: Exception) {
                settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == "Original" }.forEach { currentResolutionDisplayNames.add(it) }
                supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
            }
        }
        if (currentResolutionDisplayNames.size == 1 && currentResolutionDisplayNames[0] == "Original") {
             settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == "Original" || currentResolutionDisplayNames.contains(it) }.forEach { currentResolutionDisplayNames.add(it) }
        }

        val previouslySelectedIsCustomRes = !settingsManager.STANDARD_RESOLUTIONS_MAP.containsKey(currentSelectedResolutionDisplayName) && currentSelectedResolutionDisplayName.startsWith("Custom")
        if (previouslySelectedIsCustomRes && !currentResolutionDisplayNames.contains(currentSelectedResolutionDisplayName)) {
            currentResolutionDisplayNames.add(currentSelectedResolutionDisplayName)
        }

        resolutionAdapter.clear()
        // Use SettingsManager.parseResolutionValue for sorting key
        resolutionAdapter.addAll(currentResolutionDisplayNames.distinct().sortedWith(compareBy { if (it == "Original") "0" else settingsManager.parseResolutionValue(it)?.let { dim -> String.format("%05dx%05d", dim.first, dim.second) } ?: it }))
        resolutionAdapter.notifyDataSetChanged()

        if (currentResolutionDisplayNames.contains(currentSelectedResolutionDisplayName)) spinnerResolution.setText(currentSelectedResolutionDisplayName, false)
        else if (currentResolutionDisplayNames.isNotEmpty()) spinnerResolution.setText(currentResolutionDisplayNames[0], false)
        else spinnerResolution.setText("", false)

        if (currentFrameRateDisplayNames.contains(currentSelectedFrameRate)) spinnerFrameRate.setText(currentSelectedFrameRate, false)
        else if (currentFrameRateDisplayNames.isNotEmpty()) spinnerFrameRate.setText(currentFrameRateDisplayNames.find { it == settingsManager.loadFrameRate().toString() } ?: COMMON_FRAME_RATES.first(), false)
    }

    private fun setupSpinners() {
        val formatDisplayNames = supportedMimeTypesWithFriendlyNames.map { it.first }
        formatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, formatDisplayNames)
        spinnerFormat.setAdapter(formatAdapter)

        currentResolutionDisplayNames.clear(); currentResolutionDisplayNames.add("Original")
        settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == "Original" }.forEach { currentResolutionDisplayNames.add(it) } // Use SettingsManager.STANDARD_RESOLUTIONS_MAP
        resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, currentResolutionDisplayNames.distinct().toMutableList())
        spinnerResolution.setAdapter(resolutionAdapter)

        val qualityAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, supportedQualities)
        spinnerQuality.setAdapter(qualityAdapter)

        currentFrameRateDisplayNames.clear(); currentFrameRateDisplayNames.addAll(COMMON_FRAME_RATES)
        val savedFps = settingsManager.loadFrameRate().toString()
        if (!currentFrameRateDisplayNames.contains(savedFps)) currentFrameRateDisplayNames.add(savedFps)
        currentFrameRateDisplayNames.sortWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
        frameRateAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, currentFrameRateDisplayNames)
        spinnerFrameRate.setAdapter(frameRateAdapter)

        val audioBitrateDisplayNames = commonAudioBitrates.keys.toList()
        audioBitrateAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, audioBitrateDisplayNames)
        spinnerAudioBitrate.setAdapter(audioBitrateAdapter)
    }

    private fun loadSettings() {
        val loadedFormatMime = settingsManager.loadFormat()
        val loadedFormatDisplayName = supportedMimeTypesWithFriendlyNames.find { it.second == loadedFormatMime }?.first ?: getFormatDisplayName(loadedFormatMime)
        spinnerFormat.setText(loadedFormatDisplayName, false)
        updateCapabilitiesForFormat(loadedFormatMime)

        val loadedResolutionDisplayName = settingsManager.loadResolution()
        // Use SettingsManager.parseResolutionValue
        if (!currentResolutionDisplayNames.contains(loadedResolutionDisplayName) && (loadedResolutionDisplayName.startsWith("Custom (") || loadedResolutionDisplayName.contains("x"))) {
            val customDisplayName = if (loadedResolutionDisplayName.startsWith("Custom (")) loadedResolutionDisplayName
            else settingsManager.parseResolutionValue(loadedResolutionDisplayName)?.let { getString(R.string.custom_resolution_format, it.first, it.second) } ?: loadedResolutionDisplayName
            if(!currentResolutionDisplayNames.contains(customDisplayName)){
                resolutionAdapter.add(customDisplayName); resolutionAdapter.notifyDataSetChanged()
            }
            spinnerResolution.setText(customDisplayName, false)
        } else {
             if (currentResolutionDisplayNames.contains(loadedResolutionDisplayName)) spinnerResolution.setText(loadedResolutionDisplayName, false)
             else if (currentResolutionDisplayNames.isNotEmpty()) spinnerResolution.setText(currentResolutionDisplayNames[0], false)
        }

        val loadedFrameRate = settingsManager.loadFrameRate().toString()
        if (!currentFrameRateDisplayNames.contains(loadedFrameRate)) {
            currentFrameRateDisplayNames.add(loadedFrameRate)
            currentFrameRateDisplayNames.sortWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
            frameRateAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, currentFrameRateDisplayNames)
            spinnerFrameRate.setAdapter(frameRateAdapter)
        }
        spinnerFrameRate.setText(loadedFrameRate, false)

        val savedAudioBitrateBps = settingsManager.loadAudioBitrate()
        val savedAudioBitrateDisplayKey = commonAudioBitrates.entries.find { it.value == savedAudioBitrateBps }?.key
                                        ?: commonAudioBitrates.keys.firstOrNull()
        spinnerAudioBitrate.setText(savedAudioBitrateDisplayKey, false)

        spinnerQuality.setText(settingsManager.loadQuality(), false)
        updateEstimatedSize()
    }

    private fun setupListeners() {
        btnAddCustomResolution.setOnClickListener { showCustomResolutionDialog() }
        btnAddCustomFrameRate.setOnClickListener { showCustomFrameRateDialog() }

        spinnerResolution.setOnItemClickListener { _, _, position, _ ->
            settingsManager.saveResolution(resolutionAdapter.getItem(position) ?: "Original"); updateEstimatedSize()
        }
        spinnerFormat.setOnItemClickListener { _, _, position, _ ->
            val selectedFormatMime = supportedMimeTypesWithFriendlyNames[position].second
            settingsManager.saveFormat(selectedFormatMime); updateCapabilitiesForFormat(selectedFormatMime); updateEstimatedSize()
        }
        spinnerQuality.setOnItemClickListener { _, _, position, _ ->
            settingsManager.saveQuality(supportedQualities[position]); updateEstimatedSize()
        }
        spinnerFrameRate.setOnItemClickListener { _, _, position, _ ->
            val selectedFpsStr = frameRateAdapter.getItem(position) ?: COMMON_FRAME_RATES.first()
            settingsManager.saveFrameRate(selectedFpsStr.toIntOrNull() ?: settingsManager.loadFrameRate()); updateEstimatedSize()
        }
        spinnerAudioBitrate.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplayName = audioBitrateAdapter.getItem(position)
            val selectedBitrateBps = commonAudioBitrates[selectedDisplayName] ?: commonAudioBitrates.values.first()
            settingsManager.saveAudioBitrate(selectedBitrateBps); updateEstimatedSize()
        }
    }

    private fun showCustomResolutionDialog() {
        val dialogView = LinearLayout(this); dialogView.orientation = LinearLayout.VERTICAL; dialogView.setPadding(48,24,48,24)
        val widthInputLayout = TextInputLayout(this); val widthEditText = TextInputEditText(this)
        widthEditText.hint = getString(R.string.dialog_label_width); widthEditText.inputType = InputType.TYPE_CLASS_NUMBER
        widthInputLayout.addView(widthEditText); dialogView.addView(widthInputLayout)
        val heightInputLayout = TextInputLayout(this); val heightEditText = TextInputEditText(this)
        heightEditText.hint = getString(R.string.dialog_label_height); heightEditText.inputType = InputType.TYPE_CLASS_NUMBER
        heightInputLayout.addView(heightEditText); dialogView.addView(heightInputLayout)

        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_custom_resolution)).setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val width = widthEditText.text.toString().toIntOrNull(); val height = heightEditText.text.toString().toIntOrNull()
                if (width != null && height != null && width > 0 && height > 0) {
                    val customResDisplayName = getString(R.string.custom_resolution_format, width, height)
                    if (!currentResolutionDisplayNames.contains(customResDisplayName)) {
                        resolutionAdapter.add(customResDisplayName);
                        currentResolutionDisplayNames.add(customResDisplayName)
                        // Use SettingsManager.parseResolutionValue for sorting key
                        currentResolutionDisplayNames.sortWith(compareBy { if (it == "Original") "0" else settingsManager.parseResolutionValue(it)?.let { dim -> String.format("%05dx%05d", dim.first, dim.second) } ?: it })
                        resolutionAdapter.clear()
                        resolutionAdapter.addAll(currentResolutionDisplayNames)
                        resolutionAdapter.notifyDataSetChanged()
                    }
                    spinnerResolution.setText(customResDisplayName, false)
                    settingsManager.saveResolution(customResDisplayName); updateEstimatedSize()
                    dialog.dismiss()
                } else { Toast.makeText(this, getString(R.string.error_invalid_resolution_value), Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showCustomFrameRateDialog() {
        val dialogView = LinearLayout(this); dialogView.orientation = LinearLayout.VERTICAL; dialogView.setPadding(48,24,48,24)
        val frInputLayout = TextInputLayout(this); val frEditText = TextInputEditText(this)
        frEditText.hint = getString(R.string.dialog_label_frame_rate); frEditText.inputType = InputType.TYPE_CLASS_NUMBER
        frInputLayout.addView(frEditText); dialogView.addView(frInputLayout)

        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_custom_frame_rate)).setView(dialogView)
            .setPositiveButton(android.R.string.ok) {dialog, _ ->
                val fpsStr = frEditText.text.toString(); val fps = fpsStr.toIntOrNull()
                if (fps != null && fps > 0) {
                    if(!currentFrameRateDisplayNames.contains(fpsStr)){
                        currentFrameRateDisplayNames.add(fpsStr)
                        currentFrameRateDisplayNames.sortWith(compareBy{it.toIntOrNull() ?: Int.MAX_VALUE})
                        frameRateAdapter.clear(); frameRateAdapter.addAll(currentFrameRateDisplayNames); frameRateAdapter.notifyDataSetChanged()
                    }
                    spinnerFrameRate.setText(fpsStr, false)
                    settingsManager.saveFrameRate(fps); updateEstimatedSize()
                    dialog.dismiss()
                } else { Toast.makeText(this, getString(R.string.error_invalid_frame_rate_value), Toast.LENGTH_SHORT).show()}
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // parseResolutionValue was moved to SettingsManager

    private fun getEstimatedVideoBitrate(resDspName: String, quality: String, mimeType: String): Int {
        // Use SettingsManager.STANDARD_RESOLUTIONS_MAP and SettingsManager.parseResolutionValue
        val defaultResKey = settingsManager.STANDARD_RESOLUTIONS_MAP.entries.find { it.key.contains("720p") }?.key ?: settingsManager.STANDARD_RESOLUTIONS_MAP.keys.first()
        val dims = settingsManager.parseResolutionValue(resDspName) ?: settingsManager.parseResolutionValue(defaultResKey)!!

        val targetPx = dims.first * dims.second; val base720pPx = 1280 * 720
        var baseBitrate = when (mimeType) { "video/hevc" -> BASE_BITRATE_MEDIUM_720P_HEVC; "video/x-vnd.on2.vp9" -> BASE_BITRATE_MEDIUM_720P_VP9; else -> BASE_BITRATE_MEDIUM_720P_AVC }
        baseBitrate = when (quality) { "High" -> (baseBitrate * 1.8); "Low" -> (baseBitrate * 0.5); else -> baseBitrate }.toInt()
        var estBitrate = (baseBitrate * (targetPx.toDouble() / base720pPx)).toInt()
        val range = supportedVideoBitrates ?: Range(250_000, 10_000_000)
        return estBitrate.coerceIn(range.lower, range.upper)
    }

    private fun updateEstimatedSize() {
        val resDspName = spinnerResolution.text.toString().ifEmpty { settingsManager.loadResolution() }
        val fmtDspName = spinnerFormat.text.toString().ifEmpty { getFormatDisplayName(settingsManager.loadFormat()) }
        val fmtMime = supportedMimeTypesWithFriendlyNames.find { it.first == fmtDspName }?.second ?: SettingsManager.DEFAULT_FORMAT
        val quality = spinnerQuality.text.toString().ifEmpty { settingsManager.loadQuality() }
        val audioBitrateDspName = spinnerAudioBitrate.text.toString().ifEmpty { commonAudioBitrates.entries.find { it.value == settingsManager.loadAudioBitrate()}?.key ?: commonAudioBitrates.keys.first()}
        val audioBitrateBps = commonAudioBitrates[audioBitrateDspName] ?: settingsManager.loadAudioBitrate()

        if (resDspName == "Original") {
            // Use SettingsManager.STANDARD_RESOLUTIONS_MAP
            val assumed1080pKey = settingsManager.STANDARD_RESOLUTIONS_MAP.entries.find {it.key.contains("1080p")}!!.key
            val videoBitrateBps = getEstimatedVideoBitrate(assumed1080pKey, quality, fmtMime)
            val totalBitrateBps = videoBitrateBps + audioBitrateBps
            val bytesPerMinute = (totalBitrateBps / 8.0) * 60
            textViewEstimatedSize.text = "Est. size (Original as 1080p): ~${formatBytesToHumanReadable(bytesPerMinute)}/min"
            return
        }
        val videoBitrateBps = getEstimatedVideoBitrate(resDspName, quality, fmtMime)
        val totalBitrateBps = videoBitrateBps + audioBitrateBps
        val bytesPerMinute = (totalBitrateBps / 8.0) * 60
        textViewEstimatedSize.text = "Estimated size: ~${formatBytesToHumanReadable(bytesPerMinute)}/min"
    }

    private fun formatBytesToHumanReadable(bytes: Double): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes) / log10(1024.0)).toInt().coerceIn(0, units.size -1)
        return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
