package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
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

@OptIn(ExperimentalComposeUiApi::class)
class GradientMapRenderingTest {
    private class Session(val controller: StudioController, val scene: ImageComposeScene) {
        private var frame = 0L
        private val view = Size(800f, 880f)

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                    }
                    delay(5)
                    if (withContext(Dispatchers.Main) { predicate() && !controller.busy }) break
                }
            }

        suspend fun settle() {
            repeat(25) {
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

        suspend fun click(label: String) {
            settle()
            withContext(Dispatchers.Main) {
                val point = node(label, SemanticsActions.OnClick).boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        suspend fun slider(label: String, value: Float) {
            withContext(Dispatchers.Main) {
                assertTrue(
                    assertNotNull(
                        node(label, SemanticsActions.SetProgress)
                            .config[SemanticsActions.SetProgress]
                            .action
                    )(value)
                )
            }
            settle()
        }

        suspend fun previewReady() = waitFor { controller.adjustmentPreview?.updating == false }

        fun pixel(x: Int, preview: Boolean = false): Color {
            val pixels =
                if (preview) assertNotNull(controller.adjustmentPreview).frame else controller.frame
            return pixels.tiles.values.single().image.toPixelMap()[x, 16]
        }

        fun canvasPixel(x: Int): Color {
            val point =
                controller.viewport.toView(Offset(x + 0.5f, 16.5f), view, controller.document)
            return render().use {
                it.toComposeImageBitmap().toPixelMap()[point.x.toInt(), point.y.toInt()]
            }
        }

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }
    }

    private suspend fun withSession(block: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(64, 32)
        val project =
            try {
                listOf(
                        """{"type":"fill","x":0,"y":0,"color":[0,0,0,128],"tolerance":0}""",
                        """{"type":"select","rect":{"left":20,"top":0,"right":44,"bottom":32}}""",
                        """{"type":"fill","x":21,"y":0,"color":[128,128,128,200],"tolerance":0}""",
                        """{"type":"select","rect":{"left":44,"top":0,"right":64,"bottom":32}}""",
                        """{"type":"fill","x":45,"y":0,"color":[255,255,255,255],"tolerance":0}""",
                        """{"type":"select","rect":null}""",
                    )
                    .forEach { native.call(EngineOperation.COMMAND, it.encodeToByteArray()) }
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Gradient map must not save automatically")

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
                ImageComposeScene(1160, 960) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                CanvasWorkspace(controller, Modifier.weight(1f).fillMaxWidth())
                                Box(
                                    Modifier.fillMaxWidth().height(80.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AdjustmentDock(controller)
                                }
                            }
                            Surface(
                                Modifier.width(360.dp).fillMaxHeight(),
                                color = StudioTheme.panel,
                            ) {
                                Box(Modifier.padding(16.dp)) { AdjustmentControls(controller) }
                            }
                        }
                    }
                }
            }
        val session = Session(controller, scene)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 64 && controller.hasCanvas }
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
    fun realControlsEditAddAndRemoveStopsCompareOriginalAndCancelWithoutChangingArtwork() =
        runBlocking {
            withSession {
                val document = controller.document
                val frame = controller.frame
                val original = withContext(Dispatchers.Main) { canvasPixel(10) }
                click("Gradient map")
                previewReady()
                click("Add color stop")
                previewReady()
                assertEquals(3, controller.adjustmentPreview!!.settings.gradientMap.stops.size)
                slider("Stop position", 0.35f)
                slider("Hue", 330f)
                slider("Saturation", 1f)
                slider("Value", 0.85f)
                previewReady()
                withContext(Dispatchers.Main) {
                    val stop = controller.adjustmentPreview!!.settings.gradientMap.stops[1]
                    assertEquals(0.35f, stop.position, 0.0001f)
                    assertTrue(stop.color[0] > stop.color[2] && stop.color[2] > stop.color[1])
                    assertTrue(controller.adjustmentPreview!!.changed)
                    assertSame(frame, controller.frame)
                    assertEquals(document, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                }
                click("Remove color stop")
                previewReady()
                assertEquals(2, controller.adjustmentPreview!!.settings.gradientMap.stops.size)
                click("Reverse gradient")
                previewReady()
                withContext(Dispatchers.Main) {
                    assertEquals(1f, pixel(10, true).red, 0.005f)
                    assertEquals(0f, pixel(54, true).red, 0.005f)
                    assertNotEquals(original, canvasPixel(10))
                }
                click("Compare original")
                withContext(Dispatchers.Main) { assertEquals(original, canvasPixel(10)) }
                click("Cancel adjustment")
                waitFor { controller.adjustmentPreview == null }
                withContext(Dispatchers.Main) {
                    assertEquals(document, controller.document)
                    assertSame(frame, controller.frame)
                    assertEquals(original, canvasPixel(10))
                    assertFalse(controller.hasUnsavedChanges)
                }
            }
        }

    @Test
    fun applyControlUsesFeatheredSelectionAndPreservesAlphaWithExactUndoRedo() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(16, 4, 48, 28)) }
            waitFor { controller.document.selection != null }
            val selectionId = controller.document.selectionId
            withContext(Dispatchers.Main) {
                controller.refineSelection(SelectionRefinement.Feather, 2)
            }
            waitFor { controller.document.selectionId > selectionId }
            val document = controller.document
            val source = withContext(Dispatchers.Main) { (0..63).map { pixel(it) } }
            click("Gradient map")
            previewReady()
            click("Reverse gradient")
            previewReady()
            val mapped =
                withContext(Dispatchers.Main) {
                    (0..63).map { x ->
                        pixel(x, true).also { assertEquals(source[x].alpha, it.alpha) }
                    }
                }
            assertEquals(source[4], mapped[4])
            assertEquals(source[58], mapped[58])
            assertTrue(mapped[15].red > source[15].red && mapped[15].red < mapped[18].red)
            assertNotEquals(source[18], mapped[18])
            assertFalse(controller.hasUnsavedChanges)
            click("Apply adjustment")
            waitFor { controller.adjustmentPreview == null && controller.hasUnsavedChanges }
            withContext(Dispatchers.Main) {
                assertEquals(document.revision + 1, controller.document.revision)
                assertEquals(document.selection, controller.document.selection)
                assertEquals(mapped, (0..63).map { pixel(it) })
            }
            command("undo")
            waitFor { controller.document.contentId == document.contentId }
            withContext(Dispatchers.Main) {
                assertEquals(source, (0..63).map { pixel(it) })
                assertFalse(controller.hasUnsavedChanges)
            }
            command("redo")
            waitFor { controller.hasUnsavedChanges }
            withContext(Dispatchers.Main) { assertEquals(mapped, (0..63).map { pixel(it) }) }
        }
    }
}
