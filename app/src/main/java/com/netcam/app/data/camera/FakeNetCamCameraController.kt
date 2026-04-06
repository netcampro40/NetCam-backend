package com.netcam.app.data.camera

import com.netcam.app.domain.camera.NetCamCameraController
import com.netcam.app.domain.model.LensOption

class FakeNetCamCameraController : NetCamCameraController {
    override fun availableLenses(): Set<LensOption> {
        return setOf(LensOption.WIDE_1X, LensOption.FRONT, LensOption.ULTRA_WIDE_0_5X)
    }

    override fun startPreview(selectedLens: LensOption) {
        // Stub: real implementation will bind CameraX Preview use case
    }

    override fun stopPreview() {
        // Stub
    }
}

