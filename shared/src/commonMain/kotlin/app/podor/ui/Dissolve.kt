package app.podor.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntSize
import app.podor.engine.rgbaBitmap
import kotlin.math.roundToInt
import kotlin.random.Random

fun createDissolveTexture(): ImageBitmap {
    val size = StudioMotion.dissolveTextureSize
    val random = Random(71)
    val bytes = ByteArray(size * size * 4)
    for (y in 0 until size) for (x in 0 until size) {
        val noise = random.nextFloat() * 0.8f + (1f - x.toFloat() / size) * 0.2f
        bytes[(y * size + x) * 4 + 3] = (noise * 255).toInt().toByte()
    }
    return rgbaBitmap(bytes, 0, size)
}

fun Modifier.dissolve(texture: ImageBitmap, progress: () -> Float): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}
    .drawWithCache {
        val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val sharpness = 1f / StudioMotion.dissolveSoftness
        onDrawWithContent {
            drawContent()
            val amount = progress().coerceIn(0f, 1f)
            if (amount > 0f) {
                val matrix = ColorMatrix(FloatArray(20))
                matrix[3, 3] = -sharpness
                matrix[3, 4] = amount * (1f + StudioMotion.dissolveSoftness) * sharpness * 255f
                drawImage(
                    texture,
                    dstSize = destination,
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    filterQuality = FilterQuality.Low,
                    blendMode = BlendMode.DstOut,
                )
            }
        }
    }
