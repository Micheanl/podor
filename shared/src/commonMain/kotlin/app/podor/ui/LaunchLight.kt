package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

@Composable
fun LaunchLight(intro: () -> Float) {
    Canvas(
        Modifier.fillMaxSize().drawWithCache {
            val band = size.minDimension * StudioMotion.launchRibbonWidth
            val count = StudioTheme.launchSpectrum.size
            val strands =
                StudioTheme.launchSpectrum.mapIndexed { index, color ->
                    val shift = (index - (count - 1) / 2f) * band / count
                    val path =
                        Path().apply {
                            moveTo(size.width * 1.04f + shift, -size.height * 0.2f + shift)
                            cubicTo(
                                size.width * 0.66f + shift,
                                size.height * 0.13f + shift,
                                size.width * 0.88f + shift,
                                size.height * 0.33f + shift,
                                size.width * 0.55f + shift,
                                size.height * 0.47f + shift,
                            )
                            cubicTo(
                                size.width * 0.22f + shift,
                                size.height * 0.61f + shift,
                                size.width * 0.36f + shift,
                                size.height * 0.83f + shift,
                                -size.width * 0.04f + shift,
                                size.height * 1.06f + shift,
                            )
                        }
                    val shade = lerp(color, StudioTheme.background, 0.2f)
                    val brush =
                        Brush.linearGradient(
                            listOf(shade, color, lerp(color, Color.White, 0.22f), color, shade),
                            start = Offset(size.width, 0f),
                            end = Offset(size.width * 0.7f, size.height * 0.3f),
                            tileMode = TileMode.Repeated,
                        ) as ShaderBrush
                    path to brush
                }
            val core = Stroke(band / count * 1.25f, cap = StrokeCap.Butt)
            val halo = Stroke(band / count * 2.8f, cap = StrokeCap.Butt)
            val edge = Stroke(0.7.dp.toPx(), cap = StrokeCap.Butt)
            val transforms = List(count) { Matrix() }
            onDrawBehind {
                val progress = intro().coerceIn(0f, 1f)
                val light =
                    (progress / 0.08f).coerceIn(0f, 1f) * (1f - launchLogoProgress(progress))
                if (light > 0f) {
                    strands.forEachIndexed { index, (path, brush) ->
                        val travel = progress * StudioMotion.launchFlowTravel + index * 0.018f
                        val transform = transforms[index]
                        transform.reset()
                        transform.translate(-size.width * travel, size.height * travel)
                        brush.transform = transform
                        drawPath(
                            path,
                            StudioTheme.launchSpectrum[index],
                            alpha = light * 0.06f,
                            style = halo,
                        )
                        drawPath(path, brush, alpha = light * 0.82f, style = core)
                        drawPath(
                            path,
                            StudioTheme.launchHighlight,
                            alpha = light * 0.18f,
                            style = edge,
                        )
                    }
                }
            }
        }
    ) {}
}

fun launchLogoProgress(intro: Float): Float {
    val start = StudioMotion.launchFlowEnd - StudioMotion.launchHandoffOverlap
    return StudioMotion.easing.transform(((intro - start) / (1f - start)).coerceIn(0f, 1f))
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
