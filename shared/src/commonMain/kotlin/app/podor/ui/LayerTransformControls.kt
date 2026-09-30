package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import app.podor.domain.*
import app.podor.presentation.LayerMovePreview
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun LayerTransformHandles(
    controller: StudioController,
    preview: LayerMovePreview,
    view: Size,
    modifier: Modifier = Modifier,
) {
    val outline = remember { Path() }
    Canvas(modifier.graphicsLayer()) {
        val source = preview.sourceBounds ?: return@Canvas
        val value = preview.transform ?: return@Canvas
        val viewport = controller.viewport
        val document = controller.document
        val scale = viewport.scale(view, document).coerceAtLeast(0.01f)
        val gap = StudioTheme.transformRotationGap.toPx() / scale
        fun point(handle: TransformHandle) =
            viewport.toView(handle.position(source, value, gap), view, document)
        val corners =
            listOf(
                TransformHandle.TopLeft,
                TransformHandle.TopRight,
                TransformHandle.BottomRight,
                TransformHandle.BottomLeft,
            )
        outline.reset()
        corners.forEachIndexed { i, handle ->
            val p = point(handle)
            if (i == 0) outline.moveTo(p.x, p.y) else outline.lineTo(p.x, p.y)
        }
        outline.close()
        val line = StudioTheme.transformOutlineWidth.toPx()
        drawPath(
            outline,
            StudioTheme.transformOutlineShade,
            style = Stroke(StudioTheme.transformOutlineHalo.toPx()),
        )
        drawPath(outline, StudioTheme.text, style = Stroke(line))
        drawLine(StudioTheme.text, point(TransformHandle.Top), point(TransformHandle.Rotate), line)
        for (handle in TransformHandle.entries) {
            if (handle == TransformHandle.Move) continue
            val radius = StudioTheme.transformHandleRadius.toPx()
            val p = point(handle)
            drawCircle(StudioTheme.panel, radius + line, p)
            drawCircle(StudioTheme.text, radius, p)
            if (handle == TransformHandle.Rotate)
                drawCircle(StudioTheme.selection, StudioTheme.transformRotationDot.toPx(), p)
        }
    }
}

@Composable
fun LayerTransformDock(controller: StudioController, modifier: Modifier = Modifier) {
    val preview = controller.layerMove
    val value = preview?.transform
    val source = preview?.sourceBounds
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val enabled = value != null && !controller.busy && !preview.committing
    Row(
        modifier
            .widthIn(max = StudioTheme.transformDockWidth)
            .fillMaxWidth()
            .padding(horizontal = StudioTheme.transformDockPadding)
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.moveDockBorderWidth, StudioTheme.border, CircleShape)
            .padding(StudioTheme.transformDockGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.transformDockGap),
        ) {
            if (value == null)
                Text(
                    tr(
                        when {
                            active?.locked == true -> "图层已锁定，请先解锁"
                            active?.alphaLocked == true -> "请先解除透明度锁定"
                            active?.visible == false -> "请先显示当前图层"
                            controller.document.selection != null -> "请先取消选区，再变换图层"
                            else -> "准备图层…"
                        }
                    ),
                    fontSize = StudioTheme.moveDockTitleSize,
                )
            else {
                Text(
                    "${value.width} × ${value.height} · ${value.angle.roundToInt()}°",
                    Modifier.padding(horizontal = StudioTheme.transformDockPadding),
                    fontSize = StudioTheme.moveDockValueSize,
                    color = StudioTheme.muted,
                )
                ToolButton(Glyph.Lock, "锁定比例", selected = preview.proportional, enabled = enabled) {
                    preview.proportional = !preview.proportional
                }
                ToolButton(Glyph.Mirror, "水平翻转图层", enabled = enabled) {
                    controller.previewLayerTransform(value.copy(flipX = !value.flipX))
                }
                ToolButton(Glyph.MirrorVertical, "垂直翻转图层", enabled = enabled) {
                    controller.previewLayerTransform(value.copy(flipY = !value.flipY))
                }
                ToolButton(Glyph.Rotate, "顺时针旋转 90°", enabled = enabled) {
                    controller.previewLayerTransform(
                        value.copy(angle = normalizeTransformAngle(value.angle + 90f))
                    )
                }
                ToolButton(
                    Glyph.Selection,
                    "像素采样",
                    selected = value.filter == ResampleFilter.Nearest,
                    enabled =
                        enabled &&
                            (controller.document.colorMode != DocumentColorMode.Indexed ||
                                controller.document.maskEditing),
                ) {
                    controller.previewLayerTransform(
                        value.copy(
                            filter =
                                if (value.filter == ResampleFilter.Nearest) ResampleFilter.Lanczos3
                                else ResampleFilter.Nearest
                        )
                    )
                }
                ToolButton(Glyph.Undo, "重置变换", enabled = enabled) {
                    if (source != null)
                        controller.previewLayerTransform(
                            LayerTransform(
                                source.width.toInt(),
                                source.height.toInt(),
                                filter = value.filter,
                            )
                        )
                }
            }
        }
        ToolButton(Glyph.Close, "取消变换", enabled = preview?.committing != true) {
            controller.cancelLayerMove(exit = true)
            controller.tool = Tool.Brush
        }
        ToolButton(Glyph.Check, "确认变换", prominent = true, enabled = enabled) {
            controller.commitLayerTransform()
        }
    }
}
