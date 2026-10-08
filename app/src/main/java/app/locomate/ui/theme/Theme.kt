package app.locomate.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

// Dark design tokens from the approved Doop canvas guide.
object LM {
    val Ground: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF060708) else Color(0xFFF5F7FB)
    val Elevated: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF17171D) else Color(0xFFFFFFFF)
    val Raised: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF202025) else Color(0xFFE8ECF3)
    // Fallback for surfaces outside the current native map snapshot.
    val Glass: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFA101116) else Color(0xFAFFFFFF)
    val Accent: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF009DFA) else Color(0xFF0077C8)
    val AccentHi: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF8FCBFF) else Color(0xFF005FA5)
    val OnAccent: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF0C0D10) else Color(0xFFFFFFFF)
    val Route: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF5FAEF5) else Color(0xFF147EC1)
    val Success: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF37C982) else Color(0xFF147848)
    val Warn: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFFFB84D) else Color(0xFF875300)
    val Replay: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF9C8BFF) else Color(0xFF6550B0)
    val Error: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFFF6A61) else Color(0xFFB3261E)
    val Ink: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFF5F5F7) else Color(0xFF152131)
    val Ink2: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFADAEB5) else Color(0xFF455469)
    val Ink3: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF98A0AC) else Color(0xFF506075)
    val Ink4: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF767D89) else Color(0xFF627187)
    val Hairline: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color.White.copy(alpha = 0.10f) else Color(0x1F152131)
    val SheetEdge: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color.White.copy(alpha = 0.12f) else Color(0x24152131)
    // Solid lens fallback when no current native map texture covers the dock.
    val DockTop: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xF212131B) else Color(0xF2FFFFFF)
    val DockBottom: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xE812131B) else Color(0xE8FFFFFF)
    val DockLabel: Color @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFC9CED9) else Color(0xFF33465D)

    // Springs — same response/damping language as iOS
    val springUI   = spring<Float>(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioLowBouncy)
    val springSnap = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
    val springBouncy = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)

    val RadiusSheet = 28.dp
    val RadiusCard = 26.dp
    val RadiusNav = 36.dp
}
