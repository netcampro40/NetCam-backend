package com.netcam.app.data.recording

import com.netcam.app.domain.model.RecordingSegment
import com.netcam.app.domain.model.RecordingSegmentStatus
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.domain.recording.RecordingSegmentController
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Implementação fake responsável por organizar segmentos
 * de gravação apenas em memória.
 *
 * Ela não acessa mídia real. Futuras implementações vão
 * conectar esta camada ao buffer contínuo de vídeo.
 */
class FakeRecordingSegmentController : RecordingSegmentController {

    private val _segments = MutableStateFlow<List<RecordingSegment>>(emptyList())
    override val segments: StateFlow<List<RecordingSegment>> = _segments.asStateFlow()

    private var nextId = 1
    private var sessionActive = false
    private var recordingSessionIdForFakeSession: Long = 0L

    override fun onSessionStarted() {
        sessionActive = true
        recordingSessionIdForFakeSession++
        // Cada nova sessão começa com a lista de segmentos vazia.
        _segments.value = emptyList()
    }

    override fun onSessionStopped() {
        sessionActive = false
    }

    override fun isContinuousRecordingFinalizationPending(): Boolean = false

    override fun onContinuousRecordingFinalized() {
        // Fake: não processa mídia real
    }

    override suspend fun saveSegmentToGallery(segmentId: String): Result<android.net.Uri> =
        Result.failure(UnsupportedOperationException("Fake: salvamento na galeria não implementado"))

    override suspend fun deleteTemporarySegment(segmentId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Fake: exclusão de temporário não implementada"))

    override suspend fun refreshGalleryFromPersistenceWithRetention() {
        // Fake: sem persistência em disco
    }

    override fun requestSegment(type: RecordingSegmentType) {
        if (!sessionActive) return

        val segment =
            RecordingSegment(
                id = "fake-segment-${nextId++}",
                type = type,
                requestedAt = Instant.now(),
                status = RecordingSegmentStatus.PENDING,
                recordingSessionId = recordingSessionIdForFakeSession,
                continuousRecordingStartMsAtRequest = 0L,
            )

        _segments.update { current -> current + segment }
    }
}

