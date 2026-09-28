package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.podor.domain.GradientShape
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun GradientDock(controller: StudioController, modifier: Modifier = Modifier, onColor: () -> Unit) {
    val preview = controller.gradientPreview
    val value = controller.gradient
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val enabled = preview != null && !preview.committing && !controller.busy
    var opacityOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .widthIn(max = StudioTheme.gradientDockWidth)
            .fillMaxWidth()
            .padding(horizontal = StudioTheme.transformDockPadding)
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.moveDockBorderWidth, StudioTheme.border, CircleShape)
            .padding(StudioTheme.transformDockGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.transformDockGap),
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
                        controller.gradientEditingStart = true
                        onColor()
                    }
                }
                ToolButton(Glyph.Swap, "反转渐变", selected = value.reversed, enabled = enabled) {
                    controller.gradient = value.copy(reversed = !value.reversed)
                }
                ColorSwatch(value.to, !controller.gradientEditingStart) {
                    if (enabled) {
                        controller.gradientEditingStart = false
                        controller.gradient = value.copy(transparent = false)
                        onColor()
                    }
                }
                ToolButton(Glyph.Hidden, "淡出到透明", selected = value.transparent, enabled = enabled) {
                    controller.gradient = value.copy(transparent = !value.transparent)
                }
                ToolButton(Glyph.Adjustments, "渐变不透明度", enabled = enabled) { opacityOpen = true }
            }
        }
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
    }
    if (opacityOpen)
        StudioModal("渐变不透明度", Glyph.Gradient, { opacityOpen = false }) {
            LabeledSlider("不透明度", value.opacity, 0f..1f, "${(value.opacity*100).roundToInt()}%") {
                controller.gradient = value.copy(opacity = it)
            }
        }
}
