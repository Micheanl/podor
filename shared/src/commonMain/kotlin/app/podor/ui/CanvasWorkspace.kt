package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import kotlin.math.pow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.put

@Composable
fun CanvasWorkspace(
    controller: StudioController,
    modifier: Modifier = Modifier,
    endInset: Dp = 0.dp,
) {
    var fullSize by remember { mutableStateOf(Size.Zero) }
    val inset by rememberUpdatedState(with(LocalDensity.current) { endInset.toPx() })
    val viewSize by remember {
        derivedStateOf { Size((fullSize.width - inset).coerceAtLeast(0f), fullSize.height) }
    }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var selectionStart by remember { mutableStateOf<Offset?>(null) }
    var selectionEnd by remember { mutableStateOf<Offset?>(null) }
    val tilePaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val selectionDash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { fullSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(controller) {
                var drawing = false
                var activePointer: PointerId? = null
                var gesture = false
                try {
                    while (currentCoroutineContext().isActive) {
                        val event = awaitPointerEventScope { awaitPointerEvent() }
                        val pressed = event.changes.filter { it.pressed }
                        val stylus = pressed.firstOrNull {
                            it.type == PointerType.Stylus || it.type == PointerType.Eraser
                        }
                        val primary =
                            stylus
                                ?: event.changes.firstOrNull { it.id == activePointer }
                                ?: event.changes.first()
                        cursor =
                            if (
                                primary.type == PointerType.Mouse &&
                                    event.type != PointerEventType.Exit
                            )
                                primary.position
                            else null
                        if (event.type == PointerEventType.Scroll) {
                            if (drawing) continue
                            controller.viewport =
                                controller.viewport.transform(
                                    primary.position,
                                    Offset.Zero,
                                    1.12f.pow(-primary.scrollDelta.y),
                                    viewSize,
                                    controller.document,
                                )
                            primary.consume()
                            continue
                        }
                        if (drawing && stylus != null && stylus.id != activePointer) {
                            controller.end(cancel = true)
                            drawing = false
                            gesture = false
                        }
                        if (
                            drawing &&
                                event.changes.any { it.id == activePointer && !it.pressed } &&
                                pressed.isNotEmpty()
                        ) {
                            controller.end()
                            drawing = false
                            gesture = true
                        }
                        if (stylus == null && pressed.size >= 2) {
                            selectionStart = null
                            selectionEnd = null
                            if (drawing) {
                                controller.end(cancel = true)
                                drawing = false
                            }
                            gesture = true
                            controller.viewport =
                                controller.viewport.transform(
                                    event.calculateCentroid(),
                                    event.calculatePan(),
                                    event.calculateZoom(),
                                    viewSize,
                                    controller.document,
                                )
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pressed.isEmpty()) {
                            selectionStart?.let { start ->
                                val end =
                                    controller.viewport.toDocument(
                                        primary.position,
                                        viewSize,
                                        controller.document,
                                    )
                                controller.select(start, end)
                            }
                            selectionStart = null
                            selectionEnd = null
                            if (drawing) {
                                if (primary.position != primary.previousPosition) {
                                    val point =
                                        controller.viewport.toDocument(
                                            primary.position,
                                            viewSize,
                                            controller.document,
                                        )
                                    controller.points(
                                        listOf(
                                            Triple(
                                                point.x,
                                                point.y,
                                                if (primary.type == PointerType.Mouse) 1f
                                                else primary.pressure.coerceIn(0.05f, 1f),
                                            )
                                        )
                                    )
                                }
                                controller.end()
                                drawing = false
                            }
                            activePointer = null
                            gesture = false
                            continue
                        }
                        if (gesture) continue
                        if (controller.tool == Tool.Hand) {
                            controller.viewport =
                                controller.viewport.copy(
                                    pan =
                                        controller.viewport.pan + primary.position -
                                            primary.previousPosition
                                )
                            primary.consume()
                            continue
                        }
                        if (primary.type == PointerType.Touch && !controller.fingerDrawing) continue
                        val point =
                            controller.viewport.toDocument(
                                primary.position,
                                viewSize,
                                controller.document,
                            )
                        if (selectionStart != null) {
                            selectionEnd = point
                            primary.consume()
                            continue
                        }
                        if (!drawing && primary.pressed && !primary.previousPressed) {
                            if (
                                point.x < 0 ||
                                    point.y < 0 ||
                                    point.x >= controller.document.width ||
                                    point.y >= controller.document.height
                            )
                                continue
                            if (controller.tool == Tool.Picker) {
                                controller.command("pick") {
                                    put("x", point.x.toInt())
                                    put("y", point.y.toInt())
                                }
                                continue
                            }
                            if (!controller.ready || controller.busy) continue
                            if (controller.tool == Tool.Fill) {
                                controller.fill(point)
                                primary.consume()
                                continue
                            }
                            if (controller.tool == Tool.Select) {
                                selectionStart = point
                                selectionEnd = point
                                activePointer = primary.id
                                primary.consume()
                                continue
                            }
                            activePointer = primary.id
                            controller.begin(
                                point,
                                if (primary.type == PointerType.Mouse) 1f
                                else primary.pressure.coerceIn(0.05f, 1f),
                                primary.type == PointerType.Eraser,
                            )
                            drawing = true
                        } else if (
                            drawing &&
                                primary.id == activePointer &&
                                primary.position != primary.previousPosition
                        ) {
                            val samples =
                                primary.historical.map { historical ->
                                    val position =
                                        controller.viewport.toDocument(
                                            historical.position,
                                            viewSize,
                                            controller.document,
                                        )
                                    Triple(
                                        position.x,
                                        position.y,
                                        primary.pressure.coerceIn(0.05f, 1f),
                                    )
                                } +
                                    Triple(
                                        point.x,
                                        point.y,
                                        if (primary.type == PointerType.Mouse) 1f
                                        else primary.pressure.coerceIn(0.05f, 1f),
                                    )
                            controller.points(samples)
                        }
                        if (drawing) primary.consume()
                    }
                } finally {
                    if (drawing) controller.command("cancel")
                }
            }
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val scale = controller.viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
            val origin = controller.viewport.origin(viewSize, document)
            val width = document.width * scale
            val height = document.height * scale
            drawRect(Color.Black.copy(alpha = 0.2f), origin + Offset(0f, 10f), Size(width, height))
            translate(origin.x, origin.y) {
                scale(scale, scale, Offset.Zero) {
                    clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                        drawRect(
                            Color.White,
                            size = Size(document.width.toFloat(), document.height.toFloat()),
                        )
                        val left = (-origin.x / scale).coerceAtLeast(0f)
                        val top = (-origin.y / scale).coerceAtLeast(0f)
                        val right =
                            ((size.width - origin.x) / scale).coerceAtMost(document.width.toFloat())
                        val bottom =
                            ((size.height - origin.y) / scale).coerceAtMost(
                                document.height.toFloat()
                            )
                        controller.frame.tiles.values.forEach { tile ->
                            val x = tile.x * tile.size
                            val y = tile.y * tile.size
                            if (
                                x >= right ||
                                    y >= bottom ||
                                    x + tile.size <= left ||
                                    y + tile.size <= top
                            )
                                return@forEach
                            drawContext.canvas.drawImage(
                                tile.image,
                                Offset(
                                    (tile.x * tile.size).toFloat(),
                                    (tile.y * tile.size).toFloat(),
                                ),
                                tilePaint,
                            )
                        }
                    }
                }
            }
        }
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val scale = controller.viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
            val origin = controller.viewport.origin(viewSize, document)
            val selected =
                if (selectionStart != null && selectionEnd != null) {
                    val a = selectionStart!!
                    val b = selectionEnd!!
                    Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                } else
                    controller.document.selection?.let {
                        Rect(
                            it.left.toFloat(),
                            it.top.toFloat(),
                            it.right.toFloat(),
                            it.bottom.toFloat(),
                        )
                    }
            selected?.let { rect ->
                val topLeft = origin + Offset(rect.left, rect.top) * scale
                val selectedSize = Size(rect.width * scale, rect.height * scale)
                drawRect(Color.Black, topLeft, selectedSize, style = Stroke(2f))
                drawRect(
                    Color.White,
                    topLeft,
                    selectedSize,
                    style = Stroke(1.5f, pathEffect = selectionDash),
                )
            }
            cursor?.let { position ->
                if (controller.tool == Tool.Brush || controller.tool == Tool.Eraser) {
                    val radius = (controller.brush.size * scale * 0.5f).coerceAtLeast(2f)
                    drawCircle(
                        Color.Black.copy(alpha = 0.55f),
                        radius + 1f,
                        position,
                        style = Stroke(1f),
                    )
                    drawCircle(Color.White.copy(alpha = 0.9f), radius, position, style = Stroke(1f))
                }
            }
        }
    }
}
