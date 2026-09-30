package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

private fun Float.linePercent() = (this * 100).roundToInt().toString() + "%"

@Composable
internal fun LineGeneratorDock(controller: StudioController, modifier: Modifier = Modifier) {
    val preview = controller.lineGeneratorPreview ?: return
    val value = preview.settings
    var kindMenu by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    fun update(next: LineGeneratorSettings) {
        controller.updateLineGenerator(next)
    }
    val extent =
        (maxOf(controller.document.width, controller.document.height) * 2f).coerceAtLeast(32f)
    Surface(
        modifier.width(StudioTheme.lineGeneratorWidth),
        color = StudioTheme.panel,
        shape = StudioTheme.cardShape,
    ) {
        Column(Modifier.padding(StudioTheme.lineGeneratorPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ToolButton(Glyph.Line, "线条类型", plain = true, enabled = !preview.committing) {
                        kindMenu = true
                    }
                    StudioDropdownMenu(kindMenu, { kindMenu = false }) {
                        LineGeneratorKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(tr(kind.label)) },
                                onClick = {
                                    kindMenu = false
                                    controller.changeLineGeneratorKind(kind)
                                },
                            )
                        }
                    }
                }
                Text(
                    tr(value.kind.label),
                    Modifier.weight(1f),
                    fontSize = StudioTheme.layerBlendLabelSize,
                )
                ToolButton(
                    Glyph.Settings,
                    "设置线条",
                    selected = advanced,
                    enabled = !preview.committing,
                    plain = true,
                ) {
                    advanced = !advanced
                }
                ToolButton(Glyph.Close, "取消线条", enabled = !preview.committing, plain = true) {
                    controller.cancelLineGenerator()
                }
                ToolButton(
                    Glyph.Check,
                    "确认线条",
                    enabled =
                        !preview.committing &&
                            !preview.updating &&
                            preview.error == null &&
                            value.valid(controller.document.maxGeneratedLines),
                    plain = true,
                ) {
                    controller.commitLineGenerator()
                }
            }
            if (!preview.committing)
                Column(
                    Modifier.heightIn(max = StudioTheme.lineGeneratorControlsHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    LabeledSlider(
                        "线条数量",
                        value.count.toFloat(),
                        1f..controller.document.maxGeneratedLines.toFloat(),
                        value.count.toString(),
                    ) {
                        update(value.copy(count = it.roundToInt()))
                    }
                    LabeledSlider(
                        "线宽",
                        value.stroke.width,
                        1f..256f,
                        value.stroke.width.roundToInt().toString() + " px",
                    ) {
                        update(value.copy(stroke = value.stroke.copy(width = it)))
                    }
                    if (advanced) {
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
                                        value.stroke.copy(
                                            color = vectorRgba(controller.brush.color)
                                        )
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
