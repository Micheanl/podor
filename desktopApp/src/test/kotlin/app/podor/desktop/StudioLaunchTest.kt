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
    fun capturePathsPreview() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("PODOR_CAPTURE_STARTUP") == "1")
        app.podor.desktop.engine.NativeLoader.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files = object : app.podor.data.ProjectFiles {
            override suspend fun open(): ByteArray? = null
            override suspend fun save(bytes: ByteArray, png: Boolean) = false
        }
        withContext(Dispatchers.Main) {
            val controller = app.podor.presentation.StudioController(files, scope)
            val ready = mutableStateOf(false)
            val scene = ImageComposeScene(960, 600) {
                StudioLaunch(ready.value) { app.podor.ui.WorkspaceHome(controller) }
            }
            val directory = Path.of("build/reports/startup-paths")
            Files.createDirectories(directory)
            try {
                scene.render(0).close()
                delay((StudioMotion.launchHoldMillis + 100).toLong())
                repeat(110) { frame ->
                    if (frame == 48) ready.value = true
                    scene.render((frame + 1) * 33_333_333L).use { image ->
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
    fun flowingPathsStopAfterTheirBoundedEntranceAndDisappearWhenReady() =
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
                            assertEquals(StudioTheme.background.red, pixels[8, 8].red, 0.01f)
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
                    assertFalse(scene.hasInvalidations())
                    ready.value = true
                    for (frame in 1..105) scene.render(10_000_000_000L + frame * 16_666_667L).close()
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
    fun readyWorkspaceKeepsTheLogoDissolveWithFlowingPaths() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val scene =
                    ImageComposeScene(320, 240) {
                        StudioLaunch(true) { Box(Modifier.fillMaxSize().background(Color.White)) }
                    }
                try {
                    scene.render(0).close()
                    delay((StudioMotion.launchHoldMillis + 100).toLong())
                    for (frame in 1..105) {
                        scene.render(500_000_000L + frame * 16_666_667L).use { image ->
                            if (frame == 50) {
                                val red = image.toComposeImageBitmap().toPixelMap()[8, 8].red
                                assertTrue(red > StudioTheme.background.red && red < 0.99f)
                            }
                        }
                    }
                    scene.render(2_300_000_000L).use { image ->
                        assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[8, 8].red, 0.01f)
                    }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
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
