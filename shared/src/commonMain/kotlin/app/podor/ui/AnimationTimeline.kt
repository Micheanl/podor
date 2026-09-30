package app.podor.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
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
    val grid = rememberLazyGridState()
    val tracksList = rememberLazyListState()
    val rulerDrag = rememberDraggableState { tracksList.dispatchRawDelta(-it) }
    val bodyScroll = rememberScrollState()
    var bodyHeight by remember { mutableIntStateOf(0) }
    var gridTop by remember { mutableFloatStateOf(0f) }
    val reducedMotion = LocalWorkspaceAppearance.current.reducedMotion
    var settings by remember { mutableStateOf(false) }
    var tagEditor by remember { mutableStateOf(false) }
    val tag = animation.tags.firstOrNull { it.id == controller.animationTagId }
    LaunchedEffect(animation.tags) {
        if (tag == null) controller.animationTagId = null
    }
    val shownId = controller.animationDisplayFrameId ?: animation.activeFrameId
    LaunchedEffect(shownId, animation.frames, controller.animationPlaying, reducedMotion) {
        val item = animation.frames.indexOfFirst { it.id == shownId }
        if (item >= 0) {
            val visible = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
            if (
                visible == null ||
                    visible.offset.y < grid.layoutInfo.viewportStartOffset ||
                    visible.offset.y + visible.size.height > grid.layoutInfo.viewportEndOffset
            ) {
                if (controller.animationPlaying || reducedMotion) grid.scrollToItem(item)
                else grid.animateScrollToItem(item)
            }
            if (tracksList.layoutInfo.visibleItemsInfo.none { it.index == item }) {
                if (controller.animationPlaying || reducedMotion) tracksList.scrollToItem(item)
                else tracksList.animateScrollToItem(item)
            }
            grid.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == item }
                ?.let { shown ->
                    val top = gridTop + shown.offset.y
                    val bottom = top + shown.size.height
                    val target =
                        when {
                            top < bodyScroll.value -> top.roundToInt()
                            bottom > bodyScroll.value + bodyHeight ->
                                (bottom - bodyHeight).roundToInt()
                            else -> bodyScroll.value
                        }.coerceIn(0, bodyScroll.maxValue)
                    if (bodyHeight > 0 && target != bodyScroll.value) {
                        if (controller.animationPlaying || reducedMotion)
                            bodyScroll.scrollTo(target)
                        else
                            bodyScroll.animateScrollTo(
                                target,
                                tween(StudioMotion.panelMillis, easing = StudioMotion.easing),
                            )
                    }
                }
        }
    }
    LaunchedEffect(grid, animation.frames, shownId) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visible ->
                val ids = visible.mapNotNull { animation.frames.getOrNull(it)?.id }
                controller.requestAnimationThumbnails(
                    if (shownId in ids) listOf(shownId) + ids.filter { it != shownId } else ids
                )
            }
    }
    DisposableEffect(controller) {
        onDispose { controller.requestAnimationThumbnails(emptyList()) }
    }
    Surface(modifier.fillMaxSize().testTag("animation-timeline"), color = StudioTheme.panel) {
        BoxWithConstraints(Modifier.padding(StudioTheme.animationTimelinePadding)) {
            val compact = maxWidth < StudioTheme.animationCompactWidth
            val columns =
                if (maxWidth < StudioTheme.animationPreviewMinWidth * 2 + StudioTheme.animationGap)
                    1
                else 2
            val frameEnabled =
                !controller.busy && !controller.drawingInput && !controller.animationTransition
            val tracks =
                controller.document.layerRows().filter {
                    it.kind == LayerKind.Raster || it.kind == LayerKind.Vector
                }
            val trackNameWidth = minOf(StudioTheme.animationTrackNameWidth, maxWidth / 3)
            val previewWidth = (maxWidth - StudioTheme.animationGap * (columns - 1)) / columns
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.headerGap),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(StudioTheme.headerGap),
                ) {
                    ToolButton(
                        if (controller.animationPlaying) Glyph.Stop else Glyph.Play,
                        if (controller.animationPlaying) "停止动画" else "播放动画",
                        plain = true,
                        enabled = frameEnabled,
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
                        Glyph.Trash,
                        "删除动画帧",
                        plain = true,
                        enabled = enabled && animation.frames.size > 1,
                    ) {
                        controller.animationCommand("delete_frame") { put("frame_id", active.id) }
                    }
                    if (!compact) {
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
                    }
                    Box {
                        ToolButton(Glyph.Settings, "动画设置", plain = true, enabled = enabled) {
                            settings = !settings
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
                }
                if (tag != null)
                    Text(
                        tag.name,
                        Modifier.fillMaxWidth(),
                        fontSize = StudioTheme.layerBlendCaptionSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().onSizeChanged { bodyHeight = it.height }
                ) {
                    val previewHeight =
                        minOf(
                            previewWidth / StudioTheme.animationPreviewAspectRatio,
                            (maxHeight - StudioTheme.controlSize).coerceAtLeast(0.dp),
                        )
                    val trackHeight =
                        minOf(
                            StudioTheme.animationTrackViewportHeight,
                            (maxHeight * StudioTheme.animationTrackViewportFraction).coerceAtLeast(
                                StudioTheme.animationRulerHeight + StudioTheme.animationTrackHeight
                            ),
                            StudioTheme.animationRulerHeight +
                                StudioTheme.animationTrackHeight * tracks.size,
                        )
                    val gridHeight =
                        (maxHeight - trackHeight - StudioTheme.headerGap * 2 - StudioTheme.hairline)
                            .coerceAtLeast(previewHeight + StudioTheme.controlSize)
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(bodyScroll)
                            .testTag("animation-timeline-body"),
                        verticalArrangement = Arrangement.spacedBy(StudioTheme.headerGap),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier =
                                Modifier.height(gridHeight).fillMaxWidth().onGloballyPositioned {
                                    gridTop = it.positionInParent().y
                                },
                            state = grid,
                            horizontalArrangement = Arrangement.spacedBy(StudioTheme.animationGap),
                            verticalArrangement = Arrangement.spacedBy(StudioTheme.animationGap),
                        ) {
                            gridItemsIndexed(animation.frames, key = { _, frame -> frame.id }) {
                                frameIndex,
                                frame ->
                                val selected = frame.id == animation.activeFrameId
                                val displayed = frame.id == controller.animationDisplayFrameId
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
                                    verticalArrangement =
                                        Arrangement.spacedBy(StudioTheme.headerGap),
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(previewHeight)
                                            .border(
                                                if (selected || displayed)
                                                    StudioTheme.animationSelectionBorder
                                                else StudioTheme.hairline,
                                                if (displayed) LocalPaintColor.current
                                                else if (selected) StudioTheme.text
                                                else StudioTheme.border,
                                            )
                                    ) {
                                        ArtworkPreview(
                                            controller.animationThumbnails[frame.id],
                                            controller.document.width,
                                            controller.document.height,
                                            Modifier.fillMaxSize()
                                                .padding(StudioTheme.animationSelectionBorder)
                                                .testTag("animation-thumbnail-${frame.id}"),
                                        )
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${frameIndex + 1}",
                                            Modifier.weight(1f),
                                            fontSize = StudioTheme.layerBlendCaptionSize,
                                            fontWeight =
                                                if (selected) FontWeight.SemiBold
                                                else FontWeight.Normal,
                                            color =
                                                if (selected) StudioTheme.text
                                                else StudioTheme.muted,
                                        )
                                        Text(
                                            "${frame.durationMs} ms",
                                            fontSize = StudioTheme.layerBlendCaptionSize,
                                            color = StudioTheme.muted,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            color = StudioTheme.border,
                            thickness = StudioTheme.hairline,
                        )
                        Surface(
                            Modifier.fillMaxWidth().height(trackHeight).testTag("animation-tracks"),
                            shape = StudioTheme.cardShape,
                            color = StudioTheme.background,
                            border = BorderStroke(StudioTheme.hairline, StudioTheme.controlBorder),
                        ) {
                            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Column(Modifier.width(trackNameWidth)) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(StudioTheme.animationRulerHeight)
                                            .background(StudioTheme.elevated),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            tr("图层"),
                                            Modifier.padding(horizontal = StudioTheme.headerGap),
                                            fontSize = StudioTheme.layerBlendCaptionSize,
                                            fontWeight = FontWeight.Medium,
                                            color = StudioTheme.muted,
                                        )
                                    }
                                    tracks.forEach { layer ->
                                        val activeTrack = layer.id == controller.document.active
                                        val line = StudioTheme.controlBorder
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .height(StudioTheme.animationTrackHeight)
                                                .testTag("animation-track-name-${layer.id}")
                                                .background(
                                                    if (activeTrack) StudioTheme.selection
                                                    else StudioTheme.panel
                                                )
                                                .drawBehind {
                                                    drawLine(
                                                        line,
                                                        Offset(0f, size.height),
                                                        Offset(size.width, size.height),
                                                        StudioTheme.hairline.toPx(),
                                                    )
                                                },
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(
                                                tr(layer.name),
                                                Modifier.padding(
                                                    horizontal = StudioTheme.headerGap
                                                ),
                                                fontSize = StudioTheme.layerBlendCaptionSize,
                                                fontWeight =
                                                    if (activeTrack) FontWeight.Medium
                                                    else FontWeight.Normal,
                                                color =
                                                    if (activeTrack) StudioTheme.onSelection
                                                    else StudioTheme.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                LazyRow(Modifier.weight(1f), state = tracksList) {
                                    itemsIndexed(
                                        animation.frames,
                                        key = { _, frame -> frame.id },
                                    ) { frameIndex, frame ->
                                        val selected = frame.id == animation.activeFrameId
                                        val shown = frame.id == shownId
                                        val playhead = StudioTheme.accent
                                        val gridLine = StudioTheme.controlBorder
                                        Column(
                                            Modifier.width(StudioTheme.animationExposureWidth)
                                                .background(
                                                    if (selected)
                                                        StudioTheme.selection.copy(alpha = 0.45f)
                                                    else StudioTheme.background
                                                )
                                                .drawWithContent {
                                                    drawContent()
                                                    if (shown) {
                                                        val stroke =
                                                            StudioTheme.animationPlayIndicatorHeight
                                                                .toPx()
                                                        val top =
                                                            StudioTheme.animationRulerHeight.toPx()
                                                        drawLine(
                                                            playhead,
                                                            Offset(size.width / 2, top - stroke),
                                                            Offset(size.width / 2, size.height),
                                                            stroke,
                                                        )
                                                    }
                                                }
                                        ) {
                                            Box(
                                                Modifier.fillMaxWidth()
                                                    .height(StudioTheme.animationRulerHeight)
                                                    .background(
                                                        if (shown) StudioTheme.selection
                                                        else StudioTheme.elevated
                                                    )
                                                    .drawBehind {
                                                        val stroke = StudioTheme.hairline.toPx()
                                                        drawLine(
                                                            gridLine,
                                                            Offset(0f, 0f),
                                                            Offset(0f, size.height),
                                                            stroke,
                                                        )
                                                        drawLine(
                                                            StudioTheme.muted,
                                                            Offset(0f, size.height),
                                                            Offset(
                                                                0f,
                                                                size.height -
                                                                    if (frameIndex % 5 == 0)
                                                                        size.height / 4
                                                                    else
                                                                        StudioTheme
                                                                            .animationPlayIndicatorHeight
                                                                            .toPx(),
                                                            ),
                                                            stroke,
                                                        )
                                                        drawLine(
                                                            gridLine,
                                                            Offset(0f, size.height),
                                                            Offset(size.width, size.height),
                                                            stroke,
                                                        )
                                                        if (shown)
                                                            drawRect(
                                                                playhead,
                                                                Offset(
                                                                    size.width / 2 -
                                                                        StudioTheme
                                                                            .animationKeyRadius
                                                                            .toPx(),
                                                                    size.height -
                                                                        StudioTheme
                                                                            .animationPlayIndicatorHeight
                                                                            .toPx(),
                                                                ),
                                                                Size(
                                                                    StudioTheme.animationKeyRadius
                                                                        .toPx() * 2,
                                                                    StudioTheme
                                                                        .animationPlayIndicatorHeight
                                                                        .toPx(),
                                                                ),
                                                            )
                                                    }
                                                    .clickable(
                                                        enabled = frameEnabled,
                                                        role = Role.Button,
                                                    ) {
                                                        controller.stopAnimation()
                                                        controller.animationCommand(
                                                            "select_frame"
                                                        ) {
                                                            put("frame_id", frame.id)
                                                        }
                                                    }
                                                    .draggable(
                                                        rulerDrag,
                                                        Orientation.Horizontal,
                                                        onDragStarted = { tracksList.stopScroll() },
                                                    )
                                                    .testTag("animation-ruler-${frame.id}")
                                                    .semantics { this.selected = selected },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    "${frameIndex + 1}",
                                                    Modifier.padding(
                                                        bottom =
                                                            StudioTheme.animationPlayIndicatorHeight
                                                    ),
                                                    fontSize = StudioTheme.layerBlendCaptionSize,
                                                    fontWeight =
                                                        if (shown || selected) FontWeight.SemiBold
                                                        else FontWeight.Normal,
                                                    color =
                                                        if (shown || selected)
                                                            StudioTheme.onSelection
                                                        else StudioTheme.muted,
                                                )
                                            }
                                            tracks.forEachIndexed { trackIndex, layer ->
                                                val cel =
                                                    animation.exposure(frame.id, layer.id)?.celId
                                                val previous =
                                                    animation.frames
                                                        .getOrNull(frameIndex - 1)
                                                        ?.let {
                                                            animation
                                                                .exposure(it.id, layer.id)
                                                                ?.celId
                                                        }
                                                val next =
                                                    animation.frames
                                                        .getOrNull(frameIndex + 1)
                                                        ?.let {
                                                            animation
                                                                .exposure(it.id, layer.id)
                                                                ?.celId
                                                        }
                                                val activeTrack =
                                                    layer.id == controller.document.active
                                                val tint =
                                                    if (activeTrack) StudioTheme.text
                                                    else StudioTheme.muted
                                                val cellSelected = selected && activeTrack
                                                val trackBorder = StudioTheme.controlBorder
                                                val label =
                                                    tr(layer.name) +
                                                        " · " +
                                                        frameLabel +
                                                        " ${frameIndex + 1}"
                                                Canvas(
                                                    Modifier.fillMaxWidth()
                                                        .height(StudioTheme.animationTrackHeight)
                                                        .clickable(
                                                            enabled = frameEnabled,
                                                            role = Role.Button,
                                                        ) {
                                                            controller.stopAnimation()
                                                            controller.animationCommand(
                                                                "select_frame",
                                                                layerId = layer.id,
                                                            ) {
                                                                put("frame_id", frame.id)
                                                            }
                                                        }
                                                        .semantics {
                                                            contentDescription = label
                                                            this.selected = cellSelected
                                                        }
                                                ) {
                                                    val hairline = StudioTheme.hairline.toPx()
                                                    if (activeTrack)
                                                        drawRect(
                                                            StudioTheme.selection.copy(alpha = 0.3f)
                                                        )
                                                    else if (trackIndex % 2 == 0)
                                                        drawRect(
                                                            StudioTheme.panel.copy(alpha = 0.55f)
                                                        )
                                                    drawLine(
                                                        trackBorder,
                                                        Offset(0f, size.height),
                                                        Offset(size.width, size.height),
                                                        hairline,
                                                    )
                                                    drawLine(
                                                        trackBorder,
                                                        Offset(0f, 0f),
                                                        Offset(0f, size.height),
                                                        hairline,
                                                    )
                                                    val radius =
                                                        StudioTheme.animationKeyRadius.toPx()
                                                    if (cel != null) {
                                                        val start =
                                                            if (previous == cel) 0f else radius
                                                        val end =
                                                            if (next == cel) size.width
                                                            else size.width - radius
                                                        val height =
                                                            StudioTheme.animationLinkSize.toPx() / 2
                                                        drawRect(
                                                            tint.copy(alpha = 0.12f),
                                                            Offset(start, center.y - height / 2),
                                                            Size(end - start, height),
                                                        )
                                                        if (previous == cel)
                                                            drawLine(
                                                                tint.copy(alpha = 0.65f),
                                                                Offset(0f, center.y),
                                                                center,
                                                                hairline,
                                                            )
                                                        if (next == cel)
                                                            drawLine(
                                                                tint.copy(alpha = 0.65f),
                                                                center,
                                                                Offset(size.width, center.y),
                                                                hairline,
                                                            )
                                                    }
                                                    if (cel == null || previous != cel)
                                                        rotate(45f, center) {
                                                            drawRect(
                                                                if (cel == null) StudioTheme.muted
                                                                else tint,
                                                                center - Offset(radius, radius),
                                                                Size(radius * 2, radius * 2),
                                                                style =
                                                                    if (cel == null)
                                                                        Stroke(hairline)
                                                                    else
                                                                        androidx.compose.ui.graphics
                                                                            .drawscope
                                                                            .Fill,
                                                            )
                                                        }
                                                    if (cellSelected)
                                                        drawRect(
                                                            StudioTheme.selectionBorder,
                                                            Offset(hairline, hairline),
                                                            Size(
                                                                size.width - hairline * 2,
                                                                size.height - hairline * 2,
                                                            ),
                                                            style = Stroke(hairline),
                                                        )
                                                }
                                            }
                                        }
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
