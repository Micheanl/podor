package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.CanvasWorkspace
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LayerMoveTest {
    private val view = Size(680f, 560f)

    private fun project(mode: LayerBlendMode): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(128, 96)
        fun command(json: String) = engine.call(EngineOperation.COMMAND, json.encodeToByteArray())
        try {
            command("""{"type":"fill","x":0,"y":0,"color":[80,120,200,192],"tolerance":0}""")
            command("""{"type":"add_layer"}""")
            command("""{"type":"select","rect":{"left":20,"top":15,"right":86,"bottom":72}}""")
            command("""{"type":"fill","x":30,"y":30,"color":[190,30,75,150],"tolerance":0}""")
            command("""{"type":"set_blend","id":2,"mode":${Json.encodeToString(mode)}}""")
            command("""{"type":"set_layer","id":2,"name":"移动","visible":true,"opacity":0.6}""")
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

    private class MemoryFiles(val project: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null

        override suspend fun open() = project

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved = bytes
            return true
        }
    }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
        val fullStudio: Boolean,
    ) {
        var frame = 0L

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

        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        predicate()
                    }
                ) delay(10)
            }

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                val bounds = if (fullStudio) canvasBounds() else null
                val area = bounds?.size ?: view
                val origin = bounds?.topLeft ?: Offset.Zero
                scene.sendPointerEvent(
                    type,
                    controller.viewport.toView(point, area, controller.document) + origin,
                )
                render().close()
            }

        suspend fun save(): ByteArray {
            withContext(Dispatchers.Main) {
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            awaitState { files.saved != null && !controller.busy }
            return files.saved!!
        }
    }

    private suspend fun withSession(
        mode: LayerBlendMode,
        artwork: ByteArray = project(mode),
        fullStudio: Boolean = false,
        block: suspend Session.() -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files = MemoryFiles(artwork)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                if (fullStudio) ImageComposeScene(1360, 900) { StudioApp(controller) }
                else
                    ImageComposeScene(740, 560) {
                        CanvasWorkspace(controller, Modifier.fillMaxSize(), endInset = 60.dp)
                    }
            }
        val session = Session(controller, scene, files, fullStudio)
        try {
            session.awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.awaitState { controller.document.revision > 0 && !controller.busy }
            withContext(Dispatchers.Main) { controller.tool = Tool.MoveLayer }
            session.awaitState { controller.layerMove != null && !controller.busy }
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
    fun selectedRegionCanBeDraggedWithSelectionToolAndUndone() = runBlocking {
        withSession(LayerBlendMode.Normal) {
            val before = save()
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Select
                controller.command("select") {
                    put(
                        "rect",
                        kotlinx.serialization.json.buildJsonObject {
                            put("left", kotlinx.serialization.json.JsonPrimitive(24))
                            put("top", kotlinx.serialization.json.JsonPrimitive(20))
                            put("right", kotlinx.serialization.json.JsonPrimitive(50))
                            put("bottom", kotlinx.serialization.json.JsonPrimitive(50))
                        },
                    )
                }
            }
            awaitState {
                controller.document.selection != null &&
                    controller.layerMove?.selection != null &&
                    !controller.busy
            }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            pointer(PointerEventType.Press, Offset(32f, 30f))
            pointer(PointerEventType.Move, Offset(58f, 40f))
            withContext(Dispatchers.Main) {
                assertEquals(IntOffset(26, 10), controller.layerMove?.offset)
                assertEquals(revision, controller.document.revision)
            }
            pointer(PointerEventType.Release, Offset(58f, 40f))
            awaitState { controller.document.revision > revision && !controller.busy }
            assertFalse(before.contentEquals(save()))
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState { controller.document.revision > revision + 1 && !controller.busy }
            assertContentEquals(before, save())
        }
    }

    @Test
    fun moveShortcutAndEscapeWorkDuringAnActiveDrag() = runBlocking {
        withSession(LayerBlendMode.Normal, fullStudio = true) {
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Brush
                controller.cancelLayerMove(exit = true)
                render().close()
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.V, KeyEventType.KeyDown)))
                scene.sendKeyEvent(KeyEvent(Key.V, KeyEventType.KeyUp))
            }
            awaitState { controller.tool == Tool.MoveLayer && controller.layerMove != null }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            pointer(PointerEventType.Press, Offset(40f, 35f))
            pointer(PointerEventType.Move, Offset(55f, 45f))
            withContext(Dispatchers.Main) {
                assertEquals(IntOffset(15, 10), controller.layerMove!!.offset)
                render().use { image ->
                    Files.createDirectories(Path.of("build/reports/screenshots"))
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(
                            Path.of("build/reports/screenshots/layer-move-workspace.png"),
                            it.bytes,
                        )
                    }
                }
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                assertEquals(Tool.Brush, controller.tool)
                assertNull(controller.layerMove)
            }
            pointer(PointerEventType.Release, Offset(55f, 45f))
            assertTrue(files.project.contentEquals(save()))
            withContext(Dispatchers.Main) {
                assertEquals(revision, controller.document.revision)
                assertFalse(controller.hasUnsavedChanges)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun dragPreviewMatchesCommittedPixelsForAllBlendModesAndRotatedViews() = runBlocking {
        for (mode in LayerBlendMode.entries) withSession(mode) {
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(
                        rotation = if (mode.ordinal % 2 == 0) 17f else 0f,
                        mirrored = mode.ordinal % 3 == 0,
                    )
            }
            val initial = withContext(Dispatchers.Main) { controller.frame }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            pointer(PointerEventType.Press, Offset(40f, 35f))
            pointer(PointerEventType.Move, Offset(59f, 26f))
            val preview =
                withContext(Dispatchers.Main) {
                    repeat(3) { render().close() }
                    assertEquals(IntOffset(19, -9), controller.layerMove!!.offset)
                    assertEquals(revision, controller.document.revision)
                    assertSame(initial, controller.frame)
                    render().use { image ->
                        if (mode == LayerBlendMode.Normal) {
                            Files.createDirectories(Path.of("build/reports/screenshots"))
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(
                                    Path.of("build/reports/screenshots/layer-move-preview.png"),
                                    it.bytes,
                                )
                            }
                        }
                        image.toComposeImageBitmap().toPixelMap()
                    }
                }
            pointer(PointerEventType.Release, Offset(59f, 26f))
            awaitState { controller.document.revision == revision + 1 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Hand
                controller.cancelLayerMove(exit = true)
            }
            withContext(Dispatchers.Main) {
                repeat(3) { render().close() }
                render().use { image ->
                    val committed = image.toComposeImageBitmap().toPixelMap()
                    for (y in 4..90 step 3) for (x in 4..122 step 3) {
                        val p =
                            controller.viewport.toView(
                                Offset(x + 0.5f, y + 0.5f),
                                view,
                                controller.document,
                            )
                        val px = p.x.roundToInt()
                        val py = p.y.roundToInt()
                        if (px !in 0 until 740 || py !in 0 until 560) continue
                        val before = preview[px, py]
                        val after = committed[px, py]
                        assertTrue(
                            maxOf(
                                abs(before.red - after.red),
                                abs(before.green - after.green),
                                abs(before.blue - after.blue),
                            ) <= 5f / 255,
                            "$mode preview mismatch at $x,$y: $before / $after",
                        )
                    }
                }
            }
            val expected = createNativeEngine(1, 1)
            try {
                expected.call(EngineOperation.LOAD, files.project)
                expected.call(
                    EngineOperation.COMMAND,
                    """{"type":"translate_layer","id":2,"dx":19,"dy":-9}""".encodeToByteArray(),
                )
                assertTrue(
                    expected.call(EngineOperation.SAVE).contentEquals(save()),
                    "Move did not commit exact document coordinates",
                )
            } finally {
                expected.close()
            }
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState { controller.document.revision == revision + 2 && !controller.busy }
            assertTrue(files.project.contentEquals(save()), "Undo did not restore original pixels")
        }
    }

    @Test
    fun largeLayerPreviewKeepsPixelBuffersOnTheHardwareRenderer() = runBlocking {
        assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
        NativeLoader.load()
        val engine = createNativeEngine(4096, 4096)
        val artwork =
            try {
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"fill","x":0,"y":0,"color":[137,58,85,255],"tolerance":0}"""
                        .encodeToByteArray(),
                )
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"duplicate_layer","id":1}""".encodeToByteArray(),
                )
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"set_layer","id":2,"name":"移动","visible":true,"opacity":0.6}"""
                        .encodeToByteArray(),
                )
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
            }
        withSession(LayerBlendMode.Normal, artwork) {
            val snapshot = withContext(Dispatchers.Main) { controller.layerMove!! }
            val original = withContext(Dispatchers.Main) { controller.frame }
            val document = withContext(Dispatchers.Main) { controller.document }
            assertEquals(2048, snapshot.layers.sumOf { it.tiles.size })
            val window =
                withContext(Dispatchers.Main) {
                    ComposeWindow().apply {
                        isUndecorated = true
                        focusableWindowState = false
                        setBounds(-3000, -2000, 920, 720)
                        setContent { CanvasWorkspace(controller, Modifier.fillMaxSize()) }
                        isVisible = true
                    }
                }
            try {
                repeat(90) { index ->
                    withContext(Dispatchers.Main) {
                        controller.previewLayerMove(IntOffset(index * 8 - 360, 180 - index * 4))
                        if (index == 45)
                            controller.viewport =
                                Viewport(zoom = 3f, rotation = 17f, mirrored = true)
                    }
                    delay(16)
                    withContext(Dispatchers.Main) { window.renderImmediately() }
                }
                withContext(Dispatchers.Main) {
                    assertSame(snapshot, controller.layerMove)
                    assertSame(original, controller.frame)
                    assertEquals(document, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                    val api = window.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    val report = Path.of("build/reports/layer-move-gpu.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api\n4096 x 4096, 2 layers, 2048 tile images, 90 translations. Layer image buffers, composite frame and document revision retained. No full-app frame-rate claim.\n",
                    )
                    controller.cancelLayerMove(exit = true)
                    controller.tool = Tool.Hand
                }
                assertTrue(artwork.contentEquals(save()))
            } finally {
                withContext(Dispatchers.Main) { window.dispose() }
            }
        }
    }

    @Test
    fun repeatedPreviewAndCancellationDoNotRasterizeOrChangeHistory() = runBlocking {
        withSession(LayerBlendMode.Multiply) {
            val initial = withContext(Dispatchers.Main) { controller.frame }
            val snapshot = withContext(Dispatchers.Main) { controller.layerMove!! }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            pointer(PointerEventType.Press, Offset(40f, 35f))
            repeat(90) { index ->
                pointer(PointerEventType.Move, Offset(40f + index % 40, 35f - index % 12))
            }
            withContext(Dispatchers.Main) {
                assertSame(initial, controller.frame)
                assertSame(snapshot, controller.layerMove)
                assertEquals(revision, controller.document.revision)
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.Brush
            }
            pointer(PointerEventType.Release, Offset(90f, 45f))
            assertTrue(files.project.contentEquals(save()), "Cancelled preview changed pixels")
            withContext(Dispatchers.Main) {
                controller.command("set_protection") {
                    put("id", kotlinx.serialization.json.JsonPrimitive(2))
                    put("locked", kotlinx.serialization.json.JsonPrimitive(true))
                }
            }
            awaitState { controller.document.layers[1].locked }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.MoveLayer
                render().close()
            }
            delay(50)
            withContext(Dispatchers.Main) {
                render().close()
                assertNull(controller.layerMove)
            }
        }
    }
}
