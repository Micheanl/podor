package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.podor.domain.ClipboardAction
import app.podor.presentation.StudioController

@Composable
fun ClipboardMenu(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton(Glyph.Clipboard, "剪贴板") { expanded = true }
        StudioDropdownMenu(
            expanded,
            { expanded = false },
        ) {
            ClipboardMenuItems(controller) { expanded = false }
        }
    }
}

@Composable
fun ClipboardMenuItems(controller: StudioController, onDismiss: () -> Unit) {
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    Row(Modifier.padding(horizontal = 8.dp)) {
        ClipboardAction.entries.forEach { action ->
            ToolButton(
                when (action) {
                    ClipboardAction.Copy -> Glyph.Copy
                    ClipboardAction.CopyVisible -> Glyph.Layers
                    ClipboardAction.Cut -> Glyph.Cut
                    ClipboardAction.Paste -> Glyph.Clipboard
                },
                controller.shortcutLabel(action.shortcut),
                enabled =
                    controller.ready &&
                        !controller.busy &&
                        when (action) {
                            ClipboardAction.Cut ->
                                active != null && !active.locked && !active.alphaLocked
                            ClipboardAction.Paste ->
                                controller.document.layers.size < controller.document.maxLayers
                            else -> active != null
                        },
                plain = true,
            ) {
                controller.clipboard(action)
                onDismiss()
            }
        }
    }
}
