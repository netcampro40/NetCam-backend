package com.netcam.app.domain.model

import java.time.Instant

/**
 * Representa um segmento de gravação gerado a partir
 * da gravação contínua base de uma sessão.
 *
 * Nesta etapa, além dos metadados de controle, também
 * podemos referenciar o caminho do arquivo de mídia
 * temporário gerado pelo recorte real.
 */
data class RecordingSegment(
    val id: String,
    val type: RecordingSegmentType,
    val requestedAt: Instant,
    val status: RecordingSegmentStatus,
    /**
     * Identificador monotônico da sessão de gravação em que o clipe foi pedido.
     * Garante que o processamento use apenas o arquivo contínuo dessa sessão.
     */
    val recordingSessionId: Long = 0L,
    /**
     * Início da gravação contínua (epoch ms) no instante do pedido, espelhando o motor de câmera;
     * usado para o offset no arquivo dessa sessão sem depender de estado global após outras sessões.
     */
    val continuousRecordingStartMsAtRequest: Long = 0L,
    val outputFilePath: String? = null,
    val failureReason: String? = null,
    /** URI na galeria (MediaStore) após salvar; null se ainda não foi salvo. */
    val galleryUri: String? = null,
    /** Nome de exibição na galeria (ex.: Netcampro40_0000001); preenchido ao salvar. */
    val displayName: String? = null,
    /**
     * Duração real (em segundos) do arquivo de mídia gerado (outputFilePath).
     * Usamos isso na galeria para exibir a duração real ao invés da duração solicitada.
     */
    val realDurationSeconds: Int? = null,
)

