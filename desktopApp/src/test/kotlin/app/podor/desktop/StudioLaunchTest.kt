package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.domain.Appearance
import app.podor.ui.PodorTheme
import app.podor.ui.StudioLaunch
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class StudioLaunchTest {
    @Test
    fun titleControlsRemainClickableAboveTheContinuousSwirl() = runBlocking {
        withContext(Dispatchers.Main) {
            val appearance = StudioTheme.appearance
            var minimized = 0
            var maximized = 0
            var closed = 0
            val scene =
                ImageComposeScene(800, 600) {
                    PodorTheme(appearance = Appearance.Dark) {
                        StudioLaunch(
                            false,
                            StudioTheme.windowTitleHeight,
                            {
                                WindowTitleBar(
                                    app.podor.domain.Language.English,
                                    false,
                                    { minimized++ },
                                    { maximized++ },
                                    { closed++ },
                                    Color.Transparent,
                                )
                            },
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.White))
                        }
                    }
                }
            try {
                scene.render(0).close()
                for ((index, x) in listOf(685f, 731f, 777f).withIndex()) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x, 20f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x, 20f))
                    scene.render((index + 1) * 16_666_667L).close()
                }
                assertEquals(1, minimized)
                assertEquals(1, maximized)
                assertEquals(1, closed)
                scene.render(100_000_000L).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    assertTrue(
                        (0..650).count {
                            val color = pixels[it, 20]
                            maxOf(color.red, color.green, color.blue) -
                                minOf(color.red, color.green, color.blue) > 0.05f
                        } > 20,
                        "The transparent title region must show the colored swirl background",
                    )
                    assertTrue(pixels[400, 300].green < 0.1f)
                }
            } finally {
                scene.close()
                val restore = ImageComposeScene(1, 1) { PodorTheme(appearance = appearance) {} }
                try {
                    restore.render().close()
                } finally {
                    restore.close()
                }
            }
        }
    }

    @Test
    fun captureSwirlPreview() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("PODOR_CAPTURE_STARTUP") == "1")
        app.podor.desktop.engine.NativeLoader.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files =
            object : app.podor.data.ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false
            }
        withContext(Dispatchers.Main) {
            val controller = app.podor.presentation.StudioController(files, scope)
            val ready = mutableStateOf(false)
            val scene =
                ImageComposeScene(960, 600) {
                    StudioLaunch(
                        ready.value,
                        StudioTheme.windowTitleHeight,
                        { launching ->
                            WindowTitleBar(
                                app.podor.domain.Language.English,
                                false,
                                {},
                                {},
                                {},
                                if (launching) Color.Transparent else StudioTheme.background,
                            )
                        },
                    ) {
                        app.podor.ui.WorkspaceHome(controller)
                    }
                }
            val directory = Path.of("build/reports/startup-swirl")
            Files.createDirectories(directory)
            try {
                scene.render(0).close()
                delay((StudioMotion.launchHoldMillis + 100).toLong())
                repeat(220) { frame ->
                    if (frame == 96) ready.value = true
                    scene.render((frame + 1) * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(directory.resolve("%03d.png".format(frame)), it.bytes)
                        }
                    }
                }
                assertFalse(scene.hasInvalidations())
            } finally {
                scene.close()
                controller.shutdown()
                scope.cancel()
            }
        }
    }

    @Test
    fun swirlKeepsMovingWhileLoadingAndStopsWhenDismissed() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val ready = mutableStateOf(false)
                val scene =
                    ImageComposeScene(640, 420) {
                        StudioLaunch(ready.value) {
                            Box(Modifier.fillMaxSize().background(Color.White))
                        }
                    }
                try {
                    scene.render(0).close()
                    delay((StudioMotion.launchHoldMillis + 100).toLong())
                    val before =
                        scene.render(500_000_000L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            assertTrue(pixels[8, 8].red < 0.15f)
                            assertTrue(
                                (170..250).sumOf { y ->
                                    (280..360).count { x -> pixels[x, y].red > 0.2f }
                                } > 100
                            )
                            val png = image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                            val output =
                                Path.of("build", "reports", "screenshots", "startup-icon.png")
                            Files.createDirectories(output.parent)
                            Files.write(output, png)
                            png
                        }
                    val after =
                        scene.render(3_000_000_000L).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                        }
                    assertFalse(before.contentEquals(after))
                    scene.render(9_000_000_000L).close()
                    scene.render(10_000_000_000L).close()
                    assertTrue(scene.hasInvalidations())
                    ready.value = true
                    for (frame in 1..105) scene
                        .render(10_000_000_000L + frame * 16_666_667L)
                        .close()
                    scene.render(11_800_000_000L).use { image ->
                        assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[8, 8].red, 0.01f)
                    }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                }
            }
        }

    @Test
    fun readyWorkspaceKeepsTheLogoDissolveWithSwirl() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val appearance = StudioTheme.appearance
                val scene =
                    ImageComposeScene(320, 240) {
                        PodorTheme(appearance = Appearance.Dark) {
                            StudioLaunch(true) {
                                Box(Modifier.fillMaxSize().background(Color.White))
                            }
                        }
                    }
                try {
                    scene.render(0).close()
                    delay((StudioMotion.launchHoldMillis + 100).toLong())
                    val midpointFrame = (StudioMotion.revealMillis * 500_000L / 16_666_667L).toInt()
                    for (frame in 1..105) {
                        scene.render(500_000_000L + frame * 16_666_667L).use { image ->
                            if (frame == midpointFrame) {
                                val red = image.toComposeImageBitmap().toPixelMap()[8, 8].red
                                assertTrue(red > StudioTheme.launchSwirlBack.red && red < 0.99f)
                            }
                        }
                    }
                    scene.render(2_300_000_000L).use { image ->
                        assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[8, 8].red, 0.01f)
                    }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                    val restore = ImageComposeScene(1, 1) { PodorTheme(appearance = appearance) {} }
                    try {
                        restore.render(0).close()
                    } finally {
                        restore.close()
                    }
                }
            }
        }

    @Test
    fun logoCanBeDismissedBeforeStartupCompletes() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val scene =
                    ImageComposeScene(320, 240) {
                        StudioLaunch(false) { Box(Modifier.fillMaxSize().background(Color.White)) }
                    }
                try {
                    scene.render(0).close()
                    scene.sendPointerEvent(PointerEventType.Press, Offset(160f, 120f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(160f, 120f))
                    scene.render(16_666_667L).close()
                    scene.render(33_333_334L).use { image ->
                        assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[8, 8].red, 0.01f)
                    }
                } finally {
                    scene.close()
                }
            }
        }
}
