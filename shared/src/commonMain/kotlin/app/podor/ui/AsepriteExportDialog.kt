package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.podor.domain.AsepriteExportIssue
import app.podor.domain.AsepriteExportOptions
import app.podor.presentation.StudioController

@Composable
fun AsepriteExportDialog(controller: StudioController, onDismiss: () -> Unit) {
    val capability = controller.document.asepriteExport ?: return
    var bakeLayers by remember { mutableStateOf(false) }
    val blocked = capability.blockingIssues.isNotEmpty()
    val bakeLabel = tr("导出合成帧副本")
    StudioAlertDialog(
        title = "Aseprite 工程副本",
        glyph = Glyph.Aseprite,
        confirmLabel = "导出",
        onDismissRequest = onDismiss,
        enabled =
            controller.asepriteExportAvailable &&
                !controller.busy &&
                !controller.drawingInput &&
                !controller.animationTransition &&
                !controller.animationPlaying &&
                !blocked &&
                bakeLayers,
        onConfirm = { controller.exportAseprite(AsepriteExportOptions(bakeLayers)) },
    ) {
        Text(tr(if (blocked) "这些工程数据暂时无法保留。" else "以下内容需要逐帧合成。"), color = StudioTheme.muted)
        (if (blocked) capability.blockingIssues else capability.editableIssues).forEach { issue ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(issue.label()), Modifier.weight(1f), fontSize = StudioTheme.buttonLabelSize)
                Text(
                    issue.count.toString(),
                    color = StudioTheme.muted,
                    fontSize = StudioTheme.buttonLabelSize,
                )
            }
        }
        if (!blocked) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(bakeLabel, Modifier.weight(1f), fontSize = StudioTheme.buttonLabelSize)
                StudioSwitch(
                    bakeLayers,
                    { bakeLayers = it },
                    Modifier.semantics { contentDescription = bakeLabel },
                )
            }
            Text(
                tr("保留全部帧、时长和播放范围。副本中每帧合并为一层，原工程不变。"),
                color = StudioTheme.muted,
                fontSize = StudioTheme.buttonLabelSize,
            )
        }
    }
}

private fun AsepriteExportIssue.label(): String =
    when (kind) {
        "vector" -> "矢量图层"
        "adjustment" -> "调整图层"
        "mask" -> "图层蒙版"
        "clipping" -> "剪贴蒙版"
        "mixed_groups" -> "混合图层组模式"
        "alpha_lock" -> "透明度锁定"
        "opacity_precision" -> "图层不透明度精度"
        "assistant" -> "绘画辅助线"
        "palette_order" -> "色板排列"
        "companion_palette" -> "附加工程色板"
        "finite_ping_pong_repeat" -> "有限次数的往返播放"
        "document_limit" -> "工程大小限制"
        "metadata_invalid" -> "工程元数据"
        "pixel_precision" -> "像素精度"
        else -> "暂不支持的工程内容"
    }
