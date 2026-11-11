package app.xraymirror.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NavyBlack = Color(0xFF050A12)
private val NavyPanel = Color(0xFF0A1522)
private val ChipBg = Color(0xFF122838)
private val CyanAccent = Color(0xFF00C2FF)
private val TextPrimary = Color(0xFFE8F6FF)
private val TextMuted = Color(0xFF8FB8CC)

private val XRayColorScheme = darkColorScheme(
    primary = CyanAccent,
    secondary = CyanAccent,
    background = NavyBlack,
    surface = NavyPanel,
    surfaceVariant = ChipBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted
)

@Composable
fun XRayMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XRayColorScheme,
        content = content
    )
}
