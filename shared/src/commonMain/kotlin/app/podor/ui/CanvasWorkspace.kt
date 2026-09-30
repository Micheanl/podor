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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.domain.*
import app.podor.domain.BrushRaster
import app.podor.domain.GradientGesture
import app.podor.domain.GradientHandle
import app.podor.domain.LayerKind
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
import app.podor.ui.input.platformCanvasPointer
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
    var quickAnchor by remember { mutableStateOf<Offset?>(null) }
    var secondaryHeld by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var selectionGesture by remember { mutableStateOf<SelectionGesture?>(null) }
    var selectionVersion by remember { mutableIntStateOf(0) }
    var lassoPaintGesture by remember { mutableStateOf<SelectionGesture?>(null) }
    var lassoPaintVersion by remember { mutableIntStateOf(0) }
    var lassoPaintEraser by remember { mutableStateOf(false) }
    var lassoPaintRevision by remember { mutableLongStateOf(-1L) }
    var lassoPaintCancellation by remember { mutableIntStateOf(-1) }
    var lassoPaintLayer by remember { mutableIntStateOf(0) }
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
        controller.selectionCancellation,
        controller.document.revision,
        controller.document.active,
    ) {
        if (
            controller.tool != Tool.LassoFill ||
                controller.selectionCancellation != lassoPaintCancellation ||
                controller.document.revision != lassoPaintRevision ||
                controller.document.active != lassoPaintLayer
        )
            lassoPaintGesture = null
    }
    LaunchedEffect(
        controller.tool,
        controller.document.revision,
        controller.document.active,
        controller.document.maskEditing,
        controller.document.activeMaskId,
        controller.document.selection,
        controller.busy,
    ) {
        if (
            controller.tool == Tool.MoveLayer ||
                controller.tool == Tool.TransformLayer ||
                (controller.tool == Tool.Select && controller.document.selection != null)
        )
            controller.prepareLayerMove()
        else controller.cancelLayerMove(exit = true)
    }
    val tilePaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val previousOnionPaint = remember { Paint().apply { isAntiAlias = false } }
    val nextOnionPaint = remember { Paint().apply { isAntiAlias = false } }
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
    val lassoPaintPath = remember { Path() }
    val lassoMaskPaint = remember {
        Paint().apply {
            isAntiAlias = false
            filterQuality = FilterQuality.Low
        }
    }
    val lassoLayerPaint = remember { Paint() }
    val lassoMaskBlend = remember { Paint().apply { blendMode = BlendMode.DstIn } }
    Box(
        modifier
            .clipToBounds()
            .focusRequester(focus)
            .testTag("canvas-workspace")
            .focusable()
            .platformCanvasPointer()
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
                var vectorStart: Offset? = null
                var vectorNode: VectorNode? = null
                var vectorOriginal: VectorObjectSpec? = null
                var vectorPen: VectorPenGesture? = null
                var vectorCancellation = controller.vectorCancellation
                var assistantHandle: Int? = null
                var assistantOriginal: DrawingAssistantSpec? = null
                var assistantCancellation = controller.assistantCancellation
                var generatorOriginal: LineGeneratorSettings? = null
                var generatorPreview: app.podor.presentation.LineGeneratorPreview? = null
                fun updateGenerator(point: Offset) {
                    val preview = controller.lineGeneratorPreview ?: return
                    if (preview !== generatorPreview) return
                    controller.updateLineGenerator(
                        if (preview.settings.kind == LineGeneratorKind.Concentration)
                            preview.settings.copy(center = point.assistantPoint())
                        else preview.settings.copy(origin = point.assistantPoint())
                    )
                }
                fun updateAssistant(point: Offset) {
                    val original = assistantOriginal ?: return
                    val handle = assistantHandle ?: return
                    controller.previewAssistant(
                        original.copy(
                            geometry =
                                original.geometry.withHandle(handle, point, controller.document)
                        )
                    )
                }
                fun cancelVectorDrag() {
                    generatorOriginal?.let {
                        if (controller.lineGeneratorPreview === generatorPreview)
                            controller.updateLineGenerator(it)
                    }
                    generatorOriginal = null
                    generatorPreview = null
                    controller.cancelAssistant()
                    assistantHandle = null
                    assistantOriginal = null
                    assistantCancellation = controller.assistantCancellation
                    controller.cancelVector()
                    vectorStart = null
                    vectorNode = null
                    vectorOriginal = null
                    vectorPen = null
                    vectorCancellation = controller.vectorCancellation
                }
                fun updateVector(point: Offset) {
                    val original = vectorOriginal
                    val node = vectorNode
                    if (original != null && node != null)
                        controller.previewVector(
                            original.copy(
                                geometry =
                                    original.geometry.withNode(node, original.localPoint(point))
                            )
                        )
                    else if (controller.vectorTool == VectorEditorTool.Pen) {
                        vectorPen?.let { pen ->
                            pen.drag(point)
                            controller.vectorPreview?.value?.let {
                                controller.previewVector(it.copy(geometry = pen.geometry()))
                            } ?: run { controller.vectorPendingPath = pen.geometry() }
                        }
                    } else
                        vectorStart?.let { start ->
                            val geometry =
                                vectorShape(controller.vectorTool, start, point) ?: return
                            if (!geometry.valid()) {
                                controller.vectorPreview?.value?.let {
                                    controller.previewVector(it.copy(geometry = geometry))
                                }
                                return
                            }
                            if (geometry.valid() && (point - start).getDistanceSquared() > 0f) {
                                val current = controller.vectorPreview?.value
                                val value =
                                    current?.copy(geometry = geometry)
                                        ?: VectorObjectSpec(
                                            controller.vectorTool.label,
                                            geometry = geometry,
                                            style =
                                                if (controller.vectorTool == VectorEditorTool.Line)
                                                    VectorStyle(
                                                        stroke =
                                                            VectorStroke(
                                                                vectorRgba(controller.brush.color),
                                                                controller.brush.size,
                                                            )
                                                    )
                                                else
                                                    VectorStyle(
                                                        fill = vectorRgba(controller.brush.color)
                                                    ),
                                        )
                                if (current == null) controller.beginVectorEdit(value)
                                else controller.previewVector(value)
                            }
                        }
                }
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
                        if (
                            controller.lineGeneratorPreview !== generatorPreview ||
                                controller.tool != Tool.LineGenerator
                        ) {
                            generatorOriginal = null
                            generatorPreview = null
                        }
                        if (
                            assistantCancellation != controller.assistantCancellation ||
                                controller.tool != Tool.Assistant
                        ) {
                            assistantHandle = null
                            assistantOriginal = null
                            assistantCancellation = controller.assistantCancellation
                        }
                        if (
                            vectorCancellation != controller.vectorCancellation ||
                                controller.tool != Tool.Vector
                        ) {
                            vectorStart = null
                            vectorNode = null
                            vectorOriginal = null
                            vectorPen = null
                            vectorCancellation = controller.vectorCancellation
                        }
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
                        if (
                            lassoPaintGesture != null &&
                                (controller.tool != Tool.LassoFill ||
                                    controller.selectionCancellation != lassoPaintCancellation ||
                                    controller.document.revision != lassoPaintRevision ||
                                    controller.document.active != lassoPaintLayer)
                        ) {
                            lassoPaintGesture = null
                        }
                        val pen = event.platformPenInput()
                        val touch = event.platformTouchInput()
                        if (pen?.cancelled == true || touch?.cancelled == true) {
                            cancelVectorDrag()
                            cancelGradientDrag()
                            cancelTransformDrag()
                            if (drawing) controller.end(cancel = true)
                            drawing = false
                            activePointer = null
                            gesture = false
                            selectionGesture = null
                            lassoPaintGesture = null
                            moveAnchor = null
                            controller.cancelLayerMove()
                            touchGesture.reset()
                            continue
                        }
                        if (referenceInput.consumed(event)) {
                            cursor = null
                            continue
                        }
                        if (controller.animationPlaying || controller.animationTransition) {
                            cursor = null
                            event.changes.forEach { it.consume() }
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
                                ?: pressed.firstOrNull()
                                ?: event.changes.first()
                        val mouse =
                            primary.type == PointerType.Mouse && pen == null && touch == null
                        if (mouse && (event.buttons.isSecondaryPressed || secondaryHeld)) {
                            if (
                                event.buttons.isSecondaryPressed &&
                                    !secondaryHeld &&
                                    !drawing &&
                                    lassoPaintGesture == null
                            )
                                quickAnchor = primary.position
                            secondaryHeld = event.buttons.isSecondaryPressed
                            primary.consume()
                            continue
                        }
                        if (mouse && primary.pressed && !event.buttons.isPrimaryPressed) continue
                        if (touch != null) {
                            val transform =
                                touchGesture.update(
                                    touch.contacts.map {
                                        it.copy(offset = primary.position + it.offset * density)
                                    }
                                )
                            if (touch.gesturing) {
                                cancelVectorDrag()
                                cancelGradientDrag()
                                cancelTransformDrag()
                                if (drawing) controller.end(cancel = true)
                                drawing = false
                                activePointer = null
                                selectionGesture = null
                                lassoPaintGesture = null
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
                                .coerceIn(0f, 1f)
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
                                            sample.pressure.coerceIn(0f, 1f),
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
                            if (pen != null && event.type != PointerEventType.Exit)
                                position
                            else null
                        if (event.type == PointerEventType.Scroll) {
                            if (
                                drawing ||
                                    selectionGesture != null ||
                                    lassoPaintGesture != null ||
                                    moveAnchor != null ||
                                    transformDrag != null ||
                                    gradientDrag != null ||
                                    assistantHandle != null ||
                                    generatorOriginal != null
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
                            lassoPaintGesture != null &&
                                stylus != null &&
                                stylus.id != activePointer
                        )
                            lassoPaintGesture = null
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
                            cancelVectorDrag()
                            cancelGradientDrag()
                            cancelTransformDrag()
                            if (moveAnchor != null) controller.cancelLayerMove()
                            moveAnchor = null
                            selectionGesture = null
                            lassoPaintGesture = null
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
                            if (generatorOriginal != null) {
                                updateGenerator(
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                )
                                generatorOriginal = null
                                generatorPreview = null
                            }
                            if (assistantHandle != null) {
                                updateAssistant(
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                )
                                controller.commitAssistant()
                                assistantHandle = null
                                assistantOriginal = null
                            }
                            if (vectorStart != null || vectorNode != null) {
                                updateVector(
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                )
                                if (controller.vectorTool != VectorEditorTool.Pen)
                                    controller.commitVector()
                                vectorStart = null
                                vectorNode = null
                                vectorOriginal = null
                            }
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
                                    (controller.tool == Tool.MoveLayer ||
                                        controller.tool == Tool.Select) &&
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
                            lassoPaintGesture?.let { draft ->
                                for (sample in samples()) draft.add(
                                    Offset(sample.first, sample.second)
                                )
                                draft.selection()?.points?.let {
                                    controller.fillLasso(it, lassoPaintEraser)
                                }
                            }
                            lassoPaintGesture = null
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
                        if (controller.tool == Tool.LineGenerator) {
                            val preview = controller.lineGeneratorPreview ?: continue
                            if (preview.committing || controller.busy) continue
                            if (primary.pressed && !primary.previousPressed) {
                                activePointer = primary.id
                                generatorPreview = preview
                                generatorOriginal = preview.settings
                            }
                            if (generatorOriginal != null)
                                updateGenerator(
                                    controller.viewport.toDocument(
                                        position,
                                        viewSize,
                                        controller.document,
                                    )
                                )
                            primary.consume()
                            continue
                        }
                        if (controller.tool == Tool.Assistant) {
                            if (controller.busy || controller.assistantPreview?.committing == true)
                                continue
                            val point =
                                controller.viewport.toDocument(
                                    position,
                                    viewSize,
                                    controller.document,
                                )
                            if (primary.pressed && !primary.previousPressed) {
                                val selected = controller.selectedAssistant ?: continue
                                val original = selected.spec()
                                val handles = original.geometry.handles(controller.document)
                                val closest =
                                    handles.indices.minByOrNull {
                                        (handles[it] - point).getDistanceSquared()
                                    }
                                val radius =
                                    StudioTheme.transformHitRadius.value * density /
                                        controller.viewport
                                            .scale(viewSize, controller.document)
                                            .coerceAtLeast(0.01f)
                                if (
                                    closest != null &&
                                        (handles[closest] - point).getDistanceSquared() <=
                                            radius * radius &&
                                        controller.beginAssistantEdit(selected.id)
                                ) {
                                    activePointer = primary.id
                                    assistantHandle = closest
                                    assistantOriginal = original
                                }
                            } else if (assistantHandle != null) updateAssistant(point)
                            primary.consume()
                            continue
                        }
                        if (controller.tool == Tool.Vector) {
                            val active =
                                controller.document.layers.firstOrNull {
                                    it.id == controller.document.active
                                }
                            if (
                                active?.kind != LayerKind.Vector ||
                                    active.effectiveLocked ||
                                    !active.effectiveVisible ||
                                    controller.document.maskEditing ||
                                    controller.document.selection != null ||
                                    controller.busy
                            )
                                continue
                            if (
                                (primary.type == PointerType.Touch || touch != null) &&
                                    !controller.fingerDrawing
                            )
                                continue
                            if (
                                vectorPen != null &&
                                    controller.vectorPreview == null &&
                                    controller.vectorPendingPath == null
                            )
                                vectorPen = null
                            val point =
                                controller.viewport.toDocument(
                                    position,
                                    viewSize,
                                    controller.document,
                                )
                            if (primary.pressed && !primary.previousPressed) {
                                activePointer = primary.id
                                vectorStart = point
                                if (controller.vectorTool == VectorEditorTool.Edit) {
                                    vectorStart = null
                                    val selected = controller.selectedVectorObject
                                    val original =
                                        selected
                                            ?.takeIf { it.revision == controller.document.revision }
                                            ?.`object`
                                    val radius =
                                        StudioTheme.transformHitRadius.value * density /
                                            controller.viewport
                                                .scale(viewSize, controller.document)
                                                .coerceAtLeast(0.01f)
                                    val closest =
                                        original?.geometry?.nodes()?.minByOrNull {
                                            (original.worldPoint(it.position) - point)
                                                .getDistanceSquared()
                                        }
                                    if (
                                        original != null &&
                                            closest != null &&
                                            (original.worldPoint(closest.position) - point)
                                                .getDistance() <= radius &&
                                            controller.beginVectorEdit(original, selected.objectId)
                                    ) {
                                        vectorNode = closest
                                        vectorOriginal = original
                                    } else controller.pickVectorObject(point, radius)
                                } else if (controller.vectorTool == VectorEditorTool.Pen) {
                                    val draft =
                                        vectorPen ?: VectorPenGesture().also { vectorPen = it }
                                    draft.begin(point)
                                    val current = controller.vectorPreview?.value
                                    val geometry = draft.geometry()
                                    if (current == null && geometry.valid()) {
                                        controller.vectorPendingPath = null
                                        controller.beginVectorEdit(
                                            VectorObjectSpec(
                                                "钢笔路径",
                                                geometry = geometry,
                                                style =
                                                    VectorStyle(
                                                        stroke =
                                                            VectorStroke(
                                                                vectorRgba(controller.brush.color),
                                                                controller.brush.size,
                                                            )
                                                    ),
                                            )
                                        )
                                    } else if (current != null)
                                        controller.previewVector(current.copy(geometry = geometry))
                                    else controller.vectorPendingPath = geometry
                                }
                            }
                            if (primary.pressed && primary.id == activePointer) updateVector(point)
                            primary.consume()
                            continue
                        }
                        if (
                            pen?.barrel == true &&
                                !drawing &&
                                primary.pressed &&
                                !primary.previousPressed
                        ) {
                            val picked =
                                controller.viewport.toDocument(
                                    position,
                                    viewSize,
                                    controller.document,
                                )
                            if (
                                picked.x >= 0 &&
                                    picked.y >= 0 &&
                                    picked.x < controller.document.width &&
                                    picked.y < controller.document.height
                            ) {
                                controller.command("pick") {
                                    put("x", picked.x.toInt())
                                    put("y", picked.y.toInt())
                                }
                            }
                            gesture = true
                            primary.consume()
                            continue
                        }
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
                        val selection = controller.document.selection
                        val selectionPoint =
                            controller.viewport.toDocument(position, viewSize, controller.document)
                        val movingSelection =
                            controller.tool == Tool.Select &&
                                selection != null &&
                                controller.selectionMode == SelectionMode.Replace &&
                                (moveAnchor != null ||
                                    (selectionPoint.x >= selection.left &&
                                        selectionPoint.x < selection.right &&
                                        selectionPoint.y >= selection.top &&
                                        selectionPoint.y < selection.bottom))
                        if (controller.tool == Tool.MoveLayer || movingSelection) {
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
                        if (lassoPaintGesture != null) {
                            for (sample in samples()) lassoPaintGesture?.add(
                                Offset(sample.first, sample.second)
                            )
                            lassoPaintVersion++
                            primary.consume()
                            continue
                        }
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
                            val active =
                                controller.document.layers.firstOrNull {
                                    it.id == controller.document.active
                                }
                            if (
                                controller.tool != Tool.Select &&
                                    (active?.effectiveVisible != true ||
                                        active.effectiveLocked ||
                                        (active.kind != LayerKind.Raster &&
                                            !controller.document.maskEditing))
                            )
                                continue
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
                            if (controller.tool == Tool.LassoFill) {
                                lassoPaintCancellation = controller.selectionCancellation
                                lassoPaintRevision = controller.document.revision
                                lassoPaintLayer = controller.document.active
                                lassoPaintGesture =
                                    SelectionGesture(
                                        SelectionKind.Lasso,
                                        point,
                                        controller.document.width,
                                        controller.document.height,
                                        StudioDefaults.selectionSampleDistance / selectionScale,
                                    )
                                lassoPaintEraser =
                                    controller.lassoErase ||
                                        pen?.eraser == true ||
                                        primary.type == PointerType.Eraser
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
                                (pen != null ||
                                    position != primary.previousPosition ||
                                    (pen?.samples?.size ?: touch?.samples?.size ?: 0) > 1)
                        ) {
                            controller.points(samples())
                        }
                        if (drawing) primary.consume()
                    }
                } finally {
                    cancelVectorDrag()
                    cancelGradientDrag()
                    cancelTransformDrag()
                    if (drawing) controller.command("cancel")
                    if (moveAnchor != null) controller.cancelLayerMove()
                    lassoPaintGesture = null
                }
            }
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val document = controller.document
            val viewport = controller.viewport
            tilePaint.filterQuality =
                if (
                    controller.brush.preset.raster != BrushRaster.Antialiased ||
                        controller.preferences.canvasGrid.pixels
                )
                    FilterQuality.None
                else FilterQuality.Low
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
                    drawCanvasBackground(
                        controller.preferences.canvasBackground,
                        checker,
                        paper,
                        scale,
                    )
                    if (!controller.animationPlaying) {
                        previousOnionPaint.colorFilter = ColorFilter.tint(StudioTheme.onionPrevious)
                        nextOnionPaint.colorFilter = ColorFilter.tint(StudioTheme.onionNext)
                        for ((onion, paint) in
                            listOf(
                                controller.onionPrevious to previousOnionPaint,
                                controller.onionNext to nextOnionPaint,
                            )) {
                            paint.alpha = StudioDefaults.onionOpacity
                            paint.filterQuality = tilePaint.filterQuality
                            onion?.tiles?.values?.forEach { tile ->
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
                                        paint,
                                    )
                            }
                        }
                    }
                    if (
                        (controller.layerMove == null ||
                            (controller.layerMove?.selection != null &&
                                controller.layerMove?.offset == IntOffset.Zero)) &&
                            controller.gradientPreview == null ||
                            controller.layerMove?.canonical != null ||
                            controller.gradientPreview?.canonical != null
                    )
                        (controller.animationDisplayFrame
                                ?: controller.lineGeneratorPreview?.canonical?.frame
                                ?: controller.vectorPreview?.canonical?.frame
                                ?: controller.layerMove?.canonical?.frame
                                ?: controller.gradientPreview?.canonical?.frame
                                ?: controller.adjustmentPreview?.takeUnless { it.comparing }?.frame
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
        if (!controller.animationPlaying && !controller.animationTransition) {
            controller.layerMove
                ?.takeUnless { it.selection != null && it.offset == IntOffset.Zero }
                ?.let {
                    if (it.canonical == null)
                        LayerMoveOverlay(controller, it, viewSize, Modifier.matchParentSize())
                    if (it.transform != null)
                        LayerTransformHandles(controller, it, viewSize, Modifier.matchParentSize())
                }
            controller.gradientPreview?.let {
                if (it.canonical == null)
                    GradientOverlay(controller, it, viewSize, Modifier.matchParentSize())
            }
            PixelGridOverlay(controller, viewSize, Modifier.matchParentSize())
            if (controller.tool == Tool.Vector)
                VectorHandles(controller, viewSize, Modifier.matchParentSize())
            if (controller.document.maxDrawingAssistants > 0)
                AssistantOverlay(controller, viewSize, Modifier.matchParentSize())
            controller.lineGeneratorPreview?.let { preview ->
                val position =
                    if (preview.settings.kind == LineGeneratorKind.Concentration)
                        preview.settings.center
                    else preview.settings.origin
                val handleColor = StudioTheme.text
                val handleFill = StudioTheme.panel
                Canvas(Modifier.matchParentSize()) {
                    val point =
                        controller.viewport.toView(position.offset(), viewSize, controller.document)
                    val radius = StudioTheme.assistantHandleRadius.toPx()
                    drawCircle(handleFill, radius, point)
                    drawCircle(
                        handleColor,
                        radius,
                        point,
                        style = Stroke(StudioTheme.assistantGuideWidth.toPx()),
                    )
                }
            }
            SymmetryGuides(controller, viewSize, Modifier.matchParentSize())
            lassoPaintGesture?.let { draft ->
                val selected = controller.document.selection
                val selectionClip =
                    remember(selected) {
                        selected
                            ?.takeUnless { it.combined || it.raster }
                            ?.let {
                                Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    val bounds =
                                        Rect(
                                            it.left.toFloat(),
                                            it.top.toFloat(),
                                            it.right.toFloat(),
                                            it.bottom.toFloat(),
                                        )
                                    when (it.kind) {
                                        SelectionKind.Ellipse -> addOval(bounds)
                                        SelectionKind.Lasso -> {
                                            it.points.forEachIndexed { index, point ->
                                                if (index == 0) moveTo(point.x, point.y)
                                                else lineTo(point.x, point.y)
                                            }
                                            close()
                                        }
                                        else -> addRect(bounds)
                                    }
                                }
                            }
                    }
                Canvas(Modifier.matchParentSize().graphicsLayer()) {
                    lassoPaintVersion
                    val document = controller.document
                    val viewport = controller.viewport
                    val scale = viewport.scale(viewSize, document)
                    if (scale <= 0f || selected?.empty == true) return@Canvas
                    lassoPaintPath.reset()
                    lassoPaintPath.fillType = PathFillType.EvenOdd
                    draft.points.forEachIndexed { index, point ->
                        if (index == 0) lassoPaintPath.moveTo(point.x, point.y)
                        else lassoPaintPath.lineTo(point.x, point.y)
                    }
                    lassoPaintPath.lineTo(draft.end.x, draft.end.y)
                    lassoPaintPath.close()
                    val origin = viewport.origin(viewSize, document)
                    val tint =
                        if (lassoPaintEraser) StudioTheme.muted else Color(controller.brush.color)
                    fun drawPreview() {
                        if (!lassoPaintEraser)
                            drawPath(
                                lassoPaintPath,
                                tint.copy(
                                    alpha = StudioTheme.lassoPreviewAlpha * controller.brush.opacity
                                ),
                            )
                        drawPath(
                            lassoPaintPath,
                            tint.copy(alpha = StudioTheme.lassoOutlineAlpha),
                            style = Stroke(StudioTheme.lassoOutlineWidth.toPx() / scale),
                        )
                    }
                    withTransform({
                        translate(origin.x, origin.y)
                        rotate(viewport.rotation, Offset.Zero)
                        scale(scale * viewport.horizontalSign, scale, Offset.Zero)
                    }) {
                        val bounds =
                            selected?.let {
                                Rect(
                                    it.left.toFloat(),
                                    it.top.toFloat(),
                                    it.right.toFloat(),
                                    it.bottom.toFloat(),
                                )
                            }
                                ?: Rect(
                                    Offset.Zero,
                                    Size(document.width.toFloat(), document.height.toFloat()),
                                )
                        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                            if (selectionClip != null) clipPath(selectionClip) { drawPreview() }
                            else if (selected == null) drawPreview()
                            else {
                                val mask = controller.selectionOutline?.fillMask.orEmpty()
                                if (mask.isEmpty()) return@clipRect
                                val visible = viewport.visibleBounds(viewSize, document, size)
                                val canvas = drawContext.canvas
                                canvas.saveLayer(bounds, lassoLayerPaint)
                                drawPreview()
                                canvas.saveLayer(bounds, lassoMaskBlend)
                                for (tile in mask) {
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
                                            lassoMaskPaint,
                                        )
                                }
                                canvas.restore()
                                canvas.restore()
                            }
                        }
                    }
                }
            }
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
                            val move = controller.layerMove?.offset
                            translate((move?.x ?: 0).toFloat(), (move?.y ?: 0).toFloat()) {
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
                }
                cursor?.let { position ->
                    if (
                        controller.tool == Tool.Brush ||
                            controller.tool == Tool.Eraser ||
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
                            drawCircle(
                                Color.White.copy(alpha = 0.9f),
                                radius,
                                at,
                                style = Stroke(1f),
                            )
                        }
                        if (
                            controller.tool == Tool.Smudge ||
                                controller.symmetry.mode == SymmetryMode.Off
                        )
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
            quickAnchor?.let {
                QuickBrushPopup(controller, it) {
                    quickAnchor = null
                    secondaryHeld = false
                }
            }
        }
    }
}
