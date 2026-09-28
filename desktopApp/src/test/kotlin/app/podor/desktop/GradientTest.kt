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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class GradientTest {
    private fun project(mode: LayerBlendMode, locked: Boolean): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(256, 192)
        fun cmd(value: String) = engine.call(EngineOperation.COMMAND, value.encodeToByteArray())
        try {
            cmd("""{"type":"fill","x":0,"y":0,"color":[60,100,170,255],"tolerance":0}""")
            cmd("""{"type":"add_layer"}""")
            cmd("""{"type":"select","rect":{"left":30,"top":25,"right":225,"bottom":180}}""")
            cmd("""{"type":"fill","x":50,"y":50,"color":[180,90,30,130],"tolerance":0}""")
            cmd("""{"type":"set_blend","id":2,"mode":${Json.encodeToString(mode)}}""")
            cmd("""{"type":"set_layer","id":2,"name":"Color","visible":true,"opacity":0.65}""")
            cmd("""{"type":"set_protection","id":2,"alpha_locked":$locked}""")
            cmd("""{"type":"add_layer"}""")
            cmd("""{"type":"select","rect":{"left":160,"top":0,"right":256,"bottom":192}}""")
            cmd("""{"type":"fill","x":170,"y":80,"color":[40,170,100,120],"tolerance":0}""")
            cmd("""{"type":"set_blend","id":3,"mode":"overlay"}""")
            cmd("""{"type":"select_layer","id":2}""")
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
        val view = if (full) Size(1042f, 836f) else Size(740f, 560f)
        var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) +
                if (full) Offset(0f, 64f) else Offset.Zero

        suspend fun awaitState(check: () -> Boolean) =
            withTimeout(15_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        check()
                    }
                ) delay(5)
            }

        fun key(key: Key, shift: Boolean = false) {
            assertTrue(
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            )
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            render().close()
        }
    }

    private suspend fun session(
        mode: LayerBlendMode = LayerBlendMode.Normal,
        locked: Boolean = false,
        full: Boolean = false,
        block: suspend Session.(suspend () -> ByteArray) -> Unit,
    ) {
        val original = project(mode, locked)
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
    fun previewMatchesBothShapesAcrossBlendsSelectionsAndAlphaLock() = runBlocking {
        for (mode in LayerBlendMode.entries) for (shape in GradientShape.entries) session(
            mode,
            locked = shape == GradientShape.Radial,
        ) { _ ->
            withContext(Dispatchers.Main) {
                controller.select(Selection(10, 15, 242, 181, kind = SelectionKind.Ellipse))
            }
            awaitState { controller.document.selection != null && !controller.busy }
            withContext(Dispatchers.Main) { controller.tool = Tool.Gradient }
            awaitState { controller.gradientPreview != null && !controller.busy }
            val before = withContext(Dispatchers.Main) { controller.document.revision }
            val preview =
                withContext(Dispatchers.Main) {
                    controller.gradient =
                        GradientSettings(
                            shape,
                            from = 0xFFBA3659,
                            to = 0xFF39ADC7,
                            opacity = 0.7f,
                            transparent = shape == GradientShape.Radial,
                            reversed = mode.ordinal % 2 == 1,
                        )
                    controller.previewGradient(GradientLine(Offset(50f, 60f), Offset(190f, 140f)))
                    render().close()
                    render().use { it.toComposeImageBitmap().toPixelMap() }
                }
            withContext(Dispatchers.Main) { controller.commitGradient() }
            awaitState {
                controller.gradientPreview == null &&
                    controller.document.revision > before &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                val committed = render().use { it.toComposeImageBitmap().toPixelMap() }
                for (y in 5..187 step 11) for (x in 5..250 step 11) {
                    if (abs((y - 60f) - (x - 50f) * 80f / 140f) < 5) continue
                    val point = position(Offset(x + 0.5f, y + 0.5f))
                    val a = preview[point.x.roundToInt(), point.y.roundToInt()]
                    val b = committed[point.x.roundToInt(), point.y.roundToInt()]
                    assertTrue(
                        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) <
                            6f / 255,
                        "$mode $shape at $x,$y: $a vs $b",
                    )
                }
            }
        }
    }

    @Test
    fun draggingAndEditingEndpointsStayProvisionalUntilEnterAndUndoRestoresOriginal() =
        runBlocking {
            session(full = true) { save ->
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(rotation = 17f, mirrored = true)
                    key(Key.G, shift = true)
                }
                awaitState { controller.gradientPreview != null && !controller.busy }
                val originalFrame = withContext(Dispatchers.Main) { controller.frame }
                val before = withContext(Dispatchers.Main) { controller.document }
                withContext(Dispatchers.Main) {
                    val start = Offset(40f, 60f)
                    val end = Offset(200f, 125f)
                    scene.sendPointerEvent(PointerEventType.Press, position(start))
                    scene.sendPointerEvent(PointerEventType.Move, position(end))
                    scene.sendPointerEvent(PointerEventType.Release, position(end))
                    render().close()
                    assertEquals(start.x, controller.gradientPreview!!.line!!.start.x, 0.01f)
                    assertEquals(end.x, controller.gradientPreview!!.line!!.end.x, 0.01f)
                    assertSame(originalFrame, controller.frame)
                    assertEquals(before, controller.document)
                    controller.file(StudioController.FileAction.Save)
                    assertEquals("请先确认或取消渐变", controller.error)
                    controller.dismissError()
                    controller.gradient =
                        controller.gradient.copy(from = 0xFFD9677E, to = 0xFF5B9CC3)
                    scene.sendPointerEvent(PointerEventType.Press, position(end))
                    scene.sendPointerEvent(PointerEventType.Move, position(Offset(180f, 150f)))
                    scene.sendPointerEvent(PointerEventType.Release, position(Offset(180f, 150f)))
                    render().close()
                    assertEquals(180f, controller.gradientPreview!!.line!!.end.x, 0.01f)
                    repeat(35) { render().close() }
                    Files.createDirectories(Path.of("build/reports/screenshots"))
                    render().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(Path.of("build/reports/screenshots/gradient.png"), it.bytes)
                        }
                    }
                    val confirmed = controller.gradient
                    key(Key.Enter)
                    controller.gradient = confirmed.copy(from = 0xFFFFFFFF)
                    controller.commitGradient()
                    assertEquals(confirmed, controller.gradient)
                }
                awaitState {
                    !controller.busy &&
                        controller.gradientPreview == null &&
                        controller.document.revision == before.revision + 1
                }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState {
                    controller.document.revision == before.revision + 2 && !controller.busy
                }
                assertContentEquals(original, save())
                withContext(Dispatchers.Main) { key(Key.G, shift = true) }
                awaitState { controller.gradientPreview != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    controller.previewGradient(GradientLine(Offset.Zero, Offset(200f, 100f)))
                    key(Key.Escape)
                }
                assertContentEquals(original, save())
            }
        }

    @Test
    fun colorDockEditsBothEndpointsWithoutChangingTheBrush() = runBlocking {
        session(full = true) { _ ->
            withContext(Dispatchers.Main) { key(Key.G, shift = true) }
            awaitState { controller.gradientPreview != null && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.previewGradient(GradientLine(Offset(50f, 60f), Offset(190f, 140f)))
                val brush = controller.brush
                fun click(x: Float, y: Float) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                    scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                    repeat(35) { render().close() }
                }
                click(356f, 819f)
                assertTrue(controller.gradientEditingStart)
                click(1200f, 400f)
                val from = controller.gradient.from
                assertNotEquals(brush.color, from)
                click(444f, 819f)
                assertFalse(controller.gradientEditingStart)
                click(1160f, 430f)
                assertEquals(from, controller.gradient.from)
                assertNotEquals(StudioDefaults.gradientEndColor, controller.gradient.to)
                assertEquals(brush, controller.brush)
                assertFalse(scene.hasInvalidations())
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(
                            Path.of("build/reports/screenshots/gradient-colors.png"),
                            it.bytes,
                        )
                    }
                }
                key(Key.Escape)
            }
        }
    }

    @Test
    fun changingSelectionBeforeDraggingRefreshesTheMaskWithoutChangingPixels() = runBlocking {
        session { _ ->
            withContext(Dispatchers.Main) {
                controller.select(Selection(10, 10, 245, 180, kind = SelectionKind.Ellipse))
            }
            awaitState { controller.document.selection != null }
            withContext(Dispatchers.Main) { controller.tool = Tool.Gradient }
            awaitState {
                controller.gradientPreview?.mask?.isNotEmpty() == true && !controller.busy
            }
            val frame = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) { controller.clearSelection() }
            awaitState {
                controller.gradientPreview != null &&
                    controller.gradientPreview!!.mask.isEmpty() &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                assertNull(controller.gradientPreview!!.selection)
                assertSame(frame, controller.frame)
            }
        }
    }

    @Test
    fun secondTouchAndEscapeDoNotApplyADraftOrStartAPaintStroke() = runBlocking {
        session(full = true) { save ->
            withContext(Dispatchers.Main) { key(Key.G, shift = true) }
            awaitState { controller.gradientPreview != null && !controller.busy }
            val before = withContext(Dispatchers.Main) { controller.document }
            withContext(Dispatchers.Main) {
                val line = GradientLine(Offset(50f, 60f), Offset(180f, 130f))
                controller.previewGradient(line)
                render().close()
                fun touch(id: Long, point: Offset, down: Boolean) =
                    ComposeScenePointer(PointerId(id), position(point), down, PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Press, listOf(touch(1, line.end, true)))
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    listOf(touch(1, Offset(200f, 150f), true)),
                )
                render().close()
                assertEquals(200f, controller.gradientPreview!!.line!!.end.x, 0.01f)
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    listOf(touch(1, Offset(200f, 150f), true), touch(2, Offset(120f, 120f), true)),
                )
                render().close()
                assertEquals(line, controller.gradientPreview!!.line)
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    listOf(
                        touch(1, Offset(200f, 150f), false),
                        touch(2, Offset(120f, 120f), false),
                    ),
                )
                render().close()
                scene.sendPointerEvent(PointerEventType.Press, position(line.end))
                scene.sendPointerEvent(PointerEventType.Move, position(Offset(210f, 145f)))
                key(Key.Escape)
                scene.sendPointerEvent(PointerEventType.Release, position(Offset(210f, 145f)))
                render().close()
                assertNull(controller.gradientPreview)
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
            }
            assertContentEquals(original, save())
        }
    }
}
