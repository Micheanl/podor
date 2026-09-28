package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalHeaderButtons = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(
    glyph: Glyph,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val translatedLabel = tr(label)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val header = LocalHeaderButtons.current
    val shape = CircleShape
    val filled = selected || prominent
    val tint =
        animateColorAsState(
            when {
                !enabled -> StudioTheme.muted.copy(alpha = 0.3f)
                filled -> StudioTheme.onSelection
                hovered -> StudioTheme.text
                else -> StudioTheme.muted
            },
            tween(StudioMotion.feedbackMillis),
        )
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(containerColor = StudioTheme.elevated, contentColor = StudioTheme.text) {
                Text(translatedLabel)
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(StudioTheme.controlSize)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .then(
                    if (header) Modifier
                    else
                        Modifier.controlFeedback(interaction, shape, enabled)
                            .buttonSurface(filled, enabled, shape)
                )
                .semantics {
                    contentDescription = translatedLabel
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            StudioIcon(glyph, tint.value)
            if (header && selected)
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp)
                        .width(12.dp)
                        .height(2.dp)
                        .background(StudioTheme.text, CircleShape)
                )
        }
    }
}

@Composable
fun SectionLabel(text: String, suffix: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr(text),
            fontSize = 12.sp,
            color = StudioTheme.muted,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (suffix != null) Text(tr(suffix), fontSize = 11.sp, color = StudioTheme.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChangeFinished: (() -> Unit)? = null,
    trackColors: List<Color>? = null,
    onChange: (Float) -> Unit,
) {
    val translatedLabel = tr(label)
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    var scrubbing by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val scrubDistance = with(LocalDensity.current) { StudioTheme.sliderFineDistance.toPx() }
    val trackHeight by
        animateDpAsState(
            if (trackColors != null) StudioTheme.colorSliderHeight
            else if (dragging || scrubbing) 6.dp else 4.dp,
            tween(StudioMotion.feedbackMillis),
        )
    val thumbScale =
        animateFloatAsState(
            if (dragging || focused || scrubbing) StudioMotion.sliderActiveScale else 1f,
            tween(StudioMotion.feedbackMillis, easing = StudioMotion.easing),
        )
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(translatedLabel, fontSize = 12.sp, color = StudioTheme.muted)
            Text(
                display,
                modifier =
                    Modifier.pointerInput(range, scrubDistance) {
                            var start = 0f
                            var pixels = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    start = currentValue
                                    pixels = 0f
                                    scrubbing = true
                                },
                                onDragEnd = {
                                    scrubbing = false
                                    finished?.invoke()
                                },
                                onDragCancel = {
                                    if (scrubbing) {
                                        scrubbing = false
                                        change(start)
                                        finished?.invoke()
                                    }
                                },
                            ) { event, amount ->
                                event.consume()
                                pixels += amount.x
                                change(
                                    (start +
                                            pixels / scrubDistance *
                                                (range.endInclusive - range.start))
                                        .coerceIn(range)
                                )
                            }
                        }
                        .padding(vertical = 6.dp),
                fontSize = 12.sp,
                color =
                    if (dragging || focused || scrubbing) StudioTheme.accent else StudioTheme.text,
            )
        }
        Slider(
            value,
            onChange,
            valueRange = range,
            onValueChangeFinished = onChangeFinished,
            interactionSource = interaction,
            thumb = {
                if (trackColors != null)
                    ColorSliderThumb(
                        colorOnTrack(
                            trackColors,
                            (value - range.start) / (range.endInclusive - range.start),
                        ),
                        Modifier.size(StudioTheme.quickThumbSize).graphicsLayer {
                            scaleX = thumbScale.value
                            scaleY = thumbScale.value
                        },
                    )
                else
                    Box(
                        Modifier.size(StudioTheme.sliderThumbSize)
                            .graphicsLayer {
                                scaleX = thumbScale.value
                                scaleY = thumbScale.value
                            }
                            .shadow(3.dp, CircleShape)
                            .background(StudioTheme.text, CircleShape)
                    )
            },
            track = { state ->
                if (trackColors != null)
                    Box(
                        Modifier.fillMaxWidth()
                            .height(trackHeight)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(trackColors))
                    )
                else
                    SliderDefaults.Track(
                        state,
                        modifier = Modifier.height(trackHeight),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                        colors =
                            SliderDefaults.colors(
                                activeTrackColor = StudioTheme.accent,
                                inactiveTrackColor = StudioTheme.border,
                            ),
                    )
            },
            modifier = Modifier.height(40.dp).semantics { contentDescription = translatedLabel },
        )
    }
}

@Composable
fun ColorSwatch(value: Long, selected: Boolean, onClick: () -> Unit) {
    val label = tr("颜色 #${(value and 0xFFFFFF).toString(16).padStart(6,'0')}")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(36.dp)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .controlFeedback(interaction, CircleShape)
            .clip(CircleShape)
            .background(StudioTheme.background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) StudioTheme.accent else Color.White.copy(alpha = 0.08f),
                CircleShape,
            )
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color(value))
            .drawWithCache {
                val light =
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f),
                        ),
                        center = Offset(size.width * 0.3f, size.height * 0.2f),
                        radius = size.width,
                    )
                onDrawWithContent {
                    drawContent()
                    drawRect(light)
                }
            }
    ) {
        if (selected)
            Box(
                Modifier.size(5.dp)
                    .align(Alignment.Center)
                    .background(if (value == 0xFFFFFFFF) Color.Black else Color.White, CircleShape)
            )
    }
}
