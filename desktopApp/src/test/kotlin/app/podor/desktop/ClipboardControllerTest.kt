package app.podor.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ImageClipboard
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.coroutines.*

class ClipboardControllerTest {
    private class MemoryClipboard : ImageClipboard {
        @Volatile var image: ClipboardImage? = null
        @Volatile var writes = 0
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun read(): ClipboardImage? {
            assertFalse(SwingUtilities.isEventDispatchThread())
            return image
        }

        override suspend fun write(image: ClipboardImage) {
            assertFalse(SwingUtilities.isEventDispatchThread())
            writes++
            gate?.await()
            if (failWrite) error("剪贴板正被占用，请稍后重试")
            this.image = image
        }
    }

    private suspend fun waitFor(predicate: () -> Boolean) =
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(5)
        }

    private suspend fun session(block: suspend (StudioController, MemoryClipboard) -> Unit) {
        NativeLoader.load()
        val engine = createNativeEngine(128, 96)
        val project =
            try {
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"fill","x":0,"y":0,"color":[140,40,80,180],"tolerance":0}"""
                        .encodeToByteArray(),
                )
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
            }
        val clipboard = MemoryClipboard()
        val files =
            object : ProjectFiles {
                override val clipboard = clipboard

                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Clipboard must not save")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        try {
            waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            waitFor {
                controller.hasCanvas &&
                    !controller.busy &&
                    controller.previews.revision == controller.document.revision
            }
            block(controller, clipboard)
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }

    @Test
    fun selectedCopyPasteAndCutKeepTheirPixelsPositionAndUndoBoundaries() = runBlocking {
        session { controller, clipboard ->
            withContext(Dispatchers.Main) {
                controller.selectionKind = SelectionKind.Ellipse
                controller.select(Offset(20f, 10f), Offset(90f, 70f))
            }
            waitFor { controller.document.selection != null }
            val before = withContext(Dispatchers.Main) { controller.document }
            val frame = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) { controller.clipboard(ClipboardAction.Copy) }
            waitFor { clipboard.image != null && !controller.busy }
            val copied = assertNotNull(clipboard.image)
            assertEquals(ClipboardOrigin(128, 96, 20, 10), copied.origin)
            val pixels = ImageIO.read(ByteArrayInputStream(copied.png))
            assertEquals(70, pixels.width)
            assertEquals(60, pixels.height)
            assertEquals(0, pixels.getRGB(0, 0) ushr 24)
            assertEquals(180, pixels.getRGB(35, 30) ushr 24)
            withContext(Dispatchers.Main) {
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                controller.clipboard(ClipboardAction.Paste)
            }
            waitFor {
                controller.document.layers.size == 2 &&
                    !controller.busy &&
                    controller.previews.revision == controller.document.revision
            }
            withContext(Dispatchers.Main) {
                assertNull(controller.document.selection)
                assertEquals(2, controller.document.active)
                assertTrue(controller.hasUnsavedChanges)
                assertTrue(controller.document.layers.all { it.id in controller.previews.images })
                controller.command("undo")
            }
            waitFor { controller.document.layers.size == 1 && !controller.busy }
            withContext(Dispatchers.Main) {
                assertFalse(controller.hasUnsavedChanges)
                controller.select(before.selection)
            }
            waitFor { controller.document.selection == before.selection }
            withContext(Dispatchers.Main) { controller.clipboard(ClipboardAction.Cut) }
            waitFor { controller.status == "已剪切，可撤销" && !controller.busy }
            withContext(Dispatchers.Main) {
                val tile = controller.frame.tiles.getValue(0L).image.toPixelMap()
                assertEquals(0f, tile[55, 40].alpha, 0.005f)
                assertEquals(frame.tiles.getValue(0L).image.toPixelMap()[10, 10], tile[10, 10])
                controller.command("undo")
            }
            waitFor { !controller.hasUnsavedChanges && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(before.selection, controller.document.selection)
                assertEquals(
                    frame.tiles.getValue(0L).image.toPixelMap()[55, 40],
                    controller.frame.tiles.getValue(0L).image.toPixelMap()[55, 40],
                )
            }
        }
    }

    @Test
    fun delayedOrFailedClipboardWriteNeverCutsBeforeSuccessAndInvalidPasteIsAtomic() = runBlocking {
        session { controller, clipboard ->
            val before = withContext(Dispatchers.Main) { controller.document }
            val frame = withContext(Dispatchers.Main) { controller.frame }
            clipboard.gate = CompletableDeferred()
            clipboard.failWrite = true
            withContext(Dispatchers.Main) { controller.clipboard(ClipboardAction.Cut) }
            waitFor { clipboard.writes == 1 && controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
            }
            clipboard.gate!!.complete(Unit)
            waitFor { controller.error != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                controller.dismissError()
                clipboard.image = ClipboardImage(byteArrayOf(1, 2, 3))
                controller.clipboard(ClipboardAction.Paste)
            }
            waitFor { controller.error != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
                controller.dismissError()
                clipboard.image = null
                controller.clipboard(ClipboardAction.Paste)
            }
            waitFor { controller.error != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals("剪贴板中没有图片", controller.error)
                assertEquals(before, controller.document)
            }
        }
    }
}
