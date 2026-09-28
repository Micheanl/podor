package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalDensity
import app.podor.presentation.StudioController

@Composable
fun CachedSelectionOverlay(
    controller: StudioController,
    viewSize: Size,
    modifier: Modifier = Modifier,
) {
    val outline = controller.selectionOutline ?: return
    val density = LocalDensity.current
    val scale = controller.viewport.scale(viewSize, controller.document).coerceAtLeast(0.01f)
    val dash =
        remember(density, scale) {
            val length = with(density) { StudioTheme.combinedSelectionDash.toPx() } / scale
            PathEffect.dashPathEffect(floatArrayOf(length, length))
        }
    val paint = remember {
        Paint().apply {
            filterQuality = FilterQuality.Low
            colorFilter = ColorFilter.tint(StudioTheme.combinedSelectionFill)
        }
    }
    Canvas(modifier.graphicsLayer()) {
        val document = controller.document
        val viewport = controller.viewport
        val origin = viewport.origin(viewSize, document)
        val visible = viewport.visibleBounds(viewSize, document, size)
        withTransform({
            translate(origin.x, origin.y)
            rotate(viewport.rotation, Offset.Zero)
            scale(scale * viewport.horizontalSign, scale, Offset.Zero)
        }) {
            clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                outline.path?.let { path ->
                    drawPath(
                        path,
                        Color.Black,
                        style = Stroke(StudioTheme.combinedSelectionHalo.toPx() / scale),
                    )
                    drawPath(
                        path,
                        Color.White,
                        style =
                            Stroke(
                                StudioTheme.combinedSelectionLine.toPx() / scale,
                                pathEffect = dash,
                            ),
                    )
                }
                for (tile in outline.mask) {
                    val x = tile.x * tile.size
                    val y = tile.y * tile.size
                    if (
                        x < visible.right &&
                            y < visible.bottom &&
                            x + tile.size > visible.left &&
                            y + tile.size > visible.top
                    )
                        drawContext.canvas.drawImage(
                            tile.image,
                            Offset(x.toFloat(), y.toFloat()),
                            paint,
                        )
                }
            }
        }
    }
}
