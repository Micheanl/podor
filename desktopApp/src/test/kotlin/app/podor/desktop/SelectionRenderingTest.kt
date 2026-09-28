package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SelectionRenderingTest {
    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val full: Boolean,
    ) {
        var frame = 0L
        val view =
            if (full)
                Size(
                    1360f - StudioTheme.inspectorWidth.value - StudioTheme.inspectorMargin.value,
                    836f,
                )
            else Size(680f, 560f)

        fun render() = scene.render(frame++ * 16_666_667L)

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) +
                if (full) Offset(0f, 64f) else Offset.Zero

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) delay(5)
            }

        suspend fun pointer(type: PointerEventType, point: Offset, shift: Boolean = false) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    type,
                    position(point),
                    keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = shift),
                )
                render().close()
            }

        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            repeat(35) { render().close() }
        }
    }

    private suspend fun withSession(
        full: Boolean = false,
        width: Int = 128,
        height: Int = 96,
        block: suspend Session.() -> Unit,
    ) {
        NativeLoader.load()
        val native = createNativeEngine(width, height)
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
                    error("Selection must not save")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                if (full) ImageComposeScene(1360, 900) { StudioApp(controller) }
                else
                    ImageComposeScene(680, 560) {
                        CanvasWorkspace(controller, Modifier.fillMaxSize())
                    }
            }
        val session = Session(controller, scene, full)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor {
                controller.document.width == width &&
                    controller.document.height == height &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Select
                session.render().close()
                session.render().close()
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
    fun denseSelectionUsesCachedMaskAndLeavesHolesTransparent() = runBlocking {
        withSession(width = 512, height = 512) {
            val comb = buildList {
                add(SelectionPoint(0f, 0f))
                add(SelectionPoint(512f, 0f))
                add(SelectionPoint(512f, 512f))
                for (x in 511 downTo 1) {
                    val (first, last) = if (x % 2 == 1) 512f to 1f else 1f to 512f
                    add(SelectionPoint(x.toFloat(), first))
                    add(SelectionPoint(x.toFloat(), last))
                }
                add(SelectionPoint(0f, 1f))
            }
            withContext(Dispatchers.Main) {
                controller.select(Selection(0, 0, 512, 512, SelectionKind.Lasso, comb))
            }
            waitFor {
                controller.document.selection?.kind == SelectionKind.Lasso && !controller.busy
            }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Intersect)
                controller.select(
                    Selection(
                        0,
                        0,
                        512,
                        512,
                        SelectionKind.Lasso,
                        comb.map { SelectionPoint(it.y, it.x) },
                    )
                )
            }
            waitFor {
                controller.document.selection?.combined == true &&
                    controller.selectionOutline?.mask?.isNotEmpty() == true &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                val outline = assertNotNull(controller.selectionOutline)
                assertNull(outline.path)
                assertEquals(1, outline.mask.size)
                val mask = outline.mask.first { it.x == 0 && it.y == 0 }.image.toPixelMap()
                assertEquals(1f, mask[3, 3].alpha)
                assertEquals(0f, mask[2, 3].alpha)
                assertEquals(0f, mask[3, 2].alpha)
                controller.viewport = Viewport(zoom = 8f)
                repeat(3) { render().close() }
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val selected = position(Offset(257.5f, 257.5f))
                    val hole = position(Offset(256.5f, 257.5f))
                    assertTrue(pixels[selected.x.toInt(), selected.y.toInt()].green < 0.95f)
                    assertEquals(1f, pixels[hole.x.toInt(), hole.y.toInt()].green, 0.005f)
                }
                repeat(30) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f + it, 200f))
                    render().close()
                    assertSame(outline, controller.selectionOutline)
                }
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                repeat(35) { render().close() }
                assertFalse(scene.hasInvalidations())
                assertFalse(controller.hasUnsavedChanges)
                assertTrue(controller.frame.tiles.isEmpty())
            }
        }
    }

    @Test
    fun combinedSelectionControlsRetainHolesAndCacheTheirOutline() = runBlocking {
        withSession(full = true) {
            withContext(Dispatchers.Main) { controller.select(Selection(12, 12, 112, 84)) }
            waitFor { controller.document.selection != null && !controller.busy }
            withContext(Dispatchers.Main) {
                click(548f, 815f)
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.createDirectories(Path.of("build/reports/screenshots"))
                        Files.write(
                            Path.of("build/reports/screenshots/selection-mode-menu.png"),
                            it.bytes,
                        )
                    }
                }
                click(600f, 715f)
                assertEquals(SelectionMode.Subtract, controller.selectionMode)
                click(447f, 815f)
            }
            pointer(PointerEventType.Press, Offset(48f, 34f))
            pointer(PointerEventType.Move, Offset(80f, 62f))
            pointer(PointerEventType.Release, Offset(80f, 62f))
            waitFor {
                controller.document.selection?.combined == true &&
                    controller.selectionOutline != null &&
                    !controller.busy
            }
            val outline = withContext(Dispatchers.Main) { controller.selectionOutline }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            withContext(Dispatchers.Main) {
                assertFalse(controller.hasUnsavedChanges)
                controller.brush = controller.brush.copy(color = 0xFF8B2942, opacity = 1f)
                controller.fill(Offset(20f, 20f))
            }
            waitFor { controller.document.revision > revision && !controller.busy }
            withContext(Dispatchers.Main) {
                val frame = controller.frame
                val pixels = frame.tiles.values.single().image.toPixelMap()
                assertEquals(139f / 255f, pixels[20, 20].red, 0.005f)
                assertEquals(1f, pixels[64, 48].red, 0.005f)
                assertEquals(1f, pixels[4, 4].red, 0.005f)
                repeat(40) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(300f + it, 400f))
                    render().close()
                    assertSame(outline, controller.selectionOutline)
                    assertSame(frame, controller.frame)
                }
                controller.viewport = Viewport(rotation = 18f, mirrored = true)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                repeat(35) { render().close() }
                assertFalse(scene.hasInvalidations())
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(
                            Path.of("build/reports/screenshots/selection-combined.png"),
                            it.bytes,
                        )
                    }
                }
                controller.changeSelectionMode(SelectionMode.Add)
            }
            val original = withContext(Dispatchers.Main) { controller.document.selection }
            pointer(PointerEventType.Press, Offset(8f, 8f))
            pointer(PointerEventType.Release, Offset(8f, 8f))
            delay(50)
            withContext(Dispatchers.Main) {
                assertEquals(original, controller.document.selection)
                assertTrue(
                    scene.sendKeyEvent(
                        KeyEvent(
                            Key.I,
                            KeyEventType.KeyDown,
                            isCtrlPressed = true,
                            isShiftPressed = true,
                        )
                    )
                )
                scene.sendKeyEvent(
                    KeyEvent(Key.I, KeyEventType.KeyUp, isCtrlPressed = true, isShiftPressed = true)
                )
            }
            waitFor { controller.document.selection?.id != original?.id && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Intersect)
                controller.select(Selection(56, 40, 72, 56))
            }
            waitFor { controller.document.selection?.left == 56 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Subtract)
                controller.select(Selection(56, 40, 72, 56))
            }
            waitFor { controller.document.selection?.empty == true && !controller.busy }
            withContext(Dispatchers.Main) {
                assertNull(controller.selectionOutline)
                assertEquals("选区为空", controller.status)
                click(649f, 815f)
            }
            waitFor { controller.document.selection == null }
            withContext(Dispatchers.Main) {
                assertEquals(SelectionMode.Replace, controller.selectionMode)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun emptySelectionDoesNotPreviewOrCommitAGradient() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(10, 10, 40, 40)) }
            waitFor { controller.document.selection != null }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Subtract)
                controller.select(Selection(10, 10, 40, 40))
            }
            waitFor { controller.document.selection?.empty == true && !controller.busy }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Gradient
                render().close()
            }
            waitFor { controller.gradientPreview != null && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.gradient = controller.gradient.copy(from = 0xFF8B2942, to = 0xFF8B2942)
                controller.previewGradient(GradientLine(Offset(20f, 20f), Offset(90f, 70f)))
                repeat(3) { render().close() }
                render().use { image ->
                    val point = position(Offset(0.5f, 0.5f))
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    assertEquals(1f, pixels[point.x.toInt(), point.y.toInt()].green, 0.005f)
                }
                controller.commitGradient()
            }
            waitFor { controller.gradientPreview == null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(revision, controller.document.revision)
                assertFalse(controller.hasUnsavedChanges)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun allSelectionShapesFollowRotatedMirroredViewsAndClipNativeFill() = runBlocking {
        for (kind in SelectionKind.entries) withSession {
            withContext(Dispatchers.Main) {
                controller.selectionKind = kind
                controller.viewport = Viewport(rotation = 23f, mirrored = true)
                render().close()
            }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            val points =
                if (kind == SelectionKind.Lasso)
                    listOf(Offset(24.25f, 20.25f), Offset(102.75f, 20.25f), Offset(63.5f, 74.75f))
                else listOf(Offset(24.25f, 20.25f), Offset(102.75f, 74.75f))
            pointer(PointerEventType.Press, points.first())
            for (point in points.drop(1)) pointer(PointerEventType.Move, point)
            withContext(Dispatchers.Main) {
                assertNull(controller.document.selection)
                assertEquals(revision, controller.document.revision)
            }
            pointer(PointerEventType.Release, points.last())
            waitFor { controller.document.selection != null && !controller.busy }
            withContext(Dispatchers.Main) {
                val selection = assertNotNull(controller.document.selection)
                assertEquals(kind, selection.kind)
                assertEquals(
                    listOf(24, 20, 103, 75),
                    listOf(selection.left, selection.top, selection.right, selection.bottom),
                )
                assertFalse(controller.hasUnsavedChanges)
                controller.brush = controller.brush.copy(color = 0xFF8B2942, opacity = 1f)
                controller.fill(Offset(60f, 40f))
            }
            waitFor {
                controller.document.revision > revision && controller.frame.tiles.isNotEmpty()
            }
            withContext(Dispatchers.Main) {
                val pixels = controller.frame.tiles.values.single().image.toPixelMap()
                assertEquals(139f / 255f, pixels[60, 40].red, 0.005f)
                assertEquals(1f, pixels[10, 10].red, 0.005f)
                if (kind != SelectionKind.Rectangle) assertEquals(1f, pixels[25, 73].red, 0.005f)
                controller.command("undo")
            }
            waitFor { !controller.hasUnsavedChanges && controller.document.canRedo }
            withContext(Dispatchers.Main) {
                assertEquals(kind, controller.document.selection?.kind)
            }
        }
    }

    @Test
    fun dockModesEscapeAndTwoFingerGesturePreserveExistingSelection() = runBlocking {
        withSession(full = true) {
            withContext(Dispatchers.Main) { controller.select(Offset(20f, 20f), Offset(100f, 70f)) }
            waitFor { controller.document.selection != null }
            val original = withContext(Dispatchers.Main) { controller.document.selection }
            for (language in Language.entries) {
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(controller.preferences.copy(language = language))
                    repeat(4) { render().close() }
                    click(447f, 815f)
                    assertEquals(SelectionKind.Ellipse, controller.selectionKind)
                }
                pointer(PointerEventType.Press, Offset(35f, 25f))
                pointer(PointerEventType.Move, Offset(75f, 45f), shift = true)
                pointer(PointerEventType.Release, Offset(75f, 45f), shift = true)
                waitFor { controller.document.selection?.kind == SelectionKind.Ellipse }
                withContext(Dispatchers.Main) {
                    val selected = controller.document.selection!!
                    assertEquals(selected.right - selected.left, selected.bottom - selected.top)
                    Files.createDirectories(Path.of("build/reports/screenshots"))
                    scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                    repeat(35) { render().close() }
                    assertFalse(scene.hasInvalidations())
                    render().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(
                                Path.of(
                                    "build/reports/screenshots/selection-${language.name.lowercase()}.png"
                                ),
                                it.bytes,
                            )
                        }
                    }
                    controller.select(original)
                }
                waitFor { controller.document.selection == original }
                withContext(Dispatchers.Main) {
                    click(495f, 815f)
                    assertEquals(SelectionKind.Lasso, controller.selectionKind)
                }
                pointer(PointerEventType.Press, Offset(35f, 25f))
                pointer(PointerEventType.Move, Offset(70f, 60f))
                withContext(Dispatchers.Main) {
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
                    scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                    scene.sendPointerEvent(PointerEventType.Release, position(Offset(80f, 35f)))
                    render().close()
                }
                delay(80)
                withContext(Dispatchers.Main) {
                    assertEquals(original, controller.document.selection)
                    fun touches(type: PointerEventType, count: Int, pressed: Boolean) {
                        scene.sendPointerEvent(
                            type,
                            (1..count).map { index ->
                                ComposeScenePointer(
                                    PointerId(index.toLong()),
                                    position(Offset(40f + index * 10f, 40f)),
                                    pressed,
                                    PointerType.Touch,
                                )
                            },
                        )
                        render().close()
                    }
                    touches(PointerEventType.Press, 1, true)
                    touches(PointerEventType.Press, 2, true)
                    touches(PointerEventType.Release, 2, false)
                }
                delay(80)
                withContext(Dispatchers.Main) {
                    assertEquals(original, controller.document.selection)
                }
            }
            withContext(Dispatchers.Main) { click(649f, 815f) }
            waitFor { controller.document.selection == null }
            withContext(Dispatchers.Main) {
                assertFalse(controller.hasUnsavedChanges)
                assertNull(controller.error)
            }
        }
    }
}
