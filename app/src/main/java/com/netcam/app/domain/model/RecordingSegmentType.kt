package com.netcam.app.domain.model

import com.netcam.app.domain.config.RecordingTimeConfig

/**
 * Tipos de segmentos gerados a partir da gravação contínua base.
 *
 * Para [FULL_SESSION], [durationSeconds] é **0** — a duração do recorte é calculada
 * na hora do processamento (da abertura da sessão até o instante do pedido).
 */
enum class RecordingSegmentType(val durationSeconds: Int) {
    ONE_MINUTE(RecordingTimeConfig.ONE_MINUTE_SECONDS),
    TWO_MINUTES(RecordingTimeConfig.TWO_MINUTES_SECONDS),
    /**
     * Da abertura da gravação (início da sessão) até o momento em que o usuário
     * segurou Volume+ por 3s (fim efetivo do trecho no arquivo contínuo).
     */
    FULL_SESSION(0),
}
