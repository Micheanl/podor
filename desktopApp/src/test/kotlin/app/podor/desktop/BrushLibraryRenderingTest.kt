package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class BrushLibraryRenderingTest {
    @Test
    fun favoritesAndSearchChooseBrushesWithoutChangingCanvasAndStopRenderingWhenIdle() =
        runBlocking {
            NativeLoader.load()
            val preferences =
                Preferences(
                    language = Language.English,
                    brushes = listOf(BrushPreset.Ink.copy(id = "custom-1", label = "My ink")),
                    plugins =
                        listOf(
                            BrushPack(
                                "studio",
                                "Studio",
                                brushes = listOf(BrushPreset.Ink.copy(label = "Studio liner")),
                            )
                        ),
                )
            val files =
                object : ProjectFiles {
                    override suspend fun open(): ByteArray? = null

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Brush library must not save artwork")

                    override suspend fun readPreferences() =
                        Json.encodeToString(preferences).encodeToByteArray()

                    override suspend fun writePreferences(bytes: ByteArray) {}
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(360, 850) {
                        PodorTheme(controller.preferences.language) {
                            Surface(color = StudioTheme.panel) {
                                Box(Modifier.fillMaxSize().padding(20.dp)) {
                                    BrushControls(controller)
                                }
                            }
                        }
                    }
                }
            var frame = 0L
            fun settle() {
                repeat(40) { scene.render(frame++ * 16_666_667L).close() }
            }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                settle()
            }
            fun screenshot(name: String) {
                val file = Path.of("build/reports/screenshots/$name.png")
                Files.createDirectories(file.parent)
                scene.render(frame++ * 16_666_667L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
                }
            }
            try {
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { controller.ready }) delay(5)
                }
                withContext(Dispatchers.Main) {
                    controller.selectPreset(BrushPreset.Marker)
                    val pixels = controller.frame
                    val document = controller.document
                    settle()
                    screenshot("brush-library")
                    click(149f, 487f)
                    assertEquals(setOf("ink"), controller.preferences.favoriteBrushes)
                    assertEquals(
                        "marker",
                        controller.brush.preset.id,
                        "Starring must not select the brush",
                    )
                    click(318f, 371f)
                    screenshot("brush-library-collections")
                    click(275f, 473f)
                    assertEquals(BrushCollection.Favorites, controller.brushCollection)
                    screenshot("brush-library-favorites")
                    click(65f, 440f)
                    assertEquals("ink", controller.brush.preset.id)
                    click(274f, 371f)
                    controller.brushCollection = BrushCollection.Extensions
                    controller.brushLibraryQuery = " STUDIO "
                    settle()
                    screenshot("brush-library-search")
                    click(65f, 504f)
                    assertEquals("plugin:studio/ink", controller.brush.preset.id)
                    controller.brushCollection = BrushCollection.Custom
                    controller.brushLibraryQuery = "My"
                    settle()
                    click(65f, 504f)
                    assertEquals("custom-1", controller.brush.preset.id)
                    controller.brushCollection = BrushCollection.All
                    controller.brushLibraryQuery = "no such brush"
                    settle()
                    screenshot("brush-library-empty")
                    assertSame(pixels, controller.frame)
                    assertEquals(document, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                    assertFalse(scene.hasInvalidations())
                }
            } finally {
                withContext(Dispatchers.Main) {
                    scene.close()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
