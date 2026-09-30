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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
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

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MaskStackWorkflowRenderingTest {
    private val view = Size(640f, 880f)

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
        return Json.decodeFromString(
            engine
                .call(
                    EngineOperation.COMMAND,
                    JsonObject(source + ("revision" to state.getValue("revision")))
                        .toString()
                        .encodeToByteArray(),
                )
                .decodeToString()
        )
    }

    private suspend fun project(
        stack: Boolean = false,
        gray: Int = 128,
        indexed: Boolean = false,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(64, 32)
            try {
                command(
                    engine,
                    """{"type":"fill","x":0,"y":0,"color":[20,40,60,255],"tolerance":0}""",
                )
                command(
                    engine,
                    """{"type":"select","rect":{"left":32,"top":0,"right":64,"bottom":32}}""",
                )
                command(
                    engine,
                    """{"type":"fill","x":32,"y":0,"color":[220,160,80,255],"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
                command(
                    engine,
                    """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                )
                if (indexed) {
                    command(
                        engine,
                        """{"type":"new_indexed","width":64,"height":32,"palette":{"colors":[[0,0,0,0],[20,40,60,255],[20,40,60,255]],"transparent":0,"order":[0,2,1]}}""",
                    )
                    command(
                        engine,
                        """{"type":"fill_indexed","x":0,"y":0,"index":1,"tolerance":0}""",
                    )
                    command(
                        engine,
                        """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                    )
                }
                if (stack) {
                    command(
                        engine,
                        """{"type":"select","rect":{"left":8,"top":4,"right":48,"bottom":28}}""",
                    )
                    command(engine, """{"type":"add_mask","mode":"selection","name":"Stencil"}""")
                    command(engine, """{"type":"select","rect":null}""")
                    command(engine, """{"type":"add_mask","mode":"reveal","name":"Detail"}""")
                    command(
                        engine,
                        """{"type":"select","rect":{"left":16,"top":12,"right":24,"bottom":20}}""",
                    )
                    command(
                        engine,
                        """{"type":"fill","x":20,"y":16,"color":[$gray,$gray,$gray,255],"tolerance":0}""",
                    )
                    command(engine, """{"type":"select","rect":null}""")
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

    private suspend fun rawLeaf(bytes: ByteArray, id: Int = 1) =
        probe(bytes) { engine ->
            command(engine, """{"type":"set_mask_editing","id":$id,"enabled":false}""")
            engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
        }

    private suspend fun rawMasks(bytes: ByteArray, id: Int = 1): Map<Int, ByteArray> =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            state.layers
                .first { it.id == id }
                .masks
                .associate { mask ->
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":$id,"mask_id":${mask.id},"enabled":true}""",
                    )
                    mask.id to
                        ByteArray(state.width * state.height) { offset ->
                            Json.parseToJsonElement(
                                    engine
                                        .call(
                                            EngineOperation.COMMAND,
                                            """{"type":"pick","x":${offset % state.width},"y":${offset / state.width}}"""
                                                .encodeToByteArray(),
                                        )
                                        .decodeToString()
                                )
                                .jsonObject
                                .getValue("color")
                                .jsonArray
                                .first()
                                .jsonPrimitive
                                .int
                                .toByte()
                        }
                }
        }

    private suspend fun rawSlots(bytes: ByteArray): ByteArray =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"set_mask_editing","id":1,"enabled":false}""")
            ByteArray(state.width * state.height) { offset ->
                Json.parseToJsonElement(
                        engine
                            .call(
                                EngineOperation.COMMAND,
                                """{"type":"pick","x":${offset % state.width},"y":${offset / state.width}}"""
                                    .encodeToByteArray(),
                            )
                            .decodeToString()
                    )
                    .jsonObject
                    .getValue("index")
                    .jsonPrimitive
                    .int
                    .toByte()
            }
        }

    private fun assertMasks(expected: Map<Int, ByteArray>, actual: Map<Int, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (id, bytes) ->
            assertContentEquals(bytes, actual.getValue(id), "Mask $id")
        }
    }

    private fun imagePixels(image: ImageBitmap) =
        IntArray(image.width * image.height).also { image.readPixels(it) }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var time = 0L

        fun render() = scene.render(time++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) {
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
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
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing mask-stack control: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        private suspend fun clickPoint(point: Offset) {
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun click(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val control = node(label)
                    assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                    control.boundsInWindow.center
                }
            clickPoint(point)
        }

        suspend fun dismissMenu() = clickPoint(Offset(400f, 32f))

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

        fun layer(id: Int = 1) = controller.document.layers.first { it.id == id }

        fun mask(name: String) = layer().masks.first { it.name == name }

        suspend fun selectMask(name: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val card =
                        nodes().single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                descendants(it).any { child -> matches(child, name) }
                        }
                    assertFalse(card.config.contains(SemanticsProperties.Disabled))
                    Offset(card.boundsInWindow.center.x, card.boundsInWindow.top + 18f)
                }
            clickPoint(point)
            waitFor {
                controller.document.maskEditing &&
                    controller.document.activeMaskId == mask(name).id &&
                    controller.previews.maskLayerId == 1 &&
                    controller.previews.maskEntries.keys == layer().masks.map { it.id }.toSet()
            }
        }

        suspend fun option(name: String, label: String) {
            click("$name · Mask settings")
            click(label)
        }

        suspend fun rename(name: String, replacement: String) {
            val id = mask(name).id
            option(name, "Mask name")
            text("Mask name", replacement)
            click("Save")
            waitFor { layer().masks.any { it.id == id && it.name == replacement } }
        }

        suspend fun renameAt(index: Int, replacement: String) {
            val mask = layer().masks[index]
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val cards =
                        nodes()
                            .filter {
                                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                    descendants(it).any { child ->
                                        layer().masks.any { entry -> matches(child, entry.name) }
                                    }
                            }
                            .sortedBy { it.boundsInWindow.left }
                            .toList()
                    descendants(cards[index])
                        .filter {
                            it.config.contains(SemanticsActions.OnClick) &&
                                matches(it, "${mask.name} · Mask settings")
                        }
                        .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                        .boundsInWindow
                        .center
                }
            clickPoint(point)
            click("Mask name")
            text("Mask name", replacement)
            click("Save")
            waitFor { layer().masks.any { it.id == mask.id && it.name == replacement } }
        }

        suspend fun add(label: String): Int {
            val ids = layer().masks.map { it.id }.toSet()
            click(if (ids.isEmpty()) "Add mask" else "Mask settings")
            click(label)
            waitFor { layer().masks.size == ids.size + 1 && controller.document.maskEditing }
            return layer()
                .masks
                .single { it.id !in ids }
                .id
                .also {
                    assertEquals(it, controller.document.activeMaskId)
                    assertEquals(16, controller.document.maxLayerMasks)
                }
        }

        fun pixels(value: RenderFrame = controller.frame): IntArray =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in value.tiles.values) {
                    val source = imagePixels(tile.image)
                    val left = tile.x * tile.size
                    val top = tile.y * tile.size
                    val width = minOf(tile.size, controller.document.width - left)
                    for (y in 0 until minOf(tile.size, controller.document.height - top)) {
                        source.copyInto(
                            output,
                            (top + y) * controller.document.width + left,
                            y * tile.image.width,
                            y * tile.image.width + width,
                        )
                    }
                }
            }

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + Offset(0f, 64f)

        suspend fun stroke(gray: Int, point: Offset) {
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.brush =
                    controller.brush.copy(
                        color = 0xFF000000L or (gray.toLong() * 0x010101),
                        size = 1f,
                        opacity = 1f,
                    )
                controller.tool = Tool.Brush
            }
            pointer(PointerEventType.Press, position(point))
            pointer(PointerEventType.Release, position(point))
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun touch(pressed: Boolean, points: List<Offset>) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    if (pressed) PointerEventType.Press else PointerEventType.Release,
                    points.mapIndexed { index, point ->
                        ComposeScenePointer(
                            PointerId((index + 1).toLong()),
                            position(point),
                            pressed,
                            PointerType.Touch,
                            1f,
                        )
                    },
                )
                render().close()
            }

        suspend fun toggleBeside(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val bounds =
                        nodes()
                            .filter { matches(it, label) && !it.boundsInWindow.isEmpty }
                            .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                            .boundsInWindow
                    val toggle =
                        nodes()
                            .filter {
                                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                                    it.config.contains(SemanticsActions.OnClick) &&
                                    !it.config.contains(SemanticsProperties.Disabled) &&
                                    !it.boundsInWindow.isEmpty
                            }
                            .minBy { abs(it.boundsInWindow.center.y - bounds.center.y) }
                    assertTrue(abs(toggle.boundsInWindow.center.y - bounds.center.y) < 24f)
                    toggle.boundsInWindow.center
                }
            clickPoint(point)
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

    private suspend fun withSession(project: ByteArray, block: suspend Session.() -> Unit) {
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
                                    Box(Modifier.fillMaxWidth().height(80.dp))
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
    fun maskCardsRenameDuplicateDeleteAndGrayStrokeCancellationRoundTripEveryIdentity() =
        runBlocking {
            val source = project()
            val raw = rawLeaf(source)
            withSession(source) {
                val first = add("Reveal all")
                rename("Mask 1", "Ink")
                val firstRevision = controller.document.revision
                stroke(128, Offset(20.5f, 16.5f))
                waitFor { controller.document.revision == firstRevision + 1 }
                val painted = save()
                val firstGray = rawMasks(painted).getValue(first)
                assertEquals(128, firstGray[16 * 64 + 20].toInt() and 255)
                assertEquals(255, firstGray[16 * 64 + 19].toInt() and 255)
                assertContentEquals(raw, rawLeaf(painted))
                val beforeCancel = controller.document
                val beforeCancelPixels = withContext(Dispatchers.Main) { pixels() }
                withContext(Dispatchers.Main) {
                    controller.fingerDrawing = true
                    controller.brush = controller.brush.copy(color = 0xFF000000)
                }
                touch(true, listOf(Offset(30.5f, 16.5f)))
                waitFor { pixels()[16 * 64 + 30].ushr(24) == 0 }
                touch(true, listOf(Offset(30.5f, 16.5f), Offset(34.5f, 20.5f)))
                touch(false, listOf(Offset(30.5f, 16.5f), Offset(34.5f, 20.5f)))
                waitFor { pixels().contentEquals(beforeCancelPixels) }
                assertEquals(beforeCancel, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(painted, save())
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport()
                    controller.select(Selection(8, 4, 48, 28))
                }
                waitFor { controller.document.selection != null }
                val second = add("From selection")
                rename("Mask 2", "Window")
                withContext(Dispatchers.Main) { controller.select(null) }
                waitFor { controller.document.selection == null }
                selectMask("Ink")
                val originalMasks = layer().masks
                option("Ink", "Duplicate mask")
                waitFor { layer().masks.size == 3 }
                val copy = layer().masks.single { it.id !in originalMasks.map { item -> item.id } }
                assertEquals(listOf(first, copy.id, second), layer().masks.map { it.id })
                assertEquals(copy.id, controller.document.activeMaskId)
                renameAt(1, "Copy")
                val duplicated = save()
                val duplicateGray = rawMasks(duplicated)
                assertContentEquals(firstGray, duplicateGray.getValue(first))
                assertContentEquals(firstGray, duplicateGray.getValue(copy.id))
                option("Copy", "Delete mask")
                waitFor { layer().masks.size == 2 }
                click("Undo")
                waitFor { layer().masks.size == 3 }
                assertEquals(copy.id, mask("Copy").id)
                assertMasks(duplicateGray, rawMasks(save()))
                click("Redo")
                waitFor { layer().masks.size == 2 }
                selectMask("Window")
                val saved = save()
                val masks = layer().masks
                val gray = rawMasks(saved)
                val composite = withContext(Dispatchers.Main) { pixels() }
                assertEquals(setOf(first, second), gray.keys)
                assertContentEquals(firstGray, gray.getValue(first))
                assertEquals(255, gray.getValue(second)[16 * 64 + 32].toInt() and 255)
                assertEquals(0, gray.getValue(second)[0].toInt() and 255)
                assertContentEquals(raw, rawLeaf(saved))
                open(saved)
                assertEquals(masks, layer().masks)
                assertEquals(second, controller.document.activeMaskId)
                assertFalse(controller.document.maskEditing)
                selectMask("Window")
                assertTrue(controller.document.maskEditing)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                assertMasks(gray, rawMasks(save()))
                assertEquals(0, files.exports.get())
            }
        }

    @Test
    fun perMaskFlagsAndOrderingPreserveGrayBytesAndApplyingTheWholeStackIsOneUndo() = runBlocking {
        val source = project(stack = true)
        val raw = rawLeaf(source)
        val gray = rawMasks(source)
        withSession(source) {
            val before = withContext(Dispatchers.Main) { pixels() }
            val original = layer().masks
            val stencil = mask("Stencil").id
            val detail = mask("Detail").id
            option("Detail", "Disable mask")
            waitFor { !mask("Detail").enabled }
            val disabled = withContext(Dispatchers.Main) { pixels() }
            assertNotEquals(before[16 * 64 + 20], disabled[16 * 64 + 20])
            assertEquals(before[0], disabled[0])
            assertMasks(gray, rawMasks(save()))
            option("Detail", "Unlink mask")
            waitFor { !mask("Detail").linked }
            assertContentEquals(disabled, withContext(Dispatchers.Main) { pixels() })
            option("Detail", "Enable mask")
            waitFor { mask("Detail").enabled }
            assertContentEquals(before, withContext(Dispatchers.Main) { pixels() })
            option("Detail", "Move mask earlier")
            waitFor { layer().masks.map { it.id } == listOf(detail, stencil) }
            val reordered = layer().masks
            val orderedSave = save()
            assertMasks(gray, rawMasks(orderedSave))
            assertContentEquals(raw, rawLeaf(orderedSave))
            assertContentEquals(before, withContext(Dispatchers.Main) { pixels() })
            click("Undo")
            waitFor { layer().masks.map { it.id } == listOf(stencil, detail) }
            assertEquals(original.map { it.copy(linked = it.id != detail) }, layer().masks)
            click("Redo")
            waitFor { layer().masks == reordered }
            selectMask("Detail")
            val applyRevision = controller.document.revision
            click("Mask settings")
            click("Apply all masks")
            waitFor { layer().masks.isEmpty() && controller.document.revision == applyRevision + 1 }
            assertNull(layer().mask)
            assertNull(controller.document.activeMaskId)
            assertFalse(controller.document.maskEditing)
            assertContentEquals(before, withContext(Dispatchers.Main) { pixels() })
            assertFalse(raw.contentEquals(rawLeaf(save())))
            click("Undo")
            waitFor {
                layer().masks == reordered && controller.document.revision == applyRevision + 2
            }
            val restored = save()
            assertContentEquals(raw, rawLeaf(restored))
            assertMasks(gray, rawMasks(restored))
            assertContentEquals(before, withContext(Dispatchers.Main) { pixels() })
            open(restored)
            assertEquals(reordered, layer().masks)
            assertMasks(gray, rawMasks(save()))
            for (label in listOf("New group", "New adjustment layer · Tone & color")) {
                click("Source · Edit layer pixels")
                waitFor { controller.document.active == 1 && !controller.document.maskEditing }
                val oldIds = controller.document.layers.map { it.id }.toSet()
                click("Layer actions")
                click(label)
                waitFor { controller.document.layers.size == oldIds.size + 1 }
                val id = controller.document.active
                assertEquals(
                    if (label == "New group") LayerKind.Group else LayerKind.Adjustment,
                    layer(id).kind,
                )
                repeat(2) { index ->
                    click(if (index == 0) "Add mask" else "Mask settings")
                    click("Reveal all")
                    waitFor { layer(id).masks.size == index + 1 }
                }
                click("Mask settings")
                withContext(Dispatchers.Main) {
                    assertTrue(
                        node("Apply all masks").config.contains(SemanticsProperties.Disabled)
                    )
                }
                dismissMenu()
            }
        }
        val indexed = project(stack = true, indexed = true)
        val slots = rawSlots(indexed)
        val indexedGray = rawMasks(indexed)
        withSession(indexed) {
            val before = controller.document
            click("Mask settings")
            withContext(Dispatchers.Main) {
                assertTrue(node("Apply all masks").config.contains(SemanticsProperties.Disabled))
            }
            dismissMenu()
            assertEquals(before, controller.document)
            val saved = save()
            assertContentEquals(slots, rawSlots(saved))
            assertMasks(indexedGray, rawMasks(saved))
        }
    }

    @Test
    fun sameRevisionMaskSelectionDropsStaleMoveAndDraggingMovesOnlyItsPlaneWithExactPreview() =
        runBlocking {
            val source = project(stack = true)
            val raw = rawLeaf(source)
            val gray = rawMasks(source)
            withSession(source) {
                selectMask("Stencil")
                val stencil = mask("Stencil").id
                val detail = mask("Detail").id
                val stencilThumb =
                    withContext(Dispatchers.Main) {
                        imagePixels(controller.previews.maskEntries.getValue(stencil))
                    }
                assertContentEquals(
                    stencilThumb,
                    withContext(Dispatchers.Main) {
                        imagePixels(controller.previews.masks.getValue(1))
                    },
                )
                val image = controller.previews.images.getValue(1)
                withContext(Dispatchers.Main) { controller.tool = Tool.MoveLayer }
                waitFor { controller.layerMove?.maskId == stencil }
                val stale = assertNotNull(controller.layerMove)
                val revision = controller.document.revision
                withContext(Dispatchers.Main) { controller.previewLayerMove(IntOffset(3, 0)) }
                waitFor {
                    stale.canonical?.renderedAction?.get("mask_id")?.jsonPrimitive?.int ==
                        stencil &&
                        stale.canonical
                            ?.renderedAction
                            ?.get("action")
                            ?.jsonObject
                            ?.get("dx")
                            ?.jsonPrimitive
                            ?.int == 3
                }
                selectMask("Detail")
                waitFor { controller.layerMove?.maskId == detail }
                assertEquals(revision, controller.document.revision)
                assertFalse(controller.hasUnsavedChanges)
                assertNotSame(stale, controller.layerMove)
                assertEquals(IntOffset.Zero, controller.layerMove!!.offset)
                assertSame(image, controller.previews.images.getValue(1))
                val detailThumb =
                    withContext(Dispatchers.Main) {
                        imagePixels(controller.previews.maskEntries.getValue(detail))
                    }
                assertFalse(stencilThumb.contentEquals(detailThumb))
                assertContentEquals(
                    detailThumb,
                    withContext(Dispatchers.Main) {
                        imagePixels(controller.previews.masks.getValue(1))
                    },
                )
                val beforeFrame = controller.frame
                val start = Offset(20.5f, 16.5f)
                val end = start + Offset(3f, 2f)
                pointer(PointerEventType.Press, position(start))
                pointer(PointerEventType.Move, position(end))
                waitFor {
                    controller.layerMove
                        ?.canonical
                        ?.renderedAction
                        ?.get("action")
                        ?.jsonObject
                        ?.get("dx")
                        ?.jsonPrimitive
                        ?.int == 3 &&
                        controller.layerMove
                            ?.canonical
                            ?.renderedAction
                            ?.get("action")
                            ?.jsonObject
                            ?.get("dy")
                            ?.jsonPrimitive
                            ?.int == 2
                }
                val preview =
                    withContext(Dispatchers.Main) {
                        assertEquals(IntOffset(3, 2), controller.layerMove!!.offset)
                        assertEquals(detail, controller.layerMove!!.maskId)
                        assertEquals(revision, controller.document.revision)
                        assertSame(beforeFrame, controller.frame)
                        pixels(assertNotNull(controller.layerMove!!.canonical).frame)
                    }
                pointer(PointerEventType.Release, position(end))
                pointer(PointerEventType.Move, Offset.Zero)
                waitFor {
                    controller.document.revision == revision + 1 &&
                        controller.layerMove?.offset != IntOffset(3, 2)
                }
                assertContentEquals(preview, withContext(Dispatchers.Main) { pixels() })
                val moved = save()
                val movedGray = rawMasks(moved)
                assertContentEquals(raw, rawLeaf(moved))
                assertContentEquals(gray.getValue(stencil), movedGray.getValue(stencil))
                assertFalse(gray.getValue(detail).contentEquals(movedGray.getValue(detail)))
                val expected =
                    ByteArray(64 * 32) { offset ->
                        val x = offset % 64 - 3
                        val y = offset / 64 - 2
                        if (x in 0 until 64 && y in 0 until 32) gray.getValue(detail)[y * 64 + x]
                        else 255.toByte()
                    }
                assertContentEquals(expected, movedGray.getValue(detail))
                val frame = controller.frame
                val thumbnails = controller.previews.maskEntries
                val maskProjection = controller.previews.masks.getValue(1)
                settle()
                assertSame(frame, controller.frame)
                frame.tiles.forEach { (key, tile) ->
                    assertSame(tile.image, controller.frame.tiles.getValue(key).image)
                }
                thumbnails.forEach { (id, thumb) ->
                    assertSame(thumb, controller.previews.maskEntries.getValue(id))
                }
                assertSame(maskProjection, controller.previews.masks.getValue(1))
                click("Undo")
                waitFor { controller.document.revision == revision + 2 }
                assertMasks(gray, rawMasks(save()))
                click("Redo")
                waitFor { controller.document.revision == revision + 3 }
                assertMasks(movedGray, rawMasks(save()))
            }
        }

    @Test
    fun editablePsdAndOraRejectTheStackWhileExplicitFlattenedCopiesReopenWithoutMutatingSource() =
        runBlocking {
            val source = project(stack = true, gray = 0)
            val raw = rawLeaf(source)
            val gray = rawMasks(source)
            withSession(source) {
                val original = controller.document.layers
                val composite = withContext(Dispatchers.Main) { pixels() }
                for (format in listOf(ExportFormat.Psd, ExportFormat.Ora)) {
                    probe(source) { engine ->
                        val before = engine.call(EngineOperation.SAVE)
                        val failure = assertFails {
                            engine.call(
                                EngineOperation.EXPORT_IMAGE,
                                Json.encodeToString(
                                        ExportOptions(
                                            format,
                                            transparent = true,
                                            bakeLayers = false,
                                        )
                                    )
                                    .encodeToByteArray(),
                            )
                        }
                        assertTrue(failure.message.orEmpty().contains("蒙版"), failure.message)
                        assertContentEquals(before, engine.call(EngineOperation.SAVE))
                    }
                    val before = controller.document
                    val frame = controller.frame
                    val saves = files.saves.get()
                    val exports = files.exports.get()
                    click("Export image")
                    click(format.label)
                    withContext(Dispatchers.Main) {
                        assertTrue(node("Export").config.contains(SemanticsProperties.Disabled))
                    }
                    toggleBeside("Export a flattened copy")
                    withContext(Dispatchers.Main) {
                        assertFalse(node("Export").config.contains(SemanticsProperties.Disabled))
                    }
                    click("Export")
                    waitFor { files.exports.get() == exports + 1 }
                    val exported = assertNotNull(files.exported.get())
                    assertEquals(format, exported.format)
                    assertEquals(before, controller.document)
                    assertSame(frame, controller.frame)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(saves, files.saves.get())
                    open(exported.bytes)
                    assertEquals(1, controller.document.layers.size)
                    assertEquals(LayerKind.Raster, controller.document.layers.single().kind)
                    assertTrue(controller.document.layers.single().masks.isEmpty())
                    assertNull(controller.document.layers.single().mask)
                    assertFalse(controller.document.maskEditing)
                    assertFalse(controller.document.canUndo)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    open(source)
                    assertEquals(original, controller.document.layers)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    val restored = save()
                    assertContentEquals(raw, rawLeaf(restored))
                    assertMasks(gray, rawMasks(restored))
                }
            }
        }
}
