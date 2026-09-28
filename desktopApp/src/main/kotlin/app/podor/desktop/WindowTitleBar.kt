package app.podor.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.podor.domain.Language
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import app.podor.ui.trValue

@Composable
internal fun WindowTitleBar(
    language: Language,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    background: Color = StudioTheme.panel,
) {
    Row(Modifier.fillMaxWidth().height(StudioTheme.windowTitleHeight).background(background)) {
        Spacer(Modifier.weight(1f))
        WindowButton(trValue("最小化", language), 0, background.alpha == 0f, onClick = onMinimize)
        WindowButton(
            trValue(if (maximized) "还原窗口" else "最大化", language),
            if (maximized) 2 else 1,
            background.alpha == 0f,
            onClick = onMaximize,
        )
        WindowButton(trValue("关闭", language), 3, background.alpha == 0f, onClick = onClose)
    }
}

@Composable
private fun WindowButton(label: String, glyph: Int, overAnimation: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val active = hovered || pressed || keyboardFocus
    val scale =
        animateFloatAsState(
            if (pressed) StudioMotion.pressScale else 1f,
            tween(StudioMotion.pressMillis, easing = StudioMotion.easing),
        )
    val tint =
        animateColorAsState(
            when {
                active && glyph == 3 -> StudioTheme.accent
                active || overAnimation -> StudioTheme.text
                else -> StudioTheme.muted
            },
            tween(StudioMotion.feedbackMillis),
        )
    Box(
        Modifier.width(StudioTheme.windowButtonWidth)
            .fillMaxHeight()
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(StudioTheme.windowButtonInset)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(StudioTheme.windowIconSize).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            val color = tint.value
            val stroke = 1.dp.toPx()
            val inset = stroke / 2f
            when (glyph) {
                0 ->
                    drawLine(
                        color,
                        Offset(inset, size.height / 2),
                        Offset(size.width - inset, size.height / 2),
                        stroke,
                    )
                1 ->
                    drawRect(
                        color,
                        Offset(inset, inset),
                        Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                2 -> {
                    val shift = 3.dp.toPx()
                    drawRect(
                        color,
                        Offset(inset, shift),
                        Size(size.width - shift - inset, size.height - shift - inset),
                        style = Stroke(stroke),
                    )
                    drawLine(color, Offset(shift, inset), Offset(size.width - inset, inset), stroke)
                    drawLine(
                        color,
                        Offset(size.width - inset, inset),
                        Offset(size.width - inset, size.height - shift),
                        stroke,
                    )
                }
                3 -> {
                    drawLine(
                        color,
                        Offset(inset, inset),
                        Offset(size.width - inset, size.height - inset),
                        stroke,
                    )
                    drawLine(
                        color,
                        Offset(size.width - inset, inset),
                        Offset(inset, size.height - inset),
                        stroke,
                    )
                }
            }
        }
    }
}
