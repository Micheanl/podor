package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.podor.domain.LayerInfo
import app.podor.domain.LayerMaskInfo
import app.podor.presentation.StudioController

@Composable
internal fun LayerMaskStackControls(
    controller: StudioController,
    layer: LayerInfo,
    enabled: Boolean,
) {
    var renaming by remember(layer.id) { mutableStateOf<LayerMaskInfo?>(null) }
    val editable = enabled && !layer.effectiveLocked
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.maskStackGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("蒙版堆栈"),
                Modifier.weight(1f),
                fontSize = StudioTheme.layerBlendCaptionSize,
                color = StudioTheme.muted,
            )
            Text(
                "${layer.masks.size} / ${controller.document.maxLayerMasks}",
                fontSize = StudioTheme.layerBlendLabelSize,
                color = StudioTheme.muted,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(StudioTheme.maskStackGap)) {
            itemsIndexed(layer.masks, key = { _, mask -> mask.id }) { index, mask ->
                var menu by remember(mask.id) { mutableStateOf(false) }
                val selected =
                    controller.document.maskEditing && controller.document.activeMaskId == mask.id
                Box {
                    ChoiceSurface(
                        selected,
                        { controller.selectLayer(layer.id, mask = true, maskId = mask.id) },
                        Modifier.width(StudioTheme.maskStackCardWidth),
                        enabled = enabled,
                    ) {
                        ArtworkPreview(
                            controller.previews.maskEntries[mask.id].takeIf {
                                controller.previews.maskLayerId == layer.id
                            },
                            controller.document.width,
                            controller.document.height,
                            Modifier.fillMaxWidth()
                                .height(StudioTheme.maskStackPreviewHeight)
                                .background(StudioTheme.background),
                            transparent = false,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr(mask.name),
                                Modifier.weight(1f),
                                fontSize = StudioTheme.layerBlendCaptionSize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) StudioTheme.onSelection else StudioTheme.text,
                            )
                            ToolButton(
                                Glyph.More,
                                "${tr(mask.name)} · ${tr("蒙版设置")}",
                                enabled = editable,
                                plain = true,
                            ) {
                                menu = true
                            }
                        }
                    }
                    StudioDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(tr("蒙版名称")) },
                            leadingIcon = { StudioIcon(Glyph.Brush) },
                            onClick = {
                                menu = false
                                renaming = mask
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(if (mask.enabled) "停用蒙版" else "启用蒙版")) },
                            leadingIcon = {
                                StudioIcon(if (mask.enabled) Glyph.Eye else Glyph.Hidden)
                            },
                            onClick = {
                                menu = false
                                controller.setLayerMask(
                                    layer.id,
                                    enabled = !mask.enabled,
                                    maskId = mask.id,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(if (mask.linked) "解除蒙版链接" else "链接蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.Link) },
                            onClick = {
                                menu = false
                                controller.setLayerMask(
                                    layer.id,
                                    linked = !mask.linked,
                                    maskId = mask.id,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("反相蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.SelectionInvert) },
                            onClick = {
                                menu = false
                                controller.maskCommand("invert_mask", layer.id, mask.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("复制蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.Copy) },
                            enabled = layer.masks.size < controller.document.maxLayerMasks,
                            onClick = {
                                menu = false
                                controller.maskCommand("duplicate_mask", layer.id, mask.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("前移蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.Up) },
                            enabled = index > 0,
                            onClick = {
                                menu = false
                                controller.reorderMask(layer.id, mask.id, index - 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("后移蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.Down) },
                            enabled = index < layer.masks.lastIndex,
                            onClick = {
                                menu = false
                                controller.reorderMask(layer.id, mask.id, index + 1)
                            },
                        )
                        HorizontalDivider(color = StudioTheme.border)
                        DropdownMenuItem(
                            text = { Text(tr("删除蒙版")) },
                            leadingIcon = { StudioIcon(Glyph.Trash) },
                            onClick = {
                                menu = false
                                controller.maskCommand("delete_mask", layer.id, mask.id)
                            },
                        )
                    }
                }
            }
        }
    }
    renaming?.let { mask ->
        var name by remember(mask.id) { mutableStateOf(mask.name) }
        StudioAlertDialog(
            onDismissRequest = { renaming = null },
            title = "蒙版名称",
            glyph = Glyph.Mask,
            confirmLabel = "保存",
            enabled = name.isNotBlank(),
            onConfirm = {
                controller.setLayerMask(layer.id, maskId = mask.id, name = name.trim())
                renaming = null
            },
            text = {
                OutlinedTextField(
                    name,
                    { name = it.take(60) },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr("蒙版名称")) },
                    singleLine = true,
                )
            },
        )
    }
}
