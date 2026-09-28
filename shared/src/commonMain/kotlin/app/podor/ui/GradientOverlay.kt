package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import app.podor.domain.GradientShape
import app.podor.presentation.GradientPreview
import app.podor.presentation.StudioController

@Composable
fun GradientOverlay(
    controller: StudioController,
    preview: GradientPreview,
    view: Size,
    modifier: Modifier = Modifier,
) {
    val tilePaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val maskPaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val blendPaint = remember { Paint() }
    val maskBlend = remember { Paint().apply { blendMode = BlendMode.DstIn } }
    LayerStackOverlay(preview.layers, modifier) { frame ->
        val document = controller.document
        val viewport = controller.viewport
        val scale = viewport.scale(view, document)
        if (scale <= 0f) return@LayerStackOverlay
        val origin = viewport.origin(view, document)
        val visible = viewport.visibleBounds(view, document, size)
        val area = Rect(0f, 0f, document.width.toFloat(), document.height.toFloat())
        withTransform({
            translate(origin.x, origin.y)
            rotate(viewport.rotation, Offset.Zero)
            scale(scale * viewport.horizontalSign, scale, Offset.Zero)
        }) {
            clipRect(area.left, area.top, area.right, area.bottom) {
                frame.tiles.forEach { tile ->
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
                            tilePaint,
                        )
                }
                if (frame.layer.id == preview.layerId) {
                    val line = preview.line
                    if (line != null && line.valid()) {
                        val settings = controller.gradient
                        val colors =
                            listOf(
                                Color(settings.startColor).let {
                                    it.copy(alpha = it.alpha * settings.opacity)
                                },
                                Color(settings.endColor).let {
                                    it.copy(alpha = it.alpha * settings.opacity)
                                },
                            )
                        val brush =
                            when (settings.shape) {
                                GradientShape.Linear ->
                                    Brush.linearGradient(colors, line.start, line.end)
                                GradientShape.Radial ->
                                    Brush.radialGradient(
                                        colors,
                                        line.start,
                                        (line.end - line.start).getDistance(),
                                    )
                            }
                        val selection = preview.selection
                        val bounds =
                            selection?.let {
                                Rect(
                                    it.left.toFloat(),
                                    it.top.toFloat(),
                                    it.right.toFloat(),
                                    it.bottom.toFloat(),
                                )
                            } ?: area
                        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                            blendPaint.blendMode =
                                if (frame.layer.alphaLocked) BlendMode.SrcAtop
                                else BlendMode.SrcOver
                            val canvas = drawContext.canvas
                            canvas.saveLayer(bounds, blendPaint)
                            drawRect(brush, topLeft = bounds.topLeft, size = bounds.size)
                            if (preview.mask.isNotEmpty()) {
                                canvas.saveLayer(bounds, maskBlend)
                                preview.mask.forEach { tile ->
                                    val x = tile.x * tile.size
                                    val y = tile.y * tile.size
                                    if (
                                        x < visible.right &&
                                            y < visible.bottom &&
                                            x + tile.size > visible.left &&
                                            y + tile.size > visible.top
                                    )
                                        canvas.drawImage(
                                            tile.image,
                                            Offset(x.toFloat(), y.toFloat()),
                                            maskPaint,
                                        )
                                }
                                canvas.restore()
                            }
                            canvas.restore()
                        }
                    }
                }
            }
        }
    }
    Canvas(modifier.graphicsLayer()) {
        val line = preview.line ?: return@Canvas
        val viewport = controller.viewport
        val start = viewport.toView(line.start, view, controller.document)
        val end = viewport.toView(line.end, view, controller.document)
        drawLine(
            StudioTheme.transformOutlineShade,
            start,
            end,
            StudioTheme.transformOutlineHalo.toPx(),
        )
        drawLine(StudioTheme.text, start, end, StudioTheme.transformOutlineWidth.toPx())
        for ((point, color) in
            listOf(start to controller.gradient.startColor, end to controller.gradient.endColor)) {
            drawCircle(
                StudioTheme.text,
                StudioTheme.gradientHandleRadius.toPx() + StudioTheme.transformOutlineWidth.toPx(),
                point,
            )
            drawCircle(StudioTheme.panel, StudioTheme.gradientHandleRadius.toPx(), point)
            drawCircle(Color(color), StudioTheme.gradientHandleRadius.toPx(), point)
        }
    }
}
