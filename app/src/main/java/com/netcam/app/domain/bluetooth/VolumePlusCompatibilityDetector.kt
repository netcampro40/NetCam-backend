package com.netcam.app.domain.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.core.content.ContextCompat
import java.util.Locale

data class VolumePlusPairedCompatibility(
    val hasPairedCompatibleDevice: Boolean,
    val compatibleDeviceNames: List<String>,
)

/**
 * Android não expõe um “detector universal” para controles genéricos que emitem Volume+ via Bluetooth.
 *
 * Então fazemos uma identificação heurística dos dispositivos já pareados usando:
 * - nome do dispositivo (palavras-chave comuns)
 * - filtro conservador para reduzir falsos positivos
 *
 * O teste manual na tela serve para validar de forma definitiva.
 */
object VolumePlusCompatibilityDetector {
    private val keywords = listOf(
        "netcam",
        "volume",
        "vol+",
        "vol+",
        "media",
        "remote",
        "controle",
        "controller",
    )

    fun getPairedCompatibleDeviceSummary(
        context: Context,
        adapter: BluetoothAdapter?,
    ): VolumePlusPairedCompatibility {
        if (adapter == null) {
            return VolumePlusPairedCompatibility(
                hasPairedCompatibleDevice = false,
                compatibleDeviceNames = emptyList(),
            )
        }

        val hasConnectPermission =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        if (!hasConnectPermission) {
            return VolumePlusPairedCompatibility(
                hasPairedCompatibleDevice = false,
                compatibleDeviceNames = emptyList(),
            )
        }

        val bondedDevices =
            runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

        val compatible =
            bondedDevices.filter { device ->
                isLikelyVolumePlusCompatible(device)
            }

        val names = compatible.mapNotNull { it.name }.distinct().sorted()

        return VolumePlusPairedCompatibility(
            hasPairedCompatibleDevice = compatible.isNotEmpty(),
            compatibleDeviceNames = names,
        )
    }

    private fun isLikelyVolumePlusCompatible(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        val n = name.lowercase(Locale.ROOT)
        if (n.isBlank()) return false

        return keywords.any { keyword ->
            n.contains(keyword)
        }
    }
}

