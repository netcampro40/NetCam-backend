package com.netcam.app.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import com.netcam.app.domain.camera.LensCatalog
import com.netcam.app.domain.camera.LensCatalogProvider
import kotlin.math.abs

/**
 * Detecta opções reais de lente/zoom usando Camera2:
 * - Em aparelhos com logical multi-camera, usa as physical cameras e suas focal lengths
 *   para inferir ratios (ex.: 0.6x, 1x, 2x).
 * - Em fallback, tenta usar range de zoom e expor apenas 1x (e eventualmente um "wide" < 1x).
 */
class Camera2LensCatalogProvider(
    private val appContext: Context,
) : LensCatalogProvider {
    override fun getCatalog(): LensCatalog {
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrElse { emptyList() }
        if (DEBUG_LOGS) {
            Log.d(TAG, "cameraIdList=${ids.joinToString(prefix = "[", postfix = "]")}")
        }
        val hasFront = ids.any { id ->
            runCatching {
                val cc = cameraManager.getCameraCharacteristics(id)
                cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        }

        val backIds = ids.filter { id ->
            runCatching {
                val cc = cameraManager.getCameraCharacteristics(id)
                cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }

        val wideEvidence = detectWideEvidence(cameraManager = cameraManager, backIds = backIds)

        if (DEBUG_LOGS) {
            val logical = backIds.filter { isLogicalMultiCamera(cameraManager, it) }
            Log.d(TAG, "backIds=${backIds.joinToString(prefix = "[", postfix = "]")} logical=${logical.joinToString(prefix = "[", postfix = "]")}")
        }

        // Estratégia 1 (robusta): varre todos os cameraIds traseiros e infere ratios por focal length.
        // Isso cobre aparelhos onde ultra-wide é exposta como outro cameraId (não como physical de uma logical camera).
        val scanAllBackIds = inferBackZoomRatiosByScanningBackIds(cameraManager, backIds)
        if (scanAllBackIds.size > 1) {
            val hasWideRatio = scanAllBackIds.any { it < 0.98f }
            return LensCatalog(
                backZoomRatios = scanAllBackIds,
                hasFrontCamera = hasFront,
                hasRealWideSupport = wideEvidence.hasWideSupport || hasWideRatio,
                wideSupportEvidence =
                    if (hasWideRatio) {
                        "back_zoom_ratio_below_1.0"
                    } else {
                        wideEvidence.evidence
                    },
            )
        }

        // Estratégia 2: tenta a logical multi-camera (physical cameras), se existir.
        val backPrimary = backIds.firstOrNull { id -> isLogicalMultiCamera(cameraManager, id) } ?: backIds.firstOrNull()

        if (backPrimary == null) {
            return LensCatalog(
                backZoomRatios = listOf(1f),
                hasFrontCamera = hasFront,
                hasRealWideSupport = false,
                wideSupportEvidence = "no_back_camera",
            )
        }

        val inferred = inferBackZoomRatios(cameraManager, backPrimary, backIds)
        val normalized = inferred.ifEmpty { listOf(1f) }
        val hasWideRatio = normalized.any { it < 0.98f }
        return LensCatalog(
            backZoomRatios = normalized,
            hasFrontCamera = hasFront,
            hasRealWideSupport = wideEvidence.hasWideSupport || hasWideRatio,
            wideSupportEvidence =
                if (hasWideRatio) {
                    "zoom_ratio_range_or_focal_inference_below_1.0"
                } else {
                    wideEvidence.evidence
                },
        )
    }

    private data class WideEvidence(
        val hasWideSupport: Boolean,
        val evidence: String,
    )

    private fun detectWideEvidence(
        cameraManager: CameraManager,
        backIds: List<String>,
    ): WideEvidence {
        if (backIds.isEmpty()) {
            return WideEvidence(hasWideSupport = false, evidence = "no_back_camera")
        }

        if (backIds.size > 1) {
            return WideEvidence(hasWideSupport = true, evidence = "multiple_back_camera_ids")
        }

        val singleBackId = backIds.first()
        val cc = runCatching { cameraManager.getCameraCharacteristics(singleBackId) }.getOrNull()
            ?: return WideEvidence(false, "camera_characteristics_unavailable")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null && range.lower < 0.98f) {
                return WideEvidence(hasWideSupport = true, evidence = "zoom_ratio_range_min_below_1.0")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = cc.physicalCameraIds.toList()
            if (physicalIds.size > 1) {
                val minFocals =
                    physicalIds.mapNotNull { pid ->
                        runCatching {
                            val pcc = cameraManager.getCameraCharacteristics(pid)
                            pcc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                        }.getOrNull()
                    }.sorted()
                if (minFocals.size > 1) {
                    val base = pickBaseFocal(minFocals)
                    val hasWideByPhysical = minFocals.any { focal -> (focal / base) < 0.90f }
                    if (hasWideByPhysical) {
                        return WideEvidence(
                            hasWideSupport = true,
                            evidence = "logical_multi_camera_physical_focal_below_1.0",
                        )
                    }
                }
            }
        }

        return WideEvidence(hasWideSupport = false, evidence = "no_public_wide_evidence")
    }

    private fun inferBackZoomRatios(
        cameraManager: CameraManager,
        backCameraId: String,
        allBackIds: List<String>,
    ): List<Float> {
        return runCatching {
            val backCc = cameraManager.getCameraCharacteristics(backCameraId)

            val focalCandidatesMm = mutableListOf<Float>()

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                backCc.physicalCameraIds.toList()
            } else {
                emptyList()
            }

            if (physicalIds.isNotEmpty()) {
                for (pid in physicalIds) {
                    val cc = cameraManager.getCameraCharacteristics(pid)
                    val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?: continue
                    val minFocal = focals.minOrNull() ?: continue
                    focalCandidatesMm.add(minFocal)
                }
            } else {
                // Sem physical cameras: tenta usar as focal lengths do próprio back camera.
                val focals = backCc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focals != null) {
                    focalCandidatesMm.addAll(focals.toList())
                }
            }

            if (focalCandidatesMm.isEmpty()) {
                // Fallback por capabilities/zoom range
                return@runCatching fallbackRatiosFromZoomCharacteristics(cameraManager, allBackIds)
            }

            val uniqueFocals = focalCandidatesMm
                .sorted()
                .distinctByApprox(tolerance = 0.10f) // mm

            val baseFocal = pickBaseFocal(uniqueFocals)
            val ratios = uniqueFocals
                .map { focal -> focal / baseFocal }
                .distinctByApprox(tolerance = 0.06f)
                .map { clampToSaneRatio(it) }
                .distinctByApprox(tolerance = 0.05f)
                .toMutableList()

            if (ratios.none { abs(it - 1f) <= 0.05f }) {
                ratios.add(1f)
            }

            // Mantém UI simples: prioriza wide(<1), 1x, tele(>1). Se houver múltiplos teles, inclui o mais próximo e o mais distante.
            val sorted = ratios.sorted()
            val below = sorted.filter { it < 0.98f }
            val above = sorted.filter { it > 1.02f }

            val picked = mutableListOf<Float>()
            below.minOrNull()?.let { picked.add(it) }
            picked.add(1f)
            when (above.size) {
                0 -> {}
                1 -> picked.add(above[0])
                else -> {
                    picked.add(above.minOrNull()!!)
                    val far = above.maxOrNull()!!
                    if (abs(far - picked.last()) > 0.20f) picked.add(far)
                }
            }

            val normalized = picked
                .distinctByApprox(tolerance = 0.05f)
                .sorted()

            // Se não apareceu nada < 1x mas o hardware mostra range < 1x, adiciona.
            val fallback = fallbackRatiosFromZoomCharacteristics(cameraManager, allBackIds)
            val fallbackWide = fallback.firstOrNull { it < 0.98f }
            if (fallbackWide != null && normalized.none { it < 0.98f }) {
                (listOf(fallbackWide) + normalized).distinctByApprox(0.05f).sorted()
            } else {
                normalized
            }
        }.getOrElse { e ->
            Log.w(TAG, "Falha ao inferir zoom ratios do aparelho", e)
            listOf(1f)
        }
    }

    private fun inferBackZoomRatiosByScanningBackIds(
        cameraManager: CameraManager,
        backIds: List<String>,
    ): List<Float> {
        val focalsById = backIds.mapNotNull { id ->
            runCatching {
                val cc = cameraManager.getCameraCharacteristics(id)
                val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minF = focals?.minOrNull()
                if (DEBUG_LOGS) {
                    Log.d(TAG, "backId=$id focals=${focals?.joinToString(prefix = "[", postfix = "]")} min=$minF")
                }
                minF?.let { id to it }
            }.getOrNull()
        }

        val focals = focalsById.map { it.second }
            .sorted()
            .distinctByApprox(0.10f)

        if (focals.size <= 1) {
            return emptyList()
        }

        val base = pickBaseFocal(focals)
        val ratios = focals
            .map { clampToSaneRatio(it / base) }
            .distinctByApprox(0.05f)
            .toMutableList()

        if (ratios.none { abs(it - 1f) <= 0.05f }) ratios.add(1f)

        val sorted = ratios.sorted()
        val below = sorted.filter { it < 0.98f }
        val above = sorted.filter { it > 1.02f }

        val picked = mutableListOf<Float>()
        below.minOrNull()?.let { picked.add(it) }
        picked.add(1f)
        above.minOrNull()?.let { picked.add(it) }

        return picked.distinctByApprox(0.05f).sorted()
    }

    private fun fallbackRatiosFromZoomCharacteristics(
        cameraManager: CameraManager,
        backIds: List<String>,
    ): List<Float> {
        // Android 11+ expõe CONTROL_ZOOM_RATIO_RANGE em muitos aparelhos.
        // Se o lower bound < 1, isso é um bom indício (confiável) de "wide" < 1x.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ranges = backIds.mapNotNull { id ->
                runCatching {
                    val cc = cameraManager.getCameraCharacteristics(id)
                    val range = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "backId=$id CONTROL_ZOOM_RATIO_RANGE=$range")
                    }
                    range
                }.getOrNull()
            }
            val minLower = ranges.minOfOrNull { it.lower } ?: 1f
            val maxUpper = ranges.maxOfOrNull { it.upper } ?: 1f
            if (minLower < 0.98f) {
                return listOf(minLower, 1f, maxUpper).distinctByApprox(0.05f).sorted()
            }
        }

        // Fallback final: nada confiável para <1x. Mantém 1x.
        return listOf(1f)
    }

    private fun pickBaseFocal(sortedFocals: List<Float>): Float {
        if (sortedFocals.isEmpty()) return 1f
        return when {
            sortedFocals.size >= 3 -> sortedFocals[sortedFocals.size / 2] // "main" tende a ser o meio
            else -> sortedFocals.maxOrNull()!! // comum em (ultra + main): main é o maior focal
        }.coerceAtLeast(0.1f)
    }

    private fun clampToSaneRatio(ratio: Float): Float {
        // Limites conservadores só para evitar valores absurdos em aparelhos bugados/atípicos
        return ratio.coerceIn(0.3f, 10f)
    }

    private fun isLogicalMultiCamera(cameraManager: CameraManager, cameraId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val cc = cameraManager.getCameraCharacteristics(cameraId)
            val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return@runCatching false
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.getOrDefault(false)
    }

    private fun List<Float>.distinctByApprox(tolerance: Float): List<Float> {
        val out = mutableListOf<Float>()
        for (v in this) {
            if (out.none { abs(it - v) <= tolerance }) out.add(v)
        }
        return out
    }

    companion object {
        private const val TAG = "LensCatalogProvider"
        private const val DEBUG_LOGS = false
    }
}

