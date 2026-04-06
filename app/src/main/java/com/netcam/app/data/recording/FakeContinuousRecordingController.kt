package com.netcam.app.data.recording

import com.netcam.app.domain.recording.ContinuousRecordingController

/**
 * Implementação fake da gravação contínua.
 *
 * Nesta fase, apenas mantém o estado em memória sem gravar mídia de verdade.
 * Futuras etapas irão substituir ou expandir esta classe para integrar com
 * CameraX / MediaRecorder e geração de segmentos.
 */
class FakeContinuousRecordingController : ContinuousRecordingController {
    private var active: Boolean = false

    override fun startBaseRecording() {
        active = true
    }

    override fun stopBaseRecording(onFinalized: (() -> Unit)?) {
        active = false
        onFinalized?.invoke()
    }

    override fun isBaseRecordingActive(): Boolean = active
}

