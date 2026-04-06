package com.netcam.app.domain.model

import java.time.Instant

data class NetCamVideo(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val durationSeconds: Int,
)

