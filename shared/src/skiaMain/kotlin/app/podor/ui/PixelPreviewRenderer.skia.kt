package app.podor.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import app.podor.domain.GradientLine
import app.podor.domain.GradientSettings
import app.podor.domain.GradientShape
import app.podor.domain.LayerTransform
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.impl.use

private val tileEffect by lazy { RuntimeEffect.makeForShader(pixelPreviewTileShader) }
private val gradientEffect by lazy { RuntimeEffect.makeForShader(pixelPreviewGradientShader) }

internal actual fun createPixelPreviewRenderer(): PixelPreviewRenderer? = SkiaPixelPreviewRenderer()

private class SkiaPixelPreviewRenderer : PixelPreviewRenderer {
    private val tile = RuntimeShaderBuilder(tileEffect)
    private val gradient = RuntimeShaderBuilder(gradientEffect)
    private val white = Shader.makeColor(-1)
    private val images = mutableMapOf<ImageBitmap, Shader>()
    private val paint = Paint().apply { isAntiAlias = false }

    private fun imageShader(bitmap: ImageBitmap) =
        images.getOrPut(bitmap) { bitmap.asSkiaBitmap().makeShader() }

    private fun draw(
        scope: DrawScope,
        builder: RuntimeShaderBuilder,
        bounds: Rect,
        mode: BlendMode,
    ) {
        builder.makeShader().use { shader ->
            paint.shader = shader
            paint.blendMode = mode
            scope.drawIntoCanvas {
                it.skiaCanvas.drawRect(
                    org.jetbrains.skia.Rect.makeLTRB(
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                    ),
                    paint,
                )
            }
            paint.shader = null
        }
    }

    override fun DrawScope.drawTile(
        image: ImageBitmap,
        bounds: Rect,
        imageOrigin: Offset,
        offset: Offset,
        transform: LayerTransform,
        source: Rect,
        replace: Boolean,
    ) {
        tile.child("tile", imageShader(image))
        tile.uniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
        tile.uniform("imageOrigin", imageOrigin.x, imageOrigin.y)
        tile.uniform("sourceCenter", source.center.x, source.center.y)
        val center = transform.center(source) + offset
        tile.uniform("destinationCenter", center.x, center.y)
        tile.uniform(
            "inverseScale",
            source.width / transform.width * if (transform.flipX) -1f else 1f,
            source.height / transform.height * if (transform.flipY) -1f else 1f,
        )
        val rotation = previewRotation(transform.angle)
        tile.uniform("rotation", rotation.x, rotation.y)
        val destination = previewTileDestination(bounds, transform, source).translate(offset)
        if (replace) {
            tile.uniform("coverageOnly", 1f)
            draw(this, tile, destination, BlendMode.DST_OUT)
        }
        tile.uniform("coverageOnly", 0f)
        draw(this, tile, destination, BlendMode.SRC_OVER)
    }

    override fun DrawScope.drawGradient(
        settings: GradientSettings,
        line: GradientLine,
        bounds: Rect,
        mask: ImageBitmap?,
        maskOrigin: Offset,
        alphaLocked: Boolean,
    ) {
        gradient.child("selection", mask?.let { imageShader(it) } ?: white)
        gradient.uniform("hasSelection", if (mask == null) 0f else 1f)
        gradient.uniform("selectionOrigin", maskOrigin.x, maskOrigin.y)
        gradient.uniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
        gradient.uniform("start", line.start.x, line.start.y)
        gradient.uniform("end", line.end.x, line.end.y)
        fun color(name: String, value: Long) {
            val color = Color(value)
            gradient.uniform(name, color.red, color.green, color.blue, color.alpha)
        }
        color("fromColor", settings.startColor)
        color("toColor", settings.endColor)
        gradient.uniform("opacity", settings.opacity)
        gradient.uniform("radial", if (settings.shape == GradientShape.Radial) 1f else 0f)
        draw(this, gradient, bounds, if (alphaLocked) BlendMode.SRC_ATOP else BlendMode.SRC_OVER)
    }

    override fun close() {
        paint.close()
        tile.close()
        gradient.close()
        white.close()
        images.values.forEach { it.close() }
        images.clear()
    }
}
