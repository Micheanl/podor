package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.controlFeedback(
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
            onDrawWithContent {
                drawContent()
                val brightness = light.value
                if (brightness > 0f) {
                    drawRect(Color.White, alpha = brightness)
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
    StudioButton(onClick, modifier, enabled, primary) {
        if (glyph != null) {
            StudioIcon(glyph, LocalContentColor.current, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(tr(label))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(
            onClick,
            modifier
                .heightIn(min = StudioTheme.controlSize)
                .controlFeedback(interaction, StudioTheme.buttonShape, enabled)
                .buttonSurface(
                    primary,
                    enabled,
                    StudioTheme.buttonShape,
                    alwaysVisible = true,
                    primary = primary,
                ),
            enabled = enabled,
            interactionSource = interaction,
            shape = StudioTheme.buttonShape,
            elevation = null,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = if (primary) StudioTheme.onAccent else StudioTheme.text,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = StudioTheme.muted.copy(alpha = 0.4f),
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            ProvideTextStyle(
                LocalTextStyle.current.copy(
                    fontSize = StudioTheme.buttonLabelSize,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
            ) {
                content()
            }
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
    val shape = StudioTheme.cardShape
    val background =
        animateColorAsState(
            if (selected) StudioTheme.selection else StudioTheme.elevated,
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
                StudioTheme.hairline,
                if (selected) StudioTheme.selectionBorder
                else StudioTheme.border.copy(alpha = 0.45f),
                shape,
            )
            .padding(12.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        TextButton(
            onClick,
            modifier.controlFeedback(interaction, StudioTheme.buttonShape, enabled),
            enabled = enabled,
            shape = StudioTheme.buttonShape,
            interactionSource = interaction,
            contentPadding = contentPadding,
            content = content,
        )
    }
}
