package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.hypot

@Composable
internal fun Modifier.gradientButtonSurface(
    interaction: MutableInteractionSource,
    accented: Boolean,
    enabled: Boolean,
    alwaysVisible: Boolean = false,
): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val visibility =
        animateFloatAsState(
            if (alwaysVisible || accented || (hovered && enabled)) 1f else 0f,
            tween(StudioMotion.feedbackMillis),
        )
    val accent = animateFloatAsState(if (accented) 1f else 0f, tween(StudioMotion.feedbackMillis))
    val travel = remember { Animatable(1f) }
    LaunchedEffect(hovered, pressed, enabled) {
        if (enabled && (hovered || pressed)) {
            travel.snapTo(0f)
            travel.animateTo(
                1f,
                tween(StudioMotion.buttonGradientMillis, easing = StudioMotion.easing),
            )
        } else travel.snapTo(1f)
    }
    return drawWithCache {
        val inset = StudioTheme.buttonRimWidth.toPx().coerceAtMost(size.minDimension / 4f)
        val radius = CornerRadius(size.height / 2f)
        val innerRadius = CornerRadius((radius.x - inset).coerceAtLeast(0f))
        val innerSize =
            Size(
                (size.width - inset * 2).coerceAtLeast(0f),
                (size.height - inset * 2).coerceAtLeast(0f),
            )
        val ring =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(Rect(Offset.Zero, size), radius))
                addRoundRect(RoundRect(Rect(Offset(inset, inset), innerSize), innerRadius))
            }
        val center = Offset(size.width / 2f, size.height / 2f)
        val gradient = Brush.sweepGradient(StudioTheme.buttonGradient, center)
        val diameter = hypot(size.width, size.height)
        val gradientOrigin = center - Offset(diameter / 2f, diameter / 2f)
        val gradientSize = Size(diameter, diameter)
        onDrawWithContent {
            val alpha = visibility.value * if (enabled) 1f else StudioTheme.buttonDisabledAlpha
            if (alpha > 0f) {
                drawRoundRect(StudioTheme.background, cornerRadius = radius, alpha = alpha)
                clipPath(ring) {
                    rotate(travel.value * 360f) {
                        drawRect(
                            gradient,
                            gradientOrigin,
                            gradientSize,
                            alpha =
                                alpha *
                                    (StudioTheme.buttonSecondaryRimAlpha +
                                        (1f - StudioTheme.buttonSecondaryRimAlpha) * accent.value),
                        )
                    }
                }
            }
            drawContent()
        }
    }
}
