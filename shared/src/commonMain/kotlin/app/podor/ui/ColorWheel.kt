package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.domain.HsvColor
import kotlin.math.*

@Composable
fun ColorWheel(value: HsvColor, onChange: (HsvColor) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(value)
    val update by rememberUpdatedState(onChange)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hueLabel = tr("色相")
    val planeLabel = tr("饱和度与明度色板")
    val hues = remember {
        Brush.sweepGradient((0..6).map { Color(HsvColor(it * 60f, 1f, 1f).toArgb()) })
    }
    val saturation =
        remember(value.hue) {
            Brush.horizontalGradient(
                listOf(Color.White, Color(HsvColor(value.hue, 1f, 1f).toArgb()))
            )
        }
    val brightness = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black)) }
    fun changeHue(hue: Float) {
        update(current.copy(hue = (hue + 360f) % 360f))
    }
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = hueLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(value.hue, 0f..359.99f)
                    setProgress {
                        changeHue(it.coerceIn(0f, 359.99f))
                        true
                    }
                }
                .onKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false
                    else
                        when (it.key) {
                            Key.DirectionRight,
                            Key.DirectionUp -> {
                                changeHue(current.hue + 1f)
                                true
                            }
                            Key.DirectionLeft,
                            Key.DirectionDown -> {
                                changeHue(current.hue - 1f)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(interactionSource = interaction)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val distance = (down.position - center).getDistance() / size.width
                        if (
                            distance in
                                (StudioTheme.colorRingRadius - StudioTheme.colorRingWidth)..0.5f
                        ) {
                            fun select(position: Offset) {
                                val point = position - center
                                changeHue(atan2(point.y, point.x) * 180f / PI.toFloat())
                            }
                            select(down.position)
                            down.consume()
                            do {
                                val change =
                                    awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: break
                                if (change.pressed) {
                                    select(change.position)
                                    change.consume()
                                }
                            } while (change.pressed)
                        }
                    }
                }
                .drawWithCache {
                    val radius = size.width * StudioTheme.colorRingRadius
                    val thickness = size.width * StudioTheme.colorRingWidth
                    val rim = Stroke(1.dp.toPx())
                    onDrawBehind {
                        drawCircle(
                            StudioTheme.background,
                            radius + thickness * 0.55f,
                            center + Offset(0f, 2.dp.toPx()),
                        )
                        drawCircle(StudioTheme.elevated, radius - thickness * 0.7f)
                        drawCircle(hues, radius, style = Stroke(thickness))
                        drawCircle(
                            if (focused) StudioTheme.accent else StudioTheme.feedbackInk.copy(alpha = 0.2f),
                            radius + thickness / 2f,
                            style = rim,
                        )
                        drawCircle(
                            Color.Black.copy(alpha = 0.25f),
                            radius - thickness / 2f,
                            style = rim,
                        )
                        val angle = current.hue * PI.toFloat() / 180f
                        val marker = center + Offset(cos(angle), sin(angle)) * radius
                        drawCircle(
                            Color.Black.copy(alpha = 0.4f),
                            thickness * 0.55f,
                            marker + Offset(0f, 1.dp.toPx()),
                        )
                        drawCircle(
                            Color(HsvColor(current.hue, 1f, 1f).toArgb()),
                            thickness * 0.44f,
                            marker,
                        )
                        drawCircle(
                            Color.White,
                            thickness * 0.44f,
                            marker,
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
        )
        Box(
            Modifier.fillMaxWidth(StudioTheme.colorPlaneFraction)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioTheme.background)
                .semantics { contentDescription = planeLabel }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun select(position: Offset) {
                            update(
                                current.copy(
                                    saturation = (position.x / size.width).coerceIn(0f, 1f),
                                    brightness = (1f - position.y / size.height).coerceIn(0f, 1f),
                                )
                            )
                        }
                        select(down.position)
                        down.consume()
                        do {
                            val change =
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: break
                            if (change.pressed) {
                                select(change.position)
                                change.consume()
                            }
                        } while (change.pressed)
                    }
                }
                .drawWithCache {
                    val markerRadius = 6.dp.toPx()
                    onDrawBehind {
                        drawRect(saturation)
                        drawRect(brightness)
                        val marker =
                            Offset(
                                (current.saturation * size.width).coerceIn(
                                    markerRadius,
                                    size.width - markerRadius,
                                ),
                                ((1f - current.brightness) * size.height).coerceIn(
                                    markerRadius,
                                    size.height - markerRadius,
                                ),
                            )
                        drawCircle(
                            Color.Black.copy(alpha = 0.45f),
                            markerRadius,
                            marker,
                            style = Stroke(4.dp.toPx()),
                        )
                        drawCircle(Color.White, markerRadius, marker, style = Stroke(2.dp.toPx()))
                    }
                }
        )
    }
}
