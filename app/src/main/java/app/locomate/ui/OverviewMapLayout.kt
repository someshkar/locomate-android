package app.locomate.ui

import app.locomate.ui.theme.LM
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The native map fills the backdrop; its usable viewport ends at the reading sheet. */
@Composable
internal fun OverviewMapLayout(
    bottomInset: Dp,
    keyboardVisible: Boolean = false,
    glassTint: Brush = Brush.verticalGradient(
        0f to LM.Elevated.copy(alpha = 0.5f),
        0.2f to LM.Elevated.copy(alpha = 0.88f),
        1f to LM.Elevated.copy(alpha = 0.98f)),
    map: @Composable (Modifier, Rect) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(LM.Ground)) {
        val density = LocalDensity.current
        val exposedHeight = if (keyboardVisible) 0.dp else if (density.fontScale >= 1.5f) 96.dp
            else ((maxHeight - bottomInset) * 0.24f).coerceIn(128.dp, 190.dp)
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val viewport = with(density) {
            Rect(0f, statusTop, maxWidth.toPx(), statusTop + exposedHeight.toPx())
        }
        // A zero-height native view releases its decorative texture while typing,
        // but stays mounted so closing the keyboard preserves the map camera.
        map(if (keyboardVisible) Modifier.fillMaxWidth().height(0.dp) else Modifier.fillMaxSize(), viewport)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(exposedHeight))
            Surface(color = Color.Transparent,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                border = BorderStroke(1.dp, LM.Ink.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth().weight(1f)) {
                MapGlassSurface { glass ->
                    Box(Modifier.fillMaxSize().background(if (glass) glassTint else Brush.verticalGradient(
                        listOf(LM.Elevated.copy(alpha = 0.941f), LM.Ground))).padding(bottom = bottomInset)) {
                        content()
                    }
                }
            }
        }
    }
}
