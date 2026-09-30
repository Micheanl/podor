package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.AdjustmentKind
import app.podor.domain.LayerInfo
import app.podor.domain.LayerKind
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlinx.serialization.json.put

@Composable
fun LayerControls(controller: StudioController) {
    val pages = rememberSaveableStateHolder()
    val blending = controller.adjustmentPreview?.settings?.kind == AdjustmentKind.LayerBlend
    PageTransition(if (blending) 1 else 0, Modifier.fillMaxSize()) { page ->
        pages.SaveableStateProvider(page) {
            if (page == 1) LayerBlendControls(controller) else LayerListControls(controller)
        }
    }
}

@Composable
private fun LayerListControls(controller: StudioController) {
    val layers = controller.document.layers
    val active = layers.firstOrNull { it.id == controller.document.active }
    val enabled = controller.ready && !controller.busy
    val document = controller.document
    val ordered = remember(document) { document.layerRows() }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Int>()) }
    val siblings = document.siblings(active?.parentId)
    val activeIndex = siblings.indexOf(active)
    val clippingBase = siblings.take(activeIndex.coerceAtLeast(0)).lastOrNull { !it.clipping }
    val clippingAllowed =
        if (active?.clipping == true)
            active.kind != LayerKind.Adjustment ||
                siblings.getOrNull(activeIndex + 1)?.clipping != true
        else
            clippingBase != null &&
                clippingBase.kind != LayerKind.Adjustment &&
                clippingBase.isolation != app.podor.domain.GroupIsolation.PassThrough &&
                active?.isolation != app.podor.domain.GroupIsolation.PassThrough
    val nodesAvailable = document.maxLayerNodes == 0 || layers.size < document.maxLayerNodes
    val activeSubtree =
        remember(layers, active?.id) {
            layers.filter { it.id == active?.id || active?.id in document.ancestorIds(it.id) }
        }
    LaunchedEffect(layers) { selectedIds = selectedIds.intersect(layers.map { it.id }.toSet()) }
    var settings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${document.drawableLayerCount} / ${document.maxLayers}",
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = StudioTheme.muted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (active != null) {
                ToolButton(
                    Glyph.Clipping,
                    if (active.clipping) "解除剪贴" else "剪贴到下方图层",
                    selected = active.clipping,
                    enabled = enabled && clippingAllowed && !active.effectiveLocked,
                ) {
                    controller.setLayerClipping(active.id, !active.clipping)
                }
                ToolButton(
                    Glyph.AlphaLock,
                    if (active.alphaLocked) "解除透明度锁定" else "锁定透明度",
                    selected = active.alphaLocked,
                    enabled = enabled && !active.effectiveLocked && active.kind == LayerKind.Raster,
                ) {
                    controller.setLayerProtection(active.id, alphaLocked = !active.alphaLocked)
                }
                ToolButton(
                    Glyph.Lock,
                    if (active.locked) "解锁图层" else "锁定图层",
                    selected = active.locked,
                    enabled = enabled,
                ) {
                    controller.setLayerProtection(active.id, locked = !active.locked)
                }
            }
            ToolButton(
                Glyph.ImportImage,
                "导入为图层",
                enabled =
                    enabled &&
                        document.drawableLayerCount < document.maxLayers &&
                        nodesAvailable &&
                        active?.effectiveLocked != true,
            ) {
                controller.file(StudioController.FileAction.ImportLayer)
            }
            ToolButton(
                Glyph.Plus,
                "新建图层",
                enabled =
                    enabled &&
                        document.drawableLayerCount < document.maxLayers &&
                        nodesAvailable &&
                        active?.effectiveLocked != true,
            ) {
                controller.command("add_layer")
            }
            if (document.maxLayerNodes > 0)
                LayerGroupControls(
                    controller,
                    active,
                    enabled,
                    selecting,
                    selectedIds,
                    onSelecting = {
                        selecting = !selecting
                        selectedIds = emptySet()
                    },
                    onGrouped = {
                        selecting = false
                        selectedIds = emptySet()
                    },
                )
        }
        LayerList(
            controller,
            ordered,
            enabled,
            Modifier.weight(1f).fillMaxWidth(),
            selecting,
            selectedIds,
        ) { id ->
            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        }
        if (active != null) {
            if (document.maxLayerMasks > 0 && active.masks.isNotEmpty())
                LayerMaskStackControls(controller, active, enabled)
            if (active.kind == LayerKind.Vector) LayerVectorControls(controller, enabled)
            Column(
                Modifier.fillMaxWidth()
                    .clip(StudioTheme.cardShape)
                    .background(StudioTheme.background)
                    .padding(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        "${tr(active.blend.label)} · ${(active.opacity * 100).roundToInt()}%",
                        { controller.prepareAdjustment(AdjustmentKind.LayerBlend) },
                        Modifier.weight(1f),
                        enabled = enabled && controller.adjustmentPreview == null,
                        primary = false,
                    )
                    LayerMaskControls(controller, active, enabled)
                    if (active.kind == LayerKind.Adjustment)
                        ToolButton(
                            Glyph.Curves,
                            "编辑调整图层",
                            enabled = enabled && !active.effectiveLocked && !document.maskEditing,
                            plain = true,
                        ) {
                            controller.editAdjustmentLayer()
                        }
                    ToolButton(Glyph.Adjustments, "图层设置", enabled = enabled) { settings = true }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ToolButton(
                        Glyph.Copy,
                        "复制图层",
                        enabled =
                            enabled &&
                                (document.maxLayerNodes == 0 ||
                                    layers.size + activeSubtree.size <= document.maxLayerNodes) &&
                                document.drawableLayerCount +
                                    activeSubtree.count {
                                        it.kind == LayerKind.Raster || it.kind == LayerKind.Vector
                                    } <= document.maxLayers,
                    ) {
                        controller.command("duplicate_layer") { put("id", active.id) }
                    }
                    ToolButton(
                        Glyph.Up,
                        "上移图层",
                        enabled =
                            enabled &&
                                active.id != siblings.lastOrNull()?.id &&
                                !active.effectiveLocked,
                    ) {
                        controller.command("move_layer") {
                            put("id", active.id)
                            put("direction", 1)
                        }
                    }
                    ToolButton(
                        Glyph.Down,
                        "下移图层",
                        enabled =
                            enabled &&
                                active.id != siblings.firstOrNull()?.id &&
                                !active.effectiveLocked,
                    ) {
                        controller.command("move_layer") {
                            put("id", active.id)
                            put("direction", -1)
                        }
                    }
                    ToolButton(
                        Glyph.Merge,
                        "合并可见图层",
                        enabled =
                            enabled &&
                                document.animation == null &&
                                layers.count { it.visible } >= 2 &&
                                layers.none { it.visible && it.locked },
                    ) {
                        controller.command("merge_visible")
                    }
                    ToolButton(
                        Glyph.Trash,
                        "删除图层",
                        enabled =
                            enabled && !active.effectiveLocked && activeSubtree.size < layers.size,
                    ) {
                        controller.command("remove_layer") { put("id", active.id) }
                    }
                }
            }
        }
    }
    if (settings && active != null) {
        LayerSettingsDialog(controller, active) { settings = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayerRow(
    controller: StudioController,
    layer: LayerInfo,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    selecting: Boolean = false,
    onSelect: () -> Unit = { controller.selectLayer(layer.id) },
) {
    val background =
        animateColorAsState(
            if (selected) StudioTheme.selection else StudioTheme.elevated,
            tween(StudioMotion.feedbackMillis),
        )
    Row(
        modifier
            .fillMaxWidth()
            .clip(StudioTheme.layerShape)
            .drawBehind { drawRect(background.value) }
            .border(
                1.dp,
                if (selected) StudioTheme.accent.copy(alpha = 0.3f) else Color.Transparent,
                StudioTheme.layerShape,
            )
            .selectable(selected, enabled = enabled) {
                onSelect()
            }
            .padding(
                start =
                    StudioTheme.layerPreviewInset +
                        (StudioTheme.layerIndent * layer.depth).coerceAtMost(
                            StudioTheme.layerMaxIndent
                        ),
                top = StudioTheme.layerRowPadding,
                bottom = StudioTheme.layerRowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer.kind == LayerKind.Group)
            ToolButton(
                Glyph.Chevron,
                "${tr(if (layer.closed) "展开" else "收起")} ${tr(layer.name)}",
                enabled = enabled && !selecting,
                plain = true,
            ) {
                controller.closeLayerGroup(layer)
            }
        val dragLabel = "${tr(layer.name)} · ${tr(if (layer.mask == null) "拖动缩略图排序" else "编辑图层")}"
        TooltipBox(
            positionProvider =
                TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip(
                    containerColor = StudioTheme.elevated,
                    contentColor = StudioTheme.text,
                ) {
                    Text(dragLabel)
                }
            },
            state = rememberTooltipState(),
            enableUserInput = enabled,
        ) {
            ArtworkPreview(
                controller.previews.images[layer.id],
                controller.document.width,
                controller.document.height,
                Modifier.size(StudioTheme.layerPreviewSize)
                    .semantics { contentDescription = dragLabel }
                    .then(
                        if (layer.mask != null && selected && !controller.document.maskEditing)
                            Modifier.border(
                                StudioTheme.layerTargetBorder,
                                StudioTheme.accent,
                            )
                        else Modifier
                    ),
            )
        }
        layer.mask?.let { mask ->
            Spacer(Modifier.width(StudioTheme.layerMaskPreviewGap))
            val label = "${tr(layer.name)} · ${tr("编辑蒙版")}"
            val editing = selected && controller.document.maskEditing
            TooltipBox(
                positionProvider =
                    TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip(
                        containerColor = StudioTheme.elevated,
                        contentColor = StudioTheme.text,
                    ) {
                        Text(label)
                    }
                },
                state = rememberTooltipState(),
                enableUserInput = enabled,
            ) {
                Box(
                    Modifier.size(StudioTheme.layerMaskPreviewSize)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioTheme.elevated)
                        .border(
                            StudioTheme.layerTargetBorder,
                            if (editing) StudioTheme.accent else StudioTheme.controlBorder,
                            RoundedCornerShape(8.dp),
                        )
                        .selectable(editing, enabled = enabled && !selecting) {
                            controller.selectLayer(layer.id, mask = true)
                        }
                        .semantics { contentDescription = label }
                ) {
                    ArtworkPreview(
                        controller.previews.masks[layer.id],
                        controller.document.width,
                        controller.document.height,
                        Modifier.fillMaxSize()
                            .padding(StudioTheme.layerTargetBorder)
                            .alpha(if (mask.enabled) 1f else StudioTheme.layerMaskDisabledAlpha),
                        transparent = false,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    tr(layer.name),
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) StudioTheme.accent else StudioTheme.text,
                )
                if (layer.kind == LayerKind.Group)
                    StudioIcon(
                        Glyph.Folder,
                        StudioTheme.muted,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (layer.kind == LayerKind.Adjustment)
                    StudioIcon(
                        Glyph.Adjustments,
                        StudioTheme.muted,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (selecting && selected)
                    StudioIcon(
                        Glyph.Check,
                        StudioTheme.accent,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (layer.alphaLocked)
                    StudioIcon(
                        Glyph.AlphaLock,
                        StudioTheme.accent,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (layer.clipping)
                    StudioIcon(
                        Glyph.Clipping,
                        StudioTheme.muted,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (layer.effectiveLocked)
                    StudioIcon(
                        Glyph.Lock,
                        StudioTheme.muted,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
            }
            Text(
                "${tr(layer.blend.label)} · ${(layer.opacity * 100).roundToInt()}%",
                fontSize = 10.sp,
                color = StudioTheme.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ToolButton(
            if (layer.visible) Glyph.Eye else Glyph.Hidden,
            if (layer.visible) "隐藏 ${layer.name}" else "显示 ${layer.name}",
            enabled = enabled,
        ) {
            controller.setLayer(layer.copy(visible = !layer.visible))
        }
    }
}

@Composable
private fun LayerSettingsDialog(
    controller: StudioController,
    layer: LayerInfo,
    onDismiss: () -> Unit,
) {
    var name by remember(layer.id) { mutableStateOf(layer.name) }
    StudioAlertDialog(
        onDismissRequest = onDismiss,
        title = "图层设置",
        glyph = Glyph.Layers,
        confirmLabel = "保存",
        enabled = name.isNotBlank(),
        onConfirm = { controller.setLayer(layer.copy(name = name.trim())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(
                    name,
                    { name = it.take(60) },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr("图层名称")) },
                    singleLine = true,
                )
            }
        },
    )
}
