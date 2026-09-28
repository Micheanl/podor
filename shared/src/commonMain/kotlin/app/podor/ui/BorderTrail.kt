package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.min

@Composable
fun Modifier.borderTrail(active: Boolean, radius: Dp = StudioTheme.borderTrailRadius): Modifier {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(active, StudioTheme.appearance) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(StudioMotion.borderTrailMillis, easing = LinearEasing))
        } else progress.snapTo(1f)
    }
    return drawWithCache {
        val inset = StudioTheme.borderTrailWidth.toPx() / 2f
        val path =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        CornerRadius(radius.toPx()),
                    )
                )
            }
        val measure = PathMeasure().apply { setPath(path, true) }
        val segment = Path()
        val stroke = Stroke(StudioTheme.borderTrailWidth.toPx(), cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            val phase = progress.value
            if (phase > 0f && phase < 1f) {
                val head = phase * measure.length
                val tail =
                    head -
                        min(
                            measure.length * StudioTheme.borderTrailFraction,
                            StudioTheme.borderTrailLength.toPx(),
                        )
                segment.reset()
                measure.getSegment(tail.coerceAtLeast(0f), head, segment)
                if (tail < 0f) measure.getSegment(measure.length + tail, measure.length, segment)
                val light =
                    Brush.radialGradient(
                        listOf(StudioTheme.borderTrailLight, Color.Transparent),
                        measure.getPosition(head),
                        StudioTheme.borderTrailLength.toPx(),
                    )
                val opacity = min(phase * 10f, (1f - phase) * 10f).coerceAtMost(1f)
                drawPath(segment, light, alpha = opacity, style = stroke)
            }
        }
    }
}
