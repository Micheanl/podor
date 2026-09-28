package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AdjustmentPreviewTest {
    private class FilesMemory(val bytes: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null

        override suspend fun open() = bytes

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved = bytes
            return true
        }
    }

    private fun project(): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(256, 192)
        fun command(value: String) = engine.call(EngineOperation.COMMAND, value.encodeToByteArray())
        try {
            command("""{"type":"fill","x":0,"y":0,"color":[65,91,116,255],"tolerance":0}""")
            command("""{"type":"add_layer"}""")
            command("""{"type":"select","rect":{"left":32,"top":24,"right":224,"bottom":168}}""")
            command("""{"type":"fill","x":50,"y":50,"color":[177,91,111,190],"tolerance":0}""")
            command(
                """{"type":"set_layer","id":2,"name":"Color study","visible":true,"opacity":0.8}"""
            )
            return engine.call(EngineOperation.SAVE)
        } finally {
            engine.close()
        }
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: FilesMemory,
    ) {
        var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        fun settle() {
            repeat(35) { render().close() }
        }

        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            settle()
        }

        fun key(key: Key) {
            assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            render().close()
        }

        fun pixel() = render().use { it.toComposeImageBitmap().toPixelMap()[520, 480] }

        fun capture(name: String) {
            val directory = Path.of("build/reports/screenshots")
            Files.createDirectories(directory)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(directory.resolve("$name.png"), it.bytes)
                }
            }
        }

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        predicate()
                    }
                ) delay(5)
            }
    }

    private suspend fun session(block: suspend Session.() -> Unit) {
        val files = FilesMemory(project())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) { ImageComposeScene(1360, 900) { StudioApp(controller) } }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 256 && !controller.busy }
            withContext(Dispatchers.Main) { session.settle() }
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun slidersCompareResetConfirmAndUndoWorkOnTheCanvas() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { pixel() }
            val originalFrame = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) {
                click(1298f, 194f)
                capture("adjustments-menu")
                click(1190f, 407f)
            }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                capture("adjustment-neutral")
                click(1260f, 368f)
            }
            waitFor {
                controller.adjustmentPreview!!.settings.brightness > 0.1f &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                assertSame(originalFrame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                assertNull(files.saved)
                assertNotEquals(original, pixel())
                capture("adjustment-preview")
                click(449f, 817f)
                assertTrue(controller.adjustmentPreview!!.comparing)
                assertEquals(original, pixel())
                click(449f, 817f)
                assertNotEquals(original, pixel())
                click(497f, 817f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertFalse(controller.adjustmentPreview!!.changed)
                assertEquals(original, pixel())
                val value =
                    controller.adjustmentPreview!!
                        .settings
                        .copy(brightness = 0.2f, contrast = 0.1f, saturation = -0.3f)
                controller.updateAdjustment(value)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            val expected =
                withContext(Dispatchers.Main) {
                    val value = pixel()
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    settle()
                    capture("adjustment-english")
                    key(Key.Enter)
                    value
                }
            waitFor { controller.adjustmentPreview == null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(expected, pixel())
                assertTrue(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
                controller.command("undo")
            }
            waitFor { !controller.document.canUndo && !controller.busy }
            withContext(Dispatchers.Main) {
                settle()
                assertEquals(original, pixel())
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun rapidChangesUseLatestSettingsAndCancelNeverSavesPreviewPixels() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) { controller.prepareAdjustment(AdjustmentKind.Blur) }
            waitFor { controller.adjustmentPreview != null }
            withContext(Dispatchers.Main) {
                val settings = controller.adjustmentPreview!!.settings
                repeat(300) { controller.updateAdjustment(settings.copy(sigma = 0.5f + (it % 32))) }
                controller.updateAdjustment(settings.copy(sigma = 17f))
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(17f, controller.adjustmentPreview!!.renderedSettings!!.sigma)
                assertTrue(controller.adjustmentPreview!!.changed)
                assertSame(original, controller.frame)
                controller.updateAdjustment(
                    controller.adjustmentPreview!!.settings.copy(sigma = 32f)
                )
                key(Key.Escape)
                assertNull(controller.adjustmentPreview)
                assertSame(original, controller.frame)
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertContentEquals(files.bytes, files.saved)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
                settle()
                assertFalse(scene.hasInvalidations())
            }
        }
    }
}
