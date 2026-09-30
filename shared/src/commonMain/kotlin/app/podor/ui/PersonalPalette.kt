package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.podor.domain.PaletteFileFormat
import app.podor.domain.StudioDefaults
import app.podor.presentation.StudioController

@Composable
fun PersonalPalette(controller: StudioController, color: Long, onColorChange: (Long) -> Unit) {
    val palette = controller.preferences.palette
    var files by remember { mutableStateOf(false) }
    var flattenExport by remember { mutableStateOf<PaletteFileFormat?>(null) }
    Column(
        Modifier.fillMaxWidth()
            .clip(StudioTheme.paletteShape)
            .background(StudioTheme.background)
            .padding(StudioTheme.palettePadding),
        verticalArrangement = Arrangement.spacedBy(StudioTheme.paletteGap),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("我的色卡"),
                Modifier.weight(1f),
                color = StudioTheme.muted,
                fontSize = StudioTheme.paletteLabelSize,
            )
            if (controller.extractingPalette) {
                Box(Modifier.size(StudioTheme.controlSize), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(StudioTheme.iconSize),
                        strokeWidth = StudioTheme.paletteProgressWidth,
                    )
                }
            } else {
                ToolButton(
                    Glyph.Palette,
                    "从画布提取颜色",
                    enabled =
                        controller.ready &&
                            !controller.busy &&
                            palette.size < StudioDefaults.maxPaletteColors,
                ) {
                    controller.extractPalette()
                }
            }
            ToolButton(
                Glyph.Plus,
                "保存当前颜色",
                enabled = color !in palette && palette.size < StudioDefaults.maxPaletteColors,
            ) {
                controller.addPaletteColors(listOf(color))
            }
            ToolButton(Glyph.Minus, "移除此颜色", enabled = color in palette) {
                controller.removePaletteColor(color)
            }
            Box {
                ToolButton(
                    Glyph.More,
                    "色板文件",
                    enabled =
                        controller.ready && !controller.busy && controller.supportsPaletteFiles,
                ) {
                    files = !files
                }
                StudioDropdownMenu(files, { files = false }) {
                    Row(Modifier.padding(StudioTheme.palettePadding)) {
                        ToolButton(Glyph.Folder, "导入色板") {
                            files = false
                            controller.file(StudioController.FileAction.ImportPalette)
                        }
                        controller.paletteFormats.forEach { format ->
                            ToolButton(
                                Glyph.Export,
                                "${tr("导出色板")} · ${format.label}",
                                enabled = palette.isNotEmpty(),
                            ) {
                                files = false
                                if (controller.paletteExportLosesMetadata(format))
                                    flattenExport = format
                                else controller.exportPalette(format)
                            }
                        }
                    }
                }
            }
        }
        palette.chunked(StudioDefaults.paletteColumns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { swatch ->
                    ColorSwatch(swatch, color == swatch) { onColorChange(swatch) }
                }
                repeat(StudioDefaults.paletteColumns - row.size) {
                    Spacer(Modifier.size(StudioTheme.paletteSwatchSize))
                }
            }
        }
    }
    flattenExport?.let { format ->
        StudioAlertDialog(
            "${tr("导出色板")} · ${format.label}",
            Glyph.Palette,
            "仅导出颜色",
            { flattenExport = null },
            onConfirm = { controller.exportPalette(format, flattenMetadata = true) },
            text = {
                Text(
                    tr(
                        if (format == PaletteFileFormat.Gpl) "GPL 保留颜色和名称，不能保存分组及专色类型。"
                        else "PAL 只保存颜色，不能保存名称、分组及专色类型。"
                    )
                )
            },
        )
    }
}
