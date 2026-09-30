package app.podor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import app.podor.domain.BrushRaster
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
    val maskPaints = rememberLayerMaskPaints()
    val pixelRenderer = rememberPixelPreviewRenderer(preview.layers)
    LayerStackOverlay(preview.layers, modifier) { frame ->
        val scale = viewport.scale(view, document)
        if (scale <= 0f) return@LayerStackOverlay
        val origin = viewport.origin(view, document)
        val active = frame.layer.id == preview.layerId
        val delta =
            if (active) Offset(preview.offset.x.toFloat(), preview.offset.y.toFloat())
            else Offset.Zero
        val transform = if (active) preview.transform else null
        val source = preview.sourceBounds
        val area = viewport.visibleBounds(view, document, size)
        val pixelDelta = if (preview.maskEditing) Offset.Zero else delta
        val pixelTransform = if (preview.maskEditing) null else transform
        val visible = layerSourceVisible(area, pixelDelta, pixelTransform, source)
        val moveMask =
            active &&
                (preview.maskEditing || (frame.mask?.linked == true && preview.selection == null))
        val paper = Rect(0f, 0f, document.width.toFloat(), document.height.toFloat())
        paint.filterQuality =
            if (
                transform?.filter == ResampleFilter.Nearest ||
                    controller.preferences.canvasGrid.pixels ||
                    controller.brush.preset.raster != BrushRaster.Antialiased
            )
                FilterQuality.None
            else FilterQuality.Low
        withTransform({
            translate(origin.x, origin.y)
            rotate(viewport.rotation, Offset.Zero)
            scale(scale * viewport.horizontalSign, scale, Offset.Zero)
        }) {
            clipRect(paper.left, paper.top, paper.right, paper.bottom) {
                withLayerMask(
                    frame.mask,
                    paper,
                    area,
                    maskPaints,
                    paint.filterQuality,
                    if (moveMask) delta else Offset.Zero,
                    if (moveMask) transform else null,
                    source,
                    renderer = pixelRenderer,
                ) {
                    frame.stationary.forEach { tile ->
                        drawContext.canvas.drawImage(
                            tile.image,
                            Offset((tile.x * tile.size).toFloat(), (tile.y * tile.size).toFloat()),
                            paint,
                        )
                    }
                    if (
                        pixelTransform?.filter == ResampleFilter.Nearest &&
                            source != null &&
                            pixelRenderer != null
                    ) {
                        with(pixelRenderer) {
                            frame.tiles.forEach { tile ->
                                val origin =
                                    Offset(
                                        (tile.x * tile.size).toFloat(),
                                        (tile.y * tile.size).toFloat(),
                                    )
                                val bounds =
                                    Rect(origin, Size(tile.size.toFloat(), tile.size.toFloat()))
                                        .intersect(paper)
                                if (!bounds.isEmpty && bounds.overlaps(visible)) {
                                    drawTile(
                                        tile.image,
                                        bounds,
                                        origin,
                                        pixelDelta,
                                        pixelTransform,
                                        source,
                                        replace = false,
                                    )
                                }
                            }
                        }
                    } else
                        withLayerContentTransform(pixelDelta, pixelTransform, source) {
                            clipRect(paper.left, paper.top, paper.right, paper.bottom) {
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
