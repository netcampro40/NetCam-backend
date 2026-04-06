package com.netcam.app.data.recording

import android.content.Context
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.netcam.app.domain.recording.ContinuousRecordingController
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Implementação real inicial da gravação contínua usando CameraX.
 *
 * Responsabilidades desta etapa:
 * - iniciar/parar uma gravação contínua única durante a sessão;
 * - gravar um arquivo temporário em armazenamento interno do app;
 * - não gerenciar múltiplos segmentos nem cortes.
 *
 * A integração com o pipeline de CameraX (preview, provider, etc.) é feita
 * via [CameraXVideoEngine], que é configurado pela tela de câmera.
 */
class CameraXContinuousRecordingController(
    private val appContext: Context,
) : ContinuousRecordingController {

    private var active: Boolean = false
    private var currentRecording: Recording? = null
    private var onFinalizedCallback: (() -> Unit)? = null
    private var stopFallbackJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun startBaseRecording() {
        if (active) return
        stopFallbackJob?.cancel()
        stopFallbackJob = null

        val videoCapture: VideoCapture<Recorder> = CameraXVideoEngine.videoCapture ?: run {
            Log.w(TAG, "startBaseRecording chamado sem VideoCapture configurado. Ignorando.")
            return
        }

        try {
            val executor: Executor = ContextCompat.getMainExecutor(appContext)

            // Arquivo temporário único para a sessão atual.
            val outputDir: File = File(appContext.cacheDir, "continuous").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
            val outputFile = File(outputDir, "continuous_session.mp4")

            // Marca o instante de início da gravação contínua base.
            // Esse valor será usado para mapear cada clique (requestedAt)
            // para o timestamp equivalente dentro do arquivo final.
            CameraXVideoEngine.currentRecordingStartTimeMs = System.currentTimeMillis()

            val outputOptions =
                FileOutputOptions
                    .Builder(outputFile)
                    .build()

            val pendingRecording =
                videoCapture
                    .output
                    .prepareRecording(appContext, outputOptions)
                    .withAudioEnabled()

            currentRecording =
                pendingRecording.start(executor) { event ->
                    Log.d(TAG, "Evento de gravação contínua: $event")
                    if (event is VideoRecordEvent.Finalize) {
                        val file = CameraXVideoEngine.currentOutputFile
                        Log.d(
                            TAG,
                            "Finalize recebido. path=${file?.absolutePath}, exists=${file?.exists()}, length=${file?.length()} bytes, error=${event.error}",
                        )
                        finalizeStopIfNeeded("cameraX_finalize_event")
                    }
                }

            CameraXVideoEngine.currentOutputFile = outputFile

            Log.d(
                TAG,
                "Gravação contínua iniciada em ${outputFile.absolutePath}",
            )

            active = true
        } catch (t: Throwable) {
            Log.e(TAG, "Erro ao iniciar gravação base contínua", t)
            active = false
            currentRecording?.close()
            currentRecording = null
        }
    }

    override fun stopBaseRecording(onFinalized: (() -> Unit)?) {
        if (!active) return

        onFinalizedCallback = onFinalized
        stopFallbackJob?.cancel()
        try {
            currentRecording?.stop()
            Log.d(TAG, "stop() chamado; aguardando evento Finalize do CameraX para invocar callback")
            stopFallbackJob =
                scope.launch {
                    delay(STOP_FALLBACK_TIMEOUT_MS)
                    if (active || currentRecording != null || onFinalizedCallback != null) {
                        Log.w(
                            TAG,
                            "Timeout aguardando Finalize do CameraX; forçando liberação da sessão de gravação",
                        )
                        finalizeStopIfNeeded("fallback_timeout")
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro ao parar gravação base contínua", t)
            finalizeStopIfNeeded("stop_exception")
        }
    }

    override fun isBaseRecordingActive(): Boolean = active

    @Synchronized
    private fun finalizeStopIfNeeded(reason: String) {
        stopFallbackJob?.cancel()
        stopFallbackJob = null
        runCatching { currentRecording?.close() }
        currentRecording = null
        active = false
        // NÃO zerar currentRecordingStartTimeMs aqui: o callback onFinalized
        // agenda o processamento dos segmentos (com delay). Eles precisam
        // ler esse valor para calcular endOffsetMillis por segmento.
        // O valor será sobrescrito na próxima startBaseRecording().
        val callback = onFinalizedCallback
        onFinalizedCallback = null
        Log.d(TAG, "finalizeStopIfNeeded reason=$reason callback=${callback != null}")
        callback?.invoke()
    }

    companion object {
        private const val TAG = "CameraXContinuousRecording"
        private const val STOP_FALLBACK_TIMEOUT_MS = 1_500L
    }
}

