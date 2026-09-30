package app.podor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.podor.domain.GradientLine
import app.podor.domain.GradientSettings
import app.podor.domain.LayerTransform
import kotlin.math.*

internal interface PixelPreviewRenderer {
    fun DrawScope.drawTile(
        image: ImageBitmap,
        bounds: Rect,
        imageOrigin: Offset,
        offset: Offset,
        transform: LayerTransform,
        source: Rect,
        replace: Boolean,
    )

    fun DrawScope.drawGradient(
        settings: GradientSettings,
        line: GradientLine,
        bounds: Rect,
        mask: ImageBitmap?,
        maskOrigin: Offset,
        alphaLocked: Boolean,
    )

    fun close()
}

internal expect fun createPixelPreviewRenderer(): PixelPreviewRenderer?

@Composable
internal fun rememberPixelPreviewRenderer(key: Any): PixelPreviewRenderer? {
    val renderer = remember(key) { createPixelPreviewRenderer() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return renderer
}

internal fun previewRotation(angle: Float): Offset {
    if (angle % 90f == 0f) {
        return when (((angle.toInt() / 90) % 4 + 4) % 4) {
            0 -> Offset(1f, 0f)
            1 -> Offset(0f, 1f)
            2 -> Offset(-1f, 0f)
            else -> Offset(0f, -1f)
        }
    }
    val radians = angle * PI.toFloat() / 180f
    return Offset(cos(radians), sin(radians))
}

internal fun previewTileDestination(bounds: Rect, value: LayerTransform, source: Rect): Rect {
    val rotation = previewRotation(value.angle)
    val center = value.center(source)
    val points =
        listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight).map {
            val x =
                (it.x - source.center.x) * value.width / source.width * if (value.flipX) -1f else 1f
            val y =
                (it.y - source.center.y) * value.height / source.height *
                    if (value.flipY) -1f else 1f
            center + Offset(x * rotation.x - y * rotation.y, x * rotation.y + y * rotation.x)
        }
    return Rect(
        floor(points.minOf { it.x }),
        floor(points.minOf { it.y }),
        ceil(points.maxOf { it.x }),
        ceil(points.maxOf { it.y }),
    )
}

internal const val pixelPreviewTileShader =
    """
uniform shader tile;
uniform float4 bounds;
uniform float2 imageOrigin;
uniform float2 sourceCenter;
uniform float2 destinationCenter;
uniform float2 inverseScale;
uniform float2 rotation;
uniform float coverageOnly;
half4 main(float2 p) {
    float2 d = floor(p) + 0.5 - destinationCenter;
    float2 s = float2(d.x * rotation.x + d.y * rotation.y,
        -d.x * rotation.y + d.y * rotation.x) * inverseScale + sourceCenter;
    if (s.x < bounds.x || s.y < bounds.y || s.x >= bounds.z || s.y >= bounds.w) {
        return half4(0.0);
    }
    if (coverageOnly > 0.5) return half4(0.0, 0.0, 0.0, 1.0);
    return tile.eval(floor(s) + 0.5 - imageOrigin);
}
"""

internal const val pixelPreviewGradientShader =
    """
uniform shader selection;
uniform float2 selectionOrigin;
uniform float hasSelection;
uniform float4 bounds;
uniform float2 start;
uniform float2 end;
uniform float4 fromColor;
uniform float4 toColor;
uniform float opacity;
uniform float radial;
half4 main(float2 p) {
    p = floor(p) + 0.5;
    if (p.x < bounds.x || p.y < bounds.y || p.x >= bounds.z || p.y >= bounds.w) {
        return half4(0.0);
    }
    float2 d = end - start;
    float t = radial > 0.5 ? length(p - start) / length(d) : dot(p - start, d) / dot(d, d);
    float4 color = mix(fromColor, toColor, clamp(t, 0.0, 1.0));
    float coverage = hasSelection > 0.5 ? selection.eval(p - selectionOrigin).a : 1.0;
    float alpha = color.a * opacity * coverage;
    return half4(color.rgb * alpha, alpha);
}
"""
