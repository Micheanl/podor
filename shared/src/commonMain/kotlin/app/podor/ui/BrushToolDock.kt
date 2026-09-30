package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.podor.domain.BrushRaster
import app.podor.domain.StudioDefaults
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
internal fun BrushToolDock(
    controller: StudioController,
    onBrushes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brush = controller.brush
    val enabled =
        controller.ready &&
            !controller.busy &&
            !controller.drawingInput &&
            !controller.animationPlaying &&
            !controller.animationTransition
    var sizeOpen by remember { mutableStateOf(false) }
    var opacityOpen by remember { mutableStateOf(false) }
    ContextActionRow(modifier, floating = true) {
        ToolButton(
            if (controller.tool == Tool.Eraser) Glyph.Eraser else Glyph.Brush,
            "笔刷库",
            enabled = enabled,
        ) {
            onBrushes()
        }
        Box {
            ToolButton(Glyph.BrushSize, "大小", selected = sizeOpen, enabled = enabled) {
                opacityOpen = false
                sizeOpen = !sizeOpen
            }
            CapsuleSliderPopup(
                sizeOpen,
                { sizeOpen = false },
                "大小",
                brush.size,
                StudioDefaults.minBrushSize..StudioDefaults.maxBrushSize,
                "${brush.size.roundToInt()} px",
                Glyph.BrushSize,
                tint = Color(brush.color),
            ) {
                if (enabled)
                    controller.brush =
                        brush.copy(
                            size =
                                if (brush.preset.raster == BrushRaster.Antialiased) it
                                else it.roundToInt().toFloat()
                        )
            }
        }
        Text(
            "${brush.size.roundToInt()} px",
            fontSize = StudioTheme.layerBlendLabelSize,
            color = StudioTheme.text,
        )
        Box {
            ToolButton(Glyph.Opacity, "不透明度", selected = opacityOpen, enabled = enabled) {
                sizeOpen = false
                opacityOpen = !opacityOpen
            }
            CapsuleSliderPopup(
                opacityOpen,
                { opacityOpen = false },
                "不透明度",
                brush.opacity,
                0.01f..1f,
                "${(brush.opacity * 100).roundToInt()}%",
                Glyph.Opacity,
                tint = Color(brush.color),
            ) {
                if (enabled) controller.brush = brush.copy(opacity = it)
            }
        }
        Text(
            "${(brush.opacity * 100).roundToInt()}%",
            fontSize = StudioTheme.layerBlendLabelSize,
            color = StudioTheme.text,
        )
    }
}
