package com.netcam.app.domain.model

enum class ExperimentalWideOption(
    val ratio: Float?,
    val label: String,
) {
    OFF(null, "Desligado"),
    X0_5(0.5f, "0.5x"),
    X0_6(0.6f, "0.6x"),
    X0_7(0.7f, "0.7x"),
    X0_8(0.8f, "0.8x"),
    ;
}

