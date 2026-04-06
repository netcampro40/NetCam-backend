package com.netcam.app.domain.recording

import java.io.File

/**
 * Camada responsável por extrair trechos de vídeo do
 * arquivo contínuo gravado durante a sessão.
 *
 * Nesta etapa a implementação concreta ainda é simples,
 * mas a interface já prepara o caminho para evoluções.
 */
interface VideoSegmentExtractor {
    /**
     * Extrai um segmento com [durationSeconds] segundos
     * cujo fim (end) ocorre em um instante específico do
     * arquivo contínuo [source].
     *
     * [endOffsetMillis] é o deslocamento (em ms) do início da gravação contínua
     * até o instante desejado do fim do clipe dentro do arquivo [source].
     *
     * O retorno é o arquivo temporário gerado com o trecho.
     */
    suspend fun extractSegmentAt(
        source: File,
        durationSeconds: Int,
        endOffsetMillis: Long,
    ): File
}

