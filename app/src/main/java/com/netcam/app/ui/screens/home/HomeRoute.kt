package com.netcam.app.ui.screens.home

import android.util.Log
import com.netcam.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    contentPadding: PaddingValues,
    onFilmClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onPairControlClick: () -> Unit,
    controlStatusText: String,
    onDebugCameraBypass: () -> Unit = {},
) {
    var logoTapCount by remember { mutableStateOf(0) }
    var lastLogoTapMs by remember { mutableStateOf(0L) }
    val logoTapResetWindowMs = 2_000L
    val logoTapTarget = 5
    val netCamLogoInteractionSource = remember { MutableInteractionSource() }

    fun onNetCamProLogoTap() {
        if (!BuildConfig.DEBUG) return

        val now = System.currentTimeMillis()
        if (now - lastLogoTapMs > logoTapResetWindowMs) {
            logoTapCount = 0
        }
        lastLogoTapMs = now
        logoTapCount += 1
        if (logoTapCount < logoTapTarget) return

        logoTapCount = 0
        Log.d(TAG, "Debug QR bypass activated")
        onDebugCameraBypass()
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
                modifier =
                    Modifier.clickable(
                        indication = null,
                        interactionSource = netCamLogoInteractionSource,
                        onClick = { onNetCamProLogoTap() },
                    ),
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
                onClick = onPairControlClick,
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
                Text(text = "Parear controle", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = controlStatusText,
                modifier = Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val TAG = "HomeRoute"
