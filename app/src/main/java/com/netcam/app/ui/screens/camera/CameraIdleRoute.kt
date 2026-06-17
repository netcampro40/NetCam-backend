package com.netcam.app.ui.screens.camera

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.netcam.app.ui.components.CollapsibleInfoPanel
import com.netcam.app.ui.components.RecordButton
import com.netcam.app.ui.components.VolumeHelpContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ArrowBack
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netcam.app.data.recording.CameraXVideoEngine
import com.netcam.app.data.ble.BleClipCommand
import com.netcam.app.di.AppGraph
import com.netcam.app.domain.model.RecordingSegmentType

@Composable
fun CameraIdleRoute(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    var hasFrontCamera by remember { mutableStateOf(true) }
    var hasRealWideSupport by remember { mutableStateOf(false) }
    var wideSupportEvidence by remember { mutableStateOf("no_evidence") }
    var wideOptionRatio by remember { mutableStateOf<Float?>(null) }
    var selectedBackZoomRatio by remember { mutableStateOf(1f) }

    var isFrontSelected by remember { mutableStateOf(false) }
    var isHelpVisible by remember { mutableStateOf(false) }
    var cameraInitState by remember { mutableStateOf(CameraInitState.INITIALIZING) }
    var cameraInitMessage by remember { mutableStateOf("Aguardando câmera...") }
    var bindAttemptCount by remember { mutableStateOf(0) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionsRequestedAtLeastOnce by remember { mutableStateOf(false) }
    val shouldShowCameraPermissionRationale =
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    val shouldShowAudioPermissionRationale =
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
    val hasAllRuntimePermissions = cameraPermissionGranted && audioPermissionGranted

    val sessionViewModel: CameraSessionViewModel = viewModel()
    val sessionUiState by sessionViewModel.uiState.collectAsState()
    val continuousRecordingController = AppGraph.continuousRecordingController
    val bleDebugController = AppGraph.bleDebugController
    val clipController = AppGraph.clipController
    val volumeGestureInterpreter = AppGraph.volumeButtonGestureInterpreter
    val volumeGestureFeedback by volumeGestureInterpreter.lastRecognizedRequest.collectAsState()

    val window = (context as? Activity)?.window
    DisposableEffect(sessionUiState.isActive) {
        if (sessionUiState.isActive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(sessionViewModel) {
        onDispose {
            sessionViewModel.stopSession()
            continuousRecordingController.forceReleaseStaleRecording("camera_route_dispose")
            CameraXVideoEngine.videoCapture = null
            Log.d("NetCam", "[CAMERA] route disposed: session stopped and engine cleared")
        }
    }

    val permissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            cameraPermissionGranted = result[Manifest.permission.CAMERA] == true
            audioPermissionGranted = result[Manifest.permission.RECORD_AUDIO] == true
            permissionsRequestedAtLeastOnce = true
            if (!cameraPermissionGranted || !audioPermissionGranted) {
                cameraInitState = CameraInitState.ERROR
                cameraInitMessage = "Permissões de câmera e microfone são necessárias."
            }
        }
    val blePermissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ -> }

    LaunchedEffect(Unit) {
        if (!hasAllRuntimePermissions) {
            permissionsRequestedAtLeastOnce = true
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        val blePermissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val missing =
            blePermissions.filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            blePermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            val catalog = AppGraph.lensCatalogProvider.getCatalog()
            hasFrontCamera = catalog.hasFrontCamera
            hasRealWideSupport = catalog.hasRealWideSupport
            wideSupportEvidence = catalog.wideSupportEvidence
            wideOptionRatio = catalog.backZoomRatios.filter { it < 0.98f }.minOrNull()
            if (!hasRealWideSupport) {
                selectedBackZoomRatio = 1f
            }
            Log.d(
                "NetCamDiag",
                "lensCatalog backZoomRatios=${catalog.backZoomRatios} hasFrontCamera=${catalog.hasFrontCamera} " +
                    "hasRealWideSupport=${catalog.hasRealWideSupport} wideSupportEvidence=${catalog.wideSupportEvidence}",
            )
        }.onFailure {
            // Fallback: mantém UI funcional com 1x e, se existir, alternância front.
            hasFrontCamera = true
            hasRealWideSupport = false
            wideSupportEvidence = "lens_catalog_error"
            wideOptionRatio = null
            selectedBackZoomRatio = 1f
        }
    }

    DisposableEffect(Unit) {
        bleDebugController.startAutoConnect()
        onDispose {
            bleDebugController.stopAutoConnect(keepConnection = true)
        }
    }

    LaunchedEffect(Unit) {
        bleDebugController.clipCommands.collect { command ->
            when (command) {
                BleClipCommand.CLIP_1M -> {
                    Log.d("NetCam", "BLE -> CLIP_1M -> saveLastMinute()")
                    clipController.saveLastMinute()
                    volumeGestureInterpreter.emitClipFeedback(RecordingSegmentType.ONE_MINUTE)
                }
                BleClipCommand.CLIP_2M -> {
                    Log.d("NetCam", "BLE -> CLIP_2M -> saveLastTwoMinutes()")
                    clipController.saveLastTwoMinutes()
                    volumeGestureInterpreter.emitClipFeedback(RecordingSegmentType.TWO_MINUTES)
                }
                BleClipCommand.CLIP_SESSION -> {
                    Log.d("NetCam", "BLE -> CLIP_SESSION -> saveFullSession()")
                    clipController.saveFullSession()
                    volumeGestureInterpreter.emitClipFeedback(RecordingSegmentType.FULL_SESSION)
                }
            }
        }
    }

    LaunchedEffect(cameraProviderFuture) {
        Log.d("NetCam", "Camera bind flow: aguardando ProcessCameraProvider")
        cameraProviderFuture.addListener(
            {
                try {
                    cameraProvider = cameraProviderFuture.get()
                    Log.d("NetCam", "Camera bind flow: ProcessCameraProvider obtido com sucesso")
                } catch (t: Throwable) {
                    cameraInitState = CameraInitState.ERROR
                    cameraInitMessage = "Falha ao obter ProcessCameraProvider: ${t.message ?: "erro desconhecido"}"
                    Log.e("NetCam", "Erro ao obter CameraProvider", t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    val currentIsFrontSelected by rememberUpdatedState(isFrontSelected)

    LaunchedEffect(
        cameraProvider,
        previewView,
        currentIsFrontSelected,
        cameraPermissionGranted,
        audioPermissionGranted,
    ) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        if (!hasAllRuntimePermissions) {
            cameraInitState = CameraInitState.ERROR
            cameraInitMessage = "Permita câmera e microfone para usar a função Filmar."
            return@LaunchedEffect
        }

        cameraInitState = CameraInitState.INITIALIZING
        cameraInitMessage = "Inicializando câmera..."
        camera = null
        videoCapture = null
        CameraXVideoEngine.videoCapture = null
        bindAttemptCount += 1
        val attempt = bindAttemptCount
        Log.d("NetCam", "Camera bind flow [attempt=$attempt]: iniciando bind (front=$currentIsFrontSelected)")
        logBackCamera2Summary(context = context, attempt = attempt)
        Log.d("NetCam", "Camera bind flow [attempt=$attempt]: previewView attached=${ViewCompat.isAttachedToWindow(view)}")
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val hasAudioPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        Log.d(
            "NetCam",
            "Camera bind flow [attempt=$attempt]: cameraPermissionGranted=$hasCameraPermission audioPermissionGranted=$hasAudioPermission",
        )
        if (!hasCameraPermission) {
            cameraInitState = CameraInitState.ERROR
            cameraInitMessage = "Permissão da câmera não concedida."
            Log.e("NetCam", "Camera bind flow [attempt=$attempt]: permissão da câmera ausente")
            return@LaunchedEffect
        }
        if (!hasAudioPermission) {
            cameraInitState = CameraInitState.ERROR
            cameraInitMessage = "Permissão do microfone não concedida."
            Log.e("NetCam", "Camera bind flow [attempt=$attempt]: permissão do microfone ausente")
            return@LaunchedEffect
        }

        try {
            // Garante surface provider do PreviewView para este ciclo de entrada.
            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            view.scaleType = PreviewView.ScaleType.FILL_CENTER
            provider.unbindAll()
            Log.d("NetCam", "Camera bind flow [attempt=$attempt]: provider.unbindAll() executado")

            val preview = Preview.Builder().build().also { previewUseCase ->
                previewUseCase.surfaceProvider = view.surfaceProvider
            }
            Log.d("NetCam", "Camera bind flow [attempt=$attempt]: Preview criado e surfaceProvider configurado")

            val selector: CameraSelector =
                if (currentIsFrontSelected) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            // Escolhe automaticamente a melhor qualidade suportada tentando em ordem:
            // FHD -> HD -> SD. Se uma tentativa falhar ao bindar, cai para a próxima.
            val qualityOrder = listOf(Quality.FHD, Quality.HD, Quality.SD)
            var lastError: Throwable? = null
            var boundCamera: Camera? = null
            var chosenVideoCapture: VideoCapture<Recorder>? = null

            for (quality in qualityOrder) {
                try {
                    Log.d("NetCam", "Camera bind flow [attempt=$attempt]: tentando quality=$quality")
                    val recorder =
                        Recorder
                            .Builder()
                            .setQualitySelector(QualitySelector.from(quality))
                            .build()
                    val videoCaptureCandidate = VideoCapture.withOutput(recorder)
                    Log.d("NetCam", "Camera bind flow [attempt=$attempt]: VideoCapture criado para quality=$quality")

                    boundCamera =
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            preview,
                            videoCaptureCandidate,
                        )
                    chosenVideoCapture = videoCaptureCandidate
                    lastError = null
                    Log.d("NetCam", "Camera bind flow [attempt=$attempt]: bindToLifecycle sucesso com quality=$quality")
                    break
                } catch (t: Throwable) {
                    lastError = t
                    cameraInitMessage = "Falha no bind ($quality): ${t.message ?: "erro desconhecido"}"
                    Log.w("NetCam", "Falha ao bindar com qualidade=$quality; tentando próximo", t)
                    provider.unbindAll()
                }
            }

            if (boundCamera == null || chosenVideoCapture == null) {
                throw lastError
                    ?: IllegalStateException("Não foi possível iniciar a câmera com nenhuma qualidade disponível")
            }

            camera = boundCamera
            videoCapture = chosenVideoCapture
            val resolvedCameraId = runCatching { Camera2CameraInfo.from(boundCamera.cameraInfo).cameraId }.getOrNull()
            Log.d(
                "NetCamDiag",
                "cameraRoute bind resolvedCameraId=$resolvedCameraId requestedFacing=${if (currentIsFrontSelected) "FRONT" else "BACK"}",
            )

            // Atualiza engine compartilhado para o controlador de gravação contínua.
            CameraXVideoEngine.appContext = context.applicationContext
            CameraXVideoEngine.videoCapture = chosenVideoCapture

            val initialBackZoom =
                if (hasRealWideSupport) {
                    selectedBackZoomRatio.coerceAtLeast(0.5f)
                } else {
                    1f
                }
            if (currentIsFrontSelected) {
                boundCamera.cameraControl.setZoomRatio(1f)
            } else {
                boundCamera.cameraControl.setZoomRatio(initialBackZoom)
            }
            cameraInitState = CameraInitState.READY
            cameraInitMessage = "Câmera pronta"
            Log.d("NetCam", "[CAMERA] bind complete; ready for recording")
        } catch (t: Throwable) {
            cameraInitState = CameraInitState.ERROR
            cameraInitMessage = "Erro ao iniciar câmera: ${t.message ?: "erro desconhecido"}"
            Log.e("NetCam", "Erro ao iniciar preview da câmera", t)
        }
    }

    LaunchedEffect(camera, isFrontSelected, selectedBackZoomRatio, hasRealWideSupport) {
        val activeCamera = camera ?: return@LaunchedEffect
        if (isFrontSelected) {
            runCatching { activeCamera.cameraControl.setZoomRatio(1f) }
            return@LaunchedEffect
        }
        val target =
            if (hasRealWideSupport) {
                selectedBackZoomRatio.coerceAtLeast(0.5f)
            } else {
                1f
            }
        runCatching {
            activeCamera.cameraControl.setZoomRatio(target)
            Log.d("NetCamDiag", "cameraRoute applyBackZoom target=$target supported=$hasRealWideSupport")
        }.onFailure {
            Log.w("NetCamDiag", "cameraRoute falha ao aplicar zoom target=$target", it)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // Preview de câmera ocupando o máximo da tela
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

        if (cameraInitState != CameraInitState.READY && !sessionUiState.isActive) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val friendlyMessage =
                    if (!hasAllRuntimePermissions) {
                        "Permita câmera e microfone para usar a função Filmar."
                    } else {
                        cameraInitMessage
                    }
                Text(
                    text = friendlyMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (!hasAllRuntimePermissions) {
                    Button(
                        onClick = {
                            permissionsRequestedAtLeastOnce = true
                            permissionsLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO,
                                ),
                            )
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Text("Permitir câmera e microfone")
                    }

                    val canOpenSettings =
                        permissionsRequestedAtLeastOnce &&
                            !shouldShowCameraPermissionRationale &&
                            !shouldShowAudioPermissionRationale
                    if (canOpenSettings) {
                        OutlinedButton(
                            onClick = {
                                val intent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Abrir configurações")
                        }
                    }
                }
            }
        }

        // Barra superior transparente sobreposta ao preview,
        // com ícone de voltar, cronômetro centralizado e ícones de ajuda/configurações.
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = contentPadding.calculateTopPadding(),
                        start = 16.dp,
                        end = 16.dp,
                    )
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ícone de voltar à esquerda
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Voltar",
                )
            }

            // Área central para o indicador/cronômetro de gravação
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (sessionUiState.isActive) {
                    RecordingIndicator(
                        elapsedMillis = sessionUiState.elapsedMillis,
                    )
                }
            }

            // Ícones de ajuda e configurações à direita
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { isHelpVisible = !isHelpVisible }) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "Ajuda",
                    )
                }
            }
        }

        // Feedback discreto de reconhecimento do gesto de volume+,
        // sobreposto na parte superior da tela.
        VolumeGestureFeedbackChip(
            type = volumeGestureFeedback,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = contentPadding.calculateTopPadding() + 40.dp,
                    ),
        )

        // Aviso fixo e discreto do Volume+.
        // Regras: some quando o painel de ajuda estiver aberto e também durante a gravação.
        if (!isHelpVisible && !sessionUiState.isActive) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = contentPadding.calculateTopPadding() + 52.dp,
                            start = 12.dp,
                            end = 12.dp,
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(
                        text = "Os clipes são salvos com Volume +",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                    )
                    Row(
                        modifier = Modifier.padding(top = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Toque em ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        )
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Ajuda",
                            modifier = Modifier.padding(start = 6.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        )
                        Text(
                            text = " para mais informações.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                            modifier = Modifier.padding(start = 0.dp),
                        )
                    }
                }
            }
        }

        // Painel de ajuda colapsável sobreposto, abaixo do topo
        if (isHelpVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        // Fica abaixo do aviso fixo para manter o aviso sempre visível.
                        top = contentPadding.calculateTopPadding() + 96.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ),
            ) {
                CollapsibleInfoPanel(
                    isVisible = true,
                ) {
                    VolumeHelpContent()
                }
            }
        }

        // Controles inferiores: zoom, troca de câmera e botão principal
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!sessionUiState.isActive) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LensChip(
                        label = "1x",
                        enabled = true,
                        selected = !isFrontSelected && selectedBackZoomRatio >= 0.98f,
                        onClick = {
                            isFrontSelected = false
                            selectedBackZoomRatio = 1f
                        },
                    )
                    if (hasRealWideSupport && wideOptionRatio != null) {
                        val ratio = wideOptionRatio!!
                        LensChip(
                            label = "${"%.1f".format(ratio)}x",
                            enabled = true,
                            selected = !isFrontSelected && kotlin.math.abs(selectedBackZoomRatio - ratio) <= 0.03f,
                            onClick = {
                                isFrontSelected = false
                                selectedBackZoomRatio = ratio
                            },
                        )
                    }

                    IconButton(
                        onClick = {
                            isFrontSelected = !isFrontSelected
                            if (isFrontSelected) {
                                selectedBackZoomRatio = 1f
                            }
                        },
                        enabled = hasFrontCamera,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Trocar câmera frontal/traseira",
                        )
                    }
                }
                if (!isFrontSelected && !hasRealWideSupport) {
                    Text(
                        text = "Wide indisponível neste aparelho",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            RecordButton(
                onClick = {
                    if (sessionUiState.isActive) {
                        sessionViewModel.stopSession()
                    } else {
                        if (cameraInitState != CameraInitState.READY || videoCapture == null) {
                            Log.w(
                                "NetCam",
                                "[CAMERA] record ignored: state=$cameraInitState videoCaptureNull=${videoCapture == null}",
                            )
                            sessionViewModel.clearSessionError()
                            return@RecordButton
                        }
                        val started = sessionViewModel.startSession()
                        if (!started) {
                            Log.w("NetCam", "[RECORDING] startSession returned false")
                        }
                    }
                },
                isActive = sessionUiState.isActive,
                enabled = sessionUiState.isActive || (cameraInitState == CameraInitState.READY && videoCapture != null),
            )

            sessionUiState.sessionError?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

}

private fun logBackCamera2Summary(
    context: android.content.Context,
    attempt: Int,
) {
    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
    val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { emptyList() }
    val backIds = ids.filter { id ->
        runCatching {
            val cc = manager.getCameraCharacteristics(id)
            cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
    Log.d("NetCamDiag", "cameraRoute attempt=$attempt backIds=$backIds")
    backIds.forEach { id ->
        runCatching {
            val cc = manager.getCameraCharacteristics(id)
            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
            val maxZoom = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val zoomRange =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString()
                } else {
                    "n/a"
                }
            Log.d(
                "NetCamDiag",
                "cameraRoute attempt=$attempt cameraId=$id focals=$focals maxDigitalZoom=$maxZoom zoomRatioRange=$zoomRange",
            )
        }.onFailure { throwable ->
            Log.e("NetCamDiag", "cameraRoute attempt=$attempt erro cameraId=$id", throwable)
        }
    }
}

private enum class CameraInitState {
    INITIALIZING,
    READY,
    ERROR,
}

@Composable
private fun VolumeGestureFeedbackChip(
    type: RecordingSegmentType?,
    modifier: Modifier = Modifier,
) {
    if (type == null) return

    val label =
        when (type) {
            RecordingSegmentType.ONE_MINUTE -> "Clipe 1 minuto"
            RecordingSegmentType.TWO_MINUTES -> "Clipe 2 minutos"
            RecordingSegmentType.FULL_SESSION -> "Clipe sessão inteira"
        }

    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RecordingIndicator(
    elapsedMillis: Long,
    modifier: Modifier = Modifier,
) {
    val activeRed = Color(0xFFEF4444).copy(alpha = 0.95f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(12.dp)
                    .clip(shape = MaterialTheme.shapes.small)
                    .background(activeRed),
        )
        Text(
            text = "Gravando ${formatElapsed(elapsedMillis)}",
            color = activeRed,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1_000L).coerceAtLeast(0L)
    if (totalSeconds < 3600L) {
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun LensChip(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Neutro cinza claro (sem azul) antes de gravar.
    val baseBg = Color(0xFFD9DDE2)
    val bg: Color = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        selected -> baseBg.copy(alpha = 0.42f)
        else -> baseBg.copy(alpha = 0.22f)
    }
    val fg: Color = when {
        !enabled -> Color.White.copy(alpha = 0.55f)
        else -> Color(0xFF0B1220).copy(alpha = 0.88f)
    }

    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier,
    ) {
        Box(
            modifier = Modifier
                .background(bg, shape = MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = fg, fontWeight = FontWeight.SemiBold)
        }
    }
}

