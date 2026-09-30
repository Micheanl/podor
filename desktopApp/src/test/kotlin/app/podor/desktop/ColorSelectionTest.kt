package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ColorSelectionTest {
    @Test
    fun wandClickOptionsAndFillRespectViewAndPreserveArtworkUntilEditing() = runBlocking {
        NativeLoader.load()
        val native = createNativeEngine(256, 192)
        val project =
            try {
                fun command(value: String) =
                    native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                for ((left, red) in listOf(24 to 180, 160 to 191)) {
                    command(
                        """{"type":"select","rect":{"left":$left,"top":40,"right":${left + 72},"bottom":140}}"""
                    )
                    command(
                        """{"type":"fill","x":$left,"y":40,"color":[$red,20,40,255],"tolerance":0}"""
                    )
                }
                command("""{"type":"select","rect":null}""")
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
            withContext(Dispatchers.Main) { ImageComposeScene(1360, 900) { StudioApp(controller) } }
        var frame = 0L
        fun render() = scene.render(frame++ * 16_666_667L)
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }
        fun controls() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun click(chinese: String, english: String, toggle: Boolean = false) {
            val labels = listOf(chinese, english)
            val label =
                controls()
                    .filter { node ->
                        !node.boundsInWindow.isEmpty &&
                            (toggle || node.config.contains(SemanticsActions.OnClick)) &&
                            (node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                                value ->
                                labels.any { value.startsWith(it) }
                            } == true ||
                                node.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                                    value.text in labels
                                } == true)
                    }
                    .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                    ?: error("Missing rendered control: $english")
            val control =
                if (toggle) {
                    controls()
                        .filter {
                            it.config.contains(SemanticsProperties.ToggleableState) &&
                                it.config.contains(SemanticsActions.OnClick) &&
                                !it.boundsInWindow.isEmpty
                        }
                        .minByOrNull {
                            abs(it.boundsInWindow.center.y - label.boundsInWindow.center.y)
                        }
                        ?.also {
                            assertTrue(
                                abs(it.boundsInWindow.center.y - label.boundsInWindow.center.y) < 1f
                            )
                        } ?: error("Missing rendered switch: $english")
                } else label
            val point = control.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            repeat(35) { render().close() }
        }
        fun position(point: Offset) =
            controller.viewport.toView(
                point,
                Size(
                    1360f - StudioTheme.inspectorWidth.value - StudioTheme.inspectorMargin.value,
                    836f,
                ),
                controller.document,
            ) + Offset(0f, 64f)
        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        assertNull(controller.error)
                        predicate()
                    }
                ) delay(5)
            }
        fun screenshot(name: String) {
            val file = Path.of("build/reports/screenshots/$name.png")
            Files.createDirectories(file.parent)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
            }
        }
        try {
            waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            waitFor { controller.document.width == 256 && !controller.busy }
            val original =
                withContext(Dispatchers.Main) {
                    scene.openInspector { render().close() }
                    controller.tool = Tool.Select
                    controller.viewport = Viewport(rotation = 17f, mirrored = true)
                    repeat(4) { render().close() }
                    click("魔棒选区", "Magic wand")
                    assertEquals(SelectionKind.MagicWand, controller.selectionKind)
                    controller.tool = Tool.Brush
                    scene.sendKeyEvent(KeyEvent(Key.W, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.W, KeyEventType.KeyUp))
                    assertEquals(Tool.Select, controller.tool)
                    assertEquals(SelectionKind.MagicWand, controller.selectionKind)
                    controller.frame
                }
            withContext(Dispatchers.Main) {
                val p = position(Offset(50f, 70f))
                scene.sendPointerEvent(PointerEventType.Press, p)
                assertNull(controller.document.selection)
                scene.sendPointerEvent(PointerEventType.Release, p)
            }
            waitFor { controller.document.selection != null && !controller.busy }
            withContext(Dispatchers.Main) {
                val selection = controller.document.selection!!
                assertEquals(
                    listOf(24, 40, 96, 140),
                    listOf(selection.left, selection.top, selection.right, selection.bottom),
                )
                assertSame(original, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                repeat(4) { render().close() }
                click("魔棒设置", "Magic wand options")
                screenshot("magic-wand-options")
                click("仅连续区域", "Contiguous only", toggle = true)
                assertFalse(controller.selectionContiguous)
                click("取样所有可见图层", "Sample visible layers", toggle = true)
                assertTrue(controller.selectionMerged)
                click("取样所有可见图层", "Sample visible layers", toggle = true)
                assertFalse(controller.selectionMerged)
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                repeat(4) { render().close() }
            }
            waitFor { !controller.busy }
            withContext(Dispatchers.Main) { controller.selectColor(Offset(50f, 70f)) }
            waitFor { controller.document.selection?.right == 232 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences.copy(language = Language.English)
                )
                repeat(4) { render().close() }
                screenshot("magic-wand-english")
                val before = controller.document.selection
                val p = position(Offset(5f, 5f))
                scene.sendPointerEvent(PointerEventType.Press, p)
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                scene.sendPointerEvent(PointerEventType.Release, p)
                render().close()
                assertEquals(before, controller.document.selection)
                controller.selectionTolerance = 0f
            }
            waitFor { !controller.busy }
            withContext(Dispatchers.Main) {
                controller.selectColor(Offset(50f, 70f))
            }
            waitFor { controller.document.selection?.right == 96 && !controller.busy }
            withContext(Dispatchers.Main) {
                assertSame(original, controller.frame)
                controller.brush = controller.brush.copy(color = 0xFF2848C0, opacity = 1f)
                controller.fill(Offset(50f, 70f))
            }
            waitFor { controller.hasUnsavedChanges && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(
                    40f / 255f,
                    controller.frame.tiles[0]!!.image.toPixelMap()[50, 70].red,
                    0.005f,
                )
                assertEquals(
                    191f / 255f,
                    controller.frame.tiles[1L shl 32]!!.image.toPixelMap()[50, 70].red,
                    0.005f,
                )
                controller.command("undo")
            }
            waitFor { !controller.hasUnsavedChanges && controller.document.canRedo }
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                repeat(200) {
                    render().close()
                    delay(1)
                }
                assertFalse(scene.hasInvalidations(), "Magic wand redraws while idle")
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
