package com.netcam.app.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class BleDebugStatus {
    IDLE,
    SCANNING,
    FOUND,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

enum class BleClipCommand {
    CLIP_1M,
    CLIP_2M,
    CLIP_SESSION,
}

data class BleDebugUiState(
    val status: BleDebugStatus = BleDebugStatus.IDLE,
    val statusMessage: String = "Aguardando",
    val foundDeviceName: String? = null,
    val foundDeviceAddress: String? = null,
    val connectedDeviceName: String? = null,
    val connectedDeviceAddress: String? = null,
    val lastCommand: String? = null,
    val commandHistory: List<String> = emptyList(),
    /** 0–100 quando o controle BLE envia leitura (ex.: notificação `BATTERY:87`). */
    val batteryPercent: Int? = null,
    /** true quando o protocolo sinaliza que não há leitura (ex.: `BATTERY:NA`). */
    val batteryExplicitlyUnavailable: Boolean = false,
)

class BleDebugController(
    private val appContext: Context,
) {
    private val serviceUuid = UUID.fromString("6f0a0001-8f9b-4c6a-9d55-1f2b3c4d5e01")
    private val characteristicUuid = UUID.fromString("6f0a0002-8f9b-4c6a-9d55-1f2b3c4d5e01")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val officialDeviceName = "NetCamPro_CTRL"

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var isScanning = false
    private var foundDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var autoConnectEnabled = false
    private var lastHandledPayload: String? = null
    private var lastHandledAtMs: Long = 0L

    private val _uiState = MutableStateFlow(BleDebugUiState())
    val uiState: StateFlow<BleDebugUiState> = _uiState.asStateFlow()
    private val _clipCommands = MutableSharedFlow<BleClipCommand>(extraBufferCapacity = 16)
    val clipCommands: SharedFlow<BleClipCommand> = _clipCommands.asSharedFlow()

    fun requiredRuntimePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return requiredRuntimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            emitError("Permissões BLE não concedidas")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emitError("Bluetooth desativado ou indisponível")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            emitError("Scanner BLE indisponível")
            return
        }

        stopScan()
        foundDevice = null
        isScanning = true
        _uiState.value =
            _uiState.value.copy(
                status = BleDebugStatus.SCANNING,
                statusMessage = "Procurando $officialDeviceName...",
                foundDeviceName = null,
                foundDeviceAddress = null,
            )
        Log.d(TAG, "BLE scan iniciado")
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        runCatching {
            scanner.startScan(null, settings, scanCallback)
        }.onFailure {
            emitError("Falha ao iniciar scan BLE")
        }
    }

    fun startAutoConnect() {
        autoConnectEnabled = true
        if (isConnected()) return
        startScan()
    }

    fun stopAutoConnect(keepConnection: Boolean = true) {
        autoConnectEnabled = false
        stopScan()
        if (!keepConnection) {
            disconnect()
        }
    }

    fun isConnected(): Boolean = _uiState.value.status == BleDebugStatus.CONNECTED

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        isScanning = false
        Log.d(TAG, "BLE scan parado")
    }

    @SuppressLint("MissingPermission")
    fun connectToFound() {
        if (!hasRequiredPermissions()) {
            emitError("Permissões BLE não concedidas")
            return
        }
        val device = foundDevice
        if (device == null) {
            emitError("Nenhum dispositivo encontrado para conectar")
            return
        }

        stopScan()
        disconnect()
        _uiState.value =
            _uiState.value.copy(
                status = BleDebugStatus.CONNECTING,
                statusMessage = "Conectando em ${device.name ?: device.address}...",
            )
        Log.d(TAG, "Conectando em ${device.address}")
        gatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        _uiState.value =
            _uiState.value.copy(
                status = BleDebugStatus.DISCONNECTED,
                statusMessage = "Desconectado",
                connectedDeviceName = null,
                connectedDeviceAddress = null,
                batteryPercent = null,
                batteryExplicitlyUnavailable = false,
            )
    }

    private fun emitError(message: String) {
        _uiState.value =
            _uiState.value.copy(
                status = BleDebugStatus.ERROR,
                statusMessage = message,
            )
        Log.e(TAG, message)
    }

    private fun onCommandReceived(command: String) {
        val normalized = command.trim()
        val now = System.currentTimeMillis()
        val isDuplicate =
            lastHandledPayload == normalized && (now - lastHandledAtMs) <= COMMAND_DEDUP_WINDOW_MS
        if (isDuplicate) {
            Log.d(TAG, "Comando BLE duplicado ignorado: $normalized")
            return
        }
        lastHandledPayload = normalized
        lastHandledAtMs = now
        val currentHistory = _uiState.value.commandHistory
        val updatedHistory = (listOf(normalized) + currentHistory).take(MAX_HISTORY)
        _uiState.value =
            _uiState.value.copy(
                lastCommand = normalized,
                commandHistory = updatedHistory,
                statusMessage = "Comando recebido: $normalized",
            )
        Log.d(TAG, "Comando BLE recebido: $normalized")

        if (tryApplyBatteryPayload(normalized)) {
            return
        }

        val mapped =
            when (normalized) {
                "CLIP_1M" -> BleClipCommand.CLIP_1M
                "CLIP_2M" -> BleClipCommand.CLIP_2M
                "CLIP_SESSION" -> BleClipCommand.CLIP_SESSION
                else -> null
            }
        if (mapped != null) {
            scope.launch {
                _clipCommands.emit(mapped)
            }
        } else {
            _uiState.value =
                _uiState.value.copy(
                    statusMessage = "Comando BLE desconhecido: $normalized",
                )
            Log.w(TAG, "Comando BLE desconhecido: $normalized")
        }
    }

    /**
     * Extensão não destrutiva do protocolo NetCamPro: notificações com carga do controle.
     * Ex.: `BATTERY:87`, `BATTERY:NA`, `BAT 50`.
     */
    private fun tryApplyBatteryPayload(payload: String): Boolean {
        val unavailable =
            Regex("""(?i)^(?:BATTERY|BAT)\s*[:=]\s*(NA|N/?A|NONE|UNAVAILABLE|INDISPONIVEL)\s*$""")
                .matches(payload)
        if (unavailable) {
            _uiState.value =
                _uiState.value.copy(
                    batteryPercent = null,
                    batteryExplicitlyUnavailable = true,
                    statusMessage = "Bateria do controle: indisponível",
                )
            Log.d(TAG, "BLE bateria: indisponível (protocolo)")
            return true
        }
        val percentMatch =
            Regex("""(?i)^(?:BATTERY|BAT)\s*[:=]?\s*(\d{1,3})\s*$""")
                .find(payload)
                ?: return false
        val pct = percentMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return false
        _uiState.value =
            _uiState.value.copy(
                batteryPercent = pct,
                batteryExplicitlyUnavailable = false,
                statusMessage = "Bateria: $pct%",
            )
        Log.d(TAG, "BLE bateria: $pct%")
        return true
    }

    private fun isTargetDevice(result: ScanResult): Boolean {
        val scanName = result.scanRecord?.deviceName ?: result.device.name
        val nameMatches = scanName == officialDeviceName
        val advertisedUuids = result.scanRecord?.serviceUuids.orEmpty()
        val serviceMatches = advertisedUuids.any { it.uuid == serviceUuid }
        return nameMatches || serviceMatches
    }

    private val scanCallback =
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (!isTargetDevice(result)) return

                foundDevice = result.device
                stopScan()
                _uiState.value =
                    _uiState.value.copy(
                        status = BleDebugStatus.FOUND,
                        statusMessage = "Controle encontrado",
                        foundDeviceName = result.device.name ?: "Sem nome",
                        foundDeviceAddress = result.device.address,
                    )
                Log.d(
                    TAG,
                    "Controle encontrado name=${result.device.name} address=${result.device.address} rssi=${result.rssi}",
                )
                if (autoConnectEnabled) {
                    connectToFound()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                emitError("Falha no scan BLE (code=$errorCode)")
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitError("Erro de conexão BLE (status=$status)")
                    runCatching { gatt.close() }
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _uiState.value =
                            _uiState.value.copy(
                                status = BleDebugStatus.CONNECTING,
                                statusMessage = "Conectado. Descobrindo serviços...",
                                connectedDeviceName = gatt.device.name ?: "Sem nome",
                                connectedDeviceAddress = gatt.device.address,
                            )
                        Log.d(TAG, "BLE conectado em ${gatt.device.address}")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                _uiState.value =
                    _uiState.value.copy(
                        status = BleDebugStatus.DISCONNECTED,
                        statusMessage = "Dispositivo desconectado",
                        connectedDeviceName = null,
                        connectedDeviceAddress = null,
                        batteryPercent = null,
                        batteryExplicitlyUnavailable = false,
                    )
                        Log.d(TAG, "BLE desconectado")
                        runCatching { gatt.close() }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitError("Falha ao descobrir services (status=$status)")
                    return
                }

                val service: BluetoothGattService? = gatt.getService(serviceUuid)
                if (service == null) {
                    emitError("Service BLE não encontrado")
                    return
                }
                val characteristic: BluetoothGattCharacteristic? = service.getCharacteristic(characteristicUuid)
                if (characteristic == null) {
                    emitError("Characteristic BLE não encontrada")
                    return
                }

                val supportsNotify =
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                if (!supportsNotify) {
                    emitError("Characteristic sem suporte a NOTIFY")
                    return
                }

                val notifyEnabled = gatt.setCharacteristicNotification(characteristic, true)
                if (!notifyEnabled) {
                    emitError("Falha ao habilitar notifications")
                    return
                }

                val cccd: BluetoothGattDescriptor? = characteristic.getDescriptor(cccdUuid)
                if (cccd == null) {
                    emitError("CCCD (0x2902) não encontrado na characteristic")
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val writeOk = gatt.writeDescriptor(cccd)
                if (!writeOk) {
                    emitError("Falha ao escrever CCCD de notificação")
                    return
                }
                _uiState.value =
                    _uiState.value.copy(
                        status = BleDebugStatus.CONNECTING,
                        statusMessage = "Ativando notify...",
                    )
                Log.d(TAG, "CCCD write solicitado com sucesso")
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                if (descriptor.uuid != cccdUuid) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitError("Falha ao ativar notify (status=$status)")
                    return
                }
                val value = descriptor.value
                val notifyEnabled =
                    value != null &&
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (!notifyEnabled) {
                    emitError("CCCD escrito sem valor de notify")
                    return
                }
                _uiState.value =
                    _uiState.value.copy(
                        status = BleDebugStatus.CONNECTED,
                        statusMessage = "Conectado e escutando comandos",
                        batteryPercent = null,
                        batteryExplicitlyUnavailable = false,
                    )
                Log.d(TAG, "BLE pronto para notifications")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                if (characteristic.uuid != characteristicUuid) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                val payload = characteristic.value?.toString(Charsets.UTF_8).orEmpty()
                onCommandReceived(payload)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)
                if (characteristic.uuid != characteristicUuid) return
                onCommandReceived(value.toString(Charsets.UTF_8))
            }
        }

    companion object {
        private const val TAG = "NetCamBleDebug"
        private const val MAX_HISTORY = 20
        private const val COMMAND_DEDUP_WINDOW_MS = 350L
    }
}
