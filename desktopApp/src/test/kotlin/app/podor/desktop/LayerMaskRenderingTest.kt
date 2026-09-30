package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class LayerMaskRenderingTest {
    private class Session(val controller: StudioController, val scene: ImageComposeScene) {
        private var frame = 0L
        private val view = androidx.compose.ui.geometry.Size(600f, 640f)

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (true) {
                    withContext(Dispatchers.Main) { render().close() }
                    delay(5)
                    if (
                        withContext(Dispatchers.Main) {
                            predicate() &&
                                !controller.busy &&
                                controller.previews.revision == controller.document.revision
                        }
                    )
                        break
                }
            }

        suspend fun settle(count: Int = 30) {
            repeat(count) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        fun node(label: String, action: Boolean = false): SemanticsNode =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter { node ->
                    (node.config
                        .getOrNull(SemanticsProperties.ContentDescription)
                        ?.contains(label) == true ||
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } ==
                            true) &&
                        (!action || node.config.contains(SemanticsActions.OnClick)) &&
                        !node.boundsInWindow.isEmpty
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
            val point = withContext(Dispatchers.Main) { node(label).boundsInWindow.center }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun option(label: String) {
            click(
                if (
                    controller.document.layers.first { it.id == controller.document.active }.mask ==
                        null
                )
                    "Add mask"
                else "Mask settings"
            )
            click(label)
        }

        suspend fun stroke(color: Long) {
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.brush = controller.brush.copy(color = color)
            }
            fun point(x: Float) =
                controller.viewport.toView(Offset(x, 24.5f), view, controller.document)
            pointer(PointerEventType.Press, point(16.5f))
            pointer(PointerEventType.Move, point(44.5f))
            pointer(PointerEventType.Release, point(44.5f))
            pointer(PointerEventType.Move, Offset.Zero)
        }

        fun alpha(x: Int, y: Int) =
            controller.frame.tiles.values
                .firstOrNull { it.x == 0 && it.y == 0 }
                ?.image
                ?.toPixelMap()
                ?.get(x, y)
                ?.alpha ?: 0f

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }

        fun capture(name: String) {
            val file = Path.of("build/reports/screenshots/$name.png")
            Files.createDirectories(file.parent)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
            }
        }
    }

    private suspend fun withSession(count: Int = 1, block: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(64, 64)
        val project =
            try {
                for (id in 1..count) {
                    fun command(value: String) =
                        native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                    if (id > 1) command("""{"type":"add_layer"}""")
                    val name = listOf("Ink", "Accent", "Finish")[id - 1]
                    command(
                        """{"type":"set_layer","id":$id,"name":"$name","visible":true,"opacity":1}"""
                    )
                    command(
                        """{"type":"fill","x":0,"y":0,"color":[${190 - id * 30},50,110,255],"tolerance":0}"""
                    )
                }
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Mask UI must not save automatically")

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
                ImageComposeScene(900, 640) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
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
        val session = Session(controller, scene)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor {
                controller.document.width == 64 && controller.document.layers.size == count
            }
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
    fun thumbnailTargetsEditMasksWithoutChangingPixelsAndBlackWhiteStrokesHideAndRestore() =
        runBlocking {
            withSession {
                option("Reveal all")
                waitFor { controller.document.maskEditing && controller.previews.masks[1] != null }
                val content = controller.document.contentId
                click("Ink · Edit layer pixels")
                waitFor { !controller.document.maskEditing }
                assertEquals(content, controller.document.contentId)
                click("Ink · Edit mask")
                waitFor { controller.document.maskEditing }
                assertEquals(content, controller.document.contentId)
                stroke(0xFF000000)
                waitFor { alpha(20, 24) == 0f }
                withContext(Dispatchers.Main) {
                    assertEquals(1f, alpha(20, 23))
                    val mask = controller.previews.masks.getValue(1)
                    val pixels = IntArray(mask.width * mask.height)
                    mask.readPixels(pixels)
                    assertTrue(
                        pixels.any { (it and 0xFFFFFF) < 0xCCCCCC },
                        "Native mask thumbnail has no dark stroke",
                    )
                    capture("layer-mask-light")
                }
                command("undo")
                waitFor { alpha(20, 24) == 1f }
                command("redo")
                waitFor { alpha(20, 24) == 0f }
                stroke(0xFFFFFFFF)
                waitFor { alpha(20, 24) == 1f }
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(
                        controller.preferences.copy(appearance = Appearance.Dark)
                    )
                }
                settle(200)
                withContext(Dispatchers.Main) {
                    capture("layer-mask-dark")
                    assertFalse(scene.hasInvalidations())
                    assertNull(controller.error)
                }
            }
        }

    @Test
    fun maskMenuTogglesLinksAndVisibilityAndApplyDeleteHaveDifferentUndoableResults() =
        runBlocking {
            withSession {
                option("Hide all")
                waitFor { controller.document.layers.first().mask != null && alpha(32, 32) == 0f }
                option("Disable mask")
                waitFor {
                    controller.document.layers.first().mask?.enabled == false && alpha(32, 32) == 1f
                }
                click("Mask settings")
                withContext(Dispatchers.Main) {
                    assertTrue(
                        node("Apply mask", action = true)
                            .config
                            .contains(SemanticsProperties.Disabled)
                    )
                }
                pointer(PointerEventType.Press, Offset(5f, 5f))
                pointer(PointerEventType.Release, Offset(5f, 5f))
                option("Unlink mask")
                waitFor { controller.document.layers.first().mask?.linked == false }
                option("Enable mask")
                waitFor {
                    controller.document.layers.first().mask?.enabled == true && alpha(32, 32) == 0f
                }
                option("Invert mask")
                waitFor { alpha(32, 32) == 1f }
                option("Invert mask")
                waitFor { alpha(32, 32) == 0f }
                click("Lock alpha")
                waitFor { controller.document.layers.first().alphaLocked }
                click("Mask settings")
                withContext(Dispatchers.Main) {
                    assertTrue(
                        node("Apply mask", action = true)
                            .config
                            .contains(SemanticsProperties.Disabled)
                    )
                }
                pointer(PointerEventType.Press, Offset(5f, 5f))
                pointer(PointerEventType.Release, Offset(5f, 5f))
                click("Unlock alpha")
                waitFor { !controller.document.layers.first().alphaLocked }
                option("Apply mask")
                waitFor {
                    controller.document.layers.first().mask == null &&
                        !controller.document.maskEditing
                }
                assertEquals(0f, withContext(Dispatchers.Main) { alpha(32, 32) })
                command("undo")
                waitFor { controller.document.layers.first().mask != null }
                option("Delete mask")
                waitFor { controller.document.layers.first().mask == null && alpha(32, 32) == 1f }
                command("undo")
                waitFor { controller.document.layers.first().mask != null && alpha(32, 32) == 0f }
                assertNull(controller.error)
            }
        }

    @Test
    fun selectionCreatesTheVisibleMaskRegionAndAnEmptySelectionDisablesTheEntry() = runBlocking {
        withSession {
            withContext(Dispatchers.Main) { controller.select(Selection(16, 16, 48, 48)) }
            waitFor { controller.document.selection != null }
            val selection = controller.document.selection
            option("From selection")
            waitFor { controller.document.layers.first().mask != null }
            assertEquals(selection, controller.document.selection)
            withContext(Dispatchers.Main) {
                assertEquals(1f, alpha(32, 32))
                assertEquals(0f, alpha(8, 8))
            }
            command("undo")
            waitFor { controller.document.layers.first().mask == null }
            withContext(Dispatchers.Main) {
                controller.changeSelectionMode(SelectionMode.Intersect)
                controller.select(Selection(2, 2, 8, 8))
            }
            waitFor { controller.document.selection?.empty == true }
            click("Add mask")
            withContext(Dispatchers.Main) {
                assertTrue(
                    node("From selection", action = true)
                        .config
                        .contains(SemanticsProperties.Disabled)
                )
                assertNull(controller.document.layers.first().mask)
            }
        }
    }

    @Test
    fun holdingTheMaskThumbnailReordersItsLayerAndKeepsTheMaskAttachedThroughUndo() = runBlocking {
        withSession(count = 3) {
            option("Reveal all")
            waitFor { controller.previews.masks[3] != null && controller.document.maskEditing }
            val revision = controller.document.revision
            val start =
                withContext(Dispatchers.Main) { node("Finish · Edit mask").boundsInWindow.center }
            pointer(PointerEventType.Press, start)
            delay(650)
            settle(12)
            pointer(PointerEventType.Move, Offset(start.x, 200f))
            pointer(PointerEventType.Move, Offset(start.x, 300f))
            assertEquals(revision, controller.document.revision)
            pointer(PointerEventType.Release, Offset(start.x, 300f))
            pointer(PointerEventType.Move, Offset.Zero)
            waitFor { controller.document.layers.map { it.id } == listOf(3, 1, 2) }
            assertEquals(revision + 1, controller.document.revision)
            assertNotNull(controller.document.layers.first().mask)
            assertTrue(controller.document.maskEditing)
            command("undo")
            waitFor { controller.document.layers.map { it.id } == listOf(1, 2, 3) }
            assertNotNull(controller.document.layers.last().mask)
            assertNotNull(controller.previews.masks[3])
            assertNull(controller.error)
        }
    }

    @Test
    fun maskTargetRejectsUnsupportedToolsAndPixelTargetCanUseThemNormally() = runBlocking {
        withSession {
            option("Reveal all")
            waitFor { controller.document.maskEditing }
            val document = controller.document
            val frame = controller.frame
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Smudge
                assertNotEquals(Tool.Smudge, controller.tool)
                assertTrue(assertNotNull(controller.error).contains("蒙版"))
                controller.dismissError()
                controller.tool = Tool.Gradient
                controller.prepareGradient()
                assertTrue(assertNotNull(controller.error).contains("图层像素"))
                assertNull(controller.gradientPreview)
                controller.dismissError()
                controller.prepareAdjustment(AdjustmentKind.Tone)
                assertTrue(assertNotNull(controller.error).contains("图层像素"))
                assertNull(controller.adjustmentPreview)
                assertSame(frame, controller.frame)
                assertEquals(document, controller.document)
                controller.dismissError()
                controller.tool = Tool.Brush
            }
            click("Ink · Edit layer pixels")
            waitFor { !controller.document.maskEditing }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Smudge
                assertEquals(Tool.Smudge, controller.tool)
                controller.begin(Offset(24f, 24f), 1f)
                controller.points(listOf(Triple(40f, 24f, 1f)))
                controller.end()
            }
            settle()
            withContext(Dispatchers.Main) {
                assertNull(controller.error)
                controller.tool = Tool.Gradient
                controller.prepareGradient()
            }
            waitFor { controller.gradientPreview != null }
            val beforeGradient = controller.document.revision
            withContext(Dispatchers.Main) {
                controller.previewGradient(GradientLine(Offset(8f, 8f), Offset(56f, 56f)))
                controller.commitGradient()
            }
            waitFor {
                controller.gradientPreview == null && controller.document.revision > beforeGradient
            }
            withContext(Dispatchers.Main) { controller.prepareAdjustment(AdjustmentKind.Tone) }
            waitFor { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                val preview = assertNotNull(controller.adjustmentPreview)
                controller.updateAdjustment(preview.settings.copy(brightness = 0.2f))
            }
            waitFor { controller.adjustmentPreview?.let { it.changed && !it.updating } == true }
            val beforeAdjustment = controller.document.revision
            withContext(Dispatchers.Main) { controller.commitAdjustment() }
            waitFor {
                controller.adjustmentPreview == null &&
                    controller.document.revision > beforeAdjustment
            }
            assertNull(controller.error)
            assertNotNull(controller.document.layers.first().mask)
        }
    }
}
