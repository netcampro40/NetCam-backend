package com.netcam.app.domain.auth

interface CameraAccessRepository {
    suspend fun getAuthorization(): CameraAccessAuthorization?

    suspend fun saveAuthorization(authorization: CameraAccessAuthorization)

    suspend fun clearAuthorization()

    suspend fun isAuthorizedNow(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean
}
