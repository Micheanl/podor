package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import app.podor.domain.SelectionKind
import app.podor.domain.SelectionMode
import app.podor.domain.SelectionRefinement
import app.podor.domain.ShortcutAction
import app.podor.domain.StudioDefaults
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun SelectionDock(
    controller: StudioController,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    ContextActionRow(
        modifier,
        floating,
        trailing = {
            ToolButton(
                Glyph.Close,
                controller.shortcutLabel(app.podor.domain.ShortcutAction.Deselect),
                enabled = controller.document.selection != null,
            ) {
                controller.clearSelection()
            }
        },
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
            CapsulePopup(expanded, { expanded = false }) {
                ContextActionRow(Modifier, floating = true) {
                    SelectionMode.entries.forEach { mode ->
                        ToolButton(
                            glyph(mode),
                            mode.label,
                            selected = controller.selectionMode == mode,
                            enabled =
                                controller.document.selection != null ||
                                    mode == SelectionMode.Replace ||
                                    mode == SelectionMode.Add,
                        ) {
                            controller.changeSelectionMode(mode)
                            expanded = false
                        }
                    }
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
    }
}

@Composable
private fun SelectionRefinementOptions(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(SelectionRefinement.Expand) }
    var radius by remember { mutableFloatStateOf(StudioDefaults.selectionRefinementRadius) }
    val enabled = controller.document.selection != null && controller.ready && !controller.busy
    fun glyph(kind: SelectionRefinement) =
        when (kind) {
            SelectionRefinement.Expand -> Glyph.SelectionAdd
            SelectionRefinement.Contract -> Glyph.SelectionSubtract
            SelectionRefinement.Smooth -> Glyph.Stabilize
            SelectionRefinement.Feather -> Glyph.Blur
        }
    Box {
        ToolButton(Glyph.Adjustments, "调整选区", selected = expanded, enabled = enabled) {
            expanded = !expanded
        }
        CapsulePopup(expanded, { expanded = false }) {
            val windowWidth =
                with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            Column(
                Modifier.width(minOf(StudioTheme.colorSelectionWidth, windowWidth)),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.colorSelectionGap),
            ) {
                ContextActionRow(
                    Modifier,
                    floating = true,
                    trailing = {
                        ToolButton(Glyph.Check, "应用", enabled = enabled) {
                            controller.refineSelection(kind, radius.roundToInt())
                            expanded = false
                        }
                    },
                ) {
                    SelectionRefinement.entries.forEach { entry ->
                        ToolButton(
                            glyph(entry),
                            entry.label,
                            selected = kind == entry,
                            enabled = enabled,
                        ) {
                            kind = entry
                        }
                    }
                }
                CapsuleSlider(
                    kind.label,
                    radius,
                    0f..StudioDefaults.maxSelectionRefinementRadius,
                    "${radius.roundToInt()} px",
                    glyph = glyph(kind),
                ) {
                    if (enabled) radius = it.roundToInt().toFloat()
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
                    StudioSwitch(
                        controller.selectionContiguous,
                        { controller.selectionContiguous = it },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("取样所有可见图层"),
                        Modifier.weight(1f),
                        fontSize = StudioTheme.colorSelectionLabelSize,
                    )
                    StudioSwitch(controller.selectionMerged, { controller.selectionMerged = it })
                }
            }
        }
    }
}
