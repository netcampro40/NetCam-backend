package com.netcam.app.domain.recording

/**
 * Controla a gravação contínua base durante uma sessão.
 *
 * [stopBaseRecording] pode receber um callback [onFinalized] que será
 * invocado quando a gravação estiver realmente finalizada e o arquivo
 * estiver pronto (ex.: após CameraX emitir o evento Finalize).
 */
interface ContinuousRecordingController {
    fun startBaseRecording()

    /**
     * Para a gravação. Se [onFinalized] for fornecido, será chamado
     * quando o arquivo de saída estiver realmente fechado e válido.
     */
    fun stopBaseRecording(onFinalized: (() -> Unit)? = null)

    fun isBaseRecordingActive(): Boolean
}

