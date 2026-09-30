package app.podor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                val maskName = tr(mask.name)
                val selected =
                    controller.document.maskEditing && controller.document.activeMaskId == mask.id
                Box {
                    Column(
                        Modifier.width(StudioTheme.maskStackCardWidth)
                            .testTag("mask-stack-entry-${mask.id}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArtworkPreview(
                            controller.previews.maskEntries[mask.id].takeIf {
                                controller.previews.maskLayerId == layer.id
                            },
                            controller.document.width,
                            controller.document.height,
                            Modifier.size(StudioTheme.maskStackPreviewHeight)
                                .then(
                                    if (selected)
                                        Modifier.border(
                                            StudioTheme.layerTargetBorder,
                                            StudioTheme.accent,
                                        )
                                    else Modifier
                                )
                                .selectable(
                                    selected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                ) {
                                    controller.selectLayer(layer.id, mask = true, maskId = mask.id)
                                }
                                .semantics { contentDescription = maskName },
                            transparent = false,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                maskName,
                                Modifier.weight(1f),
                                fontSize = StudioTheme.layerBlendCaptionSize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) StudioTheme.accent else StudioTheme.text,
                            )
                            ToolButton(
                                Glyph.More,
                                "$maskName · ${tr("蒙版设置")}",
                                enabled = editable,
                                plain = true,
                            ) {
                                menu = true
                            }
                        }
                    }
                    if (menu)
                        StudioModal("蒙版设置", Glyph.Mask, { menu = false }) { dismiss ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .weight(1f, false)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                StudioDropdownMenuItem(
                                    text = { Text(tr("蒙版名称")) },
                                    leadingIcon = { StudioIcon(Glyph.Brush) },
                                    onClick = {
                                        dismiss()
                                        renaming = mask
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr(if (mask.enabled) "停用蒙版" else "启用蒙版")) },
                                    leadingIcon = {
                                        StudioIcon(if (mask.enabled) Glyph.Eye else Glyph.Hidden)
                                    },
                                    onClick = {
                                        dismiss()
                                        controller.setLayerMask(
                                            layer.id,
                                            enabled = !mask.enabled,
                                            maskId = mask.id,
                                        )
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr(if (mask.linked) "解除蒙版链接" else "链接蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.Link) },
                                    onClick = {
                                        dismiss()
                                        controller.setLayerMask(
                                            layer.id,
                                            linked = !mask.linked,
                                            maskId = mask.id,
                                        )
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr("反相蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.SelectionInvert) },
                                    onClick = {
                                        dismiss()
                                        controller.maskCommand("invert_mask", layer.id, mask.id)
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr("复制蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.Copy) },
                                    enabled = layer.masks.size < controller.document.maxLayerMasks,
                                    onClick = {
                                        dismiss()
                                        controller.maskCommand("duplicate_mask", layer.id, mask.id)
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr("前移蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.Up) },
                                    enabled = index > 0,
                                    onClick = {
                                        dismiss()
                                        controller.reorderMask(layer.id, mask.id, index - 1)
                                    },
                                )
                                StudioDropdownMenuItem(
                                    text = { Text(tr("后移蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.Down) },
                                    enabled = index < layer.masks.lastIndex,
                                    onClick = {
                                        dismiss()
                                        controller.reorderMask(layer.id, mask.id, index + 1)
                                    },
                                )
                                HorizontalDivider(color = StudioTheme.border)
                                StudioDropdownMenuItem(
                                    text = { Text(tr("删除蒙版")) },
                                    leadingIcon = { StudioIcon(Glyph.Trash) },
                                    onClick = {
                                        dismiss()
                                        controller.maskCommand("delete_mask", layer.id, mask.id)
                                    },
                                )
                            }
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
