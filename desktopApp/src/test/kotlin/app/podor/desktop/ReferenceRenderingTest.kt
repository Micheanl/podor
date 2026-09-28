package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.data.*
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.*
import app.podor.ui.StudioApp
import app.podor.ui.StudioTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReferenceRenderingTest {
    private fun image(width: Int = 320, height: Int = 240): ByteArray {
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setRGB(x, y, if (x < width / 2) 0xFFC77781.toInt() else 0x80649EB2.toInt())
        }
        return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
    }

    private class ReferenceFiles(var bytes: ByteArray) : ProjectFiles {
        val exports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            exports += bytes
            return true
        }

        var imports = 0
        var reads = 0
        var gate: CompletableDeferred<Unit>? = null
        override val clipboard =
            object : ImageClipboard {
                override suspend fun read(): ClipboardImage {
                    reads++
                    gate?.await()
                    return ClipboardImage(bytes)
                }

                override suspend fun write(image: ClipboardImage) = error("Reference reads only")
            }

        override suspend fun readPreferences() =
            Json.encodeToString(Preferences(startupScreen = StartupScreen.Canvas))
                .encodeToByteArray()

        override suspend fun open(): ByteArray? = null

        override suspend fun openImage(): OpenedProject {
            imports++
            gate?.await()
            return OpenedProject(bytes, ProjectReference("reference.png", "Color study", false))
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
            error("References must not save artwork")
    }

    @Test
    fun referencesAreCanvasObjectsAndNeverChangeArtworkOrExports() = runBlocking {
        NativeLoader.load()
        val files = ReferenceFiles(image())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val references = controller.references
        var scene: ImageComposeScene? = null
        var tick = 0L
        val view =
            Size(1360f - StudioTheme.inspectorWidth.value - StudioTheme.inspectorMargin.value, 836f)
        fun settle() {
            repeat(35) { scene!!.render(tick++ * 16_666_667L).close() }
        }
        fun at(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + Offset(0f, 64f)
        fun drag(from: Offset, to: Offset) {
            scene!!.sendPointerEvent(PointerEventType.Press, at(from))
            scene!!.sendPointerEvent(PointerEventType.Move, at((from + to) / 2f))
            scene!!.sendPointerEvent(PointerEventType.Move, at(to))
            scene!!.sendPointerEvent(PointerEventType.Release, at(to))
            scene!!.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            settle()
        }
        suspend fun await(predicate: () -> Boolean) =
            withTimeout(15_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(5) }
        fun capture(name: String) {
            val path = Path.of("build/reports/screenshots/references-$name.png")
            Files.createDirectories(path.parent)
            scene!!.render(tick++ * 16_666_667L).use {
                it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    Files.write(path, data.bytes)
                }
            }
        }
        suspend fun export(): ByteArray {
            val count = files.exports.size
            withContext(Dispatchers.Main) { controller.export(ExportOptions()) }
            await { files.exports.size > count && !controller.busy }
            return files.exports.last()
        }
        try {
            await { controller.ready }
            val original = withContext(Dispatchers.Main) { controller.frame to controller.document }
            val beforeExport = export()
            withContext(Dispatchers.Main) {
                scene = ImageComposeScene(1360, 900) { StudioApp(controller) }
                settle()
                assertTrue(
                    scene!!.sendKeyEvent(
                        KeyEvent(
                            Key.V,
                            KeyEventType.KeyDown,
                            isCtrlPressed = true,
                            isShiftPressed = true,
                        )
                    )
                )
                scene!!.sendKeyEvent(
                    KeyEvent(Key.V, KeyEventType.KeyUp, isCtrlPressed = true, isShiftPressed = true)
                )
            }
            await { references.images.size == 1 && !references.loading }
            withContext(Dispatchers.Main) {
                settle()
                capture("canvas")
                val reference = references.selected!!
                assertEquals(320, reference.bitmap.width)
                assertEquals(240, reference.bitmap.height)
                assertEquals(128 / 255f, reference.bitmap.toPixelMap()[250, 100].alpha, 0.01f)
                val initial = reference.placement.bounds
                drag(initial.center, initial.center + Offset(80f, 45f))
                assertEquals(initial.left + 80f, reference.placement.bounds.left, 0.1f)
                assertEquals(initial.top + 45f, reference.placement.bounds.top, 0.1f)
                val moved = reference.placement.bounds
                drag(
                    moved.bottomRight,
                    moved.bottomRight + Offset(moved.width * 0.2f, moved.height * 0.2f),
                )
                assertEquals(moved.width * 1.2f, reference.placement.bounds.width, 0.1f)
                assertEquals(moved.topLeft, reference.placement.bounds.topLeft)
                controller.viewport =
                    controller.viewport.copy(
                        zoom = 1.1f,
                        rotation = 20f,
                        mirrored = true,
                        pan = Offset(15f, -10f),
                    )
                settle()
                val rotated = reference.placement.bounds
                drag(rotated.center, rotated.center + Offset(12f, -7f))
                assertEquals(rotated.left + 12f, reference.placement.bounds.left, 0.1f)
                assertEquals(rotated.top - 7f, reference.placement.bounds.top, 0.1f)
                capture("transformed")
                references.visible = false
                settle()
                capture("hidden")
                references.visible = true
                references.load(false, controller.document)
            }
            await { references.images.size == 2 && !references.loading }
            withContext(Dispatchers.Main) {
                controller.viewport = Viewport()
                settle()
                capture("multiple")
                scene!!.sendKeyEvent(KeyEvent(Key.Delete, KeyEventType.KeyDown))
                assertEquals(1, references.images.size)
                assertSame(original.first, controller.frame)
                assertSame(original.second, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                scene!!.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                assertNull(references.selected)
                settle()
                assertFalse(scene!!.hasInvalidations())
            }
            assertContentEquals(beforeExport, export())
        } finally {
            withContext(Dispatchers.Main) {
                scene?.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun loadingIsBoundedCancellationAndInvalidImagesPreserveExistingReferences() = runBlocking {
        NativeLoader.load()
        val files = ReferenceFiles(image(4096, 16))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val references = ReferenceController(files, scope)
        suspend fun done() =
            withTimeout(15_000) {
                while (withContext(Dispatchers.Main) { references.loading }) delay(5)
            }
        try {
            withContext(Dispatchers.Main) { references.load(true) }
            done()
            withContext(Dispatchers.Main) {
                assertEquals(StudioDefaults.maxReferenceEdge, references.selected!!.bitmap.width)
                assertEquals(4096, references.selected!!.originalWidth)
                files.gate = CompletableDeferred()
                references.load(false)
                repeat(20) { references.load(true) }
            }
            withTimeout(2_000) { while (files.imports == 0) delay(5) }
            withContext(Dispatchers.Main) { references.cancelLoading() }
            done()
            withContext(Dispatchers.Main) {
                assertEquals(1, references.images.size)
                assertEquals(1, files.reads)
                files.gate = null
                files.bytes = byteArrayOf(1, 2, 3)
                references.load(false)
            }
            done()
            withContext(Dispatchers.Main) {
                assertNotNull(references.error)
                assertEquals(1, references.images.size)
                files.bytes = image(4, 4)
            }
            repeat(StudioDefaults.maxReferenceImages - 1) {
                withContext(Dispatchers.Main) { references.load(false) }
                done()
            }
            withContext(Dispatchers.Main) {
                assertFalse(references.canAdd)
                val count = files.imports
                references.load(false)
                assertEquals(count, files.imports)
                references.remove()
                assertTrue(references.canAdd)
                assertNotNull(references.selected)
                while (references.images.isNotEmpty()) references.remove()
                assertNull(references.selected)
            }
        } finally {
            scope.cancel()
        }
    }
}
