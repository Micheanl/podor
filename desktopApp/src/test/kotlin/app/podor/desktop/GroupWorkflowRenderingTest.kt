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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class)
class GroupWorkflowRenderingTest {

    private data class Exported(val bytes: ByteArray, val format: ExportFormat)

    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        val input = AtomicReference(project.copyOf())
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val exports = AtomicInteger()
        val opens = AtomicInteger()
        override val exportFormats = listOf(ExportFormat.Psd, ExportFormat.Ora)

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
            exported.set(Exported(bytes.copyOf(), format))
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

    private fun project(nested: Boolean = false): ByteArray {
        NativeLoader.load()
        val native = createNativeEngine(384, 256)
        var revision = 0L
        fun command(value: String) {
            val source = Json.parseToJsonElement(value).jsonObject
            val input = JsonObject(source + ("revision" to JsonPrimitive(revision)))
            val state =
                Json.parseToJsonElement(
                        native
                            .call(EngineOperation.COMMAND, input.toString().encodeToByteArray())
                            .decodeToString()
                    )
                    .jsonObject
            revision = state.getValue("revision").jsonPrimitive.long
        }
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String) {
            command(
                """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}"""
            )
            command("""{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""")
            command("""{"type":"select","rect":null}""")
        }
        try {
            command("""{"type":"set_layer","id":1,"name":"Anchor","visible":true,"opacity":1}""")
            fill(272, 144, 336, 208, "[80,144,96,255]")
            command("""{"type":"add_layer"}""")
            command("""{"type":"set_layer","id":2,"name":"Red","visible":true,"opacity":1}""")
            fill(16, 16, 96, 112, "[190,48,64,255]")
            command("""{"type":"add_layer"}""")
            command("""{"type":"set_layer","id":3,"name":"Blue","visible":true,"opacity":1}""")
            fill(48, 32, 128, 96, "[32,80,180,192]")
            if (nested) {
                command(
                    """{"type":"group_layers","ids":[2,3],"parent_id":null,"index":1,"name":"Outer"}"""
                )
                command(
                    """{"type":"group_layers","ids":[2,3],"parent_id":4,"index":0,"name":"Inner"}"""
                )
                command("""{"type":"select_layer","id":4}""")
            }
            return native.call(EngineOperation.SAVE).also {
                assertContentEquals("PODOR\u000c".encodeToByteArray(), it.copyOf(6))
            }
        } finally {
            native.close()
        }
    }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var frame = 0L
        private val remoteKey = (2L shl 32) or 1L

        fun render() = scene.render(frame++ * 16_666_667L)

        val canvasSize
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

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) =
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

        private fun matches(node: SemanticsNode, label: String): Boolean =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun hasLabel(label: String) = nodes().any { matches(it, label) }

        fun node(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode =
            nodes()
                .filter { node ->
                    node.config.contains(action) &&
                        !node.boundsInWindow.isEmpty &&
                        descendants(node).any { matches(it, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing rendered group control: $label")

        private fun textNode(label: String): SemanticsNode =
            nodes()
                .filter { matches(it, label) && !it.boundsInWindow.isEmpty }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing rendered group label: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) {
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }
        }

        suspend fun click(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val control = node(label)
                    assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                    control.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun layerAction(label: String) {
            click("Layer actions")
            click(label)
        }

        fun layer(id: Int) = controller.document.layers.first { it.id == id }

        suspend fun target(id: Int, mask: Boolean = false) {
            val current = layer(id)
            click(
                if (mask) "${trName(current.name)} · Edit mask"
                else if (current.mask != null) "${trName(current.name)} · Edit layer pixels"
                else trName(current.name)
            )
            waitFor { controller.document.active == id && controller.document.maskEditing == mask }
        }

        private fun trName(name: String) = if (name == "图层组") "Layer group" else name

        suspend fun rename(value: String) {
            val id = controller.document.active
            click("Layer settings")
            withContext(Dispatchers.Main) {
                assertTrue(
                    node("Layer name", SemanticsActions.SetText)
                        .config[SemanticsActions.SetText]
                        .action!!
                        .invoke(AnnotatedString(value))
                )
            }
            click("Save")
            waitFor { layer(id).name == value }
        }

        suspend fun opacity(value: Float) {
            val id = controller.document.active
            click(
                "${layer(id).blend.label.let { if (it == "正常") "Normal" else it }} · ${(layer(id).opacity * 100).toInt()}%"
            )
            waitFor { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                assertTrue(
                    node("Layer opacity", SemanticsActions.SetProgress)
                        .config[SemanticsActions.SetProgress]
                        .action!!
                        .invoke(value)
                )
            }
            waitFor {
                controller.adjustmentPreview?.let {
                    !it.updating && it.settings.opacity == value
                } == true
            }
            val preview = withContext(Dispatchers.Main) { scenePixels() }
            click("Apply adjustment")
            waitFor { controller.adjustmentPreview == null && layer(id).opacity == value }
            settle()
            withContext(Dispatchers.Main) { assertContentEquals(preview, scenePixels()) }
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

        fun scenePixels(): IntArray =
            render().use { image ->
                val source = IntArray(image.width * image.height)
                image.toComposeImageBitmap().readPixels(source)
                val points =
                    listOf(Offset(60.5f, 60.5f), Offset(84.5f, 88.5f), Offset(300.5f, 180.5f))
                IntArray(points.size) { i ->
                    val point =
                        controller.viewport.toView(
                            points[i],
                            canvasSize,
                            controller.document,
                        ) + canvasBounds().topLeft
                    source[point.y.toInt() * image.width + point.x.toInt()]
                }
            }

        suspend fun draw(color: Long, from: Offset, to: Offset) {
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.brush = controller.brush.copy(color = color, size = 3f)
                controller.tool = Tool.Brush
            }
            val revision = controller.document.revision
            fun view(point: Offset) =
                controller.viewport.toView(point, canvasSize, controller.document) +
                    canvasBounds().topLeft
            pointer(PointerEventType.Press, view(from))
            pointer(PointerEventType.Move, view(to))
            pointer(PointerEventType.Release, view(to))
            pointer(PointerEventType.Move, Offset.Zero)
            waitFor { controller.document.revision == revision + 1 }
        }

        suspend fun save(): ByteArray {
            val count = files.saves.get()
            click("Save project")
            waitFor { files.saves.get() == count + 1 && !controller.hasUnsavedChanges }
            return assertNotNull(files.saved.get()).copyOf()
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
        }

        suspend fun export(format: ExportFormat): ByteArray {
            val count = files.exports.get()
            click("Export image")
            click(format.label)
            click("Export")
            waitFor { files.exports.get() == count + 1 }
            return assertNotNull(files.exported.get())
                .also { assertEquals(format, it.format) }
                .bytes
                .copyOf()
        }

        suspend fun compareGroupMove(id: Int, mask: Boolean = false) {
            target(id, mask)
            val original = controller.frame
            val before = controller.document
            val dirty = controller.hasUnsavedChanges
            withContext(Dispatchers.Main) { controller.tool = Tool.MoveLayer }
            waitFor { controller.layerMove?.canonical != null }
            val preview = assertNotNull(controller.layerMove?.canonical)
            val offsets =
                listOf(IntOffset(11, 7), IntOffset(-5, 3), IntOffset.Zero, IntOffset(9, 5))
            for (offset in offsets) {
                withContext(Dispatchers.Main) { controller.previewLayerMove(offset) }
                waitFor {
                    preview.renderedAction?.get("action")?.jsonObject?.let {
                        it["dx"]?.jsonPrimitive?.int == offset.x &&
                            it["dy"]?.jsonPrimitive?.int == offset.y
                    } == true
                }
                withContext(Dispatchers.Main) {
                    assertEquals(before, controller.document)
                    assertEquals(dirty, controller.hasUnsavedChanges)
                    assertSame(original, controller.frame)
                    assertSame(
                        original.tiles.getValue(remoteKey).image,
                        preview.frame.tiles.getValue(remoteKey).image,
                    )
                    if (offset == IntOffset.Zero)
                        assertContentEquals(pixels(original), pixels(preview.frame))
                }
            }
            val expected = withContext(Dispatchers.Main) { pixels(preview.frame) }
            val rendered = withContext(Dispatchers.Main) { scenePixels() }
            withContext(Dispatchers.Main) { controller.commitLayerMove() }
            waitFor { controller.document.revision == before.revision + 1 }
            withContext(Dispatchers.Main) {
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.Brush
            }
            settle()
            withContext(Dispatchers.Main) {
                assertContentEquals(expected, pixels())
                assertContentEquals(rendered, scenePixels())
                assertSame(
                    original.tiles.getValue(remoteKey).image,
                    controller.frame.tiles.getValue(remoteKey).image,
                )
            }
            click("Undo")
            waitFor { controller.document.contentId == before.contentId }
            withContext(Dispatchers.Main) { assertContentEquals(pixels(original), pixels()) }
        }

        suspend fun dragBelow(source: String, target: String) {
            settle()
            val points =
                withContext(Dispatchers.Main) {
                    val from = textNode(source).boundsInWindow.center
                    val targetRow = node(target).boundsInWindow
                    from to Offset(from.x, targetRow.bottom - 2f)
                }
            pointer(PointerEventType.Press, points.first)
            delay(650)
            settle()
            pointer(PointerEventType.Move, points.second)
            settle()
            pointer(PointerEventType.Release, points.second)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }
    }

    private suspend fun withSession(nested: Boolean = false, block: suspend Session.() -> Unit) {
        val files = MemoryFiles(project(nested))
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1000, 824) {
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
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    CanvasWorkspace(controller, Modifier.fillMaxSize())
                                    if (controller.adjustmentPreview != null)
                                        AdjustmentDock(
                                            controller,
                                            Modifier.align(Alignment.BottomCenter)
                                                .padding(StudioTheme.workspacePadding),
                                            floating = true,
                                        )
                                }
                                Surface(
                                    Modifier.width(360.dp).fillMaxHeight(),
                                    color = StudioTheme.panel,
                                ) {
                                    Box(Modifier.padding(12.dp)) { LayerControls(controller) }
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
            session.waitFor { controller.hasCanvas && controller.document.width == 384 }
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
    fun emptyNestedGroupsCollapseWithoutChangingArtAndTheirMasksOpacityUndoAndSaveUseRealControls() =
        runBlocking {
            withSession {
                val original = withContext(Dispatchers.Main) { pixels() }
                val originalLeaves =
                    controller.document.layers.filter { it.kind == LayerKind.Raster }.map { it.id }
                layerAction("New group")
                waitFor { controller.document.layers.size == 4 }
                assertEquals(LayerKind.Group, layer(4).kind)
                rename("Outer")
                layerAction("New group")
                waitFor { controller.document.layers.size == 5 }
                assertEquals(4, layer(5).parentId)
                rename("Inner")
                click("Add layer")
                waitFor { controller.document.layers.size == 6 }
                assertEquals(5, layer(6).parentId)
                rename("Ink")
                assertEquals(
                    originalLeaves + 6,
                    controller.document.layers.filter { it.kind == LayerKind.Raster }.map { it.id },
                )
                assertEquals(4, controller.document.rasterLayerCount)
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                click("Collapse Inner")
                waitFor { layer(5).closed }
                withContext(Dispatchers.Main) {
                    assertEquals(
                        listOf(4, 5, 3, 2, 1),
                        controller.document.layerRows().map { it.id },
                    )
                    assertFalse(hasLabel("Ink"))
                    assertContentEquals(original, pixels())
                }
                click("Collapse Outer")
                waitFor { layer(4).closed }
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(4, 3, 2, 1), controller.document.layerRows().map { it.id })
                    assertFalse(hasLabel("Inner"))
                    assertContentEquals(original, pixels())
                }
                click("Expand Outer")
                waitFor { !layer(4).closed }
                click("Expand Inner")
                waitFor { !layer(5).closed }
                target(6)
                draw(0xFF3311CC, Offset(20.5f, 24.5f), Offset(72.5f, 24.5f))
                target(4)
                click("Add mask")
                click("Reveal all")
                waitFor { layer(4).mask != null && controller.document.maskEditing }
                val revealed = withContext(Dispatchers.Main) { pixels() }
                draw(0xFF000000, Offset(24.5f, 24.5f), Offset(64.5f, 24.5f))
                val hidden = withContext(Dispatchers.Main) { pixels() }
                assertFalse(revealed.contentEquals(hidden))
                click("Undo")
                waitFor { controller.document.canRedo }
                withContext(Dispatchers.Main) { assertContentEquals(revealed, pixels()) }
                click("Redo")
                waitFor { !controller.document.canRedo }
                withContext(Dispatchers.Main) { assertContentEquals(hidden, pixels()) }
                target(4)
                opacity(.5f)
                val expected = withContext(Dispatchers.Main) { pixels() }
                assertFalse(hidden.contentEquals(expected))
                val saved = save()
                assertContentEquals("PODOR\u000c".encodeToByteArray(), saved.copyOf(6))
                open(saved)
                assertEquals(.5f, layer(4).opacity)
                assertNotNull(layer(4).mask)
                assertEquals(5, layer(6).parentId)
                withContext(Dispatchers.Main) { assertContentEquals(expected, pixels()) }
            }
        }

    @Test
    fun selectingAdjacentLayersGroupsOnceAndSubtreeDragReparentAndAncestorLocksUseRealRows() =
        runBlocking {
            withSession {
                val original = withContext(Dispatchers.Main) { pixels() }
                val revision = controller.document.revision
                layerAction("Select layers")
                click("Red")
                click("Blue")
                layerAction("Group selected")
                waitFor { controller.document.layers.size == 4 }
                assertEquals(revision + 1, controller.document.revision)
                assertEquals(listOf(1, 4, 2, 3), controller.document.layers.map { it.id })
                assertEquals(4, layer(2).parentId)
                assertEquals(4, layer(3).parentId)
                withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                click("Undo")
                waitFor { controller.document.layers.size == 3 }
                assertEquals(listOf(1, 2, 3), controller.document.layers.map { it.id })
                click("Redo")
                waitFor { controller.document.layers.size == 4 }
                target(4)
                rename("Painting")
                val beforeDrag = controller.document.revision
                dragBelow("Painting", "Anchor")
                waitFor { controller.document.revision == beforeDrag + 1 }
                assertEquals(listOf(4, 2, 3, 1), controller.document.layers.map { it.id })
                assertEquals(listOf(1, 4, 3, 2), controller.document.uiOrder)
                target(1)
                layerAction("New group")
                waitFor { controller.document.layers.size == 5 }
                rename("Destination")
                target(4)
                layerAction("Move into · Destination")
                waitFor { layer(4).parentId == 5 }
                assertEquals(4, layer(2).parentId)
                assertEquals(4, layer(3).parentId)
                target(2)
                layerAction("Move out of group")
                waitFor { layer(2).parentId == 5 }
                target(5)
                click("Lock layer")
                waitFor { layer(5).locked && layer(2).effectiveLocked }
                target(2)
                val before = controller.document
                val lockedPixels = withContext(Dispatchers.Main) { pixels() }
                withContext(Dispatchers.Main) {
                    controller.selectPreset(BrushPreset.PixelPencil)
                    controller.tool = Tool.Brush
                }
                val point =
                    controller.viewport.toView(
                        Offset(40.5f, 40.5f),
                        canvasSize,
                        controller.document,
                    ) + canvasBounds().topLeft
                pointer(PointerEventType.Press, point)
                pointer(PointerEventType.Release, point)
                pointer(PointerEventType.Move, Offset.Zero)
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(before, controller.document)
                    assertContentEquals(lockedPixels, pixels())
                    assertNull(controller.error)
                }
                assertEquals(0, files.saves.get())
            }
        }

    @Test
    fun groupAndMaskMovesRenderCanonicalPixelsAndPreserveTheUnrelatedTile() = runBlocking {
        withSession(nested = true) {
            target(4)
            click("Add mask")
            click("Reveal all")
            waitFor { layer(4).mask != null && controller.document.maskEditing }
            draw(0xFF808080, Offset(24.5f, 48.5f), Offset(108.5f, 48.5f))
            target(5)
            click("Add mask")
            click("Reveal all")
            waitFor { layer(5).mask != null && controller.document.maskEditing }
            draw(0xFF000000, Offset(32.5f, 56.5f), Offset(100.5f, 56.5f))
            compareGroupMove(4)
            compareGroupMove(4, mask = true)
            assertEquals(0, files.saves.get())
        }
    }

    @Test
    fun partialGroupCannotLoseMetadataAndNestedPsdOraExportsReopenThroughTheRealFileWorker() =
        runBlocking {
            withSession(nested = true) {
                target(5)
                opacity(.5f)
                val partial = controller.document
                val partialPixels = withContext(Dispatchers.Main) { pixels() }
                click("Layer actions")
                withContext(Dispatchers.Main) {
                    assertTrue(node("Pass through").config.contains(SemanticsProperties.Disabled))
                    assertTrue(node("Ungroup").config.contains(SemanticsProperties.Disabled))
                }
                click("Layer actions")
                withContext(Dispatchers.Main) {
                    assertEquals(partial, controller.document)
                    assertContentEquals(partialPixels, pixels())
                }
                opacity(1f)
                target(4)
                layerAction("Pass through")
                waitFor { layer(4).isolation == GroupIsolation.PassThrough }
                val before = controller.document
                val expectedPixels = withContext(Dispatchers.Main) { pixels() }
                fun hierarchy(document: DocumentInfo): List<List<Any?>> {
                    val byId = document.layers.associateBy { it.id }
                    return document.layers.map {
                        listOf(
                            it.name,
                            it.kind,
                            byId[it.parentId]?.name,
                            it.isolation,
                            it.opacity,
                            it.visible,
                        )
                    }
                }
                val expectedHierarchy = hierarchy(before)
                for (format in listOf(ExportFormat.Psd, ExportFormat.Ora)) {
                    val bytes = export(format)
                    withContext(Dispatchers.Main) {
                        assertEquals(expectedHierarchy, hierarchy(controller.document))
                        assertContentEquals(expectedPixels, pixels())
                    }
                    open(bytes)
                    assertEquals(expectedHierarchy, hierarchy(controller.document))
                    withContext(Dispatchers.Main) { assertContentEquals(expectedPixels, pixels()) }
                    assertFalse(controller.document.canUndo)
                }
                assertEquals(2, files.exports.get())
                assertEquals(0, files.saves.get())
            }
        }
}
