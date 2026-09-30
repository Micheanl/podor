package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.AdjustmentKind
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LayerReorderTest {
    private class FilesInMemory(val project: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null

        override suspend fun open() = project

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved = bytes
            return true
        }
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: FilesInMemory,
    ) {
        var frame = 0L

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        fun layerListBounds() =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .single { it.config.contains(SemanticsActions.ScrollToIndex) }
                .boundsInWindow

        suspend fun render(count: Int = 1) =
            withContext(Dispatchers.Main) {
                repeat(count) {
                    scene.render(frame++ * 16_666_667L).close()
                    yield()
                }
            }

        suspend fun pointer(type: PointerEventType, x: Float, y: Float) {
            withContext(Dispatchers.Main) { scene.sendPointerEvent(type, Offset(x, y)) }
            render()
        }

        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) {
                    render()
                    delay(10)
                }
            }

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }

        suspend fun save(): ByteArray {
            withContext(Dispatchers.Main) {
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            awaitState { files.saved != null && !controller.busy }
            render()
            return files.saved!!
        }
    }

    private suspend fun session(count: Int, content: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(64, 64)
        val project =
            try {
                for (id in 1..count) {
                    if (id > 1)
                        native.call(
                            EngineOperation.COMMAND,
                            """{"type":"add_layer"}""".encodeToByteArray(),
                        )
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[${id * 7},50,100,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                }
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files = FilesInMemory(project)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        var scene: ImageComposeScene? = null
        suspend fun awaitReady(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
            }
        try {
            awaitReady { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            awaitReady {
                controller.document.layers.size == count &&
                    !controller.busy &&
                    controller.previews.revision == controller.document.revision
            }
            withContext(Dispatchers.Main) {
                scene =
                    ImageComposeScene(900, 640) {
                        PodorTheme {
                            Row {
                                CanvasWorkspace(controller, Modifier.weight(1f).fillMaxHeight())
                                Surface(
                                    Modifier.width(300.dp).fillMaxHeight(),
                                    color = StudioTheme.panel,
                                ) {
                                    LayerControls(controller)
                                }
                            }
                        }
                    }
            }
            val session = Session(controller, scene!!, files)
            session.render(40)
            session.content()
        } finally {
            withContext(Dispatchers.Main) {
                scene?.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun holdLayerNameThenDragCommitsOnceAndCanUndo() =
        runBlocking<Unit> {
            session(4) {
                val revision = controller.document.revision
                pointer(PointerEventType.Press, 750f, 84f)
                delay(650)
                render(12)
                pointer(PointerEventType.Move, 750f, 160f)
                pointer(PointerEventType.Move, 750f, 325f)
                assertEquals(revision, controller.document.revision)
                pointer(PointerEventType.Release, 750f, 325f)
                awaitState { controller.document.revision > revision }
                assertEquals(listOf(4, 1, 2, 3), controller.document.layers.map { it.id })
                assertEquals(revision + 1, controller.document.revision)
                command("undo")
                awaitState { controller.document.layers.map { it.id } == listOf(1, 2, 3, 4) }
            }
        }

    @Test
    fun returningFromLayerBlendKeepsTheScrolledLayerList() =
        runBlocking<Unit> {
            session(20) {
                fun rowPixels() =
                    scene.render(frame++ * 16_666_667L).use { image ->
                        val pixels = image.toComposeImageBitmap().toPixelMap()
                        (90..440 step 32).map { y -> pixels[630, y] }
                    }
                val top = withContext(Dispatchers.Main) { rowPixels() }
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        Offset(750f, 240f),
                        scrollDelta = Offset(0f, 12f),
                    )
                    scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                }
                render(40)
                val scrolled = withContext(Dispatchers.Main) { rowPixels() }
                assertNotEquals(top, scrolled)
                withContext(Dispatchers.Main) {
                    controller.prepareAdjustment(AdjustmentKind.LayerBlend)
                }
                awaitState {
                    controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
                }
                render(40)
                withContext(Dispatchers.Main) { controller.cancelAdjustment() }
                render(40)
                withContext(Dispatchers.Main) {
                    assertEquals(
                        scrolled,
                        rowPixels(),
                        "Returning from blending moved the layer list",
                    )
                    assertFalse(controller.hasUnsavedChanges)
                    assertFalse(controller.document.canUndo)
                    assertNull(controller.error)
                }
            }
        }

    @Test
    fun dragCommitsOnceUpdatesCanvasAndSurvivesUndoAndSave() =
        runBlocking<Unit> {
            session(4) {
                val before = save()
                val revision = controller.document.revision
                val pixels = controller.frame
                val previews = controller.previews.images
                pointer(PointerEventType.Press, 630f, 84f)
                pointer(PointerEventType.Move, 630f, 140f)
                pointer(PointerEventType.Move, 630f, 325f)
                render(20)
                withContext(Dispatchers.Main) {
                    assertEquals(revision, controller.document.revision)
                    assertSame(pixels, controller.frame)
                    assertSame(previews, controller.previews.images)
                    val output = Path.of("build/reports/screenshots/layer-reorder.png")
                    Files.createDirectories(output.parent)
                    scene.render(frame++ * 16_666_667L).use { image ->
                        val colors = image.toComposeImageBitmap().toPixelMap()
                        val indicator =
                            (200 until 380).maxOf { y ->
                                (605..895).count { x ->
                                    val color = colors[x, y]
                                    kotlin.math.abs(color.red - StudioTheme.accent.red) < 0.01f &&
                                        kotlin.math.abs(color.green - StudioTheme.accent.green) <
                                            0.01f &&
                                        kotlin.math.abs(color.blue - StudioTheme.accent.blue) <
                                            0.01f
                                }
                            }
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output, it.bytes)
                        }
                        assertTrue(
                            indicator > 280,
                            "The dragged row hides the insertion indicator: $indicator pixels",
                        )
                    }
                }
                pointer(PointerEventType.Release, 630f, 325f)
                awaitState { controller.document.revision > revision }
                assertEquals(listOf(4, 1, 2, 3), controller.document.layers.map { it.id })
                assertEquals(revision + 1, controller.document.revision)
                render(40)
                withContext(Dispatchers.Main) {
                    scene.render(frame++ * 16_666_667L).use { image ->
                        assertEquals(
                            21f / 255,
                            image.toComposeImageBitmap().toPixelMap()[300, 320].red,
                            0.005f,
                        )
                    }
                }
                val after = save()
                assertFalse(before.contentEquals(after))
                command("undo")
                awaitState { controller.document.layers.map { it.id } == listOf(1, 2, 3, 4) }
                assertContentEquals(before, save())
                command("redo")
                awaitState { controller.document.layers.first().id == 4 }
                assertContentEquals(after, save())
                render(40)
                pointer(PointerEventType.Press, 630f, 314f)
                pointer(PointerEventType.Move, 630f, 56f)
                pointer(PointerEventType.Release, 630f, 56f)
                awaitState { controller.document.layers.map { it.id } == listOf(1, 2, 3, 4) }
            }

            @Test
            fun thumbnailTapSelectsAndASecondFingerCancelsTheDrag() =
                runBlocking<Unit> {
                    session(4) {
                        val revision = controller.document.revision
                        pointer(PointerEventType.Press, 630f, 160f)
                        pointer(PointerEventType.Release, 630f, 160f)
                        awaitState { controller.document.active == 3 }
                        suspend fun touch(
                            type: PointerEventType,
                            y: Float,
                            second: Boolean,
                            pressed: Boolean,
                        ) {
                            withContext(Dispatchers.Main) {
                                scene.sendPointerEvent(
                                    type,
                                    buildList {
                                        add(
                                            ComposeScenePointer(
                                                PointerId(11),
                                                Offset(630f, y),
                                                pressed,
                                                PointerType.Touch,
                                            )
                                        )
                                        if (second)
                                            add(
                                                ComposeScenePointer(
                                                    PointerId(12),
                                                    Offset(730f, y),
                                                    pressed,
                                                    PointerType.Touch,
                                                )
                                            )
                                    },
                                )
                            }
                            render()
                        }
                        touch(PointerEventType.Press, 160f, false, true)
                        touch(PointerEventType.Move, 325f, false, true)
                        touch(PointerEventType.Press, 325f, true, true)
                        touch(PointerEventType.Release, 325f, true, false)
                        render(40)
                        assertEquals(revision, controller.document.revision)
                        assertEquals((1..4).toList(), controller.document.layers.map { it.id })
                        assertFalse(controller.document.canUndo)
                        withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
                    }
                }
        }

    @Test
    fun edgeDragScrollsToTheEndAndStopsOnRelease() =
        runBlocking<Unit> {
            session(32) {
                val revision = controller.document.revision
                val pixels = controller.frame
                val edge = withContext(Dispatchers.Main) { layerListBounds().bottom - 2f }
                pointer(PointerEventType.Press, 630f, 84f)
                pointer(PointerEventType.Move, 630f, edge)
                render(300)
                assertEquals(revision, controller.document.revision)
                assertSame(pixels, controller.frame)
                pointer(PointerEventType.Release, 630f, edge)
                awaitState { controller.document.revision > revision }
                assertEquals(listOf(32) + (1..31), controller.document.layers.map { it.id })
                render(80)
                withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
            }
        }

    @Test
    fun outsideDropAndDocumentChangesCancelWithoutExtraHistory() =
        runBlocking<Unit> {
            session(4) {
                val revision = controller.document.revision
                val pixels = controller.frame
                pointer(PointerEventType.Press, 630f, 84f)
                pointer(PointerEventType.Move, 630f, 300f)
                pointer(PointerEventType.Release, 500f, 300f)
                render(40)
                assertEquals(revision, controller.document.revision)
                assertSame(pixels, controller.frame)
                pointer(PointerEventType.Press, 630f, 84f)
                pointer(PointerEventType.Move, 630f, 300f)
                command("add_layer")
                awaitState { controller.document.layers.size == 5 }
                pointer(PointerEventType.Release, 630f, 300f)
                render(40)
                assertEquals(revision + 1, controller.document.revision)
                assertEquals((1..5).toList(), controller.document.layers.map { it.id })
                command("undo")
                awaitState { controller.document.layers.size == 4 }
                assertFalse(controller.document.canUndo)
                withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
            }
        }
}
