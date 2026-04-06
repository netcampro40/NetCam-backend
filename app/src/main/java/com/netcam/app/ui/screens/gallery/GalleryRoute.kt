package com.netcam.app.ui.screens.gallery

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.netcam.app.data.recording.MediaMuxerVideoSegmentExtractor
import com.netcam.app.data.storage.MediaStoreVideoGallerySaver
import com.netcam.app.di.AppGraph
import com.netcam.app.domain.config.TemporaryClipRetention
import com.netcam.app.domain.model.RecordingSegment
import com.netcam.app.domain.model.RecordingSegmentStatus
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.ui.components.NetCamTopBar
import java.time.format.DateTimeFormatter
import java.io.File
import kotlin.math.max
import kotlin.math.min

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private const val CLIP_DEBUG_TAG = "NetCamClipDebug"

/** Duração exibida: arquivo real; clipes de sessão inteira não usam [RecordingSegmentType.durationSeconds]. */
private fun effectiveDurationSeconds(segment: RecordingSegment): Int? {
    segment.realDurationSeconds?.let { return it }
    return when (segment.type) {
        RecordingSegmentType.FULL_SESSION -> null
        else -> segment.type.durationSeconds
    }
}

private fun formatDurationLabel(segment: RecordingSegment): String =
    effectiveDurationSeconds(segment)?.let { formatDurationSeconds(it) } ?: "—"

private fun temporaryStatusLine(
    status: RecordingSegmentStatus,
    failureReason: String?,
): String? =
    when (status) {
        RecordingSegmentStatus.PENDING -> "Aguardando finalização da sessão"
        RecordingSegmentStatus.PROCESSING -> "Processando"
        RecordingSegmentStatus.READY -> "Pronto"
        RecordingSegmentStatus.FAILED ->
            failureReason?.takeIf { it.isNotBlank() } ?: "Falha ao gerar o clipe"
    }

@Composable
fun GalleryRoute(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val segments by AppGraph
        .recordingSegmentController
        .segments
        .collectAsState()

    val temporarySegments =
        segments
            .asSequence()
            .filter { it.galleryUri == null }
            .filter { segment ->
                when (segment.status) {
                    RecordingSegmentStatus.READY ->
                        segment.outputFilePath?.let { path ->
                            runCatching {
                                val f = File(path)
                                f.exists() && f.length() > 0L
                            }.getOrDefault(false)
                        } == true
                    RecordingSegmentStatus.PENDING,
                    RecordingSegmentStatus.PROCESSING,
                    RecordingSegmentStatus.FAILED ->
                        true
                }
            }
            .sortedByDescending { it.requestedAt }
            .toList()

    LaunchedEffect(segments) {
        Log.d(
            "NetCamGalleryDebug",
            "GalleryRoute: segmentsTotal=${segments.size} temporarios=${temporarySegments.size} idsTemporarios=${temporarySegments.joinToString { it.id }}",
        )
    }

    var segmentToPreview by remember { mutableStateOf<RecordingSegment?>(null) }
    var lastPreviewedSegmentId by remember { mutableStateOf<String?>(null) }
    var segmentToDelete by remember { mutableStateOf<RecordingSegment?>(null) }
    var previewTrimStartMs by remember { mutableStateOf(0L) }
    var previewTrimEndMs by remember { mutableStateOf(0L) }
    var previewClipDurationMs by remember { mutableStateOf(0L) }
    var previewTrimChangeToken by remember { mutableStateOf(0L) }
    var trimSaveInProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ao retomar o app (ex.: voltou dos recentes) com a galeria visível.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching {
                        AppGraph.recordingSegmentController.refreshGalleryFromPersistenceWithRetention()
                    }.onFailure { e ->
                        Log.w("NetCamGallery", "refreshGallery ON_RESUME", e)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Imediato ao entrar na rota + periodicamente enquanto a galeria está aberta.
    LaunchedEffect(Unit) {
        val interval = TemporaryClipRetention.galleryRetentionPollIntervalMs()
        while (isActive) {
            runCatching {
                AppGraph.recordingSegmentController.refreshGalleryFromPersistenceWithRetention()
            }.onFailure { e ->
                Log.w("NetCamGallery", "refreshGalleryFromPersistenceWithRetention", e)
            }
            delay(interval)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(scaffoldPadding),
        ) {
            NetCamTopBar(
                title = "Galeria",
                onBack = onBack,
            )

            // Banner informativo (retencao 48h)
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = TemporaryClipRetention.retentionHintForUser(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            Text(
                text = "Temporários",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(temporarySegments, key = { it.id }) { segment ->
                    GalleryItemCard(
                        segment = segment,
                        isSelected = segment.id == lastPreviewedSegmentId,
                        onPreview = {
                            lastPreviewedSegmentId = segment.id
                            segmentToPreview = segment
                            previewTrimStartMs = 0L
                            previewTrimEndMs = 0L
                            previewClipDurationMs = 0L
                            previewTrimChangeToken += 1L
                            trimSaveInProgress = false
                        },
                        onSaveToGallery = {
                            scope.launch {
                                val result = AppGraph.recordingSegmentController.saveSegmentToGallery(segment.id)
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar(
                                        "Clipe salvo na galeria",
                                        withDismissAction = true,
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        "Erro ao salvar: ${result.exceptionOrNull()?.message ?: "desconhecido"}",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        },
                        onDeleteTemporary = {
                            segmentToDelete = segment
                        },
                    )
                }
            }
        }
    }

    val selected = segmentToPreview
    if (selected != null) {
        val canPreview =
            selected.outputFilePath != null &&
                selected.status == RecordingSegmentStatus.READY
        AlertDialog(
            onDismissRequest = {
                if (!trimSaveInProgress) segmentToPreview = null
            },
            title = {
                Text(selected.displayName ?: "Preview")
            },
            text = {
                Column {
                    if (canPreview) {
                        val durationSec = effectiveDurationSeconds(selected)
                        Text(
                            text =
                                durationSec?.let { sec -> "Duração: ${formatDurationSeconds(sec)}" }
                                    ?: "Duração: —",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        VideoPreviewPlayer(
                            videoPath = selected.outputFilePath!!,
                            trimStartMs = previewTrimStartMs,
                            trimEndMs = previewTrimEndMs.takeIf { it > 0L },
                            trimWindowChangeToken = previewTrimChangeToken,
                            onDurationKnown = { duration ->
                                previewClipDurationMs = duration
                                if (previewTrimEndMs <= 0L || previewTrimEndMs > duration) {
                                    previewTrimEndMs = duration
                                }
                            },
                        )

                        val durationForTrim = previewClipDurationMs
                        if (durationForTrim > 0L) {
                            val startValue = previewTrimStartMs.coerceIn(0L, durationForTrim).toFloat()
                            val endValue = previewTrimEndMs.coerceIn(0L, durationForTrim).toFloat()
                            val safeEnd = max(endValue, startValue + 1000f).coerceAtMost(durationForTrim.toFloat())
                            val safeStart = min(startValue, safeEnd - 1000f).coerceAtLeast(0f)

                            Text(
                                text = "Corte: ${formatMillisLabel(safeStart.toLong())} - ${formatMillisLabel(safeEnd.toLong())}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                            RangeSlider(
                                value = safeStart..safeEnd,
                                valueRange = 0f..durationForTrim.toFloat(),
                                onValueChange = { range ->
                                    val minGapMs = 1000L
                                    var newStart = range.start.toLong().coerceAtLeast(0L)
                                    var newEnd = range.endInclusive.toLong().coerceAtMost(durationForTrim)
                                    if (newEnd - newStart < minGapMs) {
                                        if (newStart + minGapMs <= durationForTrim) {
                                            newEnd = newStart + minGapMs
                                        } else {
                                            newStart = (durationForTrim - minGapMs).coerceAtLeast(0L)
                                            newEnd = durationForTrim
                                        }
                                    }
                                    previewTrimStartMs = newStart
                                    previewTrimEndMs = newEnd
                                    previewTrimChangeToken += 1L
                                },
                            )
                        }
                    } else {
                        Text(
                            text = "Este clipe ainda não está pronto para reprodução.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (trimSaveInProgress) return@Button
                            scope.launch {
                                val result = AppGraph.recordingSegmentController.saveSegmentToGallery(selected.id)
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar(
                                        "Clipe salvo na galeria",
                                        withDismissAction = true,
                                    )
                                    segmentToPreview = null
                                } else {
                                    snackbarHostState.showSnackbar(
                                        "Erro ao salvar: ${result.exceptionOrNull()?.message ?: "desconhecido"}",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        },
                        enabled = !trimSaveInProgress && canPreview,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Salvar original")
                    }

                    Button(
                        onClick = {
                            if (trimSaveInProgress) return@Button
                            val sourcePath = selected.outputFilePath ?: return@Button
                            val endMs = previewTrimEndMs.takeIf { it > 0L } ?: previewClipDurationMs
                            val startMs = previewTrimStartMs.coerceAtLeast(0L)
                            if (endMs <= startMs) return@Button
                            trimSaveInProgress = true
                            scope.launch {
                                val result =
                                    saveTrimmedVideoToGallery(
                                        context = context,
                                        sourcePath = sourcePath,
                                        startMs = startMs,
                                        endMs = endMs,
                                    )
                                trimSaveInProgress = false
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar(
                                        "Clipe cortado salvo na galeria",
                                        withDismissAction = true,
                                    )
                                    segmentToPreview = null
                                } else {
                                    snackbarHostState.showSnackbar(
                                        "Erro ao salvar: ${result.exceptionOrNull()?.message ?: "desconhecido"}",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        },
                        enabled = !trimSaveInProgress && canPreview && previewClipDurationMs > 0L,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (trimSaveInProgress) "Salvando..." else "Salvar cortado")
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    TextButton(
                        onClick = {
                            if (trimSaveInProgress) return@TextButton
                            segmentToPreview = null
                        },
                        enabled = !trimSaveInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancelar")
                    }
                }
            },
        )
    }

    val deleteCandidate = segmentToDelete
    if (deleteCandidate != null) {
        AlertDialog(
            onDismissRequest = { segmentToDelete = null },
            title = { Text("Excluir clipe?") },
            text = {
                Text(
                    text = "Isso vai apagar o arquivo temporário e remover da galeria interna. Não afeta a galeria do Android.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = AppGraph.recordingSegmentController.deleteTemporarySegment(deleteCandidate.id)
                            segmentToDelete = null
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar("Clipe excluído", withDismissAction = true)
                            } else {
                                snackbarHostState.showSnackbar(
                                    "Erro ao excluir: ${result.exceptionOrNull()?.message ?: "desconhecido"}",
                                    withDismissAction = true,
                                )
                            }
                        }
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { segmentToDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun GalleryItemCard(
    segment: RecordingSegment,
    isSelected: Boolean,
    onPreview: () -> Unit,
    onSaveToGallery: () -> Unit,
    onDeleteTemporary: () -> Unit,
) {
    val displayName = segment.displayName ?: "Clipe"
    val durationLabel = formatDurationLabel(segment)
    val dateStr = segment.requestedAt.atZone(java.time.ZoneId.systemDefault()).format(dateFormat)
    val canSaveToGallery = segment.status == RecordingSegmentStatus.READY
    val statusLine =
        temporaryStatusLine(
            status = segment.status,
            failureReason = segment.failureReason,
        )

    val cardShape = RoundedCornerShape(18.dp)
    val pillShape = RoundedCornerShape(999.dp)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            shape = cardShape,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = cardShape,
        onClick = { if (segment.status == RecordingSegmentStatus.READY) onPreview() },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (isSelected) 0.22f else 0.10f,
                    ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VideoThumbnail(segment = segment)

            Column(
                modifier = Modifier
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = "$durationLabel · $dateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                )

                if (statusLine != null) {
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 2,
                    )
                }

                Button(
                    onClick = onSaveToGallery,
                    modifier = Modifier.padding(top = 10.dp),
                    enabled = canSaveToGallery,
                    shape = pillShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                ) {
                    Text("Salvar na galeria", fontWeight = FontWeight.SemiBold)
                }
            }

            IconButton(
                onClick = onDeleteTemporary,
                modifier = Modifier
                    .size(44.dp),
                ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Excluir clipe temporário",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VideoThumbnail(segment: RecordingSegment) {
    val path = segment.outputFilePath
    var bitmap by remember(segment.id, path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(path, segment.status) {
        if (path != null && segment.status == RecordingSegmentStatus.READY) {
            Log.d(
                CLIP_DEBUG_TAG,
                "THUMBNAIL requestedAt=${segment.requestedAt} id=${segment.id} outputPath=$path frameAtTimeUs=0",
            )
            bitmap = withContext(Dispatchers.IO) {
                try {
                    extractFirstFrameBitmap(path)
                } catch (_: Throwable) {
                    null
                }
            }
        } else {
            bitmap = null
        }
    }

    val size = 72.dp
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (segment.status) {
                RecordingSegmentStatus.PROCESSING ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                RecordingSegmentStatus.PENDING ->
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                RecordingSegmentStatus.FAILED ->
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                else ->
                    Text(
                        text = "▶",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

private fun extractFirstFrameBitmap(path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)

        // Extrai o primeiro frame real do arquivo final:
        // - se a mídia for mais curta que o solicitado, o arquivo gerado já reflete o início real
        // - o frame em 0 us representa o começo do arquivo resultante.
        val frameAtStart =
            retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        Log.d(
            CLIP_DEBUG_TAG,
            "THUMB extractFirstFrameBitmap: path=$path frameAtTimeUs=0 hasBitmap=${frameAtStart != null}",
        )

        val bitmap = frameAtStart ?: retriever.getFrameAtTime(
            100_000L, // fallback: 0.1s
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        )

        if (bitmap == null) return null

        val rotationDegrees =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0

        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatDurationSeconds(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return if (safe < 60) {
        "${safe} s"
    } else {
        val minutes = safe / 60
        val seconds = safe % 60
        "${minutes} min ${seconds} s"
    }
}

private fun formatMillisLabel(totalMillis: Long): String {
    val seconds = (totalMillis / 1_000L).coerceAtLeast(0L)
    val minutesPart = seconds / 60L
    val secondsPart = seconds % 60L
    return "%02d:%02d".format(minutesPart, secondsPart)
}

private suspend fun saveTrimmedVideoToGallery(
    context: android.content.Context,
    sourcePath: String,
    startMs: Long,
    endMs: Long,
): Result<android.net.Uri> =
    withContext(Dispatchers.IO) {
        runCatching {
            val sourceFile = File(sourcePath)
            require(sourceFile.exists()) { "Arquivo de origem não encontrado" }
            require(endMs > startMs) { "Intervalo de corte inválido" }

            val durationMs = endMs - startMs
            val extractor = MediaMuxerVideoSegmentExtractor(context.applicationContext)
            val trimmedFile =
                extractor.extractSegmentAt(
                    source = sourceFile,
                    durationSeconds = max(1, ((durationMs + 999L) / 1_000L).toInt()),
                    endOffsetMillis = endMs,
                )

            try {
                val saver = MediaStoreVideoGallerySaver(context.applicationContext)
                saver.saveVideoToGallery(trimmedFile).getOrThrow().uri
            } finally {
                runCatching { trimmedFile.delete() }
            }
        }
    }

@Composable
private fun VideoPreviewPlayer(
    videoPath: String,
    trimStartMs: Long = 0L,
    trimEndMs: Long? = null,
    trimWindowChangeToken: Long = 0L,
    onDurationKnown: (Long) -> Unit = {},
) {
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var lastAppliedTrimToken by remember { mutableStateOf(-1L) }

    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoPath) {
        onDispose {
            videoViewRef.value?.stopPlayback()
            videoViewRef.value = null
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(340.dp),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewRef.value = this

                    setVideoPath(videoPath)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        durationMs = mp.duration.toLong().coerceAtLeast(0L)
                        onDurationKnown(durationMs)
                        val startCap = trimStartMs.coerceIn(0L, durationMs)
                        seekTo(startCap.toInt())
                        start()
                        isPlaying = true
                    }
                    setOnCompletionListener {
                        isPlaying = false
                    }
                }
            },
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            val maxMs = durationMs.takeIf { it > 0 } ?: 0L
            val startCap = trimStartMs.coerceIn(0L, maxMs)
            val endCap = (trimEndMs?.coerceAtMost(maxMs) ?: maxMs).coerceAtLeast(startCap)
            val sliderMin = startCap
            val sliderMax = endCap
            val sliderValue = positionMs.coerceIn(sliderMin, sliderMax)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val vv = videoViewRef.value ?: return@IconButton
                        val startCap = trimStartMs.coerceAtLeast(0L)
                        val endCap = trimEndMs?.coerceAtMost(durationMs).takeIf { it != null && it > 0L } ?: durationMs
                        if (vv.isPlaying) {
                            vv.pause()
                            isPlaying = false
                        } else {
                            // Comportamento de edição: play sempre inicia do começo do trecho selecionado.
                            vv.seekTo(startCap.coerceAtMost(endCap).toInt())
                            positionMs = startCap.coerceAtMost(endCap)
                            vv.start()
                            isPlaying = true
                        }
                    },
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Text(
                        text = if (isPlaying) "||" else "▶",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

            Slider(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp, end = 6.dp)
                    .height(18.dp),
                value = sliderValue.toFloat(),
                valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
                enabled = maxMs > 0 && sliderMax > sliderMin,
                colors =
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        activeTrackColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        inactiveTrackColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    ),
                onValueChange = { newValue ->
                    positionMs = newValue.toLong().coerceIn(sliderMin, sliderMax)
                    isUserSeeking = true
                },
                onValueChangeFinished = {
                    val vv = videoViewRef.value
                    if (vv != null) vv.seekTo(positionMs.coerceIn(sliderMin, sliderMax).toInt())
                    isUserSeeking = false
                },
            )

                Text(
                    text = formatMillisLabel(sliderValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    LaunchedEffect(trimWindowChangeToken, trimStartMs, trimEndMs, durationMs) {
        val vv = videoViewRef.value ?: return@LaunchedEffect
        if (durationMs <= 0L) return@LaunchedEffect
        val startCap = trimStartMs.coerceIn(0L, durationMs)
        val endCap = (trimEndMs?.coerceAtMost(durationMs) ?: durationMs).coerceAtLeast(startCap)
        val trimChanged = trimWindowChangeToken != lastAppliedTrimToken
        val shouldSeek = trimChanged || positionMs < startCap || positionMs > endCap
        if (shouldSeek) {
            vv.seekTo(startCap.toInt())
            positionMs = startCap
        }
        if (vv.isPlaying && endCap > 0L && positionMs >= endCap) {
            vv.pause()
            isPlaying = false
        }
        lastAppliedTrimToken = trimWindowChangeToken
    }

    LaunchedEffect(videoPath) {
        while (true) {
            val vv = videoViewRef.value ?: return@LaunchedEffect

            isPlaying = vv.isPlaying
            val dur = vv.duration.toLong().coerceAtLeast(0L)
            if (dur > 0) {
                durationMs = dur
                onDurationKnown(dur)
            }
            val pos = vv.currentPosition.toLong().coerceAtLeast(0L)
            val startCap = trimStartMs.coerceIn(0L, dur)
            val endCap = (trimEndMs?.coerceAtMost(dur) ?: dur).coerceAtLeast(startCap)

            // Mantém o preview sempre dentro da janela [startCap, endCap].
            if (endCap > 0L && pos < startCap) {
                vv.seekTo(startCap.toInt())
                positionMs = startCap
            } else {
                positionMs = pos
            }

            // Interrompe imediatamente ao atingir o fim do recorte.
            // Usamos pequena margem para compensar granulação de currentPosition em alguns devices.
            val endGuardMs = 25L
            if (vv.isPlaying && endCap > 0L && pos >= (endCap - endGuardMs).coerceAtLeast(startCap)) {
                vv.pause()
                vv.seekTo(startCap.toInt())
                isPlaying = false
                positionMs = startCap
            }

            // Poll curto para reduzir chance de ultrapassar o endCap no preview.
            delay(40)
        }
    }

    // Guarda dedicado do fim do recorte:
    // interrompe imediatamente quando currentPosition >= endCap.
    LaunchedEffect(videoPath, trimStartMs, trimEndMs, durationMs) {
        while (true) {
            val vv = videoViewRef.value ?: return@LaunchedEffect
            val dur = durationMs.takeIf { it > 0L } ?: vv.duration.toLong().coerceAtLeast(0L)
            if (dur > 0L && vv.isPlaying) {
                val startCap = trimStartMs.coerceIn(0L, dur)
                val endCap = (trimEndMs?.coerceAtMost(dur) ?: dur).coerceAtLeast(startCap)
                val pos = vv.currentPosition.toLong().coerceAtLeast(0L)
                if (pos >= endCap) {
                    vv.pause()
                    vv.seekTo(startCap.toInt())
                    isPlaying = false
                    positionMs = startCap
                }
            }
            delay(16)
        }
    }
}

