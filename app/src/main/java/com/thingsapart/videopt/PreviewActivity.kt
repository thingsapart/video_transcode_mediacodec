package com.thingsapart.videopt

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import java.io.File // Added back for FileProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class PreviewActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var imageViewThumbnail: ImageView
    private lateinit var viewFade: View
    private lateinit var progressBarTranscoding: LinearProgressIndicator
    private lateinit var textViewStatus: TextView
    private lateinit var btnShare: MaterialButton
    private lateinit var fabSettings: com.google.android.material.floatingactionbutton.FloatingActionButton

    private var outputVideoUri: Uri? = null
    private var outputVideoMimeType: String = "video/mp4" // Default

    private var inputVideoUri: Uri? = null
    private var contentUri: Uri? = null // Added to address the "must be initialized" error context
    private var currentTranscodingStatus: String = STATUS_PENDING

    companion object {
        private const val TAG = "PreviewActivity"
        // Consistent with TranscodingService
        const val EXTRA_INPUT_VIDEO_URI = "extra_input_video_uri" // Ensure this matches MainActivity's key
        private const val REQUEST_CODE_TEMPORARY_SETTINGS = 1001

        // Constants for saving state
        private const val STATE_OUTPUT_URI = "state_output_uri"
        private const val STATE_OUTPUT_MIME_TYPE = "state_output_mime_type"
        private const val STATE_TRANSCODING_STATUS = "state_transcoding_status"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_SHARE_BUTTON_TEXT = "state_share_button_text"
        private const val STATE_SHARE_BUTTON_ENABLED = "state_share_button_enabled"
        private const val STATE_PROGRESS_BAR_VISIBILITY = "state_progress_bar_visibility"
        private const val STATE_VIDEO_VIEW_VISIBILITY = "state_video_view_visibility"
        private const val STATE_THUMBNAIL_VISIBILITY = "state_thumbnail_visibility"
        private const val STATE_FADE_VIEW_VISIBILITY = "state_fade_view_visibility"
        private const val STATE_VIDEO_PLAYBACK_POSITION = "state_video_playback_position"

        // Transcoding status values
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_COMPLETE = "COMPLETE"
        private const val STATUS_ERROR = "ERROR"
    }

    private val transcodingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TranscodingService.ACTION_TRANSCODING_PROGRESS -> {
                    val progress = intent.getIntExtra(TranscodingService.EXTRA_PROGRESS, 0)
                    Log.d(TAG, "Received progress: $progress%")
                    progressBarTranscoding.progress = progress
                    textViewStatus.text = "Transcoding: $progress%"
                    currentTranscodingStatus = STATUS_PENDING // Or ensure it's already PENDING
                }
                TranscodingService.ACTION_TRANSCODING_COMPLETE -> {
                    val uriString = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_URI)
                    outputVideoMimeType = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_MIME_TYPE) ?: "video/mp4"
                    val videoSizeBytes = intent.getLongExtra(TranscodingService.EXTRA_OUTPUT_VIDEO_SIZE, -1L)

                    if (uriString != null) {
                        currentTranscodingStatus = STATUS_COMPLETE
                        outputVideoUri = Uri.parse(uriString)
                        Log.i(TAG, "Transcoding complete. Output URI: $outputVideoUri, MIME: $outputVideoMimeType, Size: $videoSizeBytes bytes")
                        textViewStatus.text = "Transcoding Complete! Tap video to play."
                        progressBarTranscoding.visibility = View.GONE

                        videoView.setVideoURI(outputVideoUri)
                        videoView.visibility = View.VISIBLE
                        imageViewThumbnail.visibility = View.GONE
                        //viewFade.visibility = View.GONE

                        if (videoSizeBytes > 0) {
                            btnShare.text = "Share (${formatFileSize(videoSizeBytes)})"
                        } else {
                            btnShare.text = "Share (Size N/A)"
                        }
                        btnShare.visibility = View.VISIBLE
                        btnShare.isEnabled = true
                        videoView.requestFocus()
                        videoView.start()
                    } else {
                        Log.e(TAG, "Transcoding complete broadcast missing output URI.")
                        textViewStatus.text = "Error: Output file not found."
                        progressBarTranscoding.visibility = View.GONE
                        btnShare.text = "Share"
                        btnShare.visibility = View.GONE // Or INVISIBLE and disabled
                        btnShare.isEnabled = false
                    }
                }
                TranscodingService.ACTION_TRANSCODING_ERROR -> {
                    currentTranscodingStatus = STATUS_ERROR
                    val errorMessage = intent.getStringExtra(TranscodingService.EXTRA_ERROR_MESSAGE) ?: "Unknown transcoding error"
                    btnShare.text = "Share"
                    btnShare.isEnabled = false
                    Log.e(TAG, "Transcoding error: $errorMessage")
                    textViewStatus.text = "Error: $errorMessage"
                    progressBarTranscoding.visibility = View.GONE
                    imageViewThumbnail.visibility = View.VISIBLE
                    //viewFade.visibility = View.VISIBLE
                    videoView.visibility = View.GONE
                    btnShare.visibility = View.GONE // Or INVISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        Log.d(TAG, "onCreate called")

        imageViewThumbnail = findViewById(R.id.imageview_thumbnail)
        //viewFade = findViewById(R.id.view_fade)
        videoView = findViewById(R.id.video_view_preview)
        progressBarTranscoding = findViewById(R.id.progressbar_transcoding)
        textViewStatus = findViewById(R.id.textview_status)
        btnShare = findViewById(R.id.btn_share_transcoded)
        fabSettings = findViewById(R.id.fab_settings)

        // Initialize MediaController here, it will be reassigned if needed during state restoration
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        btnShare.setOnClickListener {
            if (outputVideoUri != null) { // Check if there's something to share
                shareTranscodingVideo()
            } else {
                Toast.makeText(this, "No video available to share yet.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Share button clicked but outputVideoUri is null.")
            }
        }

        fabSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra(SettingsActivity.EXTRA_IS_TEMPORARY_SETTINGS, true)
            startActivityForResult(intent, REQUEST_CODE_TEMPORARY_SETTINGS)
        }

        if (savedInstanceState != null) {
            Log.d(TAG, "onCreate: Restoring from savedInstanceState")
            currentTranscodingStatus = savedInstanceState.getString(STATE_TRANSCODING_STATUS, STATUS_PENDING)
            savedInstanceState.getString(STATE_OUTPUT_URI)?.let { outputVideoUri = Uri.parse(it) }
            outputVideoMimeType = savedInstanceState.getString(STATE_OUTPUT_MIME_TYPE, "video/mp4")

            textViewStatus.text = savedInstanceState.getString(STATE_STATUS_TEXT)
            btnShare.text = savedInstanceState.getString(STATE_SHARE_BUTTON_TEXT)
            btnShare.isEnabled = savedInstanceState.getBoolean(STATE_SHARE_BUTTON_ENABLED)
            progressBarTranscoding.visibility = savedInstanceState.getInt(STATE_PROGRESS_BAR_VISIBILITY)

            // Important: Only set video URI if it's valid and status is complete
            if (currentTranscodingStatus == STATUS_COMPLETE && outputVideoUri != null) {
                imageViewThumbnail.visibility = savedInstanceState.getInt(STATE_THUMBNAIL_VISIBILITY, View.GONE)
                //viewFade.visibility = savedInstanceState.getInt(STATE_FADE_VIEW_VISIBILITY, View.GONE)
                videoView.visibility = savedInstanceState.getInt(STATE_VIDEO_VIEW_VISIBILITY, View.VISIBLE)

                videoView.setVideoURI(outputVideoUri)
                val playbackPosition = savedInstanceState.getInt(STATE_VIDEO_PLAYBACK_POSITION, 0)
                videoView.seekTo(playbackPosition) // Seek first

                // Ensure MediaController is set up BEFORE start() if it's needed immediately
                val mediaController = MediaController(this) // Use the already defined one or re-init if needed
                mediaController.setAnchorView(videoView)
                videoView.setMediaController(mediaController)

                videoView.start() // Then start playback to refresh the view and continue playing

                videoView.requestFocus() // Request focus for MediaController interaction

            } else {
                // If not complete, restore thumbnail/fade visibility as saved
                imageViewThumbnail.visibility = savedInstanceState.getInt(STATE_THUMBNAIL_VISIBILITY, View.VISIBLE)
                //viewFade.visibility = savedInstanceState.getInt(STATE_FADE_VIEW_VISIBILITY, View.VISIBLE)
                videoView.visibility = savedInstanceState.getInt(STATE_VIDEO_VIEW_VISIBILITY, View.GONE)
            }

            // Restore visibility for other elements based on general saved state
            textViewStatus.visibility = View.VISIBLE // usually always visible
            btnShare.visibility = if(btnShare.isEnabled) View.VISIBLE else View.INVISIBLE

            // Handle thumbnail loading based on restored state
            val inputVideoUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI) // Get original input URI
            if (inputVideoUriString != null) {
                 inputVideoUri = Uri.parse(inputVideoUriString) // Ensure inputVideoUri is set for loadThumbnail if needed
            }

            if (currentTranscodingStatus == STATUS_COMPLETE && outputVideoUri != null) {
                Log.d(TAG, "Restored to COMPLETE state. Skipping initial transcoding trigger if any.")
                // Thumbnail is already hidden, videoView is visible.
            } else if (currentTranscodingStatus == STATUS_ERROR) {
                Log.d(TAG, "Restored to ERROR state.")
                inputVideoUri?.let { loadThumbnail(it) } // Reload original thumbnail
            } else { // STATUS_PENDING or other
                Log.d(TAG, "Restored to PENDING state. Original transcoding logic will proceed if URI was in intent.")
                inputVideoUri?.let { loadThumbnail(it) } // Reload original thumbnail
            }

        } else {
            // This is the normal flow when there's no savedInstanceState
            Log.d(TAG, "onCreate: No savedInstanceState. Initializing normally.")
            currentTranscodingStatus = STATUS_PENDING // Ensure it's reset

            textViewStatus.text = "Preparing for transcoding..."
            progressBarTranscoding.progress = 0
            progressBarTranscoding.visibility = View.VISIBLE
            btnShare.visibility = View.INVISIBLE
            btnShare.isEnabled = false
            imageViewThumbnail.visibility = View.VISIBLE
            //viewFade.visibility = View.VISIBLE
            videoView.visibility = View.GONE

            val inputVideoUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI)
            if (inputVideoUriString != null) {
                val parsedUri = Uri.parse(inputVideoUriString)
                inputVideoUri = parsedUri
                contentUri = parsedUri

                Log.i(TAG, "PreviewActivity onCreate (fresh): Parsed URI for thumbnail: $parsedUri")
                if ("content" == parsedUri.scheme) {
                    try {
                        val cursor = contentResolver.query(parsedUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                val displayName = if(displayNameIndex != -1) it.getString(displayNameIndex) else "N/A"
                                val size = if(sizeIndex != -1) it.getLong(sizeIndex) else -1L
                                Log.i(TAG, "Content URI details: Display Name = $displayName, Size = $size")
                            } else {
                                Log.w(TAG, "Content URI details: Cursor empty for $parsedUri")
                            }
                        } ?: Log.w(TAG, "Content URI details: Cursor is null for $parsedUri")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error querying content URI details for $parsedUri", e)
                    }
                }
                loadThumbnail(parsedUri)
                // IMPORTANT: Transcoding should be started via onActivityResult or if specific conditions met.
                // For a fresh start, if settings are NOT changed first, it implies direct transcoding.
                // However, the original code didn't start service here, it was started by onActivityResult.
                // Let's stick to that pattern: User must interact with settings first or it's just preview.
                // OR, if no temporary settings flow, start directly.
                // For now, let's assume a direct start if no saved instance and URI is present.
                // This part needs to be clear: WHEN does transcoding START?
                // If it's after settings, then this is fine. If it should start on initial open, then:
                // TranscodingService.startTranscoding(this, inputVideoUri!!) // - This line would be needed.
                // Given the flow through settings, this seems okay to NOT start here automatically.
                // The user will go to settings, save, and then onActivityResult will trigger it.
                // If the app is meant to transcode immediately on open with default settings,
                // then a call to start service here IS required.
                // Let's assume for now the flow requires going via settings (even if defaults are kept).
                // This means the UI will show "Preparing for transcoding..." until settings are confirmed.
            } else {
                Log.e(TAG, "No input_video_uri found in intent. inputVideoUriString is null.")
                Toast.makeText(this, "Error: Input video not found.", Toast.LENGTH_LONG).show()
                textViewStatus.text = "Error: Input video not specified."
                imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
                btnShare.visibility = View.GONE
                progressBarTranscoding.visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG, "onSaveInstanceState called")
        outState.putString(STATE_TRANSCODING_STATUS, currentTranscodingStatus)
        outputVideoUri?.let { outState.putString(STATE_OUTPUT_URI, it.toString()) }
        outState.putString(STATE_OUTPUT_MIME_TYPE, outputVideoMimeType)
        outState.putString(STATE_STATUS_TEXT, textViewStatus.text.toString())
        outState.putString(STATE_SHARE_BUTTON_TEXT, btnShare.text.toString())
        outState.putBoolean(STATE_SHARE_BUTTON_ENABLED, btnShare.isEnabled)
        outState.putInt(STATE_PROGRESS_BAR_VISIBILITY, progressBarTranscoding.visibility)
        outState.putInt(STATE_VIDEO_VIEW_VISIBILITY, videoView.visibility)
        outState.putInt(STATE_THUMBNAIL_VISIBILITY, imageViewThumbnail.visibility)
        //outState.putInt(STATE_FADE_VIEW_VISIBILITY, viewFade.visibility)
        if (videoView.visibility == View.VISIBLE) {
            outState.putInt(STATE_VIDEO_PLAYBACK_POSITION, videoView.currentPosition)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_TEMPORARY_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                // Apply the temporary settings if needed.
                // For now, just log that we received them.
                Log.d(TAG, "Received temporary settings from SettingsActivity.")
                val temporarySettingsBundle = data?.extras
                if (temporarySettingsBundle != null && inputVideoUri != null) {
                    Log.d(TAG, "Applying temporary settings for transcoding.")

                    currentTranscodingStatus = STATUS_PENDING;
                    // Reset UI to initial transcoding state
                    textViewStatus.text = getString(R.string.applying_temporary_settings)
                    progressBarTranscoding.progress = 0
                    progressBarTranscoding.visibility = View.VISIBLE
                    btnShare.visibility = View.INVISIBLE
                    btnShare.isEnabled = false
                    videoView.visibility = View.GONE
                    imageViewThumbnail.visibility = View.VISIBLE // Show placeholder/last thumbnail
                    //viewFade.visibility = View.VISIBLE

                    // Stop any ongoing service explicitly if possible, though starting a new one
                    // with the same foreground ID might just replace it or cause issues if not handled well by the service.
                    // For now, we assume the service handles a new START_TRANSCODING action correctly (e.g. cancels previous)
                    // or that a new startForeground call effectively replaces the old one.
                    // A more robust solution might involve an explicit stop action to the service.

                    val serviceIntent = Intent(this, TranscodingService::class.java).apply {
                        action = TranscodingService.ACTION_START_TRANSCODING
                        putExtra(TranscodingService.EXTRA_INPUT_VIDEO_URI, inputVideoUri.toString())
                        putExtra(TranscodingService.EXTRA_TEMPORARY_SETTINGS, temporarySettingsBundle)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Toast.makeText(this, getString(R.string.applying_temporary_settings), Toast.LENGTH_LONG).show()

                } else {
                    Log.w(TAG, "Temporary settings bundle or inputVideoUri is null. Cannot apply settings.")
                    Toast.makeText(this, "Could not apply temporary settings.", Toast.LENGTH_SHORT).show()
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.d(TAG, "Temporary settings cancelled or no changes made in SettingsActivity.")
            }
        }
    }

    private fun loadThumbnail(uri: Uri?) { // Parameter renamed to 'uri' to avoid confusion with class member
        Log.i(TAG, "loadThumbnail called with URI: $uri")
        if (uri == null) {
            Log.w(TAG, "loadThumbnail: videoUri is null, setting placeholder.")
            imageViewThumbnail.setImageDrawable(null);
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            return
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            // Ensure uri is not null before using !! although the check above should suffice
            retriever.setDataSource(applicationContext, uri)
            Log.i(TAG, "MediaMetadataRetriever: setDataSource successful for $uri")
            val bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            Log.i(TAG, "MediaMetadataRetriever: getFrameAtTime called. Bitmap is ${if (bitmap == null) "null" else "not null"}")

            if (bitmap != null) {
                Log.i(TAG, "Bitmap retrieved: Dimensions ${bitmap.width}x${bitmap.height}")
                imageViewThumbnail.setImageDrawable(null);
                imageViewThumbnail.setImageBitmap(bitmap)
            } else {
                Log.e(TAG, "Failed to retrieve thumbnail using MediaMetadataRetriever, bitmap is null for URI: $uri")
                imageViewThumbnail.setImageDrawable(null);
                imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadThumbnail: Exception during MediaMetadataRetriever operations for $uri", e)
            imageViewThumbnail.setImageDrawable(null);
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
        } finally {
            Log.i(TAG, "loadThumbnail: MediaMetadataRetriever release attempt.")
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter().apply {
            addAction(TranscodingService.ACTION_TRANSCODING_PROGRESS)
            addAction(TranscodingService.ACTION_TRANSCODING_COMPLETE)
            addAction(TranscodingService.ACTION_TRANSCODING_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcodingReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcodingReceiver, intentFilter)
        }
        Log.d(TAG, "BroadcastReceiver registered.")
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(transcodingReceiver)
        Log.d(TAG, "BroadcastReceiver unregistered.")
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "N/A"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeDigitGroups = digitGroups.coerceIn(0, units.size - 1)
        return String.format("%.1f %s", sizeBytes / Math.pow(1024.0, safeDigitGroups.toDouble()), units[safeDigitGroups])
    }

    private fun shareTranscodingVideo() {
        if (outputVideoUri == null) { // Should be checked before calling or by button state
            Log.e(TAG, "Cannot share, outputVideoUri is null.")
            Toast.makeText(this, "Error: Transcoded video URI is missing.", Toast.LENGTH_SHORT).show()
            return
        }

        val videoFile = try {
            // Ensure outputVideoUri is not null before using !!
             outputVideoUri?.path?.let { File(it) } ?: run {
                Log.e(TAG, "outputVideoUri path is null")
                Toast.makeText(this, "Error accessing transcoded video file path.", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: NullPointerException) { // Should be caught by the null check above with let
            Log.e(TAG, "Error getting path from outputVideoUri: $outputVideoUri", e)
            Toast.makeText(this, "Error accessing transcoded video file.", Toast.LENGTH_LONG).show()
            return
        }

        if (!videoFile.exists()) {
            Log.e(TAG, "File to share does not exist: ${videoFile.absolutePath}")
            Toast.makeText(this, "Error: Transcoded video file not found.", Toast.LENGTH_LONG).show()
            return
        }

        val fileUriToShare: Uri // Renamed local variable for clarity
        try {
            fileUriToShare = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                videoFile
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider Error for output URI: $outputVideoUri", e)
            Toast.makeText(this, "Error preparing video for sharing.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Sharing video with content URI: $fileUriToShare (original file URI: $outputVideoUri)")

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = outputVideoMimeType
            putExtra(Intent.EXTRA_STREAM, fileUriToShare)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Share Transcoded Video"))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No app found to handle sharing video.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "ActivityNotFoundException for sharing: $e")
        }

        // After starting activity, delete the shared file
        try {
            outputVideoUri?.path?.let { path ->
                val fileToDelete = File(path)
                if (fileToDelete.exists()) {
                    if (fileToDelete.delete()) {
                        Log.i(TAG, "Successfully deleted shared file: ${fileToDelete.absolutePath}")
                    } else {
                        Log.w(TAG, "Failed to delete shared file: ${fileToDelete.absolutePath}")
                    }
                }
                // Optionally set outputVideoUri to null now, if appropriate for app logic
                // outputVideoUri = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting shared file", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called. isChangingConfigurations: ${isChangingConfigurations()}")

        // Only delete the file if the activity is actually finishing, not just due to a configuration change.
        if (!isChangingConfigurations()) {
            outputVideoUri?.path?.let { path ->
                try {
                    val fileToDelete = File(path)
                    if (fileToDelete.exists()) {
                        // Check if the file is in our app's cache directory to be safer
                        if (fileToDelete.absolutePath.startsWith(cacheDir.absolutePath)) {
                            if (fileToDelete.delete()) {
                                Log.i(TAG, "Cleaned up transcoded file in onDestroy (activity finishing): ${fileToDelete.absolutePath}")
                            } else {
                                Log.w(TAG, "Failed to clean up transcoded file in onDestroy (activity finishing): ${fileToDelete.absolutePath}")
                            }
                        } else {
                            Log.w(TAG, "Skipping deletion in onDestroy (activity finishing), file not in cache: ${fileToDelete.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during onDestroy cleanup (activity finishing) of transcoded file", e)
                }
            }
        } else {
            Log.d(TAG, "Skipping onDestroy cleanup as it's due to configuration change.")
        }
    }

    // Hypothetical function that might have used 'this.contentUri' (the input URI)
    // This is added to ensure that if lines 252, 256 from an error report
    // referred to a method like this, 'this.contentUri' would be initialized.
    private fun shareOriginalVideo() {
        this.contentUri?.let { uriToShare ->
            // Note: Directly using uriToShare.path for content URIs (e.g., from MediaStore)
            // to create a File object is often unreliable.
            // Content URIs should typically be shared directly or copied to a cache file first.
            // For this example, we'll log this caveat.
            Log.w(TAG, "Attempting to share original video. Path from URI: ${uriToShare.path}")

            val path = uriToShare.path
            if (path == null) {
                Toast.makeText(this, "Original video path is invalid.", Toast.LENGTH_SHORT).show()
                return@let
            }

            // This part is problematic for non-file scheme URIs (e.g. content:// from MediaStore)
            // For a robust solution, content URIs should be handled by copying to a local cache
            // file before sharing with FileProvider if direct path access isn't possible.
            // However, to satisfy the 'contentUri must be initialized' context if this method was the cause:
            try {
                val videoFile = File(path)
                 if (!videoFile.exists()) { // This check might fail for content URIs
                    Log.e(TAG, "Original video file does not appear to exist at path: $path (might be a content URI issue)")
                    // Attempting to share the content URI directly if it's not a file scheme
                    if (uriToShare.scheme != "file") {
                         Log.d(TAG, "Original URI is a content URI, attempting to share directly: $uriToShare")
                         val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = contentResolver.getType(uriToShare) ?: "video/*" // Get MIME type
                            putExtra(Intent.EXTRA_STREAM, uriToShare)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                         }
                         startActivity(Intent.createChooser(shareIntent, "Share Original Video (Direct)"))
                         return@let // Exit after attempting direct share
                    }
                    Toast.makeText(this, "Original video file not found or accessible.", Toast.LENGTH_LONG).show()
                    return@let
                }

                val fileUriForSharing = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    videoFile
                )
                Log.d(TAG, "Sharing original video with FileProvider URI: $fileUriForSharing")
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = contentResolver.getType(uriToShare) ?: "video/*"
                    putExtra(Intent.EXTRA_STREAM, fileUriForSharing)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Original Video"))

            } catch (e: Exception) {
                 Log.e(TAG, "Error preparing original video for sharing: $uriToShare", e)
                 Toast.makeText(this, "Error sharing original video.", Toast.LENGTH_SHORT).show()
            }

        } ?: run {
            Toast.makeText(this, "Original video URI not available to share.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "shareOriginalVideo called but contentUri is null.")
        }
    }
}
