package app.podor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.podor.domain.ColorHarmony

@Composable
fun ColorHarmonyControls(color: Long, onColorChange: (Long) -> Unit) {
    var mode by remember { mutableStateOf(ColorHarmony.Complementary) }
    var expanded by remember { mutableStateOf(false) }
    val colors = remember(color, mode) { mode.colors(color).distinct() }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
    ) {
        Box {
            ToolButton(Glyph.Palette, "色彩和谐", plain = true) { expanded = !expanded }
            StudioDropdownMenu(expanded, { expanded = false }) {
                ColorHarmony.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                tr(option.label),
                                color = StudioTheme.text,
                                fontSize = StudioTheme.paletteLabelSize,
                            )
                        },
                        trailingIcon = { if (option == mode) StudioIcon(Glyph.Check) },
                        onClick = {
                            mode = option
                            expanded = false
                        },
                    )
                }
            }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
        ) {
            colors.forEach { swatch ->
                ColorSwatch(swatch, swatch == color) { onColorChange(swatch) }
            }
        }
    }
}
