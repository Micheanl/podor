package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.AdjustmentKind
import app.podor.presentation.StudioController

enum class StudioPanel(val label: String) {
    Brushes("画笔"),
    Colors("颜色"),
    Layers("图层"),
    Adjustments("调整"),
}

@Composable
fun Inspector(
    controller: StudioController,
    panel: StudioPanel,
    onPanelChange: (StudioPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(StudioTheme.controlSize), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(panel.label),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StudioIcon(
                when (panel) {
                    StudioPanel.Brushes -> Glyph.Brush
                    StudioPanel.Colors -> Glyph.Palette
                    StudioPanel.Layers -> Glyph.Layers
                    StudioPanel.Adjustments -> Glyph.Adjustments
                },
                StudioTheme.muted.copy(alpha = 0.5f),
                Modifier.size(16.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(CircleShape)
                .background(StudioTheme.background)
                .padding(5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StudioPanel.entries.forEach { item ->
                ToolButton(
                    when (item) {
                        StudioPanel.Brushes -> Glyph.Brush
                        StudioPanel.Colors -> Glyph.Palette
                        StudioPanel.Layers -> Glyph.Layers
                        StudioPanel.Adjustments -> Glyph.Adjustments
                    },
                    item.label,
                    panel == item,
                ) {
                    onPanelChange(item)
                }
            }
        }
        PageTransition(
            panel.ordinal,
            Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val activePanel = StudioPanel.entries[page]
            if (activePanel == StudioPanel.Brushes) BrushControls(controller)
            else if (activePanel == StudioPanel.Layers) LayerControls(controller)
            else if (
                activePanel == StudioPanel.Adjustments &&
                    controller.adjustmentPreview?.settings?.kind == AdjustmentKind.LayerBlend
            )
                LayerBlendControls(controller)
            else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (activePanel) {
                        StudioPanel.Brushes -> Unit
                        StudioPanel.Colors -> ColorControls(controller)
                        StudioPanel.Layers -> Unit
                        StudioPanel.Adjustments -> AdjustmentControls(controller)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioIcon(if (controller.hasUnsavedChanges) Glyph.Brush else Glyph.Check, StudioTheme.accent, Modifier.size(13.dp))
            Text(
                tr(if (controller.hasUnsavedChanges) "有未保存的改动" else controller.status),
                Modifier.padding(start = 8.dp).weight(1f),
                fontSize = 10.sp,
                color = StudioTheme.muted,
                maxLines = 1,
            )
        }
    }
}
