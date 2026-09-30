package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            expanded = !expanded
        }
        StudioDropdownMenu(
            expanded,
            { expanded = false },
        ) {
            ReferenceMenuItems(controller) { expanded = false }
        }
    }
}

@Composable
fun ReferenceMenuItems(controller: StudioController, close: () -> Unit) {
    val references = controller.references
    Row(Modifier.padding(horizontal = 8.dp)) {
        ToolButton(Glyph.ImportImage, "导入参考图", enabled = references.canAdd, plain = true) {
            references.load(false, controller.document)
            close()
        }
        ToolButton(
            Glyph.Clipboard,
            controller.shortcutLabel(app.podor.domain.ShortcutAction.PasteReference),
            enabled = references.canAdd && references.canPaste,
            plain = true,
        ) {
            references.load(true, controller.document)
            close()
        }
        if (references.images.isNotEmpty()) {
            ToolButton(
                if (references.visible) Glyph.Hidden else Glyph.Eye,
                if (references.visible) "收起参考图" else "显示参考图",
                plain = true,
            ) {
                references.visible = !references.visible
                close()
            }
        }
    }
    if (references.images.isNotEmpty()) {
        HorizontalDivider(color = StudioTheme.border)
        references.images.forEach { reference ->
            StudioDropdownMenuItem(
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
