package com.netcam.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.netcam.app.domain.storage.SavedVideoResult
import com.netcam.app.domain.storage.VideoGallerySaver
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Salva vídeos na galeria usando MediaStore (Android 10+).
 * Usa IS_PENDING durante a escrita e atualiza para 0 ao finalizar.
 */
class MediaStoreVideoGallerySaver(
    private val appContext: Context,
) : VideoGallerySaver {

    override suspend fun saveVideoToGallery(sourceFile: File): Result<SavedVideoResult> =
        withContext(Dispatchers.IO) {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Arquivo inexistente ou vazio: ${sourceFile.absolutePath}"),
                )
            }

            val resolver = appContext.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val baseName = getNextSequentialDisplayName()
            val displayNameWithExt = "$baseName.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayNameWithExt)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH_VIDEOS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            var insertedUri: Uri? = null
            try {
                val uri = resolver.insert(collection, contentValues)
                    ?: return@withContext Result.failure(
                        IllegalStateException("MediaStore.insert retornou null"),
                    )
                insertedUri = uri

                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        output.flush()
                    }
                } ?: throw IllegalStateException("Não foi possível abrir OutputStream para $uri")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    val updated = resolver.update(uri, finalizeValues, null, null)
                    if (updated <= 0) {
                        throw IllegalStateException("Falha ao finalizar item no MediaStore (IS_PENDING=0)")
                    }
                }

                // Validação final: o item precisa existir e, em Q+, não pode estar pending.
                val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING)
                } else {
                    arrayOf(MediaStore.Video.Media._ID)
                }
                val ok = resolver.query(uri, projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val idxPending = c.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                        if (idxPending >= 0 && c.getInt(idxPending) == 1) return@use false
                    }
                    true
                } ?: false
                if (!ok) {
                    throw IllegalStateException("Item não ficou disponível/visível no MediaStore após salvar")
                }

                Log.d(TAG, "Vídeo salvo na galeria: $uri (displayName=$baseName)")
                Result.success(SavedVideoResult(uri = uri, displayName = baseName))
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar vídeo na galeria", e)
                insertedUri?.let { uri ->
                    runCatching { resolver.delete(uri, null, null) }
                }
                Result.failure(e)
            }
        }

    private fun getNextSequentialDisplayName(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_SEQUENCE, 0) + 1
        prefs.edit().putInt(KEY_SEQUENCE, next).apply()
        return String.format(NAME_PATTERN, next)
    }

    companion object {
        private const val TAG = "MediaStoreVideoGallery"
        private const val MIME_MP4 = "video/mp4"
        private const val RELATIVE_PATH_VIDEOS = "Movies/NetCam"
        private const val PREFS_NAME = "netcam_gallery"
        private const val KEY_SEQUENCE = "gallery_sequence"
        private const val NAME_PATTERN = "Netcampro40_%07d"
    }
}
