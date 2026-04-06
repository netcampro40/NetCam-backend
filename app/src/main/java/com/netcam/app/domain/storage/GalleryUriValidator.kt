package com.netcam.app.domain.storage

import android.net.Uri

/**
 * Valida se um [Uri] persistido realmente aponta para um item válido no MediaStore
 * e já está finalizado/visível ao usuário.
 */
interface GalleryUriValidator {
    /**
     * @return true se o URI existe e representa um item finalizado na galeria.
     */
    fun isSavedToGallery(uri: Uri): Boolean
}

