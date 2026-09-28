package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(
    glyph: Glyph,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val translatedLabel = tr(label)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val filled = selected || prominent
    val iconTurn = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        iconTurn.snapTo(0f)
        if (selected)
            iconTurn.animateTo(
                0f,
                keyframes {
                    durationMillis = StudioMotion.iconSelectMillis
                    0f at 0
                    StudioMotion.iconSelectAngle at
                        StudioMotion.pressMillis using
                        StudioMotion.easing
                    0f at StudioMotion.iconSelectMillis
                },
            )
    }
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
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .controlFeedback(interaction, CircleShape, enabled)
                .gradientButtonSurface(interaction, filled, enabled)
                .semantics {
                    contentDescription = translatedLabel
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            StudioIcon(glyph, tint.value, Modifier.graphicsLayer { rotationZ = iconTurn.value })
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
    onChange: (Float) -> Unit,
) {
    val translatedLabel = tr(label)
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val thumbScale =
        animateFloatAsState(
            if (dragging || focused) StudioMotion.sliderActiveScale else 1f,
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
                fontSize = 12.sp,
                color = if (dragging || focused) StudioTheme.accent else StudioTheme.text,
            )
        }
        Slider(
            value,
            onChange,
            valueRange = range,
            onValueChangeFinished = onChangeFinished,
            interactionSource = interaction,
            thumb = {
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
                SliderDefaults.Track(
                    state,
                    modifier = Modifier.height(4.dp),
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
