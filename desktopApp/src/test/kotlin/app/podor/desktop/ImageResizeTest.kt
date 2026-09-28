package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class ImageResizeTest {
    @Test
    fun scalingRefreshesCanvasAndPreviewsAndOnlySavesWhenAsked() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(256, 128)
            val original =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"select","rect":{"left":128,"top":0,"right":256,"bottom":128}}"""
                            .encodeToByteArray(),
                    )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":200,"y":60,"color":[180,60,90,128],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"add_layer"}""".encodeToByteArray(),
                    )
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val reference = ProjectReference("resized.podor", "Resized")
            var saved: ByteArray? = null
            val files =
                object : ProjectFiles {
                    override suspend fun open() = original

                    override suspend fun openDocument(reference: ProjectReference?) =
                        OpenedProject(original, reference)

                    override suspend fun save(bytes: ByteArray, png: Boolean) = false

                    override suspend fun saveDocument(
                        bytes: ByteArray,
                        reference: ProjectReference?,
                        saveAs: Boolean,
                    ): ProjectReference {
                        check(!javax.swing.SwingUtilities.isEventDispatchThread())
                        saved = bytes
                        return assertNotNull(reference)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.navigate(WorkspaceDestination.Open(reference))
                }
                awaitState {
                    controller.hasCanvas &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                val layers = withContext(Dispatchers.Main) { controller.document.layers }
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(zoom = 2f, rotation = 30f)
                    controller.resizeImage(
                        64,
                        32,
                        ResampleFilter.Nearest,
                        controller.document.revision,
                    )
                }
                awaitState {
                    controller.document.width == 64 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                withContext(Dispatchers.Main) {
                    assertEquals(32, controller.document.height)
                    assertEquals(layers, controller.document.layers)
                    assertEquals(reference, controller.projectReference)
                    assertEquals(Viewport(), controller.viewport)
                    assertTrue(controller.hasUnsavedChanges)
                    assertNull(saved)
                    assertEquals(setOf(0L), controller.frame.tiles.keys)
                    val pixels = controller.frame.tiles.getValue(0L).image.toPixelMap()
                    assertEquals(0f, pixels[5, 16].alpha)
                    assertEquals(60 / 255f, pixels[50, 16].green, 0.005f)
                    assertEquals(128 / 255f, pixels[50, 16].alpha, 0.005f)
                    assertEquals(2, controller.document.active)
                    controller.command("undo")
                }
                awaitState { controller.document.width == 256 && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(
                        0f,
                        controller.frame.tiles[0L]?.image?.toPixelMap()?.get(5, 16)?.alpha ?: 0f,
                    )
                    assertEquals(
                        60 / 255f,
                        controller.frame.tiles.getValue(1L shl 32).image.toPixelMap()[50, 60].green,
                        0.005f,
                    )
                    controller.command("redo")
                }
                awaitState { controller.document.width == 64 && !controller.busy }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
                awaitState { saved != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.hasUnsavedChanges)
                    assertNull(controller.error)
                }
                val restored = createNativeEngine(1, 1)
                try {
                    val state =
                        restored.call(EngineOperation.LOAD, assertNotNull(saved)).decodeToString()
                    assertTrue(state.contains("\"width\":64"))
                    assertTrue(state.contains("\"height\":32"))
                } finally {
                    restored.close()
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun proportionPreviewAndSizeOptionsRespondAndBecomeIdle() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(160, 120)
            val preview =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[120,40,70,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    val bytes = native.call(EngineOperation.THUMBNAIL)
                    rgbaBitmap(bytes, 4, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
                } finally {
                    native.close()
                }
            withContext(Dispatchers.Main) {
                for (language in Language.entries) {
                    val settings = mutableStateOf(ImageSize(160, 120))
                    val scene =
                        ImageComposeScene(440, 590) {
                            PodorTheme(language) {
                                Surface(color = StudioTheme.panel) {
                                    Column(Modifier.padding(24.dp)) {
                                        ImageSizeSettings(settings.value, preview) {
                                            settings.value = it
                                        }
                                    }
                                }
                            }
                        }
                    var frame = 0L
                    suspend fun settle() {
                        repeat(40) {
                            scene.render(frame++ * 16_666_667L).close()
                            yield()
                        }
                    }
                    suspend fun click(x: Float, y: Float) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        settle()
                    }
                    try {
                        settle()
                        click(220f, 304f)
                        assertFalse(settings.value.locked)
                        click(90f, 370f)
                        assertEquals("80", settings.value.width)
                        assertEquals("60", settings.value.height)
                        click(320f, 450f)
                        assertEquals(ResampleFilter.Nearest, settings.value.filter)
                        scene.render(frame++ * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            assertEquals(120 / 255f, pixels[220, 120].red, 0.01f)
                            assertEquals(StudioTheme.background.red, pixels[140, 120].red, 0.01f)
                            val output =
                                Path.of(
                                    "build/reports/screenshots/image-size-${language.name.lowercase()}.png"
                                )
                            Files.createDirectories(output.parent)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(output, it.bytes)
                            }
                        }
                        assertFalse(scene.hasInvalidations())
                        settings.value = settings.value.withWidth("9000")
                        settle()
                        assertFalse(settings.value.valid)
                        assertFalse(scene.hasInvalidations())
                    } finally {
                        scene.close()
                    }
                }
            }
        }
}
