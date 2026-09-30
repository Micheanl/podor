package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.podor.domain.AdjustmentKind
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

internal fun StudioController.hasToolOptions(): Boolean =
    adjustmentPreview != null ||
        tool in
            listOf(
                Tool.Brush,
                Tool.Eraser,
                Tool.Smudge,
                Tool.Fill,
                Tool.Gradient,
                Tool.LineGenerator,
            )

internal fun StudioController.defaultToolPanel(): StudioPanel =
    if (hasToolOptions()) StudioPanel.ToolOptions
    else
        when (tool) {
            Tool.LassoFill,
            Tool.Picker -> StudioPanel.Colors
            else -> StudioPanel.Layers
        }

@Composable
internal fun ContextToolPanel(
    controller: StudioController,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val preview = controller.adjustmentPreview
        if (preview?.settings?.kind == AdjustmentKind.LayerBlend) {
            LayerBlendControls(controller)
        } else if (preview != null) {
            AdjustmentControls(controller, Modifier.fillMaxSize())
        } else if (controller.tool in listOf(Tool.Brush, Tool.Eraser, Tool.Smudge)) {
            BrushControls(controller)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioTheme.workspaceGap),
            ) {
                when (controller.tool) {
                    Tool.Fill -> FillSettings(controller)
                    Tool.Gradient -> {
                        val value = controller.gradient
                        val enabled =
                            controller.gradientPreview != null &&
                                controller.gradientPreview?.committing != true &&
                                !controller.busy
                        LabeledSlider(
                            "不透明度",
                            value.opacity,
                            0f..1f,
                            "${(value.opacity * 100).roundToInt()}%",
                        ) {
                            if (enabled) controller.gradient = value.copy(opacity = it)
                        }
                    }
                    Tool.LineGenerator -> LineGeneratorDock(controller, Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        }
    }
}
