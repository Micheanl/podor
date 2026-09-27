package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform

@Composable
fun LaunchLight(intro: () -> Float) {
    Canvas(
        Modifier.fillMaxSize().drawWithCache {
            val width = size.minDimension * StudioMotion.launchBandWidth
            val length = kotlin.math.hypot(size.width, size.height) * 0.9f
            val angle = kotlin.math.atan2(size.width, size.height) * 180f / kotlin.math.PI.toFloat()
            val rainbow =
                Brush.linearGradient(
                    StudioTheme.launchSpectrum,
                    start = Offset(-width / 2f, 0f),
                    end = Offset(width / 2f, 0f),
                )
            val origin = Offset(-width / 2f, -length / 2f)
            val bounds = Size(width, length)
            val corners = CornerRadius(width / 2f)
            onDrawBehind {
                val phase = (intro() / StudioMotion.launchFlowEnd).coerceIn(0f, 1f)
                if (phase > 0f && phase < 1f) {
                    val travel = -0.8f + phase * 2.6f
                    withTransform({
                        translate(size.width * (1f - travel), size.height * travel)
                        rotate(angle, pivot = Offset.Zero)
                    }) {
                        drawRoundRect(rainbow, origin, bounds, corners)
                    }
                }
            }
        }
    ) {}
}

fun launchLogoProgress(intro: Float): Float =
    StudioMotion.easing.transform(
        ((intro - StudioMotion.launchFlowEnd) / (1f - StudioMotion.launchFlowEnd)).coerceIn(0f, 1f)
    )

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
