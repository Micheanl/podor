package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun IndexedPaletteControls(controller: StudioController) {
    val palette = controller.document.indexedPalette ?: return
    val index = controller.indexedColorIndex.coerceIn(palette.colors.indices)
    val original = palette.argb(index)
    var draft by remember(index, original) { mutableLongStateOf(original) }
    var hsv by remember(index, original) { mutableStateOf(HsvColor.fromArgb(original)) }
    var deleting by remember { mutableStateOf(false) }
    val enabled = controller.ready && !controller.busy
    val replacements =
        palette.order.filter {
            it != index && (index != palette.transparent || palette.colors[it][3] == 0)
        }
    fun update(value: HsvColor) {
        hsv = value
        draft = (value.toArgb() and 0xFFFFFFL) or (draft and 0xFF000000L)
    }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("工程调色板"),
                Modifier.weight(1f),
                fontSize = StudioTheme.paletteLabelSize,
                color = StudioTheme.muted,
            )
            Text(
                "${palette.colors.size}/${StudioDefaults.maxIndexedColors}",
                fontSize = StudioTheme.paletteLabelSize,
                color = StudioTheme.muted,
            )
            ToolButton(Glyph.Palette, "转换为全彩色", enabled = enabled) {
                controller.convertColorMode(DocumentColorMode.Rgba)
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(StudioDefaults.paletteColumns),
            Modifier.fillMaxWidth().height(StudioTheme.indexedPaletteGridHeight),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
            verticalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
        ) {
            items(palette.order, key = { it }) { item ->
                val name =
                    controller.document.asepriteMetadata?.indexedPaletteNames?.getOrNull(item)
                val label =
                    tr("索引色") +
                        " $item" +
                        (name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                Box(
                    Modifier.clearAndSetSemantics {
                        contentDescription = label
                        selected = item == index
                        onClick {
                            if (enabled) controller.selectIndexedColor(item)
                            enabled
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    ColorSwatch(palette.argb(item), item == index) {
                        if (enabled) controller.selectIndexedColor(item)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap)) {
            ToolButton(Glyph.Up, "向前排列颜色", enabled = enabled && palette.order.indexOf(index) > 0) {
                controller.reorderIndexedColor(index, -1)
            }
            ToolButton(
                Glyph.Down,
                "向后排列颜色",
                enabled = enabled && palette.order.indexOf(index) < palette.order.lastIndex,
            ) {
                controller.reorderIndexedColor(index, 1)
            }
            ToolButton(
                Glyph.Plus,
                "添加调色板颜色",
                enabled = enabled && palette.colors.size < StudioDefaults.maxIndexedColors,
            ) {
                controller.addIndexedColor(draft)
            }
            Box {
                ToolButton(
                    Glyph.Trash,
                    "替换并移除颜色",
                    enabled = enabled && palette.colors.size > 2 && replacements.isNotEmpty(),
                ) {
                    deleting = !deleting
                }
                StudioDropdownMenu(deleting, { deleting = false }) {
                    Column(
                        Modifier.padding(StudioTheme.palettePadding),
                        verticalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
                    ) {
                        Text(
                            tr("替换为"),
                            fontSize = StudioTheme.paletteLabelSize,
                            color = StudioTheme.muted,
                        )
                        replacements.chunked(StudioDefaults.paletteColumns).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap)
                            ) {
                                row.forEach { replacement ->
                                    val label = tr("替换为") + " " + tr("索引色") + " $replacement"
                                    Box(
                                        Modifier.clearAndSetSemantics {
                                            contentDescription = label
                                            onClick {
                                                if (enabled)
                                                    controller.removeIndexedColor(
                                                        index,
                                                        replacement,
                                                    )
                                                deleting = false
                                                enabled
                                            }
                                        }
                                    ) {
                                        ColorSwatch(palette.argb(replacement), false) {
                                            if (enabled)
                                                controller.removeIndexedColor(index, replacement)
                                            deleting = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ToolButton(Glyph.Check, "应用到调色板", enabled = enabled && draft != original) {
                controller.setIndexedColor(index, draft)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(
                hsv,
                { if (enabled) update(it) },
                Modifier.widthIn(max = StudioTheme.colorWheelSize).fillMaxWidth(),
            )
        }
        HsvSliders(hsv) { if (enabled) update(it) }
        if (index != palette.transparent)
            LabeledSlider(
                "颜色透明度",
                (draft shr 24 and 255) / 255f,
                0f..1f,
                "${((draft shr 24 and 255) * 100 / 255)}%",
                tint = Color(draft),
            ) {
                if (enabled)
                    draft = (draft and 0xFFFFFFL) or ((it * 255).roundToInt().toLong() shl 24)
            }
    }
}
