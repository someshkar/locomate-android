package app.locomate.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Dark design tokens from the approved Doop canvas guide.
object LM {
    val Ground   = Color(0xFF060708)
    val Elevated = Color(0xFF17171D)
    val Raised   = Color(0xFF202025)
    // Compose does not blur the MapLibre view beneath this sheet; use a stronger scrim.
    val Glass    = Color(0xFA101116)
    val Accent   = Color(0xFF009DFA)
    val AccentHi = Color(0xFF8FCBFF)
    val OnAccent = Color(0xFF0C0D10)
    val Route    = Color(0xFF5FAEF5)
    val Success  = Color(0xFF37C982)
    val Warn     = Color(0xFFFFB84D)
    val Replay   = Color(0xFF9C8BFF)
    val Error    = Color(0xFFFF6A61)
    val Ink      = Color(0xFFF5F5F7)
    val Ink2     = Color(0xFFADAEB5)
    val Ink3     = Color(0xFF98A0AC)
    val Ink4     = Color(0xFF767D89)
    val Hairline = Color.White.copy(alpha = 0.10f)
    val SheetEdge = Color.White.copy(alpha = 0.12f)

    // Springs — same response/damping language as iOS
    val springUI   = spring<Float>(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioLowBouncy)
    val springSnap = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
    val springBouncy = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)

    val RadiusSheet = 28.dp
    val RadiusCard = 26.dp
    val RadiusNav = 36.dp
}
