package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlinx.serialization.json.put

private fun VectorEditorTool.glyph() =
    when (this) {
        VectorEditorTool.Rectangle -> Glyph.Rectangle
        VectorEditorTool.Ellipse -> Glyph.Ellipse
        VectorEditorTool.Line -> Glyph.Line
        VectorEditorTool.Pen -> Glyph.Brush
        VectorEditorTool.Edit -> Glyph.Vector
    }

@Composable
internal fun VectorDock(controller: StudioController, modifier: Modifier = Modifier) {
    Surface(modifier, color = StudioTheme.panel, shape = StudioTheme.cardShape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VectorEditorTool.entries.forEach { mode ->
                ToolButton(
                    mode.glyph(),
                    mode.label,
                    selected = controller.vectorTool == mode,
                    enabled = !controller.busy,
                    plain = true,
                ) {
                    controller.cancelVector()
                    controller.vectorTool = mode
                }
            }
            ToolButton(
                Glyph.Close,
                "取消矢量编辑",
                enabled = controller.vectorPreview != null || controller.vectorPendingPath != null,
                plain = true,
            ) {
                controller.cancelVector()
            }
            ToolButton(
                Glyph.Check,
                "确认矢量编辑",
                enabled = controller.vectorPreview?.value?.valid() == true,
                plain = true,
            ) {
                controller.commitVector()
            }
        }
    }
}

@Composable
internal fun LayerVectorControls(controller: StudioController, enabled: Boolean) {
    val document = controller.document
    val active = document.layers.firstOrNull { it.id == document.active } ?: return
    val objects =
        controller.vectorObjects?.takeIf { it.id == active.id && it.revision == document.revision }
    val selected =
        controller.selectedVectorObject?.takeIf {
            it.id == active.id && it.revision == document.revision
        }
    val value = controller.vectorPreview?.value ?: selected?.`object`
    val editable =
        enabled &&
            !active.effectiveLocked &&
            active.effectiveVisible &&
            !document.maskEditing &&
            document.selection == null
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.maskStackGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("矢量对象"), Modifier.weight(1f), fontSize = StudioTheme.layerBlendLabelSize)
            ToolButton(
                Glyph.Vector,
                "编辑节点",
                selected = controller.tool == Tool.Vector,
                enabled = editable,
                plain = true,
            ) {
                controller.tool = Tool.Vector
                controller.vectorTool = VectorEditorTool.Edit
            }
            ToolButton(Glyph.Layers, "转换为像素图层", enabled = editable, plain = true) {
                controller.command("rasterize_vector") {
                    put("id", active.id)
                    put("revision", document.revision)
                }
            }
        }
        LazyColumn(Modifier.heightIn(max = StudioTheme.vectorObjectListHeight)) {
            items(objects?.objects.orEmpty().asReversed(), key = { it.id }) { item ->
                ChoiceSurface(
                    selected?.objectId == item.id,
                    { controller.selectVectorObject(item.id) },
                    Modifier.fillMaxWidth(),
                    enabled = enabled && controller.vectorPreview == null,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StudioIcon(
                            when (item.kind) {
                                "rect" -> Glyph.Rectangle
                                "ellipse" -> Glyph.Ellipse
                                "line" -> Glyph.Line
                                else -> Glyph.Vector
                            }
                        )
                        Text(
                            tr(item.name),
                            Modifier.weight(1f),
                            fontSize = StudioTheme.layerBlendLabelSize,
                        )
                        ToolButton(
                            if (item.visible) Glyph.Eye else Glyph.Hidden,
                            "对象可见性",
                            enabled = editable && selected?.objectId == item.id,
                            plain = true,
                        ) {
                            selected?.let {
                                controller.setVectorObject(
                                    it.`object`.copy(visible = !item.visible)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (selected != null && value != null) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ToolButton(
                    Glyph.Fill,
                    "填充颜色",
                    selected = value.style.fill != null,
                    enabled = editable,
                    plain = true,
                ) {
                    controller.setVectorObject(
                        value.copy(
                            style = value.style.copy(fill = vectorRgba(controller.brush.color))
                        )
                    )
                }
                ToolButton(
                    Glyph.Brush,
                    "描边颜色",
                    selected = value.style.stroke != null,
                    enabled = editable,
                    plain = true,
                ) {
                    controller.setVectorObject(
                        value.copy(
                            style =
                                value.style.copy(
                                    stroke =
                                        (value.style.stroke
                                                ?: VectorStroke(
                                                    vectorRgba(controller.brush.color),
                                                    controller.brush.size,
                                                ))
                                            .copy(color = vectorRgba(controller.brush.color))
                                )
                        )
                    )
                }
                ToolButton(
                    Glyph.Hidden,
                    "移除填充",
                    enabled = editable && value.style.fill != null && value.style.stroke != null,
                    plain = true,
                ) {
                    controller.setVectorObject(value.copy(style = value.style.copy(fill = null)))
                }
                ToolButton(
                    Glyph.Eraser,
                    "移除描边",
                    enabled = editable && value.style.fill != null && value.style.stroke != null,
                    plain = true,
                ) {
                    controller.setVectorObject(value.copy(style = value.style.copy(stroke = null)))
                }
                ToolButton(Glyph.Trash, "删除矢量对象", enabled = editable, plain = true) {
                    controller.vectorObjectCommand("delete_vector_object")
                }
            }
            var settings by remember { mutableStateOf(false) }
            Box {
                ToolButton(Glyph.More, "矢量对象设置", enabled = editable, plain = true) {
                    settings = true
                }
                StudioDropdownMenu(settings, { settings = false }) {
                    val index = objects?.objects?.indexOfFirst { it.id == selected.objectId } ?: -1
                    DropdownMenuItem(
                        text = { Text(tr("上移对象")) },
                        enabled = index >= 0 && index < (objects?.objects?.lastIndex ?: -1),
                        onClick = {
                            settings = false
                            controller.vectorObjectCommand("reorder_vector_object", index + 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("下移对象")) },
                        enabled = index > 0,
                        onClick = {
                            settings = false
                            controller.vectorObjectCommand("reorder_vector_object", index - 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("奇偶填充")) },
                        onClick = {
                            settings = false
                            controller.setVectorObject(
                                value.copy(
                                    style =
                                        value.style.copy(
                                            fillRule =
                                                if (value.style.fillRule == VectorFillRule.EvenOdd)
                                                    VectorFillRule.NonZero
                                                else VectorFillRule.EvenOdd
                                        )
                                )
                            )
                        },
                    )
                    if (value.geometry is VectorGeometry.Path)
                        DropdownMenuItem(
                            text = { Text(tr("闭合路径")) },
                            onClick = {
                                settings = false
                                val path = value.geometry
                                if (path.segments.lastOrNull() != VectorSegment.Close)
                                    controller.setVectorObject(
                                        value.copy(
                                            geometry =
                                                path.copy(
                                                    segments = path.segments + VectorSegment.Close
                                                )
                                        )
                                    )
                            },
                        )
                    value.style.stroke?.let { stroke ->
                        VectorCap.entries.forEach { cap ->
                            DropdownMenuItem(
                                text = { Text(tr("端点") + " · " + tr(cap.name)) },
                                onClick = {
                                    settings = false
                                    controller.setVectorObject(
                                        value.copy(
                                            style =
                                                value.style.copy(stroke = stroke.copy(cap = cap))
                                        )
                                    )
                                },
                            )
                        }
                        VectorJoin.entries.forEach { join ->
                            DropdownMenuItem(
                                text = { Text(tr("连接") + " · " + tr(join.name)) },
                                onClick = {
                                    settings = false
                                    controller.setVectorObject(
                                        value.copy(
                                            style =
                                                value.style.copy(stroke = stroke.copy(join = join))
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
            value.style.stroke
                ?.takeIf { editable }
                ?.let { stroke ->
                    LabeledSlider(
                        "描边宽度",
                        stroke.width,
                        1f..256f,
                        "${stroke.width.toInt()} px",
                        onChangeFinished = { controller.commitVector() },
                    ) { width ->
                        val updated =
                            value.copy(
                                style = value.style.copy(stroke = stroke.copy(width = width))
                            )
                        if (controller.vectorPreview == null)
                            controller.beginVectorEdit(updated, selected.objectId)
                        else controller.previewVector(updated)
                    }
                }
        }
    }
}

@Composable
internal fun VectorHandles(
    controller: StudioController,
    view: Size,
    modifier: Modifier = Modifier,
) {
    val spec = controller.vectorPreview?.value ?: controller.selectedVectorObject?.`object`
    val geometry = spec?.geometry ?: controller.vectorPendingPath ?: return
    Canvas(modifier) {
        val scale = controller.viewport.scale(view, controller.document)
        if (scale <= 0f) return@Canvas
        val origin = controller.viewport.origin(view, controller.document)
        val radius = StudioTheme.vectorHandleRadius.toPx() / scale
        val width = StudioTheme.vectorGuideWidth.toPx() / scale
        val nodes = geometry.nodes()
        fun position(node: VectorNode) = spec?.worldPoint(node.position) ?: node.position
        withTransform({
            translate(origin.x, origin.y)
            rotate(controller.viewport.rotation, Offset.Zero)
            scale(scale * controller.viewport.horizontalSign, scale, Offset.Zero)
        }) {
            if (geometry is VectorGeometry.Path) {
                nodes
                    .filter { it.point > 0 }
                    .forEach { node ->
                        val endpoint = nodes.firstOrNull {
                            it.segment == node.segment && it.point == 0
                        }
                        val previous = nodes.lastOrNull {
                            it.segment < node.segment && it.point == 0
                        }
                        val anchor =
                            if (
                                geometry.segments[node.segment] is VectorSegment.Cubic &&
                                    node.point == 1
                            )
                                previous
                            else endpoint
                        anchor?.let {
                            drawLine(StudioTheme.muted, position(it), position(node), width)
                        }
                    }
            }
            nodes.forEach { node ->
                drawCircle(StudioTheme.panel, radius, position(node))
                drawCircle(StudioTheme.text, radius, position(node), style = Stroke(width))
            }
        }
    }
}
