package com.netcam.app.data.storage

import com.netcam.app.domain.model.NetCamVideo
import com.netcam.app.domain.storage.NetCamStorage
import java.time.Instant

class FakeNetCamStorage : NetCamStorage {
    override suspend fun listNetCamVideos(): List<NetCamVideo> {
        val now = Instant.now()
        return listOf(
            NetCamVideo(
                id = "mock-1",
                title = "NetCam - Melhor momento (1 min)",
                createdAt = now.minusSeconds(3600),
                durationSeconds = 60,
            ),
            NetCamVideo(
                id = "mock-2",
                title = "NetCam - Melhor momento (2 min)",
                createdAt = now.minusSeconds(7200),
                durationSeconds = 120,
            ),
            NetCamVideo(
                id = "mock-3",
                title = "NetCam - Sessão inteira (exemplo)",
                createdAt = now.minusSeconds(24 * 3600),
                durationSeconds = 45 * 60,
            ),
        )
    }
}

