package com.netcam.app.data.session

import com.netcam.app.domain.session.NetCamSessionController

class FakeNetCamSessionController : NetCamSessionController {
    private var active = false

    override fun startSession() {
        active = true
    }

    override fun stopSession() {
        active = false
    }

    override fun isSessionActive(): Boolean = active
}

