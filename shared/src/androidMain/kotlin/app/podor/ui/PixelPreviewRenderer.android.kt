package app.podor.ui

import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.podor.domain.GradientLine
import app.podor.domain.GradientSettings
import app.podor.domain.GradientShape
import app.podor.domain.LayerTransform

internal actual fun createPixelPreviewRenderer(): PixelPreviewRenderer? =
    if (Build.VERSION.SDK_INT >= 33) AndroidPixelPreviewRenderer() else null

@RequiresApi(33)
private class AndroidPixelPreviewRenderer : PixelPreviewRenderer {
    private val tile = RuntimeShader(pixelPreviewTileShader)
    private val gradient = RuntimeShader(pixelPreviewGradientShader)
    private val white = RuntimeShader("half4 main(float2 p) { return half4(1.0); }")
    private val images = mutableMapOf<ImageBitmap, BitmapShader>()
    private val paint = Paint()

    private fun imageShader(bitmap: ImageBitmap) =
        images.getOrPut(bitmap) {
            BitmapShader(bitmap.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply {
                    setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
                }
        }

    private fun draw(
        scope: DrawScope,
        shader: RuntimeShader,
        bounds: Rect,
        mode: android.graphics.BlendMode,
    ) {
        paint.shader = shader
        paint.blendMode = mode
        scope.drawIntoCanvas {
            it.nativeCanvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
        }
        paint.shader = null
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
        tile.setInputShader("tile", imageShader(image))
        tile.setFloatUniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
        tile.setFloatUniform("imageOrigin", imageOrigin.x, imageOrigin.y)
        tile.setFloatUniform("sourceCenter", source.center.x, source.center.y)
        val center = transform.center(source) + offset
        tile.setFloatUniform("destinationCenter", center.x, center.y)
        tile.setFloatUniform(
            "inverseScale",
            source.width / transform.width * if (transform.flipX) -1f else 1f,
            source.height / transform.height * if (transform.flipY) -1f else 1f,
        )
        val rotation = previewRotation(transform.angle)
        tile.setFloatUniform("rotation", rotation.x, rotation.y)
        val destination = previewTileDestination(bounds, transform, source).translate(offset)
        if (replace) {
            tile.setFloatUniform("coverageOnly", 1f)
            draw(this, tile, destination, android.graphics.BlendMode.DST_OUT)
        }
        tile.setFloatUniform("coverageOnly", 0f)
        draw(this, tile, destination, android.graphics.BlendMode.SRC_OVER)
    }

    override fun DrawScope.drawGradient(
        settings: GradientSettings,
        line: GradientLine,
        bounds: Rect,
        mask: ImageBitmap?,
        maskOrigin: Offset,
        alphaLocked: Boolean,
    ) {
        gradient.setInputShader("selection", mask?.let { imageShader(it) } ?: white)
        gradient.setFloatUniform("hasSelection", if (mask == null) 0f else 1f)
        gradient.setFloatUniform("selectionOrigin", maskOrigin.x, maskOrigin.y)
        gradient.setFloatUniform("bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
        gradient.setFloatUniform("start", line.start.x, line.start.y)
        gradient.setFloatUniform("end", line.end.x, line.end.y)
        fun color(name: String, value: Long) {
            val color = Color(value)
            gradient.setFloatUniform(name, color.red, color.green, color.blue, color.alpha)
        }
        color("fromColor", settings.startColor)
        color("toColor", settings.endColor)
        gradient.setFloatUniform("opacity", settings.opacity)
        gradient.setFloatUniform("radial", if (settings.shape == GradientShape.Radial) 1f else 0f)
        draw(
            this,
            gradient,
            bounds,
            if (alphaLocked) android.graphics.BlendMode.SRC_ATOP
            else android.graphics.BlendMode.SRC_OVER,
        )
    }

    override fun close() {
        paint.shader = null
        images.clear()
    }
}
