package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.podor.domain.GradientShape
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun GradientDock(
    controller: StudioController,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    onColor: (Boolean) -> Unit,
) {
    val preview = controller.gradientPreview
    val value = controller.gradient
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val enabled = preview != null && !preview.committing && !controller.busy
    var opacityOpen by remember { mutableStateOf(false) }
    ContextActionRow(
        modifier,
        floating,
        maximumWidth = StudioTheme.gradientDockWidth,
        trailing = {
            ToolButton(Glyph.Close, "取消渐变", enabled = preview?.committing != true) {
                controller.cancelGradient()
                controller.tool = Tool.Brush
            }
            ToolButton(
                Glyph.Check,
                "确认渐变",
                prominent = true,
                enabled = enabled && preview.line?.valid() == true,
            ) {
                controller.commitGradient()
            }
        },
    ) {
        if (preview == null)
            Text(
                tr(
                    when {
                        active?.locked == true -> "图层已锁定，请先解锁"
                        active?.visible == false -> "请先显示当前图层"
                        else -> "准备图层…"
                    }
                ),
                fontSize = StudioTheme.moveDockTitleSize,
            )
        else {
            ToolButton(
                Glyph.Gradient,
                "线性渐变",
                selected = value.shape == GradientShape.Linear,
                enabled = enabled,
            ) {
                controller.gradient = value.copy(shape = GradientShape.Linear)
            }
            ToolButton(
                Glyph.EllipseSelection,
                "径向渐变",
                selected = value.shape == GradientShape.Radial,
                enabled = enabled,
            ) {
                controller.gradient = value.copy(shape = GradientShape.Radial)
            }
            ColorSwatch(value.from, controller.gradientEditingStart) {
                if (enabled) {
                    val sameEndpoint = controller.gradientEditingStart
                    controller.gradientEditingStart = true
                    onColor(sameEndpoint)
                }
            }
            ToolButton(Glyph.Swap, "反转渐变", selected = value.reversed, enabled = enabled) {
                controller.gradient = value.copy(reversed = !value.reversed)
            }
            ColorSwatch(value.to, !controller.gradientEditingStart) {
                if (enabled) {
                    val sameEndpoint = !controller.gradientEditingStart
                    controller.gradientEditingStart = false
                    controller.gradient = value.copy(transparent = false)
                    onColor(sameEndpoint)
                }
            }
            ToolButton(Glyph.Hidden, "淡出到透明", selected = value.transparent, enabled = enabled) {
                controller.gradient = value.copy(transparent = !value.transparent)
            }
            Box {
                ToolButton(
                    Glyph.Adjustments,
                    "渐变不透明度",
                    selected = opacityOpen,
                    enabled = enabled,
                ) {
                    opacityOpen = !opacityOpen
                }
                CapsuleSliderPopup(
                    opacityOpen,
                    { opacityOpen = false },
                    "不透明度",
                    value.opacity,
                    0f..1f,
                    "${(value.opacity * 100).roundToInt()}%",
                    Glyph.Opacity,
                    tint = Color(value.from),
                ) {
                    controller.gradient = value.copy(opacity = it)
                }
            }
        }
    }
}
