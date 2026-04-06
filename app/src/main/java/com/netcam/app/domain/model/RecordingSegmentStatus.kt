package com.netcam.app.domain.model

/**
 * Estado de um segmento dentro do pipeline de gravação.
 *
 * Nesta etapa todos os segmentos serão mantidos apenas
 * em memória com estado PENDING, mas os demais estados
 * já estão prontos para as próximas fases.
 */
enum class RecordingSegmentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
}

