package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
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
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class VectorWorkflowRenderingTest {

    private data class Exported(val format: ExportFormat, val bytes: ByteArray)

    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        val input = AtomicReference(project.copyOf())
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val opens = AtomicInteger()
        val exports = AtomicInteger()
        override val exportFormats =
            listOf(ExportFormat.Png, ExportFormat.Psd, ExportFormat.Ora, ExportFormat.Svg)

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

    private fun query(engine: NativeEngine, value: String): ByteArray {
        val state =
            Json.parseToJsonElement(
                    engine
                        .call(
                            EngineOperation.COMMAND,
                            """{"type":"state"}""".encodeToByteArray(),
                        )
                        .decodeToString()
                )
                .jsonObject
        val source = Json.parseToJsonElement(value).jsonObject
        return engine.call(
            EngineOperation.COMMAND,
            JsonObject(source + ("revision" to state.getValue("revision")))
                .toString()
                .encodeToByteArray(),
        )
    }

    private fun command(engine: NativeEngine, value: String): DocumentInfo =
        Json.decodeFromString(query(engine, value).decodeToString())

    private suspend fun project(): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(128, 96)
            try {
                command(
                    engine,
                    """{"type":"select","rect":{"left":0,"top":0,"right":32,"bottom":96}}""",
                )
                command(
                    engine,
                    """{"type":"fill","x":0,"y":0,"color":[20,40,60,255],"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
                command(
                    engine,
                    """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                )
                val firstMask =
                    command(engine, """{"type":"add_mask","mode":"reveal","name":"First"}""")
                        .activeMaskId!!
                command(engine, """{"type":"add_mask","mode":"reveal","name":"Second"}""")
                command(
                    engine,
                    """{"type":"select","rect":{"left":8,"top":8,"right":16,"bottom":16}}""",
                )
                command(
                    engine,
                    """{"type":"fill","x":10,"y":10,"color":[0,0,0,255],"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
                command(
                    engine,
                    """{"type":"set_mask_editing","id":1,"mask_id":$firstMask,"enabled":false}""",
                )
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

    private suspend fun rawRaster(bytes: ByteArray) =
        probe(bytes) { engine ->
            command(
                engine,
                """{"type":"set_mask_editing","id":1,"enabled":false}""",
            )
            engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
        }

    private suspend fun rawMasks(bytes: ByteArray): Map<Int, ByteArray> =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            state.layers
                .first { it.id == 1 }
                .masks
                .associate { mask ->
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":1,"mask_id":${mask.id},"enabled":true}""",
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

    private fun assertMasks(expected: Map<Int, ByteArray>, actual: Map<Int, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (id, bytes) ->
            assertContentEquals(bytes, actual.getValue(id), "Mask $id")
        }
    }

    private suspend fun objects(bytes: ByteArray, id: Int): Map<Int, VectorObjectSpec> =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            val before = engine.call(EngineOperation.SAVE)
            val list =
                Json.decodeFromString<VectorObjects>(
                    query(engine, """{"type":"vector_objects","id":$id}""").decodeToString()
                )
            list.objects
                .associate { summary ->
                    val result =
                        Json.decodeFromString<VectorObjectResult>(
                            query(
                                    engine,
                                    """{"type":"vector_object","id":$id,"object_id":${summary.id}}""",
                                )
                                .decodeToString()
                        )
                    summary.id to result.`object`
                }
                .also {
                    assertContentEquals(before, engine.call(EngineOperation.SAVE))
                    assertEquals(state, command(engine, """{"type":"state"}"""))
                }
        }

    private suspend fun picked(bytes: ByteArray, id: Int, point: Offset): Int? =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            val before = engine.call(EngineOperation.SAVE)
            val result =
                Json.parseToJsonElement(
                        query(
                                engine,
                                """{"type":"pick_vector_object","id":$id,"x":${point.x},"y":${point.y},"tolerance":4}""",
                            )
                            .decodeToString()
                    )
                    .jsonObject["object_id"]
                    ?.jsonPrimitive
                    ?.intOrNull
            assertContentEquals(before, engine.call(EngineOperation.SAVE))
            assertEquals(state, command(engine, """{"type":"state"}"""))
            result
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

        suspend fun waitFor(predicate: () -> Boolean) =
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
                                controller.previews.revision == controller.document.revision &&
                                (controller.document.layers.none {
                                    it.id == controller.document.active &&
                                        it.kind == LayerKind.Vector
                                } ||
                                    (controller.vectorObjects?.id == controller.document.active &&
                                        controller.vectorObjects?.revision ==
                                            controller.document.revision &&
                                        (controller.selectedVectorObject == null ||
                                            controller.selectedVectorObject?.revision ==
                                                controller.document.revision)))
                        }
                    )
                        break
                }
            }

        suspend fun settle() {
            repeat(20) {
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
                ?: error("Missing vector control: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
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

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + canvasBounds().topLeft

        suspend fun fit() =
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(zoom = 4f / Viewport().scale(view, controller.document))
                assertEquals(4f, controller.viewport.scale(view, controller.document))
                for (point in listOf(Offset(20f, 30f), Offset(60f, 50f), Offset(112f, 52f))) {
                    assertEquals(
                        point,
                        controller.viewport.toDocument(
                            controller.viewport.toView(point, view, controller.document),
                            view,
                            controller.document,
                        ),
                    )
                }
            }

        suspend fun canvasClick(point: Offset) {
            pointer(PointerEventType.Press, position(point))
            pointer(PointerEventType.Release, position(point))
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun dragStart(start: Offset, end: Offset) {
            pointer(PointerEventType.Press, position(start))
            pointer(PointerEventType.Move, position(end))
            previewReady()
        }

        suspend fun dragEnd(end: Offset) {
            pointer(PointerEventType.Release, position(end))
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun previewReady() = waitFor {
            controller.vectorPreview?.let {
                it.value.valid() && it.canonical.renderedAction == it.request()
            } == true
        }

        suspend fun touch(type: PointerEventType, pressed: Boolean, points: List<Offset>) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    type,
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

        fun layer(id: Int = controller.document.active) =
            controller.document.layers.first { it.id == id }

        fun selected() = assertNotNull(controller.selectedVectorObject).`object`

        fun objectIds() = assertNotNull(controller.vectorObjects).objects.map { it.id }

        suspend fun selectObject(id: Int) {
            val name = controller.vectorObjects!!.objects.single { it.id == id }.name
            val label =
                when (name) {
                    "钢笔路径" -> "Pen path"
                    "矩形" -> "Rectangle"
                    "椭圆" -> "Ellipse"
                    "直线" -> "Line"
                    else -> name
                }
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val card =
                        nodes().single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                descendants(it).any { child -> matches(child, label) }
                        }
                    assertFalse(card.config.contains(SemanticsProperties.Disabled))
                    card.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            waitFor {
                controller.selectedVectorObject?.objectId == id &&
                    controller.selectedVectorObject?.revision == controller.document.revision
            }
        }

        fun pixels(frame: RenderFrame = controller.frame): IntArray =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in frame.tiles.values) {
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

        suspend fun create(): Int {
            val before = controller.document
            click("Layer actions")
            click("New vector layer")
            waitFor {
                controller.document.layers.size == before.layers.size + 1 &&
                    controller.vectorObjects?.id == controller.document.active
            }
            assertEquals(before.revision + 1, controller.document.revision)
            assertEquals(LayerKind.Vector, layer().kind)
            assertEquals(Tool.Vector, controller.tool)
            assertEquals(VectorEditorTool.Rectangle, controller.vectorTool)
            assertEquals(1024, controller.document.maxVectorObjects)
            assertEquals(4096, controller.document.maxVectorSegments)
            assertTrue(objectIds().isEmpty())
            return controller.document.active
        }

        suspend fun shape(
            label: String,
            start: Offset,
            end: Offset,
            expected: VectorGeometry,
        ): Int {
            click(label)
            val revision = controller.document.revision
            val count = objectIds().size
            val beforeFrame = controller.frame
            dragStart(start, end)
            val preview =
                withContext(Dispatchers.Main) {
                    val value = assertNotNull(controller.vectorPreview)
                    assertEquals(expected, value.value.geometry)
                    assertEquals(revision, controller.document.revision)
                    assertSame(beforeFrame, controller.frame)
                    pixels(value.canonical.frame)
                }
            dragEnd(end)
            waitFor {
                controller.document.revision == revision + 1 &&
                    objectIds().size == count + 1 &&
                    controller.selectedVectorObject?.revision == controller.document.revision
            }
            assertEquals(expected, selected().geometry)
            assertContentEquals(preview, withContext(Dispatchers.Main) { pixels() })
            return assertNotNull(controller.selectedVectorObject).objectId
        }

        suspend fun pen(): Int {
            click("Pen path")
            pointer(PointerEventType.Press, position(Offset(20f, 30f)))
            pointer(PointerEventType.Move, position(Offset(26f, 24f)))
            dragEnd(Offset(26f, 24f))
            dragStart(Offset(60f, 50f), Offset(68f, 56f))
            dragEnd(Offset(68f, 56f))
            val revision = controller.document.revision
            val expected =
                VectorGeometry.Path(
                    listOf(
                        VectorSegment.Move(20f, 30f),
                        VectorSegment.Cubic(26f, 24f, 52f, 44f, 60f, 50f),
                    )
                )
            assertEquals(expected, controller.vectorPreview!!.value.geometry)
            val preview =
                withContext(Dispatchers.Main) { pixels(controller.vectorPreview!!.canonical.frame) }
            click("Confirm vector edit")
            waitFor {
                controller.document.revision == revision + 1 &&
                    controller.vectorPreview == null &&
                    controller.selectedVectorObject?.revision == controller.document.revision
            }
            assertEquals(expected, selected().geometry)
            assertContentEquals(preview, withContext(Dispatchers.Main) { pixels() })
            return assertNotNull(controller.selectedVectorObject).objectId
        }

        suspend fun editNode(start: Offset, end: Offset, expected: VectorGeometry) {
            val before = controller.document.revision
            val objectId = controller.selectedVectorObject!!.objectId
            dragStart(start, end)
            val preview =
                withContext(Dispatchers.Main) {
                    assertEquals(expected, controller.vectorPreview!!.value.geometry)
                    assertEquals(objectId, controller.vectorPreview!!.objectId)
                    assertEquals(before, controller.document.revision)
                    pixels(controller.vectorPreview!!.canonical.frame)
                }
            dragEnd(end)
            waitFor {
                controller.document.revision == before + 1 &&
                    controller.selectedVectorObject?.revision == controller.document.revision
            }
            assertEquals(expected, selected().geometry)
            assertContentEquals(preview, withContext(Dispatchers.Main) { pixels() })
        }

        suspend fun option(label: String) {
            click("Vector object settings")
            click(label)
        }

        suspend fun toggleBeside(label: String) {
            settle()
            repeat(6) {
                val point =
                    withContext(Dispatchers.Main) {
                        val root =
                            scene.semanticsOwners
                                .map { it.rootSemanticsNode }
                                .single { descendants(it).any { child -> matches(child, label) } }
                        descendants(root)
                            .filter {
                                it.config.contains(SemanticsActions.ScrollBy) &&
                                    it.config.contains(
                                        SemanticsProperties.VerticalScrollAxisRange
                                    ) &&
                                    !it.boundsInWindow.isEmpty &&
                                    descendants(it).any { child -> matches(child, label) }
                            }
                            .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                            ?.takeIf {
                                val axis = it.config[SemanticsProperties.VerticalScrollAxisRange]
                                axis.value() < axis.maxValue()
                            }
                            ?.boundsInWindow
                            ?.center
                    }
                if (point != null) {
                    withContext(Dispatchers.Main) {
                        scene.sendPointerEvent(
                            PointerEventType.Scroll,
                            point,
                            scrollDelta = Offset(0f, 12f),
                        )
                        render().close()
                    }
                    settle()
                }
            }
            val point =
                withContext(Dispatchers.Main) {
                    val root =
                        scene.semanticsOwners
                            .map { it.rootSemanticsNode }
                            .single { descendants(it).any { child -> matches(child, label) } }
                    val bounds =
                        descendants(root)
                            .filter { matches(it, label) && !it.boundsInWindow.isEmpty }
                            .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                            .boundsInWindow
                    val toggle =
                        descendants(root)
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
            fit()
        }
    }

    private suspend fun withSession(bytes: ByteArray, block: suspend Session.() -> Unit) {
        val originalAppearance = StudioTheme.appearance
        val files = MemoryFiles(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1600, 1200) {
                    StudioApp(controller)
                    UnsavedChangesDialog(controller)
                }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 128 }
            session.click("Show panel")
            session.click("Layers")
            session.fit()
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
    fun realVectorCreationAndTypedShapeDragsCommitOneUndoAndCancellationPreservesRasterMasks() =
        runBlocking {
            val source = project()
            val raw = rawRaster(source)
            val masks = rawMasks(source)
            withSession(source) {
                val raster = layer(1)
                val original = withContext(Dispatchers.Main) { pixels() }
                val vector = create()
                assertContentEquals(original, withContext(Dispatchers.Main) { pixels() })
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF75325A, size = 2f)
                }
                val definitions =
                    listOf(
                        Triple(
                            "Rectangle",
                            Offset(40f, 10f) to Offset(65f, 30f),
                            VectorGeometry.Rectangle(40f, 10f, 25f, 20f),
                        ),
                        Triple(
                            "Ellipse",
                            Offset(80f, 20f) to Offset(112f, 52f),
                            VectorGeometry.Ellipse(96f, 36f, 16f, 16f),
                        ),
                        Triple(
                            "Line",
                            Offset(40f, 70f) to Offset(100f, 80f),
                            VectorGeometry.Line(40f, 70f, 100f, 80f),
                        ),
                    )
                val specs = linkedMapOf<Int, VectorObjectSpec>()
                for ((label, points, geometry) in definitions) {
                    val revision = controller.document.revision
                    val before = withContext(Dispatchers.Main) { pixels() }
                    val ids = objectIds()
                    val id = shape(label, points.first, points.second, geometry)
                    val after = withContext(Dispatchers.Main) { pixels() }
                    specs[id] = selected()
                    assertFalse(before.contentEquals(after))
                    click("Undo")
                    waitFor { controller.document.revision == revision + 2 && objectIds() == ids }
                    assertContentEquals(before, withContext(Dispatchers.Main) { pixels() })
                    click("Redo")
                    waitFor {
                        controller.document.revision == revision + 3 && objectIds() == ids + id
                    }
                    assertContentEquals(after, withContext(Dispatchers.Main) { pixels() })
                }
                val saved = save()
                val savedSpecs = objects(saved, vector)
                assertEquals(specs, savedSpecs)
                assertEquals(specs.keys.toList(), savedSpecs.keys.toList())
                assertEquals(raster, layer(1))
                assertContentEquals(raw, rawRaster(saved))
                assertMasks(masks, rawMasks(saved))
                click("Rectangle")
                val before = controller.document
                val frame = withContext(Dispatchers.Main) { pixels() }
                withContext(Dispatchers.Main) { controller.fingerDrawing = true }
                touch(PointerEventType.Press, true, listOf(Offset(45f, 50f)))
                touch(PointerEventType.Move, true, listOf(Offset(70f, 65f)))
                previewReady()
                assertFalse(
                    frame.contentEquals(
                        withContext(Dispatchers.Main) {
                            pixels(controller.vectorPreview!!.canonical.frame)
                        }
                    )
                )
                touch(PointerEventType.Press, true, listOf(Offset(70f, 65f), Offset(90f, 70f)))
                touch(PointerEventType.Release, false, listOf(Offset(70f, 65f), Offset(90f, 70f)))
                waitFor { controller.vectorPreview == null }
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(frame, withContext(Dispatchers.Main) { pixels() })
                assertContentEquals(saved, save())
                assertEquals(specs, objects(files.saved.get(), vector))
                assertEquals(0, files.exports.get())
            }
        }

    @Test
    fun penAnchorsRemainDraftUntilConfirmAndRealAnchorControlDragsPreviewCommitOrCancelExactly() =
        runBlocking {
            val source = project()
            val raw = rawRaster(source)
            val masks = rawMasks(source)
            withSession(source) {
                val vector = create()
                val empty = save()
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF000000, size = 2f)
                }
                click("Pen path")
                val before = controller.document
                val frame = withContext(Dispatchers.Main) { pixels() }
                val saves = files.saves.get()
                pointer(PointerEventType.Press, position(Offset(20f, 30f)))
                pointer(PointerEventType.Move, position(Offset(26f, 24f)))
                dragEnd(Offset(26f, 24f))
                assertEquals(
                    VectorGeometry.Path(listOf(VectorSegment.Move(20f, 30f))),
                    controller.vectorPendingPath,
                )
                assertNull(controller.vectorPreview)
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(frame, withContext(Dispatchers.Main) { pixels() })
                assertEquals(saves, files.saves.get())
                assertContentEquals(empty, files.saved.get())
                withContext(Dispatchers.Main) {
                    assertTrue(
                        node("Confirm vector edit").config.contains(SemanticsProperties.Disabled)
                    )
                }
                dragStart(Offset(60f, 50f), Offset(68f, 56f))
                dragEnd(Offset(68f, 56f))
                val path =
                    VectorGeometry.Path(
                        listOf(
                            VectorSegment.Move(20f, 30f),
                            VectorSegment.Cubic(26f, 24f, 52f, 44f, 60f, 50f),
                        )
                    )
                assertEquals(path, controller.vectorPreview!!.value.geometry)
                assertEquals(before, controller.document)
                click("Cancel vector edit")
                waitFor { controller.vectorPreview == null && controller.vectorPendingPath == null }
                assertEquals(before, controller.document)
                assertContentEquals(frame, withContext(Dispatchers.Main) { pixels() })
                val id = pen()
                val initial = selected()
                val painted = withContext(Dispatchers.Main) { pixels() }
                assertEquals(before.revision + 1, controller.document.revision)
                click("Undo")
                waitFor { objectIds().isEmpty() }
                assertContentEquals(frame, withContext(Dispatchers.Main) { pixels() })
                assertFalse(controller.hasUnsavedChanges)
                click("Redo")
                waitFor { objectIds() == listOf(id) }
                selectObject(id)
                assertContentEquals(painted, withContext(Dispatchers.Main) { pixels() })
                click("Edit nodes")
                val movedAnchor =
                    path.copy(
                        segments =
                            listOf(
                                VectorSegment.Move(20f, 30f),
                                VectorSegment.Cubic(26f, 24f, 52f, 44f, 66f, 54f),
                            )
                    )
                editNode(Offset(60f, 50f), Offset(66f, 54f), movedAnchor)
                val movedControl =
                    movedAnchor.copy(
                        segments =
                            listOf(
                                VectorSegment.Move(20f, 30f),
                                VectorSegment.Cubic(26f, 24f, 49f, 38f, 66f, 54f),
                            )
                    )
                editNode(Offset(52f, 44f), Offset(49f, 38f), movedControl)
                val saved = save()
                assertEquals(
                    initial.copy(geometry = movedControl),
                    objects(saved, vector).getValue(id),
                )
                assertContentEquals(raw, rawRaster(saved))
                assertMasks(masks, rawMasks(saved))
                val unchanged = controller.document
                val unchangedPixels = withContext(Dispatchers.Main) { pixels() }
                withContext(Dispatchers.Main) { controller.fingerDrawing = true }
                touch(PointerEventType.Press, true, listOf(Offset(49f, 38f)))
                touch(PointerEventType.Move, true, listOf(Offset(45f, 34f)))
                previewReady()
                touch(PointerEventType.Press, true, listOf(Offset(45f, 34f), Offset(80f, 70f)))
                touch(PointerEventType.Release, false, listOf(Offset(45f, 34f), Offset(80f, 70f)))
                waitFor { controller.vectorPreview == null }
                assertEquals(unchanged, controller.document)
                assertContentEquals(unchangedPixels, withContext(Dispatchers.Main) { pixels() })
                assertContentEquals(saved, save())
            }
        }

    @Test
    fun coveragePickingStyleControlsWidthReleaseReorderAndDeleteKeepTypedObjectsAndReuseIdleImages() =
        runBlocking {
            val source = project()
            val raw = rawRaster(source)
            val masks = rawMasks(source)
            withSession(source) {
                val vector = create()
                val rectangle =
                    shape(
                        "Rectangle",
                        Offset(40f, 60f),
                        Offset(65f, 80f),
                        VectorGeometry.Rectangle(40f, 60f, 25f, 20f),
                    )
                val rectanglePixels = withContext(Dispatchers.Main) { pixels() }
                val ellipse =
                    shape(
                        "Ellipse",
                        Offset(72f, 12f),
                        Offset(120f, 60f),
                        VectorGeometry.Ellipse(96f, 36f, 24f, 24f),
                    )
                val originals = objects(save(), vector)
                click("Edit nodes")
                withContext(Dispatchers.Main) { controller.selectVectorObject(null) }
                waitFor { controller.selectedVectorObject == null }
                val beforePick = controller.document
                canvasClick(Offset(72.5f, 12.5f))
                assertNull(controller.selectedVectorObject)
                assertEquals(beforePick, controller.document)
                assertNull(picked(files.saved.get(), vector, Offset(72.5f, 12.5f)))
                canvasClick(Offset(96f, 36f))
                waitFor { controller.selectedVectorObject?.objectId == ellipse }
                assertEquals(ellipse, picked(files.saved.get(), vector, Offset(96f, 36f)))
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF3877C5, size = 2f)
                }
                var revision = controller.document.revision
                click("Fill color")
                waitFor { controller.document.revision == revision + 1 }
                assertEquals(listOf(56, 119, 197, 255), selected().style.fill)
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFFA45A22)
                }
                revision = controller.document.revision
                click("Stroke color")
                waitFor { controller.document.revision == revision + 1 }
                assertEquals(listOf(164, 90, 34, 255), selected().style.stroke?.color)
                assertEquals(listOf(56, 119, 197, 255), selected().style.fill)
                val styled = selected()
                val beforeWidth = withContext(Dispatchers.Main) { pixels() }
                revision = controller.document.revision
                val bounds =
                    withContext(Dispatchers.Main) {
                        node("Stroke width", SemanticsActions.SetProgress).boundsInWindow
                    }
                val start = Offset(bounds.left + 20f, bounds.center.y)
                val end = Offset(bounds.left + bounds.width * 0.12f, bounds.center.y)
                pointer(PointerEventType.Press, start)
                pointer(PointerEventType.Move, end)
                previewReady()
                val widthSpec = controller.vectorPreview!!.value
                assertTrue(widthSpec.style.stroke!!.width > styled.style.stroke!!.width)
                assertEquals(revision, controller.document.revision)
                val widthPixels =
                    withContext(Dispatchers.Main) {
                        pixels(controller.vectorPreview!!.canonical.frame)
                    }
                pointer(PointerEventType.Release, end)
                pointer(PointerEventType.Move, Offset.Zero)
                waitFor {
                    controller.document.revision == revision + 1 && controller.vectorPreview == null
                }
                assertEquals(widthSpec, selected())
                assertContentEquals(widthPixels, withContext(Dispatchers.Main) { pixels() })
                click("Undo")
                waitFor { controller.document.revision == revision + 2 }
                assertEquals(styled, selected())
                assertContentEquals(beforeWidth, withContext(Dispatchers.Main) { pixels() })
                click("Redo")
                waitFor { controller.document.revision == revision + 3 }
                assertEquals(widthSpec, selected())
                assertContentEquals(widthPixels, withContext(Dispatchers.Main) { pixels() })
                for ((label, check) in
                    listOf<Pair<String, (VectorObjectSpec) -> Boolean>>(
                        "Line cap · Square" to { it.style.stroke?.cap == VectorCap.Square },
                        "Line join · Bevel" to { it.style.stroke?.join == VectorJoin.Bevel },
                        "Even-odd fill" to { it.style.fillRule == VectorFillRule.EvenOdd },
                    )) {
                    val before = controller.document.revision
                    option(label)
                    waitFor { controller.document.revision == before + 1 && check(selected()) }
                }
                val final = selected()
                assertEquals(originals.getValue(ellipse).geometry, final.geometry)
                val beforeOrder = withContext(Dispatchers.Main) { pixels() }
                revision = controller.document.revision
                option("Move object down")
                waitFor {
                    controller.document.revision == revision + 1 &&
                        objectIds() == listOf(ellipse, rectangle)
                }
                assertContentEquals(beforeOrder, withContext(Dispatchers.Main) { pixels() })
                click("Delete vector object")
                waitFor {
                    objectIds() == listOf(rectangle) && controller.selectedVectorObject == null
                }
                assertContentEquals(rectanglePixels, withContext(Dispatchers.Main) { pixels() })
                click("Undo")
                waitFor { objectIds() == listOf(ellipse, rectangle) }
                assertContentEquals(beforeOrder, withContext(Dispatchers.Main) { pixels() })
                val saved = save()
                val savedSpecs = objects(saved, vector)
                assertEquals(
                    mapOf(rectangle to originals.getValue(rectangle), ellipse to final),
                    savedSpecs,
                )
                assertEquals(listOf(ellipse, rectangle), savedSpecs.keys.toList())
                assertContentEquals(raw, rawRaster(saved))
                assertMasks(masks, rawMasks(saved))
                val frame = controller.frame
                val images = controller.previews.images
                val objectList = controller.vectorObjects
                settle()
                assertSame(frame, controller.frame)
                frame.tiles.forEach { (key, tile) ->
                    assertSame(tile.image, controller.frame.tiles.getValue(key).image)
                }
                images.forEach { (id, image) ->
                    assertSame(image, controller.previews.images.getValue(id))
                }
                assertSame(objectList, controller.vectorObjects)
            }
        }

    @Test
    fun podorTenKeepsEditableCurvesAndNormalSvgWhileExplicitPsdOraCopiesPreserveSourceAndAlpha() =
        runBlocking {
            val source = project()
            val raw = rawRaster(source)
            val masks = rawMasks(source)
            withSession(source) {
                val vector = create()
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF000000, size = 2f)
                }
                val id = pen()
                val first = selected()
                val firstPixels = withContext(Dispatchers.Main) { pixels() }
                val saved = save()
                val originalLayers = controller.document.layers
                open(saved)
                assertEquals(originalLayers, controller.document.layers)
                assertFalse(controller.document.canUndo)
                assertContentEquals(firstPixels, withContext(Dispatchers.Main) { pixels() })
                assertEquals(mapOf(id to first), objects(saved, vector))
                click("Edit nodes")
                canvasClick(Offset(39.25f, 35.5f))
                waitFor { controller.selectedVectorObject?.objectId == id }
                val changed =
                    VectorGeometry.Path(
                        listOf(
                            VectorSegment.Move(20f, 30f),
                            VectorSegment.Cubic(26f, 24f, 54f, 40f, 60f, 50f),
                        )
                    )
                editNode(Offset(52f, 44f), Offset(54f, 40f), changed)
                val original = save()
                val geometry = objects(original, vector)
                assertEquals(mapOf(id to first.copy(geometry = changed)), geometry)
                assertContentEquals(raw, rawRaster(original))
                assertMasks(masks, rawMasks(original))
                val live = controller.document
                val composite = withContext(Dispatchers.Main) { pixels() }
                assertTrue(composite.any { it.ushr(24) == 0 })
                assertTrue(composite.any { it.ushr(24) in 1..254 })
                val frame = controller.frame
                val exportCount = files.exports.get()
                click("Export image")
                click("SVG")
                click("Export")
                waitFor { files.exports.get() == exportCount + 1 }
                assertEquals(ExportFormat.Svg, files.exported.get().format)
                val svg = files.exported.get().bytes
                val factory =
                    DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = true
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                val xml =
                    withContext(Dispatchers.Default) {
                        factory.newDocumentBuilder().parse(ByteArrayInputStream(svg))
                    }
                assertEquals("128", xml.documentElement.getAttribute("width"))
                assertEquals("96", xml.documentElement.getAttribute("height"))
                assertEquals(0, xml.getElementsByTagName("image").length)
                assertEquals(1, xml.getElementsByTagName("path").length)
                val path = xml.getElementsByTagName("path").item(0) as org.w3c.dom.Element
                assertEquals("object-$id", path.getAttribute("id"))
                assertEquals(
                    listOf("M", "20", "30", "C", "26", "24", "54", "40", "60", "50"),
                    Regex("[A-Za-z]|[-+]?[0-9]+(?:\\.[0-9]+)?")
                        .findAll(path.getAttribute("d"))
                        .map { it.value }
                        .toList(),
                )
                assertEquals("none", path.getAttribute("fill"))
                assertEquals("#000000", path.getAttribute("stroke"))
                assertEquals("2", path.getAttribute("stroke-width"))
                assertEquals("matrix(1 0 0 1 0 0)", path.getAttribute("transform"))
                assertEquals(live, controller.document)
                assertSame(frame, controller.frame)
                assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                for (format in listOf(ExportFormat.Psd, ExportFormat.Ora)) {
                    probe(original) { engine ->
                        val state = command(engine, """{"type":"state"}""")
                        val before = engine.call(EngineOperation.SAVE)
                        val failure = assertFails {
                            engine.call(
                                EngineOperation.EXPORT_IMAGE,
                                Json.encodeToString(ExportOptions(format, bakeLayers = false))
                                    .encodeToByteArray(),
                            )
                        }
                        assertTrue(failure.message.orEmpty().contains("可编辑矢量图层"), failure.message)
                        assertContentEquals(before, engine.call(EngineOperation.SAVE))
                        assertEquals(state, command(engine, """{"type":"state"}"""))
                    }
                    val before = controller.document
                    val beforeFrame = controller.frame
                    val saves = files.saves.get()
                    val exports = files.exports.get()
                    click("Export image")
                    click(format.label)
                    withContext(Dispatchers.Main) {
                        assertTrue(node("Export").config.contains(SemanticsProperties.Disabled))
                    }
                    toggleBeside("Export a flattened copy")
                    click("Export")
                    waitFor { files.exports.get() == exports + 1 }
                    val baked = files.exported.get()
                    assertEquals(format, baked.format)
                    assertEquals(before, controller.document)
                    assertSame(beforeFrame, controller.frame)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    assertEquals(saves, files.saves.get())
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(geometry, objects(files.saved.get(), vector))
                    open(baked.bytes)
                    assertEquals(1, controller.document.layers.size)
                    assertEquals(LayerKind.Raster, layer().kind)
                    assertNull(layer().vector)
                    assertTrue(layer().masks.isEmpty())
                    assertFalse(controller.document.canUndo)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    open(original)
                    assertEquals(live.layers, controller.document.layers)
                    assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                    assertEquals(geometry, objects(save(), vector))
                }
            }
        }
}
