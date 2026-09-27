package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ArtworkPreview(
    bitmap: ImageBitmap?,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    transparent: Boolean = true,
) {
    Canvas(modifier.clipToBounds()) {
        val scale = minOf(size.width / width, size.height / height)
        val target = Size(width * scale, height * scale)
        val origin = Offset((size.width - target.width) / 2, (size.height - target.height) / 2)
        clipRect(origin.x, origin.y, origin.x + target.width, origin.y + target.height) {
            drawRect(if (transparent) StudioTheme.checkerLight else Color.White, origin, target)
            if (transparent) {
                val cell = 7.dp.toPx()
                for (y in 0..(target.height / cell).toInt()) for (x in
                    0..(target.width / cell).toInt()) {
                    if ((x + y) % 2 == 0)
                        drawRect(
                            StudioTheme.checkerDark,
                            origin + Offset(x * cell, y * cell),
                            Size(cell, cell),
                        )
                }
            }
            bitmap?.let {
                val sourceWidth = (width * it.width / maxOf(width, height)).coerceAtLeast(1)
                val sourceHeight = (height * it.height / maxOf(width, height)).coerceAtLeast(1)
                drawImage(
                    it,
                    srcOffset =
                        IntOffset((it.width - sourceWidth) / 2, (it.height - sourceHeight) / 2),
                    srcSize = IntSize(sourceWidth, sourceHeight),
                    dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize =
                        IntSize(
                            target.width.roundToInt().coerceAtLeast(1),
                            target.height.roundToInt().coerceAtLeast(1),
                        ),
                )
            }
        }
    }
}
