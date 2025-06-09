package com.example.videotranscoder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.videotranscoder.R

class MainActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: Button

    private val transcodingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TranscodingService.ACTION_TRANSCODING_COMPLETE -> {
                    val outputUriString = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_URI)
                    val outputMimeType = intent.getStringExtra(TranscodingService.EXTRA_OUTPUT_MIME_TYPE) ?: "video/mp4" // Fallback

                    tvStatus.text = "Transcoding Complete!"
                    Toast.makeText(applicationContext, "Transcoding Complete!", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Transcoding Complete. Output: $outputUriString, MIME: $outputMimeType")
                    if (outputUriString != null) {
                        val previewIntent = Intent(this@MainActivity, PreviewActivity::class.java).apply {
                            putExtra("transcoded_video_uri", outputUriString)
                            putExtra("transcoded_video_mime_type", outputMimeType)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(previewIntent)
                    }
                }
                TranscodingService.ACTION_TRANSCODING_ERROR -> {
                    val errorMessage = intent.getStringExtra(TranscodingService.EXTRA_ERROR_MESSAGE)
                    tvStatus.text = "Transcoding Error: $errorMessage"
                    Toast.makeText(applicationContext, "Transcoding Error: $errorMessage", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Transcoding Error: $errorMessage")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called. Intent: $intent")

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            setContentView(R.layout.activity_main)
            tvStatus = findViewById(R.id.tv_hello)
            btnSettings = findViewById(R.id.btn_go_to_settings)
            btnSettings.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            val intentFilter = IntentFilter().apply {
                addAction(TranscodingService.ACTION_TRANSCODING_COMPLETE)
                addAction(TranscodingService.ACTION_TRANSCODING_ERROR)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(transcodingReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(transcodingReceiver, intentFilter)
            }
            Log.d(TAG, "BroadcastReceiver registered for share intent flow.")
            handleShareIntent(intent)
        } else {
            Log.d(TAG, "Direct launch or unknown intent. Navigating to SettingsActivity.")
            val settingsIntent = Intent(this, SettingsActivity::class.java)
            startActivity(settingsIntent)
            finish()
            return
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

        if (videoUri != null) {
            tvStatus.text = "Received video: $videoUri - Starting transcoding..."
            Log.d(TAG, "ACTION_SEND: Received video URI: $videoUri")
            TranscodingService.startTranscoding(applicationContext, videoUri)
        } else {
            tvStatus.text = "Error: No video URI found in share intent."
            Log.e(TAG, "ACTION_SEND: Video URI is null")
            Toast.makeText(this, "Could not get video from Share intent", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            try {
                unregisterReceiver(transcodingReceiver)
                Log.d(TAG, "BroadcastReceiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered or already unregistered: $e")
            }
        }
    }
}
