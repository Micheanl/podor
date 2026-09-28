package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.podor.domain.ClipboardAction
import app.podor.presentation.StudioController

@Composable
fun ClipboardMenu(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton(Glyph.Clipboard, "剪贴板") { expanded = true }
        DropdownMenu(
            expanded,
            { expanded = false },
            shape = StudioTheme.menuShape,
            containerColor = StudioTheme.panel,
        ) {
            ClipboardMenuItems(controller) { expanded = false }
        }
    }
}

@Composable
fun ClipboardMenuItems(controller: StudioController, onDismiss: () -> Unit) {
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    ClipboardAction.entries.forEach { action ->
        DropdownMenuItem(
            text = { Text(tr(action.shortcut.label)) },
            onClick = {
                controller.clipboard(action)
                onDismiss()
            },
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
            leadingIcon = {
                StudioIcon(
                    when (action) {
                        ClipboardAction.Copy -> Glyph.Copy
                        ClipboardAction.CopyVisible -> Glyph.Layers
                        ClipboardAction.Cut -> Glyph.Cut
                        ClipboardAction.Paste -> Glyph.Clipboard
                    }
                )
            },
            trailingIcon = {
                Text(
                    controller.preferences.shortcut(action.shortcut).display(),
                    fontSize = StudioTheme.clipboardShortcutSize,
                    color = StudioTheme.muted,
                )
            },
        )
    }
}
