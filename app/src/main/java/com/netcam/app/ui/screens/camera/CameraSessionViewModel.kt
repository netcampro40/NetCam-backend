package com.netcam.app.ui.screens.camera

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netcam.app.data.recording.CameraXVideoEngine
import com.netcam.app.di.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraSessionUiState(
    val isActive: Boolean = false,
    val elapsedMillis: Long = 0L,
    val sessionError: String? = null,
)

class CameraSessionViewModel : ViewModel() {

    private companion object {
        private const val TAG = "CameraSessionVM"
    }

    private val sessionController = AppGraph.sessionController
    private val continuousRecordingController = AppGraph.continuousRecordingController
    private val recordingSegmentController = AppGraph.recordingSegmentController

    private val _uiState =
        MutableStateFlow(
            CameraSessionUiState(
                isActive = sessionController.isSessionActive(),
                elapsedMillis = 0L,
            ),
        )
    val uiState: StateFlow<CameraSessionUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    fun startSession(): Boolean {
        _uiState.update { it.copy(sessionError = null) }

        recordingSegmentController.recoverStuckFinalizationIfNeeded()

        if (recordingSegmentController.isContinuousRecordingFinalizationPending()) {
            val message = "Aguarde a finalização da gravação anterior e tente novamente."
            Log.w(TAG, "[CAMERA] startSession blocked: finalization pending")
            _uiState.update { it.copy(sessionError = message) }
            return false
        }

        if (CameraXVideoEngine.videoCapture == null) {
            val message = "Câmera ainda não está pronta para gravar."
            Log.w(TAG, "[CAMERA] startSession blocked: videoCapture null")
            _uiState.update { it.copy(sessionError = message) }
            return false
        }

        if (continuousRecordingController.isBaseRecordingActive()) {
            Log.w(TAG, "[CAMERA] stale base recording flag; forcing release")
            continuousRecordingController.forceReleaseStaleRecording("start_session_recovery")
        }

        sessionController.startSession()
        recordingSegmentController.onSessionStarted()
        continuousRecordingController.startBaseRecording()

        if (!continuousRecordingController.isBaseRecordingActive()) {
            Log.w(TAG, "[RECORDING] startSession failed: base recording inactive after start")
            sessionController.stopSession()
            recordingSegmentController.onSessionStopped()
            continuousRecordingController.forceReleaseStaleRecording("start_session_failed")
            _uiState.update {
                it.copy(
                    sessionError = "Não foi possível iniciar a gravação. Tente novamente.",
                )
            }
            return false
        }

        _uiState.update { it.copy(isActive = true, elapsedMillis = 0L, sessionError = null) }
        Log.d(TAG, "[RECORDING] session started")
        startTimer()
        return true
    }

    fun stopSession() {
        if (!_uiState.value.isActive) return

        sessionController.stopSession()
        recordingSegmentController.onSessionStopped()
        continuousRecordingController.stopBaseRecording(onFinalized = {
            recordingSegmentController.onContinuousRecordingFinalized()
        })

        timerJob?.cancel()
        timerJob = null

        _uiState.update { it.copy(isActive = false, elapsedMillis = 0L) }
        Log.d(TAG, "[RECORDING] session stopped")
    }

    fun clearSessionError() {
        _uiState.update { it.copy(sessionError = null) }
    }

    private fun startTimer() {
        timerJob?.cancel()

        timerJob =
            viewModelScope.launch {
                val startTime = System.currentTimeMillis()
                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime
                    _uiState.update { it.copy(elapsedMillis = elapsed) }
                    delay(1_000L)
                }
            }
    }

    override fun onCleared() {
        if (_uiState.value.isActive) {
            Log.w(TAG, "[CAMERA] onCleared with active session — stopping recording")
            stopSession()
        }
        super.onCleared()
    }
}
