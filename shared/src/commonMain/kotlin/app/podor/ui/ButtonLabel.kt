package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit

internal val LocalButtonInteraction = staticCompositionLocalOf<MutableInteractionSource?> { null }

@Composable
fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val interaction = LocalButtonInteraction.current
    if (interaction == null) {
        Text(text, modifier, color = color, fontSize = fontSize)
        return
    }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active =
        hovered ||
            pressed ||
            (focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard)
    val progress = remember(text) { Animatable(1f) }
    var order by remember(text) { mutableStateOf(text.indices.toList()) }
    LaunchedEffect(active, text) {
        if (active) {
            order = text.indices.shuffled()
            progress.snapTo(0f)
            progress.animateTo(1f, tween(StudioMotion.letterSwapMillis, easing = LinearEasing))
        } else progress.snapTo(1f)
    }
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        text.forEachIndexed { index, letter ->
            Box(Modifier.clipToBounds()) {
                fun phase(): Float {
                    val delay =
                        order[index].toFloat() / text.length.coerceAtLeast(1) *
                            StudioMotion.letterSwapStagger
                    return StudioMotion.easing.transform(
                        ((progress.value - delay) / (1f - StudioMotion.letterSwapStagger)).coerceIn(
                            0f,
                            1f,
                        )
                    )
                }
                Text(
                    letter.toString(),
                    color = color,
                    fontSize = fontSize,
                    modifier = Modifier.graphicsLayer { translationY = -phase() * size.height },
                )
                Text(
                    letter.toString(),
                    color = color,
                    fontSize = fontSize,
                    modifier =
                        Modifier.graphicsLayer { translationY = (1f - phase()) * size.height },
                )
            }
        }
    }
}
