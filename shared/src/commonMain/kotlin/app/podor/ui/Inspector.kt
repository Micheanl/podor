package app.podor.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlinx.serialization.json.put

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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (activePanel) {
                        StudioPanel.Brushes -> Unit
                        StudioPanel.Colors -> ColorControls(controller)
                        StudioPanel.Layers -> LayerControls(controller)
                        StudioPanel.Adjustments -> AdjustmentControls(controller)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioIcon(Glyph.Check, StudioTheme.accent, Modifier.size(13.dp))
            Text(
                tr(controller.status),
                Modifier.padding(start = 8.dp).weight(1f),
                fontSize = 10.sp,
                color = StudioTheme.muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LayerControls(controller: StudioController) {
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    var rename by remember { mutableStateOf(false) }
    var blending by remember(active?.id) { mutableStateOf(false) }
    var name by remember(active?.id, active?.name) { mutableStateOf(active?.name.orEmpty()) }
    var opacity by remember(active?.id, active?.opacity) { mutableStateOf(active?.opacity ?: 1f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("图层"), fontSize = 12.sp, color = StudioTheme.muted, modifier = Modifier.weight(1f))
        Text("${controller.document.layers.size} / 32", fontSize = 10.sp, color = StudioTheme.muted)
        Spacer(Modifier.width(6.dp))
        ToolButton(Glyph.Plus, "新建图层", enabled = controller.document.layers.size < 32) {
            controller.command("add_layer")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        controller.document.layers.asReversed().forEach { layer ->
            val selected = layer.id == controller.document.active
            val background by
                animateColorAsState(
                    if (selected) StudioTheme.accent.copy(alpha = 0.12f)
                    else StudioTheme.elevated.copy(alpha = 0.5f),
                    tween(StudioMotion.feedbackMillis),
                )
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .border(
                        1.dp,
                        if (selected) StudioTheme.accent.copy(alpha = 0.3f) else Color.Transparent,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { controller.command("select_layer") { put("id", layer.id) } }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioTheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtworkPreview(
                        controller.previews.images[layer.id],
                        controller.document.width,
                        controller.document.height,
                        Modifier.fillMaxSize().padding(3.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        tr(layer.name),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (selected) StudioTheme.accent else StudioTheme.text,
                    )
                    Text(
                        "${tr(layer.blend.label)} · ${(layer.opacity*100).roundToInt()}%",
                        fontSize = 10.sp,
                        color = StudioTheme.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ToolButton(
                    if (layer.visible) Glyph.Eye else Glyph.Hidden,
                    if (layer.visible) "隐藏 ${layer.name}" else "显示 ${layer.name}",
                ) {
                    controller.setLayer(layer.copy(visible = !layer.visible))
                }
            }
        }
    }
    if (active != null) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(StudioTheme.background)
                .padding(14.dp)
        ) {
            SectionLabel("混合模式")
            ActionButton(
                active.blend.label,
                { blending = true },
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
                enabled = controller.ready && !controller.busy,
                glyph = Glyph.Layers,
                primary = false,
            )
            LabeledSlider(
                "图层不透明度",
                opacity,
                0f..1f,
                "${(opacity*100).roundToInt()}%",
                onChangeFinished = { controller.setLayer(active.copy(opacity = opacity)) },
            ) {
                opacity = it
            }
            HorizontalDivider(color = StudioTheme.border.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({ rename = true }, contentPadding = PaddingValues(4.dp)) {
                    Text(tr("重命名"), fontSize = 11.sp)
                }
                ToolButton(
                    Glyph.Up,
                    "上移图层",
                    enabled = active.id != controller.document.layers.lastOrNull()?.id,
                ) {
                    controller.command("move_layer") {
                        put("id", active.id)
                        put("direction", 1)
                    }
                }
                ToolButton(
                    Glyph.Down,
                    "下移图层",
                    enabled = active.id != controller.document.layers.firstOrNull()?.id,
                ) {
                    controller.command("move_layer") {
                        put("id", active.id)
                        put("direction", -1)
                    }
                }
                ToolButton(Glyph.Trash, "删除图层", enabled = controller.document.layers.size > 1) {
                    controller.command("remove_layer") { put("id", active.id) }
                }
            }
        }
    }
    if (blending && active != null)
        LayerBlendDialog(
            active.blend,
            { mode ->
                if (mode != active.blend) controller.setLayerBlend(active.id, mode)
            },
            { blending = false },
        )
    if (rename && active != null)
        StudioAlertDialog(
            onDismissRequest = { rename = false },
            title = "重命名图层",
            glyph = Glyph.Layers,
            confirmLabel = "保存",
            enabled = name.isNotBlank(),
            onConfirm = { controller.setLayer(active.copy(name = name.trim())) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
        )
}
