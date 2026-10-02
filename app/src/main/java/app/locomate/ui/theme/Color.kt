package app.locomate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.locomate.R

private val Dark = darkColorScheme(
    primary = LM.Accent,
    onPrimary = LM.OnAccent,
    secondary = LM.Route,
    onSecondary = LM.OnAccent,
    tertiary = LM.Replay,
    background = LM.Ground,
    surface = LM.Elevated,
    surfaceVariant = LM.Raised,
    onBackground = LM.Ink,
    onSurface = LM.Ink,
    onSurfaceVariant = LM.Ink2,
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val Type = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Inter),
        displayMedium = base.displayMedium.copy(fontFamily = Inter),
        displaySmall = base.displaySmall.copy(fontFamily = Inter),
        headlineLarge = base.headlineLarge.copy(fontFamily = Inter),
        headlineMedium = base.headlineMedium.copy(fontFamily = Inter),
        headlineSmall = base.headlineSmall.copy(fontFamily = Inter),
        titleLarge = base.titleLarge.copy(fontFamily = Inter),
        titleMedium = base.titleMedium.copy(fontFamily = Inter),
        titleSmall = base.titleSmall.copy(fontFamily = Inter),
        bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
        bodySmall = base.bodySmall.copy(fontFamily = Inter),
        labelLarge = base.labelLarge.copy(fontFamily = Inter),
        labelMedium = base.labelMedium.copy(fontFamily = Inter),
        labelSmall = base.labelSmall.copy(fontFamily = Inter),
    )
}

@Composable
fun LocomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Type, content = content)
}
