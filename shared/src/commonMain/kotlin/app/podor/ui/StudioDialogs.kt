package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController

enum class StudioDialog {
    None,
    New,
    Open,
    Clear,
    Settings,
    Export,
}

@Composable
fun StudioDialogs(controller: StudioController, dialog: StudioDialog, updates: UpdateController? = null, onDismiss: () -> Unit) {
    when (dialog) {
        StudioDialog.None -> Unit
        StudioDialog.New -> NewCanvasDialog(controller, onDismiss)
        StudioDialog.Open ->
            StudioAlertDialog(
                onDismissRequest = onDismiss,
                title = "打开工程或图片",
                glyph = Glyph.Folder,
                confirmLabel = "选择文件",
                text = { Text(tr("打开后会替换当前画布，重要改动请先保存。")) },
                onConfirm = { controller.file(StudioController.FileAction.Open) },
            )
        StudioDialog.Clear ->
            StudioAlertDialog(
                onDismissRequest = onDismiss,
                title = "清空当前图层？",
                glyph = Glyph.Trash,
                confirmLabel = "清空",
                text = { Text(tr("清空后仍可撤销。")) },
                onConfirm = { controller.command("clear") },
            )
        StudioDialog.Settings -> SettingsDialog(controller, onDismiss, updates)
        StudioDialog.Export -> ExportDialog(controller, onDismiss)
    }
    controller.error?.let { error ->
        StudioAlertDialog(
            onDismissRequest = controller::dismissError,
            title = "暂时无法完成",
            glyph = Glyph.More,
            confirmLabel = "知道了",
            showCancel = false,
            text = { Text(tr(error)) },
        )
    }
}
