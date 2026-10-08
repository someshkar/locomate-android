package app.locomate.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.pow

/**
 * Motion language shared with iOS `Motion.swift`: springs for anything the user
 * can interrupt, short timings for fades. iOS expresses springs as
 * (response, dampingFraction); [fromResponse] converts so both platforms feel alike.
 */
object Motion {
    /** Sheets: critically damped glide to the detent. */
    val sheet: SpringSpec<Float> = fromResponse(0.36f, 0.86f)
    /** Card presses and entrances: firm, settles fast. */
    val card: SpringSpec<Float> = fromResponse(0.28f, 0.78f)
    /** Toggles, pills, selection indicators: quick, no wobble. */
    val selection: SpringSpec<Float> = fromResponse(0.26f, 0.76f)
    /** Large surfaces such as the dock: slow, heavy, smooth. */
    val dockSpring: SpringSpec<IntOffset> = spring(
        dampingRatio = 0.88f, stiffness = stiffness(0.42f), visibilityThreshold = IntOffset(1, 1))

    const val FAST_MS = 180
    const val NORMAL_MS = 260

    fun <T> fadeFast(): FiniteAnimationSpec<T> = tween(FAST_MS)
    fun <T> fadeNormal(): FiniteAnimationSpec<T> = tween(NORMAL_MS)

    /** Delay for the n-th item of a staggered entrance; later items appear together. */
    fun staggerMillis(index: Int, stepMillis: Int = 45): Int = if (index < 8) index * stepMillis else 0

    private fun stiffness(response: Float): Float = (2 * PI / response).pow(2).toFloat()
    private fun fromResponse(response: Float, damping: Float): SpringSpec<Float> =
        spring(dampingRatio = damping, stiffness = stiffness(response))
}

/** True when the system asks for reduced motion (animator duration scale 0). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Substitute an instant change for motion when the system asks for it. */
@Composable
fun <T> motionSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduceMotion()) snap() else spec

/**
 * The iOS `ScaleButton` press response: content dips to [pressedScale] while
 * held and springs back on release. Pass the same [interactionSource] given to
 * the clickable so the press state is shared.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        motionSpec(if (pressed) spring(stiffness = Spring.StiffnessHigh) else Motion.card),
        label = "pressScale")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
