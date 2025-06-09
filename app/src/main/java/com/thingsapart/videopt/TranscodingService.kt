package com.thingsapart.videopt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscodingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    private lateinit var settingsManager: SettingsManager

    companion object {
        const val TAG = "TranscodingService"
        const val ACTION_START_TRANSCODING = "com.example.videotranscoder.action.START_TRANSCODING"
        const val EXTRA_INPUT_VIDEO_URI = "extra_input_video_uri"

        const val NOTIFICATION_CHANNEL_ID = "TranscodingChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_TRANSCODING_PROGRESS = "com.example.videotranscoder.action.TRANSCODING_PROGRESS" // New
        const val ACTION_TRANSCODING_COMPLETE = "com.example.videotranscoder.action.TRANSCODING_COMPLETE"
        const val ACTION_TRANSCODING_ERROR = "com.example.videotranscoder.action.TRANSCODING_ERROR"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_OUTPUT_MIME_TYPE = "extra_output_mime_type" // New
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_PROGRESS = "extra_progress" // New

        fun startTranscoding(context: Context, inputVideoUri: Uri) {
            val intent = Intent(context, TranscodingService::class.java).apply {
                action = ACTION_START_TRANSCODING
                putExtra(EXTRA_INPUT_VIDEO_URI, inputVideoUri.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_TRANSCODING) {
            val inputVideoUriString = intent.getStringExtra(EXTRA_INPUT_VIDEO_URI)
            if (inputVideoUriString == null) {
                Log.e(TAG, "Missing input URI. Stopping service.")
                sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = "Input video URI missing.")
                stopSelf(); return START_NOT_STICKY
            }

            val inputVideoUri = Uri.parse(inputVideoUriString)
            val outputFileName = "transcoded_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4" // Default to mp4 extension
            val outputDir = File(cacheDir, "transcoded_videos")
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputVideoPath = File(outputDir, outputFileName).absolutePath

            Log.d(TAG, "Starting transcoding for: $inputVideoUri to $outputVideoPath")
            startForeground(NOTIFICATION_ID, createNotification("Preparing to transcode...", 0))

            serviceScope.launch {
                var finalOutputMimeType: String? = null
                try {
                    val targetVideoMimeUserSetting = settingsManager.loadFormat()
                    val targetResolutionStr = settingsManager.loadResolution()
                    val targetQuality = settingsManager.loadQuality()
                    val targetFrameRate = settingsManager.loadFrameRate()
                    val targetAudioBitrateKbps = settingsManager.loadAudioBitrate()

                    // --- Create Target Video MediaFormat ---
                    var actualTargetWidth: Int? = null
                    var actualTargetHeight: Int? = null

                    if (targetResolutionStr == "Original") {
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(applicationContext, inputVideoUri)
                            actualTargetWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                            actualTargetHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                            retriever.release()
                            if (actualTargetWidth == null || actualTargetHeight == null) {
                                Log.w(TAG, "Could not get original WxH. Falling back to 720p equivalent for encoder.")
                                actualTargetWidth = 1280
                                actualTargetHeight = 720
                            }
                            Log.i(TAG, "Original resolution selected: $actualTargetWidth x $actualTargetHeight")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error retrieving original metadata, falling back for encoder dimensions", e)
                            actualTargetWidth = 1280; actualTargetHeight = 720 // Fallback
                        }
                    } else {
                        val resolutionPair = SettingsActivity.COMMON_RESOLUTIONS[targetResolutionStr]
                        if (resolutionPair != null) {
                            actualTargetWidth = resolutionPair.first
                            actualTargetHeight = resolutionPair.second
                        } else {
                            Log.w(TAG, "Unknown resolution string: $targetResolutionStr. Falling back.")
                            actualTargetWidth = 1280; actualTargetHeight = 720 // Fallback
                        }
                    }

                    val videoFormat = MediaFormat.createVideoFormat(targetVideoMimeUserSetting, actualTargetWidth!!, actualTargetHeight!!)
                    finalOutputMimeType = targetVideoMimeUserSetting // Store the actual MIME type used

                    val estimatedVideoBitrate = getEstimatedVideoBitrate(actualTargetWidth, actualTargetHeight, targetQuality, targetVideoMimeUserSetting)
                    videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, estimatedVideoBitrate)
                    videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate)
                    videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)


                    // --- Create Target Audio MediaFormat ---
                    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                    audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetAudioBitrateKbps * 1000)

                    Log.d(TAG, "Prepared Target Video Format: $videoFormat")
                    Log.d(TAG, "Prepared Target Audio Format: $audioFormat")
                    updateNotification("Transcoding video...", 0)

                    val videoTranscoder = VideoTranscoder(applicationContext)
                    videoTranscoder.transcode(inputVideoUri, outputVideoPath, videoFormat, audioFormat,
                        onProgress = { progress ->
                            // Update notification and send broadcast with progress
                            updateNotification("Transcoding: $progress%", progress)
                            sendProgressBroadcast(progress)
                            Log.d(TAG, "Transcoding progress: $progress%")
                        }
                    )

                    Log.i(TAG, "Transcoding successful. Output: $outputVideoPath")
                    updateNotification("Transcoding complete", 100)
                    sendBroadcastResult(ACTION_TRANSCODING_COMPLETE, Uri.fromFile(File(outputVideoPath)).toString(), outputMimeType = finalOutputMimeType)

                } catch (e: Exception) {
                    Log.e(TAG, "Transcoding failed", e)
                    updateNotification("Transcoding error", -1)
                    sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = e.message ?: "Unknown transcoding error")
                } finally {
                    Log.d(TAG, "Transcoding process finished. Stopping service.")
                    stopSelf()
                }
            }
        } else {
            Log.w(TAG, "Unknown action or null intent. Stopping service.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun getEstimatedVideoBitrate(width: Int, height: Int, quality: String, mimeType: String): Int {
        val targetPixels = width * height
        val base720pPixels = 1280 * 720

        var baseBitrate = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> 1_800_000
            "video/vp9" -> 2_000_000
            else -> 2_500_000 // Default for AVC
        }
        baseBitrate = when (quality) {
            "High" -> (baseBitrate * 1.8).toInt()
            "Low" -> (baseBitrate * 0.6).toInt()
            else -> baseBitrate // Medium
        }
        var estimatedBitrate = (baseBitrate * (targetPixels.toDouble() / base720pPixels)).toInt()
        estimatedBitrate = estimatedBitrate.coerceIn(250_000, 15_000_000)
        Log.d(TAG, "Service estimated video bitrate for res($width x $height), $quality, $mimeType: $estimatedBitrate bps")
        return estimatedBitrate
    }

    private fun sendBroadcastResult(action: String, outputUriString: String? = null, outputMimeType: String? = null, errorMessage: String? = null) {
        val intent = Intent(action).setPackage(packageName)
        outputUriString?.let { intent.putExtra(EXTRA_OUTPUT_URI, it) }
        outputMimeType?.let { intent.putExtra(EXTRA_OUTPUT_MIME_TYPE, it) }
        errorMessage?.let { intent.putExtra(EXTRA_ERROR_MESSAGE, it) }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: $action, Output: $outputUriString, Mime: $outputMimeType, Error: $errorMessage")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Video Transcoding",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
     }

    private fun sendProgressBroadcast(progress: Int) {
        val intent = Intent(ACTION_TRANSCODING_PROGRESS).setPackage(packageName)
        intent.putExtra(EXTRA_PROGRESS, progress)
        sendBroadcast(intent)
    }

    private fun updateNotification(contentText: String, progress: Int) {
        val notification = createNotification(contentText, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java) // Or PreviewActivity if more appropriate
         val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with a proper transcoding icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Avoid repeated sounds/vibrations for progress updates

            if (progress >= 0 && progress <= 100) {
                builder.setProgress(100, progress, false)
            } else if (progress == -1) { // Error state
                 builder.setProgress(0,0,false) // Clear progress or indicate error
            }


            return builder.build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); serviceJob.cancel(); Log.d(TAG, "Service destroyed"); }
}
