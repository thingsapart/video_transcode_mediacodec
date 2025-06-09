package com.example.videotranscoder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.example.videotranscoder.R
import java.io.File

class PreviewActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var btnShare: Button
    private var transcodedVideoUri: Uri? = null
    private var transcodedVideoMimeType: String = "video/mp4" // Default

    companion object {
        private const val TAG = "PreviewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        Log.d(TAG, "onCreate called")

        videoView = findViewById(R.id.video_view_preview)
        btnShare = findViewById(R.id.btn_share_transcoded)

        val videoUriString = intent.getStringExtra("transcoded_video_uri")
        // Retrieve the MIME type passed from MainActivity
        transcodedVideoMimeType = intent.getStringExtra("transcoded_video_mime_type") ?: "video/mp4"
        Log.d(TAG, "Received video URI: $videoUriString, MIME Type: $transcodedVideoMimeType")


        if (videoUriString != null) {
            transcodedVideoUri = Uri.parse(videoUriString)
            videoView.setVideoURI(transcodedVideoUri)
            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)
            videoView.setOnPreparedListener {
                Log.d(TAG, "VideoView prepared. Starting playback.")
                videoView.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "VideoView error: What - $what, Extra - $extra. URI: $transcodedVideoUri")
                Toast.makeText(this, "Error playing video.", Toast.LENGTH_LONG).show()
                true
            }
        } else {
            Log.e(TAG, "No transcoded_video_uri found in intent.")
            Toast.makeText(this, "Error: Video not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        btnShare.setOnClickListener {
            shareTranscodedVideo()
        }
    }

    private fun shareTranscodedVideo() {
        if (transcodedVideoUri == null) {
            Log.e(TAG, "Cannot share, transcodedVideoUri is null.")
            Toast.makeText(this, "Error: Video URI is missing for sharing.", Toast.LENGTH_SHORT).show()
            return
        }

        if (transcodedVideoUri?.scheme == "file") {
            val videoFile = File(transcodedVideoUri!!.path!!)
            val contentUri: Uri
            try {
                contentUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    videoFile
                )
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "FileProvider Error: Could not get URI for file. Check file path and provider authorities.", e)
                Toast.makeText(this, "Error preparing video for sharing.", Toast.LENGTH_LONG).show()
                return
            }

            Log.d(TAG, "Sharing video with content URI: $contentUri (original file URI: $transcodedVideoUri)")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = transcodedVideoMimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(shareIntent, "Share Transcoded Video"))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle sharing video.", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "ActivityNotFoundException for sharing: $e")
            }

        } else {
            Log.w(TAG, "Cannot share video with URI scheme: ${transcodedVideoUri?.scheme}. Expected 'file' scheme for FileProvider.")
            Toast.makeText(this, "Cannot share this type of video URI.", Toast.LENGTH_SHORT).show()
        }
    }
}
