package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.domain.StudioDefaults
import app.podor.domain.Tool
import app.podor.domain.TouchGesture
import app.podor.presentation.LayerMovePreview
import app.podor.presentation.StudioController
import app.podor.ui.input.platformPenInput
import app.podor.ui.input.platformTouchInput
import kotlin.math.pow
import kotlin.math.roundToInt
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
    val density by rememberUpdatedState(LocalDensity.current.density)
    val inset by rememberUpdatedState(with(LocalDensity.current) { endInset.toPx() })
    val viewSize by remember {
        derivedStateOf { Size((fullSize.width - inset).coerceAtLeast(0f), fullSize.height) }
    }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var selectionStart by remember { mutableStateOf<Offset?>(null) }
    var selectionEnd by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(
        controller.tool,
        controller.document.revision,
        controller.document.active,
        controller.busy,
    ) {
        if (controller.tool == Tool.MoveLayer) controller.prepareLayerMove()
        else controller.cancelLayerMove(exit = true)
    }
    val tilePaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val selectionDash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) }
    val selectionPath = remember { Path() }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { fullSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(controller) {
                var drawing = false
                var activePointer: PointerId? = null
                var gesture = false
                var moveAnchor: Offset? = null
                var movePreview: LayerMovePreview? = null
                val touchGesture = TouchGesture()
                try {
                    while (currentCoroutineContext().isActive) {
                        val event = awaitPointerEventScope { awaitPointerEvent() }
                        val pen = event.platformPenInput()
                        val touch = event.platformTouchInput()
                        if (pen?.cancelled == true || touch?.cancelled == true) {
                            if (drawing) controller.end(cancel = true)
                            drawing = false
                            activePointer = null
                            gesture = false
                            selectionStart = null
                            selectionEnd = null
                            moveAnchor = null
                            controller.cancelLayerMove()
                            touchGesture.reset()
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        val stylus = pressed.firstOrNull {
                            pen != null ||
                                it.type == PointerType.Stylus ||
                                it.type == PointerType.Eraser
                        }
                        val primary =
                            stylus
                                ?: event.changes.firstOrNull { it.id == activePointer }
                                ?: event.changes.first()
                        val mouse =
                            primary.type == PointerType.Mouse && pen == null && touch == null
                        if (touch != null) {
                            val transform =
                                touchGesture.update(
                                    touch.contacts.map {
                                        it.copy(offset = primary.position + it.offset * density)
                                    }
                                )
                            if (touch.gesturing) {
                                if (drawing) controller.end(cancel = true)
                                drawing = false
                                activePointer = null
                                selectionStart = null
                                selectionEnd = null
                                if (moveAnchor != null) controller.cancelLayerMove()
                                moveAnchor = null
                                cursor = null
                                if (transform != null)
                                    controller.viewport =
                                        controller.viewport.transform(
                                            transform.center,
                                            transform.pan,
                                            transform.zoom,
                                            viewSize,
                                            controller.document,
                                            transform.rotation,
                                        )
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }
                        val position =
                            primary.position +
                                (pen?.samples?.lastOrNull()?.offset
                                    ?: touch?.samples?.lastOrNull()
                                    ?: Offset.Zero) * density
                        val pressure =
                            (pen?.samples?.lastOrNull()?.pressure
                                    ?: if (mouse) 1f else primary.pressure)
                                .coerceIn(0.05f, 1f)
                        fun samples(): List<Triple<Float, Float, Float>> = buildList {
                            if (touch != null) {
                                for (sample in touch.samples) {
                                    val point =
                                        controller.viewport.toDocument(
                                            primary.position + sample * density,
                                            viewSize,
                                            controller.document,
                                        )
                                    add(Triple(point.x, point.y, 1f))
                                }
                            } else if (pen != null) {
                                for (sample in pen.samples) {
                                    val point =
                                        controller.viewport.toDocument(
                                            primary.position + sample.offset * density,
                                            viewSize,
                                            controller.document,
                                        )
                                    add(
                                        Triple(
                                            point.x,
                                            point.y,
                                            sample.pressure.coerceIn(0.05f, 1f),
                                        )
                                    )
                                }
                            } else {
                                for (historical in primary.historical) {
                                    val point =
                                        controller.viewport.toDocument(
                                            historical.position,
                                            viewSize,
                                            controller.document,
                                        )
                                    add(Triple(point.x, point.y, pressure))
                                }
                                val point =
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                add(Triple(point.x, point.y, pressure))
                            }
                        }
                        cursor =
                            if (mouse && event.type != PointerEventType.Exit) position else null
                        if (event.type == PointerEventType.Scroll) {
                            if (drawing || selectionStart != null || moveAnchor != null) continue
                            val rotating = event.keyboardModifiers.isShiftPressed
                            val scroll =
                                if (rotating && primary.scrollDelta.y == 0f) primary.scrollDelta.x
                                else primary.scrollDelta.y
                            controller.viewport =
                                controller.viewport.transform(
                                    primary.position,
                                    Offset.Zero,
                                    if (rotating) 1f else 1.12f.pow(-scroll),
                                    viewSize,
                                    controller.document,
                                    degrees =
                                        if (rotating) -scroll * StudioDefaults.rotationStep else 0f,
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
                            if (moveAnchor != null) controller.cancelLayerMove()
                            moveAnchor = null
                            selectionStart = null
                            selectionEnd = null
                            if (drawing) {
                                controller.end(cancel = true)
                                drawing = false
                            }
                            gesture = true
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid.isSpecified) {
                                controller.viewport =
                                    controller.viewport.transform(
                                        centroid,
                                        event.calculatePan(),
                                        event.calculateZoom(),
                                        viewSize,
                                        controller.document,
                                        degrees = event.calculateRotation(),
                                    )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pressed.isEmpty()) {
                            moveAnchor?.let { anchor ->
                                if (
                                    controller.tool == Tool.MoveLayer &&
                                        controller.layerMove === movePreview
                                ) {
                                    val end =
                                        controller.viewport.toDocument(
                                            position,
                                            viewSize,
                                            controller.document,
                                        )
                                    controller.previewLayerMove(
                                        IntOffset(
                                            (end.x - anchor.x).roundToInt(),
                                            (end.y - anchor.y).roundToInt(),
                                        )
                                    )
                                    controller.commitLayerMove()
                                }
                            }
                            moveAnchor = null
                            selectionStart?.let { start ->
                                val end =
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                controller.select(start, end)
                            }
                            selectionStart = null
                            selectionEnd = null
                            if (drawing) {
                                if (
                                    position != primary.previousPosition ||
                                        (pen?.samples?.size ?: touch?.samples?.size ?: 0) > 1
                                ) {
                                    controller.points(samples())
                                }
                                controller.end()
                                drawing = false
                            }
                            activePointer = null
                            gesture = false
                            continue
                        }
                        if (gesture) continue
                        if (controller.tool == Tool.MoveLayer) {
                            if (
                                (primary.type == PointerType.Touch || touch != null) &&
                                    !controller.fingerDrawing
                            )
                                continue
                            val preview = controller.layerMove
                            val point =
                                controller.viewport.toDocument(
                                    position,
                                    viewSize,
                                    controller.document,
                                )
                            if (
                                primary.pressed &&
                                    !primary.previousPressed &&
                                    preview != null &&
                                    !preview.committing &&
                                    point.x >= 0 &&
                                    point.y >= 0 &&
                                    point.x < controller.document.width &&
                                    point.y < controller.document.height
                            ) {
                                moveAnchor = point
                                movePreview = preview
                            }
                            moveAnchor?.let { anchor ->
                                if (preview === movePreview)
                                    controller.previewLayerMove(
                                        IntOffset(
                                            (point.x - anchor.x).roundToInt(),
                                            (point.y - anchor.y).roundToInt(),
                                        )
                                    )
                            }
                            primary.consume()
                            continue
                        }
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
                        if (
                            (primary.type == PointerType.Touch || touch != null) &&
                                !controller.fingerDrawing
                        )
                            continue
                        val point =
                            controller.viewport.toDocument(
                                position,
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
                                pressure,
                                pen?.eraser == true || primary.type == PointerType.Eraser,
                            )
                            drawing = true
                        } else if (
                            drawing &&
                                primary.id == activePointer &&
                                (position != primary.previousPosition ||
                                    (pen?.samples?.size ?: touch?.samples?.size ?: 0) > 1)
                        ) {
                            controller.points(samples())
                        }
                        if (drawing) primary.consume()
                    }
                } finally {
                    if (drawing) controller.command("cancel")
                    if (moveAnchor != null) controller.cancelLayerMove()
                }
            }
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val viewport = controller.viewport
            val scale = viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
            val origin = viewport.origin(viewSize, document)
            val paper = Size(document.width.toFloat(), document.height.toFloat())
            val visible = viewport.visibleBounds(viewSize, document, size)
            withTransform({
                translate(origin.x, origin.y + 10f)
                rotate(viewport.rotation, Offset.Zero)
                scale(scale * viewport.horizontalSign, scale, Offset.Zero)
            }) {
                drawRect(Color.Black.copy(alpha = 0.2f), size = paper)
            }
            withTransform({
                translate(origin.x, origin.y)
                rotate(viewport.rotation, Offset.Zero)
                scale(scale * viewport.horizontalSign, scale, Offset.Zero)
            }) {
                clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                    drawRect(Color.White, size = paper)
                    if (controller.layerMove == null)
                        controller.frame.tiles.values.forEach { tile ->
                            val x = tile.x * tile.size
                            val y = tile.y * tile.size
                            if (
                                x >= visible.right ||
                                    y >= visible.bottom ||
                                    x + tile.size <= visible.left ||
                                    y + tile.size <= visible.top
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
        controller.layerMove?.let {
            LayerMoveOverlay(controller, it, viewSize, Modifier.matchParentSize())
        }
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val scale = controller.viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
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
                val a = controller.viewport.toView(rect.topLeft, viewSize, document)
                val b = controller.viewport.toView(rect.topRight, viewSize, document)
                val c = controller.viewport.toView(rect.bottomRight, viewSize, document)
                val d = controller.viewport.toView(rect.bottomLeft, viewSize, document)
                selectionPath.reset()
                selectionPath.moveTo(a.x, a.y)
                selectionPath.lineTo(b.x, b.y)
                selectionPath.lineTo(c.x, c.y)
                selectionPath.lineTo(d.x, d.y)
                selectionPath.close()
                drawPath(selectionPath, Color.Black, style = Stroke(2f))
                drawPath(
                    selectionPath,
                    Color.White,
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
