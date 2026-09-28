package app.podor.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import app.podor.domain.LayerInfo
import app.podor.presentation.StudioController
import app.podor.ui.input.platformPenInput
import app.podor.ui.input.platformTouchInput
import kotlin.math.abs
import kotlinx.serialization.json.put

private class LayerDrag(val id: Int, val grabY: Float, initial: Offset) {
    var position by mutableStateOf(initial)
}

@Composable
internal fun LayerList(
    controller: StudioController,
    layers: List<LayerInfo>,
    enabled: Boolean,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val ids = remember(layers) { layers.map { it.id } }
    var revealedLayer by rememberSaveable { mutableIntStateOf(0) }
    var revealedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val revision = controller.document.revision
    var drag by remember { mutableStateOf<LayerDrag?>(null) }
    val density = LocalDensity.current
    val previewStart = with(density) { StudioTheme.layerPreviewInset.toPx() }
    val previewEnd = previewStart + with(density) { StudioTheme.layerPreviewSize.toPx() }
    val rowPadding = with(density) { StudioTheme.layerRowPadding.toPx() }
    val edge = with(density) { StudioTheme.layerDragEdge.toPx() }
    val speed = with(density) { StudioTheme.layerDragSpeed.toPx() }
    val insertion by remember {
        derivedStateOf {
            drag?.let { active ->
                val layout = list.layoutInfo
                val items = layout.visibleItemsInfo
                if (
                    active.position.x !in 0f..layout.viewportSize.width.toFloat() || items.isEmpty()
                )
                    null
                else {
                    val next = items.firstOrNull { active.position.y < it.offset + it.size / 2f }
                    if (next != null) next.index to next.offset.toFloat()
                    else
                        (items.last().index + 1) to
                            (items.last().offset + items.last().size).toFloat()
                }
            }
        }
    }
    LaunchedEffect(controller.document.active, ids) {
        val index = ids.indexOf(controller.document.active)
        if (index >= 0 && (revealedLayer != controller.document.active || revealedIndex != index)) {
            val layout = list.layoutInfo
            val visible =
                layout.visibleItemsInfo.any {
                    it.index == index &&
                        it.offset >= layout.viewportStartOffset &&
                        it.offset + it.size <= layout.viewportEndOffset
                }
            if (!visible) {
                if (layout.visibleItemsInfo.isEmpty()) list.scrollToItem(index)
                else list.animateScrollToItem(index)
            }
            revealedLayer = controller.document.active
            revealedIndex = index
        }
    }
    LaunchedEffect(drag) {
        val active = drag ?: return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val layout = list.layoutInfo
            if (active.position.x !in 0f..layout.viewportSize.width.toFloat()) continue
            val y = active.position.y
            val height = layout.viewportSize.height.toFloat()
            val amount =
                when {
                    y < edge -> ((y - edge) / edge).coerceAtLeast(-1f)
                    y > height - edge -> ((y - height + edge) / edge).coerceAtMost(1f)
                    else -> 0f
                }
            if (amount != 0f) list.scrollBy(amount * speed * elapsed)
        }
    }
    Box(modifier.clipToBounds()) {
        LazyColumn(
            Modifier.fillMaxSize().pointerInput(ids, enabled, revision) {
                if (!enabled || ids.size < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)
                        return@awaitEachGesture
                    val item =
                        list.layoutInfo.visibleItemsInfo.firstOrNull {
                            down.position.y >= it.offset + rowPadding &&
                                down.position.y < it.offset + rowPadding + previewEnd - previewStart
                        }
                    if (item == null || down.position.x !in previewStart..previewEnd)
                        return@awaitEachGesture
                    val id = item.key as Int
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (
                                change.isConsumed ||
                                    event.changes.count { it.pressed } > 1 ||
                                    event.platformPenInput()?.cancelled == true ||
                                    event.platformTouchInput()?.let {
                                        it.cancelled || it.gesturing
                                    } == true
                            )
                                break
                            change.consume()
                            if (!change.pressed) {
                                if (
                                    change.position.x in 0f..size.width.toFloat() &&
                                        change.position.y in 0f..size.height.toFloat()
                                ) {
                                    if (drag == null) {
                                        controller.command("select_layer") { put("id", id) }
                                    } else {
                                        drag?.position = change.position
                                        insertion?.first?.let { slot ->
                                            val source = ids.indexOf(id)
                                            val target = slot - if (source < slot) 1 else 0
                                            if (target != source)
                                                controller.command("reorder_layer") {
                                                    put("id", id)
                                                    put("index", ids.lastIndex - target)
                                                    put("revision", revision)
                                                }
                                        }
                                    }
                                }
                                break
                            }
                            if (
                                drag == null &&
                                    abs(change.position.y - down.position.y) >
                                        viewConfiguration.touchSlop
                            )
                                drag = LayerDrag(id, down.position.y - item.offset, change.position)
                            drag?.position = change.position
                        }
                    } finally {
                        drag = null
                    }
                }
            },
            state = list,
            verticalArrangement = Arrangement.spacedBy(StudioTheme.layerRowPadding),
        ) {
            items(layers, key = { it.id }) { layer ->
                LayerRow(
                    controller,
                    layer,
                    layer.id == controller.document.active,
                    enabled,
                    Modifier.animateItem(
                            fadeInSpec = tween(StudioMotion.feedbackMillis),
                            placementSpec =
                                tween(StudioMotion.panelMillis, easing = StudioMotion.easing),
                            fadeOutSpec = tween(StudioMotion.dismissMillis),
                        )
                        .graphicsLayer {
                            alpha =
                                if (drag?.id == layer.id) StudioTheme.layerDragSourceAlpha else 1f
                        },
                )
            }
        }
        drag?.let { active ->
            val layer = layers.firstOrNull { it.id == active.id } ?: return@let
            LayerRow(
                controller,
                layer,
                true,
                false,
                Modifier.fillMaxWidth()
                    .graphicsLayer { translationY = active.position.y - active.grabY }
                    .shadow(StudioTheme.layerDragShadow, StudioTheme.layerShape)
                    .background(StudioTheme.panel, StudioTheme.layerShape),
            )
            Box(
                Modifier.fillMaxSize().drawBehind {
                    insertion?.second?.let { y ->
                        drawLine(
                            StudioTheme.accent,
                            Offset(0f, y),
                            Offset(size.width, y),
                            StudioTheme.layerDropLineWidth.toPx(),
                        )
                    }
                }
            )
        }
    }
}
