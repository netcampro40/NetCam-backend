package com.netcam.app.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Resolve cameraIds traseiros para "main" e "wide" (ultra-wide).
 *
 * Observação: muitos aparelhos expõem ultra-wide como outro cameraId traseiro, não como zoom < 1x.
 * Aqui tentamos inferir isso pela menor focal length disponível.
 */
class BackCameraIdResolver(
    private val appContext: Context,
) {
    data class BackCameraIds(
        val mainCameraId: String?,
        val wideCameraId: String?,
    )

    fun resolve(): BackCameraIds {
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrElse { emptyList() }

        val backIds = ids.filter { id ->
            runCatching {
                val cc = cameraManager.getCameraCharacteristics(id)
                cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }

        // Coleta focal mínima por cameraId traseiro.
        val backFocals = backIds.mapNotNull { id ->
            runCatching {
                val cc = cameraManager.getCameraCharacteristics(id)
                val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minFocal = focals?.minOrNull()
                if (DEBUG_LOGS) {
                    val focalsStr = focals?.joinToString(prefix = "[", postfix = "]")
                    Log.d(TAG, "backId=$id focals=$focalsStr min=$minFocal")
                }
                if (minFocal == null) null else id to minFocal
            }.getOrNull()
        }.sortedBy { it.second }

        if (backFocals.isEmpty()) {
            if (DEBUG_LOGS) Log.d(TAG, "Nenhuma focal encontrada; sem mapeamento físico")
            return BackCameraIds(mainCameraId = null, wideCameraId = null)
        }

        // Heurística:
        // - wide: menor focal
        // - main: focal "do meio" quando houver 3+; caso 2, o maior tende a ser main
        val focalsOnly = backFocals.map { it.second }
        val mainFocal = when {
            focalsOnly.size >= 3 -> focalsOnly[focalsOnly.size / 2]
            else -> focalsOnly.maxOrNull()!!
        }

        val mainId = backFocals.minByOrNull { kotlin.math.abs(it.second - mainFocal) }?.first

        val candidateWide = backFocals.first()
        val wideRatio = candidateWide.second / mainFocal

        val wideId = if (wideRatio < 0.90f && candidateWide.first != mainId) {
            candidateWide.first
        } else {
            null
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, "resolved mainId=$mainId mainFocal=$mainFocal wideId=$wideId wideRatio=$wideRatio backIds=$backIds")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                backIds.forEach { id ->
                    runCatching {
                        val cc = cameraManager.getCameraCharacteristics(id)
                        val range = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        Log.d(TAG, "backId=$id CONTROL_ZOOM_RATIO_RANGE=$range")
                    }
                }
            }
        }

        return BackCameraIds(mainCameraId = mainId, wideCameraId = wideId)
    }

    companion object {
        private const val TAG = "BackCameraIdResolver"
        private const val DEBUG_LOGS = false
    }
}

