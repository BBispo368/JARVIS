package dev.breno.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JarvisColors {
    val Background = Color(0xFF05080F)
    val Surface = Color(0xFF0D1422)
    val Cyan = Color(0xFF00E5FF)
    val CyanSoft = Color(0xFF26C6DA)
    val Amber = Color(0xFFFFB300)
    val Purple = Color(0xFFB388FF)
    val UserBubble = Color(0xFF0F3A47)
    val JarvisBubble = Color(0xFF151D2E)
    val TextPrimary = Color(0xFFE3F2F7)
    val TextMuted = Color(0xFF7F95A8)
    val Error = Color(0xFFFF6E6E)
}

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisColors.Cyan,
    onPrimary = JarvisColors.Background,
    secondary = JarvisColors.Purple,
    tertiary = JarvisColors.Amber,
    background = JarvisColors.Background,
    onBackground = JarvisColors.TextPrimary,
    surface = JarvisColors.Surface,
    onSurface = JarvisColors.TextPrimary,
    error = JarvisColors.Error,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColorScheme, content = content)
}
