package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

private fun Float.linePercent() = (this * 100).roundToInt().toString() + "%"

@Composable
internal fun LineGeneratorDock(controller: StudioController, modifier: Modifier = Modifier) {
    val preview = controller.lineGeneratorPreview ?: return
    val value = preview.settings
    fun update(next: LineGeneratorSettings) {
        controller.updateLineGenerator(next)
    }
    val extent =
        (maxOf(controller.document.width, controller.document.height) * 2f).coerceAtLeast(32f)
    Surface(
        modifier.fillMaxWidth(),
        color = StudioTheme.panel,
        shape = StudioTheme.cardShape,
    ) {
        Column(Modifier.padding(StudioTheme.lineGeneratorPadding)) {
            Text(tr(value.kind.label), fontSize = StudioTheme.layerBlendLabelSize)
            if (!preview.committing)
                Column {
                    LabeledSlider(
                        "线条不透明度",
                        value.opacity,
                        0f..1f,
                        value.opacity.linePercent(),
                    ) {
                        update(value.copy(opacity = it))
                    }
                    LabeledSlider(
                        "随机变化",
                        value.randomness,
                        0f..1f,
                        value.randomness.linePercent(),
                    ) {
                        update(value.copy(randomness = it))
                    }
                    LabeledSlider(
                        "起点收锋",
                        value.taperStart,
                        0f..1f,
                        value.taperStart.linePercent(),
                    ) {
                        update(value.copy(taperStart = it))
                    }
                    LabeledSlider(
                        "终点收锋",
                        value.taperEnd,
                        0f..1f,
                        value.taperEnd.linePercent(),
                    ) {
                        update(value.copy(taperEnd = it))
                    }
                    if (value.kind == LineGeneratorKind.Concentration) {
                        LabeledSlider(
                            "起始角度",
                            value.angleStart,
                            -360f..360f,
                            value.angleStart.roundToInt().toString() + "°",
                        ) {
                            update(value.copy(angleStart = it))
                        }
                        LabeledSlider(
                            "展开角度",
                            value.angleSweep,
                            1f..360f,
                            value.angleSweep.roundToInt().toString() + "°",
                        ) {
                            update(value.copy(angleSweep = it))
                        }
                        LabeledSlider(
                            "内圈横向半径",
                            value.inner.rx,
                            0.1f..extent,
                            value.inner.rx.roundToInt().toString(),
                        ) {
                            update(value.copy(inner = value.inner.copy(rx = it)))
                        }
                        LabeledSlider(
                            "内圈纵向半径",
                            value.inner.ry,
                            0.1f..extent,
                            value.inner.ry.roundToInt().toString(),
                        ) {
                            update(value.copy(inner = value.inner.copy(ry = it)))
                        }
                        LabeledSlider(
                            "外圈横向半径",
                            value.outer.rx,
                            0.1f..extent,
                            value.outer.rx.roundToInt().toString(),
                        ) {
                            update(value.copy(outer = value.outer.copy(rx = it)))
                        }
                        LabeledSlider(
                            "外圈纵向半径",
                            value.outer.ry,
                            0.1f..extent,
                            value.outer.ry.roundToInt().toString(),
                        ) {
                            update(value.copy(outer = value.outer.copy(ry = it)))
                        }
                    } else {
                        LabeledSlider(
                            "线条角度",
                            value.angle,
                            -360f..360f,
                            value.angle.roundToInt().toString() + "°",
                        ) {
                            update(value.copy(angle = it))
                        }
                        LabeledSlider(
                            "线条长度",
                            value.length,
                            1f..extent,
                            value.length.roundToInt().toString() + " px",
                        ) {
                            update(value.copy(length = it))
                        }
                        LabeledSlider(
                            "线条间距",
                            value.spacing,
                            0.1f..extent,
                            value.spacing.roundToInt().toString() + " px",
                        ) {
                            update(value.copy(spacing = it))
                        }
                    }
                    ToolButton(Glyph.Palette, "线条颜色", plain = true) {
                        update(
                            value.copy(
                                stroke =
                                    value.stroke.copy(color = vectorRgba(controller.brush.color))
                            )
                        )
                    }
                    OutlinedTextField(
                        value.seed.toString(),
                        { text ->
                            text
                                .toLongOrNull()
                                ?.takeIf { it in 0..4294967295L }
                                ?.let { update(value.copy(seed = it)) }
                        },
                        label = { Text(tr("随机种子")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            preview.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = StudioTheme.layerBlendLabelSize,
                )
            }
        }
    }
}

@Composable
internal fun LineGeneratorActions(
    controller: StudioController,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = controller.lineGeneratorPreview ?: return
    val value = preview.settings
    val enabled = !preview.committing
    var kindMenu by remember(preview) { mutableStateOf(false) }
    var countOpen by remember(preview) { mutableStateOf(false) }
    var widthOpen by remember(preview) { mutableStateOf(false) }
    ContextActionRow(
        modifier,
        floating = true,
        trailing = {
            ToolButton(Glyph.Close, "取消线条", enabled = !preview.committing) {
                controller.cancelLineGenerator()
            }
            ToolButton(
                Glyph.Check,
                "确认线条",
                enabled =
                    !preview.committing &&
                        !preview.updating &&
                        preview.error == null &&
                        preview.settings.valid(controller.document.maxGeneratedLines),
            ) {
                controller.commitLineGenerator()
            }
        },
    ) {
        Box {
            ToolButton(Glyph.Line, "线条类型", selected = kindMenu, enabled = enabled) {
                kindMenu = true
            }
            CapsulePopup(kindMenu, { kindMenu = false }) {
                ContextActionRow(Modifier, floating = true) {
                    LineGeneratorKind.entries.forEach { kind ->
                        ToolButton(
                            if (kind == LineGeneratorKind.Concentration) Glyph.Assistant
                            else Glyph.Line,
                            kind.label,
                            selected = value.kind == kind,
                            enabled = enabled,
                        ) {
                            kindMenu = false
                            controller.changeLineGeneratorKind(kind)
                        }
                    }
                }
            }
        }
        Box {
            ToolButton(Glyph.PixelGrid, "线条数量", selected = countOpen, enabled = enabled) {
                widthOpen = false
                countOpen = !countOpen
            }
            CapsuleSliderPopup(
                countOpen,
                { countOpen = false },
                "线条数量",
                value.count.toFloat(),
                1f..controller.document.maxGeneratedLines.toFloat(),
                value.count.toString(),
                Glyph.PixelGrid,
            ) {
                controller.updateLineGenerator(value.copy(count = it.roundToInt()))
            }
        }
        Text(
            value.count.toString(),
            fontSize = StudioTheme.layerBlendLabelSize,
            color = StudioTheme.text,
        )
        Box {
            ToolButton(Glyph.BrushSize, "线宽", selected = widthOpen, enabled = enabled) {
                countOpen = false
                widthOpen = !widthOpen
            }
            CapsuleSliderPopup(
                widthOpen,
                { widthOpen = false },
                "线宽",
                value.stroke.width,
                1f..256f,
                "${value.stroke.width.roundToInt()} px",
                Glyph.BrushSize,
            ) {
                controller.updateLineGenerator(value.copy(stroke = value.stroke.copy(width = it)))
            }
        }
        Text(
            "${value.stroke.width.roundToInt()} px",
            fontSize = StudioTheme.layerBlendLabelSize,
            color = StudioTheme.text,
        )
        ToolButton(Glyph.Settings, "设置线条", enabled = enabled) { onOptions() }
    }
}
