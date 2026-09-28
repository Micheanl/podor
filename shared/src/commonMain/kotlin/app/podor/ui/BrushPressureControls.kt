package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.podor.domain.BrushPreset
import kotlin.math.roundToInt

@Composable
fun BrushPressureControls(
    preset: BrushPreset,
    modifier: Modifier = Modifier,
    smudge: Boolean = false,
    onChange: (BrushPreset) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(StudioTheme.brushGraphShape)
                .background(StudioTheme.background)
                .padding(StudioTheme.brushGraphPadding)
        ) {
            Text(tr("响应"), fontSize = StudioTheme.brushCaptionSize, color = StudioTheme.muted)
            Box(
                Modifier.fillMaxWidth().height(StudioTheme.brushGraphHeight).drawWithCache {
                    val inset = StudioTheme.brushGraphLine.toPx()
                    val width = (size.width - inset * 2).coerceAtLeast(1f)
                    val height = (size.height - inset * 2).coerceAtLeast(1f)
                    fun point(x: Float, y: Float) =
                        Offset(inset + width * x, inset + height * (1f - y))
                    val start = point(0f, 0f)
                    val end = point(1f, 1f)
                    val control = point(0.5f, (1f + preset.pressureCurve) * 0.5f)
                    val curve =
                        Path().apply {
                            moveTo(start.x, start.y)
                            quadraticTo(control.x, control.y, end.x, end.y)
                        }
                    val fill =
                        Path().apply {
                            addPath(curve)
                            lineTo(end.x, start.y)
                            close()
                        }
                    val gradient =
                        Brush.verticalGradient(
                            listOf(StudioTheme.selection.copy(alpha = 0.45f), Color.Transparent)
                        )
                    val stroke = Stroke(inset, cap = StrokeCap.Round)
                    val midpoint = point(0.5f, 0.5f + preset.pressureCurve * 0.25f)
                    onDrawBehind {
                        for (i in 0..4) {
                            val t = i / 4f
                            drawLine(StudioTheme.border, point(t, 0f), point(t, 1f))
                            drawLine(StudioTheme.border, point(0f, t), point(1f, t))
                        }
                        drawLine(StudioTheme.muted.copy(alpha = 0.25f), start, end)
                        drawPath(fill, gradient)
                        drawPath(curve, StudioTheme.accent, style = stroke)
                        drawCircle(StudioTheme.accent, StudioTheme.brushGraphDot.toPx(), midpoint)
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("轻压"), fontSize = StudioTheme.brushCaptionSize, color = StudioTheme.muted)
                Text(tr("重压"), fontSize = StudioTheme.brushCaptionSize, color = StudioTheme.muted)
            }
        }
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap),
        ) {
            listOf("轻柔" to 1f, "线性" to 0f, "有力" to -1f).forEach { (label, value) ->
                ChoiceSurface(
                    preset.pressureCurve == value,
                    { onChange(preset.copy(pressureCurve = value)) },
                    Modifier.weight(1f),
                ) {
                    Text(tr(label), fontSize = StudioTheme.brushLabelSize)
                }
            }
        }
        LabeledSlider(
            "压感曲线",
            preset.pressureCurve,
            -1f..1f,
            "${(preset.pressureCurve * 100).roundToInt()}%",
        ) {
            onChange(preset.copy(pressureCurve = it))
        }
        LabeledSlider(
            "粗细压感",
            preset.sizePressure,
            0f..1f,
            "${(preset.sizePressure * 100).roundToInt()}%",
        ) {
            onChange(preset.copy(sizePressure = it))
        }
        LabeledSlider(
            if (smudge) "强度压感" else "透明度压感",
            preset.opacityPressure,
            0f..1f,
            "${(preset.opacityPressure * 100).roundToInt()}%",
        ) {
            onChange(preset.copy(opacityPressure = it))
        }
    }
}
