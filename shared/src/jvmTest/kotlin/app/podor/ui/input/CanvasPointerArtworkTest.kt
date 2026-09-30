package app.podor.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.podor.resources.Res
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalComposeUiApi::class)
class CanvasPointerArtworkTest {
    @Test
    fun cursorArtworkFitsCompletelyAndKeepsItsColorsTransparencyAndTipAcrossDensities() =
        runBlocking(Dispatchers.Default) {
            val bytes = Res.readBytes("files/stylus-cursor.png")
            val source = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(96, source.width)
            assertEquals(96, source.height)
            val sourceBounds = opaqueBounds(source)
            val sourceColors = coloredPixels(source)
            assertTrue(sourceColors > 10, "The original colored stripe must remain in the resource")
            val cases =
                listOf(
                    IntSize(32, 32) to Offset(2.0986593f, 27.781536f),
                    IntSize(40, 40) to Offset(2.6233242f, 34.72692f),
                    IntSize(64, 64) to Offset(4.1973186f, 55.563072f),
                    IntSize(96, 96) to Offset(6.295978f, 83.344604f),
                    IntSize(96, 64) to Offset(20.197319f, 55.563072f),
                    IntSize(64, 97) to Offset(4.1973186f, 71.56307f),
                )
            for ((size, expectedTip) in cases) {
                val image = decodeCanvasPointerImage(bytes, size)
                assertEquals(size.width, image.width)
                assertEquals(size.height, image.height)
                assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
                val side = minOf(size.width, size.height)
                val scale = side / 96f
                val bounds = opaqueBounds(image)
                val left = (size.width - side) / 2
                val top = (size.height - side) / 2
                assertEquals(left + sourceBounds.x * scale, bounds.x.toFloat(), 2f, "$size left")
                assertEquals(top + sourceBounds.y * scale, bounds.y.toFloat(), 2f, "$size top")
                assertEquals(sourceBounds.width * scale, bounds.width.toFloat(), 2f, "$size width")
                assertEquals(
                    sourceBounds.height * scale,
                    bounds.height.toFloat(),
                    2f,
                    "$size height",
                )
                assertTrue(bounds.x > left && bounds.y > top, "$size artwork must keep its padding")
                assertTrue(
                    bounds.x + bounds.width < left + side && bounds.y + bounds.height < top + side
                )
                assertTrue(
                    coloredPixels(image) >= maxOf(1, (sourceColors * scale * scale * 0.3f).toInt())
                )
                val tip = canvasPointerHotspot(size)
                assertEquals(expectedTip.x, tip.x, 0.00001f, "$size tip x")
                assertEquals(expectedTip.y, tip.y, 0.00001f, "$size tip y")
                assertTrue(
                    (-1..1).any { dy ->
                        (-1..1).any { dx ->
                            image.getRGB(tip.x.roundToInt() + dx, tip.y.roundToInt() + dy) ushr 24 >
                                64
                        }
                    },
                    "$size hotspot must touch the actual pen tip",
                )
                if (size == IntSize(96, 96)) {
                    for (y in 0 until 96) for (x in 0 until 96) {
                        if (source.getRGB(x, y) ushr 24 == 255) {
                            assertEquals(
                                source.getRGB(x, y),
                                image.getRGB(x, y),
                                "Original color at $x,$y",
                            )
                        }
                    }
                }
                for (background in listOf(0xff171b22.toInt(), 0xfff2f4f7.toInt())) {
                    val bitmap = image.toComposeImageBitmap()
                    val scene =
                        ImageComposeScene(
                            size.width,
                            size.height,
                            density = Density(side / 32f),
                        ) {
                            Canvas(Modifier.fillMaxSize().background(Color(background))) {
                                drawImage(bitmap)
                            }
                        }
                    try {
                        scene.render().use { rendered ->
                            val pixels = rendered.toComposeImageBitmap().toPixelMap()
                            for (y in 0 until size.height) for (x in 0 until size.width) {
                                val foreground = image.getRGB(x, y)
                                val actual = pixels[x, y].toArgb()
                                assertEquals(255, actual ushr 24)
                                for (shift in listOf(0, 8, 16)) {
                                    val alpha = (foreground ushr 24) / 255f
                                    val expected =
                                        (((foreground ushr shift) and 255) * alpha +
                                                ((background ushr shift) and 255) * (1f - alpha))
                                            .roundToInt()
                                    assertTrue(abs(((actual ushr shift) and 255) - expected) <= 3)
                                }
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        }

    private fun opaqueBounds(image: BufferedImage): Rectangle {
        val points =
            (0 until image.height).flatMap { y ->
                (0 until image.width)
                    .filter { x -> image.getRGB(x, y) ushr 24 > 16 }
                    .map { x -> x to y }
            }
        assertTrue(points.isNotEmpty(), "The cursor artwork must not be empty")
        val minX = points.minOf { it.first }
        val minY = points.minOf { it.second }
        return Rectangle(
            minX,
            minY,
            points.maxOf { it.first } - minX + 1,
            points.maxOf { it.second } - minY + 1,
        )
    }

    private fun coloredPixels(image: BufferedImage): Int =
        (0 until image.height).sumOf { y ->
            (0 until image.width).count { x ->
                val pixel = image.getRGB(x, y)
                val channels =
                    listOf((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
                pixel ushr 24 > 128 && channels.max() - channels.min() > 48
            }
        }
}
