package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import app.podor.domain.LayerBlendMode
import app.podor.domain.ResampleFilter
import app.podor.presentation.LayerMovePreview
import app.podor.presentation.StudioController

@Composable
fun LayerMoveOverlay(
    controller: StudioController,
    preview: LayerMovePreview,
    view: Size,
    modifier: Modifier = Modifier,
) {
    val document = controller.document
    val viewport = controller.viewport
    val paint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        preview.layers.forEach { frame ->
            Canvas(
                Modifier.matchParentSize().graphicsLayer {
                    alpha = frame.layer.opacity
                    blendMode =
                        when (frame.layer.blend) {
                            LayerBlendMode.Normal -> BlendMode.SrcOver
                            LayerBlendMode.Multiply -> BlendMode.Multiply
                            LayerBlendMode.Screen -> BlendMode.Screen
                            LayerBlendMode.Overlay -> BlendMode.Overlay
                            LayerBlendMode.SoftLight -> BlendMode.Softlight
                            LayerBlendMode.Darken -> BlendMode.Darken
                            LayerBlendMode.Lighten -> BlendMode.Lighten
                            LayerBlendMode.Difference -> BlendMode.Difference
                        }
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            ) {
                val scale = viewport.scale(view, document)
                if (scale <= 0f) return@Canvas
                val origin = viewport.origin(view, document)
                val delta =
                    if (frame.layer.id == preview.layerId)
                        Offset(preview.offset.x.toFloat(), preview.offset.y.toFloat())
                    else Offset.Zero
                val transform = if (frame.layer.id == preview.layerId) preview.transform else null
                val source = preview.sourceBounds
                val area = viewport.visibleBounds(view, document, size)
                val visible =
                    if (transform != null && source != null) {
                        val corners =
                            listOf(area.topLeft, area.topRight, area.bottomLeft, area.bottomRight)
                                .map { transform.sourcePoint(source, it) }
                        Rect(
                            corners.minOf { it.x },
                            corners.minOf { it.y },
                            corners.maxOf { it.x },
                            corners.maxOf { it.y },
                        )
                    } else area.translate(-delta)
                paint.filterQuality =
                    if (transform?.filter == ResampleFilter.Nearest) FilterQuality.None
                    else FilterQuality.Low
                withTransform({
                    translate(origin.x, origin.y)
                    rotate(viewport.rotation, Offset.Zero)
                    scale(scale * viewport.horizontalSign, scale, Offset.Zero)
                }) {
                    clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                        withTransform({
                            if (transform != null && source != null) {
                                val center = transform.center(source)
                                translate(center.x, center.y)
                                rotate(transform.angle, Offset.Zero)
                                scale(
                                    transform.width / source.width *
                                        if (transform.flipX) -1f else 1f,
                                    transform.height / source.height *
                                        if (transform.flipY) -1f else 1f,
                                    Offset.Zero,
                                )
                                translate(-source.center.x, -source.center.y)
                            } else translate(delta.x, delta.y)
                        }) {
                            clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                                frame.tiles.forEach { tile ->
                                    val x = tile.x * tile.size
                                    val y = tile.y * tile.size
                                    if (
                                        x < visible.right &&
                                            y < visible.bottom &&
                                            x + tile.size > visible.left &&
                                            y + tile.size > visible.top
                                    ) {
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
                }
            }
        }
    }
}
