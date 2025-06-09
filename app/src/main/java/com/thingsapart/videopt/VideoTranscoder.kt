package com.thingsapart.videopt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
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
            // extractor.selectTrack(inputVideoTrackIndex) // Select track just before its processing pass
            videoDecoder = MediaCodec.createDecoderByType(inputVideoMediaFormat.getString(MediaFormat.KEY_MIME)!!)
            videoDecoder.configure(inputVideoMediaFormat, null, null, 0)
            videoDecoder.start()

            videoEncoder = MediaCodec.createEncoderByType(targetVideoFormat.getString(MediaFormat.KEY_MIME)!!)
            videoEncoder.configure(targetVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoEncoder.start()
            Log.d(TAG, "Video Decoder & Encoder started.")

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

                // Video Decoder to Encoder
                if (!videoDecoderDone) {
                    val decOutIdx = videoDecoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Video Decoder EOS")
                            videoDecoderDone = true
                            videoEncoder.signalEndOfInputStream()
                        }
                        if (videoBufferInfo.size > 0 && (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                            val decOutBuffer = videoDecoder.getOutputBuffer(decOutIdx)!!
                            val encInIdx = videoEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                val encInBuffer = videoEncoder.getInputBuffer(encInIdx)!!
                                encInBuffer.put(decOutBuffer)
                                videoEncoder.queueInputBuffer(encInIdx, 0, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                            }
                        }
                        videoDecoder.releaseOutputBuffer(decOutIdx, false)
                    } else if (decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Video Decoder output format changed: " + videoDecoder.outputFormat)
                    }
                }

                // Video Encoder to Muxer
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

                        encOutBuffer.position(videoBufferInfo.offset)
                        encOutBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                        muxer.writeSampleData(muxerVideoTrackIndex, encOutBuffer, videoBufferInfo)
                        videoEncoder.releaseOutputBuffer(encOutIdx, false)
                    }
                } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = videoEncoder.outputFormat
                    if (muxerVideoTrackIndex == -1) {
                         muxerVideoTrackIndex = muxer.addTrack(newFormat)
                         Log.d(TAG, "Video Encoder output format changed: $newFormat. Added video track to muxer: $muxerVideoTrackIndex")
                         if ((inputAudioTrackIndex == -1 || muxerAudioTrackIndex != -1) && !muxerStarted) {
                            Log.d(TAG, "Muxer starting (from video path).")
                            muxer.start()
                            muxerStarted = true
                         }
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

                            encOutBuffer.position(audioBufferInfo.offset)
                            encOutBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            muxer.writeSampleData(muxerAudioTrackIndex, encOutBuffer, audioBufferInfo)
                            audioEncoder.releaseOutputBuffer(encOutIdx, false)
                        }
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = audioEncoder.outputFormat
                        if (muxerAudioTrackIndex == -1) { // Should only be -1
                            muxerAudioTrackIndex = muxer.addTrack(newFormat)
                            Log.d(TAG, "Audio Encoder output format changed: $newFormat. Added audio track to muxer: $muxerAudioTrackIndex")
                            if (muxerVideoTrackIndex != -1 && !muxerStarted) {
                                Log.d(TAG, "Muxer starting (from audio path).")
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                    }
                }
                Log.d(TAG, "Audio Processing Pass COMPLETED.")
            }

            if (!muxerStarted && muxerVideoTrackIndex != -1) {
                Log.i(TAG, "Muxer starting (likely video-only or audio processing skipped/failed before muxer start).")
                muxer.start()
                muxerStarted = true
            }

            Log.i(TAG, "Transcoding processing loop finished for all tracks.")

        } catch (e: Exception) {
            Log.e(TAG, "Error during transcoding pipeline", e)
            throw e
        } finally {
            Log.d(TAG, "Releasing all resources...")
            try { videoDecoder?.stop(); videoDecoder?.release(); Log.d(TAG, "Video Decoder released.") } catch (e: Exception) { Log.e(
                TAG, "Err release videoDec", e) }
            try { videoEncoder?.stop(); videoEncoder?.release(); Log.d(TAG, "Video Encoder released.") } catch (e: Exception) { Log.e(
                TAG, "Err release videoEnc", e) }
            if (inputAudioTrackIndex != -1) { // Only release audio codecs if they were initialized
                try { audioDecoder?.stop(); audioDecoder?.release(); Log.d(TAG, "Audio Decoder released.") } catch (e: Exception) { Log.e(
                    TAG, "Err release audioDec", e) }
                try { audioEncoder?.stop(); audioEncoder?.release(); Log.d(TAG, "Audio Encoder released.") } catch (e: Exception) { Log.e(
                    TAG, "Err release audioEnc", e) }
            }
            try { extractor?.release(); Log.d(TAG, "Extractor released.") } catch (e: Exception) { Log.e(
                TAG, "Err release extractor", e) }
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
