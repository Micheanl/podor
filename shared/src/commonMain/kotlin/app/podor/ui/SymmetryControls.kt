package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun SymmetryControls(controller: StudioController) {
    var expanded by remember { mutableStateOf(false) }
    val settings = controller.symmetry
    val available = controller.tool == Tool.Brush || controller.tool == Tool.Eraser
    Box {
        ToolButton(
            Glyph.Mirror,
            if (available) "对称绘画" else "对称绘画适用于画笔和橡皮",
            selected = available && settings.mode != SymmetryMode.Off,
            enabled = available,
        ) {
            expanded = !expanded
        }
        StudioDropdownMenu(
            expanded,
            { expanded = false },
        ) {
            Column(
                Modifier.width(StudioTheme.symmetryControlsWidth).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap),
            ) {
                SectionLabel("对称绘画")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SymmetryMode.entries.forEach { mode ->
                        val glyph =
                            when (mode) {
                                SymmetryMode.Off -> Glyph.Close
                                SymmetryMode.Vertical -> Glyph.Mirror
                                SymmetryMode.Horizontal -> Glyph.MirrorVertical
                                SymmetryMode.Quadrant -> Glyph.Home
                            }
                        ToolButton(glyph, mode.label, selected = settings.mode == mode) {
                            controller.symmetry = settings.copy(mode = mode)
                        }
                    }
                }
                if (settings.mode != SymmetryMode.Off) {
                    Text(
                        tr(settings.mode.label),
                        fontSize = StudioTheme.brushCaptionSize,
                        color = StudioTheme.muted,
                    )
                    if (settings.mode.vertical)
                        LabeledSlider(
                            "横向位置",
                            settings.x,
                            0f..1f,
                            "${(settings.x * 100).roundToInt()}%",
                        ) {
                            controller.symmetry = settings.copy(x = it)
                        }
                    if (settings.mode.horizontal)
                        LabeledSlider(
                            "纵向位置",
                            settings.y,
                            0f..1f,
                            "${(settings.y * 100).roundToInt()}%",
                        ) {
                            controller.symmetry = settings.copy(y = it)
                        }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("显示对称轴"),
                            Modifier.weight(1f),
                            fontSize = StudioTheme.brushLabelSize,
                        )
                        Switch(
                            settings.guides,
                            { controller.symmetry = settings.copy(guides = it) },
                        )
                    }
                    StudioTextButton({
                        controller.symmetry =
                            settings.copy(
                                x = StudioDefaults.symmetryAxis,
                                y = StudioDefaults.symmetryAxis,
                            )
                    }) {
                        ButtonLabel(tr("对称轴居中"))
                    }
                }
            }
        }
    }
}
