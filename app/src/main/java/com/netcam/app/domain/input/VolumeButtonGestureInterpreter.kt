package com.netcam.app.domain.input

import android.view.KeyEvent
import com.netcam.app.domain.model.RecordingSegmentType
import com.netcam.app.domain.recording.ClipController
import com.netcam.app.domain.session.NetCamSessionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Camada responsável por interpretar gestos no botão volume+.
 *
 * Ela traduz os eventos brutos de tecla (down/up) em gestos de 1 clique,
 * 2 cliques ou pressão longa, e dispara solicitações de segmentos
 * através do [RecordingSegmentController].
 *
 * Regras:
 * - 1 clique: segmento de 1 minuto
 * - 2 cliques rápidos: segmento de 2 minutos
 * - pressionar e segurar por 3 segundos: segmento da seção inteira (do início da gravação até agora)
 *
 * Tudo isso só é considerado quando existe sessão ativa.
 */
class VolumeButtonGestureInterpreter(
    private val sessionController: NetCamSessionController,
    private val clipController: ClipController,
) {

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lastRecognizedRequest =
        MutableStateFlow<RecordingSegmentType?>(null)
    val lastRecognizedRequest: StateFlow<RecordingSegmentType?> =
        _lastRecognizedRequest.asStateFlow()

    private val _lastGestureAtMs = MutableStateFlow<Long?>(null)
    val lastGestureAtMs: StateFlow<Long?> = _lastGestureAtMs.asStateFlow()

    private var isControlTestModeActive: Boolean = false

    private val _controlTestDetectedAtMs = MutableStateFlow<Long?>(null)
    val controlTestDetectedAtMs: StateFlow<Long?> = _controlTestDetectedAtMs.asStateFlow()

    /**
     * Última vez que o app recebeu qualquer evento de Volume+ durante um teste.
     *
     * Usamos para distinguir:
     * - controle pareado no sistema
     * - controle conectado agora (inferido pelo teste recente)
     * - controle validado manualmente por teste
     */
    private val _lastTestValidatedAtMs = MutableStateFlow<Long?>(null)
    val lastTestValidatedAtMs: StateFlow<Long?> = _lastTestValidatedAtMs.asStateFlow()

    fun setControlTestModeActive(active: Boolean) {
        isControlTestModeActive = active
        if (active) {
            _controlTestDetectedAtMs.value = null
        } else {
            _controlTestDetectedAtMs.value = null
        }
        // Garante que o estado interno de cliques não “vaze” entre modos.
        resetState()
    }

    private var clickCount = 0
    private var firstClickUptime: Long = 0L
    private var longPressJob: Job? = null
    private var clickTimeoutJob: Job? = null
    private var isPressed = false
    private val longPressConsumed = AtomicBoolean(false)

    // Janela máxima entre cliques para considerar "2 cliques"
    private val multiClickTimeoutMillis: Long = 400L

    // Duração necessária para considerar um "press and hold"
    private val longPressThresholdMillis: Long = 3_000L

    /**
     * Deve ser chamado a partir da Activity (por exemplo, em dispatchKeyEvent)
     * sempre que um evento de tecla for recebido.
     *
     * Retorna true se o evento foi consumido pelo NetCam,
     * false caso contrário (permite que o sistema ajuste o volume).
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        // Modo de teste para controle compatível (Volume+).
        // Aqui a prioridade é apenas confirmar recebimento do evento,
        // sem acionar lógica de salvar clipes.
        if (isControlTestModeActive) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = System.currentTimeMillis()
                if (_controlTestDetectedAtMs.value == null) {
                    _controlTestDetectedAtMs.value = now
                    _lastTestValidatedAtMs.value = now
                }
            }
            resetState()
            return true
        }

        if (!sessionController.isSessionActive()) {
            // Fora de sessão o NetCam não interfere no volume.
            resetState()
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleActionDown(event)
            KeyEvent.ACTION_UP -> handleActionUp(event)
        }

        // Durante sessão ativa, o NetCam consome o evento de volume+.
        return true
    }

    private fun handleActionDown(event: KeyEvent) {
        if (event.repeatCount > 0) {
            // Repetições automáticas não são consideradas novos cliques.
            return
        }

        isPressed = true
        longPressConsumed.set(false)

        longPressJob?.cancel()
        longPressJob =
            scope.launch {
                delay(longPressThresholdMillis)
                if (isPressed && !longPressConsumed.get()) {
                    longPressConsumed.set(true)
                    // Pressão longa reconhecida.
                    triggerSegment(RecordingSegmentType.FULL_SESSION)
                    // Após uma pressão longa, não queremos que o ACTION_UP gere clique.
                    resetClicks()
                }
            }
    }

    private fun handleActionUp(event: KeyEvent) {
        isPressed = false
        longPressJob?.cancel()

        if (longPressConsumed.get()) {
            // Já tratamos como pressão longa, ignorar o "up".
            return
        }

        val now = event.eventTime

        if (clickCount == 0) {
            clickCount = 1
            firstClickUptime = now

            clickTimeoutJob?.cancel()
            clickTimeoutJob =
                scope.launch {
                    delay(multiClickTimeoutMillis)
                    if (clickCount == 1 && !longPressConsumed.get()) {
                        // Janela de duplo clique passou sem segundo clique.
                        triggerSegment(RecordingSegmentType.ONE_MINUTE)
                    }
                    resetClicks()
                }
        } else {
            val delta = now - firstClickUptime
            if (delta <= multiClickTimeoutMillis) {
                clickCount = 2
                clickTimeoutJob?.cancel()
                if (!longPressConsumed.get()) {
                    triggerSegment(RecordingSegmentType.TWO_MINUTES)
                }
                resetClicks()
            } else {
                // Muito tempo entre os cliques, tratar como novo primeiro clique.
                clickCount = 1
                firstClickUptime = now
                clickTimeoutJob?.cancel()
                clickTimeoutJob =
                    scope.launch {
                        delay(multiClickTimeoutMillis)
                        if (clickCount == 1 && !longPressConsumed.get()) {
                            triggerSegment(RecordingSegmentType.ONE_MINUTE)
                        }
                        resetClicks()
                    }
            }
        }
    }

    /**
     * Mesmo feedback visual do chip na tela de filmagem ([lastRecognizedRequest]) para comandos
     * que não passam por teclas Volume+ (ex.: BLE). Só faz sentido com sessão ativa, como em [triggerSegment].
     */
    fun emitClipFeedback(type: RecordingSegmentType) {
        if (!sessionController.isSessionActive()) {
            return
        }
        emitClipFeedbackUi(type)
    }

    private fun emitClipFeedbackUi(type: RecordingSegmentType) {
        _lastRecognizedRequest.value = type
        _lastGestureAtMs.value = System.currentTimeMillis()
        scope.launch {
            delay(1_000L)
            if (_lastRecognizedRequest.value == type) {
                _lastRecognizedRequest.value = null
            }
        }
    }

    private fun triggerSegment(type: RecordingSegmentType) {
        if (!sessionController.isSessionActive()) {
            return
        }
        when (type) {
            RecordingSegmentType.ONE_MINUTE -> clipController.saveLastMinute()
            RecordingSegmentType.TWO_MINUTES -> clipController.saveLastTwoMinutes()
            RecordingSegmentType.FULL_SESSION -> clipController.saveFullSession()
        }
        emitClipFeedbackUi(type)
    }

    private fun resetClicks() {
        clickCount = 0
        firstClickUptime = 0L
        clickTimeoutJob?.cancel()
        clickTimeoutJob = null
    }

    private fun resetState() {
        isPressed = false
        longPressJob?.cancel()
        longPressJob = null
        resetClicks()
        longPressConsumed.set(false)
    }
}

