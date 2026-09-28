package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.podor.presentation.StudioController

@Composable
fun ReferenceMenu(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton(
            Glyph.Reference,
            "参考图",
            controller.references.visible && controller.references.images.isNotEmpty(),
        ) {
            expanded = true
        }
        DropdownMenu(
            expanded,
            { expanded = false },
            containerColor = StudioTheme.panel,
            shape = StudioTheme.menuShape,
        ) {
            ReferenceMenuItems(controller) { expanded = false }
        }
    }
}

@Composable
fun ReferenceMenuItems(controller: StudioController, close: () -> Unit) {
    val references = controller.references
    DropdownMenuItem(
        { Text(tr("导入参考图")) },
        {
            references.load(false, controller.document)
            close()
        },
        enabled = references.canAdd,
        leadingIcon = { StudioIcon(Glyph.ImportImage) },
    )
    DropdownMenuItem(
        { Text(controller.shortcutLabel(app.podor.domain.ShortcutAction.PasteReference)) },
        {
            references.load(true, controller.document)
            close()
        },
        enabled = references.canAdd && references.canPaste,
        leadingIcon = { StudioIcon(Glyph.Clipboard) },
    )
    if (references.images.isNotEmpty()) {
        DropdownMenuItem(
            { Text(tr(if (references.visible) "收起参考图" else "显示参考图")) },
            {
                references.visible = !references.visible
                close()
            },
            leadingIcon = { StudioIcon(if (references.visible) Glyph.Hidden else Glyph.Eye) },
        )
        HorizontalDivider(color = StudioTheme.border)
        references.images.forEach { reference ->
            DropdownMenuItem(
                { Text(tr(reference.name), maxLines = 1) },
                {
                    references.visible = true
                    references.select(reference.id)
                    close()
                },
                leadingIcon = {
                    StudioIcon(
                        if (references.selectedId == reference.id) Glyph.Check else Glyph.Reference
                    )
                },
            )
        }
    }
}
