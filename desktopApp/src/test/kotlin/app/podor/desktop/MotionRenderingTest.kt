package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.ui.LaunchLight
import app.podor.ui.StudioLaunch
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import app.podor.ui.launchLogoProgress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class MotionRenderingTest {
    @Test
    fun colorFlowTravelsDownLeftBeforeTheLogoAppears() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val intro = mutableFloatStateOf(0f)
                val scene =
                    ImageComposeScene(640, 420) {
                        Box(Modifier.fillMaxSize().background(StudioTheme.background)) {
                            LaunchLight { intro.floatValue }
                        }
                    }
                try {
                    fun center(phase: Float, time: Long): Offset {
                        intro.floatValue = phase * StudioMotion.launchFlowEnd
                        return scene.render(time).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            var xTotal = 0f
                            var yTotal = 0f
                            var weight = 0f
                            for (y in 0 until pixels.height step 4) {
                                for (x in 0 until pixels.width step 4) {
                                    val color = pixels[x, y]
                                    val intensity =
                                        (maxOf(color.red, color.green, color.blue) - 0.15f)
                                            .coerceAtLeast(0f)
                                    xTotal += x * intensity
                                    yTotal += y * intensity
                                    weight += intensity
                                }
                            }
                            assertTrue(weight > 10f, "Color flow should be visible")
                            Offset(xTotal / weight, yTotal / weight)
                        }
                    }
                    val entry = center(0.3f, 0L)
                    val exit = center(0.7f, 100_000_000L)
                    assertTrue(entry.x > 640 * 0.6f && entry.y < 420 * 0.4f, "entry=$entry")
                    assertTrue(exit.x < 640 * 0.4f && exit.y > 420 * 0.6f, "exit=$exit")
                    assertEquals(0f, launchLogoProgress(StudioMotion.launchFlowEnd))
                    assertEquals(1f, launchLogoProgress(1f))
                    assertTrue(
                        launchLogoProgress((1f + StudioMotion.launchFlowEnd) / 2f) in 0.1f..0.99f
                    )
                } finally {
                    scene.close()
                }
            }
        }

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
                    val end =
                        (StudioMotion.launchMillis +
                            StudioMotion.launchHoldMillis +
                            StudioMotion.revealMillis) * 60 / 1000 + 36
                    for (frame in 1..end) {
                        val started = System.nanoTime()
                        scene.render(frame * 16_666_667L).use { image ->
                            durations += System.nanoTime() - started
                            if (frame in listOf(30, 50, 65, 90, 112, 162, 200, 275)) {
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
