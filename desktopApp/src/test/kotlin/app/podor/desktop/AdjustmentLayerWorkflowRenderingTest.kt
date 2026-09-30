package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.NativeEngine
import app.podor.engine.createNativeEngine
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class)
class AdjustmentLayerWorkflowRenderingTest {

    private data class Exported(val format: ExportFormat, val bytes: ByteArray)

    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        val input = AtomicReference(project.copyOf())
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val opens = AtomicInteger()
        val exports = AtomicInteger()
        override val exportFormats = listOf(ExportFormat.Png, ExportFormat.Psd, ExportFormat.Ora)

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            opens.incrementAndGet()
            return input.get().copyOf()
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            assertFalse(png)
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return true
        }

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            exported.set(Exported(format, bytes.copyOf()))
            exports.incrementAndGet()
            return true
        }

        override suspend fun readPreferences(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return Json.encodeToString(
                    Preferences(language = Language.English, appearance = Appearance.Light)
                )
                .encodeToByteArray()
        }

        override suspend fun writePreferences(bytes: ByteArray) {
            assertFalse(EventQueue.isDispatchThread())
        }
    }

    private fun command(engine: NativeEngine, value: String): DocumentInfo {
        val state =
            Json.parseToJsonElement(
                    engine
                        .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                        .decodeToString()
                )
                .jsonObject
        val source = Json.parseToJsonElement(value).jsonObject
        val input = JsonObject(source + ("revision" to state.getValue("revision")))
        return Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, input.toString().encodeToByteArray())
                .decodeToString()
        )
    }

    private suspend fun project(
        nested: Boolean = false,
        indexed: Boolean = false,
        opaque: Boolean = false,
        clippingChain: Boolean = false,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(64, 32)
            fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String, index: Int = 1) {
                command(
                    engine,
                    """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}""",
                )
                command(
                    engine,
                    if (indexed)
                        """{"type":"fill_indexed","x":$left,"y":$top,"index":$index,"tolerance":0}"""
                    else """{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
            }
            try {
                if (indexed) {
                    command(
                        engine,
                        """{"type":"new_indexed","width":64,"height":32,"palette":{"colors":[[0,0,0,0],[20,40,60,255],[20,40,60,255],[120,80,40,128]],"transparent":0,"order":[0,2,1,3]}}""",
                    )
                    fill(0, 0, 32, 32, "[]", 1)
                    fill(32, 0, 64, 32, "[]", 2)
                    fill(0, 24, 16, 32, "[]", 0)
                    fill(16, 24, 32, 32, "[]", 3)
                } else {
                    fill(0, 0, 20, 32, "[20,40,60,${if (opaque) 255 else 128}]")
                    fill(20, 0, 44, 32, "[128,96,64,${if (opaque) 255 else 200}]")
                    fill(44, 0, 64, 32, "[220,200,180,255]")
                }
                command(
                    engine,
                    """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                )
                if (nested) {
                    command(
                        engine,
                        """{"type":"create_group","name":"Painting","parent_id":null,"index":0}""",
                    )
                    command(engine, """{"type":"move_node","id":1,"parent_id":2,"index":0}""")
                    command(engine, """{"type":"select_layer","id":1}""")
                    command(engine, """{"type":"add_layer"}""")
                    command(
                        engine,
                        """{"type":"set_layer","id":3,"name":"Accent","visible":true,"opacity":1}""",
                    )
                    fill(40, 16, 56, 28, "[30,180,100,96]")
                    if (clippingChain) {
                        command(engine, """{"type":"set_clipping","id":3,"clipping":true}""")
                        command(engine, """{"type":"add_layer"}""")
                        command(
                            engine,
                            """{"type":"set_layer","id":4,"name":"Finish","visible":true,"opacity":1}""",
                        )
                        fill(32, 8, 48, 20, "[180,30,120,192]")
                        command(engine, """{"type":"set_clipping","id":4,"clipping":true}""")
                    }
                    command(engine, """{"type":"select_layer","id":1}""")
                }
                engine.call(EngineOperation.SAVE).also {
                    assertContentEquals("PODOR\u000c".encodeToByteArray(), it.copyOf(6))
                }
            } finally {
                engine.close()
            }
        }

    private suspend fun <T> probe(bytes: ByteArray, block: (NativeEngine) -> T): T =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            val engine = createNativeEngine(1, 1)
            try {
                engine.call(EngineOperation.LOAD, bytes)
                block(engine)
            } finally {
                engine.close()
            }
        }

    private suspend fun rawLeaf(bytes: ByteArray, id: Int) =
        probe(bytes) { engine ->
            command(engine, """{"type":"select_layer","id":$id}""")
            engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
        }

    private suspend fun rawValues(bytes: ByteArray, id: Int, mask: Boolean = false) =
        probe(bytes) { engine ->
            command(engine, """{"type":"select_layer","id":$id}""")
            if (mask) command(engine, """{"type":"set_mask_editing","id":$id,"enabled":true}""")
            IntArray(64 * 32) { offset ->
                val picked =
                    Json.parseToJsonElement(
                            engine
                                .call(
                                    EngineOperation.COMMAND,
                                    """{"type":"pick","x":${offset % 64},"y":${offset / 64}}"""
                                        .encodeToByteArray(),
                                )
                                .decodeToString()
                        )
                        .jsonObject
                if (mask) picked.getValue("color").jsonArray.first().jsonPrimitive.int
                else picked.getValue("index").jsonPrimitive.int
            }
        }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var time = 0L

        fun render() = scene.render(time++ * 16_666_667L)

        val view
            get() = canvasBounds().size

        fun canvasBounds(): Rect =
            nodes()
                .filter {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" &&
                        it.boundsInWindow.width > 100f &&
                        it.boundsInWindow.height > 100f
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                .boundsInWindow

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) {
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        if (!allowError) assertNull(controller.error)
                    }
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
        }

        suspend fun settle() {
            repeat(30) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        private fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun node(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode =
            nodes()
                .filter {
                    it.config.contains(action) &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.size.width * it.size.height }
                ?: error("Missing adjustment-layer control: $label")

        private suspend fun reveal(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode {
            repeat(24) {
                val visible =
                    withContext(Dispatchers.Main) {
                        val target = node(label, action)
                        val containers =
                            nodes()
                                .filter {
                                    it.config.contains(
                                        SemanticsProperties.VerticalScrollAxisRange
                                    ) && descendants(it).any { child -> child.id == target.id }
                                }
                                .toList()
                        if (containers.isEmpty()) {
                            assertFalse(target.boundsInWindow.isEmpty, label)
                            return@withContext target
                        }
                        assertEquals(1, containers.size, "Adjustment control has nested scrolling")
                        val viewport = containers.single().boundsInWindow
                        val top = target.positionInWindow.y
                        val bottom = top + target.size.height
                        if (
                            !target.boundsInWindow.isEmpty &&
                                top >= viewport.top - 1f &&
                                bottom <= viewport.bottom + 1f
                        )
                            return@withContext target
                        scene.sendPointerEvent(
                            PointerEventType.Scroll,
                            viewport.center,
                            scrollDelta = Offset(0f, if (top < viewport.top) -4f else 4f),
                        )
                        null
                    }
                if (visible != null) return visible
                settle()
            }
            error("Adjustment control did not scroll fully into view: $label")
        }

        private fun textNode(label: String) =
            nodes()
                .filter { matches(it, label) && !it.boundsInWindow.isEmpty }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing adjustment-layer label: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        suspend fun click(label: String) {
            settle()
            val control = reveal(label)
            val point =
                withContext(Dispatchers.Main) {
                    assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                    control.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun slider(label: String, value: Float) {
            val control = reveal(label, SemanticsActions.SetProgress)
            withContext(Dispatchers.Main) {
                assertTrue(
                    assertNotNull(control.config[SemanticsActions.SetProgress].action)(value)
                )
            }
            previewReady()
            settle()
        }

        suspend fun dragSlider(label: String) {
            settle()
            val control = reveal(label, SemanticsActions.SetProgress)
            val bounds =
                withContext(Dispatchers.Main) {
                    control.boundsInWindow
                }
            val start = Offset(bounds.center.x, bounds.center.y)
            val end = Offset(bounds.left + bounds.width * 0.75f, bounds.center.y)
            pointer(PointerEventType.Press, start)
            pointer(PointerEventType.Move, end)
            pointer(PointerEventType.Release, end)
            pointer(PointerEventType.Move, Offset.Zero)
            previewReady()
        }

        suspend fun text(label: String, value: String) {
            val control = reveal(label, SemanticsActions.SetText)
            withContext(Dispatchers.Main) {
                assertTrue(
                    assertNotNull(control.config[SemanticsActions.SetText].action)(
                        AnnotatedString(value)
                    )
                )
            }
            previewReady()
            settle()
        }

        suspend fun toggleBeside(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val labelBounds = textNode(label).boundsInWindow
                    val toggle =
                        nodes()
                            .filter {
                                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                                    it.config.contains(SemanticsActions.OnClick) &&
                                    !it.config.contains(SemanticsProperties.Disabled) &&
                                    !it.boundsInWindow.isEmpty
                            }
                            .minByOrNull { abs(it.boundsInWindow.center.y - labelBounds.center.y) }
                            ?: error("Missing switch beside: $label")
                    assertTrue(abs(toggle.boundsInWindow.center.y - labelBounds.center.y) < 24f)
                    toggle.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        fun layer(id: Int) = controller.document.layers.first { it.id == id }

        suspend fun create(kind: AdjustmentKind): Int {
            val count = controller.document.layers.size
            click("Layer actions")
            click("New adjustment layer · ${name(kind)}")
            waitFor { controller.document.layers.size == count + 1 }
            return controller.document.active.also {
                assertEquals(LayerKind.Adjustment, layer(it).kind)
                assertEquals(kind, layer(it).adjustment?.kind)
                assertFalse(controller.document.maskEditing)
            }
        }

        suspend fun edit() {
            click("Edit adjustment layer")
            previewReady()
            assertTrue(controller.adjustmentPreview!!.nodeEditing)
        }

        suspend fun previewReady() = waitFor { controller.adjustmentPreview?.updating == false }

        suspend fun target(id: Int, mask: Boolean = false) {
            val layer = layer(id)
            val label =
                when {
                    mask -> "${displayName(layer.name)} · Edit mask"
                    layer.mask != null -> "${displayName(layer.name)} · Edit layer pixels"
                    else -> displayName(layer.name)
                }
            click(label)
            waitFor { controller.document.active == id && controller.document.maskEditing == mask }
        }

        fun pixels(value: RenderFrame = controller.frame): IntArray =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in value.tiles.values) {
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

        fun maskPixels(id: Int): IntArray {
            val image = assertNotNull(controller.previews.masks[id])
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }

        fun scenePixel(x: Int, y: Int): Int {
            val point =
                controller.viewport.toView(Offset(x + 0.5f, y + 0.5f), view, controller.document) +
                    canvasBounds().topLeft
            return render().use { image ->
                val pixels = IntArray(image.width * image.height)
                image.toComposeImageBitmap().readPixels(pixels)
                pixels[point.y.toInt() * image.width + point.x.toInt()]
            }
        }

        suspend fun stroke(color: Long, x: Float, y: Float) {
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.brush = controller.brush.copy(color = color, size = 1f, opacity = 1f)
                controller.tool = Tool.Brush
            }
            val point =
                controller.viewport.toView(Offset(x, y), view, controller.document) +
                    canvasBounds().topLeft
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun save(): ByteArray {
            val count = files.saves.get()
            click("Save project")
            waitFor { files.saves.get() == count + 1 && !controller.hasUnsavedChanges }
            return assertNotNull(files.saved.get()).copyOf().also {
                assertContentEquals("PODOR\u000c".encodeToByteArray(), it.copyOf(6))
            }
        }

        suspend fun open(bytes: ByteArray) {
            files.input.set(bytes.copyOf())
            val count = files.opens.get()
            click("Project")
            click("Open project / image")
            click("Choose file")
            waitFor { files.opens.get() == count + 1 || controller.pendingNavigation != null }
            if (controller.pendingNavigation != null) click("Don't save")
            waitFor { files.opens.get() == count + 1 }
            settle()
        }
    }

    private fun name(kind: AdjustmentKind) =
        when (kind) {
            AdjustmentKind.Tone -> "Tone & color"
            AdjustmentKind.Curves -> "Curves"
            AdjustmentKind.GradientMap -> "Gradient map"
            else -> error("Not a live adjustment layer")
        }

    private fun displayName(value: String) =
        when (value) {
            "明暗与色彩" -> "Tone & color"
            "曲线" -> "Curves"
            "渐变映射" -> "Gradient map"
            else -> value
        }

    private suspend fun withSession(
        project: ByteArray,
        block: suspend Session.() -> Unit,
    ) {
        val originalAppearance = StudioTheme.appearance
        val files = MemoryFiles(project)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1000, 1024) {
                    var dialog by remember { mutableStateOf(StudioDialog.None) }
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Column {
                            StudioHeader(
                                controller,
                                compact = false,
                                showDocument = true,
                                onDialog = { dialog = it },
                            )
                            Row(Modifier.weight(1f)) {
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
                                    Box(Modifier.padding(12.dp)) {
                                        if (controller.adjustmentPreview != null) {
                                            Column { AdjustmentControls(controller) }
                                        } else LayerControls(controller)
                                    }
                                }
                            }
                        }
                        StudioDialogs(controller, dialog) { dialog = StudioDialog.None }
                        UnsavedChangesDialog(controller)
                    }
                }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 64 }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.close()
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
    fun selectionMaskToneControlsPreviewCancelApplyUndoAndVersionTenSaveKeepSourcePixelsIntact() =
        runBlocking {
            val source = project()
            val raw = rawLeaf(source, 1)
            withSession(source) {
                withContext(Dispatchers.Main) { controller.select(Selection(16, 4, 48, 28)) }
                waitFor { controller.document.selection != null }
                val selectionId = controller.document.selectionId
                withContext(Dispatchers.Main) {
                    controller.refineSelection(SelectionRefinement.Feather, 2)
                }
                waitFor { controller.document.selectionId > selectionId }
                val original = withContext(Dispatchers.Main) { pixels() }
                val revision = controller.document.revision
                val id = create(AdjustmentKind.Tone)
                assertEquals(revision + 1, controller.document.revision)
                assertNotNull(layer(id).mask)
                assertEquals(1, controller.document.rasterLayerCount)
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                withContext(Dispatchers.Main) { controller.clearSelection() }
                waitFor { controller.document.selection == null }
                val before = controller.document
                val frame = controller.frame
                val sceneOriginal = withContext(Dispatchers.Main) { scenePixel(32, 16) }
                edit()
                dragSlider("Brightness")
                assertTrue(controller.adjustmentPreview!!.settings.brightness > 0.15f)
                withContext(Dispatchers.Main) {
                    assertSame(frame, controller.frame)
                    assertEquals(before, controller.document)
                    assertNotEquals(sceneOriginal, scenePixel(32, 16))
                }
                click("Compare original")
                withContext(Dispatchers.Main) { assertEquals(sceneOriginal, scenePixel(32, 16)) }
                click("Cancel adjustment")
                waitFor { controller.adjustmentPreview == null }
                assertEquals(before, controller.document)
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                edit()
                slider("Brightness", 0.25f)
                slider("Saturation", -1f)
                val preview =
                    withContext(Dispatchers.Main) { pixels(controller.adjustmentPreview!!.frame) }
                assertEquals(original[16 * 64 + 4], preview[16 * 64 + 4])
                assertNotEquals(original[16 * 64 + 32], preview[16 * 64 + 32])
                original.zip(preview).forEach { (a, b) -> assertEquals(a ushr 24, b ushr 24) }
                click("Apply adjustment")
                waitFor {
                    controller.adjustmentPreview == null &&
                        controller.document.revision == before.revision + 1
                }
                assertEquals(0.25f, layer(id).adjustment!!.brightness)
                withContext(Dispatchers.Main) { assertContentEquals(preview, pixels()) }
                click("Undo")
                waitFor { layer(id).adjustment!!.brightness == 0f }
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                click("Redo")
                waitFor { layer(id).adjustment!!.brightness == 0.25f }
                withContext(Dispatchers.Main) { assertContentEquals(preview, pixels()) }
                assertEquals(0, files.saves.get())
                val expectedLayers = controller.document.layers
                val saved = save()
                assertContentEquals(raw, rawLeaf(saved, 1))
                val mask = rawValues(saved, id, mask = true)
                assertTrue(mask.any { it in 1..254 })
                assertEquals(0, mask[16 * 64 + 4])
                assertEquals(255, mask[16 * 64 + 32])
                val maskPreview = withContext(Dispatchers.Main) { maskPixels(id) }
                open(saved)
                assertEquals(expectedLayers, controller.document.layers)
                assertFalse(controller.document.canUndo)
                withContext(Dispatchers.Main) {
                    assertContentEquals(preview, pixels())
                    assertContentEquals(maskPreview, maskPixels(id))
                }
            }
        }

    @Test
    fun groupClippedCurvesAndGradientMapEditorsKeepHiddenMetadataUndoableAndRoundTripMasks() =
        runBlocking {
            val source = project(nested = true)
            val raw = listOf(1, 3).associateWith { rawLeaf(source, it) }
            withSession(source) {
                val curves = create(AdjustmentKind.Curves)
                assertEquals(2, layer(curves).parentId)
                click("Clip to layer below")
                waitFor { layer(curves).clipping && layer(curves).clippingBase == 1 }
                edit()
                text("Output", "64")
                val curved =
                    withContext(Dispatchers.Main) { pixels(controller.adjustmentPreview!!.frame) }
                click("Apply adjustment")
                waitFor {
                    controller.adjustmentPreview == null &&
                        layer(curves).adjustment!!.curves.rgb.points.first().y == 64
                }
                withContext(Dispatchers.Main) { assertContentEquals(curved, pixels()) }
                click("Hide 曲线")
                waitFor { !layer(curves).visible }
                val hidden = withContext(Dispatchers.Main) { pixels() }
                val hiddenRevision = controller.document.revision
                edit()
                text("Output", "96")
                withContext(Dispatchers.Main) {
                    assertTrue(controller.adjustmentPreview!!.changed)
                    assertContentEquals(hidden, pixels(controller.adjustmentPreview!!.frame))
                }
                click("Apply adjustment")
                waitFor {
                    controller.adjustmentPreview == null &&
                        controller.document.revision == hiddenRevision + 1
                }
                assertEquals(96, layer(curves).adjustment!!.curves.rgb.points.first().y)
                withContext(Dispatchers.Main) { assertContentEquals(hidden, pixels()) }
                click("Undo")
                waitFor { layer(curves).adjustment!!.curves.rgb.points.first().y == 64 }
                assertFalse(layer(curves).visible)
                click("Redo")
                waitFor { layer(curves).adjustment!!.curves.rgb.points.first().y == 96 }
                withContext(Dispatchers.Main) { assertContentEquals(hidden, pixels()) }
                click("Show 曲线")
                waitFor { layer(curves).visible }
                val map = create(AdjustmentKind.GradientMap)
                assertEquals(2, layer(map).parentId)
                click("Clip to layer below")
                waitFor { layer(map).clipping && layer(map).clippingBase == 1 }
                edit()
                click("Add color stop")
                slider("Stop position", 0.35f)
                slider("Hue", 330f)
                slider("Saturation", 1f)
                slider("Value", 0.8f)
                click("Reverse gradient")
                previewReady()
                assertEquals(3, controller.adjustmentPreview!!.settings.gradientMap.stops.size)
                val preview =
                    withContext(Dispatchers.Main) { pixels(controller.adjustmentPreview!!.frame) }
                click("Apply adjustment")
                waitFor {
                    controller.adjustmentPreview == null &&
                        layer(map).adjustment!!.gradientMap.stops.size == 3
                }
                withContext(Dispatchers.Main) { assertContentEquals(preview, pixels()) }
                click("Add mask")
                click("Reveal all")
                waitFor { controller.document.maskEditing && layer(map).mask != null }
                target(map)
                val expectedLayers = controller.document.layers
                val saved = save()
                for ((id, original) in raw) assertContentEquals(original, rawLeaf(saved, id))
                open(saved)
                assertEquals(expectedLayers, controller.document.layers)
                assertEquals(2, controller.document.rasterLayerCount)
                withContext(Dispatchers.Main) { assertContentEquals(preview, pixels()) }
                assertNotNull(controller.previews.masks[map])
                assertEquals(0, files.exports.get())
            }
        }

    @Test
    fun indexedLiveAdjustmentPreservesEveryRawSlotAndPaletteAndOnlyItsGrayMaskCanBePainted() =
        runBlocking {
            val source = project(indexed = true)
            val indices = rawValues(source, 1)
            assertEquals(1, indices[16 * 64 + 20])
            assertEquals(2, indices[16 * 64 + 40])
            withSession(source) {
                val palette = controller.document.indexedPalette
                val id = create(AdjustmentKind.Tone)
                edit()
                slider("Brightness", 0.25f)
                val adjusted =
                    withContext(Dispatchers.Main) { pixels(controller.adjustmentPreview!!.frame) }
                click("Apply adjustment")
                waitFor {
                    controller.adjustmentPreview == null &&
                        layer(id).adjustment!!.brightness == 0.25f
                }
                assertEquals(DocumentColorMode.Indexed, controller.document.colorMode)
                assertEquals(palette, controller.document.indexedPalette)
                val beforePaint = controller.document
                stroke(0xFF000000, 20.5f, 16.5f)
                assertEquals(beforePaint, controller.document)
                withContext(Dispatchers.Main) {
                    assertContentEquals(adjusted, pixels())
                    assertNull(controller.error)
                }
                click("Add mask")
                click("Reveal all")
                waitFor { controller.document.maskEditing && layer(id).mask != null }
                val revision = controller.document.revision
                stroke(0xFF000000, 20.5f, 16.5f)
                waitFor { controller.document.revision == revision + 1 }
                val masked = withContext(Dispatchers.Main) { pixels() }
                assertEquals(0xFF14283C.toInt(), masked[16 * 64 + 20])
                assertNotEquals(adjusted[16 * 64 + 20], masked[16 * 64 + 20])
                assertEquals(adjusted[16 * 64 + 21], masked[16 * 64 + 21])
                assertEquals(palette, controller.document.indexedPalette)
                click("Undo")
                waitFor { controller.document.revision == revision + 2 }
                withContext(Dispatchers.Main) { assertContentEquals(adjusted, pixels()) }
                click("Redo")
                waitFor { controller.document.revision == revision + 3 }
                withContext(Dispatchers.Main) { assertContentEquals(masked, pixels()) }
                target(id)
                val saved = save()
                assertContentEquals(indices, rawValues(saved, 1))
                val mask = rawValues(saved, id, mask = true)
                assertEquals(0, mask[16 * 64 + 20])
                assertEquals(255, mask[16 * 64 + 21])
                probe(saved) { engine ->
                    val brush =
                        """{"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[20,40,60],"eraser":false,"raster":"pixel","index":1}}"""
                    command(engine, """{"type":"select_layer","id":1}""")
                    command(engine, brush)
                    command(engine, """{"type":"cancel"}""")
                    command(engine, """{"type":"select_layer","id":$id}""")
                    val before = engine.call(EngineOperation.SAVE)
                    val error = assertFails {
                        command(engine, brush)
                    }
                    assertTrue(error.message.orEmpty().contains("像素图层"), error.message)
                    assertContentEquals(before, engine.call(EngineOperation.SAVE))
                }
                open(saved)
                assertEquals(palette, controller.document.indexedPalette)
                assertEquals(DocumentColorMode.Indexed, controller.document.colorMode)
                withContext(Dispatchers.Main) { assertContentEquals(masked, pixels()) }
                assertEquals(0, files.exports.get())
            }
        }

    @Test
    fun psdAndOraRequireExplicitBakedCopyAndTheirReopenedCompositeKeepsTheLiveSourceUntouched() =
        runBlocking {
            val source = project(opaque = true)
            withSession(source) {
                val id = create(AdjustmentKind.GradientMap)
                edit()
                click("Reverse gradient")
                previewReady()
                click("Apply adjustment")
                waitFor { controller.adjustmentPreview == null && layer(id).adjustment != null }
                val live = controller.document
                val pixels = withContext(Dispatchers.Main) { pixels() }
                val original = save()
                for (format in listOf(ExportFormat.Psd, ExportFormat.Ora)) {
                    probe(original) { engine ->
                        val before = engine.call(EngineOperation.SAVE)
                        val failure = assertFails {
                            engine.call(
                                EngineOperation.EXPORT_IMAGE,
                                Json.encodeToString(ExportOptions(format, transparent = true))
                                    .encodeToByteArray(),
                            )
                        }
                        assertTrue(failure.message.orEmpty().contains("调整图层"))
                        assertContentEquals(before, engine.call(EngineOperation.SAVE))
                    }
                    val beforeExport = controller.document
                    val beforeFrame = controller.frame
                    val count = files.exports.get()
                    click("Export image")
                    click(format.label)
                    withContext(Dispatchers.Main) {
                        assertTrue(node("Export").config.contains(SemanticsProperties.Disabled))
                    }
                    toggleBeside("Export a flattened copy")
                    click("Export")
                    waitFor { files.exports.get() == count + 1 }
                    val baked =
                        assertNotNull(files.exported.get())
                            .also { assertEquals(format, it.format) }
                            .bytes
                    withContext(Dispatchers.Main) {
                        assertEquals(beforeExport, controller.document)
                        assertSame(beforeFrame, controller.frame)
                        assertContentEquals(pixels, pixels())
                        assertFalse(controller.hasUnsavedChanges)
                        assertEquals(beforeExport.canUndo, controller.document.canUndo)
                        assertEquals(beforeExport.canRedo, controller.document.canRedo)
                    }
                    assertEquals(1, files.saves.get())
                    open(baked)
                    assertEquals(1, controller.document.layers.size)
                    assertEquals(LayerKind.Raster, controller.document.layers.single().kind)
                    assertNull(controller.document.layers.single().adjustment)
                    assertFalse(controller.document.canUndo)
                    withContext(Dispatchers.Main) { assertContentEquals(pixels, pixels()) }
                    open(original)
                    assertEquals(live.layers, controller.document.layers)
                    withContext(Dispatchers.Main) { assertContentEquals(pixels, pixels()) }
                }
                assertEquals(2, files.exports.get())
                assertEquals(1, files.saves.get())
            }
        }

    @Test
    fun creationAboveAClippingBaseInsertsToneAndGroupAfterTheWholeChainWithoutChangingPixels() =
        runBlocking {
            val source = project(nested = true, clippingChain = true)
            withSession(source) {
                val original = withContext(Dispatchers.Main) { pixels() }
                val ids = controller.document.layers.map { it.id }
                val clipIds = controller.document.layers.filter { it.clipping }.map { it.id }
                assertEquals(listOf(3, 4), clipIds)
                assertEquals(listOf(1, 3, 4), controller.document.siblings(2).map { it.id })
                for (group in listOf(false, true)) {
                    target(1)
                    val before = controller.document
                    val leaves = before.layers.filter { it.kind == LayerKind.Raster }
                    val created =
                        if (group) {
                            click("Layer actions")
                            click("New group")
                            waitFor { controller.document.layers.size == before.layers.size + 1 }
                            controller.document.active
                        } else create(AdjustmentKind.Tone)
                    assertEquals(before.revision + 1, controller.document.revision)
                    assertEquals(before.rasterLayerCount, controller.document.rasterLayerCount)
                    assertEquals(2, layer(created).parentId)
                    assertEquals(
                        if (group) LayerKind.Group else LayerKind.Adjustment,
                        layer(created).kind,
                    )
                    assertFalse(layer(created).clipping)
                    assertEquals(
                        listOf(1, 3, 4, created),
                        controller.document.siblings(2).map { it.id },
                    )
                    assertEquals(
                        clipIds,
                        controller.document.layers.filter { it.clipping }.map { it.id },
                    )
                    assertEquals(
                        leaves,
                        controller.document.layers.filter { it.kind == LayerKind.Raster },
                    )
                    for (id in clipIds) assertEquals(1, layer(id).clippingBase)
                    withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                    assertTrue(controller.document.canUndo)
                    assertTrue(controller.hasUnsavedChanges)
                    click("Undo")
                    waitFor {
                        controller.document.layers.map { it.id } == ids &&
                            controller.document.contentId == before.contentId
                    }
                    assertEquals(before.layers, controller.document.layers)
                    assertEquals(before.active, controller.document.active)
                    assertFalse(controller.document.canUndo)
                    assertFalse(controller.hasUnsavedChanges)
                    withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                    assertNull(controller.error)
                }
                assertEquals(0, files.saves.get())
                assertEquals(0, files.exports.get())
            }
        }
}
