package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

@OptIn(ExperimentalComposeUiApi::class)
class SelectionRefinementRenderingTest {
    private class Session(val controller: StudioController, val scene: ImageComposeScene) {
        private var frame = 0L
        private val view = Size(800f, 560f)

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (true) {
                    withContext(Dispatchers.Main) { render().close() }
                    delay(5)
                    if (withContext(Dispatchers.Main) { predicate() && !controller.busy }) break
                }
            }

        suspend fun settle(count: Int = 25) {
            repeat(count) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        fun node(label: String, action: SemanticsPropertyKey<*>): SemanticsNode =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.contains(action) &&
                        !it.boundsInWindow.isEmpty &&
                        (it.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains(label) == true ||
                            it.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                                value.text == label
                            } == true)
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing rendered control: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        suspend fun click(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    node(label, SemanticsActions.OnClick).boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun radius(label: String, value: Float) {
            withContext(Dispatchers.Main) {
                val slider = node(label, SemanticsActions.SetProgress)
                assertEquals(0f..64f, slider.config[SemanticsProperties.ProgressBarRangeInfo].range)
                assertTrue(assertNotNull(slider.config[SemanticsActions.SetProgress].action)(value))
            }
            settle()
            withContext(Dispatchers.Main) {
                assertEquals(
                    value,
                    node(label, SemanticsActions.SetProgress)
                        .config[SemanticsProperties.ProgressBarRangeInfo]
                        .current,
                )
            }
        }

        suspend fun refine(label: String, pixels: Int = 2) {
            val before = controller.document.selectionId
            click("Refine selection")
            click(label)
            val document = controller.document
            radius(label, 0f)
            radius(label, 64f)
            radius(label, pixels.toFloat())
            assertEquals(document, controller.document)
            click("Apply")
            waitFor { controller.document.selectionId > before }
        }

        suspend fun select(selection: Selection) {
            val before = controller.document.selectionId
            withContext(Dispatchers.Main) { controller.select(selection) }
            waitFor { controller.document.selectionId > before }
        }

        suspend fun mouse(type: PointerEventType, point: Offset) =
            pointer(type, controller.viewport.toView(point, view, controller.document))

        fun alpha(x: Int, y: Int): Float =
            controller.frame.tiles.values
                .firstOrNull { it.x == 0 && it.y == 0 }
                ?.image
                ?.toPixelMap()
                ?.get(x, y)
                ?.alpha ?: 0f

        fun coverage(x: Int, y: Int): Float {
            val tile =
                assertNotNull(controller.selectionOutline).fillMask.firstOrNull {
                    x / it.size == it.x && y / it.size == it.y
                } ?: return 0f
            return tile.image.toPixelMap()[x % tile.size, y % tile.size].alpha
        }

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }
    }

    private suspend fun withSession(block: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(64, 64)
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
                    error("Selection refinement must not save automatically")

                override suspend fun readPreferences() =
                    Json.encodeToString(
                            Preferences(language = Language.English, appearance = Appearance.Light)
                        )
                        .encodeToByteArray()

                override suspend fun writePreferences(bytes: ByteArray) {}
            }
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(800, 640) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Column {
                            CanvasWorkspace(controller, Modifier.weight(1f).fillMaxWidth())
                            Box(
                                Modifier.fillMaxWidth().height(80.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SelectionDock(controller)
                            }
                        }
                    }
                }
            }
        val session = Session(controller, scene)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 64 && !controller.showWorkspace }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = originalAppearance) {} }
                try {
                    restore.render(0).close()
                } finally {
                    restore.close()
                }
            }
            scope.cancel()
        }
    }

    @Test
    fun allFourRefinementControlsChangeCoverageWithoutChangingPixelsSavedStateOrRedo() =
        runBlocking {
            withSession {
                withContext(Dispatchers.Main) {
                    assertTrue(
                        node("Refine selection", SemanticsActions.OnClick)
                            .config
                            .contains(SemanticsProperties.Disabled)
                    )
                    controller.brush = controller.brush.copy(color = 0xFF2848C0, opacity = 1f)
                    controller.fill(Offset(32f, 32f))
                }
                waitFor { controller.document.canUndo }
                command("undo")
                waitFor { controller.document.canRedo && !controller.hasUnsavedChanges }
                val saved = controller.document
                val frame = controller.frame
                withContext(Dispatchers.Main) { controller.tool = Tool.Select }
                for (label in
                    listOf(
                        "Expand selection",
                        "Contract selection",
                        "Smooth selection",
                        "Feather selection",
                    )) {
                    withContext(Dispatchers.Main) {
                        controller.changeSelectionMode(SelectionMode.Replace)
                    }
                    select(Selection(16, 16, 48, 48))
                    if (label == "Smooth selection") {
                        withContext(Dispatchers.Main) {
                            controller.changeSelectionMode(SelectionMode.Subtract)
                        }
                        select(Selection(20, 20, 21, 21))
                        withContext(Dispatchers.Main) {
                            controller.changeSelectionMode(SelectionMode.Add)
                        }
                        select(Selection(2, 2, 3, 3))
                        withContext(Dispatchers.Main) {
                            assertEquals(0f, coverage(20, 20))
                            assertEquals(1f, coverage(2, 2))
                        }
                    }
                    refine(label)
                    withContext(Dispatchers.Main) {
                        when (label) {
                            "Expand selection" -> {
                                assertEquals(1f, coverage(14, 32))
                                assertEquals(0f, coverage(13, 32))
                            }
                            "Contract selection" -> {
                                assertEquals(0f, coverage(17, 32))
                                assertEquals(1f, coverage(18, 32))
                            }
                            "Smooth selection" -> {
                                assertEquals(1f, coverage(20, 20))
                                assertEquals(0f, coverage(2, 2))
                            }
                            "Feather selection" -> {
                                assertTrue(coverage(15, 32) in 0.01f..0.99f)
                                assertTrue(coverage(15, 32) < coverage(16, 32))
                                assertEquals(1f, coverage(32, 32))
                            }
                        }
                        assertEquals(saved.revision, controller.document.revision)
                        assertEquals(saved.contentId, controller.document.contentId)
                        assertEquals(saved.canUndo, controller.document.canUndo)
                        assertEquals(saved.canRedo, controller.document.canRedo)
                        assertEquals(saved.layers, controller.document.layers)
                        assertSame(frame, controller.frame)
                        assertFalse(controller.hasUnsavedChanges)
                        assertNull(controller.error)
                    }
                }
                command("redo")
                waitFor { controller.document.canUndo && alpha(32, 32) == 1f }
                withContext(Dispatchers.Main) {
                    assertEquals(
                        40f / 255f,
                        controller.frame.tiles.values.single().image.toPixelMap()[32, 32].red,
                        0.005f,
                    )
                }
            }
        }

    @Test
    fun contractedSelectionClipsMousePaintingAndCanBeDraggedWithOneUndoableMove() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.tool = Tool.Select }
            select(Selection(16, 16, 48, 48))
            refine("Contract selection", 3)
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.brush = controller.brush.copy(color = 0xFF3020A0)
            }
            settle()
            val revision = controller.document.revision
            mouse(PointerEventType.Press, Offset(14.5f, 32.5f))
            mouse(PointerEventType.Move, Offset(50.5f, 32.5f))
            mouse(PointerEventType.Release, Offset(50.5f, 32.5f))
            waitFor { controller.document.revision > revision }
            withContext(Dispatchers.Main) {
                for (x in 0..63) assertEquals(if (x in 19..44) 1f else 0f, alpha(x, 32))
                controller.tool = Tool.Select
            }
            waitFor { controller.layerMove != null }
            val painted = controller.document
            mouse(PointerEventType.Press, Offset(25.5f, 32.5f))
            mouse(PointerEventType.Move, Offset(33.5f, 37.5f))
            mouse(PointerEventType.Release, Offset(33.5f, 37.5f))
            waitFor { controller.document.revision > painted.revision }
            withContext(Dispatchers.Main) {
                assertEquals(painted.revision + 1, controller.document.revision)
                assertNull(controller.document.selection)
                for (x in 0..63) {
                    assertEquals(0f, alpha(x, 32))
                    assertEquals(if (x in 27..52) 1f else 0f, alpha(x, 37))
                }
                assertNull(controller.error)
            }
            command("undo")
            waitFor { alpha(25, 32) == 1f && alpha(33, 37) == 0f }
            command("redo")
            waitFor { alpha(25, 32) == 0f && alpha(33, 37) == 1f }
        }
    }

    @Test
    fun staleRefinementKeepsTheLatestSelectionAndCancelledPixelPreviewDoesNotCreateSaveChanges() =
        runBlocking {
            withSession {
                select(Selection(8, 8, 32, 32))
                val stale = controller.document
                select(Selection(16, 16, 48, 48))
                val latest = controller.document
                val frame = controller.frame
                withContext(Dispatchers.Main) {
                    controller.command("modify_selection") {
                        put("kind", "expand")
                        put("radius", 4)
                        put("revision", stale.revision)
                        put("selection_id", stale.selectionId)
                    }
                }
                waitFor { controller.error != null }
                withContext(Dispatchers.Main) {
                    assertTrue(assertNotNull(controller.error).contains("选区已变化"))
                    assertEquals(latest, controller.document)
                    assertSame(frame, controller.frame)
                    assertFalse(controller.hasUnsavedChanges)
                    controller.dismissError()
                    controller.selectPreset(BrushPreset.PixelPencil)
                    controller.begin(Offset(20.5f, 24.5f), 1f)
                    controller.points(listOf(Triple(40.5f, 24.5f, 1f)))
                }
                waitFor { alpha(30, 24) > 0f }
                withContext(Dispatchers.Main) {
                    assertEquals(latest, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                    controller.end(cancel = true)
                }
                waitFor { alpha(30, 24) == 0f }
                withContext(Dispatchers.Main) {
                    assertEquals(latest, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                    assertNull(controller.error)
                }
            }
        }
}
