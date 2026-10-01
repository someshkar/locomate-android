package app.locomate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.locomate.R

private val Dark = darkColorScheme(
    background = Color(0xFF0E0E14),
    surface = Color(0xFF15151C),
    onBackground = LM.Ink,
    onSurface = LM.Ink,
)

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val Type = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = SpaceGrotesk),
        titleMedium = base.titleMedium.copy(fontFamily = SpaceGrotesk),
        titleSmall = base.titleSmall.copy(fontFamily = SpaceGrotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = SpaceGrotesk),
        bodyMedium = base.bodyMedium.copy(fontFamily = SpaceGrotesk),
        bodySmall = base.bodySmall.copy(fontFamily = SpaceGrotesk),
        labelLarge = base.labelLarge.copy(fontFamily = SpaceGrotesk),
        labelMedium = base.labelMedium.copy(fontFamily = SpaceGrotesk),
        labelSmall = base.labelSmall.copy(fontFamily = SpaceGrotesk),
    )
}

@Composable
fun LocomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Type, content = content)
}
