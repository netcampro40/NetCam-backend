package com.netcam.app.ui.screens.access

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

@Composable
fun AccessValidationRoute(
    contentPadding: PaddingValues,
    viewModel: AccessValidationViewModel,
    onAuthorized: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var scannerEnabled by remember { mutableStateOf(true) }
    var qrInFrame by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shouldShowPermissionRationale =
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasCameraPermission = granted
            permissionRequested = true
            if (!granted) {
                message = "Permita a câmera para escanear o QR da arena."
            } else {
                message = null
                scannerEnabled = true
            }
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionRequested = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(scannerEnabled) {
        if (!scannerEnabled) {
            qrInFrame = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            QrScannerPreview(
                enabled = scannerEnabled && !isLoading,
                lifecycleOwner = lifecycleOwner,
                onQrPresenceChanged = { present ->
                    if (present != qrInFrame) {
                        qrInFrame = present
                    }
                },
                onTokenDetected = { scannedValue ->
                    if (isLoading) return@QrScannerPreview
                    scannerEnabled = false
                    qrInFrame = false
                    isLoading = true
                    message = "Validando QR..."
                    scope.launch {
                        when (val result = viewModel.validateToken(scannedValue)) {
                            is ValidateTokenResult.Success -> {
                                message = "Acesso liberado para ${result.arenaName}."
                                onAuthorized()
                            }
                            is ValidateTokenResult.Error -> {
                                message = result.message
                                scannerEnabled = true
                            }
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212)),
            )
        }

        if (hasCameraPermission) {
            QrScanningFrameOverlay(
                qrInFrame = qrInFrame || isLoading,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AccessValidationHeader(
                modifier = Modifier.padding(top = 8.dp),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                message?.let { feedback ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.95f),
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (!hasCameraPermission) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            permissionRequested = true
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Permitir câmera")
                    }
                    if (permissionRequested && !shouldShowPermissionRationale) {
                        OutlinedButton(
                            onClick = {
                                val intent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                context.startActivity(intent)
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                        ) {
                            Text("Abrir configurações")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessValidationHeader(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.38f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Validar acesso",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = "Escaneie o QR code da arena para liberar a câmera",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
            Text(
                text = "A câmera será liberada após a validação",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun QrScanningFrameOverlay(
    qrInFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cutoutSize = (minOf(maxWidth, maxHeight) * 0.72f).coerceAtMost(280.dp)
        QrScanningFrameOverlayContent(
            cutoutSize = cutoutSize,
            qrInFrame = qrInFrame,
        )
    }
}

@Composable
private fun QrScanningFrameOverlayContent(
    cutoutSize: Dp,
    qrInFrame: Boolean,
) {
    val dimBase =
        if (qrInFrame) {
            Color.Black.copy(alpha = 0.52f)
        } else {
            Color.Black.copy(alpha = 0.62f)
        }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(dimBase))
        Row(Modifier.fillMaxWidth().height(cutoutSize)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(dimBase))
            Box(
                modifier =
                    Modifier
                        .size(cutoutSize)
                        .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                ScanWindowBorder(
                    qrInFrame = qrInFrame,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(dimBase))
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(dimBase))
    }
}

@Composable
private fun ScanWindowBorder(
    qrInFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanBorder")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )
    val accent = MaterialTheme.colorScheme.primary
    val baseStroke =
        if (qrInFrame) {
            accent.copy(alpha = (0.78f + 0.22f * pulse).coerceIn(0f, 1f))
        } else {
            Color.White.copy(alpha = 0.55f + 0.35f * pulse)
        }

    Canvas(modifier = modifier) {
        val strokeWidth = 2.2.dp.toPx()
        val corner = CornerRadius(14.dp.toPx(), 14.dp.toPx())
        drawRoundRect(
            color = baseStroke,
            style =
                Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                ),
            cornerRadius = corner,
        )
    }
}

@Composable
private fun QrScannerPreview(
    enabled: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onTokenDetected: (String) -> Unit,
    onQrPresenceChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val onPresence = rememberUpdatedState(onQrPresenceChanged)

    DisposableEffect(enabled, previewView) {
        val view = previewView
        if (!enabled || view == null) {
            onDispose { }
        } else {
            val options =
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            var consumed = false
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                processQrFrame(
                    scanner = scanner,
                    imageProxy = imageProxy,
                    onTokenDetected = { token ->
                        if (!consumed) {
                            consumed = true
                            onTokenDetected(token)
                        }
                    },
                    onQrPresenceChanged = { has ->
                        onPresence.value?.invoke(has)
                    },
                )
            }

            val listener = Runnable {
                val provider = cameraProviderFuture.get()
                val preview =
                    Preview.Builder().build().also { useCase ->
                        useCase.surfaceProvider = view.surfaceProvider
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
            cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

            onDispose {
                analysis.clearAnalyzer()
                scanner.close()
                onPresence.value?.invoke(false)
                runCatching {
                    cameraProviderFuture.get().unbindAll()
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView = this
            }
        },
        update = { view ->
            previewView = view
        },
    )
}

private fun processQrFrame(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onTokenDetected: (String) -> Unit,
    onQrPresenceChanged: ((Boolean) -> Unit)? = null,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner
        .process(image)
        .addOnSuccessListener { barcodes ->
            val hasValidQr = barcodes.any { !it.rawValue.isNullOrBlank() }
            onQrPresenceChanged?.invoke(hasValidQr)
            val value = barcodes.firstNotNullOfOrNull { it.rawValue?.trim() }
            if (!value.isNullOrBlank()) {
                onTokenDetected(value)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
