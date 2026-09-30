package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class IndexedWorkflowRenderingTest {
    private class MemoryFiles : ProjectFiles {
        var saved: ByteArray? = null
        var saves = 0
        var opens = 0
        val exports = mutableListOf<Pair<ExportFormat, ByteArray>>()
        override val exportFormats = ExportFormat.entries

        override suspend fun open() = saved

        override suspend fun openDocument(reference: ProjectReference?): OpenedProject? {
            opens++
            return saved?.let {
                OpenedProject(it, ProjectReference("indexed.podor", "Indexed study"))
            }
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(png)
            saved = bytes
            saves++
            return true
        }

        override suspend fun saveDocument(
            bytes: ByteArray,
            reference: ProjectReference?,
            saveAs: Boolean,
        ): ProjectReference {
            save(bytes, false)
            return ProjectReference("indexed.podor", "Indexed study")
        }

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            exports.add(format to bytes)
            return true
        }

        override suspend fun readPreferences() =
            Json.encodeToString(
                    Preferences(language = Language.English, appearance = Appearance.Light)
                )
                .encodeToByteArray()

        override suspend fun writePreferences(bytes: ByteArray) {}
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
        val touchPresses: MutableList<List<PointerInputChange>>,
    ) {
        private var frame = 0L
        private val view = Size(640f, 960f)

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(
            allowError: Boolean = false,
            state: () -> String = {
                "revision=${controller.document.revision}, busy=${controller.busy}, " +
                    "tool=${controller.tool}, indexedColor=${controller.indexedColorIndex}"
            },
            predicate: () -> Boolean,
        ) {
            try {
                withTimeout(15_000) {
                    while (true) {
                        withContext(Dispatchers.Main) {
                            render().close()
                            if (!allowError) assertNull(controller.error)
                        }
                        delay(5)
                        if (withContext(Dispatchers.Main) { predicate() && !controller.busy }) break
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError(
                    withContext(Dispatchers.Main) {
                        "Timed out waiting for indexed workflow: ${state()}"
                    },
                    timeout,
                )
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
                .filter { node ->
                    node.config.contains(action) &&
                        !node.boundsInWindow.isEmpty &&
                        descendants(node).any {
                            it.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains(label) == true ||
                                it.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                                    value.text == label
                                } == true
                        }
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

        suspend fun text(label: String, value: String) {
            withContext(Dispatchers.Main) {
                assertTrue(
                    assertNotNull(
                        node(label, SemanticsActions.SetText)
                            .config[SemanticsActions.SetText]
                            .action
                    )(AnnotatedString(value))
                )
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

        fun position(point: Offset) = controller.viewport.toView(point, view, controller.document)

        suspend fun mouse(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, position(point))
                render().close()
            }

        suspend fun stroke(start: Offset, end: Offset = start) {
            val revision = controller.document.revision
            mouse(PointerEventType.Press, start)
            if (start != end) mouse(PointerEventType.Move, end)
            mouse(PointerEventType.Release, end)
            waitFor { controller.document.revision > revision }
        }

        fun pixel(x: Int, y: Int): Color {
            val tile =
                controller.frame.tiles.values.firstOrNull {
                    x / it.size == it.x && y / it.size == it.y
                } ?: return Color.Transparent
            return tile.image.toPixelMap()[x % tile.size, y % tile.size]
        }

        fun pixels(): IntArray =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in controller.frame.tiles.values) {
                    val source = IntArray(tile.image.width * tile.image.height)
                    tile.image.readPixels(source)
                    val left = tile.x * tile.size
                    val top = tile.y * tile.size
                    val width = minOf(tile.size, controller.document.width - left)
                    for (y in 0 until minOf(tile.size, controller.document.height - top)) source
                        .copyInto(
                            output,
                            (top + y) * controller.document.width + left,
                            y * tile.image.width,
                            y * tile.image.width + width,
                        )
                }
            }

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }
    }

    private suspend fun withSession(
        width: Int = 64,
        height: Int = 48,
        block: suspend Session.() -> Unit,
    ) {
        NativeLoader.load()
        val files = MemoryFiles()
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val touchPresses = mutableListOf<List<PointerInputChange>>()
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1000, 960) {
                    var creating by remember { mutableStateOf(true) }
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Row {
                            CanvasWorkspace(
                                controller,
                                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (
                                                event.type == PointerEventType.Press &&
                                                    event.changes.any {
                                                        it.type == PointerType.Touch &&
                                                            it.pressed &&
                                                            !it.previousPressed
                                                    }
                                            )
                                                touchPresses.add(event.changes.toList())
                                        }
                                    }
                                },
                            )
                            Surface(
                                Modifier.width(360.dp).fillMaxHeight(),
                                color = StudioTheme.panel,
                            ) {
                                Column(Modifier.padding(16.dp)) { ColorControls(controller) }
                            }
                        }
                        if (creating && controller.ready)
                            NewCanvasDialog(controller) { creating = false }
                    }
                }
            }
        val session = Session(controller, scene, files, touchPresses)
        try {
            session.waitFor { controller.ready }
            session.settle()
            session.text("Width", width.toString())
            session.text("Height", height.toString())
            session.click("Indexed canvas")
            session.click("Create")
            session.waitFor {
                controller.hasCanvas &&
                    controller.document.width == width &&
                    controller.document.colorMode == DocumentColorMode.Indexed
            }
            session.settle()
            withContext(Dispatchers.Main) {
                assertEquals(IndexedPalette.defaults(), controller.document.indexedPalette)
                assertEquals(BrushPreset.PixelPencil, controller.brush.preset)
                assertEquals(1f, controller.brush.size)
                assertTrue(ExportFormat.IndexedPng in controller.exportFormats)
                assertTrue(controller.frame.tiles.isEmpty())
                assertEquals(0, files.saves)
            }
            session.click("Indexed color 0")
            withContext(Dispatchers.Main) {
                assertTrue(
                    session
                        .node("Replace and remove color", SemanticsActions.OnClick)
                        .config
                        .contains(SemanticsProperties.Disabled)
                )
            }
            session.click("Indexed color 1")
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
    fun indexedCreationAndPaletteDraftApplyUpdateEveryUsedTileAtomicallyAndDeletionRemapsTheSelectedSlot() =
        runBlocking {
            withSession(256, 192) {
                click("Indexed color 1")
                stroke(Offset(16.5f, 16.5f))
                stroke(Offset(224.5f, 160.5f))
                val before = controller.document
                val original = withContext(Dispatchers.Main) { pixels() }
                val source = controller.frame
                slider("Hue", 240f)
                slider("Saturation", 1f)
                slider("Value", 1f)
                withContext(Dispatchers.Main) {
                    assertEquals(before, controller.document)
                    assertSame(source, controller.frame)
                    assertFalse(
                        node("Apply palette color", SemanticsActions.OnClick)
                            .config
                            .contains(SemanticsProperties.Disabled)
                    )
                }
                click("Apply palette color")
                waitFor { controller.document.revision > before.revision }
                withContext(Dispatchers.Main) {
                    assertEquals(before.revision + 1, controller.document.revision)
                    assertEquals(
                        listOf(0, 0, 255, 255),
                        controller.document.indexedPalette!!.colors[1],
                    )
                    assertEquals(Color.Blue, pixel(16, 16))
                    assertEquals(Color.Blue, pixel(224, 160))
                }
                command("undo")
                waitFor { controller.document.indexedPalette == before.indexedPalette }
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                val count = controller.document.indexedPalette!!.colors.size
                click("Add palette color")
                waitFor { controller.document.indexedPalette!!.colors.size == count + 1 }
                click("Indexed color $count")
                stroke(Offset(32.5f, 24.5f))
                click("Move color earlier")
                waitFor { controller.document.indexedPalette!!.order[count - 1] == count }
                click("Replace and remove color")
                click("Replace with Indexed color 1")
                waitFor { controller.document.indexedPalette!!.colors.size == count }
                assertEquals(1, controller.indexedColorIndex)
                withContext(Dispatchers.Main) { controller.setIndexedColor(1, 0xFF2266CC) }
                waitFor { controller.document.indexedPalette!!.argb(1) == 0xFF2266CC }
                withContext(Dispatchers.Main) {
                    for ((x, y) in listOf(16 to 16, 224 to 160, 32 to 24)) assertEquals(
                        Color(0xFF2266CC),
                        pixel(x, y),
                    )
                    assertEquals(0, files.saves)
                    assertNull(controller.error)
                }
            }
        }

    @Test
    fun equalColoredSlotsRemainDistinctThroughPickerPaletteEditEraserCancellationAndGrayMaskPainting() =
        runBlocking {
            withSession {
                val first = controller.document.indexedPalette!!.argb(1)
                withContext(Dispatchers.Main) { controller.setIndexedColor(2, first) }
                waitFor { controller.document.indexedPalette!!.argb(2) == first }
                click("Indexed color 1")
                stroke(Offset(8.5f, 12.5f), Offset(24.5f, 12.5f))
                click("Indexed color 2")
                stroke(Offset(8.5f, 24.5f), Offset(24.5f, 24.5f))
                click("Indexed color 1")
                val painted = controller.document
                withContext(Dispatchers.Main) { controller.tool = Tool.Picker }
                mouse(PointerEventType.Press, Offset(14.5f, 24.5f))
                mouse(PointerEventType.Release, Offset(14.5f, 24.5f))
                waitFor { controller.indexedColorIndex == 2 }
                assertEquals(painted, controller.document)
                withContext(Dispatchers.Main) { controller.setIndexedColor(2, 0xFF2266CC) }
                waitFor { controller.document.indexedPalette!!.argb(2) == 0xFF2266CC }
                withContext(Dispatchers.Main) {
                    assertEquals(Color(first), pixel(14, 12))
                    assertEquals(Color(0xFF2266CC), pixel(14, 24))
                    controller.tool = Tool.Eraser
                }
                stroke(Offset(14.5f, 24.5f))
                withContext(Dispatchers.Main) {
                    assertEquals(0f, pixel(14, 24).alpha)
                    assertEquals(1f, pixel(13, 24).alpha)
                }
                command("undo")
                waitFor { pixel(14, 24).alpha == 1f }
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.Brush
                    controller.fingerDrawing = true
                }
                settle()
                val beforeCancel = controller.document
                val pixels = withContext(Dispatchers.Main) { pixels() }
                val points = listOf(Offset(10.5f, 34.5f), Offset(20.5f, 34.5f))
                for ((index, point) in points.withIndex()) withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        if (index == 0) PointerEventType.Press else PointerEventType.Move,
                        listOf(
                            ComposeScenePointer(
                                PointerId(1),
                                position(point),
                                true,
                                PointerType.Touch,
                                1f,
                            )
                        ),
                    )
                    render().close()
                }
                withContext(Dispatchers.Main) {
                    val press = touchPresses.single()
                    assertTrue(
                        press.any {
                            it.type == PointerType.Mouse && !it.pressed && !it.previousPressed
                        }
                    )
                    assertTrue(
                        press.any {
                            it.type == PointerType.Touch && it.pressed && !it.previousPressed
                        }
                    )
                }
                waitFor(
                    state = {
                        "Touch provisional row34=${(8..22).map { pixel(it, 34).alpha }}, " +
                            "revision=${controller.document.revision}, busy=${controller.busy}, " +
                            "tool=${controller.tool}, fingerDrawing=${controller.fingerDrawing}, " +
                            "raster=${controller.brush.preset.raster}, opacity=${controller.brush.opacity}, " +
                            "indexedColor=${controller.indexedColorIndex}"
                    }
                ) {
                    pixel(14, 34).alpha > 0f
                }
                withContext(Dispatchers.Main) {
                    val firstPoint = position(points.last())
                    val second = firstPoint + Offset(80f, 40f)
                    for (pressed in listOf(true, false)) {
                        scene.sendPointerEvent(
                            if (pressed) PointerEventType.Press else PointerEventType.Release,
                            listOf(
                                ComposeScenePointer(
                                    PointerId(1),
                                    firstPoint,
                                    pressed,
                                    PointerType.Touch,
                                    1f,
                                ),
                                ComposeScenePointer(
                                    PointerId(2),
                                    second,
                                    pressed,
                                    PointerType.Touch,
                                    1f,
                                ),
                            ),
                        )
                        render().close()
                    }
                }
                waitFor { pixel(14, 34).alpha == 0f }
                withContext(Dispatchers.Main) {
                    assertEquals(beforeCancel, controller.document)
                    assertContentEquals(pixels, pixels())
                    controller.addLayerMask("reveal")
                }
                waitFor { controller.document.maskEditing }
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF808080, opacity = 1f)
                }
                stroke(Offset(14.5f, 24.5f))
                withContext(Dispatchers.Main) {
                    assertEquals(128f / 255f, pixel(14, 24).alpha, 1f / 255f)
                    assertEquals(1f, pixel(13, 24).alpha)
                    assertEquals(DocumentColorMode.Indexed, controller.document.colorMode)
                    assertEquals(beforeCancel.indexedPalette, controller.document.indexedPalette)
                }
                command("undo")
                waitFor { pixel(14, 24).alpha == 1f }
                assertEquals(0, files.saves)
            }
        }

    @Test
    fun explicitProjectSaveReopenKeepsSlotIdentityAndIndexedPngSupportsExactAndQuantizedExports() =
        runBlocking {
            withSession {
                val first = controller.document.indexedPalette!!.argb(1)
                withContext(Dispatchers.Main) { controller.setIndexedColor(2, first) }
                waitFor { controller.document.indexedPalette!!.argb(2) == first }
                for ((slot, y) in listOf(1 to 12.5f, 2 to 24.5f)) {
                    click("Indexed color $slot")
                    stroke(Offset(8.5f, y), Offset(24.5f, y))
                }
                val palette = controller.document.indexedPalette
                val pixels = withContext(Dispatchers.Main) { pixels() }
                assertEquals(0, files.saves)
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
                waitFor { files.saved != null && !controller.hasUnsavedChanges }
                assertContentEquals(
                    "PODOR".encodeToByteArray() + byteArrayOf(12),
                    files.saved!!.copyOf(6),
                )
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                waitFor {
                    files.opens == 1 &&
                        !controller.document.canUndo &&
                        controller.document.indexedPalette == palette &&
                        controller.projectReference?.id == "indexed.podor"
                }
                withContext(Dispatchers.Main) {
                    assertContentEquals(pixels, pixels())
                    controller.export(ExportOptions(ExportFormat.IndexedPng, transparent = true))
                }
                waitFor { files.exports.size == 1 }
                val exact = files.exports.single()
                assertEquals(ExportFormat.IndexedPng, exact.first)
                val png = ImageIO.read(ByteArrayInputStream(exact.second))
                assertEquals(3, exact.second[25].toInt())
                val colors = assertIs<IndexColorModel>(png.colorModel)
                assertEquals(0, colors.getAlpha(0))
                assertEquals(1, png.raster.getSample(14, 12, 0))
                assertEquals(2, png.raster.getSample(14, 24, 0))
                assertEquals(0, png.raster.getSample(2, 2, 0))
                assertEquals(colors.getRGB(1), colors.getRGB(2))
                withContext(Dispatchers.Main) {
                    controller.setLayer(controller.document.layers.single().copy(opacity = 0.5f))
                }
                waitFor { controller.document.layers.single().opacity == 0.5f }
                val beforeExport = controller.document
                val frame = controller.frame
                withContext(Dispatchers.Main) {
                    controller.export(ExportOptions(ExportFormat.IndexedPng, transparent = true))
                }
                waitFor(allowError = true) { controller.error != null }
                assertEquals(1, files.exports.size)
                assertTrue(controller.error!!.contains("调色板"))
                withContext(Dispatchers.Main) {
                    controller.dismissError()
                    controller.export(
                        ExportOptions(
                            ExportFormat.IndexedPng,
                            transparent = true,
                            indexedPolicy = IndexedExportPolicy.Quantize,
                        )
                    )
                }
                waitFor { files.exports.size == 2 }
                val quantized = ImageIO.read(ByteArrayInputStream(files.exports.last().second))
                assertIs<IndexColorModel>(quantized.colorModel)
                assertEquals(64, quantized.width)
                assertEquals(48, quantized.height)
                assertEquals(beforeExport, controller.document)
                assertSame(frame, controller.frame)
                click("Convert to RGBA")
                waitFor { controller.document.colorMode == DocumentColorMode.Rgba }
                assertNull(controller.document.indexedPalette)
                assertFalse(ExportFormat.IndexedPng in controller.exportFormats)
                command("undo")
                waitFor { controller.document.colorMode == DocumentColorMode.Indexed }
                assertEquals(palette, controller.document.indexedPalette)
                assertEquals(1, files.saves)
            }
        }
}
