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
// Remove unused MediaStore import if ThumbnailUtils specific parts are removed.
// import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
// Remove unused File and IOException if they are only for ThumbnailUtils.createVideoThumbnailFile
// import java.io.File
// import java.io.IOException

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

    companion object {
        private const val TAG = "PreviewActivity"
        // Consistent with TranscodingService
        const val EXTRA_INPUT_VIDEO_URI = "extra_input_video_uri"
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
                        textViewStatus.text = "Transcoding Complete! Tap video to play." // Or "Tap to play"
                        progressBarTranscoding.visibility = View.GONE // Hide progress bar

                        // Make VideoView visible and hide thumbnail
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
                        videoView.requestFocus() // Request focus for MediaController
                        videoView.start() // Start playback immediately
                    } else {
                        Log.e(TAG, "Transcoding complete broadcast missing output URI.")
                        textViewStatus.text = "Error: Output file not found."
                        progressBarTranscoding.visibility = View.GONE
                            btnShare.text = "Share" // Reset button text
                        btnShare.visibility = View.GONE
                    }
                }
                TranscodingService.ACTION_TRANSCODING_ERROR -> {
                    val errorMessage = intent.getStringExtra(TranscodingService.EXTRA_ERROR_MESSAGE) ?: "Unknown transcoding error"
                        btnShare.text = "Share" // Reset button text
                    Log.e(TAG, "Transcoding error: $errorMessage")
                    textViewStatus.text = "Error: $errorMessage"
                    progressBarTranscoding.visibility = View.GONE
                    imageViewThumbnail.visibility = View.VISIBLE // Keep thumbnail visible on error
                    viewFade.visibility = View.VISIBLE
                    videoView.visibility = View.GONE
                    btnShare.visibility = View.GONE
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

        // Setup MediaController
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        // Initialize UI state
        textViewStatus.text = "Preparing for transcoding..."
        progressBarTranscoding.progress = 0
        progressBarTranscoding.visibility = View.VISIBLE
        btnShare.visibility = View.INVISIBLE
        imageViewThumbnail.visibility = View.VISIBLE
        viewFade.visibility = View.VISIBLE
        videoView.visibility = View.GONE


        val inputVideoUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI)
        if (inputVideoUriString != null) {
            inputVideoUri = Uri.parse(inputVideoUriString)
            Log.d(TAG, "Received input video URI: $inputVideoUri")
            loadThumbnail(inputVideoUri)
            // Start transcoding service (MainActivity usually does this, but for testing or direct launch)
            // TranscodingService.startTranscoding(this, inputVideoUri!!)
        } else {
            Log.e(TAG, "No input_video_uri found in intent.")
            Toast.makeText(this, "Error: Input video not found.", Toast.LENGTH_LONG).show()
            textViewStatus.text = "Error: Input video not specified."
            finish()
            return
        }

        btnShare.setOnClickListener {
            shareTranscodingVideo()
        }
    }

    private fun loadThumbnail(videoUri: Uri?) {
        if (videoUri == null) {
            Log.e(TAG, "Cannot load thumbnail, videoUri is null.")
            imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            return
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(applicationContext, videoUri)
            // Get a frame at an arbitrary time, -1 gets the first available frame, OPTION_CLOSEST_SYNC is usually good.
            val bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (bitmap != null) {
                imageViewThumbnail.setImageBitmap(bitmap)
                Log.d(TAG, "Thumbnail loaded successfully into ImageView using MediaMetadataRetriever.")
            } else {
                Log.e(TAG, "Failed to retrieve thumbnail using MediaMetadataRetriever, bitmap is null for URI: $videoUri")
                imageViewThumbnail.setImageResource(R.drawable.ic_placeholder_video)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading thumbnail with MediaMetadataRetriever for URI: $videoUri", e)
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
        // Refresh progress in case activity was paused and resumed during transcoding
        // This would require the service to perhaps resend last progress or query service state.
        // For now, relies on new broadcasts.
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
        // Ensure digitGroups is within the bounds of the units array
        val safeDigitGroups = digitGroups.coerceIn(0, units.size - 1)
        return String.format("%.1f %s", sizeBytes / Math.pow(1024.0, safeDigitGroups.toDouble()), units[safeDigitGroups])
    }

    private fun shareTranscodingVideo() {
        if (outputVideoUri == null) {
            Log.e(TAG, "Cannot share, outputVideoUri is null.")
            Toast.makeText(this, "Error: Transcoded video URI is missing.", Toast.LENGTH_SHORT).show()
            return
        }

        // Assuming outputVideoUri is a file URI from the service's cache.
        // If the service provides a content URI directly, this logic might need adjustment.
        val videoFile = try {
            File(outputVideoUri!!.path!!)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Error getting path from outputVideoUri: $outputVideoUri", e)
            Toast.makeText(this, "Error accessing transcoded video file.", Toast.LENGTH_LONG).show()
            return
        }


        val contentUri: Uri
        try {
            contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                videoFile
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider Error for output URI: $outputVideoUri", e)
            Toast.makeText(this, "Error preparing video for sharing.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Sharing video with content URI: $contentUri (original file URI: $outputVideoUri)")

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = outputVideoMimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Share Transcoded Video"))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No app found to handle sharing video.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "ActivityNotFoundException for sharing: $e")
        }
    }
}
