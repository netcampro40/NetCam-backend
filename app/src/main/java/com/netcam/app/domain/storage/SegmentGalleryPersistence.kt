package com.netcam.app.domain.storage

import com.netcam.app.domain.model.RecordingSegment

/**
 * Persistência da galeria interna de segmentos do app.
 * Permite carregar e salvar a lista de segmentos e o próximo id,
 * com política de retenção para temporários.
 */
interface SegmentGalleryPersistence {
    /**
     * Carrega segmentos e próximo id.
     * Aplica retenção: temporários (sem galleryUri) mais antigos que o período
     * configurado são removidos e seus arquivos apagados.
     * @return par (lista de segmentos após retenção, próximo id para novos segmentos)
     */
    fun load(): GalleryLoadResult

    /**
     * Salva a lista de segmentos e o próximo id.
     */
    fun save(segments: List<RecordingSegment>, nextSegmentId: Int)

    data class GalleryLoadResult(
        val segments: List<RecordingSegment>,
        val nextSegmentId: Int,
    )
}
