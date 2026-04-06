package com.netcam.app.ui.screens.volume

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.netcam.app.di.AppGraph
import com.netcam.app.domain.bluetooth.VolumePlusCompatibilityDetector
import com.netcam.app.domain.model.ControlConnectionState
import com.netcam.app.ui.components.NetCamTopBar
import com.netcam.app.ui.screens.control.ControlBatteryLine
import com.netcam.app.ui.screens.control.controlBatteryStatusText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat

@Composable
fun VolumePlusControlRoute(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bluetoothAdapter = rememberBluetoothAdapter()
    val bluetoothEnabled = bluetoothAdapter?.isEnabled == true

    val pairedCompatibility =
        remember(bluetoothAdapter, bluetoothEnabled) {
            VolumePlusCompatibilityDetector.getPairedCompatibleDeviceSummary(
                context = context,
                adapter = bluetoothAdapter,
            )
        }

    val lastTestValidatedAtMs by
        AppGraph.volumeButtonGestureInterpreter.lastTestValidatedAtMs.collectAsState()

    val connectedWindowMs = 120_000L
    val isConnectedNow =
        lastTestValidatedAtMs?.let { nowMs ->
            System.currentTimeMillis() - nowMs <= connectedWindowMs
        } ?: false

    val statusText =
        when {
            !bluetoothEnabled -> "Bluetooth desligado"
            isConnectedNow -> "Controle pronto para uso"
            pairedCompatibility.hasPairedCompatibleDevice -> "Controle pareado"
            else -> "Controle não pareado"
        }

    val statusColor: Color =
        when {
            !bluetoothEnabled -> MaterialTheme.colorScheme.error
            isConnectedNow -> Color(0xFF0E9F6E)
            else -> MaterialTheme.colorScheme.primary
        }

    val hasControlContext = pairedCompatibility.hasPairedCompatibleDevice || isConnectedNow
    val volumePlusBatteryLineText =
        controlBatteryStatusText(
            connectionState =
                if (hasControlContext) {
                    ControlConnectionState.CONNECTED
                } else {
                    ControlConnectionState.DISCONNECTED
                },
            batteryPercent = null,
            batteryExplicitlyUnavailable = false,
            transportCanReportBattery = false,
        )

    val permissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

    val bluetoothEnableLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {}

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun ensureBluetoothEnabled() {
        if (bluetoothEnabled) return
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(intent)
    }

    fun ensurePermissionsThenOpenBluetoothSettings() {
        val perms =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val missing =
            perms.filter {
                ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
            return
        }
        // Fluxo oficial e mais confiável: abrir configurações do Bluetooth.
        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun startVolumePlusTest() {
        if (!bluetoothEnabled) return
        if (isTesting) return

        testStatus = "Escutando Volume+ por alguns segundos…"
        isTesting = true

        // Ativa modo de teste no interpreter: captura o evento Volume+ e
        // evita que a lógica de salvar clipes seja acionada.
        AppGraph.volumeButtonGestureInterpreter.setControlTestModeActive(true)

        val testDurationMs = 6_000L
        scope.launch {
            val detectedAtMs =
                withTimeoutOrNull(testDurationMs) {
                    AppGraph
                        .volumeButtonGestureInterpreter
                        .controlTestDetectedAtMs
                        .filterNotNull()
                        .first()
                }

            AppGraph.volumeButtonGestureInterpreter.setControlTestModeActive(false)
            isTesting = false
            testStatus =
                if (detectedAtMs != null) "Comando detectado. Teste deu certo."
                else "Nenhum comando detectado. Tente novamente."
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        NetCamTopBar(title = "Controle compatível (Volume+)", onBack = onBack)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                    ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        maxLines = 2,
                    )
                    if (pairedCompatibility.compatibleDeviceNames.isNotEmpty()) {
                        Text(
                            text = "Pareado: ${pairedCompatibility.compatibleDeviceNames.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bluetoothEnabled) {
                        Text(
                            text = "Abra \"Filmar\" e use o botão do controle (Volume+).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Ative o Bluetooth para parear e usar o controle compatível (Volume+).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (volumePlusBatteryLineText != null) {
                        ControlBatteryLine(
                            text = volumePlusBatteryLineText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            if (!bluetoothEnabled) {
                Button(onClick = { ensureBluetoothEnabled() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ativar Bluetooth")
                }
            }

            if (bluetoothEnabled) {
                Button(
                    onClick = { ensurePermissionsThenOpenBluetoothSettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Abrir pareamento no Bluetooth do sistema")
                }
            }

            if (bluetoothEnabled) {
                Button(
                    onClick = { startVolumePlusTest() },
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Testar controle (Volume+)")
                }
            }

            if (testStatus != null) {
                Text(
                    text = testStatus!!,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            OutlinedButton(
                onClick = onOpenCamera,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Abrir Filmar")
            }
        }

        Box(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun rememberBluetoothAdapter(): BluetoothAdapter? {
    return androidx.compose.runtime.remember {
        BluetoothAdapter.getDefaultAdapter()
    }
}

