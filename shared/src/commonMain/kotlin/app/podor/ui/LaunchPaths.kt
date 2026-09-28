package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

@Composable
internal fun LaunchPaths(modifier: Modifier = Modifier) {
    val motion = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        motion.animateTo(1f, tween(StudioMotion.launchPathsMillis, easing = LinearEasing))
    }
    Box(
        modifier
            .clipToBounds()
            .drawWithCache {
                val width = size.width
                val height = size.height
                val lines =
                    List(StudioTheme.launchPathCount) { index ->
                        val spread = index.toFloat() / (StudioTheme.launchPathCount - 1)
                        Path().apply {
                            moveTo(-width * 0.15f, height * (0.80f + spread * 0.30f))
                            cubicTo(
                                width * 0.24f,
                                height * (0.94f - spread * 0.08f),
                                width * 0.32f,
                                height * (0.15f + spread * 0.36f),
                                width * 0.66f,
                                height * (0.23f + spread * 0.28f),
                            )
                            cubicTo(
                                width * 0.89f,
                                height * (0.27f + spread * 0.25f),
                                width * 1.06f,
                                height * (0.05f + spread * 0.08f),
                                width * 1.18f,
                                -height * 0.15f,
                            )
                        }
                    }
                val mirrored =
                    List(StudioTheme.launchPathCount) { index ->
                        val spread = index.toFloat() / (StudioTheme.launchPathCount - 1)
                        Path().apply {
                            moveTo(width * 1.15f, height * (0.91f + spread * 0.25f))
                            cubicTo(
                                width * 0.80f,
                                height * (0.78f - spread * 0.10f),
                                width * 0.69f,
                                height * (0.10f + spread * 0.28f),
                                width * 0.36f,
                                height * (0.18f + spread * 0.30f),
                            )
                            cubicTo(
                                width * 0.14f,
                                height * (0.25f + spread * 0.20f),
                                -width * 0.07f,
                                height * (0.02f + spread * 0.10f),
                                -width * 0.18f,
                                -height * 0.15f,
                            )
                        }
                    }
                val ink =
                    Brush.linearGradient(StudioTheme.launchPathColors, end = Offset(width, height))
                val stroke = Stroke(StudioTheme.launchPathWidth.toPx())
                val veil =
                    Brush.radialGradient(
                        listOf(StudioTheme.background, StudioTheme.background.copy(alpha = 0f)),
                        center = Offset(width / 2f, height / 2f),
                        radius = size.minDimension * 0.40f,
                    )
                val drift = StudioTheme.launchPathDrift.toPx()
                onDrawBehind {
                    val progress = motion.value
                    val opacity =
                        (progress / StudioMotion.launchPathsEntranceFraction).coerceIn(0f, 1f)
                    translate(top = -drift * progress) {
                        lines.forEach {
                            drawPath(
                                it,
                                ink,
                                alpha = StudioTheme.launchPathAlpha * opacity,
                                style = stroke,
                            )
                        }
                    }
                    translate(top = drift * progress) {
                        mirrored.forEach {
                            drawPath(
                                it,
                                ink,
                                alpha = StudioTheme.launchPathAlpha * opacity,
                                style = stroke,
                            )
                        }
                    }
                    drawRect(veil)
                }
            }
            .fillMaxSize()
    )
}
