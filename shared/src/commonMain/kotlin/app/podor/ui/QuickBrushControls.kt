package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt

internal fun quickControlsPosition(
    anchor: IntOffset,
    popup: IntSize,
    window: IntSize,
    gap: Int,
): IntOffset =
    IntOffset(
        (anchor.x - popup.width - gap).coerceIn(0, (window.width - popup.width).coerceAtLeast(0)),
        (anchor.y - popup.height / 2).coerceIn(0, (window.height - popup.height).coerceAtLeast(0)),
    )

@Composable
fun QuickBrushPopup(
    controller: StudioController,
    anchor: Offset,
    besideTool: Boolean = false,
    colorsOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 18.dp.roundToPx() }
    val position =
        remember(anchor, gap, besideTool) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset =
                    if (besideTool)
                        IntOffset(
                            (anchorBounds.right + gap).coerceAtMost(
                                (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                            ),
                            (anchorBounds.top + anchorBounds.height / 2 -
                                    popupContentSize.height / 2)
                                .coerceIn(
                                    0,
                                    (windowSize.height - popupContentSize.height).coerceAtLeast(0),
                                ),
                        )
                    else
                        quickControlsPosition(
                            anchorBounds.topLeft +
                                IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
                            popupContentSize,
                            windowSize,
                            gap,
                        )
            }
        }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(Unit) { reveal.animateTo(1f, tween(StudioMotion.feedbackMillis)) }
        Box(
            Modifier.graphicsLayer {
                alpha = reveal.value
                translationX = (1f - reveal.value) * gap
            }
        ) {
            QuickBrushControls(controller, colorsOnly)
        }
    }
}

@Composable
fun QuickBrushControls(controller: StudioController, colorsOnly: Boolean = false) {
    val smudge = controller.tool == Tool.Smudge
    Column(
        Modifier.width(StudioTheme.quickControlsWidth).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!colorsOnly) {
            CapsuleSlider(
                "大小",
                controller.brush.size,
                StudioDefaults.minBrushSize..StudioDefaults.maxBrushSize,
                "${controller.brush.size.roundToInt()} px",
                tint = Color(controller.brush.color),
            ) {
                controller.brush = controller.brush.copy(size = it)
            }
            val strength = if (smudge) controller.smudgeStrength else controller.brush.opacity
            CapsuleSlider(
                if (smudge) "涂抹强度" else "不透明度",
                strength,
                0.01f..1f,
                "${(strength * 100).roundToInt()}%",
                tint = Color(controller.brush.color),
            ) {
                if (smudge) controller.smudgeStrength = it
                else controller.brush = controller.brush.copy(opacity = it)
            }
        }
        if (colorsOnly || (controller.tool != Tool.Eraser && !smudge)) {
            var hsv by remember { mutableStateOf(HsvColor.fromArgb(controller.brush.color)) }
            LaunchedEffect(controller.brush.color) {
                if (hsv.toArgb() != controller.brush.color)
                    hsv = HsvColor.fromArgb(controller.brush.color)
            }
            CapsuleSlider(
                "色相",
                hsv.hue,
                0f..360f,
                "${hsv.hue.roundToInt()}°",
                (0..6).map { HsvColor(it * 60f, 1f, 1f).let { c -> Color(c.toArgb()) } },
            ) {
                hsv = hsv.copy(hue = it)
                controller.brush = controller.brush.copy(color = hsv.toArgb())
            }
            CapsuleSlider(
                "饱和度",
                hsv.saturation,
                0f..1f,
                "${(hsv.saturation * 100).roundToInt()}%",
                listOf(
                    Color(hsv.copy(saturation = 0f).toArgb()),
                    Color(hsv.copy(saturation = 1f).toArgb()),
                ),
            ) {
                hsv = hsv.copy(saturation = it)
                controller.brush = controller.brush.copy(color = hsv.toArgb())
            }
            CapsuleSlider(
                "明度",
                hsv.brightness,
                0f..1f,
                "${(hsv.brightness * 100).roundToInt()}%",
                listOf(Color.Black, Color(hsv.copy(brightness = 1f).toArgb())),
            ) {
                hsv = hsv.copy(brightness = it)
                controller.brush = controller.brush.copy(color = hsv.toArgb())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapsuleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    colors: List<Color>? = null,
    tint: Color = LocalPaintColor.current,
    onChange: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val description = tr(label)
    val dragging by interaction.collectIsDraggedAsState()
    val scale by
        animateFloatAsState(if (dragging) 1.08f else 1f, tween(StudioMotion.feedbackMillis))
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val distance = with(LocalDensity.current) { StudioTheme.sliderFineDistance.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .height(StudioTheme.quickControlHeight)
            .shadow(StudioTheme.quickShadow, CircleShape)
            .background(StudioTheme.panel, CircleShape)
            .borderTrail(true, StudioTheme.quickTrailRadius)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HintIcon(
            when (label) {
                "大小" -> Glyph.BrushSize
                "不透明度" -> Glyph.Opacity
                "涂抹强度" -> Glyph.Smudge
                "色相" -> Glyph.Palette
                "饱和度" -> Glyph.Gradient
                else -> Glyph.Sun
            },
            label,
            Modifier.width(48.dp),
        )
        Slider(
            value,
            onChange,
            valueRange = range,
            interactionSource = interaction,
            modifier =
                Modifier.weight(1f).height(StudioTheme.quickControlHeight).semantics {
                    contentDescription = description
                },
            thumb = {
                val thumb =
                    Modifier.size(StudioTheme.quickThumbSize).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                if (colors != null)
                    ColorSliderThumb(
                        colorOnTrack(
                            colors,
                            (value - range.start) / (range.endInclusive - range.start),
                        ),
                        thumb,
                    )
                else ColorSliderThumb(tint, thumb)
            },
            track = { state ->
                Box(
                    Modifier.fillMaxWidth()
                        .height(StudioTheme.quickTrackHeight)
                        .clip(CircleShape)
                        .background(StudioTheme.elevated)
                ) {
                    if (colors != null)
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(colors)))
                    else
                        Box(
                            Modifier.fillMaxWidth(
                                    ((state.value - range.start) /
                                            (range.endInclusive - range.start))
                                        .coerceIn(0f, 1f)
                                )
                                .fillMaxHeight()
                                .background(tint)
                        )
                }
            },
        )
        Text(
            display,
            Modifier.width(48.dp).padding(start = 8.dp).pointerInput(range, distance) {
                var start = 0f
                var delta = 0f
                var active = false
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        start = current
                        delta = 0f
                        active = true
                    },
                    onDragEnd = { active = false },
                    onDragCancel = {
                        if (active) {
                            change(start)
                            active = false
                        }
                    },
                ) { event, amount ->
                    event.consume()
                    delta += amount.x
                    change(
                        (start + delta / distance * (range.endInclusive - range.start)).coerceIn(
                            range
                        )
                    )
                }
            },
            color = StudioTheme.text,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}
