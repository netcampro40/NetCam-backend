package com.netcam.app.ui.screens.camera

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun startSession() {
        if (_uiState.value.isActive) return
        if (recordingSegmentController.isContinuousRecordingFinalizationPending()) {
            Log.w(
                TAG,
                "startSession ignorado: aguardando finalização do arquivo contínuo da sessão anterior",
            )
            return
        }

        sessionController.startSession()
        recordingSegmentController.onSessionStarted()
        continuousRecordingController.startBaseRecording()
        if (!continuousRecordingController.isBaseRecordingActive()) {
            Log.w(TAG, "startSession abortado: gravação contínua não iniciou (camera engine indisponível)")
            sessionController.stopSession()
            return
        }

        _uiState.update { it.copy(isActive = true, elapsedMillis = 0L) }

        startTimer()
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
            Log.w(TAG, "onCleared com sessão ativa — encerrando gravação (fallback ao destruir ViewModel)")
            stopSession()
        }
        super.onCleared()
    }
}

