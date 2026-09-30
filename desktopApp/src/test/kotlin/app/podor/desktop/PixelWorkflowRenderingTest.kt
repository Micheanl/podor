package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PixelWorkflowRenderingTest {
    private class Session(val controller: StudioController, val scene: ImageComposeScene) {
        private var frame = 0L
        private val view
            get() = canvasBounds().size

        fun render() = scene.render(frame++ * 16_666_667L)

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        fun canvasBounds(): Rect =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" &&
                        it.boundsInWindow.width > 100f &&
                        it.boundsInWindow.height > 100f
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                .boundsInWindow

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + canvasBounds().topLeft

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (true) {
                    withContext(Dispatchers.Main) { render().close() }
                    delay(5)
                    if (withContext(Dispatchers.Main) { predicate() && !controller.busy }) break
                }
            }

        suspend fun key(key: Key, command: Boolean = false, shift: Boolean = false) =
            withContext(Dispatchers.Main) {
                assertTrue(
                    scene.sendKeyEvent(
                        KeyEvent(
                            key,
                            KeyEventType.KeyDown,
                            isCtrlPressed = command,
                            isShiftPressed = shift,
                        )
                    )
                )
                scene.sendKeyEvent(
                    KeyEvent(
                        key,
                        KeyEventType.KeyUp,
                        isCtrlPressed = command,
                        isShiftPressed = shift,
                    )
                )
                render().close()
            }

        suspend fun mouse(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, position(point))
                render().close()
            }

        fun pixels(): IntArray =
            IntArray(64 * 64).also { pixels ->
                controller.frame.tiles.values
                    .firstOrNull { it.x == 0 && it.y == 0 }
                    ?.image
                    ?.let { image ->
                        val tile = IntArray(image.width * image.height)
                        image.readPixels(tile)
                        for (y in 0 until minOf(64, image.height)) tile.copyInto(
                            pixels,
                            destinationOffset = y * 64,
                            startIndex = y * image.width,
                            endIndex = y * image.width + minOf(64, image.width),
                        )
                    }
            }

        fun capture(name: String) {
            val file = Path.of("build/reports/screenshots/$name.png")
            Files.createDirectories(file.parent)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
            }
        }
    }

    private suspend fun withSession(block: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(64, 48)
        val project =
            try {
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Pixel editing must not save automatically")

                override suspend fun readPreferences() =
                    Json.encodeToString(
                            Preferences(language = Language.English, appearance = Appearance.Light)
                        )
                        .encodeToByteArray()

                override suspend fun writePreferences(bytes: ByteArray) {}
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) { ImageComposeScene(1360, 900) { StudioApp(controller) } }
        val session = Session(controller, scene)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 64 && controller.document.height == 48 }
            repeat(25) {
                withContext(Dispatchers.Main) { session.render().close() }
                delay(3)
            }
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
    fun pixelShortcutsSelectOnePixelPencilAndAdjustIntegerSizesAndToggleGridWithoutEditingArtwork() =
        runBlocking {
            withSession {
                val document = controller.document
                val frame = controller.frame
                key(Key.E)
                assertEquals(Tool.Eraser, controller.tool)
                key(Key.P)
                assertEquals(Tool.Brush, controller.tool)
                assertEquals(BrushPreset.PixelPencil, controller.brush.preset)
                assertEquals(1f, controller.brush.size)
                key(Key.RightBracket)
                key(Key.RightBracket)
                assertEquals(3f, controller.brush.size)
                key(Key.LeftBracket)
                assertEquals(2f, controller.brush.size)
                repeat(3) { key(Key.LeftBracket) }
                assertEquals(1f, controller.brush.size)
                val grid = CanvasGridSettings(tiles = true, tileWidth = 8, tileHeight = 4)
                withContext(Dispatchers.Main) { controller.changeCanvasGrid(grid) }
                key(Key.G, command = true)
                assertEquals(grid.copy(pixels = true), controller.preferences.canvasGrid)
                assertEquals(Tool.Brush, controller.tool)
                key(Key.G, command = true)
                assertEquals(grid, controller.preferences.canvasGrid)
                assertEquals(document, controller.document)
                assertSame(frame, controller.frame)
                assertFalse(controller.document.canUndo)
            }
        }

    @Test
    fun realMouseMakesOpaqueOnePixelPathsAndUndoRedoRestoresExactPixels() = runBlocking {
        withSession {
            key(Key.P)
            withContext(Dispatchers.Main) {
                controller.brush = controller.brush.copy(color = 0xFF3020A0)
            }
            val revision = controller.document.revision
            mouse(PointerEventType.Press, Offset(12.5f, 18.5f))
            mouse(PointerEventType.Move, Offset(44.5f, 18.5f))
            mouse(PointerEventType.Move, Offset(44.5f, 30.5f))
            mouse(PointerEventType.Release, Offset(44.5f, 30.5f))
            waitFor { controller.document.revision > revision && controller.document.canUndo }
            val painted = withContext(Dispatchers.Main) { pixels() }
            assertTrue(painted.count { it ushr 24 != 0 } in 35..90)
            assertTrue(painted.all { (it ushr 24) in setOf(0, 255) }, "Pixel pencil has soft edges")
            assertTrue(painted.filter { it ushr 24 != 0 }.all { it == 0xFF3020A0.toInt() })
            for (x in 13..43) {
                assertEquals(255, painted[18 * 64 + x] ushr 24)
                assertEquals(0, painted[17 * 64 + x] ushr 24)
                assertEquals(0, painted[19 * 64 + x] ushr 24)
            }
            val content = controller.document.contentId
            key(Key.Z, command = true)
            waitFor { controller.document.canRedo && !controller.document.canUndo }
            assertTrue(withContext(Dispatchers.Main) { pixels().all { it == 0 } })
            key(Key.Z, command = true, shift = true)
            waitFor { controller.document.contentId == content && controller.document.canUndo }
            assertContentEquals(painted, withContext(Dispatchers.Main) { pixels() })
            key(Key.G, command = true)
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            repeat(200) {
                withContext(Dispatchers.Main) { render().close() }
                delay(1)
            }
            withContext(Dispatchers.Main) {
                capture("pixel-pencil-workflow")
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun secondFingerPansAndCancelsTheUncommittedPixelPathWithoutHistory() = runBlocking {
        withSession {
            key(Key.P)
            withContext(Dispatchers.Main) { controller.fingerDrawing = true }
            val revision = controller.document.revision
            val points = listOf(Offset(20.5f, 20.5f), Offset(36.5f, 20.5f))
            for ((index, point) in points.withIndex()) withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    if (index == 0) PointerEventType.Press else PointerEventType.Move,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(point),
                            true,
                            PointerType.Touch,
                            1f,
                        )
                    ),
                )
                render().close()
            }
            waitFor { pixels().any { it ushr 24 != 0 } }
            val viewport = controller.viewport
            withContext(Dispatchers.Main) {
                val first = position(points.last())
                val second = first + Offset(100f, 50f)
                for (step in 0..1) {
                    val delta = Offset(step * 30f, step * 15f)
                    scene.sendPointerEvent(
                        if (step == 0) PointerEventType.Press else PointerEventType.Move,
                        listOf(
                            ComposeScenePointer(
                                PointerId(1),
                                first + delta,
                                true,
                                PointerType.Touch,
                                1f,
                            ),
                            ComposeScenePointer(
                                PointerId(2),
                                second + delta,
                                true,
                                PointerType.Touch,
                                1f,
                            ),
                        ),
                    )
                    render().close()
                }
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            first + Offset(30f, 15f),
                            false,
                            PointerType.Touch,
                            1f,
                        ),
                        ComposeScenePointer(
                            PointerId(2),
                            second + Offset(30f, 15f),
                            false,
                            PointerType.Touch,
                            1f,
                        ),
                    ),
                )
                render().close()
            }
            waitFor { pixels().all { it == 0 } }
            assertEquals(revision, controller.document.revision)
            assertFalse(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            assertNotEquals(viewport, controller.viewport)
            assertNull(controller.error)
        }
    }
}
