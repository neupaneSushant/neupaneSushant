package com.rawdng.camera

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.*
import com.rawdng.camera.ui.CameraScreen
import com.rawdng.camera.ui.ExportScreen
import com.rawdng.camera.ui.theme.AccentAmber
import com.rawdng.camera.ui.theme.DarkBg
import com.rawdng.camera.ui.theme.RawDngCameraTheme
import com.rawdng.camera.ui.theme.TextSecondary
import com.rawdng.camera.viewmodel.CameraViewModel
import com.rawdng.camera.viewmodel.ExportViewModel

class MainActivity : ComponentActivity() {

    private val cameraViewModel: CameraViewModel by viewModels()
    private val exportViewModel: ExportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RawDngCameraTheme {
                PermissionGate {
                    AppNavigation(
                        cameraViewModel = cameraViewModel,
                        exportViewModel = exportViewModel
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    )

    LaunchedEffect(Unit) {
        permissions.launchMultiplePermissionRequest()
    }

    when {
        permissions.allPermissionsGranted -> content()
        permissions.shouldShowRationale -> PermissionRationaleScreen(
            onRequest = { permissions.launchMultiplePermissionRequest() }
        )
        else -> PermissionDeniedScreen()
    }
}

@Composable
private fun AppNavigation(cameraViewModel: CameraViewModel, exportViewModel: ExportViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "camera") {
        composable("camera") {
            CameraScreen(
                viewModel = cameraViewModel,
                onNavigateToExport = { navController.navigate("export") }
            )
        }
        composable("export") {
            ExportScreen(
                viewModel = exportViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun PermissionRationaleScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Camera Permission Required",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "RAW DNG Camera needs access to your camera to capture unprocessed sensor data in DNG format.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
            ) {
                Text("Grant Permission", color = Color.Black)
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Permission Denied",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Please grant camera permission in system Settings > Apps > RAW DNG Camera > Permissions",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
