package app.podor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope

@Composable
internal fun LaunchSwirl(modifier: Modifier = Modifier) {
    val renderer = remember(StudioTheme.appearance) { createLaunchSwirlRenderer() }
    var seconds by remember { mutableFloatStateOf(0f) }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    LaunchedEffect(renderer) {
        if (renderer != null) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { seconds = (it - start) / 1_000_000_000f }
            }
        }
    }
    Canvas(modifier) {
        if (renderer == null) drawRect(StudioTheme.launchSwirlBack)
        else with(renderer) { draw(seconds * StudioMotion.launchSwirlSpeed) }
    }
}

internal interface LaunchSwirlRenderer {
    fun DrawScope.draw(time: Float)

    fun close()
}

internal expect fun createLaunchSwirlRenderer(): LaunchSwirlRenderer?
