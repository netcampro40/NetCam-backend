package com.netcam.app.data.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.netcam.app.domain.storage.GalleryUriValidator

class MediaStoreGalleryUriValidator(
    private val appContext: Context,
) : GalleryUriValidator {
    override fun isSavedToGallery(uri: Uri): Boolean {
        val resolver = appContext.contentResolver
        return try {
            // Query direta no item (content://.../video/media/<id>), confirmando existência
            // e, em Android Q+, se não está mais em estado pending.
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING)
            } else {
                arrayOf(MediaStore.Video.Media._ID)
            }

            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val idxPending = c.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                    if (idxPending >= 0) {
                        val pending = c.getInt(idxPending)
                        if (pending == 1) return false
                    }
                }
                true
            } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

