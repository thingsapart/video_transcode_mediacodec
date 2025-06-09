package com.thingsapart.videopt

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

class MainActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: Button

    private var pendingVideoUriForTranscoding: Uri? = null
    // No longer need a separate receiver in MainActivity if PreviewActivity handles all post-transcoding UI
    // private val transcodingReceiver = object : BroadcastReceiver() { ... } // Removed for this fix

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
            // BroadcastReceiver registration removed from here
            Log.d(TAG, "Handling share intent.")
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
            this.pendingVideoUriForTranscoding = videoUri
            // Start PreviewActivity and TranscodingService immediately
            if (this.pendingVideoUriForTranscoding != null) {
                tvStatus.text = "Received video: $videoUri - Launching preview and transcoding..."
                Log.d(TAG, "ACTION_SEND: Received video URI: $videoUri. Starting PreviewActivity and TranscodingService.")

                val previewIntent = Intent(this, PreviewActivity::class.java).apply {
                    putExtra(PreviewActivity.EXTRA_INPUT_VIDEO_URI, this@MainActivity.pendingVideoUriForTranscoding.toString())
                    // Optionally, pass original MIME type if available and needed by PreviewActivity for the original
                    // intent.data?.let { originalUri ->
                    //    putExtra("original_mime_type", contentResolver.getType(originalUri))
                    // }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Or remove if not needed from Activity context
                }
                startActivity(previewIntent)

                TranscodingService.startTranscoding(this, this.pendingVideoUriForTranscoding!!)
                this.pendingVideoUriForTranscoding = null // Clear after starting
            } else {
                // This case should ideally not be hit if videoUri was non-null above
                tvStatus.text = "Error: Video URI became null before processing."
                Log.e(TAG, "ACTION_SEND: pendingVideoUriForTranscoding is null after being set.")
            }
        } else {
            tvStatus.text = "Error: No video URI found in share intent."
            Log.e(TAG, "ACTION_SEND: Video URI is null")
            Toast.makeText(this, "Could not get video from Share intent", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // BroadcastReceiver unregistration removed as receiver is removed
    }

    override fun onResume() {
        super.onResume()
        // Logic from onResume that started transcoding is moved to handleShareIntent or onCreate
        // to ensure PreviewActivity is launched alongside service start.
        Log.d(TAG, "onResume called.")
    }
}
