package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class CanvasBackgroundTest {
    private class FilesMemory(val project: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null
        var preferences: ByteArray? = null

        override suspend fun open() = project

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(SwingUtilities.isEventDispatchThread())
            saved = bytes
            return true
        }

        override suspend fun readPreferences() = preferences

        override suspend fun writePreferences(bytes: ByteArray) {
            assertFalse(SwingUtilities.isEventDispatchThread())
            preferences = bytes
        }
    }

    @Test
    fun backgroundsRevealAlphaSurviveViewTransformsAndDoNotChangeSavedArtwork() = runBlocking {
        NativeLoader.load()
        val native = createNativeEngine(256, 192)
        val project =
            try {
                native.call(
                    EngineOperation.COMMAND,
                    """{"type":"select","rect":{"left":64,"top":48,"right":192,"bottom":144}}"""
                        .encodeToByteArray(),
                )
                native.call(
                    EngineOperation.COMMAND,
                    """{"type":"fill","x":100,"y":80,"color":[160,60,100,128],"tolerance":0}"""
                        .encodeToByteArray(),
                )
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files = FilesMemory(project)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val view = Size(900f, 700f)
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(900, 700) {
                    PodorTheme(controller.preferences.language) {
                        Box(Modifier.fillMaxSize().background(StudioTheme.background)) {
                            CanvasWorkspace(controller, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                                CanvasBackgroundMenu(controller)
                            }
                        }
                    }
                }
            }
        var tick = 0L
        fun render() = scene.render(tick++ * 16_666_667L)
        fun settle() {
            repeat(40) { render().close() }
        }
        fun pixel(point: Offset): Color {
            val at = controller.viewport.toView(point, view, controller.document)
            return render().use {
                it.toComposeImageBitmap().toPixelMap()[at.x.roundToInt(), at.y.roundToInt()]
            }
        }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            settle()
        }
        fun capture(name: String) {
            val path = Path.of("build/reports/screenshots/$name.png")
            Files.createDirectories(path.parent)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) }
            }
        }
        suspend fun waitFor(condition: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        condition()
                    }
                ) delay(5)
            }
        try {
            waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            waitFor { controller.document.width == 256 && !controller.busy }
            val original = withContext(Dispatchers.Main) { controller.frame }
            val document = withContext(Dispatchers.Main) { controller.document }
            withContext(Dispatchers.Main) {
                settle()
                assertEquals(Color.White, pixel(Offset(24f, 24f)))
                val center = pixel(Offset(128f, 96f))
                assertEquals(207f / 255, center.red, 0.005f)
                controller.updatePreferences(
                    controller.preferences.copy(canvasBackground = CanvasBackground.Gray)
                )
                settle()
                assertEquals(StudioTheme.canvasGray, pixel(Offset(24f, 24f)))
                assertEquals(
                    (80f + StudioTheme.canvasGray.red * 127f) / 255,
                    pixel(Offset(128f, 96f)).red,
                    0.005f,
                )
                capture("canvas-background-gray")
                click(862f, 662f)
                capture("canvas-background-menu")
                click(745f, 570f)
            }
            waitFor { controller.preferences.canvasBackground == CanvasBackground.Transparent }
            withContext(Dispatchers.Main) {
                settle()
                val corners = buildSet {
                    for (x in 16..48 step 2) for (y in 16..36 step 2) add(
                        pixel(Offset(x.toFloat(), y.toFloat()))
                    )
                }
                assertTrue(StudioTheme.checkerLight in corners)
                assertTrue(StudioTheme.checkerDark in corners)
                assertEquals(2, corners.size, "The repeating checker shader blurred its cells")
                capture("canvas-background-transparent")
                for (rotation in listOf(-37f, 0f, 52f)) {
                    controller.viewport =
                        Viewport(zoom = 0.85f, rotation = rotation, mirrored = true)
                    settle()
                    assertTrue(
                        pixel(Offset(24f, 24f)) in
                            listOf(StudioTheme.checkerLight, StudioTheme.checkerDark)
                    )
                }
                capture("canvas-background-rotated")
                assertSame(original, controller.frame)
                assertEquals(document, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                controller.prepareAdjustment(AdjustmentKind.LayerBlend)
            }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                controller.updateAdjustment(
                    controller.adjustmentPreview!!.settings.copy(opacity = 0.25f)
                )
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                val preview =
                    controller.adjustmentPreview!!.frame.tiles[1L shl 32]!!.image.toPixelMap()
                assertEquals(32f / 255, preview[0, 96].alpha, 0.005f)
                controller.cancelAdjustment()
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertContentEquals(project, files.saved)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                assertEquals(
                    CanvasBackground.Transparent,
                    Json.decodeFromString<Preferences>(files.preferences!!.decodeToString())
                        .canvasBackground,
                )
                settle()
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
