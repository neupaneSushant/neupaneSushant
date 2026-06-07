package com.rawdng.camera.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rawdng.camera.camera.RecordingInfo
import com.rawdng.camera.ui.theme.*
import com.rawdng.camera.viewmodel.ExportState
import com.rawdng.camera.viewmodel.ExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadRecordings()
    }

    var showDeleteDialog by remember { mutableStateOf<RecordingInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Recordings", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimary
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadRecordings() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                }
            )
        },
        containerColor = DarkBg
    ) { padding ->

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentAmber)
            }
            return@Scaffold
        }

        if (state.recordings.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding)) {
            // Export state banner
            state.exportState.let { exportState ->
                when (exportState) {
                    is ExportState.InProgress -> ExportProgressBanner(exportState)
                    is ExportState.Done -> ExportDoneBanner(
                        path = exportState.outputPath,
                        onDismiss = { viewModel.resetExportState() }
                    )
                    is ExportState.Failed -> ExportErrorBanner(
                        error = exportState.error,
                        onDismiss = { viewModel.resetExportState() }
                    )
                    ExportState.Idle -> {}
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.recordings, key = { it.directory.absolutePath }) { recording ->
                    RecordingCard(
                        recording = recording,
                        selected = state.selectedRecording?.directory == recording.directory,
                        onSelect = { viewModel.selectRecording(recording) },
                        onExportZip = { viewModel.exportAsZip(recording) },
                        onExportFolder = { viewModel.exportToDownloads(recording) },
                        onShare = {
                            val intent = viewModel.getShareIntent(recording)
                            context.startActivity(Intent.createChooser(intent, "Share DNG frames"))
                        },
                        onDelete = { showDeleteDialog = recording }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { recording ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Recording?", color = TextPrimary) },
            text = {
                Text(
                    "\"${recording.name}\" will be permanently deleted (${recording.frameCount} frames, ${recording.formattedSize})",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRecording(recording)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RecordRed)
                ) { Text("Delete", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = AccentAmber)
                }
            },
            containerColor = SurfaceElevated
        )
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingInfo,
    selected: Boolean,
    onSelect: () -> Unit,
    onExportZip: () -> Unit,
    onExportFolder: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) AccentAmber else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recording.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        recording.formattedDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RecordRed.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RecordingStat(label = "FRAMES", value = recording.frameCount.toString())
                RecordingStat(label = "SIZE", value = recording.formattedSize)
                if (recording.droppedFrames > 0) {
                    RecordingStat(label = "DROPPED", value = recording.droppedFrames.toString(), valueColor = RecordRed)
                }
            }

            if (selected) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = ControlBorder)
                Spacer(Modifier.height(12.dp))

                Text("EXPORT OPTIONS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.FolderZip,
                        label = "ZIP Archive",
                        sublabel = "Downloads folder",
                        onClick = onExportZip
                    )
                    ExportButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Folder,
                        label = "DNG Folder",
                        sublabel = "Downloads folder",
                        onClick = onExportFolder
                    )
                    ExportButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Share,
                        label = "Share",
                        sublabel = "First 10 frames",
                        onClick = onShare
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingStat(label: String, value: String, valueColor: Color = TextPrimary) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, color = valueColor)
    }
}

@Composable
private fun ExportButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, ControlBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = AccentAmber, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        Text(sublabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun ExportProgressBanner(state: ExportState.InProgress) {
    Surface(color = SurfaceElevated, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = AccentAmber
            )
        }
    }
}

@Composable
private fun ExportDoneBanner(path: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFF1B5E20), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF69F0AE))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Export Complete", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(path, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 2)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ExportErrorBanner(error: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFF7F0000), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("No Recordings Yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Go back and shoot some RAW DNG video",
                style = MaterialTheme.typography.bodyMedium,
                color = TextDisabled
            )
        }
    }
}
