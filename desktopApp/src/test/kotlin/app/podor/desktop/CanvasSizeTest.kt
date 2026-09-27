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
class CanvasSizeTest {
    @Test
    fun croppingToEmptyClearsCachedTilesAndUndoRestoresPixelsWithoutSavingAutomatically() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(256, 256)
            val original =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"select","rect":{"left":128,"top":128,"right":256,"bottom":256}}"""
                            .encodeToByteArray(),
                    )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":200,"y":200,"color":[255,0,0,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val reference = ProjectReference("canvas.podor", "Canvas")
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
                        assertEquals("canvas.podor", reference!!.id)
                        saved = bytes
                        return reference
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            suspend fun resize(width: Int, height: Int) {
                withContext(Dispatchers.Main) {
                    controller.resizeCanvas(
                        width,
                        height,
                        CanvasAnchor.TopLeft,
                        controller.document.revision,
                    )
                }
                awaitState {
                    controller.document.width == width &&
                        controller.document.height == height &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
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
                withContext(Dispatchers.Main) {
                    assertEquals(1, controller.frame.tiles.size)
                    controller.viewport =
                        Viewport(zoom = 4f, pan = Offset(50f, 60f), rotation = 30f)
                }
                resize(64, 64)
                withContext(Dispatchers.Main) {
                    assertTrue(controller.frame.tiles.isEmpty())
                    assertEquals(Viewport(), controller.viewport)
                    assertEquals(reference, controller.projectReference)
                    assertTrue(controller.hasUnsavedChanges)
                    assertNull(saved)
                    controller.command("undo")
                }
                awaitState { controller.document.width == 256 && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(
                        1f,
                        controller.frame.tiles
                            .getValue((1L shl 32) or 1L)
                            .image
                            .toPixelMap()[50, 50]
                            .red,
                        0.001f,
                    )
                    controller.command("redo")
                }
                awaitState { controller.document.width == 64 && !controller.busy }
                resize(256, 256)
                withContext(Dispatchers.Main) {
                    assertTrue(controller.frame.tiles.isEmpty())
                    assertEquals(
                        0f,
                        controller.previews.images.getValue(0).toPixelMap()[70, 70].alpha,
                        0.001f,
                    )
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy && !controller.hasUnsavedChanges }
                val reopened = createNativeEngine(1, 1)
                try {
                    reopened.call(EngineOperation.LOAD, saved!!)
                    val frame = reopened.call(EngineOperation.FRAME)
                    assertEquals(16, frame.size)
                } finally {
                    reopened.close()
                }
                val before = withContext(Dispatchers.Main) { controller.document }
                withContext(Dispatchers.Main) {
                    controller.resizeCanvas(100, 100, CanvasAnchor.Center, 0)
                }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) { assertEquals(before, controller.document) }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun previewShowsExtensionAndCroppingAndAnchorAnimationSettles() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(160, 120)
            val artwork =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[60,80,130,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"select","rect":{"left":40,"top":30,"right":120,"bottom":90}}"""
                            .encodeToByteArray(),
                    )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":80,"y":60,"color":[190,70,100,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    val bytes = native.call(EngineOperation.THUMBNAIL)
                    rgbaBitmap(bytes, 4, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
                } finally {
                    native.close()
                }
            val width = mutableStateOf("320")
            val height = mutableStateOf("240")
            val anchor = mutableStateOf(CanvasAnchor.Center)
            withContext(Dispatchers.Main) {
                val scene =
                    ImageComposeScene(440, 510) {
                        PodorTheme(Language.English) {
                            Surface(color = StudioTheme.panel) {
                                Column(Modifier.padding(24.dp)) {
                                    CanvasSizeSettings(
                                        160,
                                        120,
                                        artwork,
                                        width.value,
                                        height.value,
                                        anchor.value,
                                        { width.value = it },
                                        { height.value = it },
                                        { anchor.value = it },
                                    )
                                }
                            }
                        }
                    }
                try {
                    var frame = 0L
                    suspend fun settle() {
                        repeat(40) {
                            scene.render(frame++ * 16_666_667L).close()
                            yield()
                        }
                    }
                    fun capture(name: String) {
                        val path = Path.of("build/reports/screenshots/$name.png")
                        Files.createDirectories(path.parent)
                        scene.render(frame++ * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            assertTrue(pixels[220, 119].red > 0.6f)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(path, it.bytes)
                            }
                        }
                    }
                    settle()
                    capture("canvas-size-expand")
                    width.value = "100"
                    height.value = "80"
                    settle()
                    capture("canvas-size-crop")
                    assertFalse(scene.hasInvalidations())
                    scene.sendPointerEvent(PointerEventType.Press, Offset(400f, 387f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(400f, 387f))
                    settle()
                    assertEquals(CanvasAnchor.BottomRight, anchor.value)
                    assertFalse(scene.hasInvalidations())
                    assertEquals(-60, anchor.value.offsetX(160, 100))
                    assertEquals(-40, anchor.value.offsetY(120, 80))
                    capture("canvas-size-anchor")
                } finally {
                    scene.close()
                }
            }
        }
}
