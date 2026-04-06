package com.netcam.app.domain.storage

import com.netcam.app.domain.model.NetCamVideo

interface NetCamStorage {
    suspend fun listNetCamVideos(): List<NetCamVideo>
}

