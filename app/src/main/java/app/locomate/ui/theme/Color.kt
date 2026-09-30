package app.locomate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    background = Color(0xFF0E0E14),
    surface = Color(0xFF15151C),
    onBackground = LM.Ink,
    onSurface = LM.Ink,
)

@Composable
fun LocomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}
