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
import android.content.Intent
import android.view.View
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
    private var qualityAdapter: ArrayAdapter<String>? = null
    private var currentQualityDisplayNames: MutableList<String> = mutableListOf()
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
    private lateinit var saveAsDefaultsSwitch: com.google.android.material.switchmaterial.SwitchMaterial // Changed to non-nullable

    // Initial settings for temporary mode change detection
    private var initialResolution: String? = null
    private var initialFormatMime: String? = null
    private var initialQuality: String? = null
    private var initialFrameRate: Int? = null
    private var initialAudioBitrate: Int? =null

    // Codec Info
    private var availableCodecs: List<MediaCodecInfo> = listOf()
    private var supportedFrameRatesFromCodec: Range<Int>? = null
    private var supportedVideoBitrates: Range<Int>? = null
    private var supportedAudioBitratesFromCodec: Range<Int>? = null

    private var isTemporarySettingsMode: Boolean = false

    companion object {
        private const val TAG = "SettingsActivity"
        private const val BASE_BITRATE_MEDIUM_720P_AVC = 2_500_000
        private const val BASE_BITRATE_MEDIUM_720P_HEVC = 1_800_000
        private const val BASE_BITRATE_MEDIUM_720P_VP9 = 2_000_000
        const val EXTRA_IS_TEMPORARY_SETTINGS = "is_temporary_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.d(TAG, "onCreate: Inflating layout")

        isTemporarySettingsMode = intent.getBooleanExtra(EXTRA_IS_TEMPORARY_SETTINGS, false)

        if (isTemporarySettingsMode) {
            actionBar?.setDisplayHomeAsUpEnabled(true)
            actionBar?.setDisplayShowHomeEnabled(true)
            title = getString(R.string.title_temporary_settings) // Set title for temporary mode
        }

        settingsManager = SettingsManager(applicationContext)

        spinnerResolution = findViewById(R.id.spinner_resolution)
        btnAddCustomResolution = findViewById(R.id.btn_add_custom_resolution)
        spinnerFormat = findViewById(R.id.spinner_format)
        spinnerQuality = findViewById(R.id.spinner_quality)
        spinnerFrameRate = findViewById(R.id.spinner_frame_rate)
        btnAddCustomFrameRate = findViewById(R.id.btn_add_custom_frame_rate)
        spinnerAudioBitrate = findViewById(R.id.spinner_audio_bitrate)
        textViewEstimatedSize = findViewById(R.id.textview_estimated_size)

        // Reverted to standard Android dropdown item layout
        resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentResolutionDisplayNames)
        spinnerResolution.setAdapter(resolutionAdapter)

        frameRateAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentFrameRateDisplayNames)
        spinnerFrameRate.setAdapter(frameRateAdapter)

        formatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentFormatDisplayNames)
        spinnerFormat.setAdapter(formatAdapter)

        audioBitrateAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentAudioBitrateDisplayNames)
        spinnerAudioBitrate.setAdapter(audioBitrateAdapter)

        qualityAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentQualityDisplayNames)
        spinnerQuality.setAdapter(qualityAdapter)

        Log.d(TAG, "onCreate: Spinners.")

        // Get reference to the switch from XML
        saveAsDefaultsSwitch = findViewById(R.id.switch_save_as_defaults)

        if (isTemporarySettingsMode) {
            // Make the XML switch visible and ensure it's off by default initially
            saveAsDefaultsSwitch.visibility = View.VISIBLE
            saveAsDefaultsSwitch.isChecked = false
        } else {
            saveAsDefaultsSwitch.visibility = View.GONE
        }

        setupSpinners()
        queryCodecCapabilities()
        loadSettings()
        // Store initial settings if in temporary mode
        if (isTemporarySettingsMode) {
            storeInitialSettings()
        }
        setupListeners()

        Log.d(TAG, "onCreate: UI initialized and listeners set up with corrected order.")
    }

    // Removed addSaveAsDefaultsSwitch() method as it's now in XML

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
        currentFormatDisplayNames.clear()
        currentFormatDisplayNames.addAll(supportedMimeTypesWithFriendlyNames.map { it.first })
        formatAdapter?.notifyDataSetChanged()

        val initialFormatMime = settingsManager.loadFormat().takeIf { mime -> rawMimeTypes.contains(mime) } ?: rawMimeTypes.firstOrNull() ?: SettingsManager.DEFAULT_FORMAT
        updateCapabilitiesForFormat(initialFormatMime)
    }

    private fun updateCapabilitiesForFormat(mimeType: String) {
        Log.d(TAG, "Updating capabilities for format: $mimeType")
        val previouslySelectedResolution = spinnerResolution.text.toString().ifEmpty { settingsManager.loadResolution() }
        val previouslySelectedFrameRate = spinnerFrameRate.text.toString().ifEmpty {
            val savedFps = settingsManager.loadFrameRate()
            if (savedFps == SettingsManager.ORIGINAL_FRAME_RATE) getString(R.string.frame_rate_original_display_name)
            else getString(R.string.frame_rate_display_format, savedFps.toString())
        }

        val originalResString = getString(R.string.resolution_original_display_name)
        val newResolutionDisplayNames = mutableListOf<String>()
        newResolutionDisplayNames.add(originalResString)

        val selectedCodecInfo = availableCodecs.find { it.supportedTypes.contains(mimeType) }
        val tempStandardCompatibleNames = mutableListOf<String>()

        if (selectedCodecInfo == null) {
            Log.w(TAG, "No codec info for $mimeType, adding all standard resolutions.")
            settingsManager.STANDARD_RESOLUTIONS_MAP.keys.forEach { tempStandardCompatibleNames.add(it) }
            supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
        } else {
            try {
                val capabilities = selectedCodecInfo.getCapabilitiesForType(mimeType)
                val videoCaps = capabilities.videoCapabilities
                if (videoCaps != null) {
                    supportedVideoBitrates = videoCaps.bitrateRange; supportedFrameRatesFromCodec = videoCaps.supportedFrameRates
                    settingsManager.STANDARD_RESOLUTIONS_MAP.forEach { (displayName, valueString) ->
                        settingsManager.parseResolutionValue(valueString)?.let { dims ->
                            if (videoCaps.isSizeSupported(dims.first, dims.second)) {
                                tempStandardCompatibleNames.add(displayName)
                            }
                        }
                    }
                } else {
                    settingsManager.STANDARD_RESOLUTIONS_MAP.keys.forEach { tempStandardCompatibleNames.add(it) }
                    supportedVideoBitrates = Range(500_000, 5_000_000); supportedFrameRatesFromCodec = Range(15, 30)
                }
                val audioCaps = capabilities.audioCapabilities
                if (audioCaps != null) supportedAudioBitratesFromCodec = audioCaps.bitrateRange else supportedAudioBitratesFromCodec = Range(64_000, 320_000)
            } catch (e: Exception) {
                Log.e(TAG, "Exception getting capabilities for $mimeType", e)
                settingsManager.STANDARD_RESOLUTIONS_MAP.keys.forEach { tempStandardCompatibleNames.add(it) }
                supportedFrameRatesFromCodec = Range(15, 60); supportedVideoBitrates = Range(250_000, 10_000_000); supportedAudioBitratesFromCodec = Range(32_000, 512_000)
            }
        }

        newResolutionDisplayNames.addAll(tempStandardCompatibleNames.distinct().sortedWith(compareBy { displayName ->
            settingsManager.parseResolutionValue(displayName)?.let { dims -> dims.first.toLong() * dims.second.toLong() } ?: Long.MAX_VALUE
        }))

        if (previouslySelectedResolution.startsWith("Custom (") && !newResolutionDisplayNames.contains(previouslySelectedResolution)) {
            newResolutionDisplayNames.add(previouslySelectedResolution)
        }

        currentResolutionDisplayNames.clear()
        currentResolutionDisplayNames.addAll(newResolutionDisplayNames.distinct())
        currentResolutionDisplayNames.sortWith(compareBy {
            when {
                it == originalResString -> 0L
                it.startsWith("Custom (") -> Long.MAX_VALUE
                else -> settingsManager.parseResolutionValue(it)?.let { dim -> dim.first.toLong() * dim.second.toLong() } ?: (Long.MAX_VALUE -1)
            }
        })

        //resolutionAdapter?.clear()
        //resolutionAdapter?.addAll(currentResolutionDisplayNames)
        resolutionAdapter?.notifyDataSetChanged()

        if (currentResolutionDisplayNames.contains(previouslySelectedResolution)) spinnerResolution.setText(previouslySelectedResolution, false)
        else if (currentResolutionDisplayNames.isNotEmpty()) spinnerResolution.setText(currentResolutionDisplayNames[0], false)
        else spinnerResolution.setText("", false)

        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        val defaultFrameRates = mutableListOf(
            originalFpsStr,
            getString(R.string.frame_rate_display_format, "24"),
            getString(R.string.frame_rate_display_format, "30")
        )
        val customFrameRatesInOldList = this.currentFrameRateDisplayNames
            .filter { it != originalFpsStr && !defaultFrameRates.contains(it) && it.endsWith(" FPS") }

        this.currentFrameRateDisplayNames.clear()
        this.currentFrameRateDisplayNames.addAll(defaultFrameRates)
        this.currentFrameRateDisplayNames.addAll(customFrameRatesInOldList.distinct())
        sortFrameRateDisplayNames()

        //frameRateAdapter?.clear()
        //frameRateAdapter?.addAll(this.currentFrameRateDisplayNames)
        frameRateAdapter?.notifyDataSetChanged()

        if (this.currentFrameRateDisplayNames.contains(previouslySelectedFrameRate)) {
            spinnerFrameRate.setText(previouslySelectedFrameRate, false)
        } else if (this.currentFrameRateDisplayNames.isNotEmpty()) {
            spinnerFrameRate.setText(this.currentFrameRateDisplayNames[0], false)
        } else {
            spinnerFrameRate.setText("", false)
        }
    }

    private fun setupSpinners() {
        Log.d(TAG, "setupSpinners: Enter.")
        Toast.makeText(this, "TEST", Toast.LENGTH_SHORT)
        
        currentFormatDisplayNames.clear()
        currentFormatDisplayNames.addAll(supportedMimeTypesWithFriendlyNames.map { it.first })
        formatAdapter?.notifyDataSetChanged()

        val originalResStr = getString(R.string.resolution_original_display_name)
        currentResolutionDisplayNames.clear()
        currentResolutionDisplayNames.add(originalResStr)
        val standardDisplayNames = settingsManager.STANDARD_RESOLUTIONS_MAP.keys
            .sortedWith(compareBy { displayName ->
                settingsManager.parseResolutionValue(displayName)?.let { dims ->
                    dims.first.toLong() * dims.second.toLong()
                } ?: Long.MAX_VALUE
            })
        currentResolutionDisplayNames.addAll(standardDisplayNames)
        Log.d(TAG, "setupSpinners: $standardDisplayNames.")
        //resolutionAdapter?.clear()
        //resolutionAdapter?.addAll(currentResolutionDisplayNames.distinct())
        resolutionAdapter?.notifyDataSetChanged()

        currentQualityDisplayNames.clear()
        currentQualityDisplayNames.addAll(supportedQualities.toList())
        qualityAdapter?.notifyDataSetChanged()


        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        currentFrameRateDisplayNames.clear()
        currentFrameRateDisplayNames.add(originalFpsStr)
        currentFrameRateDisplayNames.add(getString(R.string.frame_rate_display_format, "24"))
        currentFrameRateDisplayNames.add(getString(R.string.frame_rate_display_format, "30"))
        sortFrameRateDisplayNames()
        //frameRateAdapter?.clear()
        //frameRateAdapter?.addAll(currentFrameRateDisplayNames)
        frameRateAdapter?.notifyDataSetChanged()

        currentAudioBitrateDisplayNames.clear()
        currentAudioBitrateDisplayNames.addAll(commonAudioBitrates.keys.toList())
        //audioBitrateAdapter?.clear()
        //audioBitrateAdapter?.addAll(currentAudioBitrateDisplayNames)
        audioBitrateAdapter?.notifyDataSetChanged()

        Log.d(TAG, "setupSpinners: DONE.")
    }

    private fun sortFrameRateDisplayNames() {
        val originalFpsStr = getString(R.string.frame_rate_original_display_name)
        currentFrameRateDisplayNames.sortWith(compareBy {
            when (it) {
                originalFpsStr -> -1.0
                else -> it.removeSuffix(" FPS").toDoubleOrNull() ?: Double.MAX_VALUE
            }
        })
    }

    private fun loadSettings() {
        val loadedFormatMime = settingsManager.loadFormat()
        val loadedFormatDisplayName = supportedMimeTypesWithFriendlyNames.find { it.second == loadedFormatMime }?.first ?: getFormatDisplayName(loadedFormatMime)
        spinnerFormat.setText(loadedFormatDisplayName, false)
        updateCapabilitiesForFormat(loadedFormatMime)

        val loadedResolutionDisplayName = settingsManager.loadResolution()

        if (loadedResolutionDisplayName.startsWith("Custom (") && !currentResolutionDisplayNames.contains(loadedResolutionDisplayName)){
            currentResolutionDisplayNames.add(loadedResolutionDisplayName)
            val originalResString = getString(R.string.resolution_original_display_name)
            currentResolutionDisplayNames.sortWith(compareBy {
                when {
                    it == originalResString -> 0L
                    it.startsWith("Custom (") -> Long.MAX_VALUE
                    else -> settingsManager.parseResolutionValue(it)?.let { dim -> dim.first.toLong() * dim.second.toLong() } ?: (Long.MAX_VALUE -1)
                }
            })
            //resolutionAdapter?.clear()
            //resolutionAdapter?.addAll(currentResolutionDisplayNames)
            resolutionAdapter?.notifyDataSetChanged()
        }
        if (currentResolutionDisplayNames.contains(loadedResolutionDisplayName)) {
            spinnerResolution.setText(loadedResolutionDisplayName, false)
        } else if (currentResolutionDisplayNames.isNotEmpty()) {
            spinnerResolution.setText(currentResolutionDisplayNames[0], false)
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
            //frameRateAdapter?.clear()
            //frameRateAdapter?.addAll(currentFrameRateDisplayNames)
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
            val selectedResolution = resolutionAdapter?.getItem(position) ?: getString(R.string.resolution_original_display_name)
            if (!isTemporarySettingsMode) {
                settingsManager.saveResolution(selectedResolution)
            }
            updateEstimatedSize()
        }
        spinnerFormat.setOnItemClickListener { _, _, position, _ ->
            val selectedFriendlyName = formatAdapter?.getItem(position)
            val selectedFormatMime = supportedMimeTypesWithFriendlyNames.find { it.first == selectedFriendlyName }?.second ?: SettingsManager.DEFAULT_FORMAT
            if (!isTemporarySettingsMode) {
                settingsManager.saveFormat(selectedFormatMime)
            }
            updateCapabilitiesForFormat(selectedFormatMime)
            updateEstimatedSize()
        }
        spinnerQuality.setOnItemClickListener { _, _, position, _ ->
            val selectedQuality = qualityAdapter?.getItem(position) ?: supportedQualities.first()
            if (!isTemporarySettingsMode) {
                settingsManager.saveQuality(selectedQuality)
            }
            updateEstimatedSize()
        }
        spinnerFrameRate.setOnItemClickListener { _, _, position, _ ->
            val selectedFpsDisplay = frameRateAdapter?.getItem(position) ?: getString(R.string.frame_rate_original_display_name)
            val fpsToSave = if (selectedFpsDisplay == getString(R.string.frame_rate_original_display_name)) {
                SettingsManager.ORIGINAL_FRAME_RATE
            } else {
                selectedFpsDisplay.removeSuffix(" FPS").toIntOrNull() ?: SettingsManager.DEFAULT_FRAME_RATE
            }
            if (!isTemporarySettingsMode) {
                settingsManager.saveFrameRate(fpsToSave)
            }
            updateEstimatedSize()
        }
        spinnerAudioBitrate.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplayName = audioBitrateAdapter?.getItem(position)
            val selectedBitrateBps = commonAudioBitrates[selectedDisplayName] ?: commonAudioBitrates.values.first()
            if (!isTemporarySettingsMode) {
                settingsManager.saveAudioBitrate(selectedBitrateBps)
            }
            updateEstimatedSize()
        }
    }

    private fun storeInitialSettings() {
        initialResolution = settingsManager.loadResolution()
        initialFormatMime = settingsManager.loadFormat()
        initialQuality = settingsManager.loadQuality()
        initialFrameRate = settingsManager.loadFrameRate()
        initialAudioBitrate = settingsManager.loadAudioBitrate()
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
                        currentResolutionDisplayNames.add(customResDisplayName)

                        val originalResString = getString(R.string.resolution_original_display_name)
                        currentResolutionDisplayNames.sortWith(compareBy {
                            when {
                                it == originalResString -> 0L
                                it.startsWith("Custom (") -> Long.MAX_VALUE
                                else -> settingsManager.parseResolutionValue(it)?.let { dim -> dim.first.toLong() * dim.second.toLong() } ?: (Long.MAX_VALUE -1)
                            }
                        })

                        //resolutionAdapter?.clear()
                        //resolutionAdapter?.addAll(currentResolutionDisplayNames)
                        resolutionAdapter?.notifyDataSetChanged()
                    }
                    spinnerResolution.setText(customResDisplayName, false)
                    if (!isTemporarySettingsMode) {
                        settingsManager.saveResolution(customResDisplayName)
                    }
                    updateEstimatedSize()
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
                        //frameRateAdapter?.clear(); frameRateAdapter?.addAll(currentFrameRateDisplayNames);
                        frameRateAdapter?.notifyDataSetChanged()
                    }
                    spinnerFrameRate.setText(fpsDisplayString, false)
                    if (!isTemporarySettingsMode) {
                        settingsManager.saveFrameRate(fpsInt)
                    }
                    updateEstimatedSize()
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

        val audioBitrateDspName = spinnerAudioBitrate.text.toString().ifEmpty { commonAudioBitrates.entries.find { it.value == settingsManager.loadAudioBitrate()}?.key ?: commonAudioBitrates.keys.first()}
        val audioBitrateBps = commonAudioBitrates[audioBitrateDspName] ?: settingsManager.loadAudioBitrate()

        if (resDspName.equals(getString(R.string.resolution_original_display_name), ignoreCase = true)) {
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

    private fun getSelectedSettings(): Bundle {
        val settingsBundle = Bundle()
        settingsBundle.putString(SettingsManager.PREF_RESOLUTION, spinnerResolution.text.toString())
        val selectedFormatDisplayName = spinnerFormat.text.toString()
        val selectedFormatMime = supportedMimeTypesWithFriendlyNames.find { it.first == selectedFormatDisplayName }?.second ?: SettingsManager.DEFAULT_FORMAT
        settingsBundle.putString(SettingsManager.PREF_FORMAT, selectedFormatMime)
        settingsBundle.putString(SettingsManager.PREF_QUALITY, spinnerQuality.text.toString())

        val selectedFpsDisplay = spinnerFrameRate.text.toString()
        val fpsToReturn = if (selectedFpsDisplay == getString(R.string.frame_rate_original_display_name)) {
            SettingsManager.ORIGINAL_FRAME_RATE
        } else {
            selectedFpsDisplay.removeSuffix(" FPS").toIntOrNull() ?: SettingsManager.DEFAULT_FRAME_RATE
        }
        settingsBundle.putInt(SettingsManager.PREF_FRAME_RATE, fpsToReturn)

        val selectedAudioBitrateDisplayName = spinnerAudioBitrate.text.toString()
        val audioBitrateToReturn = commonAudioBitrates[selectedAudioBitrateDisplayName] ?: commonAudioBitrates.values.first()
        settingsBundle.putInt(SettingsManager.PREF_AUDIO_BITRATE, audioBitrateToReturn)
        return settingsBundle
    }

    private fun haveSettingsChanged(): Boolean {
        if (!isTemporarySettingsMode) return false // Should not be called if not in temp mode

        val currentRes = spinnerResolution.text.toString()
        val currentFormatDisplayName = spinnerFormat.text.toString()
        val currentFormatMime = supportedMimeTypesWithFriendlyNames.find { it.first == currentFormatDisplayName }?.second ?: SettingsManager.DEFAULT_FORMAT
        val currentQuality = spinnerQuality.text.toString()
        val currentFpsDisplay = spinnerFrameRate.text.toString()
        val currentFps = if (currentFpsDisplay == getString(R.string.frame_rate_original_display_name)) {
            SettingsManager.ORIGINAL_FRAME_RATE
        } else {
            currentFpsDisplay.removeSuffix(" FPS").toIntOrNull() ?: SettingsManager.DEFAULT_FRAME_RATE
        }
        val currentAudioBitrateDisplayName = spinnerAudioBitrate.text.toString()
        val currentAudioBitrate = commonAudioBitrates[currentAudioBitrateDisplayName] ?: commonAudioBitrates.values.first()

        return initialResolution != currentRes ||
                initialFormatMime != currentFormatMime ||
                initialQuality != currentQuality ||
                initialFrameRate != currentFps ||
                initialAudioBitrate != currentAudioBitrate
    }

    override fun finish() {
        if (isTemporarySettingsMode) {
            val resultIntent = Intent()
            val settingsChanged = haveSettingsChanged()
            if (settingsChanged) {
                val currentSettingsBundle = getSelectedSettings()
                resultIntent.putExtras(currentSettingsBundle)
                setResult(Activity.RESULT_OK, resultIntent)

                if (saveAsDefaultsSwitch.isChecked == true) { // Now non-nullable
                    settingsManager.saveResolution(currentSettingsBundle.getString(SettingsManager.PREF_RESOLUTION) ?: initialResolution!!)
                    settingsManager.saveFormat(currentSettingsBundle.getString(SettingsManager.PREF_FORMAT) ?: initialFormatMime!!)
                    settingsManager.saveQuality(currentSettingsBundle.getString(SettingsManager.PREF_QUALITY) ?: initialQuality!!)
                    settingsManager.saveFrameRate(currentSettingsBundle.getInt(SettingsManager.PREF_FRAME_RATE, initialFrameRate!!))
                    settingsManager.saveAudioBitrate(currentSettingsBundle.getInt(SettingsManager.PREF_AUDIO_BITRATE, initialAudioBitrate!!))
                }
            } else {
                setResult(Activity.RESULT_CANCELED, resultIntent)
            }
        }
        // For non-temporary mode, settings are saved on change by listeners, so just super.finish()
        super.finish()
    }

    override fun onNavigateUp(): Boolean {
        if (isTemporarySettingsMode) {
            finish() // finish() now contains the temporary mode logic
            return true
        }
        return super.onNavigateUp()
    }

    private fun formatBytesToHumanReadable(bytes: Double): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes) / log10(1024.0)).toInt().coerceIn(0, units.size -1)
        return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
