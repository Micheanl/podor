package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.Appearance
import app.podor.domain.Preferences
import app.podor.presentation.StudioController
import app.podor.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class StartupAppearanceTest {
    @Test
    fun firstVisibleStartupFrameUsesSavedLightAppearance() = runBlocking {
        NativeLoader.load()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false

                override suspend fun readPreferences(): ByteArray {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    return Json.encodeToString(Preferences(appearance = Appearance.Light))
                        .encodeToByteArray()
                }
            }
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(400, 300) {
                    PodorApp(controller)
                }
            }
        var frame = 0L
        try {
            withTimeout(10_000) { readStarted.await() }
            withContext(Dispatchers.Main) {
                repeat(5) {
                    scene.render(frame++ * 16_666_667L).use { image ->
                        assertEquals(0f, image.toComposeImageBitmap().toPixelMap()[10, 80].alpha)
                    }
                }
            }
            releaseRead.complete(Unit)
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            withContext(Dispatchers.Main) {
                scene.render(frame++ * 16_666_667L).use { image ->
                    val pixel = image.toComposeImageBitmap().toPixelMap()[10, 80]
                    assertEquals(1f, pixel.alpha)
                    assertEquals(Appearance.Light, StudioTheme.appearance)
                    assertTrue(
                        pixel.red > 0.4f,
                        "A dark startup frame was rendered before the light theme",
                    )
                }
                assertTrue(controller.document.maxLayers > 0)
                assertEquals(1, controller.document.rasterLayerCount)
                assertTrue(controller.frame.tiles.isEmpty())
                assertFalse(controller.startupFailed)
            }
        } finally {
            releaseRead.complete(Unit)
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val reset =
                    ImageComposeScene(10, 10) { PodorTheme(appearance = Appearance.Dark) {} }
                try {
                    repeat(3) { reset.render(it * 16_666_667L).close() }
                } finally {
                    reset.close()
                }
            }
            scope.cancel()
        }
    }
}
