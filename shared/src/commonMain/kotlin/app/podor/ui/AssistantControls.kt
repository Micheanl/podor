package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.put

@Composable
internal fun AssistantDock(
    controller: StudioController,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    var adding by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selected = controller.selectedAssistant
    val editable = controller.ready && !controller.busy && controller.assistantPreview == null
    val finish: @Composable () -> Unit = {
        ToolButton(
            Glyph.Brush,
            "结束编辑",
            enabled = controller.assistantPreview?.committing != true,
            plain = true,
        ) {
            controller.cancelAssistant()
            controller.tool = Tool.Brush
        }
    }
    ContextActionRow(
        modifier,
        floating,
        trailing = {
            ToolButton(
                Glyph.Close,
                "取消助手编辑",
                enabled = controller.assistantPreview?.committing == false,
                plain = true,
            ) {
                controller.cancelAssistant()
            }
            ToolButton(
                Glyph.Check,
                "确认助手编辑",
                enabled =
                    controller.assistantPreview?.let {
                        !it.committing && it.value.valid(controller.document.maxAssistantCoordinate)
                    } == true,
                plain = true,
            ) {
                controller.commitAssistant()
            }
            if (!floating) finish()
        },
    ) {
        Box {
            ToolButton(
                Glyph.Plus,
                "添加助手",
                enabled =
                    editable &&
                        controller.document.assistants.items.size <
                            controller.document.maxDrawingAssistants,
                plain = true,
            ) {
                adding = true
            }
            StudioDropdownMenu(adding, { adding = false }) {
                AssistantPreset.entries.forEach { preset ->
                    StudioDropdownMenuItem(
                        text = { Text(tr(preset.label)) },
                        onClick = {
                            adding = false
                            controller.addAssistant(preset)
                        },
                    )
                }
            }
        }
        Box {
            ToolButton(
                Glyph.Assistant,
                "选择助手",
                enabled = editable && controller.document.assistants.items.isNotEmpty(),
                plain = true,
            ) {
                selecting = true
            }
            StudioDropdownMenu(selecting, { selecting = false }) {
                controller.document.assistants.items.forEach { assistant ->
                    StudioDropdownMenuItem(
                        text = { Text(assistant.name) },
                        leadingIcon = {
                            if (assistant.id == selected?.id) StudioIcon(Glyph.Check)
                        },
                        onClick = {
                            selecting = false
                            controller.selectAssistant(assistant.id)
                        },
                    )
                }
            }
        }
        ToolButton(
            Glyph.MagicWand,
            "吸附助手",
            selected = selected?.id == controller.document.assistants.snapId && selected != null,
            enabled = editable && selected != null,
            plain = true,
        ) {
            controller.command("set_assistant_snap") {
                put(
                    "id",
                    selected?.id?.takeUnless { it == controller.document.assistants.snapId },
                )
                put("revision", controller.document.revision)
            }
        }
        ToolButton(
            if (selected?.visible != false) Glyph.Eye else Glyph.Hidden,
            "助手可见性",
            enabled = editable && selected != null,
            plain = true,
        ) {
            selected?.let {
                controller.command("set_assistant") {
                    put("id", it.id)
                    put("assistant", it.spec().copy(visible = !it.visible).request())
                    put("revision", controller.document.revision)
                }
            }
        }
        ToolButton(Glyph.Trash, "删除助手", enabled = editable && selected != null, plain = true) {
            selected?.let {
                controller.command("delete_assistant") {
                    put("id", it.id)
                    put("revision", controller.document.revision)
                }
            }
        }
        if (floating) finish()
    }
}

private fun assistantLine(origin: Offset, direction: Offset, size: Size): Pair<Offset, Offset>? {
    if (!origin.x.isFinite() || !origin.y.isFinite() || direction.getDistanceSquared() == 0f)
        return null
    var first = Double.NEGATIVE_INFINITY
    var last = Double.POSITIVE_INFINITY
    for ((position, delta, maximum) in
        listOf(
            Triple(origin.x, direction.x, size.width),
            Triple(origin.y, direction.y, size.height),
        )) {
        if (delta == 0f) {
            if (position !in 0f..maximum) return null
        } else {
            val a = -position.toDouble() / delta
            val b = (maximum.toDouble() - position) / delta
            first = maxOf(first, minOf(a, b))
            last = minOf(last, maxOf(a, b))
        }
    }
    if (first > last || !first.isFinite() || !last.isFinite()) return null
    fun point(t: Double) =
        Offset((origin.x + direction.x * t).toFloat(), (origin.y + direction.y * t).toFloat())
    return point(first) to point(last)
}

@Composable
internal fun AssistantOverlay(
    controller: StudioController,
    viewSize: Size,
    modifier: Modifier = Modifier,
) {
    val document = controller.document
    val preview = controller.assistantPreview
    val geometry = preview?.value?.geometry ?: controller.selectedAssistant?.geometry
    val viewport = controller.viewport
    val guides =
        remember(
            document.assistants,
            preview?.value,
            document.width,
            document.height,
            viewport,
            viewSize,
        ) {
            document.assistants.items.flatMap { item ->
                val spec = if (preview?.id == item.id) preview.value else item.spec()
                if (!spec.visible || !spec.valid(document.maxAssistantCoordinate)) emptyList()
                else {
                    fun project(point: Offset) = viewport.toView(point, viewSize, document)
                    val center = Offset(document.width / 2f, document.height / 2f)
                    val worldLines =
                        when (val shape = spec.geometry) {
                            is AssistantGeometry.Parallel -> {
                                val direction = shape.b.offset() - shape.a.offset()
                                val perpendicular =
                                    Offset(-direction.y, direction.x) / direction.getDistance()
                                (-3..3).map {
                                    shape.a.offset() +
                                        perpendicular *
                                            (minOf(document.width, document.height) / 8f) *
                                            it.toFloat() to direction
                                }
                            }
                            is AssistantGeometry.Radial ->
                                (0 until 12).map { angle ->
                                    val radians = angle * PI.toFloat() / 12f
                                    shape.center.offset() to Offset(cos(radians), sin(radians))
                                }
                            is AssistantGeometry.Perspective ->
                                shape.families.flatMap { family ->
                                    when (family) {
                                        is AssistantFamily.Vanishing ->
                                            (0..4)
                                                .flatMap { step ->
                                                    listOf(
                                                        Offset(document.width * step / 4f, 0f),
                                                        Offset(
                                                            document.width * step / 4f,
                                                            document.height.toFloat(),
                                                        ),
                                                        Offset(0f, document.height * step / 4f),
                                                        Offset(
                                                            document.width.toFloat(),
                                                            document.height * step / 4f,
                                                        ),
                                                    )
                                                }
                                                .map {
                                                    family.point.offset() to
                                                        (it - family.point.offset())
                                                }
                                        is AssistantFamily.Infinite ->
                                            (-3..3).map { index ->
                                                val direction = family.direction.offset()
                                                val perpendicular =
                                                    Offset(-direction.y, direction.x) /
                                                        direction.getDistance()
                                                center +
                                                    perpendicular *
                                                        (minOf(document.width, document.height) /
                                                            8f) *
                                                        index.toFloat() to direction
                                            }
                                    }
                                }
                        }
                    worldLines
                        .mapNotNull { (point, direction) ->
                            assistantLine(
                                project(point),
                                project(point + direction) - project(point),
                                viewSize,
                            )
                        }
                        .map { Triple(item.id, it.first, it.second) }
                }
            }
        }
    val density = LocalDensity.current
    val width = with(density) { StudioTheme.assistantGuideWidth.toPx() }
    val radius = with(density) { StudioTheme.assistantHandleRadius.toPx() }
    val color = StudioTheme.text.copy(alpha = StudioTheme.assistantGuideAlpha)
    val handleColor = StudioTheme.text
    val fill = StudioTheme.panel
    Canvas(modifier) {
        guides.forEach { (_, start, end) -> drawLine(color, start, end, width) }
        if (controller.tool == Tool.Assistant && geometry != null) {
            geometry.handles(document).forEach {
                val point = viewport.toView(it, viewSize, document)
                drawCircle(fill, radius, point)
                drawCircle(handleColor, radius, point, style = Stroke(width))
            }
        }
    }
}
