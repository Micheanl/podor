package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LayerTransformTest {
    private fun project(mode: LayerBlendMode): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(128, 96)
        fun command(json: String) = engine.call(EngineOperation.COMMAND, json.encodeToByteArray())
        try {
            command("""{"type":"fill","x":0,"y":0,"color":[80,120,200,192],"tolerance":0}""")
            command("""{"type":"add_layer"}""")
            command("""{"type":"select","rect":{"left":20,"top":16,"right":86,"bottom":72}}""")
            command("""{"type":"fill","x":30,"y":30,"color":[190,30,75,150],"tolerance":0}""")
            command("""{"type":"set_blend","id":2,"mode":${Json.encodeToString(mode)}}""")
            command("""{"type":"set_layer","id":2,"name":"Sketch","visible":true,"opacity":0.6}""")
            command("""{"type":"add_layer"}""")
            command("""{"type":"select","rect":{"left":64,"top":0,"right":128,"bottom":96}}""")
            command("""{"type":"fill","x":70,"y":30,"color":[100,170,20,120],"tolerance":0}""")
            command("""{"type":"set_blend","id":3,"mode":"overlay"}""")
            command("""{"type":"select","rect":null}""")
            command("""{"type":"select_layer","id":2}""")
            return engine.call(EngineOperation.SAVE)
        } finally {
            engine.close()
        }
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val full: Boolean,
        val original: ByteArray,
    ) {
        val area = if (full) Size(1042f, 836f) else Size(740f, 560f)
        var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        fun position(point: Offset) =
            controller.viewport.toView(point, area, controller.document) +
                if (full) Offset(0f, 64f) else Offset.Zero

        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        predicate()
                    }
                ) delay(5)
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

        fun key(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
            val handled =
                scene.sendKeyEvent(
                    KeyEvent(
                        key,
                        KeyEventType.KeyDown,
                        isCtrlPressed = ctrl,
                        isShiftPressed = shift,
                    )
                )
            scene.sendKeyEvent(
                KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift)
            )
            render().close()
            assertTrue(handled, "$key was not handled in ${controller.tool}")
        }
    }

    private suspend fun session(
        mode: LayerBlendMode = LayerBlendMode.Normal,
        full: Boolean = false,
        block: suspend Session.(suspend () -> ByteArray) -> Unit,
    ) {
        val original = project(mode)
        var saved: ByteArray? = null
        val files =
            object : ProjectFiles {
                override suspend fun open() = original

                override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
                    saved = bytes
                    return true
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                if (full) ImageComposeScene(1360, 900) { StudioApp(controller) }
                else
                    ImageComposeScene(740, 560) {
                        CanvasWorkspace(controller, Modifier.fillMaxSize())
                    }
            }
        val session = Session(controller, scene, full, original)
        try {
            session.awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.awaitState { controller.hasCanvas && !controller.busy }
            session.block {
                withContext(Dispatchers.Main) {
                    saved = null
                    controller.file(StudioController.FileAction.Save)
                }
                session.awaitState { saved != null && !controller.busy }
                saved!!
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun handlesFollowRotatedMirroredViewsAndApplyOnlyOnEnterWithOneUndo() = runBlocking {
        session(full = true) { save ->
            withContext(Dispatchers.Main) {
                controller.viewport = Viewport(rotation = 23f, mirrored = true)
                key(Key.T, ctrl = true)
            }
            awaitState { controller.layerMove?.transform != null && !controller.busy }
            val preview = withContext(Dispatchers.Main) { controller.layerMove!! }
            val source = assertNotNull(preview.sourceBounds)
            assertEquals(Rect(20f, 16f, 86f, 72f), source)
            val before = withContext(Dispatchers.Main) { controller.document }
            val pixels = withContext(Dispatchers.Main) { controller.frame }
            val start = preview.transform!!.point(source, 1f, 1f)
            pointer(PointerEventType.Press, start)
            pointer(PointerEventType.Move, start + Offset(18f, 15f))
            pointer(PointerEventType.Release, start + Offset(18f, 15f))
            val value =
                withContext(Dispatchers.Main) {
                    assertEquals(before, controller.document)
                    assertSame(pixels, controller.frame)
                    assertFalse(controller.hasUnsavedChanges)
                    assertSame(preview, controller.layerMove)
                    assertTrue(preview.transform!!.width > 66)
                    val resized = preview.transform!!
                    key(Key.DirectionRight)
                    key(Key.DirectionDown, shift = true)
                    assertEquals(resized.dx + 1f, preview.transform!!.dx)
                    assertEquals(resized.dy + 10f, preview.transform!!.dy)
                    preview.transform!!
                }
            for (language in Language.entries) withContext(Dispatchers.Main) {
                controller.updatePreferences(controller.preferences.copy(language = language))
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                repeat(35) { render().close() }
                assertFalse(scene.hasInvalidations())
                Files.createDirectories(Path.of("build/reports/screenshots"))
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(
                            Path.of(
                                "build/reports/screenshots/transform-${language.name.lowercase()}.png"
                            ),
                            it.bytes,
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) { key(Key.Enter) }
            awaitState {
                controller.document.revision == before.revision + 1 &&
                    !controller.busy &&
                    controller.layerMove == null
            }
            val expected = createNativeEngine(1, 1)
            try {
                expected.call(EngineOperation.LOAD, original)
                expected.call(
                    EngineOperation.COMMAND,
                    """{"type":"transform_layer","id":2,"revision":1,"transform":${Json.encodeToString(value)}}"""
                        .encodeToByteArray(),
                )
                assertContentEquals(expected.call(EngineOperation.SAVE), save())
            } finally {
                expected.close()
            }
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState { controller.document.revision == before.revision + 2 && !controller.busy }
            assertContentEquals(original, save())
        }
    }

    @Test
    fun escapeAndSecondTouchDiscardOnlyTheUncommittedTransform() = runBlocking {
        session(full = true) { save ->
            withContext(Dispatchers.Main) { key(Key.T, ctrl = true) }
            awaitState { controller.layerMove?.transform != null && !controller.busy }
            val preview = withContext(Dispatchers.Main) { controller.layerMove!! }
            val initial = assertNotNull(preview.transform)
            val before = withContext(Dispatchers.Main) { controller.document }
            val start = preview.sourceBounds!!.center
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    listOf(
                        ComposeScenePointer(PointerId(1), position(start), true, PointerType.Touch)
                    ),
                )
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(start + Offset(12f, 8f)),
                            true,
                            PointerType.Touch,
                        )
                    ),
                )
                render().close()
                assertEquals(12f, preview.transform!!.dx, 0.01f)
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(start + Offset(12f, 8f)),
                            true,
                            PointerType.Touch,
                        ),
                        ComposeScenePointer(
                            PointerId(2),
                            position(start + Offset(10f, 10f)),
                            true,
                            PointerType.Touch,
                        ),
                    ),
                )
                render().close()
                assertEquals(initial, preview.transform)
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(start),
                            false,
                            PointerType.Touch,
                        ),
                        ComposeScenePointer(
                            PointerId(2),
                            position(start + Offset(10f, 10f)),
                            false,
                            PointerType.Touch,
                        ),
                    ),
                )
                render().close()
            }
            pointer(PointerEventType.Press, start)
            pointer(PointerEventType.Move, start + Offset(20f, 5f))
            withContext(Dispatchers.Main) { key(Key.Escape) }
            pointer(PointerEventType.Release, start + Offset(20f, 5f))
            withContext(Dispatchers.Main) {
                assertNull(controller.layerMove)
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
            }
            assertContentEquals(original, save())
        }
    }

    @Test
    fun toolbarActionsLeaveEnterAndEscapeAvailable() = runBlocking {
        session(full = true) { _ ->
            for (key in listOf(Key.Escape, Key.Enter)) {
                withContext(Dispatchers.Main) { key(Key.T, ctrl = true) }
                awaitState { controller.layerMove?.transform != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(383f, 817f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(383f, 817f))
                    render().close()
                    assertTrue(controller.layerMove!!.transform!!.flipX)
                    key(key)
                }
                awaitState {
                    controller.layerMove == null &&
                        controller.tool == Tool.Brush &&
                        !controller.busy
                }
            }
        }
    }

    @Test
    fun unconfirmedChangesCannotBeSavedExportedOrDiscardedByAnotherTool() = runBlocking {
        session { save ->
            withContext(Dispatchers.Main) { controller.tool = Tool.TransformLayer }
            awaitState { controller.layerMove?.transform != null && !controller.busy }
            withContext(Dispatchers.Main) {
                val preview = controller.layerMove!!
                val before = controller.document
                val changed = preview.transform!!.copy(dx = 12f, angle = 30f)
                controller.previewLayerTransform(changed)
                for (action in
                    listOf<() -> Unit>(
                        { controller.file(StudioController.FileAction.Save) },
                        { controller.export(ExportOptions()) },
                        { controller.command("undo") },
                        { controller.tool = Tool.Brush },
                        { controller.selectPreset(BrushPreset.entries.last()) },
                        { controller.navigate(WorkspaceDestination.Exit) },
                        { controller.home() },
                    )) {
                    action()
                    assertEquals("请先确认或取消图层变换", controller.error)
                    assertEquals(Tool.TransformLayer, controller.tool)
                    assertEquals(changed, preview.transform)
                    assertEquals(before, controller.document)
                    assertFalse(controller.showWorkspace)
                    assertFalse(controller.exitRequested)
                    controller.dismissError()
                }
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.Brush
            }
            assertContentEquals(original, save())
        }
    }

    @Test
    fun transformedPreviewMatchesCommittedPixelsForAllBlendModes() = runBlocking {
        for (mode in LayerBlendMode.entries) session(mode) { _ ->
            withContext(Dispatchers.Main) { controller.tool = Tool.TransformLayer }
            awaitState { controller.layerMove?.transform != null && !controller.busy }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            val preview =
                withContext(Dispatchers.Main) {
                    controller.previewLayerTransform(
                        LayerTransform(66, 56, 5f, -4f, 90f, true, filter = ResampleFilter.Nearest)
                    )
                    render().close()
                    render().use { it.toComposeImageBitmap().toPixelMap() }
                }
            withContext(Dispatchers.Main) { controller.commitLayerTransform() }
            awaitState {
                controller.layerMove == null &&
                    controller.document.revision > revision &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                val committed = render().use { it.toComposeImageBitmap().toPixelMap() }
                for (y in 10..85 step 7) for (x in 10..118 step 7) {
                    val point = position(Offset(x + 0.5f, y + 0.5f))
                    val px = point.x.roundToInt()
                    val py = point.y.roundToInt()
                    if (px !in 0 until 740 || py !in 0 until 560) continue
                    if (abs(x - 30) <= 2 || abs(x - 86) <= 2 || abs(y - 7) <= 2 || abs(y - 73) <= 2)
                        continue
                    val a = preview[px, py]
                    val b = committed[px, py]
                    assertTrue(
                        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) <=
                            5f / 255,
                        "$mode transform preview mismatch at $x,$y: $a / $b",
                    )
                }
            }
        }
    }
}
