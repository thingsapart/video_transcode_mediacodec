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
    private var resolutionAdapter: ArrayAdapter<String>? = null
    private lateinit var btnAddCustomResolution: ImageButton
    private var currentResolutionDisplayNames: MutableList<String> = mutableListOf()

    // Format
    private lateinit var spinnerFormat: AutoCompleteTextView
    private var formatAdapter: ArrayAdapter<String>? = null
    private var supportedMimeTypesWithFriendlyNames = mutableListOf<Pair<String, String>>()
    private var currentFormatDisplayNames: MutableList<String> = mutableListOf()

    // Quality
    private lateinit var spinnerQuality: AutoCompleteTextView
    private var supportedQualities: Array<String> = arrayOf("High", "Medium", "Low")

    // Frame Rate
    private lateinit var spinnerFrameRate: AutoCompleteTextView
    private var frameRateAdapter: ArrayAdapter<String>? = null
    private lateinit var btnAddCustomFrameRate: ImageButton
    private var currentFrameRateDisplayNames: MutableList<String> = mutableListOf()

    // Audio Bitrate
    private lateinit var spinnerAudioBitrate: AutoCompleteTextView
    private var audioBitrateAdapter: ArrayAdapter<String>? = null
    private var currentAudioBitrateDisplayNames: MutableList<String> = mutableListOf()
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
        // COMMON_FRAME_RATES list removed, will use specific values + custom
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

        resolutionAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, currentResolutionDisplayNames)
        spinnerResolution.setAdapter(resolutionAdapter)

        frameRateAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, currentFrameRateDisplayNames)
        spinnerFrameRate.setAdapter(frameRateAdapter)

        formatAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, currentFormatDisplayNames)
        spinnerFormat.setAdapter(formatAdapter)

        audioBitrateAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, currentAudioBitrateDisplayNames)
        spinnerAudioBitrate.setAdapter(audioBitrateAdapter)

        setupSpinners()
        queryCodecCapabilities()
        loadSettings()
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
        // ... (same as before)
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
        currentFormatDisplayNames.clear()
        currentFormatDisplayNames.addAll(supportedMimeTypesWithFriendlyNames.map { it.first })
        formatAdapter?.notifyDataSetChanged()

        val initialFormatMime = settingsManager.loadFormat().takeIf { mime -> rawMimeTypes.contains(mime) } ?: rawMimeTypes.firstOrNull() ?: SettingsManager.DEFAULT_FORMAT
        updateCapabilitiesForFormat(initialFormatMime)
    }

    private fun updateCapabilitiesForFormat(mimeType: String) {
        // ... (resolution part same as before)
        Log.d(TAG, "Updating capabilities for format: $mimeType")
        val currentSelectedResolutionDisplayName = spinnerResolution.text.toString().ifEmpty { settingsManager.loadResolution() }
        val currentSelectedFrameRate = spinnerFrameRate.text.toString().ifEmpty { settingsManager.loadFrameRate().toString() } // Will be display name

        val originalResStr = getString(R.string.resolution_original_display_name)
        currentResolutionDisplayNames.clear()
        currentResolutionDisplayNames.add(originalResStr)

        val selectedCodecInfo = availableCodecs.find { it.supportedTypes.contains(mimeType) }

        if (selectedCodecInfo == null) {
            settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == originalResStr }.forEach { currentResolutionDisplayNames.add(it) }
            supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
        } else {
            try {
                val capabilities = selectedCodecInfo.getCapabilitiesForType(mimeType)
                val videoCaps = capabilities.videoCapabilities
                if (videoCaps != null) {
                    supportedVideoBitrates = videoCaps.bitrateRange; supportedFrameRatesFromCodec = videoCaps.supportedFrameRates
                    settingsManager.STANDARD_RESOLUTIONS_MAP.forEach { (displayName, value) ->
                        if (value != originalResStr) settingsManager.parseResolutionValue(value)?.let { dims ->
                            if (videoCaps.isSizeSupported(dims.first, dims.second) && !currentResolutionDisplayNames.contains(displayName)) {
                                currentResolutionDisplayNames.add(displayName)
                            }
                        }
                    }
                } else {
                    settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == originalResStr }.forEach { currentResolutionDisplayNames.add(it) }
                    supportedVideoBitrates = Range(500_000, 5_000_000); supportedFrameRatesFromCodec = Range(15, 30)
                }
                val audioCaps = capabilities.audioCapabilities
                if (audioCaps != null) supportedAudioBitratesFromCodec = audioCaps.bitrateRange else supportedAudioBitratesFromCodec = Range(64_000, 320_000)
            } catch (e: Exception) {
                settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == originalResStr }.forEach { currentResolutionDisplayNames.add(it) }
                supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
            }
        }
        if (currentResolutionDisplayNames.size == 1 && currentResolutionDisplayNames[0] == originalResStr) {
             settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == originalResStr || currentResolutionDisplayNames.contains(it) }.forEach { currentResolutionDisplayNames.add(it) }
        }

        val previouslySelectedIsCustomRes = !settingsManager.STANDARD_RESOLUTIONS_MAP.containsKey(currentSelectedResolutionDisplayName) && currentSelectedResolutionDisplayName.startsWith("Custom")
        if (previouslySelectedIsCustomRes && !currentResolutionDisplayNames.contains(currentSelectedResolutionDisplayName)) {
            currentResolutionDisplayNames.add(currentSelectedResolutionDisplayName)
        }

        resolutionAdapter?.clear()
        resolutionAdapter?.addAll(currentResolutionDisplayNames.distinct().sortedWith(compareBy { if (it == originalResStr) "0" else settingsManager.parseResolutionValue(it)?.let { dim -> String.format("%05dx%05d", dim.first, dim.second) } ?: it }))
        resolutionAdapter?.notifyDataSetChanged()

        if (currentResolutionDisplayNames.contains(currentSelectedResolutionDisplayName)) spinnerResolution.setText(currentSelectedResolutionDisplayName, false)
        else if (currentResolutionDisplayNames.isNotEmpty()) spinnerResolution.setText(currentResolutionDisplayNames[0], false)
        else spinnerResolution.setText("", false)

        // Frame rate selection preservation
        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        val loadedFpsInt = settingsManager.loadFrameRate()
        val targetFpsDisplay = if (loadedFpsInt == SettingsManager.ORIGINAL_FRAME_RATE) originalFpsStr else getString(R.string.frame_rate_display_format, loadedFpsInt.toString())

        if (currentFrameRateDisplayNames.contains(targetFpsDisplay)) {
            spinnerFrameRate.setText(targetFpsDisplay, false)
        } else if (currentFrameRateDisplayNames.isNotEmpty()) {
             // If the exact value isn't there (e.g. custom from another session not yet added), try to set to "Original" or first common.
            spinnerFrameRate.setText(currentFrameRateDisplayNames.find { it == originalFpsStr } ?: currentFrameRateDisplayNames.first(), false)
        }
    }

    private fun setupSpinners() {
        currentFormatDisplayNames.clear()
        currentFormatDisplayNames.addAll(supportedMimeTypesWithFriendlyNames.map { it.first })
        formatAdapter?.notifyDataSetChanged()

        val originalResStr = getString(R.string.resolution_original_display_name)
        currentResolutionDisplayNames.clear(); currentResolutionDisplayNames.add(originalResStr)
        settingsManager.STANDARD_RESOLUTIONS_MAP.keys.filterNot { it == originalResStr }.forEach { currentResolutionDisplayNames.add(it) }
        resolutionAdapter?.clear()
        resolutionAdapter?.addAll(currentResolutionDisplayNames.distinct())
        resolutionAdapter?.notifyDataSetChanged()

        val qualityAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, supportedQualities)
        spinnerQuality.setAdapter(qualityAdapter)

        // Frame Rate Initialization
        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        currentFrameRateDisplayNames.clear()
        currentFrameRateDisplayNames.add(originalFpsStr)
        currentFrameRateDisplayNames.add(getString(R.string.frame_rate_display_format, "24"))
        currentFrameRateDisplayNames.add(getString(R.string.frame_rate_display_format, "30"))
        // currentFrameRateDisplayNames.add(getString(R.string.frame_rate_display_format, "60")) // Optionally add more common ones

        // Add saved FPS if it's custom and not already in the list (will be handled more robustly in loadSettings)
        // For now, just sort the initial list.
        sortFrameRateDisplayNames()
        frameRateAdapter?.clear()
        frameRateAdapter?.addAll(currentFrameRateDisplayNames)
        frameRateAdapter?.notifyDataSetChanged()

        currentAudioBitrateDisplayNames.clear()
        currentAudioBitrateDisplayNames.addAll(commonAudioBitrates.keys.toList())
        audioBitrateAdapter?.clear()
        audioBitrateAdapter?.addAll(currentAudioBitrateDisplayNames)
        audioBitrateAdapter?.notifyDataSetChanged()
    }

    private fun sortFrameRateDisplayNames() {
        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        currentFrameRateDisplayNames.sortWith(compareBy {
            when (it) {
                originalFpsStr -> -1.0 // "Original" comes first
                else -> it.removeSuffix(" FPS").toDoubleOrNull() ?: Double.MAX_VALUE
            }
        })
    }

    private fun loadSettings() {
        val loadedFormatMime = settingsManager.loadFormat()
        val loadedFormatDisplayName = supportedMimeTypesWithFriendlyNames.find { it.second == loadedFormatMime }?.first ?: getFormatDisplayName(loadedFormatMime)
        spinnerFormat.setText(loadedFormatDisplayName, false)
        updateCapabilitiesForFormat(loadedFormatMime) // This will also handle resolution spinner update

        val loadedResolutionDisplayName = settingsManager.loadResolution()
        if (!currentResolutionDisplayNames.contains(loadedResolutionDisplayName) && (loadedResolutionDisplayName.startsWith("Custom (") || loadedResolutionDisplayName.contains("x"))) {
            val customDisplayName = if (loadedResolutionDisplayName.startsWith("Custom (")) loadedResolutionDisplayName
            else settingsManager.parseResolutionValue(loadedResolutionDisplayName)?.let { getString(R.string.custom_resolution_format, it.first, it.second) } ?: loadedResolutionDisplayName
            if(!currentResolutionDisplayNames.contains(customDisplayName)){
                resolutionAdapter?.add(customDisplayName)
                // Sorting is handled by updateCapabilitiesForFormat if it re-populates, or should be re-sorted here
                currentResolutionDisplayNames.sortWith(compareBy { if (it == getString(R.string.resolution_original_display_name)) "0" else settingsManager.parseResolutionValue(it)?.let { dim -> String.format("%05dx%05d", dim.first, dim.second) } ?: it }))
                resolutionAdapter?.notifyDataSetChanged()
            }
            spinnerResolution.setText(customDisplayName, false)
        } else {
             if (currentResolutionDisplayNames.contains(loadedResolutionDisplayName)) spinnerResolution.setText(loadedResolutionDisplayName, false)
             else if (currentResolutionDisplayNames.isNotEmpty()) spinnerResolution.setText(currentResolutionDisplayNames[0], false)
        }

        val savedFpsInt = settingsManager.loadFrameRate()
        val loadedFrameRateDisplay: String = if (savedFpsInt == SettingsManager.ORIGINAL_FRAME_RATE) {
            getString(R.string.frame_rate_original_display_name)
        } else {
            getString(R.string.frame_rate_display_format, savedFpsInt.toString())
        }

        if (!currentFrameRateDisplayNames.contains(loadedFrameRateDisplay)) {
            currentFrameRateDisplayNames.add(loadedFrameRateDisplay)
            sortFrameRateDisplayNames()
            frameRateAdapter?.clear()
            frameRateAdapter?.addAll(currentFrameRateDisplayNames)
            frameRateAdapter?.notifyDataSetChanged()
        }
        spinnerFrameRate.setText(loadedFrameRateDisplay, false)

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
            settingsManager.saveResolution(resolutionAdapter?.getItem(position) ?: getString(R.string.resolution_original_display_name)); updateEstimatedSize()
        }
        spinnerFormat.setOnItemClickListener { _, _, position, _ ->
            val selectedFriendlyName = formatAdapter?.getItem(position)
            val selectedFormatMime = supportedMimeTypesWithFriendlyNames.find { it.first == selectedFriendlyName }?.second ?: SettingsManager.DEFAULT_FORMAT
            settingsManager.saveFormat(selectedFormatMime); updateCapabilitiesForFormat(selectedFormatMime); updateEstimatedSize()
        }
        spinnerQuality.setOnItemClickListener { _, _, position, _ ->
            val qualityAdapter = spinnerQuality.adapter as? ArrayAdapter<String>
            settingsManager.saveQuality(qualityAdapter?.getItem(position) ?: supportedQualities.first()); updateEstimatedSize()
        }
        spinnerFrameRate.setOnItemClickListener { _, _, position, _ ->
            val selectedFpsDisplay = frameRateAdapter?.getItem(position) ?: getString(R.string.frame_rate_original_display_name)
            val fpsToSave = if (selectedFpsDisplay == getString(R.string.frame_rate_original_display_name)) {
                SettingsManager.ORIGINAL_FRAME_RATE
            } else {
                selectedFpsDisplay.removeSuffix(" FPS").toIntOrNull() ?: SettingsManager.DEFAULT_FRAME_RATE
            }
            settingsManager.saveFrameRate(fpsToSave); updateEstimatedSize()
        }
        spinnerAudioBitrate.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplayName = audioBitrateAdapter?.getItem(position)
            val selectedBitrateBps = commonAudioBitrates[selectedDisplayName] ?: commonAudioBitrates.values.first()
            settingsManager.saveAudioBitrate(selectedBitrateBps); updateEstimatedSize()
        }
    }

    private fun showCustomResolutionDialog() {
        // ... (Ensure sorting uses getString(R.string.resolution_original_display_name))
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
                        currentResolutionDisplayNames.add(customResDisplayName)
                        currentResolutionDisplayNames.sortWith(compareBy { if (it == getString(R.string.resolution_original_display_name)) "0" else settingsManager.parseResolutionValue(it)?.let { dim -> String.format("%05dx%05d", dim.first, dim.second) } ?: it }))
                        resolutionAdapter?.clear()
                        resolutionAdapter?.addAll(currentResolutionDisplayNames)
                        resolutionAdapter?.notifyDataSetChanged()
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
                val fpsStr = frEditText.text.toString(); val fpsInt = fpsStr.toIntOrNull()
                if (fpsInt != null && fpsInt > 0) {
                    val fpsDisplayString = getString(R.string.frame_rate_display_format, fpsStr)
                    if(!currentFrameRateDisplayNames.contains(fpsDisplayString)){
                        currentFrameRateDisplayNames.add(fpsDisplayString)
                        sortFrameRateDisplayNames()
                        frameRateAdapter?.clear(); frameRateAdapter?.addAll(currentFrameRateDisplayNames); frameRateAdapter?.notifyDataSetChanged()
                    }
                    spinnerFrameRate.setText(fpsDisplayString, false)
                    settingsManager.saveFrameRate(fpsInt); updateEstimatedSize() // Save the integer
                    dialog.dismiss()
                } else { Toast.makeText(this, getString(R.string.error_invalid_frame_rate_value), Toast.LENGTH_SHORT).show()}
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun getEstimatedVideoBitrate(resDspName: String, quality: String, mimeType: String): Int {
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

        val frameRateDisplay = spinnerFrameRate.text.toString().ifEmpty {
             val savedFps = settingsManager.loadFrameRate()
             if (savedFps == SettingsManager.ORIGINAL_FRAME_RATE) getString(R.string.frame_rate_original_display_name)
             else getString(R.string.frame_rate_display_format, savedFps.toString())
        }

        var actualFpsForEstimation = SettingsManager.DEFAULT_FRAME_RATE // Default for estimation if "Original"
        if (frameRateDisplay != getString(R.string.frame_rate_original_display_name)) {
            actualFpsForEstimation = frameRateDisplay.removeSuffix(" FPS").toIntOrNull() ?: SettingsManager.DEFAULT_FRAME_RATE
        }
        // Note: The 'actualFpsForEstimation' is used in the formula below if it were part of it.
        // The current formula doesn't directly use FPS, but it's good to have it parsed.

        val audioBitrateDspName = spinnerAudioBitrate.text.toString().ifEmpty { commonAudioBitrates.entries.find { it.value == settingsManager.loadAudioBitrate()}?.key ?: commonAudioBitrates.keys.first()}
        val audioBitrateBps = commonAudioBitrates[audioBitrateDspName] ?: settingsManager.loadAudioBitrate()

        if (resDspName.equals(getString(R.string.resolution_original_display_name), ignoreCase = true)) {
            val assumed1080pKey = settingsManager.STANDARD_RESOLUTIONS_MAP.entries.find {it.key.contains("1080p")}!!.key
            val videoBitrateBps = getEstimatedVideoBitrate(assumed1080pKey, quality, fmtMime) // FPS not directly used in this bitrate estimation
            val totalBitrateBps = videoBitrateBps + audioBitrateBps
            val bytesPerMinute = (totalBitrateBps / 8.0) * 60
            textViewEstimatedSize.text = "Est. size (Original as 1080p): ~${formatBytesToHumanReadable(bytesPerMinute)}/min"
            return
        }
        val videoBitrateBps = getEstimatedVideoBitrate(resDspName, quality, fmtMime) // FPS not directly used here
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
