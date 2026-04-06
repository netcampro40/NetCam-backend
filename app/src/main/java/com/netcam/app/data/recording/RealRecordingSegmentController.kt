package com.netcam.app.data.recording

import android.util.Log
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.netcam.app.domain.model.RecordingSegment
import com.netcam.app.domain.model.RecordingSegmentStatus
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.domain.config.TemporaryClipRetention
import com.netcam.app.domain.recording.RecordingSegmentController
import com.netcam.app.domain.recording.VideoSegmentExtractor
import com.netcam.app.domain.storage.SavedVideoResult
import com.netcam.app.domain.storage.SegmentGalleryPersistence
import com.netcam.app.domain.storage.GalleryUriValidator
import com.netcam.app.domain.storage.VideoGallerySaver
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementação real inicial do [RecordingSegmentController] que
 * dispara recortes de vídeo a partir do arquivo contínuo.
 */
class RealRecordingSegmentController(
    private val videoSegmentExtractor: VideoSegmentExtractor,
    private val videoGallerySaver: VideoGallerySaver,
    private val galleryPersistence: SegmentGalleryPersistence,
    private val galleryUriValidator: GalleryUriValidator,
) : RecordingSegmentController {

    private val _segments = MutableStateFlow<List<RecordingSegment>>(emptyList())
    override val segments: StateFlow<List<RecordingSegment>> = _segments.asStateFlow()

    private val nextId = AtomicInteger(1)
    private val sessionIdGenerator = AtomicLong(0L)
    private var recordingSessionIdForActiveSession: Long = 0L
    @Volatile
    private var sessionIdAwaitingFinalize: Long? = null
    private val finalizationPending = AtomicBoolean(false)
    private var sessionActive = false

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val persistMutex = Mutex()

    private fun logStateSize(prefix: String) {
        Log.d(TAG, "$prefix segmentsSize=${_segments.value.size}")
    }

    init {
        runBlocking(Dispatchers.IO) {
            val result = galleryPersistence.load()
            Log.d(
                TAG,
                "galleryPersistence.load: loadedSegments=${result.segments.size} nextSegmentId=${result.nextSegmentId}",
            )
            applyDiskGalleryState(result, coldStart = true)
        }
    }

    override suspend fun refreshGalleryFromPersistenceWithRetention() {
        persistMutex.withLock {
            withContext(Dispatchers.IO) {
                val result = galleryPersistence.load()
                Log.d(
                    TAG,
                    "refreshGallery: após load+retenção(disco) segments=${result.segments.size} nextId=${result.nextSegmentId}",
                )
                applyDiskGalleryState(result, coldStart = false)
                // Camada extra: garante que a memória não mantenha temporários expirados se algo divergiu do JSON.
                pruneExpiredTemporaryClipsInMemoryLocked()
            }
        }
    }

    /**
     * Remove temporários cuja idade > retenção, apaga arquivos e regrava JSON.
     * Deve ser chamado com [persistMutex] já adquirido (ex.: após [applyDiskGalleryState] no refresh).
     */
    private fun pruneExpiredTemporaryClipsInMemoryLocked() {
        val now = Instant.now()
        val retentionSec = TemporaryClipRetention.retentionMaxAgeSeconds()
        val current = _segments.value
        val toRemove =
            current.filter { seg -> TemporaryClipRetention.shouldExpireTemporaryClip(seg, now) }
        if (toRemove.isEmpty()) {
            return
        }
        Log.d(
            TAG,
            "pruneExpiredInMemory: agora=$now retenção=${retentionSec}s removidos=${toRemove.size} " +
                "ids=${toRemove.joinToString { it.id }}",
        )
        toRemove.forEach { seg ->
            val age = TemporaryClipRetention.clipAgeSeconds(seg.requestedAt, now)
            Log.d(
                TAG,
                "pruneExpiredInMemory: id=${seg.id} horárioClipe=${seg.requestedAt} idadeSeg=$age",
            )
            seg.outputFilePath?.let { path ->
                runCatching {
                    val deleted = File(path).delete()
                    Log.d(TAG, "pruneExpiredInMemory: arquivo path=$path deleted=$deleted")
                }
            }
        }
        val removedIds = toRemove.map { it.id }.toSet()
        val kept = current.filter { it.id !in removedIds }
        _segments.value = kept
        galleryPersistence.save(kept, nextId.get())
        logStateSize("pruneExpiredInMemory done")
    }

    /**
     * @param coldStart se true, redefine [sessionIdGenerator] a partir do disco; se false, só garante mínimo ≥ disco.
     */
    private fun applyDiskGalleryState(
        result: SegmentGalleryPersistence.GalleryLoadResult,
        coldStart: Boolean,
    ) {
        val validated = validatePersistedGalleryUris(result.segments)
        val enriched =
            validated.map { segment ->
                if (
                    segment.status == RecordingSegmentStatus.READY &&
                    segment.realDurationSeconds == null &&
                    segment.outputFilePath != null
                ) {
                    val file = File(segment.outputFilePath)
                    segment.copy(realDurationSeconds = readRealDurationSeconds(file))
                } else {
                    segment
                }
            }
        val withLegacyFix =
            enriched.map { segment ->
                if (
                    segment.status == RecordingSegmentStatus.PENDING &&
                    segment.recordingSessionId == 0L
                ) {
                    segment.copy(
                        status = RecordingSegmentStatus.FAILED,
                        failureReason = "Registro antigo sem vínculo de sessão; capture novamente.",
                    )
                } else {
                    segment
                }
            }
        _segments.value = withLegacyFix
        nextId.set(result.nextSegmentId)
        val maxPersistedSessionId = withLegacyFix.maxOfOrNull { it.recordingSessionId } ?: 0L
        if (coldStart) {
            sessionIdGenerator.set(maxPersistedSessionId)
        } else {
            bumpSessionIdGeneratorFloor(maxPersistedSessionId)
        }
        Log.d(
            TAG,
            "applyDiskGalleryState coldStart=$coldStart sessionIdGen=${sessionIdGenerator.get()} segments=${withLegacyFix.size}",
        )
        if (validated !== result.segments || withLegacyFix != enriched) {
            galleryPersistence.save(withLegacyFix, result.nextSegmentId)
        }
    }

    private fun bumpSessionIdGeneratorFloor(floor: Long) {
        while (true) {
            val prev = sessionIdGenerator.get()
            val next = max(prev, floor)
            if (next == prev) return
            if (sessionIdGenerator.compareAndSet(prev, next)) return
        }
    }

    private fun validatePersistedGalleryUris(
        segments: List<RecordingSegment>,
    ): List<RecordingSegment> {
        var changed = false
        val validated = segments.map { segment ->
            val uriStr = segment.galleryUri ?: return@map segment
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
            val ok = uri != null && galleryUriValidator.isSavedToGallery(uri)
            if (ok) return@map segment
            changed = true
            segment.copy(galleryUri = null, displayName = null)
        }
        return if (changed) validated else segments
    }

    override fun isContinuousRecordingFinalizationPending(): Boolean = finalizationPending.get()

    override fun onSessionStarted() {
        if (finalizationPending.get()) {
            Log.w(
                TAG,
                "onSessionStarted ignorado: finalização da gravação contínua anterior ainda em andamento",
            )
            return
        }
        recordingSessionIdForActiveSession = sessionIdGenerator.incrementAndGet()
        sessionActive = true
        abandonOrphanPendingFromOlderSessions(recordingSessionIdForActiveSession)
        Log.d(TAG, "onSessionStarted recordingSessionId=$recordingSessionIdForActiveSession")
    }

    /**
     * Clipes PENDING de sessões anteriores (ex.: app encerrado antes de finalizar a gravação)
     * nunca poderão usar o arquivo contínuo correto; falham de forma explícita.
     */
    private fun abandonOrphanPendingFromOlderSessions(newSessionId: Long) {
        var anyFailed = false
        _segments.update { current ->
            current.map { seg ->
                if (
                    seg.status == RecordingSegmentStatus.PENDING &&
                        seg.recordingSessionId > 0L &&
                        seg.recordingSessionId < newSessionId
                ) {
                    anyFailed = true
                    seg.copy(
                        status = RecordingSegmentStatus.FAILED,
                        failureReason =
                            "A gravação dessa sessão não foi finalizada; este clipe não pôde ser gerado. Inicie uma nova sessão e capture de novo.",
                    )
                } else {
                    seg
                }
            }
        }
        if (anyFailed) {
            Log.w(
                TAG,
                "abandonOrphanPendingFromOlderSessions: clipes PENDING de sessões < $newSessionId marcados como FAILED",
            )
            persist()
        }
    }

    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                val snapshot = _segments.value
                val snapshotSize = snapshot.size
                val snapshotNextId = nextId.get()
                Log.d(
                    TAG,
                    "persist() saving snapshot segmentsSize=$snapshotSize nextSegmentId=$snapshotNextId",
                )
                galleryPersistence.save(snapshot, snapshotNextId)
                logStateSize("persist() done")
            }
        }
    }

    override fun onSessionStopped() {
        sessionActive = false
        sessionIdAwaitingFinalize = recordingSessionIdForActiveSession
        finalizationPending.set(true)
        Log.d(
            TAG,
            "onSessionStopped: sessão inativa; sessionIdAwaitingFinalize=$sessionIdAwaitingFinalize — clipes PENDING desta sessão serão processados após finalização da gravação",
        )
    }

    override fun onContinuousRecordingFinalized() {
        val finalizedSessionId = sessionIdAwaitingFinalize
        if (finalizedSessionId == null) {
            Log.w(TAG, "onContinuousRecordingFinalized: nenhum sessionId aguardando; liberando estado")
            finalizationPending.set(false)
            return
        }
        Log.d(
            TAG,
            "onContinuousRecordingFinalized: aguardando delay e processando apenas clipes da sessão $finalizedSessionId",
        )
        scope.launch {
            try {
                delay(FINALIZE_TO_PROCESS_DELAY_MS)
                val continuousFile = CameraXVideoEngine.currentOutputFile
                val pending =
                    _segments.value.filter {
                        it.status == RecordingSegmentStatus.PENDING &&
                            it.recordingSessionId == finalizedSessionId
                    }
                val otherPending =
                    _segments.value.count {
                        it.status == RecordingSegmentStatus.PENDING &&
                            it.recordingSessionId != finalizedSessionId
                    }
                Log.d(
                    TAG,
                    "Após delay: continuousPath=${continuousFile?.absolutePath}, exists=${continuousFile?.exists()}, length=${continuousFile?.length()} bytes; pendingEstaSessão=${pending.size}, pendingOutrasSessões=$otherPending",
                )
                if (pending.isEmpty()) {
                    Log.d(TAG, "onContinuousRecordingFinalized: nenhum clipe PENDING para sessão $finalizedSessionId")
                }
                pending.forEachIndexed { idx, segment ->
                    Log.d(
                        TAG,
                        "processSeries[$idx/${pending.size}] id=${segment.id} recordingSessionId=${segment.recordingSessionId} type=${segment.type.name}",
                    )
                    enqueueProcessing(segment, continuousFile = continuousFile)
                }
            } finally {
                finalizationPending.set(false)
                sessionIdAwaitingFinalize = null
            }
        }
    }

    override fun requestSegment(type: RecordingSegmentType) {
        if (!sessionActive) {
            Log.w(TAG, "requestSegment ignorado: sessão não está ativa")
            return
        }

        val id = "segment-${nextId.getAndIncrement()}"
        val requestedAt = Instant.now()

        val pendingSegment =
            RecordingSegment(
                id = id,
                type = type,
                requestedAt = requestedAt,
                status = RecordingSegmentStatus.PENDING,
                recordingSessionId = recordingSessionIdForActiveSession,
                continuousRecordingStartMsAtRequest = CameraXVideoEngine.currentRecordingStartTimeMs,
            )

        _segments.update { current -> current + pendingSegment }
        logStateSize("after requestSegment($id)")
        persist()
        Log.d(
            TAG,
            "requestSegment: clipe $id (${if (type == RecordingSegmentType.FULL_SESSION) "sessão inteira" else "${type.durationSeconds}s"}) sessão=$recordingSessionIdForActiveSession startMs=${pendingSegment.continuousRecordingStartMsAtRequest}",
        )
    }

    override suspend fun saveSegmentToGallery(segmentId: String): Result<android.net.Uri> =
        withContext(Dispatchers.IO) {
            val segment = _segments.value.find { it.id == segmentId }
                ?: return@withContext Result.failure(IllegalArgumentException("Clipe não encontrado: $segmentId"))
            if (segment.status != RecordingSegmentStatus.READY) {
                return@withContext Result.failure(IllegalStateException("Clipe não está pronto para salvar"))
            }
            val path = segment.outputFilePath ?: return@withContext Result.failure(
                IllegalStateException("Clipe sem arquivo de saída"),
            )
            val file = File(path)
            val result = videoGallerySaver.saveVideoToGallery(file)
            result.onSuccess { saved ->
                markSegmentSavedToGallery(segmentId, saved.uri.toString(), saved.displayName)
            }
            return@withContext result.map { it.uri }
        }

    override suspend fun deleteTemporarySegment(segmentId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val segment = _segments.value.find { it.id == segmentId }
                ?: return@withContext Result.failure(IllegalArgumentException("Clipe não encontrado: $segmentId"))
            if (segment.galleryUri != null) {
                return@withContext Result.failure(IllegalStateException("Clipe já está na galeria do Android; não pode ser apagado aqui"))
            }
            segment.outputFilePath?.let { path ->
                runCatching {
                    try {
                        File(path).delete()
                    } catch (_: Throwable) {}
                }
            }

            _segments.update { current -> current.filterNot { it.id == segmentId } }
            persist()
            logStateSize("after deleteTemporarySegment($segmentId)")
            Result.success(Unit)
        }

    private fun markSegmentSavedToGallery(
        segmentId: String,
        galleryUri: String,
        displayName: String,
    ) {
        _segments.update { current ->
            current.map { segment ->
                if (segment.id == segmentId) {
                    segment.copy(galleryUri = galleryUri, displayName = displayName)
                } else {
                    segment
                }
            }
        }
        persist()
        logStateSize("after markSegmentSavedToGallery($segmentId)")
    }

    private suspend fun enqueueProcessing(
        segment: RecordingSegment,
        continuousFile: File?,
    ) {
        val id = segment.id
        val type = segment.type
        val file = continuousFile

        if (file == null) {
            Log.w(TAG, "Arquivo contínuo nulo ao processar $id")
            markFailed(id, "Arquivo contínuo ainda não definido")
            return
        }

        val exists = file.exists()
        val length = file.length()

        val continuousStartMs =
            segment.continuousRecordingStartMsAtRequest.takeIf { it > 0L }
                ?: CameraXVideoEngine.currentRecordingStartTimeMs
        val requestedAtMs = segment.requestedAt.toEpochMilli()
        val endOffsetMillis =
            if (continuousStartMs > 0L) {
                (requestedAtMs - continuousStartMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE / 1_000L
            }

        val continuousDurationMs = readVideoDurationMs(file)

        val extractDurationSeconds =
            extractionDurationSeconds(type, endOffsetMillis, continuousDurationMs)

        Log.d(
            TAG,
            "Iniciando processamento: id=$id recordingSessionId=${segment.recordingSessionId}, requestedAtMs=$requestedAtMs, type=${type.name} (extractSeconds=$extractDurationSeconds), continuousPath=${file.absolutePath}, continuousStartMs=$continuousStartMs (snapshot=${segment.continuousRecordingStartMsAtRequest}), continuousDurationMs=$continuousDurationMs, endOffsetMillis=$endOffsetMillis, exists=$exists, length=$length bytes",
        )

        if (!exists || length <= 0L) {
            markFailed(id, "Arquivo contínuo inexistente ou vazio")
            return
        }

        markStatus(id, RecordingSegmentStatus.PROCESSING)
        Log.d(TAG, "Status->PROCESSING: id=$id")
        logStateSize("after markStatus(PROCESSING) $id")

        try {
            val output =
                videoSegmentExtractor.extractSegmentAt(
                    source = file,
                    durationSeconds = extractDurationSeconds,
                    endOffsetMillis = endOffsetMillis,
                )

            Log.d(TAG, "Clipe $id gerado em ${output.absolutePath}, size=${output.length()} bytes")
            markReady(id, output)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao extrair clipe $id", t)
            markFailed(id, "Erro ao recortar: ${t.message}")
        }
    }

    /**
     * Duração em segundos passada ao [VideoSegmentExtractor].
     * [FULL_SESSION]: janela do início da gravação contínua até [endOffsetMillis]
     * (instante do pedido), limitada pela duração real do arquivo.
     */
    private fun extractionDurationSeconds(
        type: RecordingSegmentType,
        endOffsetMillis: Long,
        continuousDurationMs: Long?,
    ): Int {
        if (type != RecordingSegmentType.FULL_SESSION) {
            return type.durationSeconds
        }
        val spanSec = ((endOffsetMillis + 999L) / 1000L).toInt().coerceAtLeast(1)
        val fileSec =
            continuousDurationMs?.let { ms ->
                ((ms + 999L) / 1000L).toInt().coerceAtLeast(1)
            }
        val fromContent = fileSec?.let { min(spanSec, it) } ?: spanSec
        // Teto de segurança para o muxer (sessões muito longas ainda são suportadas até 6h).
        return fromContent.coerceIn(1, 6 * 60 * 60)
    }

    private fun readVideoDurationMs(file: java.io.File): Long? {
        return runCatching {
            if (!file.exists() || file.length() <= 0L) return@runCatching null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    private fun markStatus(
        id: String,
        status: RecordingSegmentStatus,
        failureReason: String? = null,
    ) {
        val prev = _segments.value.find { it.id == id }
        Log.d(
            TAG,
            "markStatus: id=$id prevStatus=${prev?.status} -> newStatus=$status failureReason=${failureReason ?: "null"}",
        )
        _segments.update { current ->
            current.map { segment ->
                if (segment.id == id) {
                    segment.copy(
                        status = status,
                        failureReason = failureReason,
                    )
                } else {
                    segment
                }
            }
        }
        persist()
        logStateSize("after markStatus($status) $id")
    }

    private fun markFailed(
        id: String,
        reason: String,
    ) {
        markStatus(id, RecordingSegmentStatus.FAILED, reason)
    }

    private fun markReady(
        id: String,
        outputFile: java.io.File,
    ) {
        val realDurationSeconds = readRealDurationSeconds(outputFile)
        Log.d(
            TAG,
            "markReady: id=$id output=${outputFile.absolutePath} outputSize=${outputFile.length()} bytes realDurationSeconds=$realDurationSeconds",
        )
        _segments.update { current ->
            current.map { segment ->
                if (segment.id == id) {
                    segment.copy(
                        status = RecordingSegmentStatus.READY,
                        outputFilePath = outputFile.absolutePath,
                        realDurationSeconds = realDurationSeconds,
                    )
                } else {
                    segment
                }
            }
        }
        persist()
        logStateSize("after markReady READY $id")
    }

    private fun readRealDurationSeconds(file: java.io.File): Int? {
        // MediaMetadataRetriever retorna duração em milissegundos (METADATA_KEY_DURATION).
        return runCatching {
            if (!file.exists() || file.length() <= 0L) return@runCatching null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: return@runCatching null
                val seconds = (durationMs / 1_000L).toInt()
                seconds.takeIf { it > 0 }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "RealRecordingSegmentCtl"
        private const val FINALIZE_TO_PROCESS_DELAY_MS = 300L
    }
}

