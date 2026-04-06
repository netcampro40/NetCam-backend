package com.netcam.app.data.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.netcam.app.domain.model.RecordingSegment
import com.netcam.app.domain.model.RecordingSegmentStatus
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.domain.config.TemporaryClipRetention
import com.netcam.app.domain.storage.SegmentGalleryPersistence
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistência da galeria em arquivo JSON no armazenamento interno.
 * Aplica retenção para segmentos temporários (sem [RecordingSegment.galleryUri]).
 * Período: [TemporaryClipRetention] (48h).
 */
class FileSegmentGalleryPersistence(
    private val context: Context,
) : SegmentGalleryPersistence {

    override fun load(): SegmentGalleryPersistence.GalleryLoadResult {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            return SegmentGalleryPersistence.GalleryLoadResult(emptyList(), 1)
        }
        return runCatching {
            val json = file.readText(Charsets.UTF_8)
            val obj = JSONObject(json)
            val nextId = obj.optInt(KEY_NEXT_ID, 1)
            val array = obj.optJSONArray(KEY_SEGMENTS) ?: JSONArray()
            val list = mutableListOf<RecordingSegment>()
            for (i in 0 until array.length()) {
                parseSegment(array.getJSONObject(i))?.let { list.add(it) }
            }
            val filtered = applyRetentionAndIntegrity(list)
            val maxId = filtered.mapNotNull { segmentIdToInt(it.id) }.maxOrNull() ?: 0
            val nextSegmentId = maxOf(nextId, maxId + 1)
            if (filtered.size < list.size) {
                save(filtered, nextSegmentId)
            }
            SegmentGalleryPersistence.GalleryLoadResult(filtered, nextSegmentId)
        }.getOrElse {
            Log.e(TAG, "Erro ao carregar galeria", it)
            SegmentGalleryPersistence.GalleryLoadResult(emptyList(), 1)
        }
    }

    override fun save(segments: List<RecordingSegment>, nextSegmentId: Int) {
        val file = File(context.filesDir, FILE_NAME)
        runCatching {
            val array = JSONArray()
            segments.forEach { segment ->
                array.put(segmentToJson(segment))
            }
            val obj = JSONObject()
            obj.put(KEY_NEXT_ID, nextSegmentId)
            obj.put(KEY_SEGMENTS, array)
            file.writeText(obj.toString(), Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "Erro ao salvar galeria", it)
        }
    }

    /**
     * Critério de temporário: sem [RecordingSegment.galleryUri] (não exportado à galeria do sistema).
     * Regra: idade do clipe (agora − [RecordingSegment.requestedAt]) **>** [TemporaryClipRetention.retentionMaxAgeSeconds].
     *
     * Remove registro + apaga arquivo em disco quando existir.
     */
    private fun applyRetentionAndIntegrity(segments: List<RecordingSegment>): List<RecordingSegment> {
        val now = Instant.now()
        val retentionSec = TemporaryClipRetention.retentionMaxAgeSeconds()

        val temporaryInFile = segments.filter { it.galleryUri == null }
        Log.d(
            TAG,
            "Retenção(load): agora=$now retençãoConfigurada=${retentionSec}s " +
                "totalSegments=${segments.size} temporários(galleryUri null)=${temporaryInFile.size}",
        )
        temporaryInFile.forEach { seg ->
            val ageSec = TemporaryClipRetention.clipAgeSeconds(seg.requestedAt, now)
            val expires = TemporaryClipRetention.shouldExpireTemporaryClip(seg, now)
            Log.d(
                TAG,
                "Retenção(load): clipe id=${seg.id} status=${seg.status} horárioClipe=${seg.requestedAt} " +
                    "idadeSeg=${ageSec}s expira=$expires",
            )
        }

        val toRemoveByRetention =
            segments.filter { seg -> TemporaryClipRetention.shouldExpireTemporaryClip(seg, now) }
        toRemoveByRetention.forEach { seg ->
            seg.outputFilePath?.let { path ->
                try {
                    val deleted = File(path).delete()
                    Log.d(TAG, "Retenção(load): arquivo path=$path deleted=$deleted")
                } catch (_: Throwable) {}
            }
        }
        if (toRemoveByRetention.isNotEmpty()) {
            Log.d(
                TAG,
                "Retenção(load): removidos=${toRemoveByRetention.size} " +
                    "ids=${toRemoveByRetention.joinToString { it.id }} retenção=${retentionSec}s agora=$now",
            )
        }

        val toRemoveByIntegrity =
            segments.filter { seg ->
                if (seg.galleryUri != null) return@filter false
                when (seg.status) {
                    RecordingSegmentStatus.READY -> {
                        val path = seg.outputFilePath
                        if (path.isNullOrEmpty()) return@filter true
                        val file = File(path)
                        if (!file.exists() || file.length() <= MIN_VIDEO_FILE_BYTES) return@filter true
                        !hasPositiveVideoDuration(path)
                    }
                    RecordingSegmentStatus.FAILED -> {
                        val path = seg.outputFilePath
                        path.isNullOrEmpty() ||
                            !File(path).exists() ||
                            File(path).length() <= 0L
                    }
                    RecordingSegmentStatus.PENDING,
                    RecordingSegmentStatus.PROCESSING,
                    -> false
                }
            }
        if (toRemoveByIntegrity.isNotEmpty()) {
            Log.w(
                TAG,
                "Integridade: removendo ${toRemoveByIntegrity.size} temporário(s) inválido(s): ids=${toRemoveByIntegrity.joinToString { it.id }}",
            )
        }

        val removedIds = (toRemoveByRetention.map { it.id } + toRemoveByIntegrity.map { it.id }).toSet()
        return segments.filter { it.id !in removedIds }
    }

    private fun hasPositiveVideoDuration(path: String): Boolean {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val ms =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L
                ms > 0L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(false)
    }

    private fun segmentToJson(s: RecordingSegment): JSONObject {
        return JSONObject().apply {
            put(KEY_ID, s.id)
            put(KEY_TYPE, s.type.name)
            put(KEY_REQUESTED_AT_MILLIS, s.requestedAt.toEpochMilli())
            put(KEY_STATUS, s.status.name)
            put(KEY_RECORDING_SESSION_ID, s.recordingSessionId)
            put(KEY_CONTINUOUS_START_MS_AT_REQUEST, s.continuousRecordingStartMsAtRequest)
            put(KEY_OUTPUT_PATH, s.outputFilePath ?: JSONObject.NULL)
            put(KEY_FAILURE_REASON, s.failureReason ?: JSONObject.NULL)
            put(KEY_GALLERY_URI, s.galleryUri ?: JSONObject.NULL)
            put(KEY_DISPLAY_NAME, s.displayName ?: JSONObject.NULL)
                put(KEY_REAL_DURATION_SECONDS, s.realDurationSeconds ?: JSONObject.NULL)
        }
    }

    /** Compatibilidade: JSON antigo gravava [RecordingSegmentType.MAX_SESSION]. */
    private fun parseSegmentType(raw: String): RecordingSegmentType {
        if (raw == "MAX_SESSION") return RecordingSegmentType.FULL_SESSION
        return RecordingSegmentType.valueOf(raw)
    }

    private fun parseSegment(o: JSONObject): RecordingSegment? {
        return try {
            val type = parseSegmentType(o.getString(KEY_TYPE))
            val requestedAt = Instant.ofEpochMilli(o.getLong(KEY_REQUESTED_AT_MILLIS))
            val status = RecordingSegmentStatus.valueOf(o.getString(KEY_STATUS))
            RecordingSegment(
                id = o.getString(KEY_ID),
                type = type,
                requestedAt = requestedAt,
                status = status,
                recordingSessionId = o.optLong(KEY_RECORDING_SESSION_ID, 0L),
                continuousRecordingStartMsAtRequest =
                    o.optLong(KEY_CONTINUOUS_START_MS_AT_REQUEST, 0L),
                outputFilePath = o.optString(KEY_OUTPUT_PATH).takeIf { it.isNotEmpty() },
                failureReason = o.optString(KEY_FAILURE_REASON).takeIf { it.isNotEmpty() },
                galleryUri = o.optString(KEY_GALLERY_URI).takeIf { it.isNotEmpty() },
                displayName = o.optString(KEY_DISPLAY_NAME).takeIf { it.isNotEmpty() },
                realDurationSeconds = o.optInt(KEY_REAL_DURATION_SECONDS, -1).takeIf { it > 0 },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Segmento inválido ignorado", e)
            null
        }
    }

    private fun segmentIdToInt(id: String): Int? {
        return id.removePrefix("segment-").toIntOrNull()
    }

    companion object {
        private const val TAG = "SegmentGalleryPersistence"
        private const val FILE_NAME = "gallery_segments.json"
        private const val KEY_NEXT_ID = "nextId"
        private const val KEY_SEGMENTS = "segments"
        private const val KEY_ID = "id"
        private const val KEY_TYPE = "type"
        private const val KEY_REQUESTED_AT_MILLIS = "requestedAtMillis"
        private const val KEY_STATUS = "status"
        private const val KEY_RECORDING_SESSION_ID = "recordingSessionId"
        private const val KEY_CONTINUOUS_START_MS_AT_REQUEST = "continuousRecordingStartMsAtRequest"
        private const val KEY_OUTPUT_PATH = "outputFilePath"
        private const val KEY_FAILURE_REASON = "failureReason"
        private const val KEY_GALLERY_URI = "galleryUri"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_REAL_DURATION_SECONDS = "realDurationSeconds"
        /** Arquivos menores que isso não são MP4 válidos para exibição. */
        private const val MIN_VIDEO_FILE_BYTES = 256L
    }
}
