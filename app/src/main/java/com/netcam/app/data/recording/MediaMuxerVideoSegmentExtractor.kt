package com.netcam.app.data.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.netcam.app.domain.recording.VideoSegmentExtractor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recorte de vídeo e áudio baseado em [MediaExtractor] e [MediaMuxer].
 *
 * - Copia a primeira trilha de vídeo e a primeira trilha de áudio do source.
 * - Janela: últimos [durationSeconds] segundos antes do instante [endOffsetMillis]
 *   (para “sessão inteira”, o chamador passa a duração = tempo desde o início da gravação).
 * - Preserva orientação (setOrientationHint) e intercala amostras por timestamp.
 */
class MediaMuxerVideoSegmentExtractor(
    private val appContext: Context,
) : VideoSegmentExtractor {

    override suspend fun extractSegmentAt(
        source: File,
        durationSeconds: Int,
        endOffsetMillis: Long,
    ): File =
        withContext(Dispatchers.IO) {
            require(durationSeconds > 0) { "durationSeconds deve ser > 0" }

            Log.d(
                TAG,
                "extractSegmentAt: source=${source.absolutePath}, exists=${source.exists()}, length=${source.length()} bytes, durationSeconds=$durationSeconds, endOffsetMillis=$endOffsetMillis",
            )

            val path = source.absolutePath

            val probe = MediaExtractor()
            probe.setDataSource(path)
            val trackCount = probe.trackCount

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until trackCount) {
                val format = probe.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoFormat == null -> {
                        videoTrackIndex = i
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioFormat == null -> {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }
            }
            probe.release()

            if (videoTrackIndex == -1 || videoFormat == null) {
                error("Nenhuma trilha de vídeo encontrada em $path")
            }

            val durationUs =
                if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    Log.w(TAG, "VideoFormat sem KEY_DURATION, assumindo janela inteira do arquivo")
                    0L
                }
            val desiredWindowUs = durationSeconds * 1_000_000L

            // endOffsetMillis -> endUs (limitado à duração real do arquivo)
            val endUs =
                (endOffsetMillis.coerceAtLeast(0L) * 1_000L).coerceIn(0L, durationUs)
            val startUs = (endUs - desiredWindowUs).coerceAtLeast(0L)

            Log.d(
                TAG,
                "extractSegmentAt: videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex, durationUs=$durationUs, desiredWindowUs=$desiredWindowUs, endUs=$endUs, startUs=$startUs",
            )

            val outputDir = File(appContext.filesDir, "segments").apply {
                if (!exists()) mkdirs()
            }
            val outputFile = File(
                outputDir,
                "segment_${endOffsetMillis}_${System.currentTimeMillis()}.mp4",
            )

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )

            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                muxer.setOrientationHint(rotation)
                Log.d(TAG, "extractTailSegment: orientação aplicada no muxer: $rotation°")
            }

            val muxerVideoTrackIndex = muxer.addTrack(videoFormat)
            val muxerAudioTrackIndex = if (audioFormat != null) {
                val idx = muxer.addTrack(audioFormat)
                idx
            } else {
                -1
            }
            muxer.start()

            val videoBufferSize =
                if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    512 * 1024
                }
            val audioBufferSize = if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                256 * 1024
            }

            val extractorVideo = MediaExtractor()
            val extractorAudio = if (audioFormat != null) MediaExtractor() else null
            try {
                extractorVideo.setDataSource(path)
                extractorVideo.selectTrack(videoTrackIndex)
                extractorVideo.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                if (extractorAudio != null && audioTrackIndex >= 0) {
                    extractorAudio.setDataSource(path)
                    extractorAudio.selectTrack(audioTrackIndex)
                    extractorAudio.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            } catch (t: Throwable) {
                extractorVideo.release()
                extractorAudio?.release()
                throw t
            }

            val videoBuffer = java.nio.ByteBuffer.allocate(videoBufferSize)
            val audioBuffer = java.nio.ByteBuffer.allocate(audioBufferSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = extractorAudio == null || muxerAudioTrackIndex < 0
            var videoSamplesWritten = 0
            var audioSamplesWritten = 0
            var lastVideoSampleTimeUsWritten: Long = -1L
            var lastAudioSampleTimeUsWritten: Long = -1L
            var lastVideoPresentationTimeUsWritten: Long = -1L
            var lastAudioPresentationTimeUsWritten: Long = -1L

            try {
                while (true) {
                    val canReadVideo = !videoDone && extractorVideo.sampleTrackIndex >= 0
                    val canReadAudio =
                        !audioDone && extractorAudio != null && extractorAudio.sampleTrackIndex >= 0

                    // Hard stop: só copia samples cujo sampleTime está dentro de [startUs, endUs].
                    if (canReadVideo) {
                        val timeVideoTmp = extractorVideo.sampleTime
                        if (timeVideoTmp > endUs) {
                            videoDone = true
                            Log.d(
                                TAG,
                                "[DIAG] stopVideoByEndUs: timeVideoTmpUs=$timeVideoTmp endUs=$endUs startUs=$startUs",
                            )
                        }
                    }
                    if (canReadAudio) {
                        val timeAudioTmp = extractorAudio!!.sampleTime
                        if (timeAudioTmp > endUs) {
                            audioDone = true
                            Log.d(
                                TAG,
                                "[DIAG] stopAudioByEndUs: timeAudioTmpUs=$timeAudioTmp endUs=$endUs startUs=$startUs",
                            )
                        }
                    }

                    val hasVideo = !videoDone && extractorVideo.sampleTrackIndex >= 0
                    val timeVideo = if (hasVideo) extractorVideo.sampleTime else Long.MAX_VALUE
                    val hasAudio = !audioDone && extractorAudio != null && extractorAudio.sampleTrackIndex >= 0
                    val timeAudio = if (hasAudio) extractorAudio!!.sampleTime else Long.MAX_VALUE

                    if (!hasVideo && !hasAudio) break

                    if (hasVideo && (timeVideo <= timeAudio || !hasAudio)) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractorVideo.readSampleData(videoBuffer, 0)
                        if (bufferInfo.size >= 0) {
                            val sourceSampleTimeUs = extractorVideo.sampleTime
                            if (sourceSampleTimeUs >= startUs && sourceSampleTimeUs <= endUs) {
                                val presentationTimeUs =
                                    (sourceSampleTimeUs - startUs).coerceAtLeast(0L)
                                bufferInfo.presentationTimeUs = presentationTimeUs
                                bufferInfo.flags = extractorVideo.sampleFlags
                                muxer.writeSampleData(muxerVideoTrackIndex, videoBuffer, bufferInfo)
                                videoSamplesWritten++
                                lastVideoSampleTimeUsWritten = sourceSampleTimeUs
                                lastVideoPresentationTimeUsWritten = presentationTimeUs
                            }
                        }
                        videoDone = !extractorVideo.advance()
                    } else if (hasAudio) {
                        val audioExtractor = extractorAudio!!
                        bufferInfo.offset = 0
                        bufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                        if (bufferInfo.size >= 0 && muxerAudioTrackIndex >= 0) {
                            val sourceSampleTimeUs = audioExtractor.sampleTime
                            if (sourceSampleTimeUs >= startUs && sourceSampleTimeUs <= endUs) {
                                val presentationTimeUs =
                                    (sourceSampleTimeUs - startUs).coerceAtLeast(0L)
                                bufferInfo.presentationTimeUs = presentationTimeUs
                                bufferInfo.flags = audioExtractor.sampleFlags
                                muxer.writeSampleData(muxerAudioTrackIndex, audioBuffer, bufferInfo)
                                audioSamplesWritten++
                                lastAudioSampleTimeUsWritten = sourceSampleTimeUs
                                lastAudioPresentationTimeUsWritten = presentationTimeUs
                            }
                        }
                        audioDone = !audioExtractor.advance()
                    }
                }

                muxer.stop()
                muxer.release()
                Log.d(TAG, "[DIAG] Segmento: videoSamplesWritten=$videoSamplesWritten, audioSamplesWritten=$audioSamplesWritten")
                Log.d(
                    TAG,
                    "[DIAG] Segmento limits: startUs=$startUs endUs=$endUs lastVideoSampleTimeUsWritten=$lastVideoSampleTimeUsWritten lastVideoPresentationTimeUsWritten=$lastVideoPresentationTimeUsWritten lastAudioSampleTimeUsWritten=$lastAudioSampleTimeUsWritten lastAudioPresentationTimeUsWritten=$lastAudioPresentationTimeUsWritten",
                )
                Log.d(TAG, "Segmento gerado em ${outputFile.absolutePath}, size=${outputFile.length()} bytes")
                outputFile
            } finally {
                try {
                    extractorVideo.release()
                } catch (_: Throwable) {}
                try {
                    extractorAudio?.release()
                } catch (_: Throwable) {}
            }
        }

    companion object {
        private const val TAG = "MediaMuxerVideoSegment"
    }
}

