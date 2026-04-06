package com.netcam.app.domain.config



import com.netcam.app.domain.model.RecordingSegment

import java.time.Instant

import java.time.temporal.ChronoUnit



/**

 * Janela de retenção para clipes **temporários** (sem [RecordingSegment.galleryUri] exportado).

 *

 * Regra: idade do clipe = agora − [RecordingSegment.requestedAt].

 * Se idade **>** [retentionMaxAgeSeconds] → expira (remove registro + arquivo).

 *

 * Retenção oficial: **48 horas**.

 */

object TemporaryClipRetention {



    const val PRODUCTION_RETENTION_HOURS: Int = 48



    /** Intervalo entre rechecagens da retenção com a galeria aberta (~3 min). */

    private const val GALLERY_RETENTION_POLL_MS: Long = 180_000L



    /** Texto do banner informativo na galeria. */

    const val GALLERY_RETENTION_HINT: String =

        "Clipes não salvos serão removidos automaticamente em 48h"



    /** Segundos máximos que um temporário pode ficar antes de expirar (48h). */

    fun retentionMaxAgeSeconds(): Long = PRODUCTION_RETENTION_HOURS * 3600L



    /** Idade do clipe em segundos (piso por segundo civil). */

    fun clipAgeSeconds(requestedAt: Instant, now: Instant): Long =

        ChronoUnit.SECONDS.between(requestedAt, now).coerceAtLeast(0L)



    /**

     * Temporário não exportado à galeria do sistema cuja idade é **estritamente maior** que a retenção.

     */

    fun shouldExpireTemporaryClip(segment: RecordingSegment, now: Instant): Boolean {

        if (!segment.galleryUri.isNullOrEmpty()) return false

        val maxAge = retentionMaxAgeSeconds()

        return clipAgeSeconds(segment.requestedAt, now) > maxAge

    }



    /** Texto curto para UI (banner da galeria). */

    fun retentionHintForUser(): String = GALLERY_RETENTION_HINT



    /** Intervalo para reavaliar retenção enquanto a tela da galeria está aberta. */

    fun galleryRetentionPollIntervalMs(): Long = GALLERY_RETENTION_POLL_MS

}


