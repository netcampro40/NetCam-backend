package com.netcam.app.domain.storage

import java.io.File

/**
 * Responsável por salvar um arquivo de vídeo na galeria do Android (MediaStore),
 * tornando-o visível ao usuário em Fotos/Vídeos.
 * Usa nome sequencial no padrão Netcampro40_0000001, Netcampro40_0000002, etc.
 */
interface VideoGallerySaver {
    /**
     * Copia o vídeo [sourceFile] para a galeria com o próximo nome sequencial
     * e retorna o [SavedVideoResult] (uri + displayName) ou falha.
     */
    suspend fun saveVideoToGallery(sourceFile: File): Result<SavedVideoResult>
}
