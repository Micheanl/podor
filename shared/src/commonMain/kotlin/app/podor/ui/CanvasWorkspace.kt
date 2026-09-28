package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.podor.domain.GradientGesture
import app.podor.domain.GradientHandle
import app.podor.domain.SelectionGesture
import app.podor.domain.SelectionKind
import app.podor.domain.SelectionMode
import app.podor.domain.StudioDefaults
import app.podor.domain.SymmetryMode
import app.podor.domain.Tool
import app.podor.domain.TouchGesture
import app.podor.domain.TransformGesture
import app.podor.domain.transformHandle
import app.podor.presentation.GradientPreview
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
    val checker = rememberCanvasChecker()
    var fullSize by remember { mutableStateOf(Size.Zero) }
    val focus = remember { FocusRequester() }
    val referenceInput = remember { ReferenceInput() }
    val density by rememberUpdatedState(LocalDensity.current.density)
    val inset by rememberUpdatedState(with(LocalDensity.current) { endInset.toPx() })
    val viewSize by remember {
        derivedStateOf { Size((fullSize.width - inset).coerceAtLeast(0f), fullSize.height) }
    }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var selectionGesture by remember { mutableStateOf<SelectionGesture?>(null) }
    var selectionVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(
        controller.tool,
        controller.selectionKind,
        controller.selectionCancellation,
        controller.document.revision,
    ) {
        selectionGesture = null
    }
    LaunchedEffect(
        controller.tool,
        controller.document.revision,
        controller.document.active,
        controller.busy,
    ) {
        if (controller.tool == Tool.MoveLayer || controller.tool == Tool.TransformLayer)
            controller.prepareLayerMove()
        else controller.cancelLayerMove(exit = true)
    }
    val tilePaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    LaunchedEffect(
        controller.tool,
        controller.document.revision,
        controller.document.active,
        controller.document.selection,
        controller.busy,
    ) {
        if (controller.tool == Tool.Gradient) controller.prepareGradient()
        else controller.cancelGradient()
    }
    val selectionScale by remember {
        derivedStateOf {
            controller.viewport.scale(viewSize, controller.document).coerceAtLeast(0.01f)
        }
    }
    val selectionDash =
        remember(selectionScale) {
            PathEffect.dashPathEffect(floatArrayOf(5f / selectionScale, 5f / selectionScale))
        }
    val selectionPath = remember { Path() }
    Box(
        modifier
            .clipToBounds()
            .focusRequester(focus)
            .focusable()
            .onSizeChanged { fullSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(controller) {
                var drawing = false
                var activePointer: PointerId? = null
                var gesture = false
                var selectionCancellation = controller.selectionCancellation
                var selectionRevision = controller.document.revision
                var moveAnchor: Offset? = null
                var movePreview: LayerMovePreview? = null
                var transformDrag: TransformGesture? = null
                var transformPreview: LayerMovePreview? = null
                var gradientDrag: GradientGesture? = null
                var gradientPreview: GradientPreview? = null
                fun cancelGradientDrag() {
                    if (controller.gradientPreview === gradientPreview)
                        gradientDrag?.let { controller.previewGradient(it.before) }
                    gradientDrag = null
                }
                fun cancelTransformDrag() {
                    if (controller.layerMove === transformPreview)
                        transformDrag?.let { controller.previewLayerTransform(it.initial) }
                    transformDrag = null
                }
                val touchGesture = TouchGesture()
                try {
                    while (currentCoroutineContext().isActive) {
                        val event = awaitPointerEventScope { awaitPointerEvent() }
                        if (event.changes.any { it.pressed && !it.previousPressed })
                            focus.requestFocus()
                        if (
                            selectionGesture != null &&
                                (controller.tool != Tool.Select ||
                                    controller.selectionKind != selectionGesture?.kind ||
                                    controller.selectionCancellation != selectionCancellation ||
                                    controller.document.revision != selectionRevision)
                        ) {
                            selectionGesture = null
                        }
                        val pen = event.platformPenInput()
                        val touch = event.platformTouchInput()
                        if (pen?.cancelled == true || touch?.cancelled == true) {
                            cancelGradientDrag()
                            cancelTransformDrag()
                            if (drawing) controller.end(cancel = true)
                            drawing = false
                            activePointer = null
                            gesture = false
                            selectionGesture = null
                            moveAnchor = null
                            controller.cancelLayerMove()
                            touchGesture.reset()
                            continue
                        }
                        if (referenceInput.consumed(event)) {
                            cursor = null
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
                                cancelGradientDrag()
                                cancelTransformDrag()
                                if (drawing) controller.end(cancel = true)
                                drawing = false
                                activePointer = null
                                selectionGesture = null
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
                            if (
                                drawing ||
                                    selectionGesture != null ||
                                    moveAnchor != null ||
                                    transformDrag != null ||
                                    gradientDrag != null
                            )
                                continue
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
                            cancelGradientDrag()
                            cancelTransformDrag()
                            if (moveAnchor != null) controller.cancelLayerMove()
                            moveAnchor = null
                            selectionGesture = null
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
                            gradientDrag?.let {
                                if (
                                    controller.tool == Tool.Gradient &&
                                        controller.gradientPreview === gradientPreview
                                ) {
                                    val point =
                                        controller.viewport.toDocument(
                                            position,
                                            viewSize,
                                            controller.document,
                                        )
                                    val line =
                                        it.update(point, event.keyboardModifiers.isShiftPressed)
                                    controller.previewGradient(
                                        if (line.valid()) line else it.before
                                    )
                                }
                            }
                            gradientDrag = null
                            transformDrag?.let {
                                if (
                                    controller.tool == Tool.TransformLayer &&
                                        controller.layerMove === transformPreview
                                ) {
                                    val point =
                                        controller.viewport.toDocument(
                                            position,
                                            viewSize,
                                            controller.document,
                                        )
                                    controller.previewLayerTransform(
                                        it.update(
                                            point,
                                            transformPreview?.proportional == true ||
                                                event.keyboardModifiers.isShiftPressed,
                                            event.keyboardModifiers.isShiftPressed,
                                        )
                                    )
                                }
                            }
                            transformDrag = null
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
                            selectionGesture?.let { draft ->
                                for (sample in samples()) draft.add(
                                    Offset(sample.first, sample.second),
                                    event.keyboardModifiers.isShiftPressed,
                                )
                                if (draft.kind == SelectionKind.MagicWand)
                                    controller.selectColor(draft.start)
                                else controller.select(draft.selection())
                            }
                            selectionGesture = null
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
                        if (controller.tool == Tool.Gradient) {
                            if (
                                (primary.type == PointerType.Touch || touch != null) &&
                                    !controller.fingerDrawing
                            )
                                continue
                            val preview = controller.gradientPreview
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
                                    !preview.committing
                            ) {
                                val radius =
                                    StudioTheme.transformHitRadius.value * density /
                                        controller.viewport
                                            .scale(viewSize, controller.document)
                                            .coerceAtLeast(0.01f)
                                val line = preview.line
                                val handle =
                                    when {
                                        line != null &&
                                            (point - line.start).getDistance() <= radius ->
                                            GradientHandle.Start
                                        line != null &&
                                            (point - line.end).getDistance() <= radius ->
                                            GradientHandle.End
                                        else -> GradientHandle.New
                                    }
                                gradientDrag = GradientGesture(line, point, handle)
                                gradientPreview = preview
                                activePointer = primary.id
                            }
                            if (preview === gradientPreview && preview?.committing == false)
                                gradientDrag?.let {
                                    controller.previewGradient(
                                        it.update(point, event.keyboardModifiers.isShiftPressed)
                                    )
                                }
                            primary.consume()
                            continue
                        }
                        if (controller.tool == Tool.TransformLayer) {
                            if (
                                (primary.type == PointerType.Touch || touch != null) &&
                                    !controller.fingerDrawing
                            )
                                continue
                            val preview = controller.layerMove
                            val source = preview?.sourceBounds
                            val value = preview?.transform
                            val point =
                                controller.viewport.toDocument(
                                    position,
                                    viewSize,
                                    controller.document,
                                )
                            if (
                                primary.pressed &&
                                    !primary.previousPressed &&
                                    source != null &&
                                    value != null &&
                                    !preview.committing
                            ) {
                                val scale =
                                    controller.viewport
                                        .scale(viewSize, controller.document)
                                        .coerceAtLeast(0.01f)
                                val handle =
                                    transformHandle(
                                        source,
                                        value,
                                        point,
                                        StudioTheme.transformHitRadius.value * density / scale,
                                        StudioTheme.transformRotationGap.value * density / scale,
                                    )
                                transformDrag = handle?.let {
                                    TransformGesture(source, value, it, point)
                                }
                                transformPreview = preview
                                activePointer = primary.id
                            }
                            if (preview === transformPreview && preview?.committing == false)
                                transformDrag?.let {
                                    controller.previewLayerTransform(
                                        it.update(
                                            point,
                                            preview.proportional ||
                                                event.keyboardModifiers.isShiftPressed,
                                            event.keyboardModifiers.isShiftPressed,
                                        )
                                    )
                                }
                            primary.consume()
                            continue
                        }
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
                        if (selectionGesture != null) {
                            if (selectionGesture?.kind == SelectionKind.MagicWand) {
                                primary.consume()
                                continue
                            }
                            for (sample in samples()) selectionGesture?.add(
                                Offset(sample.first, sample.second),
                                event.keyboardModifiers.isShiftPressed,
                            )
                            selectionVersion++
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
                                selectionCancellation = controller.selectionCancellation
                                selectionRevision = controller.document.revision
                                selectionGesture =
                                    SelectionGesture(
                                        controller.selectionKind,
                                        point,
                                        controller.document.width,
                                        controller.document.height,
                                        StudioDefaults.selectionSampleDistance / selectionScale,
                                    )
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
                    cancelGradientDrag()
                    cancelTransformDrag()
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
                    drawCanvasBackground(controller.preferences.canvasBackground, checker, paper, scale)
                    if (controller.layerMove == null && controller.gradientPreview == null)
                        (controller.adjustmentPreview?.takeUnless { it.comparing }?.frame
                                ?: controller.frame)
                            .tiles
                            .values
                            .forEach { tile ->
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
            if (it.transform != null)
                LayerTransformHandles(controller, it, viewSize, Modifier.matchParentSize())
        }
        controller.gradientPreview?.let {
            GradientOverlay(controller, it, viewSize, Modifier.matchParentSize())
        }
        SymmetryGuides(controller, viewSize, Modifier.matchParentSize())
        if (
            selectionGesture == null ||
                selectionGesture?.kind == SelectionKind.MagicWand ||
                controller.selectionMode != SelectionMode.Replace
        )
            CachedSelectionOverlay(controller, viewSize, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val scale = controller.viewport.scale(viewSize, document)
            if (scale <= 0f) return@Canvas
            selectionVersion
            val draft = selectionGesture
            val selected =
                if (draft != null) {
                    if (draft.kind == SelectionKind.Lasso) null else draft.selection()
                } else document.selection?.takeUnless { it.combined || it.raster || it.empty }
            val kind = draft?.kind ?: selected?.kind
            selectionPath.reset()
            var outlineLength = 0f
            if (kind == SelectionKind.Lasso) {
                val points = draft?.points ?: selected?.points.orEmpty()
                points.forEachIndexed { index, point ->
                    if (index == 0) selectionPath.moveTo(point.x, point.y)
                    else {
                        selectionPath.lineTo(point.x, point.y)
                        val previous = points[index - 1]
                        outlineLength +=
                            Offset(point.x - previous.x, point.y - previous.y).getDistance()
                    }
                }
                draft?.let { selectionPath.lineTo(it.end.x, it.end.y) }
                if (points.isNotEmpty()) {
                    val last = Offset(points.last().x, points.last().y)
                    val end = draft?.end ?: last
                    outlineLength +=
                        (end - last).getDistance() +
                            (end - Offset(points.first().x, points.first().y)).getDistance()
                }
                selectionPath.close()
            } else
                selected?.let {
                    val rect =
                        Rect(
                            it.left.toFloat(),
                            it.top.toFloat(),
                            it.right.toFloat(),
                            it.bottom.toFloat(),
                        )
                    if (kind == SelectionKind.Ellipse) selectionPath.addOval(rect)
                    else selectionPath.addRect(rect)
                }
            if (kind != null) {
                val viewport = controller.viewport
                val origin = viewport.origin(viewSize, document)
                withTransform({
                    translate(origin.x, origin.y)
                    rotate(viewport.rotation, Offset.Zero)
                    scale(scale * viewport.horizontalSign, scale, Offset.Zero)
                }) {
                    clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat()) {
                        drawPath(selectionPath, Color.Black, style = Stroke(2f / scale))
                        drawPath(
                            selectionPath,
                            Color.White,
                            style =
                                Stroke(
                                    1.5f / scale,
                                    pathEffect =
                                        if (
                                            outlineLength * scale <=
                                                StudioTheme.selectionDashLengthLimit
                                        )
                                            selectionDash
                                        else null,
                                ),
                        )
                    }
                }
            }
            cursor?.let { position ->
                if (
                    controller.tool == Tool.Brush || controller.tool == Tool.Eraser ||
                        controller.tool == Tool.Smudge
                ) {
                    val radius = (controller.brush.size * scale * 0.5f).coerceAtLeast(2f)
                    fun cursor(at: Offset) {
                        drawCircle(
                            Color.Black.copy(alpha = 0.55f),
                            radius + 1f,
                            at,
                            style = Stroke(1f),
                        )
                        drawCircle(Color.White.copy(alpha = 0.9f), radius, at, style = Stroke(1f))
                    }
                    if (controller.tool == Tool.Smudge || controller.symmetry.mode == SymmetryMode.Off)
                        cursor(position)
                    else {
                        val point = controller.viewport.toDocument(position, viewSize, document)
                        controller.symmetry.forEachPoint(point, document) {
                            cursor(controller.viewport.toView(it, viewSize, document))
                        }
                    }
                }
            }
        }
        CanvasReferences(controller, viewSize, Modifier.matchParentSize(), referenceInput)
    }
}
