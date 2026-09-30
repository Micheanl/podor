package app.podor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.podor.domain.Tool
import app.podor.presentation.StudioController

@Composable
internal fun ContextToolDock(
    controller: StudioController,
    onColors: (Boolean) -> Unit,
    onBrushes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller.adjustmentPreview == null && !controller.tool.hasContextToolDock()) return
    Box(modifier.widthIn(max = StudioTheme.transformDockWidth).testTag("context-tool-dock")) {
        ContextToolDockContent(controller, onColors, onBrushes, floating = true)
    }
}

internal fun Tool.hasContextToolDock(): Boolean =
    when (this) {
        Tool.Brush,
        Tool.Eraser,
        Tool.Select,
        Tool.LassoFill,
        Tool.MoveLayer,
        Tool.TransformLayer,
        Tool.Gradient,
        Tool.Vector,
        Tool.Assistant,
        Tool.LineGenerator -> true
        else -> false
    }

@Composable
internal fun ContextToolDockContent(
    controller: StudioController,
    onColors: (Boolean) -> Unit,
    onBrushes: () -> Unit,
    floating: Boolean = false,
) {
    if (controller.adjustmentPreview != null) AdjustmentDock(controller, floating = floating)
    else
        when (controller.tool) {
            Tool.Brush,
            Tool.Eraser -> BrushToolDock(controller, onBrushes = onBrushes)
            Tool.Select -> SelectionDock(controller, floating = floating)
            Tool.LassoFill -> LassoFillDock(controller, floating = floating)
            Tool.MoveLayer -> LayerMoveDock(controller, floating = floating)
            Tool.TransformLayer -> LayerTransformDock(controller, floating = floating)
            Tool.Gradient -> GradientDock(controller, floating = floating, onColor = onColors)
            Tool.Vector -> VectorDock(controller, floating = floating)
            Tool.Assistant -> AssistantDock(controller, floating = floating)
            Tool.LineGenerator -> LineGeneratorActions(controller, onBrushes)
            else -> Unit
        }
}
