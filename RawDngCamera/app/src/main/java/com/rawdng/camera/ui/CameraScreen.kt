package com.rawdng.camera.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rawdng.camera.ui.theme.*
import com.rawdng.camera.viewmodel.*

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onNavigateToExport: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var previewSurfaceState by remember { mutableStateOf<Surface?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val surface = Surface(st)
                            previewSurfaceState = surface
                            textureViewRef = this@apply
                            viewModel.initRecorder()
                            val caps = viewModel.cameraController.discoverCameras()
                            val mainCam = caps.firstOrNull { it.supportedRaw }
                                ?: caps.firstOrNull()
                            if (mainCam != null) {
                                viewModel.openCamera(mainCam.cameraId, surface)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            previewSurfaceState = null
                            viewModel.cameraController.close()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top HUD
        TopHud(
            state = state,
            onNavigateToExport = onNavigateToExport
        )

        // Recording indicator overlay
        if (state.isRecording) {
            RecordingOverlay(state = state)
        }

        // Camera not ready / error overlay
        if (!state.cameraReady && state.error == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentAmber)
            }
        }

        state.error?.let { err ->
            ErrorBanner(message = err, onDismiss = { viewModel.dismissError() })
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            // Settings panel (expandable)
            AnimatedVisibility(
                visible = state.showSettings,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SettingsPanel(
                    state = state,
                    onIsoChange = { idx -> previewSurfaceState?.let { viewModel.setIso(idx, it) } },
                    onShutterChange = { idx -> previewSurfaceState?.let { viewModel.setShutter(idx, it) } },
                    onEvChange = { steps -> previewSurfaceState?.let { viewModel.setEv(steps, it) } },
                    onResolutionChange = { idx -> previewSurfaceState?.let { viewModel.setResolution(idx, it) } }
                )
            }

            // Control bar
            BottomControlBar(
                state = state,
                onRecordToggle = {
                    previewSurfaceState?.let { surface ->
                        if (state.isRecording) viewModel.stopRecording(surface)
                        else viewModel.startRecording(surface)
                    }
                },
                onSettingsToggle = { viewModel.toggleSettings() }
            )
        }
    }
}

@Composable
private fun TopHud(state: CameraUiState, onNavigateToExport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RAW badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = AccentAmber
        ) {
            Text(
                text = "RAW DNG",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black
            )
        }

        // Center: resolution indicator
        state.selectedSize?.let { size ->
            Text(
                text = "${size.width}×${size.height}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        // Export button
        IconButton(
            onClick = onNavigateToExport,
            modifier = Modifier
                .size(40.dp)
                .background(SurfaceElevated, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = "Export",
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RecordingOverlay(state: CameraUiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(RecordRed.copy(alpha = alpha), CircleShape)
            )
            Text(
                text = "REC  ${state.recordingTimestamp}",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${state.frameCount} frames  |  ${state.droppedFrames} dropped",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun SettingsPanel(
    state: CameraUiState,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onEvChange: (Int) -> Unit,
    onResolutionChange: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark.copy(alpha = 0.95f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text("CAMERA SETTINGS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(12.dp))

            // ISO
            SettingRow(label = "ISO", currentValue = state.isoLabel) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ISO_OPTIONS) { idx, opt ->
                        OptionChip(
                            label = opt.label,
                            selected = idx == state.isoIndex,
                            onClick = { onIsoChange(idx) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Shutter Speed
            SettingRow(label = "SHUTTER", currentValue = state.shutterLabel) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(SHUTTER_OPTIONS) { idx, opt ->
                        OptionChip(
                            label = opt.label,
                            selected = idx == state.shutterIndex,
                            onClick = { onShutterChange(idx) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // EV
            SettingRow(label = "EV", currentValue = state.evLabel) {
                val evSteps = (-9..9).toList()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(evSteps) { _, steps ->
                        val label = when {
                            steps > 0 -> "+${"%.1f".format(steps * CameraUiState.EV_STEP)}"
                            steps < 0 -> "${"%.1f".format(steps * CameraUiState.EV_STEP)}"
                            else -> "0"
                        }
                        OptionChip(
                            label = label,
                            selected = steps == state.evSteps,
                            onClick = { onEvChange(steps) }
                        )
                    }
                }
            }

            // Resolution
            if (state.availableRawSizes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SettingRow(
                    label = "RESOLUTION",
                    currentValue = state.selectedSize?.let { "${it.width}×${it.height}" } ?: ""
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(state.availableRawSizes) { idx, size ->
                            val mp = (size.width * size.height) / 1_000_000f
                            OptionChip(
                                label = "${"%.0f".format(mp)}MP",
                                sublabel = "${size.width}×${size.height}",
                                selected = idx == state.selectedSizeIndex,
                                onClick = { onResolutionChange(idx) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingRow(label: String, currentValue: String, content: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(currentValue, style = MaterialTheme.typography.labelMedium, color = AccentAmber)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun OptionChip(
    label: String,
    sublabel: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (selected) AccentAmber else SurfaceElevated
    val textColor = if (selected) Color.Black else TextSecondary

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else ControlBorder,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
        sublabel?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun BottomControlBar(
    state: CameraUiState,
    onRecordToggle: () -> Unit,
    onSettingsToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.85f))
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Quick ISO display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ISO", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(state.iso.toString(), style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        }

        // Record button
        RecordButton(isRecording = state.isRecording, enabled = state.cameraReady, onClick = onRecordToggle)

        // Settings toggle
        Column(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (state.showSettings) AccentAmber else SurfaceElevated)
                .clickable(onClick = onSettingsToggle),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Settings",
                tint = if (state.showSettings) Color.Black else TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rec_scale"
    )

    Box(
        modifier = Modifier
            .size((72 * scale).dp)
            .clip(CircleShape)
            .background(if (!enabled) TextDisabled else if (isRecording) RecordRed else Color.White)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = RecordRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Camera Error", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                ) {
                    Text("Dismiss", color = Color.Black)
                }
            }
        }
    }
}
