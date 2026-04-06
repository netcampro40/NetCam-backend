package com.netcam.app.ui.components

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Som curto de confirmação imediata de que o comando de clipe foi aceito pelo app.
 *
 * Regras:
 * - não vibra
 * - dispara quando DefaultClipController aceita a solicitação (logo após requestSegment)
 * - não indica conclusão do processamento em segundo plano
 */
object ClipSavedSoundPlayer {
    // Duração propositalmente curta para evitar “poluir” o usuário.
    private const val TONE_DURATION_MS = 120

    /** Ack sonoro imediato ao reconhecer solicitação válida de clipe; thread-safe (executa no thread principal). */
    fun playImmediateClipAckSound() {
        Handler(Looper.getMainLooper()).post {
            playOnMainThread()
        }
    }

    fun playClipSavedSound() {
        playImmediateClipAckSound()
    }

    fun play() {
        playImmediateClipAckSound()
    }

    private fun playOnMainThread() {
        val toneType = ToneGenerator.TONE_PROP_BEEP
        val volume = 100
        // STREAM_ALARM / NOTIFICATION costumam permanecer audíveis durante gravação com microfone;
        // STREAM_MUSIC pode ficar inaudível com foco de áudio da câmera.
        val gen =
            runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }.getOrNull()
                ?: runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume) }.getOrNull()
                ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()
                ?: return

        val started = runCatching { gen.startTone(toneType, TONE_DURATION_MS) }.getOrDefault(false)
        if (!started) {
            runCatching { gen.release() }
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { runCatching { gen.release() } },
            (TONE_DURATION_MS + 80).toLong(),
        )
    }
}

