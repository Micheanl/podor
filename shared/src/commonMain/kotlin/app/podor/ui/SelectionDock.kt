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
import app.podor.domain.ShortcutAction
import app.podor.presentation.StudioController

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
                },
                kind.label,
                selected = controller.selectionKind == kind,
            ) {
                controller.selectionKind = kind
            }
        }
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
            DropdownMenu(
                expanded,
                { expanded = false },
                shape = StudioTheme.clipboardMenuShape,
                containerColor = StudioTheme.panel,
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
        ToolButton(
            Glyph.Close,
            controller.shortcutLabel(app.podor.domain.ShortcutAction.Deselect),
            enabled = controller.document.selection != null,
        ) {
            controller.clearSelection()
        }
    }
}
