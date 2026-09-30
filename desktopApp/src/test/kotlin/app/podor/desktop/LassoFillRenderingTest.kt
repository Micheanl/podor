package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
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
class LassoFillRenderingTest {
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

        suspend fun pointer(
            type: PointerEventType,
            point: Offset,
            pointerType: PointerType = PointerType.Mouse,
        ) =
            withContext(Dispatchers.Main) {
                if (pointerType == PointerType.Mouse) scene.sendPointerEvent(type, position(point))
                else
                    scene.sendPointerEvent(
                        type,
                        listOf(
                            ComposeScenePointer(
                                PointerId(1),
                                position(point),
                                type != PointerEventType.Release,
                                pointerType,
                                1f,
                            )
                        ),
                    )
                render().close()
            }

        suspend fun draw(points: List<Offset>, pointerType: PointerType = PointerType.Mouse) {
            pointer(PointerEventType.Press, points.first(), pointerType)
            for (point in points.drop(1)) pointer(PointerEventType.Move, point, pointerType)
            pointer(PointerEventType.Release, points.first(), pointerType)
        }

        fun alpha(x: Int, y: Int): Float =
            controller.frame.tiles.values
                .firstOrNull { it.x == 0 && it.y == 0 }
                ?.image
                ?.toPixelMap()
                ?.get(x, y)
                ?.alpha ?: 0f

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
        val native = createNativeEngine(128, 96)
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
                    error("Lasso paint must not save artwork automatically")

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
            session.waitFor { controller.document.width == 128 && controller.document.height == 96 }
            withContext(Dispatchers.Main) {
                controller.brush = controller.brush.copy(color = 0xFF8B2942, opacity = 1f)
                controller.tool = Tool.LassoFill
                repeat(3) { session.render().close() }
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
    fun lassoPreviewsItsInteriorThenFillsOnceAndKeepsTheExistingSelection() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(36, 16, 100, 76)) }
            waitFor { controller.document.selection != null }
            val original = withContext(Dispatchers.Main) { controller.document.selection }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            val points = listOf(Offset(20f, 20f), Offset(108f, 20f), Offset(64f, 80f))
            pointer(PointerEventType.Press, points.first())
            for (point in points.drop(1)) pointer(PointerEventType.Move, point)
            withContext(Dispatchers.Main) {
                assertEquals(revision, controller.document.revision)
                assertTrue(controller.frame.tiles.isEmpty())
                val interior = position(Offset(64f, 40f))
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val pixel = pixels[interior.x.toInt(), interior.y.toInt()]
                    assertTrue(
                        pixel.red - pixel.green > 0.03f,
                        "Draft must tint the lasso interior",
                    )
                    val outside = position(Offset(32f, 30f))
                    assertEquals(
                        1f,
                        pixels[outside.x.toInt(), outside.y.toInt()].green,
                        0.005f,
                        "Draft must be clipped by the existing selection",
                    )
                }
                capture("lasso-fill-preview")
            }
            pointer(PointerEventType.Release, points.first())
            waitFor { controller.document.revision > revision }
            withContext(Dispatchers.Main) {
                assertEquals(original, controller.document.selection)
                assertEquals(1f, alpha(64, 40), 0.005f)
                assertEquals(0f, alpha(32, 30), "Existing selection must still clip lasso paint")
                assertEquals(0f, alpha(40, 73), "Lasso fill must respect its contour")
                val pixels = controller.frame.tiles.values.single().image.toPixelMap()
                assertEquals(139f / 255f, pixels[64, 40].red, 0.005f)
                capture("lasso-fill-result")
                controller.command("undo")
            }
            waitFor { controller.document.canRedo && !controller.hasUnsavedChanges }
            withContext(Dispatchers.Main) {
                assertEquals(0f, alpha(64, 40))
                assertEquals(original, controller.document.selection)
                assertFalse(controller.document.canUndo, "One contour must create one undo step")
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun lassoEraseAndPenEraserRemoveOnlyTheirEnclosedRegion() = runBlocking {
        withSession {
            val outline =
                listOf(Offset(20f, 20f), Offset(108f, 20f), Offset(108f, 80f), Offset(20f, 80f))
            draw(outline)
            waitFor { controller.document.canUndo }
            val hole =
                listOf(Offset(45f, 30f), Offset(80f, 30f), Offset(80f, 55f), Offset(45f, 55f))
            for (penEraser in listOf(false, true)) {
                val revision =
                    withContext(Dispatchers.Main) {
                        controller.lassoErase = !penEraser
                        controller.document.revision
                    }
                draw(hole, if (penEraser) PointerType.Eraser else PointerType.Mouse)
                waitFor { controller.document.revision > revision }
                withContext(Dispatchers.Main) {
                    assertEquals(0f, alpha(64, 40), 0.005f)
                    assertEquals(1f, alpha(90, 40), 0.005f)
                    controller.command("undo")
                }
                waitFor { alpha(64, 40) > 0.99f }
            }
            withContext(Dispatchers.Main) { assertNull(controller.error) }
        }
    }

    @Test
    fun combinedSelectionHolesClipTheDraftAndNativeFillInRotatedMirroredViews() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(20, 16, 108, 80)) }
            waitFor { controller.document.selection != null }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Subtract)
                controller.select(Selection(52, 34, 76, 58))
            }
            waitFor {
                controller.document.selection?.combined == true &&
                    controller.selectionOutline?.fillMask?.isNotEmpty() == true
            }
            val original =
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(rotation = 23f, mirrored = true)
                    render().close()
                    controller.document.selection
                }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            val points =
                listOf(Offset(24f, 20f), Offset(104f, 20f), Offset(104f, 76f), Offset(24f, 76f))
            pointer(PointerEventType.Press, points.first())
            for (point in points.drop(1)) pointer(PointerEventType.Move, point)
            withContext(Dispatchers.Main) {
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val painted = position(Offset(40f, 45f))
                    val hole = position(Offset(64f, 45f))
                    assertTrue(pixels[painted.x.toInt(), painted.y.toInt()].green < 0.95f)
                    assertEquals(1f, pixels[hole.x.toInt(), hole.y.toInt()].green, 0.005f)
                }
                capture("lasso-fill-selection-preview")
            }
            pointer(PointerEventType.Release, points.first())
            waitFor { controller.document.revision > revision }
            withContext(Dispatchers.Main) {
                assertEquals(1f, alpha(40, 45), 0.005f)
                assertEquals(0f, alpha(64, 45), 0.005f)
                assertEquals(original, controller.document.selection)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun overlappingLoopsUseEvenOddFillForTheDraftAndCommit() = runBlocking {
        withSession {
            val loop =
                listOf(Offset(30f, 20f), Offset(98f, 20f), Offset(98f, 70f), Offset(30f, 70f))
            pointer(PointerEventType.Press, loop.first())
            for (point in loop.drop(1) + loop + loop.first()) pointer(PointerEventType.Move, point)
            withContext(Dispatchers.Main) {
                val center = position(Offset(64f, 45f))
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    assertEquals(1f, pixels[center.x.toInt(), center.y.toInt()].green, 0.005f)
                }
            }
            pointer(PointerEventType.Release, loop.first())
            delay(80)
            withContext(Dispatchers.Main) {
                assertTrue(controller.frame.tiles.isEmpty())
                assertFalse(controller.document.canUndo)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun escapeToolChangeAndTwoFingerPanDiscardTheDraftWithoutAnUndoStep() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(12, 12, 116, 88)) }
            waitFor { controller.document.selection != null }
            val original = withContext(Dispatchers.Main) { controller.document.selection }
            for (cancel in listOf("escape", "tool", "pan")) {
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.LassoFill
                    controller.fingerDrawing = cancel == "pan"
                    controller.viewport = Viewport()
                    repeat(3) { render().close() }
                }
                val revision = withContext(Dispatchers.Main) { controller.document.revision }
                val pointerType = if (cancel == "pan") PointerType.Touch else PointerType.Mouse
                pointer(PointerEventType.Press, Offset(30f, 25f), pointerType)
                pointer(PointerEventType.Move, Offset(98f, 25f), pointerType)
                pointer(PointerEventType.Move, Offset(64f, 70f), pointerType)
                withContext(Dispatchers.Main) {
                    when (cancel) {
                        "escape" -> {
                            assertTrue(
                                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                            )
                            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                        }
                        "tool" -> controller.tool = Tool.Brush
                        "pan" -> {
                            for (pressed in listOf(true, false)) {
                                scene.sendPointerEvent(
                                    if (pressed) PointerEventType.Press
                                    else PointerEventType.Release,
                                    listOf(64f, 80f).mapIndexed { index, x ->
                                        ComposeScenePointer(
                                            PointerId(index + 1L),
                                            position(Offset(x, 70f)),
                                            pressed,
                                            PointerType.Touch,
                                        )
                                    },
                                )
                                render().close()
                            }
                        }
                    }
                    repeat(3) { render().close() }
                }
                pointer(PointerEventType.Release, Offset(30f, 25f), pointerType)
                delay(80)
                withContext(Dispatchers.Main) {
                    assertEquals(
                        revision,
                        controller.document.revision,
                        "$cancel committed a draft",
                    )
                    assertTrue(controller.frame.tiles.isEmpty())
                    assertFalse(controller.document.canUndo)
                    assertEquals(original, controller.document.selection)
                    assertNull(controller.error)
                }
            }
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                repeat(200) { render().close() }
                assertFalse(scene.hasInvalidations())
            }
        }
    }
}
