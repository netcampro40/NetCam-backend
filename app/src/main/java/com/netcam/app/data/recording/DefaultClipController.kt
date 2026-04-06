package com.netcam.app.data.recording

import android.util.Log
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.domain.recording.ClipController
import com.netcam.app.domain.recording.RecordingSegmentController
import com.netcam.app.domain.session.NetCamSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultClipController(
    private val sessionController: NetCamSessionController,
    private val recordingSegmentController: RecordingSegmentController,
    /** Feedback imediato quando uma solicitação de clipe foi aceita (comando reconhecido). */
    private val onClipActionAccepted: () -> Unit = {},
) : ClipController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val triggerMutex = Mutex()
    private val _lastTriggeredRequest = MutableStateFlow<RecordingSegmentType?>(null)
    override val lastTriggeredRequest: StateFlow<RecordingSegmentType?> = _lastTriggeredRequest.asStateFlow()

    override fun saveLastMinute() {
        request(type = RecordingSegmentType.ONE_MINUTE, source = "saveLastMinute")
    }

    override fun saveLastTwoMinutes() {
        request(type = RecordingSegmentType.TWO_MINUTES, source = "saveLastTwoMinutes")
    }

    override fun saveFullSession() {
        request(type = RecordingSegmentType.FULL_SESSION, source = "saveFullSession")
    }

    private fun request(
        type: RecordingSegmentType,
        source: String,
    ) {
        scope.launch {
            triggerMutex.withLock {
                if (!sessionController.isSessionActive()) {
                    Log.w(TAG, "$source ignorado: sessão inativa")
                    return@withLock
                }
                if (recordingSegmentController.isContinuousRecordingFinalizationPending()) {
                    Log.w(TAG, "$source ignorado: aguardando finalização da sessão anterior")
                    return@withLock
                }
                recordingSegmentController.requestSegment(type)
                Log.d(TAG, "$source executado -> requestSegment(${type.name})")
                onClipActionAccepted()
                _lastTriggeredRequest.value = type
                scope.launch {
                    delay(1_000L)
                    if (_lastTriggeredRequest.value == type) {
                        _lastTriggeredRequest.value = null
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ClipController"
    }
}

