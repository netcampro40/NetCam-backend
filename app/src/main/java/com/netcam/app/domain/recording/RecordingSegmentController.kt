package com.netcam.app.domain.recording

import com.netcam.app.domain.model.RecordingSegment
import com.netcam.app.domain.model.RecordingSegmentType
import kotlinx.coroutines.flow.StateFlow

/**
 * Camada responsável por orquestrar segmentos de gravação
 * em cima da gravação contínua base.
 *
 * Nesta etapa ainda não existe mídia real, apenas a
 * estrutura de controle e o fluxo de dados.
 */
interface RecordingSegmentController {
    /**
     * Fluxo observável com o estado atual dos segmentos
     * associados à sessão ativa.
     */
    val segments: StateFlow<List<RecordingSegment>>

    /**
     * Deve ser chamado quando uma sessão é iniciada.
     * Futuras implementações poderão usar isso para
     * conectar-se ao buffer da gravação contínua.
     */
    fun onSessionStarted()

    /**
     * Deve ser chamado quando a sessão é encerrada.
     * Apenas marca a sessão como inativa; o processamento
     * dos segmentos pendentes deve ocorrer em [onContinuousRecordingFinalized].
     */
    fun onSessionStopped()

    /**
     * True enquanto a gravação contínua da última sessão parada ainda está sendo finalizada no disco.
     * Não iniciar nova sessão até retornar false, para não sobrescrever o arquivo antes do processamento.
     */
    fun isContinuousRecordingFinalizationPending(): Boolean

    /**
     * Chamado quando a gravação contínua foi realmente finalizada
     * e o arquivo está pronto. Processa apenas clipes PENDING da mesma sessão que acabou de finalizar.
     */
    fun onContinuousRecordingFinalized()

    /**
     * Salva o segmento (se READY e com arquivo) na galeria do Android.
     * Retorna o URI do item na galeria em sucesso.
     */
    suspend fun saveSegmentToGallery(segmentId: String): Result<android.net.Uri>

    /**
     * Exclui manualmente um segmento temporário da galeria interna:
     * remove o registro persistido e apaga o arquivo temporário.
     *
     * Segmentos já salvos na galeria do Android (com galleryUri) não devem ser excluídos por aqui.
     */
    suspend fun deleteTemporarySegment(segmentId: String): Result<Unit>

    /**
     * Solicita a criação de um segmento a partir do
     * buffer contínuo, com a janela definida pelo tipo.
     *
     * Nesta fase, a implementação fake apenas registra
     * a intenção em memória.
     */
    fun requestSegment(type: RecordingSegmentType)

    /**
     * Recarrega do disco, aplica retenção de temporários e alinha [segments] + JSON.
     * Chamar ao abrir a galeria (não depender só do cold start).
     */
    suspend fun refreshGalleryFromPersistenceWithRetention()
}

