package app.podor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.podor.domain.AdjustmentKind
import app.podor.presentation.StudioController

enum class StudioPanel(val label: String) {
    ToolOptions("工具选项"),
    Colors("颜色"),
    Layers("图层"),
    Adjustments("调整"),
    Animation("动画"),
}

@Composable
fun Inspector(
    controller: StudioController,
    panel: StudioPanel,
    onPanelChange: (StudioPanel) -> Unit,
    modifier: Modifier = Modifier,
    onAnimationExport: () -> Unit = {},
) {
    val activePanel = if (panel == StudioPanel.ToolOptions) controller.defaultToolPanel() else panel
    var animationVisited by remember(controller, activePanel) { mutableStateOf(false) }
    val animationExists = controller.document.animation != null
    val animationReady =
        controller.ready &&
            !controller.busy &&
            !controller.drawingInput &&
            !controller.animationTransition &&
            controller.document.maxAnimationFrames > 0
    LaunchedEffect(controller, activePanel, animationExists, animationReady) {
        if (activePanel == StudioPanel.Animation && !animationVisited) {
            if (animationExists) animationVisited = true
            else if (animationReady) {
                controller.toggleAnimationTimeline()
                animationVisited = controller.animationTransition
            }
        }
    }
    Column(
        modifier.padding(StudioTheme.inspectorPadding),
        verticalArrangement = Arrangement.spacedBy(StudioTheme.brushLibraryGap),
    ) {
        Row(
            Modifier.fillMaxWidth().height(StudioTheme.controlSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(
                    if (activePanel == StudioPanel.ToolOptions) controller.tool.label
                    else activePanel.label
                ),
                fontSize = StudioTheme.modalTitleSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.headerGap),
        ) {
            StudioPanel.entries
                .filter { it != StudioPanel.ToolOptions || controller.hasToolOptions() }
                .forEach { item ->
                    ToolButton(
                        when (item) {
                            StudioPanel.ToolOptions -> Glyph.Brush
                            StudioPanel.Colors -> Glyph.Palette
                            StudioPanel.Layers -> Glyph.Layers
                            StudioPanel.Adjustments -> Glyph.Adjustments
                            StudioPanel.Animation -> Glyph.Animation
                        },
                        item.label,
                        activePanel == item,
                        plain = true,
                    ) {
                        onPanelChange(item)
                    }
                }
        }

        HorizontalDivider(color = StudioTheme.border, thickness = StudioTheme.hairline)
        PageTransition(
            activePanel.ordinal,
            Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val activePanel =
                if (StudioPanel.entries[page] == StudioPanel.ToolOptions)
                    controller.defaultToolPanel()
                else StudioPanel.entries[page]
            if (activePanel == StudioPanel.ToolOptions) ContextToolPanel(controller)
            else if (activePanel == StudioPanel.Layers) LayerControls(controller)
            else if (activePanel == StudioPanel.Animation) {
                if (controller.document.animation != null)
                    AnimationTimeline(controller, onAnimationExport)
            } else if (
                activePanel == StudioPanel.Adjustments &&
                    controller.adjustmentPreview?.settings?.kind == AdjustmentKind.LayerBlend
            )
                LayerBlendControls(controller)
            else if (activePanel == StudioPanel.Adjustments)
                AdjustmentControls(controller, Modifier.fillMaxSize())
            else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (activePanel) {
                        StudioPanel.ToolOptions -> Unit
                        StudioPanel.Colors -> ColorControls(controller)
                        StudioPanel.Layers -> Unit
                        StudioPanel.Adjustments -> Unit
                        StudioPanel.Animation -> Unit
                    }
                }
            }
        }
    }
}
