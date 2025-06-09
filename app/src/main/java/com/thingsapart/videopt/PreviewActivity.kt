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

    private var outputVideoUri: Uri? = null
    private var outputVideoMimeType: String = "video/mp4" // Default

    private var inputVideoUri: Uri? = null
    private var contentUri: Uri? = null // Added to address the "must be initialized" error context

    companion object {
        private const val TAG = "PreviewActivity"
        // Consistent with TranscodingService
        const val EXTRA_INPUT_VIDEO_URI = "extra_input_video_uri" // Ensure this matches MainActivity's key
    }

    private val transcodingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TranscodingService.ACTION_TRANSCODING_PROGRESS -> {
                    val progress = intent.getIntExtra(TranscodingService.EXTRA_PROGRESS, 0)
                    Log.d(TAG, "Received progress: $progress%")
                    progressBarTranscoding.progress = progress
                    textViewStatus.text = "Transcoding: $progress%"
                }
                TranscodingService.ACTION_TRANSCODING_COMPLETE -> {
                    val uriString = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_URI)
                    outputVideoMimeType = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_MIME_TYPE) ?: "video/mp4"
                    val videoSizeBytes = intent.getLongExtra(TranscodingService.EXTRA_OUTPUT_VIDEO_SIZE, -1L)

                    if (uriString != null) {
                        outputVideoUri = Uri.parse(uriString)
                        Log.i(TAG, "Transcoding complete. Output URI: $outputVideoUri, MIME: $outputVideoMimeType, Size: $videoSizeBytes bytes")
                        textViewStatus.text = "Transcoding Complete! Tap video to play."
                        progressBarTranscoding.visibility = View.GONE

                        videoView.setVideoURI(outputVideoUri)
                        videoView.visibility = View.VISIBLE
                        imageViewThumbnail.visibility = View.GONE
                        viewFade.visibility = View.GONE

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
                    val errorMessage = intent.getStringExtra(TranscodingService.EXTRA_ERROR_MESSAGE) ?: "Unknown transcoding error"
                    btnShare.text = "Share"
                    btnShare.isEnabled = false
                    Log.e(TAG, "Transcoding error: $errorMessage")
                    textViewStatus.text = "Error: $errorMessage"
                    progressBarTranscoding.visibility = View.GONE
                    imageViewThumbnail.visibility = View.VISIBLE
                    viewFade.visibility = View.VISIBLE
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
        viewFade = findViewById(R.id.view_fade)
        videoView = findViewById(R.id.video_view_preview)
        progressBarTranscoding = findViewById(R.id.progressbar_transcoding)
        textViewStatus = findViewById(R.id.textview_status)
        btnShare = findViewById(R.id.btn_share_transcoded)

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        textViewStatus.text = "Preparing for transcoding..."
        progressBarTranscoding.progress = 0
        progressBarTranscoding.visibility = View.VISIBLE
        btnShare.visibility = View.INVISIBLE // Keep layout space, but not interactive
        btnShare.isEnabled = false
        imageViewThumbnail.visibility = View.VISIBLE
        viewFade.visibility = View.VISIBLE
        videoView.visibility = View.GONE

        val inputVideoUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI)
        if (inputVideoUriString != null) {
            val parsedUri = Uri.parse(inputVideoUriString)
            inputVideoUri = parsedUri
            contentUri = parsedUri // Initialize the contentUri class member
            Log.d(TAG, "Received input video URI: $inputVideoUri (also as contentUri)")
            loadThumbnail(inputVideoUri)
        } else {
            Log.e(TAG, "No input_video_uri found in intent.")
            Toast.makeText(this, "Error: Input video not found.", Toast.LENGTH_LONG).show()
            textViewStatus.text = "Error: Input video not specified."
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video) // Show placeholder
            // Disable share button and hide progress as transcoding can't start
            btnShare.visibility = View.GONE
            progressBarTranscoding.visibility = View.GONE
            // Optionally finish the activity if input URI is critical for all functions
            // finish()
            // return // Not strictly needed if finish() is called, but good practice.
        }

        btnShare.setOnClickListener {
            if (outputVideoUri != null) { // Check if there's something to share
                shareTranscodingVideo()
            } else {
                // This case should ideally not be reached if button is only enabled when outputVideoUri is set
                Toast.makeText(this, "No video available to share yet.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Share button clicked but outputVideoUri is null.")
            }
        }
    }

    private fun loadThumbnail(uri: Uri?) { // Parameter renamed to 'uri' to avoid confusion with class member
        if (uri == null) {
            Log.e(TAG, "Cannot load thumbnail, videoUri is null.")
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            return
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(applicationContext, uri)
            val bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (bitmap != null) {
                imageViewThumbnail.setImageBitmap(bitmap)
                Log.d(TAG, "Thumbnail loaded successfully into ImageView using MediaMetadataRetriever.")
            } else {
                Log.e(TAG, "Failed to retrieve thumbnail using MediaMetadataRetriever, bitmap is null for URI: $uri")
                imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading thumbnail with MediaMetadataRetriever for URI: $uri", e)
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
        } finally {
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
