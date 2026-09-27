package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

@Composable
fun LaunchLight(intro: () -> Float, reveal: () -> Float) {
    Canvas(
        Modifier.size(StudioTheme.launchFieldSize)
            .graphicsLayer {
                val opening = intro()
                alpha = opening * (1f - reveal())
                scaleX =
                    StudioMotion.launchFieldScale + (1f - StudioMotion.launchFieldScale) * opening
                scaleY = scaleX
            }
            .drawWithCache {
                val glow =
                    Brush.radialGradient(
                        listOf(StudioTheme.launchGlow, Color.Transparent),
                        radius = size.minDimension * 0.48f,
                    )
                val ribbon =
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Transparent,
                        0.54f to StudioTheme.selection.copy(alpha = 0.3f),
                        0.7f to StudioTheme.selectionBorder.copy(alpha = 0.8f),
                        0.78f to StudioTheme.accent.copy(alpha = 0.95f),
                        0.84f to Color.Transparent,
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                val orbit = Size(size.width * 0.88f, size.height * 0.5f)
                val start =
                    Offset((size.width - orbit.width) / 2f, (size.height - orbit.height) / 2f)
                onDrawBehind {
                    drawRect(glow)
                    val travel = intro() * StudioMotion.launchLightAngle + reveal() * 30f
                    rotate(-35f + travel) {
                        drawOval(ribbon, start, orbit, alpha = 0.1f, style = Stroke(9.dp.toPx()))
                        drawOval(ribbon, start, orbit, alpha = 0.28f, style = Stroke(3.dp.toPx()))
                        drawOval(ribbon, start, orbit, style = Stroke(0.8.dp.toPx()))
                    }
                    rotate(50f - travel * 0.45f) {
                        drawOval(
                            ribbon,
                            start,
                            orbit,
                            alpha = 0.45f,
                            style = Stroke(0.55.dp.toPx()),
                        )
                    }
                }
            }
    ) {}
}

fun Modifier.logoLight(progress: () -> Float): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}
    .drawWithCache {
        val band = size.width * 0.45f
        val light =
            Brush.linearGradient(
                listOf(Color.Transparent, StudioTheme.launchHighlight, Color.Transparent),
                start = Offset.Zero,
                end = Offset(band, 0f),
            )
        onDrawWithContent {
            drawContent()
            val amount = ((progress() - 0.25f) / 0.75f).coerceIn(0f, 1f)
            if (amount > 0f && amount < 1f) {
                translate(-band * 2f + amount * (size.width + band * 4f), 0f) {
                    drawRect(light, size = Size(band, size.height), blendMode = BlendMode.SrcAtop)
                }
            }
        }
    }
