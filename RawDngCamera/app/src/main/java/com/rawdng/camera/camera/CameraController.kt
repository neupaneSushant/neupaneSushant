package com.rawdng.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class CameraCapabilities(
    val cameraId: String,
    val rawSizes: List<Size>,
    val isoRange: Range<Int>,
    val exposureTimeRange: Range<Long>,
    val evRange: Range<Int>,
    val evStep: Float,
    val supportedRaw: Boolean
)

sealed class CameraState {
    object Closed : CameraState()
    object Opening : CameraState()
    data class Ready(val capabilities: CameraCapabilities) : CameraState()
    data class Error(val message: String) : CameraState()
}

class CameraController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewImageReader: ImageReader? = null

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var rawRecorder: RawRecorder? = null
    private var currentCapabilities: CameraCapabilities? = null

    var iso: Int = 400
    var shutterSpeedNs: Long = 16_666_666L   // 1/60s
    var ev: Int = 0
    var selectedRawSize: Size? = null

    fun discoverCameras(): List<CameraCapabilities> {
        val result = mutableListOf<CameraCapabilities>()
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            val supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps

            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val rawSizes = if (supportsRaw) {
                streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
            } else emptyList()

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: Range(100, 3200)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(1_000_000L, 1_000_000_000L)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: Range(-12, 12)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f

            result.add(
                CameraCapabilities(
                    cameraId = id,
                    rawSizes = rawSizes,
                    isoRange = isoRange,
                    exposureTimeRange = expRange,
                    evRange = evRange,
                    evStep = evStep,
                    supportedRaw = supportsRaw
                )
            )
        }
        return result
    }

    fun openCamera(
        cameraId: String,
        previewSurface: Surface,
        onReady: (CameraCapabilities) -> Unit,
        onError: (String) -> Unit
    ) {
        _cameraState.value = CameraState.Opening
        val capabilities = discoverCameras().find { it.cameraId == cameraId }
            ?: return onError("Camera $cameraId not found or does not support RAW")

        currentCapabilities = capabilities
        if (selectedRawSize == null && capabilities.rawSizes.isNotEmpty()) {
            selectedRawSize = capabilities.rawSizes.minByOrNull { it.width * it.height }
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupPreviewSession(camera, previewSurface, capabilities, onReady, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                _cameraState.value = CameraState.Closed
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                val msg = "Camera error: $error"
                _cameraState.value = CameraState.Error(msg)
                onError(msg)
            }
        }, cameraHandler)
    }

    private fun setupPreviewSession(
        camera: CameraDevice,
        previewSurface: Surface,
        capabilities: CameraCapabilities,
        onReady: (CameraCapabilities) -> Unit,
        onError: (String) -> Unit
    ) {
        val rawSize = selectedRawSize ?: capabilities.rawSizes.firstOrNull()
        if (rawSize == null) {
            onError("No RAW sizes available for this camera")
            return
        }

        previewImageReader?.close()
        previewImageReader = ImageReader.newInstance(
            rawSize.width, rawSize.height,
            ImageFormat.RAW_SENSOR, 2
        ).also { reader ->
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, cameraHandler)
        }

        val outputs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(previewImageReader!!.surface)
        )

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreviewRequest(session, previewSurface)
                    _cameraState.value = CameraState.Ready(capabilities)
                    onReady(capabilities)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val msg = "Session configuration failed"
                    _cameraState.value = CameraState.Error(msg)
                    onError(msg)
                }
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun startPreviewRequest(session: CameraCaptureSession, previewSurface: Surface) {
        val chars = cameraManager.getCameraCharacteristics(
            cameraDevice?.id ?: return
        )
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedNs)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        }
        session.setRepeatingRequest(builder.build(), null, cameraHandler)
    }

    fun startRecording(recorder: RawRecorder, previewSurface: Surface) {
        val session = captureSession ?: return
        val reader = previewImageReader ?: return
        rawRecorder = recorder

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireNextImage() ?: return@setOnImageAvailableListener
            val chars = cameraManager.getCameraCharacteristics(cameraDevice?.id ?: return@setOnImageAvailableListener)
            recorder.writeFrame(image, chars)
        }, cameraHandler)

        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(previewSurface)
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedNs)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
        }
        session.setRepeatingRequest(builder.build(), recorder.captureCallback, cameraHandler)
    }

    fun stopRecording(previewSurface: Surface) {
        val session = captureSession ?: return
        rawRecorder = null

        previewImageReader?.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, cameraHandler)
        startPreviewRequest(session, previewSurface)
    }

    fun updateSettings(newIso: Int, newShutterNs: Long, newEv: Int, previewSurface: Surface) {
        iso = newIso
        shutterSpeedNs = newShutterNs
        ev = newEv
        val session = captureSession ?: return
        if (rawRecorder?.isRecording == true) {
            startRecording(rawRecorder!!, previewSurface)
        } else {
            startPreviewRequest(session, previewSurface)
        }
    }

    fun changeResolution(newSize: Size, previewSurface: Surface, onReady: (CameraCapabilities) -> Unit, onError: (String) -> Unit) {
        selectedRawSize = newSize
        val device = cameraDevice ?: return
        val caps = currentCapabilities ?: return
        captureSession?.close()
        captureSession = null
        previewImageReader?.close()
        previewImageReader = null
        setupPreviewSession(device, previewSurface, caps, onReady, onError)
    }

    fun close() {
        captureSession?.close()
        captureSession = null
        previewImageReader?.close()
        previewImageReader = null
        cameraDevice?.close()
        cameraDevice = null
        _cameraState.value = CameraState.Closed
    }
}
