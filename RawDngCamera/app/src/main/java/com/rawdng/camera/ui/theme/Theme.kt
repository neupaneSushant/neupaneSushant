package com.rawdng.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = AccentAmber,
    onPrimary = Color.Black,
    secondary = AccentAmberLight,
    onSecondary = Color.Black,
    background = DarkBg,
    surface = SurfaceDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = RecordRed,
    onError = Color.White
)

@Composable
fun RawDngCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content
    )
}
