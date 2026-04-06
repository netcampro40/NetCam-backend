package com.netcam.app.domain.camera

import com.netcam.app.domain.model.LensOption

interface NetCamCameraController {
    fun availableLenses(): Set<LensOption>
    fun startPreview(selectedLens: LensOption)
    fun stopPreview()
}

