package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import app.podor.domain.AsepriteCompanionPalette
import app.podor.domain.StudioDefaults

@Composable
internal fun ProjectPalette(
    palette: AsepriteCompanionPalette,
    color: Long,
    onColorChange: (Long) -> Unit,
) {
    if (palette.colors.isEmpty()) return
    val rows =
        ((palette.colors.size + StudioDefaults.paletteColumns - 1) / StudioDefaults.paletteColumns)
            .coerceAtMost(4)
    SectionLabel("工程调色板", palette.colors.size.toString())
    LazyVerticalGrid(
        GridCells.Fixed(StudioDefaults.paletteColumns),
        Modifier.fillMaxWidth()
            .height(
                (StudioTheme.paletteSwatchSize + StudioTheme.paletteGap) * rows -
                    StudioTheme.paletteGap
            ),
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
        verticalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
    ) {
        items(palette.colors.indices.toList(), key = { it }) { index ->
            val rgba = palette.colors[index]
            val swatch =
                (rgba[3].toLong() shl 24) or
                    (rgba[0].toLong() shl 16) or
                    (rgba[1].toLong() shl 8) or
                    rgba[2].toLong()
            val name = palette.names.getOrNull(index)?.takeIf { it.isNotBlank() }
            val label =
                (name ?: tr("工程调色板")) +
                    " · #" +
                    (swatch and 0xffffff).toString(16).padStart(6, '0').uppercase()
            Box(
                Modifier.clearAndSetSemantics {
                    contentDescription = label
                    selected = color == swatch
                    onClick {
                        onColorChange(swatch)
                        true
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                ColorSwatch(swatch, color == swatch) { onColorChange(swatch) }
            }
        }
    }
}
