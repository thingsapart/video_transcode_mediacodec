package com.thingsapart.videopt

import android.animation.ObjectAnimator
import android.content.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        const val EXTRA_INPUT_VIDEO_URI = "input_video_uri"
        const val EXTRA_OUTPUT_VIDEO_URI = "output_video_uri" // Optional output URI
        const val EXTRA_OUTPUT_MIME_TYPE = "output_video_mime_type" // Optional output MIME
    }

    private lateinit var imageThumbnail: ImageView
    private lateinit var progressBarTranscoding: ProgressBar
    private lateinit var textStatusInfo: TextView
    private lateinit var textVideoSize: TextView
    private lateinit var buttonPlay: ImageButton
    private lateinit var buttonPause: ImageButton // Retained for future use
    private lateinit var btnShare: Button
    // private lateinit var thumbnailContainer: FrameLayout // Not strictly needed for simple alpha

    private var inputVideoUri: Uri? = null
    private var outputVideoUri: Uri? = null
    private var outputVideoMimeType: String = "video/mp4"

    private var isTranscodingComplete = false
    private var isTranscodingPreviouslyStarted = false // To handle fade only once

    private val transcodingReceiver = object : BroadcastReceiver() {
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
                        textStatusInfo.text = getString(R.string.transcoding_complete_status) // Using string resource
                        progressBarTranscoding.progress = 100
                        isTranscodingComplete = true
                        btnShare.isEnabled = true
                        buttonPlay.visibility = View.VISIBLE
                        buttonPlay.isEnabled = true
                        imageThumbnail.alpha = 0f // Ensure thumbnail is hidden
                    } else {
                        Log.e(TAG, "Transcoding complete but output URI is null.")
                        textStatusInfo.text = getString(R.string.error_output_file_not_found)
                        btnShare.isEnabled = false
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
                    buttonPlay.isEnabled = false
                    imageThumbnail.alpha = 1f // Show thumbnail again if error during transcoding
                    isTranscodingPreviouslyStarted = false // Reset fade state
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
        buttonPlay = findViewById(R.id.button_play)
        buttonPause = findViewById(R.id.button_pause)
        btnShare = findViewById(R.id.btn_share_transcoded)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.preview_activity_title) // Using string resource

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

        if (initialOutputUriString != null) {
            outputVideoUri = Uri.parse(initialOutputUriString)
            outputVideoMimeType = initialOutputMimeType ?: "video/mp4"
            isTranscodingComplete = true
            isTranscodingPreviouslyStarted = true // Assume fade happened if output is already there
            textStatusInfo.text = getString(R.string.transcoding_complete_status)
            progressBarTranscoding.progress = 100
            imageThumbnail.alpha = 0f
            Log.d(TAG, "Activity started with already transcoded video URI: $outputVideoUri")
        } else {
            textStatusInfo.text = getString(R.string.starting_transcoding_status)
            TranscodingService.startTranscoding(this, inputVideoUri!!)
        }

        loadThumbnail(inputVideoUri!!)
        displayVideoSize(inputVideoUri!!)

        btnShare.isEnabled = isTranscodingComplete
        buttonPlay.isEnabled = isTranscodingComplete
        buttonPlay.visibility = if (isTranscodingComplete) View.VISIBLE else View.GONE
        buttonPause.visibility = View.GONE // Not used for now

        btnShare.setOnClickListener { shareTranscodedVideo() }
        buttonPlay.setOnClickListener { playTranscodedVideo() }

        val intentFilter = IntentFilter().apply {
            addAction(TranscodingService.ACTION_TRANSCODING_PROGRESS)
            addAction(TranscodingService.ACTION_TRANSCODING_COMPLETE)
            addAction(TranscodingService.ACTION_TRANSCODING_ERROR)
        }
        // For Android 13 (API 33) and above, need to specify receiver export status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcodingReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcodingReceiver, intentFilter)
        }
    }

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
                textVideoSize.text = formatFileSize(fileSize)
            } else {
                textVideoSize.text = getString(R.string.size_not_available)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video size for $videoUri", e)
            textVideoSize.text = getString(R.string.size_not_available)
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.size - 1)
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

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
                // If transcoding is in progress, ask user for confirmation? Or stop transcoding?
                // For now, just navigate up.
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

// Add necessary string resources to res/values/strings.xml:
/*
<string name="preview_activity_title">Preview &amp; Transcode</string>
<string name="transcoding_complete_status">Transcoding complete!</string>
<string name="error_output_file_not_found">Error: Output file not found.</string>
<string name="error_transcoding_message">Error: %s</string>
<string name="unknown_error">Unknown error</string>
<string name="error_input_video_not_specified">Error: Input video not specified.</string>
<string name="starting_transcoding_status">Starting transcoding…</string>
<string name="size_not_available">Size N/A</string>
<string name="video_not_ready_error">Video not ready or error in transcoding.</string>
<string name="no_app_to_play_video">No app found to play this video.</string>
<string name="video_not_ready_for_sharing">Video is not ready for sharing.</string>
<string name="error_preparing_video_for_sharing">Error preparing video for sharing.</string>
<string name="share_video_title">Share Transcoded Video</string>
<string name="no_app_to_share_video">No app found to handle sharing video.</string>
<string name="error_preparing_video_playback">Error preparing video for playback.</string>
*/
