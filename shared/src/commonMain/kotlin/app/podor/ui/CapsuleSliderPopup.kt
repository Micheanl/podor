package app.podor.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal fun capsuleSliderPosition(
    anchor: IntRect,
    popup: IntSize,
    window: IntSize,
    gap: Int,
): IntOffset {
    val above = anchor.top - popup.height - gap
    return IntOffset(
        (anchor.left + (anchor.width - popup.width) / 2).coerceIn(
            0,
            (window.width - popup.width).coerceAtLeast(0),
        ),
        (if (above >= 0) above else anchor.bottom + gap).coerceIn(
            0,
            (window.height - popup.height).coerceAtLeast(0),
        ),
    )
}

@Composable
internal fun CapsuleSliderPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    glyph: Glyph,
    tint: Color = LocalPaintColor.current,
    onChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    CapsulePopup(
        expanded,
        onDismiss,
        Modifier.width(minOf(StudioTheme.quickControlsWidth, windowWidth)),
    ) {
        CapsuleSlider(
            label,
            value,
            range,
            display,
            tint = tint,
            glyph = glyph,
            modifier = Modifier.testTag("capsule-slider-popup"),
            onChange = onChange,
        )
    }
}

@Composable
internal fun CapsulePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val transition = rememberTransition(visible)
    val reveal by
        transition.animateFloat(
            transitionSpec = {
                tween(
                    if (targetState) StudioMotion.panelMillis else StudioMotion.dismissMillis,
                    easing = StudioMotion.easing,
                )
            }
        ) {
            if (it) 1f else 0f
        }
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val gap = with(density) { StudioTheme.workspacePadding.roundToPx() }
    val position =
        remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ) = capsuleSliderPosition(anchorBounds, popupContentSize, windowSize, gap)
            }
        }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            modifier.widthIn(max = windowWidth).padding(StudioTheme.quickShadow).graphicsLayer {
                alpha = reveal
                scaleX = 0.98f + 0.02f * reveal
                scaleY = scaleX
                translationY = StudioTheme.workspaceGap.toPx() * (1f - reveal)
            }
        ) {
            content()
        }
    }
}
