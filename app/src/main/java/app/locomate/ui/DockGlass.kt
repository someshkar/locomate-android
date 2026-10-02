package app.locomate.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val LocalDockBackdrop = staticCompositionLocalOf<DockBackdrop?> { null }

/** GPU drawing commands for the page beneath the dock, excluding the dock itself. */
internal class DockBackdrop(val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
    private var drawnFrames = 0
    var revision by mutableIntStateOf(0)
        private set

    fun pageDrawn() { revision = ++drawnFrames }
}

/** Replays the page and settled GL texture through a lens; controls are drawn afterward. */
@Composable
internal fun DockGlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.(Boolean) -> Unit) {
    val backdrop = LocalDockBackdrop.current
    val map = LocalMapGlass.current?.snapshot
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current.density
    val lens = remember(density) { DockLens(density) }
    val available = backdrop != null && backdrop.bounds.overlaps(bounds)
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        if (available) {
            val page = requireNotNull(backdrop)
            Canvas(Modifier.matchParentSize().graphicsLayer {
                renderEffect = lens.effect(size, page.revision)
            }) {
                drawRect(Color(0xFF060708))
                // SurfaceView's GL pixels are outside Compose's recorded drawing commands.
                if (map != null && map.bounds.overlaps(bounds)) {
                    drawImage(map.image,
                        dstOffset = IntOffset((map.bounds.left - bounds.left).roundToInt(), (map.bounds.top - bounds.top).roundToInt()),
                        dstSize = IntSize(map.bounds.width.roundToInt(), map.bounds.height.roundToInt()))
                }
                translate(page.bounds.left - bounds.left, page.bounds.top - bounds.top) {
                    drawLayer(page.layer)
                }
            }
        }
        content(available)
    }
}

/** A quiet inset rim. Kept outside the backdrop effect with the icons and captions. */
internal fun Modifier.dockRim(radius: Float) = drawWithCache {
    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.3f),
        Color.White.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.22f)))
    val stroke = 1.dp.toPx()
    onDrawWithContent {
        drawContent()
        drawRoundRect(rim, topLeft = Offset(stroke / 2, stroke / 2),
            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(radius.dp.toPx()), style = Stroke(stroke))
    }
}

private class DockLens(density: Float) {
    private val blur = RenderEffect.createBlurEffect(2f * density, 2f * density, Shader.TileMode.CLAMP)
    private val colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
    private val saturated = RenderEffect.createColorFilterEffect(
        colorFilter, blur)
    private val refraction = if (Build.VERSION.SDK_INT >= 33) DockRefraction(density) else null
    private var recordedSize = Size.Unspecified
    private var recordedRevision = -1
    private var recordedEffect = saturated.asComposeRenderEffect()

    fun effect(size: Size, revision: Int): androidx.compose.ui.graphics.RenderEffect {
        if (size != recordedSize || (Build.VERSION.SDK_INT < 33 && revision != recordedRevision)) {
            recordedSize = size
            recordedRevision = revision
            if (Build.VERSION.SDK_INT >= 33 && refraction != null) {
                // RenderEffect captures shader uniforms when created. Set size first,
                // and rebuild only when geometry changes, not on every draw frame.
                recordedEffect = RenderEffect.createChainEffect(refraction.effect(size), saturated).asComposeRenderEffect()
            } else {
                // Android 12 may cache the prior page inside a reused RenderEffect.
                recordedEffect = RenderEffect.createColorFilterEffect(colorFilter, blur).asComposeRenderEffect()
            }
        }
        return recordedEffect
    }
}

/** Smooth, static two-channel noise, following the reference's 11 dp displacement. */
@RequiresApi(33)
private class DockRefraction(density: Float) {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float density;
        uniform float2 lensSize;
        float2 hash(float2 p) {
            return fract(sin(float2(dot(p, float2(127.1, 311.7)),
                dot(p, float2(269.5, 183.3))) + 4.0) * 43758.5453);
        }
        float2 noise(float2 p) {
            float2 cell = floor(p);
            float2 t = fract(p);
            t = t * t * (3.0 - 2.0 * t);
            return mix(mix(hash(cell), hash(cell + float2(1.0, 0.0)), t.x),
                mix(hash(cell + float2(0.0, 1.0)), hash(cell + float2(1.0, 1.0)), t.x), t.y);
        }
        half4 main(float2 xy) {
            float2 displacement = (noise(xy / density * float2(0.02, 0.03)) - 0.5) * 11.0 * density;
            return scene.eval(clamp(xy + displacement, float2(0.0), max(lensSize - 1.0, float2(0.0))));
        }
    """.trimIndent()).apply { setFloatUniform("density", density) }
    fun effect(size: Size): RenderEffect {
        shader.setFloatUniform("lensSize", size.width, size.height)
        return RenderEffect.createRuntimeShaderEffect(shader, "scene")
    }
}
