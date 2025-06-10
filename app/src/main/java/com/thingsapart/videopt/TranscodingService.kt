package com.thingsapart.videopt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaFormat
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

    private val serviceJob = Job() // Parent job for the service scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var currentTranscodingJob: Job? = null // To keep track of the active transcoding task

    private lateinit var notificationManager: NotificationManager
    private lateinit var settingsManager: SettingsManager

    companion object {
        private const val TAG = "TranscodingService" // Corrected TAG definition
        const val ACTION_START_TRANSCODING = "com.thingsapart.videopt.action.START_TRANSCODING" // Corrected action string prefix
        const val EXTRA_INPUT_VIDEO_URI = "extra_input_video_uri"

        const val NOTIFICATION_CHANNEL_ID = "TranscodingChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_TRANSCODING_PROGRESS = "com.thingsapart.videopt.action.TRANSCODING_PROGRESS"
        const val ACTION_TRANSCODING_COMPLETE = "com.thingsapart.videopt.action.TRANSCODING_COMPLETE"
        const val ACTION_TRANSCODING_ERROR = "com.thingsapart.videopt.action.TRANSCODING_ERROR"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_OUTPUT_MIME_TYPE = "extra_output_mime_type"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_OUTPUT_VIDEO_SIZE = "com.thingsapart.videopt.extra.OUTPUT_VIDEO_SIZE"
        const val EXTRA_TEMPORARY_SETTINGS = "com.thingsapart.videopt.extra.TEMPORARY_SETTINGS"


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
            // Use a timestamp and part of the original filename (if possible) for uniqueness
            val originalFileName = inputVideoUri.lastPathSegment?.substringBeforeLast('.') ?: "video"
            val outputFileName = "transcoded_${originalFileName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            val outputDir = File(cacheDir, "transcoded_videos")
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputVideoPath = File(outputDir, outputFileName).absolutePath

            Log.d(TAG, "Starting transcoding for: $inputVideoUri to $outputVideoPath")
            updateNotification("Preparing to transcode...")

            val temporarySettingsBundle = intent.getBundleExtra(EXTRA_TEMPORARY_SETTINGS)

            // Cancel any previous transcoding job before starting a new one
            currentTranscodingJob?.let {
                if (it.isActive) {
                    Log.d(TAG, "Cancelling previous transcoding job.")
                    it.cancel() // This will trigger onTranscodeCanceled if the listener is set up for cancellation
                }
            }

            currentTranscodingJob = serviceScope.launch {
                try {
                    // Ensure the job is active before proceeding, especially after potential cancellation
                    if (!isActive) {
                        Log.d(TAG, "Transcoding job cancelled before starting work.")
                        return@launch
                    }
                    val targetResolutionSetting: String
                    val targetQuality: String
                    val targetFrameRate: Int
                    val targetAudioBitrateBps: Int

                    if (temporarySettingsBundle != null) {
                        Log.d(TAG, "Using temporary settings for transcoding.")
                        targetResolutionSetting = temporarySettingsBundle.getString(SettingsManager.PREF_RESOLUTION, settingsManager.loadResolution())
                        targetQuality = temporarySettingsBundle.getString(SettingsManager.PREF_QUALITY, settingsManager.loadQuality())
                        targetFrameRate = temporarySettingsBundle.getInt(SettingsManager.PREF_FRAME_RATE, settingsManager.loadFrameRate())
                        targetAudioBitrateBps = temporarySettingsBundle.getInt(SettingsManager.PREF_AUDIO_BITRATE, settingsManager.loadAudioBitrate())
                    } else {
                        Log.d(TAG, "Using default saved settings for transcoding.")
                        targetResolutionSetting = settingsManager.loadResolution()
                        targetQuality = settingsManager.loadQuality()
                        targetFrameRate = settingsManager.loadFrameRate()
                        targetAudioBitrateBps = settingsManager.loadAudioBitrate()
                    }

                    val videoStrategyBuilder = DefaultVideoStrategy.Builder()
                    // Important: parseResolutionValue is a method of SettingsManager,
                    // so it needs to be called on an instance of SettingsManager,
                    // or the logic needs to be duplicated/accessible here if SettingsManager instance isn't available.
                    // SettingsManager is available as `settingsManager` (class member).
                    val dimensions = settingsManager.parseResolutionValue(targetResolutionSetting)

                    if (targetResolutionSetting.equals("Original", ignoreCase = true) || dimensions == null) {
                        videoStrategyBuilder.addResizer(PassThroughResizer())
                        if (dimensions == null && !targetResolutionSetting.equals("Original", ignoreCase = true)) {
                            Log.w(TAG, "Could not parse resolution string '$targetResolutionSetting'. Defaulting to Original/PassThrough.")
                        } else {
                            Log.d(TAG, "Video Resolution: Original (PassThroughResizer)")
                        }
                    } else {
                        videoStrategyBuilder.addResizer(ExactResizer(dimensions.first, dimensions.second))
                        Log.d(TAG, "Video Resolution: ${dimensions.first}x${dimensions.second} (ExactResizer)")
                    }

                    if (targetFrameRate > 0) {
                        videoStrategyBuilder.frameRate(targetFrameRate)
                        Log.d(TAG, "Video Frame Rate: $targetFrameRate fps")
                    }

                    if (targetResolutionSetting.equals("Original", ignoreCase = true) || dimensions == null) {
                        videoStrategyBuilder.bitRate(DefaultVideoStrategy.BITRATE_UNKNOWN)
                         if (dimensions == null && !targetResolutionSetting.equals("Original", ignoreCase = true)) {
                            Log.w(TAG, "Video Bitrate: Unknown (due to unparsed resolution '$targetResolutionSetting')")
                        } else {
                            Log.d(TAG, "Video Bitrate: AS_IS (for original resolution)")
                        }
                    } else {
                        val estimatedBitrate = getEstimatedVideoBitrate(dimensions.first, dimensions.second, targetQuality, MediaFormat.MIMETYPE_VIDEO_AVC)
                        if (estimatedBitrate > 0) {
                            videoStrategyBuilder.bitRate(estimatedBitrate.toLong())
                            Log.d(TAG, "Video Bitrate: $estimatedBitrate bps for ${dimensions.first}x${dimensions.second}")
                        } else {
                             videoStrategyBuilder.bitRate(DefaultVideoStrategy.BITRATE_UNKNOWN) // Or BITRATE_AS_IS
                             Log.w(TAG, "Video Bitrate: Unknown (estimation failed for ${dimensions.first}x${dimensions.second})")
                        }
                    }
                    val videoTrackStrategy = videoStrategyBuilder.build()

                    val audioStrategyBuilder = DefaultAudioStrategy.builder()
                    if (targetAudioBitrateBps > 0) {
                        audioStrategyBuilder.bitRate(targetAudioBitrateBps.toLong()) // Already in BPS
                        Log.d(TAG, "Audio Bitrate: $targetAudioBitrateBps bps")
                    } else {
                        audioStrategyBuilder.bitRate(DefaultAudioStrategy.BITRATE_UNKNOWN)
                        Log.d(TAG, "Audio Bitrate: AS_IS")
                    }
                    val audioTrackStrategy = audioStrategyBuilder.build()

                    File(outputVideoPath).parentFile?.mkdirs()

                    val listener = object : TranscoderListener {
                        override fun onTranscodeProgress(progress: Double) {
                            if (serviceJob.isActive) {
                                val progressInt = (progress * 100).toInt()
                                updateNotification("Transcoding: $progressInt%")
                                sendProgressBroadcast(progressInt)
                            }
                        }
                        override fun onTranscodeCompleted(successCode: Int) {
                            if (serviceJob.isActive) {
                                val outputUri = Uri.fromFile(File(outputVideoPath))
                                val mimeType = "video/mp4"
                                val outputFile = File(outputVideoPath)
                                val outputSizeBytes = if (outputFile.exists()) outputFile.length() else -1L
                                val action = ACTION_TRANSCODING_COMPLETE
                                sendBroadcastResult(action, outputUri.toString(), mimeType, null, outputSizeBytes)
                                Log.i(TAG, "Transcoding finished with code $successCode. Output: $outputVideoPath, Size: $outputSizeBytes bytes")
                                stopSelf()
                            }
                        }
                        override fun onTranscodeCanceled() {
                            // Check currentTranscodingJob specifically, as serviceJob is for the whole service
                            currentTranscodingJob?.let { job ->
                                if (job.isCancelled) { // Check if this specific job was cancelled
                                    Log.w(TAG, "Transcoding Canceled (job was cancelled)")
                                    updateNotification("Transcoding canceled.")
                                    // Don't send error broadcast if it was an intentional cancellation for a new task
                                    // sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = "Transcoding canceled by new request.")
                                    // stopSelf() // Don't stopSelf if a new task is about to start
                                }
                            } ?: Log.w(TAG, "Transcoding Canceled (job is null)")
                        }
                        override fun onTranscodeFailed(exception: Throwable) {
                            // Only process if this job wasn't cancelled externally
                            if (isActive) { // isActive is from CoroutineScope
                                Log.e(TAG, "Transcoding Failed", exception)
                                updateNotification("Transcoding error.")
                                sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = exception.localizedMessage ?: "Transcoding error.")
                                stopSelf() // Stop service on failure
                            } else {
                                Log.i(TAG, "Transcoding failed but job was already cancelled/inactive: ${exception.message}")
                            }
                        }
                    }

                    updateNotification("Transcoding video...")
                    Transcoder.into(outputVideoPath)
                        .addDataSource(applicationContext, inputVideoUri)
                        .setVideoTrackStrategy(videoTrackStrategy)
                        .setAudioTrackStrategy(audioTrackStrategy)
                        .setListener(listener)
                        .transcode()
                } catch (e: Exception) {
                    if (isActive) { // isActive is from CoroutineScope
                        Log.e(TAG, "Error setting up transcoding", e)
                        sendBroadcastResult(ACTION_TRANSCODING_ERROR, errorMessage = "Setup error: ${e.localizedMessage}")
                        stopSelf() // Stop service on setup error
                    } else {
                        Log.i(TAG, "Exception during setup but job was already cancelled/inactive: ${e.message}")
                    }
                }
            }
        } else {
            Log.w(TAG, "Unknown action or null intent. Stopping service.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun getEstimatedVideoBitrate(width: Int, height: Int, quality: String, mimeType: String): Int {
        val targetPixels = width * height.toLong() // Use Long to prevent overflow for large resolutions
        val base720pPixels = 1280 * 720L

        var baseBitrate = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> 1_800_000 // H.265
            "video/vp9" -> 2_000_000
            else -> 2_500_000 // Default for AVC (H.264)
        }
        baseBitrate = when (quality) {
            "High" -> (baseBitrate * 1.8).toInt()
            "Low" -> (baseBitrate * 0.6).toInt() // Increased from 0.5 for slightly better low quality
            else -> baseBitrate // Medium
        }
        // Ensure positive pixel counts to avoid division by zero or negative results
        if (base720pPixels <= 0) return baseBitrate
        var estimatedBitrate = (baseBitrate * (targetPixels.toDouble() / base720pPixels)).toInt()
        // Apply some sanity clamps, ensure min bitrate is reasonable e.g. 250kbps for video
        estimatedBitrate = estimatedBitrate.coerceIn(250_000, 25_000_000) // Increased max clamp for 4K
        Log.d(TAG, "Service estimated video bitrate for res($width x $height), $quality, $mimeType: $estimatedBitrate bps")
        return estimatedBitrate
    }

    private fun sendBroadcastResult(action: String, outputUriString: String? = null, outputMimeType: String? = null, errorMessage: String? = null, outputSize: Long? = null) {
        val intent = Intent(action).setPackage(packageName)
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
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Video Transcoding", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
     }

    private fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java) // Should open MainActivity or PreviewActivity
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
        return builder.setContentTitle(getString(R.string.app_name))
            .setContentText(contentText).setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent).setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); serviceJob.cancel(); Log.d(TAG, "Service destroyed"); }
}
