package com.thingsapart.videopt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class VideoTranscoder(private val context: Context) {

    companion object {
        private const val TAG = "VideoTranscoder"
        private const val TIMEOUT_US = 10000L // 10ms
        // MAX_BUFFER_SIZE might be needed if we manually copy buffers,
        // but MediaCodec's own buffers are usually sufficient.
    }

    @Throws(IOException::class, IllegalArgumentException::class, IllegalStateException::class)
    suspend fun transcode(
        inputVideoUri: Uri,
        outputFilePath: String,
        targetVideoFormat: MediaFormat,
        targetAudioFormat: MediaFormat
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Transcode START - Input: $inputVideoUri, Output: $outputFilePath")
        Log.d(TAG, "Target Video: $targetVideoFormat")
        Log.d(TAG, "Target Audio: $targetAudioFormat")

        var extractor: MediaExtractor? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var encoderInputSurface: Surface? = null // Added for Surface transcoding

        var inputVideoTrackIndex = -1
        var inputAudioTrackIndex = -1
        var muxerVideoTrackIndex = -1
        var muxerAudioTrackIndex = -1

        var videoBufferInfo = MediaCodec.BufferInfo()
        var audioBufferInfo = MediaCodec.BufferInfo()

        var videoExtractorDone = false
        var videoDecoderDone = false
        var videoEncoderDone = false

        var audioExtractorDone = false
        var audioDecoderDone = false
        var audioEncoderDone = false

        var muxerStarted = false

        // Helper function to start muxer once all tracks are ready
        fun tryStartMuxerLocally() {
            if (!muxerStarted) {
                val videoTrackReady = muxerVideoTrackIndex != -1
                // Audio is ready if there's no audio track, or if the audio track has been added to muxer
                val audioTrackReady = inputAudioTrackIndex == -1 || muxerAudioTrackIndex != -1

                if (videoTrackReady && audioTrackReady) {
                    Log.d(TAG, "All necessary tracks added. Starting muxer.")
                    muxer!!.start() // muxer should not be null here
                    muxerStarted = true
                }
            }
        }

        try {
            extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(inputVideoUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Failed to open FileDescriptor for URI: $inputVideoUri")

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && inputVideoTrackIndex == -1) {
                    inputVideoTrackIndex = i
                    Log.d(TAG, "Input Video Track: index=$i, format=$format")
                } else if (mime?.startsWith("audio/") == true && inputAudioTrackIndex == -1) {
                    inputAudioTrackIndex = i
                    Log.d(TAG, "Input Audio Track: index=$i, format=$format")
                }
            }

            if (inputVideoTrackIndex == -1) throw IOException("No video track found.")

            // Muxer setup
            muxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.d(TAG, "Muxer initialized for: $outputFilePath")

            // Video pipeline setup
            val inputVideoMediaFormat = extractor.getTrackFormat(inputVideoTrackIndex)
            videoEncoder = MediaCodec.createEncoderByType(targetVideoFormat.getString(MediaFormat.KEY_MIME)!!)
            videoEncoder.configure(targetVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = videoEncoder.createInputSurface() // Create input surface from encoder
            videoEncoder.start()

            videoDecoder = MediaCodec.createDecoderByType(inputVideoMediaFormat.getString(MediaFormat.KEY_MIME)!!)
            // Configure decoder to output to the encoder's surface
            videoDecoder.configure(inputVideoMediaFormat, encoderInputSurface, null, 0)
            videoDecoder.start()
            Log.d(TAG, "Video Decoder & Encoder started (Surface-to-Surface).")

            // Audio pipeline setup (if audio track exists)
            if (inputAudioTrackIndex != -1) {
                val inputAudioMediaFormat = extractor.getTrackFormat(inputAudioTrackIndex)
                audioDecoder = MediaCodec.createDecoderByType(inputAudioMediaFormat.getString(MediaFormat.KEY_MIME)!!)
                audioDecoder.configure(inputAudioMediaFormat, null, null, 0)
                audioDecoder.start()

                audioEncoder = MediaCodec.createEncoderByType(targetAudioFormat.getString(MediaFormat.KEY_MIME)!!)
                audioEncoder.configure(targetAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()
                Log.d(TAG, "Audio Decoder & Encoder started.")
            } else {
                Log.i(TAG, "No audio track found or selected. Proceeding with video-only.")
                audioExtractorDone = true
                audioDecoderDone = true
                audioEncoderDone = true
            }


            // Video Processing Pass
            Log.d(TAG, "Starting Video Processing Pass")
            extractor.selectTrack(inputVideoTrackIndex) // Select video track for reading
            while (!videoEncoderDone) {
                // Feed video decoder
                if (!videoExtractorDone) {
                    val decInIdx = videoDecoder.dequeueInputBuffer(TIMEOUT_US)
                    if (decInIdx >= 0) {
                        val buffer = videoDecoder.getInputBuffer(decInIdx)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            videoDecoder.queueInputBuffer(decInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            videoExtractorDone = true
                            Log.d(TAG, "Video Extractor EOS")
                        } else {
                            videoDecoder.queueInputBuffer(decInIdx, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                // Video Decoder to Encoder (Surface Path)
                if (!videoDecoderDone) {
                    val decOutIdx = videoDecoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        val isEOS = videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (isEOS) {
                            Log.d(TAG, "Video Decoder EOS")
                            videoDecoderDone = true
                            videoEncoder.signalEndOfInputStream() // Signal EOS to encoder via surface
                        }
                        // Render the buffer to the surface if it contains data.
                        // The surface is the encoder's input surface.
                        // Release buffer and render to surface if size > 0 and not EOS (EOS frame might have size 0)
                        // The prompt said: videoDecoder.releaseOutputBuffer(decOutIdx, videoBufferInfo.size != 0 && !videoDecoderDone)
                        // Let's use videoBufferInfo.size > 0 as the condition to render.
                        // If it's EOS, videoDecoderDone will be true, so the original condition becomes videoBufferInfo.size != 0 && !true -> false for render
                        // This seems correct: render if size > 0, and if it's EOS, signal encoder but don't try to render the (potentially empty) EOS buffer.
                        // However, MediaCodec typically expects EOS buffer to be rendered to propagate timestamp correctly.
                        // Let's use: videoDecoder.releaseOutputBuffer(decOutIdx, videoBufferInfo.size > 0)
                        // If it's EOS and size is 0, it won't render. If EOS and size > 0, it will.
                        // The crucial part is videoEncoder.signalEndOfInputStream() for EOS.
                        videoDecoder.releaseOutputBuffer(decOutIdx, videoBufferInfo.size > 0)

                    } else if (decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // This format change is for the decoder's output surface,
                        // not directly relevant for encoder input buffers anymore.
                        Log.d(TAG, "Video Decoder output format changed (to surface): " + videoDecoder.outputFormat)
                    }
                }

                // Video Encoder to Muxer (remains largely the same)
                val encOutIdx = videoEncoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_US)
                if (encOutIdx >= 0) {
                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "Video Encoder EOS")
                        videoEncoderDone = true
                    }
                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        videoEncoder.releaseOutputBuffer(encOutIdx, false)
                    } else if (videoBufferInfo.size > 0) {
                        val encOutBuffer = videoEncoder.getOutputBuffer(encOutIdx)!!
                        if (muxerVideoTrackIndex == -1) throw IllegalStateException("Muxer video track not added yet. Encoder format change not handled before data.")

                        if (muxerStarted) {
                            encOutBuffer.position(videoBufferInfo.offset)
                            encOutBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrackIndex, encOutBuffer, videoBufferInfo)
                        } else {
                            Log.w(TAG, "Muxer not started, dropping video sample. PresentationTimeUs: ${videoBufferInfo.presentationTimeUs}")
                        }
                        videoEncoder.releaseOutputBuffer(encOutIdx, false)
                    }
                } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = videoEncoder.outputFormat
                    if (muxerVideoTrackIndex == -1) {
                         muxerVideoTrackIndex = muxer.addTrack(newFormat)
                         Log.d(TAG, "Video Encoder output format changed: $newFormat. Added video track to muxer: $muxerVideoTrackIndex")
                         tryStartMuxerLocally()
                    }
                }
            }
            Log.d(TAG, "Video Processing Pass COMPLETED.")

            // Audio Processing Pass
            if (inputAudioTrackIndex != -1) {
                Log.d(TAG, "Starting Audio Processing Pass")
                extractor.unselectTrack(inputVideoTrackIndex)
                extractor.selectTrack(inputAudioTrackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                while (!audioEncoderDone) {
                    // Feed audio decoder
                    if (!audioExtractorDone) {
                        val decInIdx = audioDecoder!!.dequeueInputBuffer(TIMEOUT_US)
                        if (decInIdx >= 0) {
                            val buffer = audioDecoder.getInputBuffer(decInIdx)!!
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                audioDecoder.queueInputBuffer(decInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioExtractorDone = true
                                Log.d(TAG, "Audio Extractor EOS")
                            } else {
                                audioDecoder.queueInputBuffer(decInIdx, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }

                    // Audio Decoder to Encoder
                    if (!audioDecoderDone) {
                        val decOutIdx = audioDecoder?.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_US) ?: -1
                        if (decOutIdx >= 0) {
                             if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "Audio Decoder EOS")
                                audioDecoderDone = true
                                audioEncoder!!.signalEndOfInputStream()
                            }
                            if (audioBufferInfo.size > 0 && (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                                val decOutBuffer = audioDecoder?.getOutputBuffer(decOutIdx)
                                decOutBuffer?.let {
                                    val encInIdx = audioEncoder!!.dequeueInputBuffer(TIMEOUT_US)
                                    if (encInIdx >= 0) {
                                        val encInBuffer = audioEncoder.getInputBuffer(encInIdx)!!
                                        encInBuffer.put(decOutBuffer)
                                        audioEncoder.queueInputBuffer(
                                            encInIdx,
                                            0,
                                            audioBufferInfo.size,
                                            audioBufferInfo.presentationTimeUs,
                                            audioBufferInfo.flags
                                        )
                                    }
                                }
                            }
                            audioDecoder?.releaseOutputBuffer(decOutIdx, false)
                        } else if (decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                             Log.d(TAG, "Audio Decoder output format changed: " + audioDecoder?.outputFormat)
                        }
                    }

                    // Audio Encoder to Muxer
                    val encOutIdx = audioEncoder!!.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_US)
                    if (encOutIdx >= 0) {
                        if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Audio Encoder EOS")
                            audioEncoderDone = true
                        }
                         if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            audioEncoder?.releaseOutputBuffer(encOutIdx, false)
                        } else if (audioBufferInfo.size > 0) {
                            val encOutBuffer = audioEncoder.getOutputBuffer(encOutIdx)!!
                             if (muxerAudioTrackIndex == -1) throw IllegalStateException("Muxer audio track not added yet. Encoder format change not handled.")

                            if (muxerStarted) {
                                encOutBuffer.position(audioBufferInfo.offset)
                                encOutBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                muxer.writeSampleData(muxerAudioTrackIndex, encOutBuffer, audioBufferInfo)
                            } else {
                                Log.w(TAG, "Muxer not started, dropping audio sample. PresentationTimeUs: ${audioBufferInfo.presentationTimeUs}")
                            }
                            audioEncoder.releaseOutputBuffer(encOutIdx, false)
                        }
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = audioEncoder.outputFormat
                        if (muxerAudioTrackIndex == -1) { // Should only be -1
                            muxerAudioTrackIndex = muxer.addTrack(newFormat)
                            Log.d(TAG, "Audio Encoder output format changed: $newFormat. Added audio track to muxer: $muxerAudioTrackIndex")
                            tryStartMuxerLocally()
                        }
                    }
                }
                Log.d(TAG, "Audio Processing Pass COMPLETED.")
            }

            // The tryStartMuxerLocally() calls should handle all valid scenarios.
            // If muxer hasn't started by now and tracks were expected, it might indicate an issue.
            // However, the old final check was a bit of a catch-all which might hide issues.
            // Relying on tryStartMuxerLocally is cleaner.

            Log.i(TAG, "Transcoding processing loop finished for all tracks.")

        } catch (e: Exception) {
            Log.e(TAG, "Error during transcoding pipeline", e)
            throw e
        } finally {
            Log.d(TAG, "Releasing all resources...")
            try { videoDecoder?.stop(); videoDecoder?.release(); Log.d(TAG, "Video Decoder released.") } catch (e: Exception) { Log.e(TAG, "Err release videoDec", e) }
            try { videoEncoder?.stop(); videoEncoder?.release(); Log.d(TAG, "Video Encoder released.") } catch (e: Exception) { Log.e(TAG, "Err release videoEnc", e) }
            try { encoderInputSurface?.release(); Log.d(TAG, "Encoder Input Surface released.")} catch (e: Exception) { Log.e(TAG, "Err release encoderInputSurface", e)} // Release surface
            if (inputAudioTrackIndex != -1) { // Only release audio codecs if they were initialized
                try { audioDecoder?.stop(); audioDecoder?.release(); Log.d(TAG, "Audio Decoder released.") } catch (e: Exception) { Log.e(TAG, "Err release audioDec", e) }
                try { audioEncoder?.stop(); audioEncoder?.release(); Log.d(TAG, "Audio Encoder released.") } catch (e: Exception) { Log.e(TAG, "Err release audioEnc", e) }
            }
            try { extractor?.release(); Log.d(TAG, "Extractor released.") } catch (e: Exception) { Log.e(TAG, "Err release extractor", e) }
            try {
                if (muxerStarted) muxer?.stop() // Only stop if started
                muxer?.release()
                Log.d(TAG, "Muxer released.")
            } catch (e: Exception) { Log.e(TAG, "Err release muxer", e) }
            Log.d(TAG, "All resources release attempt completed.")
        }
        Log.i(TAG, "Transcode END - Output: $outputFilePath")
    }
}
