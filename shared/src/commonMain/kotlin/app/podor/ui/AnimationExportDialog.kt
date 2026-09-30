package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

private enum class AnimationAlphaChoice(val label: String) {
    Threshold("透明阈值"),
    Matte("合成底色"),
    Exact("精确保留透明度"),
}

@Composable
fun AnimationExportDialog(
    controller: StudioController,
    onExport: (AnimationExportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val document = controller.document
    val limits = document.animationExport ?: return
    if (!limits.available) return
    val animation = document.animation?.takeIf { it.enabled } ?: return
    var format by remember { mutableStateOf(StudioDefaults.animationExportFormat) }
    var tagId by remember {
        mutableStateOf(
            controller.animationTagId?.takeIf { id -> animation.tags.any { it.id == id } }
        )
    }
    var direction by remember { mutableStateOf(controller.animationDirection) }
    var repeat by remember { mutableStateOf("0") }
    var advanced by remember { mutableStateOf(false) }
    var colorPolicy by remember { mutableStateOf(StudioDefaults.animationExportColorPolicy) }
    var alphaChoice by remember { mutableStateOf(AnimationAlphaChoice.Threshold) }
    var cutoff by remember { mutableStateOf(StudioDefaults.animationExportAlphaThreshold) }
    var matteHex by remember {
        mutableStateOf(
            (controller.brush.color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        )
    }
    var timing by remember { mutableStateOf(StudioDefaults.animationExportTiming) }
    var columns by remember { mutableStateOf(StudioDefaults.animationExportColumns.toString()) }
    var padding by remember { mutableStateOf(StudioDefaults.animationExportPadding.toString()) }
    val matte = matteHex.takeIf { it.length == 6 }?.toIntOrNull(16)
    val options =
        AnimationExportOptions(
            format = format,
            scope =
                tagId?.let { AnimationExportScope.Tag(it) }
                    ?: AnimationExportScope.All(direction, repeat.toIntOrNull() ?: -1),
            colorPolicy = colorPolicy,
            alpha =
                when (alphaChoice) {
                    AnimationAlphaChoice.Threshold -> GifAlphaPolicy.Threshold(cutoff)
                    AnimationAlphaChoice.Matte ->
                        GifAlphaPolicy.Matte(
                            matte?.let { listOf(it shr 16, (it shr 8) and 255, it and 255) }
                                ?: emptyList()
                        )
                    AnimationAlphaChoice.Exact -> GifAlphaPolicy.Exact
                },
            timing = timing,
            columns = columns.toIntOrNull() ?: -1,
            padding = padding.toIntOrNull() ?: -1,
        )
    val issue = options.validation(document)
    val enabled =
        controller.ready &&
            !controller.busy &&
            !controller.drawingInput &&
            !controller.animationTransition &&
            !controller.animationPlaying &&
            issue == null
    val selectedTag = animation.tags.firstOrNull { it.id == tagId }
    StudioAlertDialog(
        title = "导出动画",
        glyph = Glyph.Export,
        confirmLabel = "导出",
        onDismissRequest = onDismiss,
        enabled = enabled,
        onConfirm = {
            if (options.validation(controller.document) == null) onExport(options)
        },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasFieldsGap),
            ) {
                AnimationExportFormat.entries.forEach { value ->
                    StudioTextButton(
                        { format = value },
                        Modifier.weight(1f),
                    ) {
                        val selected = format == value
                        val tint = if (selected) StudioTheme.accent else StudioTheme.muted
                        StudioIcon(
                            if (value == AnimationExportFormat.Gif) Glyph.Animation
                            else Glyph.TileGrid,
                            tint,
                        )
                        Spacer(Modifier.width(StudioTheme.animationGap))
                        ButtonLabel(
                            if (value == AnimationExportFormat.Gif) "GIF" else tr("PNG 图集"),
                            color = tint,
                        )
                        if (selected) {
                            Spacer(Modifier.width(StudioTheme.animationGap))
                            StudioIcon(
                                Glyph.Check,
                                tint,
                                Modifier.size(StudioTheme.layerStatusIconSize),
                            )
                        }
                    }
                }
            }
            Text(
                "${document.width} × ${document.height} px · ${animation.frames.size} ${tr("动画帧")}",
                fontSize = StudioTheme.canvasCaptionSize,
                color = StudioTheme.muted,
            )
            AnimationExportChoice(
                "导出范围",
                tagId,
                listOf(null) + animation.tags.map { it.id },
                { id ->
                    if (id == null) tr("全部动画帧")
                    else animation.tags.firstOrNull { it.id == id }?.name ?: tr("播放范围已失效")
                },
            ) {
                tagId = it
            }
            if (tagId == null) {
                AnimationExportChoice(
                    "播放方向",
                    direction,
                    AnimationDirection.entries,
                    { tr(it.exportLabel()) },
                ) {
                    direction = it
                }
                OutlinedTextField(
                    repeat,
                    { repeat = it.filter(Char::isDigit).take(5) },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr("循环次数")) },
                    supportingText = { Text(tr("0 为无限循环")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = repeat.toIntOrNull()?.let { it in 0..65535 } != true,
                )
            } else if (selectedTag != null) {
                Text(
                    "${tr(selectedTag.direction.exportLabel())} · ${tr("循环次数")} ${selectedTag.repeat}",
                    fontSize = StudioTheme.canvasCaptionSize,
                    color = StudioTheme.muted,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("高级设置"), Modifier.weight(1f), fontSize = StudioTheme.canvasLabelSize)
                ToolButton(Glyph.Settings, "高级设置", selected = advanced, plain = true) {
                    advanced = !advanced
                }
            }
            if (advanced) {
                if (format == AnimationExportFormat.Gif) {
                    AnimationExportChoice(
                        "颜色处理",
                        colorPolicy,
                        GifColorPolicy.entries,
                        { tr(if (it == GifColorPolicy.Quantize) "调色板量化" else "精确保留颜色") },
                    ) {
                        colorPolicy = it
                    }
                    AnimationExportChoice(
                        "透明处理",
                        alphaChoice,
                        AnimationAlphaChoice.entries,
                        { tr(it.label) },
                    ) {
                        alphaChoice = it
                    }
                    when (alphaChoice) {
                        AnimationAlphaChoice.Threshold ->
                            LabeledSlider("透明阈值", cutoff.toFloat(), 1f..255f, "$cutoff") {
                                cutoff = it.roundToInt()
                            }
                        AnimationAlphaChoice.Matte -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(StudioTheme.animationGap),
                            ) {
                                OutlinedTextField(
                                    matteHex,
                                    { value ->
                                        matteHex =
                                            value
                                                .filter {
                                                    it.isDigit() || it.uppercaseChar() in 'A'..'F'
                                                }
                                                .take(6)
                                                .uppercase()
                                    },
                                    Modifier.weight(1f),
                                    label = { Text(tr("合成底色")) },
                                    prefix = { Text("#") },
                                    trailingIcon = {
                                        if (matte != null)
                                            Box(
                                                Modifier.size(StudioTheme.colorSliderHeight)
                                                    .background(
                                                        Color(0xFF000000L or matte.toLong())
                                                    )
                                            )
                                    },
                                    singleLine = true,
                                    isError = matte == null,
                                )
                                ToolButton(Glyph.Picker, "使用画笔颜色", plain = true) {
                                    matteHex =
                                        (controller.brush.color and 0xFFFFFF)
                                            .toString(16)
                                            .padStart(6, '0')
                                            .uppercase()
                                }
                            }
                        }
                        AnimationAlphaChoice.Exact -> Unit
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("保持精确时长"),
                            Modifier.weight(1f),
                            fontSize = StudioTheme.canvasLabelSize,
                        )
                        Switch(
                            timing == GifTimingPolicy.Exact,
                            {
                                timing = if (it) GifTimingPolicy.Exact else GifTimingPolicy.Round
                            },
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.canvasFieldsGap)) {
                        OutlinedTextField(
                            columns,
                            { columns = it.filter(Char::isDigit).take(5) },
                            Modifier.weight(1f),
                            label = { Text(tr("图集列数")) },
                            supportingText = { Text(tr("0 为自动排列")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError =
                                columns.toIntOrNull()?.let { it in 0..animation.frames.size } !=
                                    true,
                        )
                        OutlinedTextField(
                            padding,
                            { padding = it.filter(Char::isDigit).take(5) },
                            Modifier.weight(1f),
                            label = { Text(tr("透明边距")) },
                            suffix = { Text("px") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError =
                                padding.toIntOrNull()?.let { it in 0..limits.maxAtlasPadding } !=
                                    true,
                        )
                    }
                }
            }
            Text(
                tr(
                    issue?.label
                        ?: if (format == AnimationExportFormat.Atlas)
                            "ZIP 内含 PNG 图集与播放数据，保留透明度和帧时长。"
                        else if (
                            colorPolicy == GifColorPolicy.Quantize &&
                                alphaChoice == AnimationAlphaChoice.Threshold &&
                                timing == GifTimingPolicy.Round
                        )
                            "GIF 颜色量化，透明边缘与时长可能变化。"
                        else "GIF 仅支持有限颜色、二值透明和 10 ms 时长精度。"
                ),
                fontSize = StudioTheme.canvasCaptionSize,
                color = if (issue == null) StudioTheme.muted else StudioTheme.accent,
            )
        },
    )
}

@Composable
private fun <T> AnimationExportChoice(
    title: String,
    value: T,
    values: List<T>,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr(title),
            Modifier.weight(1f),
            fontSize = StudioTheme.canvasLabelSize,
            color = StudioTheme.muted,
        )
        Box {
            StudioTextButton({ expanded = true }) {
                ButtonLabel(label(value))
                Spacer(Modifier.width(StudioTheme.animationGap))
                StudioIcon(Glyph.Chevron, StudioTheme.muted)
            }
            StudioDropdownMenu(expanded, { expanded = false }) {
                values.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(label(item)) },
                        trailingIcon = { if (item == value) StudioIcon(Glyph.Check) },
                        onClick = {
                            onChange(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun AnimationDirection.exportLabel() =
    when (this) {
        AnimationDirection.Forward -> "正向播放"
        AnimationDirection.Reverse -> "反向播放"
        AnimationDirection.PingPong -> "往返播放"
        AnimationDirection.PingPongReverse -> "反向往返"
    }
