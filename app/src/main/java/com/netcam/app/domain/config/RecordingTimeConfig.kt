package com.netcam.app.domain.config

/**
 * Configuração centralizada de tempos dos clipes de 1 e 2 minutos.
 *
 * O atalho “seção inteira” (Volume+ 3s) não usa limite fixo aqui: a duração
 * segue o tempo real da sessão até o instante do pedido.
 */
object RecordingTimeConfig {
    /** Duração padrão em segundos para segmentos de 1 minuto. */
    const val ONE_MINUTE_SECONDS: Int = 60

    /** Duração padrão em segundos para segmentos de 2 minutos. */
    const val TWO_MINUTES_SECONDS: Int = 2 * 60
}
