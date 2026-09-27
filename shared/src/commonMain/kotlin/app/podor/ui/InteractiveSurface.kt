package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Modifier.controlFeedback(
    interaction: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    pressedScale: Float = StudioMotion.pressScale,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale =
        animateFloatAsState(
            if (pressed && enabled) pressedScale else 1f,
            tween(
                if (pressed) StudioMotion.pressMillis else StudioMotion.releaseMillis,
                easing = StudioMotion.easing,
            ),
        )
    val light =
        animateFloatAsState(
            when {
                !enabled -> 0f
                pressed -> StudioTheme.pressLight
                hovered -> StudioTheme.hoverLight
                else -> 0f
            },
            tween(StudioMotion.feedbackMillis),
        )
    return hoverable(interaction, enabled)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clip(shape)
        .drawWithCache {
            val sheen =
                Brush.verticalGradient(
                    listOf(StudioTheme.surfaceLight, Color.Transparent, StudioTheme.surfaceShade)
                )
            val highlight =
                Brush.radialGradient(
                    listOf(Color.White, Color.Transparent),
                    center = Offset(size.width * 0.28f, 0f),
                    radius = size.maxDimension.coerceAtLeast(1f),
                )
            val rim =
                Brush.linearGradient(
                    listOf(StudioTheme.accent, Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.8f, size.height),
                )
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawWithContent {
                drawContent()
                if (enabled) drawRect(sheen)
                val brightness = light.value
                if (brightness > 0f) {
                    drawRect(highlight, alpha = brightness)
                    drawOutline(
                        outline,
                        rim,
                        alpha = brightness / StudioTheme.pressLight * 0.4f,
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
        }
        .border(
            1.dp,
            if (keyboardFocus && enabled) StudioTheme.accent else Color.Transparent,
            shape,
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: Glyph? = null,
    primary: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(
            onClick,
            modifier
                .heightIn(min = StudioTheme.controlSize)
                .controlFeedback(interaction, CircleShape, enabled),
            enabled = enabled,
            interactionSource = interaction,
            shape = CircleShape,
            border =
                BorderStroke(
                    1.dp,
                    if (enabled && primary) StudioTheme.selectionBorder.copy(alpha = 0.6f)
                    else StudioTheme.border,
                ),
            elevation = null,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (primary) StudioTheme.selection else StudioTheme.elevated,
                    contentColor = if (primary) StudioTheme.onSelection else StudioTheme.text,
                    disabledContainerColor = StudioTheme.elevated.copy(alpha = 0.5f),
                    disabledContentColor = StudioTheme.muted.copy(alpha = 0.4f),
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (glyph != null) {
                StudioIcon(glyph, LocalContentColor.current, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(tr(label), fontSize = 12.sp)
        }
    }
}

@Composable
fun ChoiceSurface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val background =
        animateColorAsState(
            if (selected) StudioTheme.selection.copy(alpha = 0.3f)
            else StudioTheme.elevated.copy(alpha = 0.6f),
            tween(StudioMotion.feedbackMillis),
        )
    Column(
        modifier
            .selectable(
                selected,
                interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .controlFeedback(interaction, shape)
            .drawBehind { drawRect(background.value) }
            .border(
                1.dp,
                if (selected) StudioTheme.selectionBorder
                else StudioTheme.border.copy(alpha = 0.45f),
                shape,
            )
            .padding(12.dp),
        content = content,
    )
}
