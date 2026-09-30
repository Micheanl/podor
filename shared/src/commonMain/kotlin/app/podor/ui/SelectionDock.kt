package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.podor.domain.SelectionKind
import app.podor.domain.SelectionMode
import app.podor.domain.SelectionRefinement
import app.podor.domain.ShortcutAction
import app.podor.domain.StudioDefaults
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun SelectionDock(controller: StudioController, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.selectionDockBorder, StudioTheme.border, CircleShape)
            .padding(StudioTheme.selectionDockPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
    ) {
        SelectionKind.entries.forEach { kind ->
            ToolButton(
                when (kind) {
                    SelectionKind.Rectangle -> Glyph.Selection
                    SelectionKind.Ellipse -> Glyph.EllipseSelection
                    SelectionKind.Lasso -> Glyph.Lasso
                    SelectionKind.MagicWand -> Glyph.MagicWand
                },
                if (kind == SelectionKind.MagicWand)
                    controller.shortcutLabel(ShortcutAction.MagicWand)
                else kind.label,
                selected = controller.selectionKind == kind,
            ) {
                controller.selectionKind = kind
            }
        }
        if (controller.selectionKind == SelectionKind.MagicWand) ColorSelectionOptions(controller)
        Box(
            Modifier.width(StudioTheme.selectionDockBorder)
                .height(StudioTheme.iconSize)
                .background(StudioTheme.border)
        )
        var expanded by remember { mutableStateOf(false) }
        fun glyph(mode: SelectionMode) =
            when (mode) {
                SelectionMode.Replace -> Glyph.Selection
                SelectionMode.Add -> Glyph.SelectionAdd
                SelectionMode.Subtract -> Glyph.SelectionSubtract
                SelectionMode.Intersect -> Glyph.SelectionIntersect
            }
        Box {
            ToolButton(
                glyph(controller.selectionMode),
                controller.selectionMode.label,
                selected = controller.selectionMode != SelectionMode.Replace,
            ) {
                expanded = !expanded
            }
            StudioDropdownMenu(
                expanded,
                { expanded = false },
            ) {
                SelectionMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(tr(mode.label)) },
                        leadingIcon = { StudioIcon(glyph(mode)) },
                        trailingIcon = {
                            if (controller.selectionMode == mode) StudioIcon(Glyph.Check)
                        },
                        enabled =
                            controller.document.selection != null ||
                                mode == SelectionMode.Replace ||
                                mode == SelectionMode.Add,
                        onClick = {
                            controller.changeSelectionMode(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
        ToolButton(
            Glyph.SelectionInvert,
            controller.shortcutLabel(ShortcutAction.InvertSelection),
            enabled = controller.document.selection != null,
        ) {
            controller.invertSelection()
        }
        SelectionRefinementOptions(controller)
        ToolButton(
            Glyph.Close,
            controller.shortcutLabel(app.podor.domain.ShortcutAction.Deselect),
            enabled = controller.document.selection != null,
        ) {
            controller.clearSelection()
        }
    }
}

@Composable
private fun SelectionRefinementOptions(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(SelectionRefinement.Expand) }
    var radius by remember { mutableFloatStateOf(StudioDefaults.selectionRefinementRadius) }
    val enabled = controller.document.selection != null && controller.ready && !controller.busy
    Box {
        ToolButton(Glyph.Adjustments, "调整选区", selected = expanded, enabled = enabled) {
            expanded = !expanded
        }
        StudioDropdownMenu(expanded, { expanded = false }) {
            Column(
                Modifier.width(StudioTheme.colorSelectionWidth)
                    .padding(StudioTheme.colorSelectionPadding),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.colorSelectionGap),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap)) {
                    SelectionRefinement.entries.forEach { entry ->
                        ToolButton(
                            when (entry) {
                                SelectionRefinement.Expand -> Glyph.SelectionAdd
                                SelectionRefinement.Contract -> Glyph.SelectionSubtract
                                SelectionRefinement.Smooth -> Glyph.Stabilize
                                SelectionRefinement.Feather -> Glyph.Blur
                            },
                            entry.label,
                            selected = kind == entry,
                            enabled = enabled,
                        ) {
                            kind = entry
                        }
                    }
                }
                LabeledSlider(
                    kind.label,
                    radius,
                    0f..StudioDefaults.maxSelectionRefinementRadius,
                    "${radius.roundToInt()} px",
                ) {
                    if (enabled) radius = it.roundToInt().toFloat()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ToolButton(Glyph.Check, "应用", enabled = enabled) {
                        controller.refineSelection(kind, radius.roundToInt())
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSelectionOptions(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton(Glyph.Adjustments, "魔棒设置", selected = expanded) { expanded = !expanded }
        StudioDropdownMenu(
            expanded,
            { expanded = false },
        ) {
            Column(
                Modifier.width(StudioTheme.colorSelectionWidth)
                    .padding(StudioTheme.colorSelectionPadding),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.colorSelectionGap),
            ) {
                LabeledSlider(
                    "容差",
                    controller.selectionTolerance,
                    0f..255f,
                    controller.selectionTolerance.roundToInt().toString(),
                ) {
                    controller.selectionTolerance = it.roundToInt().toFloat()
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("仅连续区域"),
                        Modifier.weight(1f),
                        fontSize = StudioTheme.colorSelectionLabelSize,
                    )
                    Switch(controller.selectionContiguous, { controller.selectionContiguous = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("取样所有可见图层"),
                        Modifier.weight(1f),
                        fontSize = StudioTheme.colorSelectionLabelSize,
                    )
                    Switch(controller.selectionMerged, { controller.selectionMerged = it })
                }
            }
        }
    }
}
