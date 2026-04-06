package com.netcam.app.domain.storage

import android.net.Uri

/**
 * Resultado do salvamento de um vídeo na galeria (MediaStore).
 * Inclui o [uri] do item criado e o [displayName] usado (ex.: Netcampro40_0000001).
 */
data class SavedVideoResult(
    val uri: Uri,
    val displayName: String,
)
