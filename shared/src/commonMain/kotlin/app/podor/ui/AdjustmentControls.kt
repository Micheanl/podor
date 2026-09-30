package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun AdjustmentControls(controller: StudioController) {
    val preview = controller.adjustmentPreview
    if (preview != null) {
        val settings = preview.settings
        Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.adjustmentGap)) {
            SectionLabel(settings.kind.label)
            Text(
                tr(
                    if (preview.nodeEditing) "调整图层"
                    else if (controller.document.selection != null) "仅作用于当前图层的选区" else "作用于当前图层"
                ),
                fontSize = StudioTheme.adjustmentHintSize,
                color = StudioTheme.muted,
            )
            if (settings.kind == AdjustmentKind.Tone) {
                LabeledSlider("亮度", settings.brightness, -1f..1f, settings.brightness.percent()) {
                    controller.updateAdjustment(settings.copy(brightness = it))
                }
                LabeledSlider("对比度", settings.contrast, -1f..1f, settings.contrast.percent()) {
                    controller.updateAdjustment(settings.copy(contrast = it))
                }
                LabeledSlider("饱和度", settings.saturation, -1f..1f, settings.saturation.percent()) {
                    controller.updateAdjustment(settings.copy(saturation = it))
                }
            } else if (settings.kind == AdjustmentKind.Curves) {
                CurvesControls(controller)
            } else if (settings.kind == AdjustmentKind.GradientMap) {
                GradientMapControls(controller)
            } else {
                LabeledSlider(
                    "半径",
                    settings.sigma,
                    StudioDefaults.minBlurSigma..StudioDefaults.maxBlurSigma,
                    ((settings.sigma * 10).roundToInt() / 10f).toString() + " px",
                ) {
                    controller.updateAdjustment(settings.copy(sigma = it))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preview.updating || preview.committing)
                    CircularProgressIndicator(
                        Modifier.size(StudioTheme.adjustmentStatusSize),
                        strokeWidth = StudioTheme.adjustmentStatusStroke,
                    )
                else
                    StudioIcon(
                        Glyph.Check,
                        StudioTheme.accent,
                        Modifier.size(StudioTheme.adjustmentStatusSize),
                    )
                Text(
                    tr(
                        if (preview.committing) "正在应用…"
                        else if (preview.updating) "正在预览…"
                        else if (preview.comparing) "原图" else "预览"
                    ),
                    Modifier.padding(start = StudioTheme.adjustmentStatusGap),
                    fontSize = StudioTheme.adjustmentHintSize,
                    color = StudioTheme.muted,
                )
            }
        }
        return
    }
    var canvasSize by remember { mutableStateOf(false) }
    var imageSize by remember { mutableStateOf(false) }
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val enabled =
        controller.ready &&
            !controller.busy &&
            active?.effectiveLocked != true &&
            active?.effectiveVisible == true &&
            active.kind == LayerKind.Raster &&
            controller.document.colorMode != DocumentColorMode.Indexed &&
            !controller.document.maskEditing
    ActionButton(
        "画布大小",
        { canvasSize = true },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Fit,
        primary = false,
        enabled = controller.ready && !controller.busy,
    )
    if (canvasSize) CanvasSizeDialog(controller) { canvasSize = false }
    ActionButton(
        "图像尺寸",
        { imageSize = true },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Fit,
        primary = false,
        enabled = controller.ready && !controller.busy,
    )
    if (imageSize) ImageSizeDialog(controller) { imageSize = false }
    HorizontalDivider(color = StudioTheme.border)
    if (active?.kind == LayerKind.Adjustment)
        ActionButton(
            "编辑调整图层",
            { controller.editAdjustmentLayer() },
            modifier = Modifier.fillMaxWidth(),
            glyph = Glyph.Curves,
            primary = false,
            enabled =
                controller.ready &&
                    !controller.busy &&
                    !active.effectiveLocked &&
                    !controller.document.maskEditing,
        )
    ActionButton(
        "明暗与色彩",
        { controller.prepareAdjustment(AdjustmentKind.Tone) },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Adjustments,
        primary = false,
        enabled = enabled,
    )
    ActionButton(
        "高斯模糊",
        { controller.prepareAdjustment(AdjustmentKind.Blur) },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Blur,
        primary = false,
        enabled = enabled,
    )
    ActionButton(
        "曲线",
        { controller.prepareAdjustment(AdjustmentKind.Curves) },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Curves,
        primary = false,
        enabled = enabled,
    )
    ActionButton(
        "渐变映射",
        { controller.prepareAdjustment(AdjustmentKind.GradientMap) },
        modifier = Modifier.fillMaxWidth(),
        glyph = Glyph.Gradient,
        primary = false,
        enabled = enabled,
    )
    HorizontalDivider(color = StudioTheme.border)
    Column {
        SectionLabel("填充设置")
        LabeledSlider(
            "颜色容差",
            controller.fillTolerance,
            0f..255f,
            controller.fillTolerance.roundToInt().toString(),
        ) {
            controller.fillTolerance = it.roundToInt().toFloat()
        }
        Row {
            ToolButton(Glyph.Fill, "仅连续区域", selected = controller.fillContiguous, plain = true) {
                controller.fillContiguous = !controller.fillContiguous
            }
            ToolButton(
                Glyph.Layers,
                "取样所有可见图层",
                selected = controller.fillMerged && !controller.document.maskEditing,
                enabled = !controller.document.maskEditing,
                plain = true,
            ) {
                controller.fillMerged = !controller.fillMerged
            }
        }
    }
    if (controller.document.selection != null)
        StudioTextButton({ controller.clearSelection() }) {
            ButtonLabel(tr("取消选区"), fontSize = 12.sp)
        }
}

private fun Float.percent() = (this * 100).roundToInt().toString()

@Composable
fun AdjustmentDock(controller: StudioController, modifier: Modifier = Modifier) {
    val preview = controller.adjustmentPreview ?: return
    val focus = remember { FocusRequester() }
    Row(
        modifier
            .focusRequester(focus)
            .focusable()
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(StudioTheme.selectionDockBorder, StudioTheme.border, CircleShape)
            .padding(StudioTheme.selectionDockPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.selectionDockGap),
    ) {
        ToolButton(Glyph.Eye, "原图对比", selected = preview.comparing, enabled = !preview.committing) {
            preview.comparing = !preview.comparing
        }
        ToolButton(Glyph.Rotate, "重置调整", enabled = !preview.committing) {
            focus.requestFocus()
            preview.comparing = false
            controller.updateAdjustment(preview.initialSettings)
        }
        ToolButton(Glyph.Close, "取消调整", enabled = !preview.committing) {
            controller.cancelAdjustment()
        }
        ToolButton(
            Glyph.Check,
            "确认调整",
            prominent = true,
            enabled =
                !preview.updating && preview.changed && !preview.committing && preview.inputValid,
        ) {
            controller.commitAdjustment()
        }
    }
}
