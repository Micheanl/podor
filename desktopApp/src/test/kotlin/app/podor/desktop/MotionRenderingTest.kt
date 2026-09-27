package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.ui.StudioLaunch
import app.podor.ui.StudioTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class MotionRenderingTest {
    @Test
    fun launchLightDissolvesAndStopsWithoutRecomposingTheWorkspaceEachFrame() =
        runBlocking<Unit> {
            var compositions = 0
            val directory = Path.of("build", "reports", "screenshots")
            Files.createDirectories(directory)
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(1360, 900) {
                        StudioLaunch(true) {
                            SideEffect { compositions++ }
                            Box(Modifier.fillMaxSize().background(StudioTheme.panel))
                        }
                    }
                }
            try {
                withContext(Dispatchers.Main) { scene.render(0).close() }
                delay(200)
                withContext(Dispatchers.Main) {
                    val durations = mutableListOf<Long>()
                    for (frame in 1..285) {
                        val started = System.nanoTime()
                        scene.render(frame * 16_666_667L).use { image ->
                            durations += System.nanoTime() - started
                            if (frame in listOf(65, 112, 162, 204)) {
                                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                    Files.write(
                                        directory.resolve("launch-light-$frame.png"),
                                        it.bytes,
                                    )
                                }
                            }
                        }
                    }
                    assertFalse(scene.hasInvalidations())
                    assertTrue(
                        compositions <= 4,
                        "Workspace was recomposed $compositions times during launch",
                    )
                    val sorted = durations.drop(10).sorted()
                    Files.writeString(
                        directory.resolve("launch-render-timing.txt"),
                        "Headless render, including image readback; not a GPU frame-rate measurement.\np50=${sorted[sorted.size / 2] / 1_000_000.0} ms\np95=${sorted[sorted.size * 95 / 100] / 1_000_000.0} ms\nworkspace compositions=$compositions\n",
                    )
                }
            } finally {
                withContext(Dispatchers.Main) { scene.close() }
            }
        }

    @Test
    fun skippingLaunchRemovesLightAndDissolveAnimations() =
        runBlocking<Unit> {
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(500, 600) {
                        StudioLaunch(true) { Box(Modifier.fillMaxSize()) }
                    }
                }
            try {
                withContext(Dispatchers.Main) {
                    scene.render(0).close()
                    scene.sendPointerEvent(PointerEventType.Press, Offset(250f, 300f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(250f, 300f))
                    repeat(30) { scene.render((it + 1) * 16_666_667L).close() }
                    assertFalse(scene.hasInvalidations())
                }
            } finally {
                withContext(Dispatchers.Main) { scene.close() }
            }
        }
}
