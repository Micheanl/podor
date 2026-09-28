package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

    private suspend fun withSession(full: Boolean = false, block: suspend Session.() -> Unit) {
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
            session.waitFor { controller.document.width == 128 && !controller.busy }
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
                    click(495f, 815f)
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
                    click(543f, 815f)
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
            withContext(Dispatchers.Main) { click(601f, 815f) }
            waitFor { controller.document.selection == null }
            withContext(Dispatchers.Main) {
                assertFalse(controller.hasUnsavedChanges)
                assertNull(controller.error)
            }
        }
    }
}
