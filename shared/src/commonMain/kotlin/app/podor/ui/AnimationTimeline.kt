package app.podor.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

@Composable
internal fun AnimationTimeline(
    controller: StudioController,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animation = controller.document.animation ?: return
    val frameLabel = tr("动画帧")
    val enabled =
        !controller.busy &&
            !controller.drawingInput &&
            !controller.animationTransition &&
            !controller.animationPlaying
    val active = animation.frames.first { it.id == animation.activeFrameId }
    val index = animation.frames.indexOf(active)
    val list = rememberLazyListState()
    var settings by remember { mutableStateOf(false) }
    var tagEditor by remember { mutableStateOf(false) }
    val tag = animation.tags.firstOrNull { it.id == controller.animationTagId }
    LaunchedEffect(animation.tags) {
        if (tag == null) controller.animationTagId = null
    }
    val shownId = controller.animationDisplayFrameId ?: animation.activeFrameId
    LaunchedEffect(shownId, animation.frames, controller.animationPlaying) {
        val item = animation.frames.indexOfFirst { it.id == shownId }
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
        if (item >= 0 && item !in visible) {
            if (controller.animationPlaying) list.scrollToItem(item)
            else list.animateScrollToItem(item)
        }
    }
    LaunchedEffect(list, animation.frames) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visible ->
                controller.requestAnimationThumbnails(
                    visible.mapNotNull { animation.frames.getOrNull(it)?.id }
                )
            }
    }
    DisposableEffect(controller) {
        onDispose { controller.requestAnimationThumbnails(emptyList()) }
    }
    Surface(modifier.fillMaxWidth(), color = StudioTheme.panel) {
        Column(Modifier.padding(StudioTheme.animationTimelinePadding)) {
            BoxWithConstraints {
                val compact = maxWidth < StudioTheme.animationCompactWidth
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(
                        if (controller.animationPlaying) Glyph.Stop else Glyph.Play,
                        if (controller.animationPlaying) "停止动画" else "播放动画",
                        plain = true,
                        enabled =
                            !controller.busy &&
                                !controller.drawingInput &&
                                !controller.animationTransition,
                    ) {
                        if (controller.animationPlaying) controller.stopAnimation()
                        else controller.startAnimation()
                    }
                    ToolButton(
                        Glyph.Layers,
                        "洋葱皮",
                        selected = controller.onionEnabled,
                        plain = true,
                        enabled = enabled,
                    ) {
                        controller.onionEnabled = !controller.onionEnabled
                    }
                    ToolButton(
                        Glyph.Plus,
                        "添加空白帧",
                        plain = true,
                        enabled = enabled && animation.frames.size < animation.maxFrames,
                    ) {
                        controller.animationCommand("add_frame") {
                            put("index", index + 1)
                            put("duration_ms", active.durationMs)
                        }
                    }
                    if (!compact) {
                        ToolButton(
                            Glyph.Copy,
                            "复制动画帧",
                            plain = true,
                            enabled = enabled && animation.frames.size < animation.maxFrames,
                        ) {
                            controller.animationCommand("duplicate_frame") {
                                put("frame_id", active.id)
                                put("index", index + 1)
                                put("linked", false)
                            }
                        }
                        ToolButton(
                            Glyph.Link,
                            "共享复制帧",
                            plain = true,
                            enabled = enabled && animation.frames.size < animation.maxFrames,
                        ) {
                            controller.animationCommand("duplicate_frame") {
                                put("frame_id", active.id)
                                put("index", index + 1)
                                put("linked", true)
                            }
                        }
                        ToolButton(
                            Glyph.Trash,
                            "删除动画帧",
                            plain = true,
                            enabled = enabled && animation.frames.size > 1,
                        ) {
                            controller.animationCommand("delete_frame") {
                                put("frame_id", active.id)
                            }
                        }
                    }
                    if (tag == null) Spacer(Modifier.weight(1f))
                    else
                        Text(
                            tag.name,
                            Modifier.weight(1f),
                            fontSize = StudioTheme.layerBlendCaptionSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Box {
                        ToolButton(Glyph.Settings, "动画设置", plain = true, enabled = enabled) {
                            settings = true
                        }
                        StudioDropdownMenu(settings, { settings = false }) {
                            AnimationTimelineSettings(
                                controller,
                                compact,
                                onDismiss = { settings = false },
                                onEditRange = {
                                    settings = false
                                    tagEditor = true
                                },
                            )
                        }
                    }
                    if (controller.animationExportFormats.isNotEmpty())
                        ToolButton(Glyph.Export, "导出动画", plain = true, enabled = enabled) {
                            onExport()
                        }
                    ToolButton(
                        Glyph.Close,
                        "收起时间轴",
                        plain = true,
                        enabled = !controller.drawingInput && !controller.animationTransition,
                    ) {
                        controller.hideAnimationTimeline()
                    }
                }
            }
            val tracks =
                controller.document.layerRows().filter {
                    it.kind == LayerKind.Raster || it.kind == LayerKind.Vector
                }
            Row(
                Modifier.heightIn(max = StudioTheme.animationTrackViewportHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(Modifier.width(StudioTheme.animationTrackNameWidth)) {
                    Spacer(
                        Modifier.height(
                            StudioTheme.animationRulerHeight + StudioTheme.animationFrameHeight
                        )
                    )
                    tracks.forEach { layer ->
                        Box(
                            Modifier.fillMaxWidth().height(StudioTheme.animationTrackHeight),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                tr(layer.name),
                                fontSize = StudioTheme.layerBlendCaptionSize,
                                color =
                                    if (layer.id == controller.document.active) StudioTheme.text
                                    else StudioTheme.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                LazyRow(Modifier.weight(1f), state = list) {
                    itemsIndexed(animation.frames, key = { _, frame -> frame.id }) {
                        frameIndex,
                        frame ->
                        val frameEnabled =
                            !controller.busy &&
                                !controller.drawingInput &&
                                !controller.animationTransition
                        val selected = frame.id == animation.activeFrameId
                        Column(
                            Modifier.animateItem(
                                    fadeInSpec = tween(StudioMotion.feedbackMillis),
                                    placementSpec =
                                        tween(
                                            StudioMotion.panelMillis,
                                            easing = StudioMotion.easing,
                                        ),
                                    fadeOutSpec = tween(StudioMotion.dismissMillis),
                                )
                                .width(StudioTheme.animationFrameWidth)
                                .clickable(enabled = frameEnabled, role = Role.Button) {
                                    controller.stopAnimation()
                                    controller.animationCommand("select_frame") {
                                        put("frame_id", frame.id)
                                    }
                                }
                                .semantics {
                                    contentDescription = frameLabel + " ${frameIndex + 1}"
                                    this.selected = selected
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(StudioTheme.animationRulerHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${frameIndex + 1}",
                                    fontSize = StudioTheme.layerBlendCaptionSize,
                                    fontWeight =
                                        if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) StudioTheme.text else StudioTheme.muted,
                                )
                                if (selected || controller.animationDisplayFrameId == frame.id)
                                    Box(
                                        Modifier.align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(StudioTheme.animationPlayIndicatorHeight)
                                            .background(
                                                if (controller.animationDisplayFrameId == frame.id)
                                                    LocalPaintColor.current
                                                else StudioTheme.text
                                            )
                                    )
                            }
                            Box(
                                Modifier.fillMaxWidth().height(StudioTheme.animationFrameHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                controller.animationThumbnails[frame.id]?.let {
                                    Image(
                                        it,
                                        null,
                                        Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                } ?: StudioIcon(Glyph.Animation, tint = StudioTheme.muted)
                            }
                            tracks.forEach { layer ->
                                val cel = animation.exposure(frame.id, layer.id)?.celId
                                val previous =
                                    animation.frames.getOrNull(frameIndex - 1)?.let {
                                        animation.exposure(it.id, layer.id)?.celId
                                    }
                                val next =
                                    animation.frames.getOrNull(frameIndex + 1)?.let {
                                        animation.exposure(it.id, layer.id)?.celId
                                    }
                                val tint =
                                    if (layer.id == controller.document.active) StudioTheme.text
                                    else StudioTheme.muted
                                val grid = StudioTheme.border
                                val label =
                                    tr(layer.name) + " · " + frameLabel + " ${frameIndex + 1}"
                                Canvas(
                                    Modifier.fillMaxWidth()
                                        .height(StudioTheme.animationTrackHeight)
                                        .clickable(enabled = frameEnabled, role = Role.Button) {
                                            controller.stopAnimation()
                                            controller.animationCommand(
                                                "select_frame",
                                                layerId = layer.id,
                                            ) {
                                                put("frame_id", frame.id)
                                            }
                                        }
                                        .semantics { contentDescription = label }
                                ) {
                                    drawLine(
                                        grid,
                                        Offset(0f, size.height),
                                        Offset(size.width, size.height),
                                    )
                                    drawLine(
                                        grid,
                                        Offset(size.width, 0f),
                                        Offset(size.width, size.height),
                                    )
                                    val radius = StudioTheme.animationKeyRadius.toPx()
                                    if (cel == null)
                                        drawCircle(grid, radius, center, style = Stroke(1f))
                                    else {
                                        if (previous == cel)
                                            drawLine(tint, Offset(0f, center.y), center)
                                        if (next == cel)
                                            drawLine(tint, center, Offset(size.width, center.y))
                                        if (previous != cel) drawCircle(tint, radius, center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (tagEditor) AnimationRangeDialog(controller, tag) { tagEditor = false }
}

@Composable
private fun AnimationRangeDialog(
    controller: StudioController,
    tag: AnimationTag?,
    dismiss: () -> Unit,
) {
    val animation = controller.document.animation ?: return
    var name by remember(tag?.id) { mutableStateOf(tag?.name.orEmpty()) }
    var from by
        remember(tag?.id) {
            mutableStateOf(
                (animation.frames.indexOfFirst { it.id == tag?.fromFrame }.coerceAtLeast(0) + 1)
                    .toString()
            )
        }
    var to by
        remember(tag?.id) {
            mutableStateOf(
                (if (tag == null) animation.frames.size
                    else animation.frames.indexOfFirst { it.id == tag.toFrame } + 1)
                    .toString()
            )
        }
    var repeat by remember(tag?.id) { mutableStateOf((tag?.repeat ?: 0).toString()) }
    var direction by
        remember(tag?.id) { mutableStateOf(tag?.direction ?: controller.animationDirection) }
    val first = from.toIntOrNull()?.takeIf { it in 1..animation.frames.size }
    val last = to.toIntOrNull()?.takeIf { it in 1..animation.frames.size }
    val count = repeat.toIntOrNull()?.takeIf { it in 0..65535 }
    StudioAlertDialog(
        title = if (tag == null) "新建播放范围" else "编辑播放范围",
        glyph = Glyph.Animation,
        confirmLabel = "保存",
        onDismissRequest = dismiss,
        enabled =
            name.isNotBlank() &&
                first != null &&
                last != null &&
                first <= last &&
                count != null &&
                !controller.busy &&
                !controller.animationTransition,
        onConfirm = {
            controller.animationCommand(if (tag == null) "add_frame_tag" else "set_frame_tag") {
                tag?.let { put("id", it.id) }
                putJsonObject("tag") {
                    put("name", name.trim())
                    put("from_frame", animation.frames[first!! - 1].id)
                    put("to_frame", animation.frames[last!! - 1].id)
                    put("direction", Json.encodeToJsonElement(direction))
                    put("repeat", count!!)
                    putJsonArray("color") {
                        (tag?.color ?: vectorRgba(controller.brush.color)).forEach { add(it) }
                    }
                }
            }
        },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(60) },
                Modifier.fillMaxWidth(),
                label = { Text(tr("播放范围名称")) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasFieldsGap)) {
                OutlinedTextField(
                    from,
                    { from = it.take(3) },
                    Modifier.weight(1f),
                    label = { Text(tr("起始帧")) },
                    singleLine = true,
                    isError = first == null,
                )
                OutlinedTextField(
                    to,
                    { to = it.take(3) },
                    Modifier.weight(1f),
                    label = { Text(tr("结束帧")) },
                    singleLine = true,
                    isError = last == null || first != null && last < first,
                )
            }
            AnimationDirectionPicker(direction) { direction = it }
            OutlinedTextField(
                repeat,
                { repeat = it.take(5) },
                Modifier.fillMaxWidth(),
                label = { Text(tr("循环次数")) },
                supportingText = { Text(tr("0 为无限循环")) },
                singleLine = true,
                isError = count == null,
            )
        },
    )
}

internal fun AnimationDirection.label() =
    when (this) {
        AnimationDirection.Forward -> "正向播放"
        AnimationDirection.Reverse -> "反向播放"
        AnimationDirection.PingPong -> "往返播放"
        AnimationDirection.PingPongReverse -> "反向往返"
    }
