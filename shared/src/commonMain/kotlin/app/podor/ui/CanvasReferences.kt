package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.domain.ReferenceGesture
import app.podor.domain.ReferencePlacement
import app.podor.domain.Tool
import app.podor.presentation.ReferenceImage
import app.podor.presentation.StudioController
import app.podor.ui.input.platformPenInput
import app.podor.ui.input.platformTouchInput
import kotlin.math.roundToInt

class ReferenceInput {
    var change: PointerInputChange? = null

    fun consumed(event: PointerEvent): Boolean {
        val handled = change
        change = null
        return handled != null &&
            event.changes.any {
                it.isConsumed && it.id == handled.id && it.uptimeMillis == handled.uptimeMillis
            }
    }
}

@Composable
fun CanvasReferences(
    controller: StudioController,
    viewSize: Size,
    modifier: Modifier,
    input: ReferenceInput,
) {
    val references = controller.references
    if (!references.visible) return
    val view by rememberUpdatedState(viewSize)
    val density by rememberUpdatedState(LocalDensity.current.density)
    val outline = remember { Path() }
    Box(modifier) {
        Canvas(
            Modifier.matchParentSize().graphicsLayer().pointerInput(controller) {
                var target: ReferenceImage? = null
                var drag: ReferenceGesture? = null
                var pointer: PointerId? = null
                var gesturing = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pen = event.platformPenInput()
                        val touch = event.platformTouchInput()
                        if (pen?.cancelled == true || touch?.cancelled == true) {
                            drag?.let { target?.placement = it.before }
                            target = null
                            drag = null
                            pointer = null
                            gesturing = false
                            event.changes.forEach { it.consume() }
                            input.change = event.changes.firstOrNull()
                            continue
                        }
                        if (event.changes.count { it.pressed } >= 2 || touch?.gesturing == true) {
                            target = null
                            drag = null
                            pointer = null
                            gesturing = true
                            continue
                        }
                        if (gesturing) {
                            if (event.changes.none { it.pressed }) gesturing = false
                            continue
                        }
                        if (event.type == PointerEventType.Scroll) {
                            target = null
                            drag = null
                            pointer = null
                            continue
                        }
                        val change =
                            event.changes.firstOrNull { it.id == pointer }
                                ?: event.changes.firstOrNull()
                                ?: continue
                        val scale = controller.viewport.scale(view, controller.document)
                        if (scale <= 0f) continue
                        val position =
                            change.position +
                                (pen?.samples?.lastOrNull()?.offset
                                    ?: touch?.samples?.lastOrNull()
                                    ?: Offset.Zero) * density
                        val point =
                            controller.viewport.toDocument(position, view, controller.document)
                        if (
                            change.pressed &&
                                !change.previousPressed &&
                                !change.isConsumed &&
                                !controller.busy &&
                                controller.tool != Tool.Hand &&
                                (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed)
                        ) {
                            val selected = references.selected
                            val radius = StudioTheme.referenceHitRadius.value * density / scale
                            val corner =
                                selected
                                    ?.placement
                                    ?.corners
                                    ?.indexOfFirst { (it - point).getDistance() <= radius }
                                    ?.takeIf { it >= 0 }
                            target =
                                if (corner != null) selected
                                else
                                    references.images.asReversed().firstOrNull {
                                        it.placement.bounds.contains(point)
                                    }
                            references.select(target?.id)
                            drag = target?.let { ReferenceGesture(it.placement, point, corner) }
                            pointer = target?.let { change.id }
                        }
                        val active = target
                        if (active != null && drag != null) {
                            active.placement = drag!!.update(point)
                            change.consume()
                            input.change = change
                            if (!change.pressed) {
                                target = null
                                drag = null
                                pointer = null
                            }
                        }
                    }
                }
            }
        ) {
            val viewport = controller.viewport
            val document = controller.document
            val scale = viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
            val origin = viewport.origin(viewSize, document)
            withTransform({
                translate(origin.x, origin.y)
                rotate(viewport.rotation, Offset.Zero)
                scale(scale * viewport.horizontalSign, scale, Offset.Zero)
            }) {
                references.images.forEach { reference ->
                    val placement = reference.placement
                    val bounds = placement.bounds
                    withTransform({
                        translate(if (placement.mirrored) bounds.right else bounds.left, bounds.top)
                        scale(
                            bounds.width / reference.bitmap.width *
                                if (placement.mirrored) -1f else 1f,
                            bounds.height / reference.bitmap.height,
                            Offset.Zero,
                        )
                    }) {
                        drawImage(reference.bitmap, filterQuality = FilterQuality.Low)
                    }
                }
            }
            references.selected?.let { reference ->
                val corners =
                    reference.placement.corners.map { viewport.toView(it, viewSize, document) }
                outline.reset()
                outline.moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { outline.lineTo(it.x, it.y) }
                outline.close()
                drawPath(
                    outline,
                    StudioTheme.background,
                    style = Stroke(StudioTheme.referenceOutline.toPx() * 2),
                )
                drawPath(
                    outline,
                    StudioTheme.accent,
                    style = Stroke(StudioTheme.referenceOutline.toPx()),
                )
                val handle = StudioTheme.referenceHandle.toPx()
                corners.forEach {
                    drawRect(
                        StudioTheme.text,
                        it - Offset(handle / 2, handle / 2),
                        Size(handle, handle),
                    )
                    drawRect(
                        StudioTheme.accent,
                        it - Offset(handle / 2, handle / 2),
                        Size(handle, handle),
                        style = Stroke(StudioTheme.referenceOutline.toPx()),
                    )
                }
            }
        }
        references.selected?.let { reference ->
            Row(
                Modifier.offset {
                        val points =
                            reference.placement.corners.map {
                                controller.viewport.toView(it, view, controller.document)
                            }
                        val width = StudioTheme.controlSize.value * density * 4
                        val height = StudioTheme.controlSize.value * density
                        IntOffset(
                            ((points.minOf { it.x } + points.maxOf { it.x } - width) / 2)
                                .coerceIn(0f, (view.width - width).coerceAtLeast(0f))
                                .roundToInt(),
                            (points.minOf { it.y } -
                                    height -
                                    StudioTheme.referenceToolbarGap.value * density)
                                .coerceIn(0f, (view.height - height).coerceAtLeast(0f))
                                .roundToInt(),
                        )
                    }
                    .background(StudioTheme.panel, CircleShape)
                    .border(StudioTheme.referenceOutline, StudioTheme.border, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Glyph.Fit, "适合参考图") {
                    reference.placement =
                        ReferencePlacement.fit(
                            reference.bitmap.width,
                            reference.bitmap.height,
                            controller.document,
                        )
                }
                ToolButton(Glyph.Mirror, "镜像参考图", reference.placement.mirrored) {
                    reference.placement =
                        reference.placement.copy(mirrored = !reference.placement.mirrored)
                }
                ToolButton(Glyph.Trash, "移除参考图") { references.remove() }
                ToolButton(Glyph.Check, "完成") { references.select(null) }
            }
        }
        if (references.loading || references.error != null) {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .padding(StudioTheme.referenceToolbarGap)
                    .background(StudioTheme.panel, CircleShape)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (references.loading)
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else
                    Text(
                        tr(references.error!!),
                        color = StudioTheme.text,
                        fontSize = StudioTheme.repositoryLinkLabelSize,
                    )
                ToolButton(Glyph.Close, "关闭") {
                    if (references.loading) references.cancelLoading() else references.clearError()
                }
            }
        }
    }
}
