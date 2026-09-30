package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.BrushPreviewCache
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
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
            suspend fun settle() {
                repeat(40) {
                    scene.render(frame++ * 16_666_667L).close()
                    delay(2)
                }
            }
            fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                yield(node)
                node.children.forEach { yieldAll(descendants(it)) }
            }
            fun nodes() =
                scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
            suspend fun click(label: String) {
                val point =
                    nodes()
                        .filter {
                            it.config.contains(SemanticsActions.OnClick) &&
                                !it.boundsInWindow.isEmpty &&
                                descendants(it).any { child ->
                                    child.config
                                        .getOrNull(SemanticsProperties.ContentDescription)
                                        ?.contains(label) == true ||
                                        child.config.getOrNull(SemanticsProperties.Text)?.any { text
                                            ->
                                            text.text == label
                                        } == true
                                }
                        }
                        .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                        .boundsInWindow
                        .center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                settle()
            }
            suspend fun collection(value: BrushCollection) {
                click(trValue(controller.brushCollection.label, controller.preferences.language))
                click(trValue(value.label, controller.preferences.language))
                assertEquals(value, controller.brushCollection)
            }
            suspend fun search(value: String) {
                val input =
                    nodes().single {
                        it.config.contains(SemanticsActions.SetText) &&
                            it.config.getOrNull(SemanticsProperties.EditableText)?.text ==
                                controller.brushLibraryQuery &&
                            !it.boundsInWindow.isEmpty
                    }
                assertTrue(
                    assertNotNull(input.config[SemanticsActions.SetText].action)(
                        AnnotatedString(value)
                    )
                )
                settle()
                assertEquals(value, controller.brushLibraryQuery)
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
                val brushes = withContext(Dispatchers.Main) { controller.brushes }
                for (preset in brushes) BrushPreviewCache.get(preset)
                withContext(Dispatchers.Main) {
                    controller.selectPreset(BrushPreset.Marker)
                    val pixels = controller.frame
                    val document = controller.document
                    settle()
                    screenshot("brush-library")
                    click(
                        "Favorite brush · ${trValue(BrushPreset.Ink.label, preferences.language)}"
                    )
                    assertEquals(setOf("ink"), controller.preferences.favoriteBrushes)
                    assertEquals(
                        "marker",
                        controller.brush.preset.id,
                        "Starring must not select the brush",
                    )
                    click(trValue(BrushCollection.All.label, preferences.language))
                    screenshot("brush-library-collections")
                    click(trValue(BrushCollection.Favorites.label, preferences.language))
                    assertEquals(BrushCollection.Favorites, controller.brushCollection)
                    screenshot("brush-library-favorites")
                    click(trValue(BrushPreset.Ink.label, preferences.language))
                    assertEquals("ink", controller.brush.preset.id)
                    click("Search brushes")
                    collection(BrushCollection.Extensions)
                    search(" STUDIO ")
                    screenshot("brush-library-search")
                    click("Studio liner")
                    assertEquals("plugin:studio/ink", controller.brush.preset.id)
                    collection(BrushCollection.Custom)
                    search("My")
                    click("My ink")
                    assertEquals("custom-1", controller.brush.preset.id)
                    collection(BrushCollection.All)
                    search("no such brush")
                    screenshot("brush-library-empty")
                    assertSame(pixels, controller.frame)
                    assertEquals(document, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                    repeat(200) { scene.render(frame++ * 16_666_667L).close() }
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
