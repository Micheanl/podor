package app.podor.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun AdjustmentControls(controller: StudioController, modifier: Modifier = Modifier) {
    val preview = controller.adjustmentPreview
    var expanded by remember { mutableStateOf<AdjustmentSection?>(null) }
    LaunchedEffect(preview?.settings?.kind) {
        if (preview != null)
            expanded = AdjustmentSection.entries.firstOrNull { it.kind == preview.settings.kind }
        else if (expanded?.kind != null) expanded = null
    }
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    val ready =
        controller.ready &&
            !controller.busy &&
            !controller.drawingInput &&
            !controller.animationPlaying &&
            !controller.animationTransition
    val enabled =
        ready &&
            active?.effectiveLocked != true &&
            active?.effectiveVisible == true &&
            active.kind == LayerKind.Raster &&
            controller.document.colorMode != DocumentColorMode.Indexed &&
            !controller.document.maskEditing
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minimumHeight = maxHeight
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minimumHeight),
            verticalArrangement = Arrangement.spacedBy(StudioTheme.adjustmentGap),
        ) {
            AdjustmentSection.entries.forEach { section ->
                val selected = expanded == section
                val sectionEnabled =
                    if (section.kind == null) ready && preview?.committing != true
                    else
                        enabled && preview?.committing != true ||
                            (preview?.settings?.kind == section.kind && preview?.committing != true)
                AdjustmentSectionHeader(section, selected, sectionEnabled) {
                    if (selected) {
                        expanded = null
                        if (section.kind != null) controller.cancelAdjustment()
                    } else {
                        controller.cancelAdjustment()
                        expanded = section
                        section.kind?.let(controller::prepareAdjustment)
                    }
                }
                if (selected) {
                    when (section) {
                        AdjustmentSection.Canvas -> {
                            val editor = rememberCanvasSizeEditor(controller)
                            CanvasSizeEditorContent(controller, editor)
                            ResizeActions(editor.canApply(controller), { expanded = null }) {
                                editor.apply(controller)
                                expanded = null
                            }
                        }
                        AdjustmentSection.Image -> {
                            val editor = rememberImageSizeEditor(controller)
                            ImageSizeEditorContent(controller, editor)
                            ResizeActions(editor.canApply(controller), { expanded = null }) {
                                editor.apply(controller)
                                expanded = null
                            }
                        }
                        else ->
                            if (preview?.settings?.kind == section.kind)
                                AdjustmentPreviewControls(controller)
                    }
                }
            }
            if (active?.kind == LayerKind.Adjustment)
                ActionButton(
                    "编辑调整图层",
                    { controller.editAdjustmentLayer() },
                    modifier = Modifier.fillMaxWidth(),
                    glyph = Glyph.Curves,
                    primary = false,
                    enabled =
                        ready &&
                            preview == null &&
                            !active.effectiveLocked &&
                            !controller.document.maskEditing,
                )
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = StudioTheme.border, thickness = StudioTheme.hairline)
            FillSettings(controller)
            if (controller.document.selection != null)
                StudioTextButton(
                    { controller.clearSelection() },
                    enabled = ready && preview == null,
                ) {
                    ButtonLabel(tr("取消选区"), fontSize = StudioTheme.adjustmentHintSize)
                }
        }
    }
}

private enum class AdjustmentSection(
    val label: String,
    val glyph: Glyph,
    val kind: AdjustmentKind? = null,
) {
    Canvas("画布大小", Glyph.Fit),
    Image("图像尺寸", Glyph.Fit),
    Tone("明暗与色彩", Glyph.Adjustments, AdjustmentKind.Tone),
    Blur("高斯模糊", Glyph.Blur, AdjustmentKind.Blur),
    Curves("曲线", Glyph.Curves, AdjustmentKind.Curves),
    GradientMap("渐变映射", Glyph.Gradient, AdjustmentKind.GradientMap),
}

@Composable
private fun AdjustmentSectionHeader(
    section: AdjustmentSection,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = tr(section.label)
    ChoiceSurface(
        expanded,
        onClick,
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled,
        shape = CircleShape,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(section.glyph)
            Spacer(Modifier.width(StudioTheme.workspaceGap))
            ButtonLabel(label, Modifier.weight(1f))
            StudioIcon(
                Glyph.Chevron,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                selected = expanded,
            )
        }
    }
}

@Composable
private fun ResizeActions(enabled: Boolean, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioTextButton(onCancel) { ButtonLabel(tr("取消")) }
        ActionButton("应用", onApply, enabled = enabled)
    }
}

@Composable
private fun AdjustmentPreviewControls(controller: StudioController) {
    val preview = controller.adjustmentPreview
    if (preview != null) {
        val settings = preview.settings
        Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.adjustmentGap)) {
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
    }
}

@Composable
internal fun FillSettings(controller: StudioController) {
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
}

private fun Float.percent() = (this * 100).roundToInt().toString()

@Composable
fun AdjustmentDock(
    controller: StudioController,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    val preview = controller.adjustmentPreview ?: return
    val focus = remember { FocusRequester() }
    ContextActionRow(
        modifier.focusRequester(focus).focusable(),
        floating,
        trailing = {
            ToolButton(Glyph.Close, "取消调整", enabled = !preview.committing) {
                controller.cancelAdjustment()
            }
            ToolButton(
                Glyph.Check,
                "确认调整",
                prominent = true,
                enabled =
                    !preview.updating &&
                        preview.changed &&
                        !preview.committing &&
                        preview.inputValid,
            ) {
                controller.commitAdjustment()
            }
        },
    ) {
        ToolButton(Glyph.Eye, "原图对比", selected = preview.comparing, enabled = !preview.committing) {
            preview.comparing = !preview.comparing
        }
        ToolButton(Glyph.Rotate, "重置调整", enabled = !preview.committing) {
            focus.requestFocus()
            preview.comparing = false
            controller.updateAdjustment(preview.initialSettings)
        }
    }
}
