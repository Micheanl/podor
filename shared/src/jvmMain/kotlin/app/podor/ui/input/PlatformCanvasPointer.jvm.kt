package app.podor.ui.input

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntSize
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

internal actual fun createCanvasPointerIcon(image: ByteArray, sizePx: Int): PointerIcon? {
    if (GraphicsEnvironment.isHeadless()) return null
    val toolkit = Toolkit.getDefaultToolkit()
    val supported = toolkit.getBestCursorSize(sizePx, sizePx)
    if (supported.width == 0 || supported.height == 0) return null
    val size = IntSize(supported.width, supported.height)
    val hotspot = canvasPointerHotspot(size)
    return PointerIcon(
        toolkit.createCustomCursor(
            decodeCanvasPointerImage(image, size),
            Point(hotspot.x.roundToInt(), hotspot.y.roundToInt()),
            "Podor Stylus",
        )
    )
}

internal fun decodeCanvasPointerImage(bytes: ByteArray, size: IntSize): BufferedImage {
    val source = ImageIO.read(ByteArrayInputStream(bytes))
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        val side = min(size.width, size.height)
        graphics.drawImage(
            source,
            (size.width - side) / 2,
            (size.height - side) / 2,
            side,
            side,
            null,
        )
    } finally {
        graphics.dispose()
    }
    return image
}
