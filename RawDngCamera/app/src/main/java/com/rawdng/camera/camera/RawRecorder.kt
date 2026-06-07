package com.rawdng.camera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RawRecorder(private val recordingsBaseDir: File) {

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames.asStateFlow()

    private val _recordingDir = MutableStateFlow<File?>(null)
    val recordingDir: StateFlow<File?> = _recordingDir.asStateFlow()

    private val _isRecording = MutableStateFlow(false)

    val isRecording: Boolean get() = _isRecording.value

    private val frameIndex = AtomicInteger(0)
    private val lastCaptureResult = java.util.concurrent.ConcurrentLinkedQueue<TotalCaptureResult>()
    private var outputDir: File? = null

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (_isRecording.value) {
                lastCaptureResult.offer(result)
                if (lastCaptureResult.size > 5) lastCaptureResult.poll()
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (_isRecording.value) {
                _droppedFrames.value++
            }
        }
    }

    fun startRecording(): File {
        recordingsBaseDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(recordingsBaseDir, "REC_$timestamp")
        dir.mkdirs()
        outputDir = dir
        _recordingDir.value = dir
        frameIndex.set(0)
        _frameCount.value = 0
        _droppedFrames.value = 0
        lastCaptureResult.clear()
        _isRecording.value = true
        Log.d("RawRecorder", "Recording started: ${dir.absolutePath}")
        return dir
    }

    fun stopRecording(): RecordingInfo? {
        _isRecording.value = false
        val dir = outputDir ?: return null
        val frames = _frameCount.value
        Log.d("RawRecorder", "Recording stopped: $frames frames in ${dir.name}")
        return RecordingInfo(
            directory = dir,
            frameCount = frames,
            droppedFrames = _droppedFrames.value,
            timestamp = dir.lastModified(),
            sizeBytes = dir.walkTopDown().sumOf { it.length() }
        )
    }

    fun writeFrame(image: Image, characteristics: CameraCharacteristics) {
        if (!_isRecording.value) {
            image.close()
            return
        }
        val dir = outputDir
        if (dir == null) {
            image.close()
            return
        }

        val index = frameIndex.getAndIncrement()
        val captureResult = lastCaptureResult.poll()

        if (captureResult == null) {
            image.close()
            _droppedFrames.value++
            return
        }

        val frameFile = File(dir, "frame_%05d.dng".format(index))
        try {
            FileOutputStream(frameFile).use { fos ->
                val dngCreator = DngCreator(characteristics, captureResult)
                dngCreator.writeImage(fos, image)
                dngCreator.close()
            }
            _frameCount.value = index + 1
        } catch (e: Exception) {
            Log.e("RawRecorder", "Failed to write frame $index", e)
            frameFile.delete()
            _droppedFrames.value++
        } finally {
            image.close()
        }
    }

    companion object {
        fun loadRecordings(baseDir: File): List<RecordingInfo> {
            if (!baseDir.exists()) return emptyList()
            return baseDir.listFiles { file -> file.isDirectory && file.name.startsWith("REC_") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { dir ->
                    val dngFiles = dir.listFiles { f -> f.extension == "dng" } ?: emptyArray()
                    RecordingInfo(
                        directory = dir,
                        frameCount = dngFiles.size,
                        droppedFrames = 0,
                        timestamp = dir.lastModified(),
                        sizeBytes = dir.walkTopDown().sumOf { it.length() }
                    )
                } ?: emptyList()
        }
    }
}

data class RecordingInfo(
    val directory: File,
    val frameCount: Int,
    val droppedFrames: Int,
    val timestamp: Long,
    val sizeBytes: Long
) {
    val name: String get() = directory.name
    val formattedSize: String get() {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${"%.1f".format(sizeBytes / 1024.0)}KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${"%.1f".format(sizeBytes / (1024.0 * 1024))}MB"
            else -> "${"%.2f".format(sizeBytes / (1024.0 * 1024 * 1024))}GB"
        }
    }
    val formattedDate: String get() {
        return SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US).format(Date(timestamp))
    }
}
