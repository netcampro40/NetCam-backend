package com.netcam.app.domain.model

/**
 * Estado de conexão do controle para UI de pareamento e leitura de bateria.
 * (Controle BLE próprio NetCamPro ou fluxos que exponham o mesmo contrato de UI.)
 */
enum class ControlConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
