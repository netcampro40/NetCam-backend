package com.netcam.app.navigation

sealed class NetCamRoute(val route: String) {
    data object Home : NetCamRoute("home")
    data object AccessValidation : NetCamRoute("access-validation")
    data object Camera : NetCamRoute("camera")
    data object Gallery : NetCamRoute("gallery")
    data object CameraDiagnostics : NetCamRoute("camera-diagnostics")
    data object BleDebug : NetCamRoute("ble-debug")
    data object VolumePlusControl : NetCamRoute("volume-plus-control")
}

