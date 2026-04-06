package com.netcam.app.domain.camera

/**
 * Fornece um "catálogo" simples das opções de lente/zoom do aparelho,
 * baseado no que o hardware realmente expõe.
 */
interface LensCatalogProvider {
    fun getCatalog(): LensCatalog
}

data class LensCatalog(
    /** Zoom ratios discretos para a câmera traseira (ex.: 0.6, 1.0, 2.0). */
    val backZoomRatios: List<Float>,
    /** Se existe câmera frontal disponível. */
    val hasFrontCamera: Boolean,
    /** Se há evidência concreta de wide traseira acessível para app terceiro. */
    val hasRealWideSupport: Boolean = false,
    /** Motivo técnico usado para classificar suporte (ou ausência) de wide. */
    val wideSupportEvidence: String = "no_evidence",
)

