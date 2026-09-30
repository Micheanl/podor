package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

@Composable
fun GradientMapControls(controller: StudioController) {
    val preview = controller.adjustmentPreview ?: return
    val map = preview.settings.gradientMap
    var selected by remember(preview) { mutableIntStateOf(0) }
    val index = selected.coerceIn(map.stops.indices)
    val stop = map.stops[index]
    var hsv by remember(preview, index) { mutableStateOf(HsvColor.fromArgb(stop.argb)) }
    val enabled = !preview.committing
    fun update(stops: List<GradientMapStop>) {
        if (enabled)
            controller.updateAdjustment(
                preview.settings.copy(gradientMap = GradientMapSettings(stops))
            )
    }
    LaunchedEffect(stop.argb) {
        if (hsv.toArgb() != stop.argb) hsv = HsvColor.fromArgb(stop.argb)
    }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.curveGap)) {
        Box(
            Modifier.fillMaxWidth()
                .height(StudioTheme.gradientMapPreviewHeight)
                .clip(StudioTheme.cardShape)
                .background(
                    Brush.horizontalGradient(
                        *map.stops.map { it.position to Color(it.argb) }.toTypedArray()
                    )
                )
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(StudioTheme.curveGap)) {
            itemsIndexed(map.stops) { item, value ->
                ColorSwatch(value.argb, item == index) { if (enabled) selected = item }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(
                Glyph.Plus,
                "添加色标",
                enabled = enabled && map.stops.size < StudioDefaults.maxGradientMapStops,
            ) {
                val gap =
                    map.stops.zipWithNext().indices.maxBy {
                        map.stops[it + 1].position - map.stops[it].position
                    }
                val left = map.stops[gap]
                val right = map.stops[gap + 1]
                val value =
                    GradientMapStop(
                        (left.position + right.position) / 2,
                        left.color.zip(right.color).map { (a, b) -> (a + b + 1) / 2 },
                    )
                update(map.stops.toMutableList().apply { add(gap + 1, value) })
                selected = gap + 1
            }
            ToolButton(Glyph.Minus, "删除色标", enabled = enabled && map.stops.size > 2) {
                update(map.stops.filterIndexed { item, _ -> item != index })
                selected = index.coerceAtMost(map.stops.lastIndex - 1)
            }
            ToolButton(Glyph.Swap, "反转渐变", enabled = enabled) {
                update(map.stops.reversed().map { it.copy(position = 1f - it.position) })
                selected = map.stops.lastIndex - index
            }
            Spacer(Modifier.weight(1f))
            ToolButton(Glyph.Undo, "重置渐变", enabled = enabled) {
                update(GradientMapSettings().stops)
                selected = 0
            }
        }
        val low = if (index == 0) 0f else map.stops[index - 1].position + 0.00001f
        val high =
            if (index == map.stops.lastIndex) 1f else map.stops[index + 1].position - 0.00001f
        LabeledSlider(
            "色标位置",
            stop.position,
            low..high,
            "${(stop.position * 100).roundToInt()}%",
            tint = Color(stop.argb),
            glyph = Glyph.Gradient,
        ) {
            update(
                map.stops.mapIndexed { item, value ->
                    if (item == index) value.copy(position = it) else value
                }
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(
                hsv,
                { value ->
                    if (enabled) {
                        hsv = value
                        update(
                            map.stops.mapIndexed { item, point ->
                                if (item == index) point.withColor(value.toArgb()) else point
                            }
                        )
                    }
                },
                Modifier.widthIn(max = StudioTheme.colorWheelSize).fillMaxWidth(),
            )
        }
        Text(
            "#${stop.argb.and(0xFFFFFF).toString(16).padStart(6, '0').uppercase()}",
            color = StudioTheme.muted,
            fontSize = StudioTheme.curveLabelSize,
        )
        HsvSliders(hsv) { value ->
            if (enabled) {
                hsv = value
                update(
                    map.stops.mapIndexed { item, point ->
                        if (item == index) point.withColor(value.toArgb()) else point
                    }
                )
            }
        }
    }
}
