package app.podor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import app.podor.domain.AppIdentity
import app.podor.resources.Res
import app.podor.resources.brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

@Composable
fun PodorApp(controller: StudioController, updates: UpdateController? = null) {
    CompositionLocalProvider(LocalLanguage provides controller.preferences.language) {
        StudioLaunch(controller.ready) { StudioApp(controller, updates) }
    }
}

@Composable
fun StudioLaunch(ready: Boolean, content: @Composable () -> Unit) {
    val intro = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var introFinished by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val texture by
        produceState<ImageBitmap?>(null, finished) {
            value =
                if (finished) null else withContext(Dispatchers.Default) { createDissolveTexture() }
        }
    LaunchedEffect(finished) {
        if (!finished) {
            intro.animateTo(
                1f,
                keyframes {
                    durationMillis = StudioMotion.launchMillis + StudioMotion.launchHoldMillis
                    0f at 0 using StudioMotion.launchEasing
                    1f at StudioMotion.launchMillis
                    1f at durationMillis
                },
            )
            introFinished = true
        }
    }
    LaunchedEffect(ready, introFinished, finished) {
        if (ready && introFinished && !finished) {
            reveal.animateTo(1f, tween(StudioMotion.revealMillis, easing = LinearEasing))
            finished = true
        }
    }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (!finished) {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) finished = true
                true
            } else false
        }
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (finished) Modifier
                    else
                        Modifier.graphicsLayer {
                            translationY = 8.dp.toPx() * (1f - reveal.value)
                        }
                )
        ) {
            content()
        }
        if (!finished) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = trValue("跳过启动动画", LocalLanguage.current),
                ) {
                    finished = true
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val progress = ((reveal.value - 0.3f) / 0.7f).coerceIn(0f, 1f)
                            alpha = 1f - StudioMotion.launchEasing.transform(progress)
                        }
                        .background(StudioTheme.background)
                )
                Box(
                    Modifier.size(320.dp)
                        .graphicsLayer { alpha = 1f - reveal.value }
                        .drawWithCache {
                            val glow =
                                Brush.radialGradient(
                                    listOf(
                                        StudioTheme.selection.copy(alpha = 0.13f),
                                        Color.Transparent,
                                    ),
                                    radius = size.minDimension / 2,
                                )
                            onDrawBehind { drawRect(glow) }
                        }
                )
                val mask = texture
                Column(
                    Modifier.width(220.dp)
                        .padding(20.dp)
                        .then(
                            if (mask != null) Modifier.dissolve(mask) { reveal.value }
                            else Modifier.graphicsLayer { alpha = 1f - reveal.value }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painterResource(Res.drawable.brand),
                        AppIdentity.name,
                        Modifier.size(88.dp).graphicsLayer {
                            alpha = intro.value
                            scaleX = 0.92f + intro.value * 0.08f
                            scaleY = scaleX
                            translationY = 10.dp.toPx() * (1f - intro.value)
                        },
                    )
                    Spacer(Modifier.height(22.dp))
                    Text(
                        AppIdentity.name,
                        color = StudioTheme.text,
                        fontSize = 23.sp,
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier.graphicsLayer {
                                val progress = ((intro.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
                                alpha = progress
                                translationY = 6.dp.toPx() * (1f - progress)
                            },
                    )
                }
                if (introFinished && !ready) {
                    LinearProgressIndicator(
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 64.dp)
                            .width(80.dp)
                            .height(2.dp),
                        color = StudioTheme.accent,
                        trackColor = StudioTheme.border,
                    )
                }
            }
        }
    }
}
