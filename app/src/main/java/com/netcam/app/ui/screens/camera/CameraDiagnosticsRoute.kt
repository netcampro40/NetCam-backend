package com.netcam.app.ui.screens.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.netcam.app.data.camera.CameraCalibrationStore
import com.netcam.app.domain.model.CameraRole
import com.netcam.app.ui.components.NetCamTopBar
import kotlinx.coroutines.launch

data class CameraIdInfo(
    val cameraId: String,
    val lensFacing: String,
    val focalLengths: List<Float>,
    val physicalCameraIds: List<String>,
    val capabilities: List<String>,
    val activeArraySize: Rect?,
    val maxDigitalZoom: Float?,
    val zoomRatioRangeRaw: Range<Float>?,
    val zoomRatioRange: String?,
    val minZoomLessThanOne: Boolean,
    val supportsPreview: Boolean,
    val supportsVideo: Boolean,
    val videoStabilizationModes: List<Int>,
    val sceneModes: List<Int>,
    val aeTargetFpsRanges: List<Range<Int>>,
    val hardwareLevel: String,
    val logicalMultiCamera: Boolean,
    val wideHint: String,
)

private enum class BindMode {
    PREVIEW_ONLY,
    PREVIEW_AND_VIDEO,
}

@Composable
fun CameraDiagnosticsRoute(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val calibrationStore = remember { CameraCalibrationStore(context.applicationContext) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    var cameras by remember { mutableStateOf<List<CameraIdInfo>>(emptyList()) }
    var selectedBackCameraIdForPreview by remember { mutableStateOf<String?>(null) }
    var bindMode by remember { mutableStateOf(BindMode.PREVIEW_ONLY) }
    var calibration by remember { mutableStateOf<Map<CameraRole, String>>(emptyMap()) }
    var boundResolvedCameraId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cameras = loadCameraIdInfos(context.applicationContext)
        calibration = calibrationStore.getAll()
        logCameraDiagnosticsSummary(cameras)
    }

    LaunchedEffect(cameraProviderFuture) {
        cameraProviderFuture.addListener(
            {
                runCatching { cameraProviderFuture.get() }
                    .onSuccess { cameraProvider = it }
                    .onFailure { Log.e(DIAG_TAG, "erro ao obter CameraProvider", it) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    LaunchedEffect(cameraProvider, previewView, selectedBackCameraIdForPreview, bindMode) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        val targetId = selectedBackCameraIdForPreview ?: return@LaunchedEffect

        runCatching {
            provider.unbindAll()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val selector = selectorForCameraId(targetId)

            boundCamera =
                if (bindMode == BindMode.PREVIEW_AND_VIDEO) {
                    val recorder =
                        Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.FHD))
                            .build()
                    val videoCapture = VideoCapture.withOutput(recorder)
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview)
                }

            val resolvedId = runCatching { Camera2CameraInfo.from(boundCamera!!.cameraInfo).cameraId }.getOrNull()
            boundResolvedCameraId = resolvedId
            val modeLabel = if (bindMode == BindMode.PREVIEW_AND_VIDEO) "preview+video" else "preview"
            Log.d(
                DIAG_TAG,
                "bind sucesso mode=$modeLabel requestedId=$targetId resolvedId=$resolvedId",
            )
            compareCamera2VsCameraX(
                allInfos = cameras,
                requestedId = targetId,
                resolvedId = resolvedId,
                modeLabel = modeLabel,
            )
            snackbarHostState.showSnackbar(
                "Bind OK ($modeLabel) cameraId requisitado=$targetId resolvido=$resolvedId",
                withDismissAction = true,
            )
        }.onFailure {
            val modeLabel = if (bindMode == BindMode.PREVIEW_AND_VIDEO) "preview+video" else "preview"
            boundResolvedCameraId = null
            Log.e(DIAG_TAG, "bind falhou mode=$modeLabel cameraId=$targetId", it)
            snackbarHostState.showSnackbar(
                "Falha no bind ($modeLabel) cameraId=$targetId: ${it.message ?: "erro"}",
                withDismissAction = true,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(scaffoldPadding),
        ) {
            NetCamTopBar(
                title = "Diagnostico de cameras",
                onBack = onBack,
            )

            Text(
                text = "Foco: mapear wide traseira real (Camera2 + CameraX) com bind por cameraId.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(0.45f),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            previewView = this
                        }
                    },
                    update = { view -> previewView = view },
                )

                if (selectedBackCameraIdForPreview == null) {
                    Text(
                        text = "Use 'Testar preview' ou 'Testar preview+video' em um cameraId traseiro.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(0.55f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cameras, key = { it.cameraId }) { info ->
                    CameraInfoCard(
                        info = info,
                        isSelected = info.cameraId == selectedBackCameraIdForPreview,
                        calibration = calibration,
                        onOpenPreview = {
                            bindMode = BindMode.PREVIEW_ONLY
                            selectedBackCameraIdForPreview = info.cameraId
                        },
                        onOpenPreviewWithVideo = {
                            bindMode = BindMode.PREVIEW_AND_VIDEO
                            selectedBackCameraIdForPreview = info.cameraId
                        },
                        onTestZoom = { targetZoom ->
                            scope.launch {
                                val activeCamera = boundCamera
                                if (activeCamera == null) {
                                    snackbarHostState.showSnackbar(
                                        "Abra um preview antes de testar zoom",
                                        withDismissAction = true,
                                    )
                                    return@launch
                                }
                                val selectedId = selectedBackCameraIdForPreview
                                val resolvedId = boundResolvedCameraId
                                runZoomRatioTest(
                                    camera = activeCamera,
                                    requestedZoom = targetZoom,
                                    requestedCameraId = selectedId,
                                    resolvedCameraId = resolvedId,
                                ).onSuccess { summary ->
                                    snackbarHostState.showSnackbar(summary, withDismissAction = true)
                                }.onFailure { throwable ->
                                    snackbarHostState.showSnackbar(
                                        "Falha no zoom ${targetZoom}x: ${throwable.message ?: "erro"}",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        },
                        onReadZoomState = {
                            scope.launch {
                                val activeCamera = boundCamera
                                if (activeCamera == null) {
                                    snackbarHostState.showSnackbar(
                                        "Sem camera em preview para ler zoomState",
                                        withDismissAction = true,
                                    )
                                    return@launch
                                }
                                val z = activeCamera.cameraInfo.zoomState.value
                                val summary = zoomStateSummary(z)
                                Log.d(
                                    DIAG_TAG,
                                    "zoomState read requestedId=$selectedBackCameraIdForPreview resolvedId=$boundResolvedCameraId $summary",
                                )
                                snackbarHostState.showSnackbar(summary, withDismissAction = true)
                            }
                        },
                        onMark = { role ->
                            calibrationStore.setExclusive(role, info.cameraId)
                            calibration = calibrationStore.getAll()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Marcado ${role.name} = cameraId=${info.cameraId}",
                                    withDismissAction = true,
                                )
                            }
                        },
                        onIgnore = {
                            CameraRole.entries.forEach { role ->
                                if (calibrationStore.get(role) == info.cameraId) {
                                    calibrationStore.set(role, null)
                                }
                            }
                            calibration = calibrationStore.getAll()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "cameraId=${info.cameraId} removido da calibracao",
                                    withDismissAction = true,
                                )
                            }
                        },
                    )
                }
            }

            val isComplete = calibrationStore.isComplete()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDone,
                    enabled = isComplete,
                ) {
                    Text("Concluir")
                }
            }
        }
    }
}

@Composable
private fun CameraInfoCard(
    info: CameraIdInfo,
    isSelected: Boolean,
    calibration: Map<CameraRole, String>,
    onOpenPreview: () -> Unit,
    onOpenPreviewWithVideo: () -> Unit,
    onTestZoom: (Float) -> Unit,
    onReadZoomState: () -> Unit,
    onMark: (CameraRole) -> Unit,
    onIgnore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val focalsStr = info.focalLengths.joinToString(prefix = "[", postfix = "]")
            val physicalStr = info.physicalCameraIds.joinToString(prefix = "[", postfix = "]")
            val capsStr = info.capabilities.joinToString(prefix = "[", postfix = "]")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "cameraId=${info.cameraId} (${info.lensFacing})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isSelected) {
                        Text(
                            text = "Selecionada para bind",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val roleBadges = buildList {
                        if (calibration[CameraRole.BACK_MAIN] == info.cameraId) add("Traseira principal")
                        if (calibration[CameraRole.BACK_REDUCED] == info.cameraId) add("Reduzida")
                        if (calibration[CameraRole.FRONT] == info.cameraId) add("Frontal")
                    }
                    if (roleBadges.isNotEmpty()) {
                        Text(
                            text = "Marcada: ${roleBadges.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        text = "Hint wide/logical: ${info.wideHint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = onOpenPreview) { Text("Testar preview") }
                    TextButton(onClick = onOpenPreviewWithVideo) { Text("Testar preview+video") }
                }
            }
            if (info.lensFacing == "BACK") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onTestZoom(0.5f) }) { Text("Testar zoom 0.5x") }
                    TextButton(onClick = { onTestZoom(0.6f) }) { Text("Testar zoom 0.6x") }
                    TextButton(onClick = { onTestZoom(0.7f) }) { Text("Testar zoom 0.7x") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onTestZoom(0.8f) }) { Text("Testar zoom 0.8x") }
                    TextButton(onClick = { onTestZoom(1.0f) }) { Text("Testar zoom 1.0x") }
                    TextButton(onClick = onReadZoomState) { Text("Ler zoomState atual") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMark(CameraRole.BACK_MAIN) }) { Text("Traseira principal") }
                TextButton(onClick = { onMark(CameraRole.BACK_REDUCED) }) { Text("Reduzida") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMark(CameraRole.FRONT) }) { Text("Frontal") }
                TextButton(onClick = onIgnore) { Text("Ignorar") }
            }

            Text(text = "Focals(mm): $focalsStr", style = MaterialTheme.typography.bodySmall)
            Text(text = "physicalCameraIds: $physicalStr", style = MaterialTheme.typography.bodySmall)
            Text(text = "capabilities: $capsStr", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "activeArraySize: ${info.activeArraySize?.flattenToString() ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "maxDigitalZoom: ${info.maxDigitalZoom?.toString() ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "zoomRatioRange: ${info.zoomRatioRange ?: "n/a"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "zoomRatioMin<1.0: ${info.minZoomLessThanOne}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "logicalMultiCamera: ${info.logicalMultiCamera}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "hardwareLevel: ${info.hardwareLevel}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "videoStabilizationModes: ${info.videoStabilizationModes}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "sceneModes: ${info.sceneModes}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "aeTargetFpsRanges: ${info.aeTargetFpsRanges}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "supportsPreview=${info.supportsPreview} supportsVideo=${info.supportsVideo}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun loadCameraIdInfos(appContext: Context): List<CameraIdInfo> {
    val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { emptyList() }

    return ids.mapNotNull { id ->
        runCatching {
            val cc = manager.getCameraCharacteristics(id)
            val facing = cc.get(CameraCharacteristics.LENS_FACING)
            val lensFacing = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }

            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
            val physicalIds =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cc.physicalCameraIds.toList() else emptyList()

            val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val capNames = caps.map(::capabilityName).sorted()
            val activeArraySize = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxDigitalZoom = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val zoomRatioRangeRaw =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else {
                    null
                }
            val zoomRatioRange = zoomRatioRangeRaw?.let { "${it.lower}..${it.upper}" }
            val minZoomLessThanOne = zoomRatioRangeRaw?.lower?.let { it < 1.0f } == true
            val videoStabilizationModes =
                cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty()
            val sceneModes = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()
            val aeTargetFpsRanges =
                cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            val hardwareLevel = hardwareLevelName(cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            val logicalMultiCamera =
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            val streamMap = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportsPreview = supportsPreview(streamMap)
            val supportsVideo = supportsVideo(streamMap)

            CameraIdInfo(
                cameraId = id,
                lensFacing = lensFacing,
                focalLengths = focals,
                physicalCameraIds = physicalIds,
                capabilities = capNames,
                activeArraySize = activeArraySize,
                maxDigitalZoom = maxDigitalZoom,
                zoomRatioRangeRaw = zoomRatioRangeRaw,
                zoomRatioRange = zoomRatioRange,
                minZoomLessThanOne = minZoomLessThanOne,
                supportsPreview = supportsPreview,
                supportsVideo = supportsVideo,
                videoStabilizationModes = videoStabilizationModes,
                sceneModes = sceneModes,
                aeTargetFpsRanges = aeTargetFpsRanges,
                hardwareLevel = hardwareLevel,
                logicalMultiCamera = logicalMultiCamera,
                wideHint = inferWideHint(lensFacing, focals, capNames, physicalIds, minZoomLessThanOne),
            )
        }.onFailure {
            Log.e(DIAG_TAG, "falha lendo cameraId=$id", it)
        }.getOrNull()
    }
}

private fun supportsPreview(streamMap: StreamConfigurationMap?): Boolean {
    if (streamMap == null) return false
    val privateSizes = streamMap.getOutputSizes(android.graphics.SurfaceTexture::class.java)
    return !privateSizes.isNullOrEmpty()
}

private fun supportsVideo(streamMap: StreamConfigurationMap?): Boolean {
    if (streamMap == null) return false
    val mediaRecorderSizes = streamMap.getOutputSizes(android.media.MediaRecorder::class.java)
    val privateSizes = streamMap.getOutputSizes(android.graphics.SurfaceTexture::class.java)
    val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)
    return !mediaRecorderSizes.isNullOrEmpty() || !privateSizes.isNullOrEmpty() || !yuvSizes.isNullOrEmpty()
}

private fun capabilityName(cap: Int): String =
    when (cap) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        else -> "CAP_$cap"
    }

private fun inferWideHint(
    lensFacing: String,
    focals: List<Float>,
    capabilities: List<String>,
    physicalIds: List<String>,
    minZoomLessThanOne: Boolean,
): String {
    if (lensFacing != "BACK") return "nao traseira"
    if (capabilities.contains("LOGICAL_MULTI_CAMERA") && physicalIds.isNotEmpty()) {
        return "logical multi-camera (verifique physicalCameraIds)"
    }
    if (minZoomLessThanOne) {
        return "zoomRatioRange permite <1.0 (possivel troca interna para wide)"
    }
    val minFocal = focals.minOrNull()
    return when {
        minFocal == null -> "sem focal lengths"
        minFocal <= 2.0f -> "forte indicio de ultrawide"
        minFocal <= 3.0f -> "possivel wide"
        else -> "tende a principal/tele"
    }
}

private fun hardwareLevelName(level: Int?): String =
    when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN_$level"
    }

private suspend fun runZoomRatioTest(
    camera: Camera,
    requestedZoom: Float,
    requestedCameraId: String?,
    resolvedCameraId: String?,
): Result<String> {
    val before = camera.cameraInfo.zoomState.value
    Log.d(
        DIAG_TAG,
        "zoomTest start requestedZoom=$requestedZoom requestedId=$requestedCameraId resolvedId=$resolvedCameraId before=${zoomStateSummary(before)}",
    )
    return runCatching {
        camera.cameraControl.setZoomRatio(requestedZoom)
        kotlinx.coroutines.delay(250L)
        val after = camera.cameraInfo.zoomState.value
        val summary =
            "zoom ${requestedZoom}x OK -> ${zoomStateSummary(after)}"
        Log.d(
            DIAG_TAG,
            "zoomTest success requestedZoom=$requestedZoom requestedId=$requestedCameraId resolvedId=$resolvedCameraId after=${zoomStateSummary(after)}",
        )
        summary
    }.onFailure { throwable ->
        Log.e(
            DIAG_TAG,
            "zoomTest failure requestedZoom=$requestedZoom requestedId=$requestedCameraId resolvedId=$resolvedCameraId",
            throwable,
        )
    }
}

private fun zoomStateSummary(state: ZoomState?): String {
    if (state == null) return "zoomState=n/a"
    return "zoomState ratio=${state.zoomRatio} linear=${state.linearZoom} min=${state.minZoomRatio} max=${state.maxZoomRatio}"
}

private fun compareCamera2VsCameraX(
    allInfos: List<CameraIdInfo>,
    requestedId: String,
    resolvedId: String?,
    modeLabel: String,
) {
    val requested = allInfos.firstOrNull { it.cameraId == requestedId }
    val resolved = resolvedId?.let { target -> allInfos.firstOrNull { it.cameraId == target } }
    if (requested == null) {
        Log.w(DIAG_TAG, "camera2VsCameraX sem Camera2 info para requestedId=$requestedId")
        return
    }
    Log.d(
        DIAG_TAG,
        "camera2VsCameraX mode=$modeLabel requestedId=$requestedId resolvedId=$resolvedId " +
            "camera2(minZoom<1=${requested.minZoomLessThanOne}, logical=${requested.logicalMultiCamera}, " +
            "preview=${requested.supportsPreview}, video=${requested.supportsVideo}, zoomRange=${requested.zoomRatioRange})",
    )
    if (resolvedId != null && resolvedId != requestedId) {
        Log.w(
            DIAG_TAG,
            "camera2VsCameraX mismatch: CameraX resolveu cameraId diferente requested=$requestedId resolved=$resolvedId",
        )
    }
    if (resolved != null) {
        Log.d(
            DIAG_TAG,
            "camera2VsCameraX resolvedInfo minZoom<1=${resolved.minZoomLessThanOne} logical=${resolved.logicalMultiCamera} zoomRange=${resolved.zoomRatioRange}",
        )
    }
}

private fun selectorForCameraId(targetId: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter(
            CameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == targetId }.getOrDefault(false)
                }
            },
        )
        .build()

private fun logCameraDiagnosticsSummary(cameras: List<CameraIdInfo>) {
    val rear = cameras.filter { it.lensFacing == "BACK" }
    val mainCandidate = rear.maxByOrNull { it.focalLengths.minOrNull() ?: Float.MIN_VALUE }
    val wideCandidate = rear.minByOrNull { it.focalLengths.minOrNull() ?: Float.MAX_VALUE }

    Log.d(DIAG_TAG, "======== CAMERA DIAGNOSTICS START ========")
    cameras.forEach { info ->
        Log.d(
            DIAG_TAG,
            "cameraId=${info.cameraId} facing=${info.lensFacing} focals=${info.focalLengths} capabilities=${info.capabilities} " +
                "logical=${info.capabilities.contains("LOGICAL_MULTI_CAMERA")} physicalIds=${info.physicalCameraIds} " +
                "activeArray=${info.activeArraySize?.flattenToString()} maxDigitalZoom=${info.maxDigitalZoom} " +
                "zoomRatioRange=${info.zoomRatioRange} minZoom<1=${info.minZoomLessThanOne} " +
                "videoStab=${info.videoStabilizationModes} sceneModes=${info.sceneModes} aeFps=${info.aeTargetFpsRanges} " +
                "hardware=${info.hardwareLevel} supportsPreview=${info.supportsPreview} supportsVideo=${info.supportsVideo} wideHint=${info.wideHint}",
        )
    }

    Log.d(
        DIAG_TAG,
        "rearComparison mainCandidate=${mainCandidate?.cameraId} mainFocal=${mainCandidate?.focalLengths?.minOrNull()} " +
            "wideCandidate=${wideCandidate?.cameraId} wideFocal=${wideCandidate?.focalLengths?.minOrNull()}",
    )
    Log.d(DIAG_TAG, "======== CAMERA DIAGNOSTICS END ========")
}

private const val DIAG_TAG = "NetCamDiag"
