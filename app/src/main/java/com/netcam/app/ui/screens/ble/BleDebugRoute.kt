package com.netcam.app.ui.screens.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netcam.app.data.ble.BleDebugController
import com.netcam.app.data.ble.BleDebugStatus
import com.netcam.app.domain.model.ControlConnectionState
import com.netcam.app.ui.components.NetCamTopBar
import com.netcam.app.ui.screens.control.ControlBatteryLine
import com.netcam.app.ui.screens.control.controlBatteryStatusText

@Composable
fun BleDebugRoute(
    contentPadding: PaddingValues,
    controller: BleDebugController,
    onBack: () -> Unit,
) {
    val uiState by controller.uiState.collectAsState()
    val permissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            if (controller.hasRequiredPermissions()) {
                controller.startScan()
            }
        }
    val bluetoothEnableLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            if (controller.isBluetoothEnabled() && controller.hasRequiredPermissions()) {
                controller.startScan()
            }
        }

    fun ensureBleReadyThenScan() {
        if (!controller.isBluetoothEnabled()) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(intent)
            return
        }
        if (controller.hasRequiredPermissions()) {
            controller.startScan()
            return
        }
        val perms =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        permissionsLauncher.launch(perms)
    }

    val isConnected = uiState.status == BleDebugStatus.CONNECTED
    val batteryLineText =
        controlBatteryStatusText(
            connectionState =
                if (isConnected) {
                    ControlConnectionState.CONNECTED
                } else {
                    ControlConnectionState.DISCONNECTED
                },
            batteryPercent = uiState.batteryPercent,
            batteryExplicitlyUnavailable = uiState.batteryExplicitlyUnavailable,
            transportCanReportBattery = true,
        )
    val mainActionLabel =
        when (uiState.status) {
            BleDebugStatus.CONNECTED -> "Conectado"
            BleDebugStatus.CONNECTING -> "Conectando..."
            BleDebugStatus.SCANNING -> "Procurando controle..."
            BleDebugStatus.FOUND -> "Conectar ao controle"
            else -> "Procurar controle"
        }
    val statusColor =
        when (uiState.status) {
            BleDebugStatus.CONNECTED -> Color(0xFF0E9F6E)
            BleDebugStatus.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        NetCamTopBar(title = "Parear controle", onBack = onBack)

        LaunchedEffect(Unit) {
            controller.onPairingScreenVisible()
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Conecte seu controle NetCam para disparar clipes remotamente.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Status do controle",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Dispositivo: ${uiState.connectedDeviceName ?: uiState.foundDeviceName ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Endereco: ${uiState.connectedDeviceAddress ?: uiState.foundDeviceAddress ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (batteryLineText != null) {
                        ControlBatteryLine(
                            text = batteryLineText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (!controller.isBluetoothEnabled()) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        bluetoothEnableLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ativar Bluetooth")
                }
            }

            Button(
                onClick = {
                    if (isConnected) return@Button
                    if (uiState.status == BleDebugStatus.FOUND) {
                        controller.connectToFound()
                    } else {
                        ensureBleReadyThenScan()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled =
                    !isConnected &&
                        uiState.status != BleDebugStatus.CONNECTING &&
                        uiState.status != BleDebugStatus.SCANNING,
            ) {
                Text(mainActionLabel)
            }

            if (isConnected) {
                OutlinedButton(
                    onClick = { controller.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Desconectar controle")
                }
            }

            Text(
                text = "Ultimo comando recebido: ${uiState.lastCommand ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text(
                    text = "Permissoes BLE: ${if (controller.hasRequiredPermissions()) "OK" else "pendentes"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                        shape = MaterialTheme.shapes.medium,
                    )
                    .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Historico recente", style = MaterialTheme.typography.titleSmall)
                uiState.commandHistory.take(8).forEach { command ->
                    Text(text = command, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
