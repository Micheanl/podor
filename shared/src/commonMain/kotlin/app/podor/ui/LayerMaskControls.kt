package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import app.podor.domain.DocumentColorMode
import app.podor.domain.LayerInfo
import app.podor.domain.LayerKind
import app.podor.presentation.StudioController
import kotlinx.serialization.json.put

@Composable
internal fun LayerMaskControls(controller: StudioController, layer: LayerInfo, enabled: Boolean) {
    var expanded by remember(layer.id) { mutableStateOf(false) }
    val mask = layer.mask
    val editable = enabled && !layer.effectiveLocked
    Box {
        ToolButton(
            Glyph.Mask,
            if (mask == null) "添加蒙版" else "蒙版设置",
            selected = mask != null && controller.document.maskEditing,
            enabled = editable,
            plain = true,
        ) {
            expanded = !expanded
        }
        if (expanded)
            StudioModal("蒙版设置", Glyph.Mask, { expanded = false }) { dismiss ->
                Column(
                    Modifier.fillMaxWidth().weight(1f, false).verticalScroll(rememberScrollState())
                ) {
                    if (
                        mask == null ||
                            (controller.document.maxLayerMasks > 0 &&
                                layer.masks.size < controller.document.maxLayerMasks)
                    ) {
                        for ((label, mode) in
                            listOf(
                                "白色蒙版" to "reveal",
                                "黑色蒙版" to "hide",
                                "从选区创建蒙版" to "selection",
                            )) {
                            StudioDropdownMenuItem(
                                text = { Text(tr(label)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled =
                                    editable &&
                                        (mode != "selection" ||
                                            controller.document.selection?.empty == false),
                                leadingIcon = {
                                    if (mode == "selection") StudioIcon(Glyph.Selection)
                                    else
                                        Canvas(Modifier.size(StudioTheme.iconSize)) {
                                            drawCircle(
                                                if (mode == "reveal") Color.White else Color.Black
                                            )
                                            drawCircle(
                                                StudioTheme.muted,
                                                style = Stroke(StudioTheme.hairline.toPx()),
                                            )
                                        }
                                },
                                onClick = {
                                    dismiss()
                                    controller.addLayerMask(mode)
                                },
                            )
                        }
                    }
                    if (mask != null) {
                        if (
                            controller.document.maxLayerMasks > 0 &&
                                layer.masks.size < controller.document.maxLayerMasks
                        )
                            HorizontalDivider(color = StudioTheme.border)
                        StudioDropdownMenuItem(
                            text = { Text(tr(if (mask.enabled) "停用蒙版" else "启用蒙版")) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = editable,
                            leadingIcon = {
                                StudioIcon(if (mask.enabled) Glyph.Eye else Glyph.Hidden)
                            },
                            trailingIcon = { if (mask.enabled) StudioIcon(Glyph.Check) },
                            onClick = {
                                dismiss()
                                controller.setLayerMask(layer.id, enabled = !mask.enabled)
                            },
                        )
                        StudioDropdownMenuItem(
                            text = { Text(tr(if (mask.linked) "解除蒙版链接" else "链接蒙版")) },
                            enabled = editable,
                            leadingIcon = { StudioIcon(Glyph.Link) },
                            trailingIcon = { if (mask.linked) StudioIcon(Glyph.Check) },
                            onClick = {
                                dismiss()
                                controller.setLayerMask(layer.id, linked = !mask.linked)
                            },
                        )
                        StudioDropdownMenuItem(
                            text = { Text(tr("反相蒙版")) },
                            enabled = editable,
                            leadingIcon = { StudioIcon(Glyph.SelectionInvert) },
                            onClick = {
                                dismiss()
                                controller.maskCommand("invert_mask", layer.id, mask.id)
                            },
                        )
                        HorizontalDivider(color = StudioTheme.border)
                        StudioDropdownMenuItem(
                            text = { Text(tr(if (layer.masks.size > 1) "应用全部蒙版" else "应用蒙版")) },
                            enabled =
                                editable &&
                                    layer.maskEntries.any { it.enabled } &&
                                    !layer.alphaLocked &&
                                    controller.document.colorMode != DocumentColorMode.Indexed &&
                                    layer.kind == LayerKind.Raster,
                            leadingIcon = { StudioIcon(Glyph.Check) },
                            onClick = {
                                dismiss()
                                controller.command("apply_mask") { put("id", layer.id) }
                            },
                        )
                        StudioDropdownMenuItem(
                            text = { Text(tr("删除蒙版")) },
                            enabled = editable,
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
