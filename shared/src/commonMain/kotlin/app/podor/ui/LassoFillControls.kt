package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun LassoFillModes(controller: StudioController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Glyph.LassoFill, "圈选填色", selected = !controller.lassoErase, plain = true) {
            controller.cancelSelectionGesture()
            controller.lassoErase = false
        }
        ToolButton(Glyph.Eraser, "圈选擦除", selected = controller.lassoErase, plain = true) {
            controller.cancelSelectionGesture()
            controller.lassoErase = true
        }
    }
}

@Composable
fun LassoFillDock(controller: StudioController, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.selectionDockBorder, StudioTheme.border, CircleShape)
            .padding(StudioTheme.selectionDockPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LassoFillModes(controller)
        Box {
            ToolButton(Glyph.Opacity, "不透明度", selected = expanded, plain = true) {
                expanded = !expanded
            }
            StudioDropdownMenu(expanded, { expanded = false }) {
                Column(
                    Modifier.width(StudioTheme.lassoOptionsWidth)
                        .padding(StudioTheme.lassoOptionsPadding)
                ) {
                    LabeledSlider(
                        "不透明度",
                        controller.brush.opacity,
                        0.01f..1f,
                        "${(controller.brush.opacity * 100).roundToInt()}%",
                        tint = Color(controller.brush.color),
                        glyph = Glyph.Opacity,
                    ) {
                        controller.brush = controller.brush.copy(opacity = it)
                    }
                }
            }
        }
    }
}
