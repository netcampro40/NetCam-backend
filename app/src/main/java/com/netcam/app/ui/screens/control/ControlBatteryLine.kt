package com.netcam.app.ui.screens.control

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netcam.app.domain.model.ControlConnectionState

/**
 * Texto derivado para a linha de bateria do controle.
 *
 * @param batteryPercent percentual quando conhecido (0–100); null se ainda não lido.
 * @param batteryExplicitlyUnavailable true quando o protocolo indicou indisponível (ex.: BATTERY:NA).
 * @param transportCanReportBattery false para controle compatível (Volume+) sem leitura no app.
 */
fun controlBatteryStatusText(
    connectionState: ControlConnectionState,
    batteryPercent: Int?,
    batteryExplicitlyUnavailable: Boolean,
    transportCanReportBattery: Boolean,
): String? {
    if (connectionState != ControlConnectionState.CONNECTED) return null
    if (batteryExplicitlyUnavailable) return "Bateria do controle: indisponível"
    if (!transportCanReportBattery) return "Bateria do controle: indisponível"
    if (batteryPercent != null) return "Bateria do controle: $batteryPercent%"
    return "Bateria do controle: lendo..."
}

@Composable
fun ControlBatteryLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.BatteryStd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
