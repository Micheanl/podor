package app.podor.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.podor.domain.Language
import app.podor.ui.Glyph
import app.podor.ui.StudioIcon
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
        WindowButton(
            trValue("最小化", language),
            Glyph.Minimize,
            background.alpha == 0f,
            onClick = onMinimize,
        )
        WindowButton(
            trValue(if (maximized) "还原窗口" else "最大化", language),
            if (maximized) Glyph.Restore else Glyph.Maximize,
            background.alpha == 0f,
            onClick = onMaximize,
        )
        WindowButton(
            trValue("关闭", language),
            Glyph.Close,
            background.alpha == 0f,
            onClick = onClose,
        )
    }
}

@Composable
private fun WindowButton(label: String, glyph: Glyph, overAnimation: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val active = hovered || pressed || keyboardFocus
    val tint =
        animateColorAsState(
            when {
                active && glyph == Glyph.Close -> StudioTheme.accent
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
        StudioIcon(
            glyph,
            tint.value,
            Modifier.size(StudioTheme.windowIconSize),
            interactionSource = interaction,
        )
    }
}
