package com.thingsapart.videopt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
// MediaCodecInfo and MediaFormat might still be needed by getEstimatedVideoBitrate
import android.media.MediaFormat // Keep for getEstimatedVideoBitrate
import android.media.MediaMetadataRetriever // Keep for getEstimatedVideoBitrate
import android.net.Uri
import android.os.Build
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.resize.ExactResizer
import com.otaliastudios.transcoder.resize.PassThroughResizer
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

        // Using the package name as per the subtask description for consistency
        const val ACTION_TRANSCODING_PROGRESS = "com.thingsapart.videopt.action.TRANSCODING_PROGRESS"
        const val ACTION_TRANSCODING_COMPLETE = "com.thingsapart.videopt.action.TRANSCODING_COMPLETE"
        const val ACTION_TRANSCODING_ERROR = "com.thingsapart.videopt.action.TRANSCODING_ERROR"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_OUTPUT_MIME_TYPE = "extra_output_mime_type"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_OUTPUT_VIDEO_SIZE = "com.thingsapart.videopt.extra.OUTPUT_VIDEO_SIZE"


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
            startForeground(NOTIFICATION_ID, createNotification("Starting transcoding..."))
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
            updateNotification("Preparing to transcode...")

            serviceScope.launch {
                try {
                    // Load settings from SettingsManager
                    val targetResolutionStr = settingsManager.loadResolution()
                    val targetQuality = settingsManager.loadQuality()
                    val targetFrameRate = settingsManager.loadFrameRate()
                    val targetAudioBitrateKbps = settingsManager.loadAudioBitrate()

                    // Video Strategy Setup
                    val videoStrategyBuilder = com.otaliastudios.transcoder.strategy.DefaultVideoStrategy.Builder()

                    // Resolution Strategy
                    if (targetResolutionStr == "Original") {
                        videoStrategyBuilder.addResizer(com.otaliastudios.transcoder.resize.PassThroughResizer())
                        Log.d(TAG, "Video Resolution: Original (PassThroughResizer)")
                    } else {
                        val resolutionPair = SettingsActivity.COMMON_RESOLUTIONS[targetResolutionStr]
                        if (resolutionPair != null) {
                            videoStrategyBuilder.addResizer(com.otaliastudios.transcoder.resize.ExactResizer(resolutionPair.first, resolutionPair.second))
                            Log.d(TAG, "Video Resolution: ${resolutionPair.first}x${resolutionPair.second} (ExactResizer)")
                        } else {
                            Log.w(TAG, "Unknown resolution string: $targetResolutionStr. Using library default sizing for video.")
                        }
                    }

                    // Frame Rate for Video
                    if (targetFrameRate > 0) {
                        videoStrategyBuilder.frameRate(targetFrameRate)
                        Log.d(TAG, "Video Frame Rate: $targetFrameRate fps")
                    }

                    // Bitrate for Video
                    if (targetResolutionStr != "Original") {
                        val resolutionPair = SettingsActivity.COMMON_RESOLUTIONS[targetResolutionStr]
                        if (resolutionPair != null) {
                            // getEstimatedVideoBitrate expects android.media.MediaFormat.MIMETYPE_VIDEO_AVC, etc.
                            // For simplicity, we'll assume AVC for estimation if not original.
                            // The actual output format of DefaultVideoStrategy defaults to H.264 (AVC).
                            val estimatedBitrate = getEstimatedVideoBitrate(resolutionPair.first, resolutionPair.second, targetQuality, MediaFormat.MIMETYPE_VIDEO_AVC)
                            if (estimatedBitrate > 0) {
                                videoStrategyBuilder.bitRate(estimatedBitrate.toLong())
                                Log.d(TAG, "Video Bitrate: $estimatedBitrate bps")
                            }
                        } else {
                            videoStrategyBuilder.bitRate(com.otaliastudios.transcoder.strategy.DefaultVideoStrategy.BITRATE_UNKNOWN)
                            Log.d(TAG, "Video Bitrate: Unknown (due to unknown resolution)")
                        }
                    } else {
                        videoStrategyBuilder.bitRate(com.otaliastudios.transcoder.strategy.DefaultVideoStrategy.BITRATE_UNKNOWN)
                        Log.d(TAG, "Video Bitrate: AS_IS (for original resolution)")
                    }
                    val videoTrackStrategy = videoStrategyBuilder.build()

                    // Audio Strategy Setup
                    val audioStrategyBuilder = com.otaliastudios.transcoder.strategy.DefaultAudioStrategy.builder()
                    if (targetAudioBitrateKbps > 0) {
                        audioStrategyBuilder.bitRate((targetAudioBitrateKbps * 1000).toLong())
                        Log.d(TAG, "Audio Bitrate: ${targetAudioBitrateKbps * 1000} bps")
                    } else {
                        audioStrategyBuilder.bitRate(com.otaliastudios.transcoder.strategy.DefaultAudioStrategy.BITRATE_UNKNOWN)
                        Log.d(TAG, "Audio Bitrate: AS_IS")
                    }
                    val audioTrackStrategy = audioStrategyBuilder.build()

                    File(outputVideoPath).parentFile?.mkdirs()

                    val listener = object : com.otaliastudios.transcoder.TranscoderListener {
                        override fun onTranscodeProgress(progress: Double) {
                            if (serviceJob.isActive) {
                                val progressInt = (progress * 100).toInt()
                                if (progress >= 0 && progress <= 1.0) {
                                   updateNotification("Transcoding: $progressInt%")
                                   sendProgressBroadcast(progressInt)
                                } else if (progress > 1.0) { // Progress can sometimes slightly exceed 1.0
                                   updateNotification("Transcoding: 100%")
                                   sendProgressBroadcast(100)
                                }
                            }
                        }

                        override fun onTranscodeCompleted(successCode: Int) {
                            if (serviceJob.isActive) {
                                val outputUri = Uri.fromFile(File(outputVideoPath))
                                // The library typically outputs MP4, so this is a safe assumption.
                                // For more accuracy, one might inspect the file or have the strategy define it.
                                val mimeType = "video/mp4"
                                val outputFile = File(outputVideoPath)
                                val outputSizeBytes = if (outputFile.exists()) outputFile.length() else -1L

                                when (successCode) {
                                    com.otaliastudios.transcoder.Transcoder.SUCCESS_TRANSCODED -> {
                                        Log.i(TAG, "Transcoding successful (SUCCESS_TRANSCODED). Output: $outputVideoPath, Size: $outputSizeBytes bytes")
                                        sendBroadcastResult(ACTION_TRANSCODING_COMPLETE, outputUri.toString(), outputMimeType = mimeType, outputSize = outputSizeBytes)
                                    }
                                    com.otaliastudios.transcoder.Transcoder.SUCCESS_NOT_NEEDED -> {
                                        Log.i(TAG, "Transcoding not needed (SUCCESS_NOT_NEEDED). Output: $outputVideoPath, Size: $outputSizeBytes bytes")
                                        sendBroadcastResult(ACTION_TRANSCODING_COMPLETE, outputUri.toString(), outputMimeType = mimeType, outputSize = outputSizeBytes)
                                    }
                                    else -> { // Should not happen based on current library codes
                                         Log.w(TAG, "Transcoding completed with unknown successCode: $successCode. Output: $outputVideoPath, Size: $outputSizeBytes bytes")
                                         sendBroadcastResult(ACTION_TRANSCODING_COMPLETE, outputUri.toString(), outputMimeType = mimeType, outputSize = outputSizeBytes)
                                    }
                                }
                                stopSelf()
                            }
                        }

                        override fun onTranscodeCanceled() {
                            if (serviceJob.isActive) {
                                Log.w(TAG, "Transcoding Canceled")
                                updateNotification("Transcoding canceled.")
                                sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = "Transcoding canceled.")
                                stopSelf()
                            }
                        }

                        override fun onTranscodeFailed(exception: Throwable) {
                            if (serviceJob.isActive) {
                                Log.e(TAG, "Transcoding Failed with deepmedia/Transcoder", exception)
                                updateNotification("Transcoding error.")
                                sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = exception.localizedMessage ?: "Transcoding error from library.")
                                stopSelf()
                            }
                        }
                    }

                    updateNotification("Transcoding video...")
                    com.otaliastudios.transcoder.Transcoder.into(outputVideoPath)
                        .addDataSource(applicationContext, inputVideoUri)
                        .setVideoTrackStrategy(videoTrackStrategy)
                        .setAudioTrackStrategy(audioTrackStrategy)
                        .setListener(listener)
                        .transcode()

                } catch (e: Exception) {
                    if (serviceJob.isActive) {
                        Log.e(TAG, "Error setting up transcoding with deepmedia/Transcoder", e)
                        sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = "Setup error: ${e.localizedMessage}")
                        stopSelf()
                    }
                }
                // No finally block here to call stopSelf() because the listener handles terminal states.
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

    private fun sendBroadcastResult(action: String, outputUriString: String? = null, outputMimeType: String? = null, errorMessage: String? = null, outputSize: Long? = null) {
        val intent = Intent(action).setPackage(packageName) // Ensure it's a local broadcast by setting package
        outputUriString?.let { intent.putExtra(EXTRA_OUTPUT_URI, it) }
        outputMimeType?.let { intent.putExtra(EXTRA_OUTPUT_MIME_TYPE, it) }
        errorMessage?.let { intent.putExtra(EXTRA_ERROR_MESSAGE, it) }
        outputSize?.let { intent.putExtra(EXTRA_OUTPUT_VIDEO_SIZE, it) }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: $action, Output: $outputUriString, Mime: $outputMimeType, Size: $outputSize, Error: $errorMessage")
    }

    private fun sendProgressBroadcast(progress: Int) {
        val intent = Intent(ACTION_TRANSCODING_PROGRESS).setPackage(packageName)
        intent.putExtra(EXTRA_PROGRESS, progress)
        sendBroadcast(intent)
        // Log.d(TAG, "Progress broadcast sent: $progress%") // Can be too noisy, enable if needed
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
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); serviceJob.cancel(); Log.d(TAG, "Service destroyed"); }
}
