package com.netcam.app.domain.session

interface NetCamSessionController {
    fun startSession()
    fun stopSession()
    fun isSessionActive(): Boolean
}

