package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.podor.domain.Tool
import app.podor.presentation.StudioController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerMoveDock(controller: StudioController, modifier: Modifier = Modifier) {
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val preview = controller.layerMove
    val label =
        when {
            active?.locked == true -> "图层已锁定，请先解锁"
            active?.alphaLocked == true -> "请先解除透明度锁定"
            active?.visible == false -> "请先显示当前图层"
            controller.document.selection != null -> "请先取消选区，再移动图层"
            preview == null -> "准备图层…"
            else -> "移动图层"
        }
    Row(
        modifier
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.moveDockBorderWidth, StudioTheme.border, CircleShape)
            .padding(StudioTheme.moveDockPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.moveDockSpacing),
    ) {
        TooltipBox(
            positionProvider =
                TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip(
                    containerColor = StudioTheme.elevated,
                    contentColor = StudioTheme.text,
                ) {
                    Text(tr("超出画布的部分会裁切"))
                }
            },
            state = rememberTooltipState(),
        ) {
            StudioIcon(Glyph.Move, StudioTheme.accent)
        }
        Column {
            Text(tr(label), fontSize = StudioTheme.moveDockTitleSize)
            if (preview != null)
                Text(
                    "X ${preview.offset.x} · Y ${preview.offset.y} px",
                    fontSize = StudioTheme.moveDockValueSize,
                    color = StudioTheme.muted,
                )
        }
        ToolButton(Glyph.Close, "取消移动", enabled = preview?.committing != true) {
            controller.cancelLayerMove(exit = true)
            controller.tool = Tool.Brush
        }
    }
}
