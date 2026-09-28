package app.podor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
internal fun Modifier.buttonSurface(
    accented: Boolean,
    enabled: Boolean,
    shape: Shape,
    alwaysVisible: Boolean = false,
    primary: Boolean = false,
): Modifier {
    val fill =
        animateColorAsState(
            when {
                !enabled && (alwaysVisible || accented) -> StudioTheme.elevated.copy(alpha = 0.35f)
                primary -> StudioTheme.accent
                accented -> StudioTheme.selection
                alwaysVisible -> StudioTheme.elevated
                else -> Color.Transparent
            },
            tween(StudioMotion.feedbackMillis),
        )
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(StudioTheme.hairline.toPx())
        onDrawBehind {
            drawOutline(outline, fill.value)
            if (alwaysVisible || accented) {
                drawOutline(outline, StudioTheme.controlBorder, style = stroke)
            }
        }
    }
}
