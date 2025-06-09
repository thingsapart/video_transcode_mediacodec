package com.thingsapart.videopt

import android.Manifest // Added
import android.animation.ObjectAnimator
import android.content.*
import android.content.pm.PackageManager // Added
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts // Added
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat // Added
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        val COMMON_RESOLUTIONS = mapOf( // Replicated from SettingsFragment
            "1080p" to Pair(1920, 1080),
            "720p" to Pair(1280, 720),
            "480p" to Pair(854, 480),
            "360p" to Pair(640, 360)
        )
        private const val BASE_BITRATE_MEDIUM_720P_AVC = 2_500_000 // Replicated
        private const val BASE_BITRATE_MEDIUM_720P_HEVC = 1_800_000 // Replicated
        private const val BASE_BITRATE_MEDIUM_720P_VP9 = 2_000_000  // Replicated

        const val EXTRA_INPUT_VIDEO_URI = "input_video_uri"
        const val EXTRA_OUTPUT_VIDEO_URI = "output_video_uri"
        const val EXTRA_OUTPUT_MIME_TYPE = "output_video_mime_type"
    }

    private lateinit var imageThumbnail: ImageView
    private lateinit var progressBarTranscoding: ProgressBar
    private lateinit var textStatusInfo: TextView
    private lateinit var textVideoSize: TextView
    private lateinit var textEstimatedOutputSize: TextView // Added
    private lateinit var videoControlsContainer: View
    private lateinit var buttonPlay: ImageButton
    private lateinit var buttonPause: ImageButton
    private lateinit var btnShare: Button

    private var inputVideoUri: Uri? = null
    private var outputVideoUri: Uri? = null
    private var outputVideoMimeType: String = "video/mp4"

    private var isTranscodingComplete = false
    private var isTranscodingPreviouslyStarted = false

    private lateinit var settingsManager: SettingsManager // Added

    // ActivityResultLauncher for notification permission
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                startTranscodingServiceAfterPermission()
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                Toast.makeText(this, getString(R.string.notification_permission_denied_message), Toast.LENGTH_LONG).show()
                // Handle the case where permission is denied:
                // - Disable features that require the service?
                // - Show a more prominent message in textStatusInfo?
                textStatusInfo.text = getString(R.string.notification_permission_required_status)
                // Optionally, disable UI elements that depend on transcoding starting
                btnShare.isEnabled = false
                buttonPlay.isEnabled = false
            }
        }

    private val transcodingReceiver = object : BroadcastReceiver() {
        // ... (existing receiver code)
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TranscodingService.ACTION_TRANSCODING_PROGRESS -> {
                    val progress = intent.getIntExtra(TranscodingService.EXTRA_PROGRESS, 0)
                    Log.d(TAG, "Received progress: $progress%")
                    progressBarTranscoding.progress = progress
                    textStatusInfo.text = "Transcoding: $progress%"
                    if (!isTranscodingPreviouslyStarted && progress > 0 && progress < 100) {
                        isTranscodingPreviouslyStarted = true
                        animateThumbnailFadeOut()
                    }
                }
                TranscodingService.ACTION_TRANSCODING_COMPLETE -> {
                    val outputUriString = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_URI)
                    outputVideoMimeType = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_MIME_TYPE) ?: "video/mp4"
                    if (outputUriString != null) {
                        outputVideoUri = Uri.parse(outputUriString)
                        Log.i(TAG, "Transcoding complete. Output: $outputVideoUri, Mime: $outputVideoMimeType")
                        textStatusInfo.text = getString(R.string.transcoding_complete_status)
                        progressBarTranscoding.progress = 100
                        isTranscodingComplete = true
                        btnShare.isEnabled = true
                        videoControlsContainer.visibility = View.VISIBLE // Show container
                        buttonPlay.visibility = View.VISIBLE
                        buttonPlay.isEnabled = true
                        imageThumbnail.alpha = 0f
                    } else {
                        Log.e(TAG, "Transcoding complete but output URI is null.")
                        textStatusInfo.text = getString(R.string.error_output_file_not_found)
                        btnShare.isEnabled = false
                        videoControlsContainer.visibility = View.GONE // Hide container
                        buttonPlay.isEnabled = false
                    }
                }
                TranscodingService.ACTION_TRANSCODING_ERROR -> {
                    val errorMessage = intent.getStringExtra(TranscodingService.EXTRA_ERROR_MESSAGE) ?: getString(R.string.unknown_error)
                    Log.e(TAG, "Transcoding error: $errorMessage")
                    textStatusInfo.text = getString(R.string.error_transcoding_message, errorMessage)
                    progressBarTranscoding.progress = 0
                    isTranscodingComplete = false
                    btnShare.isEnabled = false
                    videoControlsContainer.visibility = View.GONE // Hide container
                    buttonPlay.isEnabled = false
                    imageThumbnail.alpha = 1f
                    isTranscodingPreviouslyStarted = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        Log.d(TAG, "onCreate called")

        imageThumbnail = findViewById(R.id.image_thumbnail)
        progressBarTranscoding = findViewById(R.id.progress_bar_transcoding)
        textStatusInfo = findViewById(R.id.text_status_info)
        textVideoSize = findViewById(R.id.text_video_size)
        textEstimatedOutputSize = findViewById(R.id.text_estimated_output_size) // Initialize
        videoControlsContainer = findViewById(R.id.video_controls_container)
        buttonPlay = findViewById(R.id.button_play)
        buttonPause = findViewById(R.id.button_pause)
        btnShare = findViewById(R.id.btn_share_transcoded)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.preview_activity_title)

        val inputUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI)
        val initialOutputUriString = intent.getStringExtra(EXTRA_OUTPUT_VIDEO_URI)
        val initialOutputMimeType = intent.getStringExtra(EXTRA_OUTPUT_MIME_TYPE)

        if (inputUriString == null) {
            Log.e(TAG, "No input_video_uri found in intent.")
            Toast.makeText(this, getString(R.string.error_input_video_not_specified), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        inputVideoUri = Uri.parse(inputUriString)
        Log.d(TAG, "Received input video URI: $inputVideoUri")

        settingsManager = SettingsManager(applicationContext) // Initialize SettingsManager

        loadThumbnail(inputVideoUri!!)
        displayVideoSize(inputVideoUri!!)
        calculateAndDisplayEstimatedOutputSize() // Initial calculation

        if (initialOutputUriString != null) {
            outputVideoUri = Uri.parse(initialOutputUriString)
            outputVideoMimeType = initialOutputMimeType ?: "video/mp4"
            isTranscodingComplete = true
            isTranscodingPreviouslyStarted = true
            textStatusInfo.text = getString(R.string.transcoding_complete_status)
            progressBarTranscoding.progress = 100
            imageThumbnail.alpha = 0f
            Log.d(TAG, "Activity started with already transcoded video URI: $outputVideoUri")
            btnShare.isEnabled = true
            videoControlsContainer.visibility = View.VISIBLE // Show container
            buttonPlay.isEnabled = true
            buttonPlay.visibility = View.VISIBLE
        } else {
            // Request permission before starting service
            checkAndRequestNotificationPermission()
            // TranscodingService.startTranscoding will be called by startTranscodingServiceAfterPermission()
            // if permission is granted or already available.
            // Set initial status. If permission is denied, this might be overwritten.
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                textStatusInfo.text = getString(R.string.starting_transcoding_status)
            } else {
                 textStatusInfo.text = getString(R.string.notification_permission_pending_status)
            }
            btnShare.isEnabled = false // Initially false until transcoding completes
            videoControlsContainer.visibility = View.GONE // Ensure container is hidden
            buttonPlay.isEnabled = false
            // buttonPlay.visibility = View.GONE already part of videoControlsContainer.visibility
        }

        // buttonPause.visibility = View.GONE // Already GONE in XML and part of videoControlsContainer

        btnShare.setOnClickListener { shareTranscodedVideo() }
        buttonPlay.setOnClickListener { playTranscodedVideo() }

        val intentFilter = IntentFilter().apply {
            addAction(TranscodingService.ACTION_TRANSCODING_PROGRESS)
            addAction(TranscodingService.ACTION_TRANSCODING_COMPLETE)
            addAction(TranscodingService.ACTION_TRANSCODING_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcodingReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcodingReceiver, intentFilter)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "POST_NOTIFICATIONS permission already granted.")
                    startTranscodingServiceAfterPermission()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show an explanation to the user *asynchronously*
                    // This is a good place to show a dialog explaining why the permission is needed.
                    // For this example, we'll log and proceed to request.
                    Log.i(TAG, "Showing rationale for POST_NOTIFICATIONS permission.")
                    // Here you would typically show a dialog. After dialog, user might trigger request again.
                    // For now, just request directly.
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // Directly request the permission
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Pre-Tiramisu, no runtime permission needed for notifications in this context
            Log.d(TAG, "No runtime POST_NOTIFICATIONS permission needed for this Android version.")
            startTranscodingServiceAfterPermission()
        }
    }

    private fun startTranscodingServiceAfterPermission() {
        if (inputVideoUri != null && !isTranscodingComplete) { // Check if not already completed
            Log.d(TAG, "Permission OK or not required. Starting TranscodingService.")
            textStatusInfo.text = getString(R.string.starting_transcoding_status)
            TranscodingService.startTranscoding(this, inputVideoUri!!)
        } else if (isTranscodingComplete) {
            Log.d(TAG, "Transcoding was already complete, not restarting service.")
        }
         else {
            Log.e(TAG, "Input URI is null, cannot start transcoding service.")
            // This case should ideally be prevented by the initial null check in onCreate
        }
    }

    // ... ( animateThumbnailFadeOut, playTranscodedVideo, shareTranscodedVideo, onDestroy, onOptionsItemSelected methods remain the same)

    // --- Start of methods for size display and estimation ---
    private fun loadThumbnail(videoUri: Uri) {
        Log.d(TAG, "Loading thumbnail for $videoUri")
        Glide.with(this)
            .asBitmap()
            .load(videoUri)
            // .placeholder(R.drawable.placeholder_image) // Consider adding actual placeholder/error drawables
            // .error(R.drawable.error_image)
            .into(imageThumbnail)
    }

    private fun displayVideoSize(videoUri: Uri) {
        var fileSize: Long = 0
        try {
            contentResolver.openFileDescriptor(videoUri, "r")?.use {
                fileSize = it.statSize
            }
            if (fileSize <= 0) { // Fallback for content URIs that don't report statSize well
                val cursor = contentResolver.query(videoUri, null, null, null, null)
                cursor?.use {
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && it.moveToFirst()) {
                        fileSize = it.getLong(sizeIndex)
                    }
                }
            }
            if (fileSize > 0) {
                textVideoSize.text = getString(R.string.input_size_prefix, formatFileSize(fileSize))
            } else {
                textVideoSize.text = getString(R.string.input_size_prefix, getString(R.string.size_not_available))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video size for $videoUri", e)
            textVideoSize.text = getString(R.string.input_size_prefix, getString(R.string.size_not_available))
        }
    }

    // Replicated and adapted from SettingsFragment/Manager for broader use if needed
    private fun formatFileSize(size: Long, isRate: Boolean = false): String {
        if (size <= 0) return "0 B" + if (isRate) "/min" else ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.size - 1)
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups] + if (isRate) "/min" else ""
    }

    // Replicated from SettingsFragment
    private fun getEstimatedVideoBitrate(resolution: String, quality: String, mimeType: String): Int {
        val targetPixels = COMMON_RESOLUTIONS[resolution]?.let { it.first * it.second }
            ?: (COMMON_RESOLUTIONS[SettingsManager.DEFAULT_RESOLUTION]?.let { it.first * it.second } ?: (1280 * 720))
        val base720pPixels = 1280 * 720

        var baseBitrate = when (mimeType) {
            "video/hevc" -> BASE_BITRATE_MEDIUM_720P_HEVC
            "video/vp9" -> BASE_BITRATE_MEDIUM_720P_VP9
            else -> BASE_BITRATE_MEDIUM_720P_AVC // Default to AVC for others like video/avc, video/mp4v-es
        }
        baseBitrate = when (quality) {
            "High" -> (baseBitrate * 1.8).toInt()
            "Low" -> (baseBitrate * 0.5).toInt()
            else -> baseBitrate // Medium
        }
        var estimatedBitrate = (baseBitrate * (targetPixels.toDouble() / base720pPixels)).toInt()

        // For simplicity, not using dynamic codec capabilities for bitrate range here in PreviewActivity
        // A broad general range as fallback:
        val videoBitrateRange = android.util.Range(250_000, 10_000_000) // Example range
        estimatedBitrate = estimatedBitrate.coerceIn(videoBitrateRange.lower, videoBitrateRange.upper)
        Log.d(TAG, "Estimated video bitrate for $resolution, $quality, $mimeType: $estimatedBitrate bps")
        return estimatedBitrate
    }

    private fun calculateAndDisplayEstimatedOutputSize() {
        val currentResolution = settingsManager.loadResolution()
        val currentFormat = settingsManager.loadFormat()
        val currentQuality = settingsManager.loadQuality()
        // val currentFrameRate = settingsManager.loadFrameRate() // Not directly used in current bitrate calc
        val currentAudioBitrateKbps = settingsManager.loadAudioBitrate()

        var displayResolution = currentResolution
        var note = ""

        if (currentResolution == "Original") {
            displayResolution = "1080p" // Calculate based on 1080p for "Original"
            note = " (1080p estimate)"
        }

        val videoBitrateBps = getEstimatedVideoBitrate(displayResolution, currentQuality, currentFormat)
        val audioBitrateBps = currentAudioBitrateKbps * 1000
        val totalBitrateBps = videoBitrateBps + audioBitrateBps
        val bytesPerMinute = (totalBitrateBps / 8.0 * 60).toLong()

        val formattedSizePerMinute = formatFileSize(bytesPerMinute, true) // true for "/min"

        // Using getString for formatting to ensure consistent spacing and potential localization
        textEstimatedOutputSize.text = getString(R.string.estimated_output_size_prefix_with_tilde, formattedSizePerMinute) + note
        Log.d(TAG, "Updated estimated output size: ${textEstimatedOutputSize.text}")
    }

    override fun onResume() {
        super.onResume()
        calculateAndDisplayEstimatedOutputSize() // Refresh on resume
    }

    // --- End of methods for size display and estimation ---

    private fun animateThumbnailFadeOut() {
        val fadeOut = ObjectAnimator.ofFloat(imageThumbnail, "alpha", 1f, 0f)
        fadeOut.duration = 750 // ms
        fadeOut.start()
    }

    private fun playTranscodedVideo() {
        if (outputVideoUri == null) {
            Toast.makeText(this, getString(R.string.video_not_ready_error), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val authority = "${applicationContext.packageName}.fileprovider"
            // Ensure URI is exposed via FileProvider if it's a file URI from cache
            val uriToPlay = if ("file" == outputVideoUri?.scheme) {
                try {
                    FileProvider.getUriForFile(this@PreviewActivity, authority, File(outputVideoUri!!.path!!))
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "FileProvider error for playing: ${e.message}")
                    Toast.makeText(this@PreviewActivity, getString(R.string.error_preparing_video_playback), Toast.LENGTH_LONG).show()
                    return
                }
            } else {
                outputVideoUri
            }
            setDataAndType(uriToPlay, outputVideoMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_to_play_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTranscodedVideo() {
        if (outputVideoUri == null) {
            Toast.makeText(this, getString(R.string.video_not_ready_for_sharing), Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "${applicationContext.packageName}.fileprovider"
        val shareUri: Uri
        try {
            // Assume outputVideoUri is a file URI from service's cache, always use FileProvider for sharing
            val videoFile = File(outputVideoUri!!.path!!) // Add null check for path if outputVideoUri can be non-file
            shareUri = FileProvider.getUriForFile(this, authority, videoFile)
        } catch (e: Exception) { // Catch more general exceptions like NullPointerException if path is null
            Log.e(TAG, "Error getting FileProvider URI for sharing: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_preparing_video_for_sharing), Toast.LENGTH_LONG).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = outputVideoMimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_video_title)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_to_share_video), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(transcodingReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

// Add necessary new string resources to res/values/strings.xml:
/*
<string name="notification_permission_denied_message">Notification permission denied. Transcoding status updates will not be shown.</string>
<string name="notification_permission_required_status">Notification permission is required to show transcoding progress. Please grant the permission.</string>
<string name="notification_permission_pending_status">Waiting for notification permission...</string>
<string name="size_not_available">N/A</string>
<string name="preview_activity_title">Preview &amp; Transcode</string>
<string name="starting_transcoding_status">Starting transcoding...</string>
<string name="transcoding_complete_status">Transcoding complete!</string>
<string name="error_output_file_not_found">Error: Output file not found.</string>
<string name="error_transcoding_message">Error during transcoding: %s</string>
<string name="unknown_error">An unknown error occurred.</string>
<string name="error_input_video_not_specified">Input video not specified.</string>
<string name="video_not_ready_error">Video is not ready.</string>
<string name="error_preparing_video_playback">Error preparing video for playback.</string>
<string name="no_app_to_play_video">No application found to play this video.</string>
<string name="video_not_ready_for_sharing">Video not ready for sharing.</string>
<string name="error_preparing_video_for_sharing">Error preparing video for sharing.</string>
<string name="no_app_to_share_video">No application found to share this video.</string>
<string name="share_video_title">Share Video Via</string>
*/
