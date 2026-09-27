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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.LayerInfo
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlinx.serialization.json.put

@Composable
fun LayerControls(controller: StudioController) {
    val layers = controller.document.layers
    val active = layers.firstOrNull { it.id == controller.document.active }
    val enabled = controller.ready && !controller.busy
    val ordered = remember(layers) { layers.asReversed() }
    var settings by remember { mutableStateOf(false) }
    var blending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${layers.size} / ${controller.document.maxLayers}",
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = StudioTheme.muted,
            )
            if (active != null) {
                ToolButton(
                    Glyph.AlphaLock,
                    if (active.alphaLocked) "解除透明度锁定" else "锁定透明度",
                    selected = active.alphaLocked,
                    enabled = enabled && !active.locked,
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
                enabled = enabled && layers.size < controller.document.maxLayers,
            ) {
                controller.file(StudioController.FileAction.ImportLayer)
            }
            ToolButton(
                Glyph.Plus,
                "新建图层",
                enabled = enabled && layers.size < controller.document.maxLayers,
            ) {
                controller.command("add_layer")
            }
        }
        LayerList(controller, ordered, enabled, Modifier.weight(1f).fillMaxWidth())
        if (active != null) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(StudioTheme.background)
                    .padding(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        "${tr(active.blend.label)} · ${(active.opacity * 100).roundToInt()}%",
                        { blending = true },
                        Modifier.weight(1f),
                        enabled = enabled,
                        primary = false,
                    )
                    ToolButton(Glyph.Adjustments, "图层设置", enabled = enabled) { settings = true }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ToolButton(
                        Glyph.Copy,
                        "复制图层",
                        enabled = enabled && layers.size < controller.document.maxLayers,
                    ) {
                        controller.command("duplicate_layer") { put("id", active.id) }
                    }
                    ToolButton(
                        Glyph.Up,
                        "上移图层",
                        enabled = enabled && active.id != layers.last().id,
                    ) {
                        controller.command("move_layer") {
                            put("id", active.id)
                            put("direction", 1)
                        }
                    }
                    ToolButton(
                        Glyph.Down,
                        "下移图层",
                        enabled = enabled && active.id != layers.first().id,
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
                                layers.count { it.visible } >= 2 &&
                                layers.none { it.visible && it.locked },
                    ) {
                        controller.command("merge_visible")
                    }
                    ToolButton(
                        Glyph.Trash,
                        "删除图层",
                        enabled = enabled && layers.size > 1 && !active.locked,
                    ) {
                        controller.command("remove_layer") { put("id", active.id) }
                    }
                }
            }
        }
    }
    if (blending && active != null) {
        LayerBlendDialog(
            active.blend,
            { mode -> if (mode != active.blend) controller.setLayerBlend(active.id, mode) },
            { blending = false },
        )
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
) {
    val background =
        animateColorAsState(
            if (selected) StudioTheme.accent.copy(alpha = 0.12f)
            else StudioTheme.elevated.copy(alpha = 0.5f),
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
                controller.command("select_layer") { put("id", layer.id) }
            }
            .padding(
                start = StudioTheme.layerPreviewInset,
                top = StudioTheme.layerRowPadding,
                bottom = StudioTheme.layerRowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dragLabel = "${tr(layer.name)} · ${tr("拖动缩略图排序")}"
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioTheme.background)
                    .padding(3.dp),
            )
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
                if (layer.alphaLocked)
                    StudioIcon(
                        Glyph.AlphaLock,
                        StudioTheme.accent,
                        Modifier.size(StudioTheme.layerStatusIconSize),
                    )
                if (layer.locked)
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
    var opacity by remember(layer.id) { mutableFloatStateOf(layer.opacity) }
    StudioAlertDialog(
        onDismissRequest = onDismiss,
        title = "图层设置",
        glyph = Glyph.Layers,
        confirmLabel = "保存",
        enabled = name.isNotBlank(),
        onConfirm = { controller.setLayer(layer.copy(name = name.trim(), opacity = opacity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(
                    name,
                    { name = it.take(60) },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr("图层名称")) },
                    singleLine = true,
                )
                LabeledSlider("图层不透明度", opacity, 0f..1f, "${(opacity * 100).roundToInt()}%") {
                    opacity = it
                }
            }
        },
    )
}
