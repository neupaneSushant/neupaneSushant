package com.rawdng.camera.viewmodel

import android.app.Application
import android.os.Environment
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawdng.camera.camera.CameraCapabilities
import com.rawdng.camera.camera.CameraController
import com.rawdng.camera.camera.CameraState
import com.rawdng.camera.camera.RawRecorder
import com.rawdng.camera.camera.RecordingInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class ShutterOption(val label: String, val nanoseconds: Long)
data class IsoOption(val value: Int, val label: String)

val SHUTTER_OPTIONS = listOf(
    ShutterOption("1/4000", 250_000L),
    ShutterOption("1/2000", 500_000L),
    ShutterOption("1/1000", 1_000_000L),
    ShutterOption("1/500", 2_000_000L),
    ShutterOption("1/250", 4_000_000L),
    ShutterOption("1/125", 8_000_000L),
    ShutterOption("1/60",  16_666_666L),
    ShutterOption("1/30",  33_333_333L),
    ShutterOption("1/15",  66_666_666L),
    ShutterOption("1/8",   125_000_000L),
    ShutterOption("1/4",   250_000_000L),
    ShutterOption("1/2",   500_000_000L),
    ShutterOption("1s",    1_000_000_000L)
)

val ISO_OPTIONS = listOf(100, 200, 400, 800, 1600, 3200).map {
    IsoOption(it, "ISO $it")
}

data class CameraUiState(
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val frameCount: Int = 0,
    val droppedFrames: Int = 0,
    val isoIndex: Int = 2,           // ISO 400 default
    val shutterIndex: Int = 6,       // 1/60 default
    val evSteps: Int = 0,            // 0 EV
    val capabilities: CameraCapabilities? = null,
    val availableRawSizes: List<Size> = emptyList(),
    val selectedSizeIndex: Int = 0,
    val cameraReady: Boolean = false,
    val error: String? = null,
    val showSettings: Boolean = false,
    val lastRecording: RecordingInfo? = null
) {
    val iso: Int get() = ISO_OPTIONS.getOrNull(isoIndex)?.value ?: 400
    val shutterNs: Long get() = SHUTTER_OPTIONS.getOrNull(shutterIndex)?.nanoseconds ?: 16_666_666L
    val shutterLabel: String get() = SHUTTER_OPTIONS.getOrNull(shutterIndex)?.label ?: "1/60"
    val isoLabel: String get() = ISO_OPTIONS.getOrNull(isoIndex)?.label ?: "ISO 400"
    val evLabel: String get() = when {
        evSteps > 0 -> "+${"%.1f".format(evSteps * EV_STEP)}"
        evSteps < 0 -> "${"%.1f".format(evSteps * EV_STEP)}"
        else -> "0.0"
    }
    val selectedSize: Size? get() = availableRawSizes.getOrNull(selectedSizeIndex)
    val recordingTimestamp: String get() {
        val h = recordingSeconds / 3600
        val m = (recordingSeconds % 3600) / 60
        val s = recordingSeconds % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
    companion object {
        const val EV_STEP = 0.333f
    }
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val cameraController = CameraController(application)
    private var rawRecorder: RawRecorder? = null
    private var timerJob: Job? = null

    val recordingsDir: File = File(
        application.getExternalFilesDir(Environment.DIRECTORY_DCIM),
        "RawDngCamera"
    )

    init {
        viewModelScope.launch {
            cameraController.cameraState.collect { state ->
                when (state) {
                    is CameraState.Ready -> {
                        val caps = state.capabilities
                        val sizes = caps.rawSizes.sortedByDescending { it.width * it.height }
                        _uiState.update { it.copy(
                            capabilities = caps,
                            availableRawSizes = sizes,
                            selectedSizeIndex = sizes.size - 1,  // smallest by default for speed
                            cameraReady = true,
                            error = null
                        )}
                    }
                    is CameraState.Error -> _uiState.update { it.copy(error = state.message, cameraReady = false) }
                    CameraState.Opening -> _uiState.update { it.copy(cameraReady = false) }
                    CameraState.Closed -> _uiState.update { it.copy(cameraReady = false) }
                }
            }
        }
    }

    fun openCamera(cameraId: String, previewSurface: Surface) {
        cameraController.iso = _uiState.value.iso
        cameraController.shutterSpeedNs = _uiState.value.shutterNs
        cameraController.ev = _uiState.value.evSteps
        cameraController.openCamera(
            cameraId = cameraId,
            previewSurface = previewSurface,
            onReady = { },
            onError = { msg -> _uiState.update { it.copy(error = msg) } }
        )
    }

    fun startRecording(previewSurface: Surface) {
        if (_uiState.value.isRecording || !_uiState.value.cameraReady) return
        rawRecorder?.let { recorder ->
            recorder.startRecording()
            cameraController.startRecording(recorder, previewSurface)
            _uiState.update { it.copy(isRecording = true, frameCount = 0, droppedFrames = 0) }
            timerJob = viewModelScope.launch {
                var secs = 0
                while (true) {
                    delay(1000)
                    secs++
                    _uiState.update { state ->
                        state.copy(
                            recordingSeconds = secs,
                            frameCount = recorder.frameCount.value,
                            droppedFrames = recorder.droppedFrames.value
                        )
                    }
                }
            }
            viewModelScope.launch {
                recorder.frameCount.collect { count ->
                    if (_uiState.value.isRecording) {
                        _uiState.update { it.copy(frameCount = count, droppedFrames = recorder.droppedFrames.value) }
                    }
                }
            }
        }
    }

    fun stopRecording(previewSurface: Surface) {
        if (!_uiState.value.isRecording) return
        timerJob?.cancel()
        timerJob = null
        val info = rawRecorder?.stopRecording()
        cameraController.stopRecording(previewSurface)
        _uiState.update { it.copy(isRecording = false, recordingSeconds = 0, lastRecording = info) }
    }

    fun setIso(index: Int, previewSurface: Surface) {
        _uiState.update { it.copy(isoIndex = index) }
        applySettings(previewSurface)
    }

    fun setShutter(index: Int, previewSurface: Surface) {
        _uiState.update { it.copy(shutterIndex = index) }
        applySettings(previewSurface)
    }

    fun setEv(steps: Int, previewSurface: Surface) {
        _uiState.update { it.copy(evSteps = steps) }
        applySettings(previewSurface)
    }

    fun setResolution(index: Int, previewSurface: Surface) {
        val state = _uiState.value
        val size = state.availableRawSizes.getOrNull(index) ?: return
        _uiState.update { it.copy(selectedSizeIndex = index) }
        cameraController.selectedRawSize = size
        val caps = state.capabilities ?: return
        cameraController.changeResolution(
            newSize = size,
            previewSurface = previewSurface,
            onReady = { },
            onError = { msg -> _uiState.update { it.copy(error = msg) } }
        )
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun initRecorder() {
        rawRecorder = RawRecorder(recordingsDir)
    }

    private fun applySettings(previewSurface: Surface) {
        val state = _uiState.value
        cameraController.updateSettings(state.iso, state.shutterNs, state.evSteps, previewSurface)
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.close()
    }
}
