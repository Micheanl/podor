package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.*

@Composable
internal fun WorkspaceSonar(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var pulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(pulse) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(StudioMotion.sonarMillis, easing = LinearEasing))
    }
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures {
                    origin = it
                    pulse++
                }
            }
            .drawWithCache {
                val spacing = StudioTheme.sonarSpacing.toPx()
                val dot = StudioTheme.sonarDotRadius.toPx()
                val band = StudioTheme.sonarBandWidth.toPx()
                val source = origin ?: Offset(size.width * 0.7f, size.height * 0.58f)
                val bounds = size
                val points = buildList {
                    var y = spacing / 2f
                    while (y < bounds.height) {
                        var x = spacing / 2f
                        while (x < bounds.width) {
                            add(Offset(x, y))
                            x += spacing
                        }
                        y += spacing
                    }
                }
                val distances = FloatArray(points.size) { (points[it] - source).getDistance() }
                val reach = (distances.maxOrNull() ?: 0f) + band
                onDrawBehind {
                    val phase = progress.value
                    val radius = phase * reach
                    points.forEachIndexed { index, point ->
                        val wave =
                            if (phase >= 1f) 0f
                            else (1f - abs(distances[index] - radius) / band).coerceAtLeast(0f)
                        drawCircle(
                            StudioTheme.muted.copy(
                                alpha =
                                    StudioTheme.sonarBaseAlpha + wave * StudioTheme.sonarWaveAlpha
                            ),
                            dot * (1f + wave),
                            point,
                        )
                    }
                }
            }
    )
}
