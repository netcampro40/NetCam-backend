package com.netcam.app.data.recording

import android.content.Context
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

/**
 * Nó central de integração com CameraX para gravação de vídeo.
 *
 * A tela de câmera configura este objeto com o [Context] da aplicação
 * e o use case de [VideoCapture]. O controlador de gravação contínua
 * consome essas referências para iniciar/parar a gravação.
 */
object CameraXVideoEngine {
    @Volatile
    var appContext: Context? = null

    @Volatile
    var videoCapture: VideoCapture<Recorder>? = null

    /**
     * Arquivo de saída atual da gravação contínua.
     * Nesta etapa ele é único por sessão e tratado como temporário.
     */
    @Volatile
    var currentOutputFile: java.io.File? = null

    /**
     * instante (System.currentTimeMillis) em que a gravação contínua base começou.
     * Usado para mapear [RecordingSegment.requestedAt] para o timeline do arquivo contínuo.
     */
    @Volatile
    var currentRecordingStartTimeMs: Long = 0L
}

