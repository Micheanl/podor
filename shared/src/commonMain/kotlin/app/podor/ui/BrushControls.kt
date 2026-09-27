package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun BrushControls(controller: StudioController) {
    var editing by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(StudioTheme.background)
                        .border(
                            1.dp,
                            StudioTheme.border.copy(alpha = 0.6f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr(controller.brush.preset.label),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        ToolButton(Glyph.Adjustments, "编辑笔刷") { editing = true }
                    }
                    BrushStrokePreview(
                        controller.brush.preset.copy(
                            size = controller.brush.size,
                            opacity = 1f,
                        ),
                        Modifier.fillMaxWidth().height(40.dp).graphicsLayer {
                            alpha = controller.brush.opacity
                        },
                        Color(controller.brush.color),
                    )
                }
                Column {
                    LabeledSlider(
                        "大小",
                        controller.brush.size,
                        1f..256f,
                        "${controller.brush.size.roundToInt()} px",
                    ) {
                        controller.brush = controller.brush.copy(size = it)
                    }
                    LabeledSlider(
                        "不透明度",
                        controller.brush.opacity,
                        0.01f..1f,
                        "${(controller.brush.opacity*100).roundToInt()}%",
                    ) {
                        controller.brush = controller.brush.copy(opacity = it)
                    }
                    LabeledSlider(
                        "稳笔",
                        controller.brush.preset.stabilization,
                        0f..1f,
                        if (controller.brush.preset.stabilization == 0f) tr("关闭")
                        else "${(controller.brush.preset.stabilization * 100).roundToInt()}%",
                    ) {
                        controller.brush =
                            controller.brush.copy(
                                preset = controller.brush.preset.copy(stabilization = it)
                            )
                    }
                }
                HorizontalDivider(color = StudioTheme.border.copy(alpha = 0.5f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                SectionLabel("笔刷库", "${controller.brushes.size}")
            }
        }
        items(controller.brushes, key = { it.id }) { preset ->
            val selected = controller.brush.preset.id == preset.id
            ChoiceSurface(selected, { controller.selectPreset(preset) }) {
                BrushStrokePreview(
                    preset,
                    Modifier.fillMaxWidth().height(42.dp),
                    if (selected) StudioTheme.accent else StudioTheme.muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(preset.label),
                        fontSize = 11.sp,
                        color = if (selected) StudioTheme.accent else StudioTheme.muted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Box(
                            Modifier.size(17.dp).background(StudioTheme.selection, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            StudioIcon(Glyph.Check, StudioTheme.onSelection, Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(Glyph.Hand, StudioTheme.muted, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("手指绘画"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(controller.fingerDrawing, { controller.fingerDrawing = it })
            }
        }
    }
    if (editing) BrushEditor(controller) { editing = false }
}

@Composable
fun BrushStrokePreview(
    preset: BrushPreset,
    modifier: Modifier = Modifier,
    tint: Color = StudioTheme.accent,
) {
    Box(
        modifier.drawWithCache {
            val inset = 8.dp.toPx()
            val width = (size.width - inset * 2).coerceAtLeast(1f)
            val thickness = (preset.size * 0.16f).coerceIn(1.5f, 12f).dp.toPx()
            fun point(t: Float) =
                Offset(inset + width * t, size.height * (0.5f - sin(t * 6.283f) * 0.2f))
            val path =
                Path().apply {
                    val start = point(0f)
                    moveTo(start.x, start.y)
                    for (i in 1..48) {
                        val p = point(i / 48f)
                        lineTo(p.x, p.y)
                    }
                }
            val stroke =
                Stroke(
                    thickness,
                    cap = if (preset.tip == BrushTip.Flat) StrokeCap.Butt else StrokeCap.Round,
                    pathEffect =
                        if (preset.spacing > 0.5f)
                            PathEffect.dashPathEffect(floatArrayOf(0.1f, thickness * 2.5f))
                        else null,
                )
            val random = Random(17)
            val grain =
                List((preset.grain * 100).toInt()) {
                    val p = point(random.nextFloat())
                    Offset(p.x, p.y + (random.nextFloat() - 0.5f) * thickness)
                }
            onDrawBehind {
                if (preset.hardness < 0.5f) {
                    drawPath(
                        path,
                        tint.copy(alpha = 0.07f),
                        style = Stroke(thickness * 1.7f, cap = StrokeCap.Round),
                    )
                    drawPath(
                        path,
                        tint.copy(alpha = 0.13f),
                        style = Stroke(thickness * 1.3f, cap = StrokeCap.Round),
                    )
                }
                drawPath(path, tint.copy(alpha = 0.4f + preset.opacity * 0.5f), style = stroke)
                grain.forEach {
                    drawCircle(StudioTheme.panel.copy(alpha = 0.65f), 0.65.dp.toPx(), it)
                }
            }
        }
    )
}

@Composable
private fun BrushEditor(controller: StudioController, onDismiss: () -> Unit) {
    var name by remember {
        mutableStateOf(trValue(controller.brush.preset.label, controller.preferences.language))
    }
    val preset = controller.brush.preset
    fun update(value: BrushPreset) {
        controller.brush = controller.brush.copy(preset = value)
    }
    StudioAlertDialog(
        onDismissRequest = onDismiss,
        title = "编辑笔刷",
        glyph = Glyph.Brush,
        confirmLabel = "保存为新笔刷",
        cancelLabel = "完成",
        enabled = name.isNotBlank() && controller.preferences.brushes.size < 64,
        onConfirm = { controller.saveBrush(name) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it.take(60) },
                    singleLine = true,
                    label = { Text(tr("名称")) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrushTip.entries.forEach { tip ->
                        FilterChip(
                            preset.tip == tip,
                            { update(preset.copy(tip = tip)) },
                            label = { Text(tr(if (tip == BrushTip.Round) "圆形" else "扁平")) },
                        )
                    }
                }
                LabeledSlider(
                    "硬度",
                    preset.hardness,
                    0f..1f,
                    "${(preset.hardness*100).roundToInt()}%",
                ) {
                    update(preset.copy(hardness = it))
                }
                LabeledSlider(
                    "笔尖比例",
                    preset.aspect,
                    0.1f..1f,
                    "${(preset.aspect*100).roundToInt()}%",
                ) {
                    update(preset.copy(aspect = it))
                }
                LabeledSlider("角度", preset.angle, -180f..180f, "${preset.angle.roundToInt()}°") {
                    update(preset.copy(angle = it))
                }
                LabeledSlider("颗粒", preset.grain, 0f..1f, "${(preset.grain*100).roundToInt()}%") {
                    update(preset.copy(grain = it))
                }
                LabeledSlider(
                    "间距",
                    preset.spacing,
                    0.02f..1f,
                    "${(preset.spacing*100).roundToInt()}%",
                ) {
                    update(preset.copy(spacing = it))
                }
            }
        },
    )
}
