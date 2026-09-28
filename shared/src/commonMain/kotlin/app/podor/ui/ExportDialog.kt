package app.podor.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.ExportFormat
import app.podor.domain.ExportOptions
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun ExportDialog(controller: StudioController, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(ExportOptions()) }
    var confirmed by remember { mutableStateOf(false) }
    StudioModal(
        "导出图像",
        Glyph.Export,
        {
            if (confirmed) controller.export(options)
            onDismiss()
        },
        width = 480.dp,
    ) { dismiss ->
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
            ExportSettings(controller, options) { options = it }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StudioTextButton(dismiss) { Text(tr("取消"), color = StudioTheme.muted) }
            Spacer(Modifier.width(10.dp))
            ActionButton(
                "导出",
                {
                    confirmed = true
                    dismiss()
                },
                enabled = controller.ready && !controller.busy,
                glyph = Glyph.Export,
            )
        }
    }
}

@Composable
fun ExportSettings(
    controller: StudioController,
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(StudioTheme.cardShape)
                .background(StudioTheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtworkPreview(
                controller.previews.images[0],
                controller.document.width,
                controller.document.height,
                Modifier.fillMaxWidth().height(174.dp),
                options.transparent || options.format.preservesLayers,
            )
            Text(
                "${controller.document.width} × ${controller.document.height} px",
                Modifier.padding(top = 12.dp),
                fontSize = 11.sp,
                color = StudioTheme.muted,
            )
        }
        Column(
            Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            controller.exportFormats.chunked(StudioTheme.exportColumns).forEach { formats ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    formats.forEach { format ->
                        val selected = format == options.format
                        ChoiceSurface(
                            selected,
                            {
                                onChange(
                                    options.copy(
                                        format = format,
                                        transparent =
                                            options.transparent && format.supportsTransparency,
                                    )
                                )
                            },
                            Modifier.weight(1f),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    format.label,
                                    Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color =
                                        if (selected) StudioTheme.onSelection else StudioTheme.text,
                                )
                                if (selected)
                                    StudioIcon(
                                        Glyph.Check,
                                        StudioTheme.accent,
                                        Modifier.size(13.dp),
                                    )
                            }
                            Text(
                                tr(
                                    when {
                                        format.preservesLayers -> "保留图层"
                                        format == ExportFormat.Jpeg -> "高兼容"
                                        else -> "无损"
                                    }
                                ),
                                Modifier.padding(top = 6.dp),
                                fontSize = 10.sp,
                                color = StudioTheme.muted,
                            )
                        }
                    }
                    repeat(StudioTheme.exportColumns - formats.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Crossfade(
            options.format.preservesLayers,
            animationSpec = tween(StudioMotion.feedbackMillis),
        ) { layered ->
            if (layered) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StudioIcon(Glyph.Layers, StudioTheme.accent, Modifier.size(21.dp))
                    Text(tr("保留图层与透明度"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${controller.document.layers.size}",
                        fontSize = 12.sp,
                        color = StudioTheme.muted,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("透明背景"), fontSize = 13.sp)
                        if (!options.format.supportsTransparency)
                            Text(tr("JPEG 使用白色背景"), fontSize = 11.sp, color = StudioTheme.muted)
                    }
                    Switch(
                        options.transparent,
                        { onChange(options.copy(transparent = it)) },
                        enabled =
                            options.format.supportsTransparency && !options.format.preservesLayers,
                    )
                }
            }
        }
        AnimatedVisibility(
            options.format == ExportFormat.Jpeg,
            enter =
                expandVertically(tween(StudioMotion.panelMillis, easing = StudioMotion.easing)) +
                    fadeIn(tween(StudioMotion.feedbackMillis)),
            exit =
                shrinkVertically(
                    tween(StudioMotion.dismissMillis, easing = StudioMotion.exitEasing)
                ) + fadeOut(tween(StudioMotion.feedbackMillis)),
        ) {
            LabeledSlider("图像质量", options.quality.toFloat(), 1f..100f, "${options.quality}%") {
                onChange(options.copy(quality = it.roundToInt()))
            }
        }
    }
}
