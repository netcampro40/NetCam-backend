package com.netcam.app.ui.screens.home

import com.netcam.app.BuildConfig
import com.netcam.app.di.AppGraph
import android.bluetooth.BluetoothAdapter
import com.netcam.app.domain.bluetooth.VolumePlusCompatibilityDetector
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeRoute(
    contentPadding: PaddingValues,
    onFilmClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onBleDebugClick: () -> Unit,
    controlStatusLabel: String,
    onVolumePlusControlClick: () -> Unit,
) {
    val context = LocalContext.current
    val bluetoothAdapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val bluetoothEnabled = bluetoothAdapter?.isEnabled == true

    val pairedCompatibility =
        VolumePlusCompatibilityDetector.getPairedCompatibleDeviceSummary(
            context = context,
            adapter = bluetoothAdapter,
        )

    val lastTestValidatedAtMs by
        AppGraph.volumeButtonGestureInterpreter.lastTestValidatedAtMs.collectAsState()

    val connectedWindowMs = 120_000L
    val isConnectedNow =
        lastTestValidatedAtMs?.let { nowMs ->
            System.currentTimeMillis() - nowMs <= connectedWindowMs
        } ?: false

    val volumeStatusText =
        when {
            !bluetoothEnabled -> "Bluetooth desligado"
            !pairedCompatibility.hasPairedCompatibleDevice -> "Controle não pareado"
            isConnectedNow -> "Controle pronto para uso"
            else -> "Controle pareado (faça o teste)"
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Image(
            painter = painterResource(id = com.netcam.app.R.drawable.netcampro_home_bg),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF000000).copy(alpha = 0.42f),
                            Color(0xFF000000).copy(alpha = 0.30f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                // Sobe um pouco título e frase em relação ao layout atual.
                .padding(top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "NetCamPro",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Grave a partida e salve os melhores momentos.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )

            val pillShape = RoundedCornerShape(28.dp)

            Button(
                onClick = onFilmClick,
                shape = pillShape,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.88f),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1B2D5A).copy(alpha = 0.85f),
                        contentColor = Color.White,
                    ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(text = "Filmar", style = MaterialTheme.typography.titleMedium)
            }

            Button(
                onClick = onGalleryClick,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.88f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                shape = pillShape,
            ) {
                Text(text = "Galeria", style = MaterialTheme.typography.titleMedium)
            }

            Button(
                onClick = onVolumePlusControlClick,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.88f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                shape = pillShape,
            ) {
                Text(
                    text = "Controle compatível (Volume+)",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = volumeStatusText,
                modifier = Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )

            if (BuildConfig.DEBUG) {
                Button(
                    onClick = onBleDebugClick,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.88f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    shape = pillShape,
                ) {
                    Text(
                        text = controlStatusLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        if (BuildConfig.DEBUG) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(onClick = onDiagnosticsClick) {
                    Text(
                        text = "Diagnostico de cameras",
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                Text(
                    text = "debug v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_STAMP}",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

