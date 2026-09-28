package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.podor.domain.SelectionKind
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
        ToolButton(
            Glyph.Close,
            controller.shortcutLabel(app.podor.domain.ShortcutAction.Deselect),
            enabled = controller.document.selection != null,
        ) {
            controller.clearSelection()
        }
    }
}
