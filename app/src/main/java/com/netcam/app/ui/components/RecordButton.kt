package com.netcam.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    enabled: Boolean = true,
) {
    // Paleta premium (neutra antes de gravar; vermelha viva durante)
    val idleFill = Color(0xFFD9D9D9).copy(alpha = 0.44f)
    val idleBorder = Color.White.copy(alpha = 0.28f)

    val activeFill = Color(0xFFEF4444).copy(alpha = 0.95f) // vermelho mais vivo
    val activeBorder = Color.White.copy(alpha = 0.22f)

    val fill: Color =
        when {
            isActive -> activeFill
            enabled -> idleFill
            else -> idleFill.copy(alpha = 0.2f)
        }
    val borderColor: Color =
        when {
            isActive -> activeBorder
            enabled -> idleBorder
            else -> idleBorder.copy(alpha = 0.5f)
        }

    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, borderColor, CircleShape)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

