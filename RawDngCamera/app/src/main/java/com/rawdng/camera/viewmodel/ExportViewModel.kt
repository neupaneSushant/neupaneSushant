package com.rawdng.camera.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawdng.camera.camera.RawRecorder
import com.rawdng.camera.camera.RecordingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class ExportState {
    object Idle : ExportState()
    data class InProgress(val progress: Float, val message: String) : ExportState()
    data class Done(val outputPath: String) : ExportState()
    data class Failed(val error: String) : ExportState()
}

data class ExportUiState(
    val recordings: List<RecordingInfo> = emptyList(),
    val selectedRecording: RecordingInfo? = null,
    val exportState: ExportState = ExportState.Idle,
    val isLoading: Boolean = false
)

class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val recordingsDir = File(
        application.getExternalFilesDir(Environment.DIRECTORY_DCIM),
        "RawDngCamera"
    )

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    fun loadRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val recordings = RawRecorder.loadRecordings(recordingsDir)
            _uiState.update { it.copy(recordings = recordings, isLoading = false) }
        }
    }

    fun selectRecording(recording: RecordingInfo?) {
        _uiState.update { it.copy(selectedRecording = recording, exportState = ExportState.Idle) }
    }

    fun deleteRecording(recording: RecordingInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            recording.directory.deleteRecursively()
            val updated = _uiState.value.recordings.filter { it.directory != recording.directory }
            _uiState.update { it.copy(
                recordings = updated,
                selectedRecording = if (it.selectedRecording?.directory == recording.directory) null else it.selectedRecording
            )}
        }
    }

    fun exportAsZip(recording: RecordingInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(exportState = ExportState.InProgress(0f, "Preparing ZIP...")) }
            try {
                val dngFiles = recording.directory
                    .listFiles { f -> f.extension == "dng" }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                if (dngFiles.isEmpty()) {
                    _uiState.update { it.copy(exportState = ExportState.Failed("No DNG frames found")) }
                    return@launch
                }

                val outDir = File(
                    getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "RawDngCamera"
                ).also { it.mkdirs() }

                val zipFile = File(outDir, "${recording.name}.zip")

                ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
                    dngFiles.forEachIndexed { i, file ->
                        val progress = i.toFloat() / dngFiles.size
                        _uiState.update { it.copy(
                            exportState = ExportState.InProgress(progress, "Zipping frame ${i+1}/${dngFiles.size}")
                        )}
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { fis -> fis.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                _uiState.update { it.copy(exportState = ExportState.Done(zipFile.absolutePath)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportState = ExportState.Failed(e.message ?: "Export failed")) }
            }
        }
    }

    fun exportToDownloads(recording: RecordingInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(exportState = ExportState.InProgress(0f, "Copying DNG files...")) }
            try {
                val dngFiles = recording.directory
                    .listFiles { f -> f.extension == "dng" }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                if (dngFiles.isEmpty()) {
                    _uiState.update { it.copy(exportState = ExportState.Failed("No DNG frames found")) }
                    return@launch
                }

                val destDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    File(
                        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "RawDngCamera/${recording.name}"
                    ).also { it.mkdirs() }
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "RawDngCamera/${recording.name}").also { it.mkdirs() }
                }

                dngFiles.forEachIndexed { i, file ->
                    val progress = i.toFloat() / dngFiles.size
                    _uiState.update { it.copy(
                        exportState = ExportState.InProgress(progress, "Copying ${i+1}/${dngFiles.size}")
                    )}
                    file.copyTo(File(destDir, file.name), overwrite = true)
                }

                _uiState.update { it.copy(exportState = ExportState.Done(destDir.absolutePath)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportState = ExportState.Failed(e.message ?: "Export failed")) }
            }
        }
    }

    fun getShareIntent(recording: RecordingInfo): Intent {
        val context = getApplication<Application>()
        val dngFiles = recording.directory
            .listFiles { f -> f.extension == "dng" }
            ?.sortedBy { it.name }
            ?: emptyList()

        val uris = ArrayList<Uri>()
        dngFiles.take(10).forEach { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            uris.add(uri)
        }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/x-adobe-dng"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun resetExportState() {
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }
}
