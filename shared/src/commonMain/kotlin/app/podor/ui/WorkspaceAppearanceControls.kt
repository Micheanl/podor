package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.podor.domain.*
import kotlin.math.roundToInt

@Composable
fun WorkspaceAppearanceControls(
    value: WorkspaceAppearance,
    onChange: (WorkspaceAppearance) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
        SectionLabel("工具栏位置")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
            ToolDockPosition.entries.forEach { position ->
                ToolButton(
                    when (position) {
                        ToolDockPosition.Left -> Glyph.Backward
                        ToolDockPosition.Right -> Glyph.Forward
                        ToolDockPosition.Top -> Glyph.Up
                        ToolDockPosition.Bottom -> Glyph.Down
                    },
                    "${tr("工具栏")} · ${tr(position.label)}",
                    selected = value.toolDock == position,
                    enabled = enabled,
                ) {
                    onChange(value.copy(toolDock = position))
                }
            }
        }
        SectionLabel("面板位置")
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
            InspectorPosition.entries.forEach { position ->
                ToolButton(
                    if (position == InspectorPosition.Left) Glyph.Backward else Glyph.Forward,
                    "${tr("面板")} · ${tr(position.label)}",
                    selected = value.inspectorPosition == position,
                    enabled = enabled,
                ) {
                    onChange(value.copy(inspectorPosition = position))
                }
            }
        }
        SectionLabel("界面密度")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
            InterfaceDensity.entries.forEach { density ->
                StudioTextButton(
                    { onChange(value.copy(density = density)) },
                    Modifier.semantics { selected = value.density == density },
                    enabled = enabled,
                ) {
                    ButtonLabel(
                        tr(density.label),
                        color =
                            if (value.density == density) StudioTheme.text else StudioTheme.muted,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("界面缩放"), Modifier.weight(1f), fontSize = StudioTheme.brushLabelSize)
            Text("${(value.scale * 100).roundToInt()}%", fontSize = StudioTheme.brushLabelSize)
            ToolButton(Glyph.Minus, "缩小界面", enabled = enabled && value.scale > 0.75f) {
                onChange(value.copy(scale = (value.scale - 0.25f).coerceAtLeast(0.75f)))
            }
            ToolButton(Glyph.Plus, "放大界面", enabled = enabled && value.scale < 2f) {
                onChange(value.copy(scale = (value.scale + 0.25f).coerceAtMost(2f)))
            }
        }
        AppearanceToggle("减少动态效果", value.reducedMotion, enabled) {
            onChange(value.copy(reducedMotion = !value.reducedMotion))
        }
        HorizontalDivider(color = StudioTheme.border, thickness = StudioTheme.hairline)
        SectionLabel("工具顺序与显示")
        Text(
            tr("隐藏的工具可在更多工具中打开"),
            fontSize = StudioTheme.canvasCaptionSize,
            color = StudioTheme.muted,
        )
        value.orderedTools().forEachIndexed { index, tool ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(tool.label),
                    Modifier.weight(1f),
                    fontSize = StudioTheme.brushLabelSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolButton(
                    Glyph.Up,
                    "${tr("上移工具")} · ${tr(tool.label)}",
                    enabled = enabled && index > 0,
                ) {
                    onChange(value.moveTool(tool, -1))
                }
                ToolButton(
                    Glyph.Down,
                    "${tr("下移工具")} · ${tr(tool.label)}",
                    enabled = enabled && index < value.toolOrder.lastIndex,
                ) {
                    onChange(value.moveTool(tool, 1))
                }
                val visible = tool.name !in value.hiddenTools
                ToolButton(
                    if (visible) Glyph.Eye else Glyph.Hidden,
                    "${tr(if (visible) "隐藏工具" else "显示工具")} · ${tr(tool.label)}",
                    selected = visible,
                    enabled = enabled,
                ) {
                    onChange(value.showTool(tool, !visible))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ToolButton(Glyph.Undo, "恢复默认界面", enabled = enabled) { onChange(WorkspaceAppearance()) }
        }
    }
}

@Composable
private fun AppearanceToggle(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(label), Modifier.weight(1f), fontSize = StudioTheme.brushLabelSize)
        ToolButton(
            if (selected) Glyph.Check else Glyph.Minus,
            label,
            selected = selected,
            enabled = enabled,
            onClick = onClick,
        )
    }
}
