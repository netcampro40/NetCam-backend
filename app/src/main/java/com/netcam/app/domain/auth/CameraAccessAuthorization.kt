package com.netcam.app.domain.auth

data class CameraAccessAuthorization(
    val arenaToken: String,
    val arenaName: String,
    val authorizedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)
