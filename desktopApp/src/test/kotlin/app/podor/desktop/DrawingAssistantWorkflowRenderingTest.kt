package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DrawingAssistantWorkflowRenderingTest {

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

    private fun json(engine: NativeEngine, value: String): JsonObject {
        val state =
            Json.parseToJsonElement(
                    engine
                        .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                        .decodeToString()
                )
                .jsonObject
        val request = Json.parseToJsonElement(value).jsonObject
        return Json.parseToJsonElement(
                engine
                    .call(
                        EngineOperation.COMMAND,
                        JsonObject(request + ("revision" to state.getValue("revision")))
                            .toString()
                            .encodeToByteArray(),
                    )
                    .decodeToString()
            )
            .jsonObject
    }

    private fun command(engine: NativeEngine, value: String): DocumentInfo =
        Json.decodeFromString(json(engine, value).toString())

    private fun pick(engine: NativeEngine, x: Int, y: Int): JsonObject =
        Json.parseToJsonElement(
                engine
                    .call(
                        EngineOperation.COMMAND,
                        """{"type":"pick","x":$x,"y":$y}""".encodeToByteArray(),
                    )
                    .decodeToString()
            )
            .jsonObject

    private fun assistant(name: String, geometry: String, visible: Boolean = true) =
        buildJsonObject {
            put("name", name)
            put("visible", visible)
            put("geometry", Json.parseToJsonElement(geometry))
        }

    private val parallel =
        assistant("Parallel", """{"kind":"parallel","a":{"x":4,"y":10},"b":{"x":56,"y":10}}""")

    private val radial = assistant("Radial", """{"kind":"radial","center":{"x":0,"y":0}}""")

    private fun perspective(count: Int) =
        assistant(
            "$count-point perspective",
            """{"kind":"perspective","families":[{"kind":"finite_vanishing_point","point":{"x":0,"y":0}},${if (count >= 2) """{"kind":"finite_vanishing_point","point":{"x":64,"y":48}}""" else """{"kind":"infinite_direction","direction":{"x":1,"y":0}}"""},${if (count == 3) """{"kind":"finite_vanishing_point","point":{"x":32,"y":-64}}""" else """{"kind":"infinite_direction","direction":{"x":0,"y":1}}"""}]}""",
        )

    private suspend fun project(
        indexed: Boolean = false,
        masks: Boolean = true,
        ink: Boolean = false,
        guide: JsonObject? = null,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(64, 48)
            fun fill(left: Int, right: Int, color: String, index: Int) {
                command(
                    engine,
                    """{"type":"select","rect":{"left":$left,"top":0,"right":$right,"bottom":48}}""",
                )
                command(
                    engine,
                    if (indexed)
                        """{"type":"fill_indexed","x":$left,"y":0,"index":$index,"tolerance":0}"""
                    else """{"type":"fill","x":$left,"y":0,"color":$color,"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
            }
            try {
                if (indexed)
                    command(
                        engine,
                        """{"type":"new_indexed","width":64,"height":48,"palette":{"colors":[[0,0,0,0],[20,40,60,255],[20,40,60,255],[120,80,40,128]],"transparent":0,"order":[0,2,1,3]}}""",
                    )
                fill(0, 16, "[20,40,60,128]", 1)
                fill(16, 32, "[20,40,60,200]", 2)
                fill(32, 48, "[120,80,40,128]", 3)
                command(
                    engine,
                    """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                )
                if (masks) {
                    command(engine, """{"type":"add_mask","mode":"reveal","name":"First"}""")
                    val second =
                        command(engine, """{"type":"add_mask","mode":"reveal","name":"Second"}""")
                            .activeMaskId!!
                    command(
                        engine,
                        """{"type":"select","rect":{"left":2,"top":2,"right":6,"bottom":6}}""",
                    )
                    command(
                        engine,
                        """{"type":"fill","x":3,"y":3,"color":[0,0,0,255],"tolerance":0}""",
                    )
                    command(engine, """{"type":"select","rect":null}""")
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":1,"mask_id":$second,"enabled":false}""",
                    )
                }
                if (ink) {
                    command(engine, """{"type":"add_layer"}""")
                    command(
                        engine,
                        """{"type":"set_layer","id":2,"name":"Ink","visible":true,"opacity":1}""",
                    )
                }
                if (guide != null) {
                    json(engine, """{"type":"add_assistant","assistant":$guide}""")
                    json(engine, """{"type":"set_assistant_snap","id":1}""")
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

    private data class Planes(
        val layers: List<LayerInfo>,
        val palette: IndexedPalette?,
        val rgba: Map<Int, ByteArray>,
        val indices: Map<Int, ByteArray>,
        val masks: Map<Int, ByteArray>,
    )

    private suspend fun planes(bytes: ByteArray): Planes =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            val rgba = mutableMapOf<Int, ByteArray>()
            val indices = mutableMapOf<Int, ByteArray>()
            val masks = mutableMapOf<Int, ByteArray>()
            for (layer in state.layers) {
                command(engine, """{"type":"select_layer","id":${layer.id}}""")
                if (layer.kind == LayerKind.Raster) {
                    val palette = state.indexedPalette
                    if (palette != null) {
                        val slots =
                            ByteArray(state.width * state.height) { offset ->
                                pick(engine, offset % state.width, offset / state.width)
                                    .getValue("index")
                                    .jsonPrimitive
                                    .int
                                    .toByte()
                            }
                        indices[layer.id] = slots
                        rgba[layer.id] =
                            ByteArray(slots.size * 4) { offset ->
                                palette.colors[slots[offset / 4].toInt() and 255][offset % 4]
                                    .toByte()
                            }
                    } else {
                        rgba[layer.id] =
                            try {
                                engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
                            } catch (error: IllegalStateException) {
                                assertEquals("选中区域没有可复制的内容", error.message)
                                assertEquals(
                                    "当前图层没有可变换的内容",
                                    assertFailsWith<IllegalStateException> {
                                            engine.call(EngineOperation.LAYER_BOUNDS)
                                        }
                                        .message,
                                )
                                ByteArray(state.width * state.height * 4)
                            }
                    }
                }
                for (mask in layer.masks) {
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":${layer.id},"mask_id":${mask.id},"enabled":true}""",
                    )
                    masks[mask.id] =
                        ByteArray(state.width * state.height) { offset ->
                            pick(engine, offset % state.width, offset / state.width)
                                .getValue("color")
                                .jsonArray
                                .first()
                                .jsonPrimitive
                                .int
                                .toByte()
                        }
                }
            }
            Planes(state.layers, state.indexedPalette, rgba, indices, masks)
        }

    private fun assertPlanes(expected: Planes, actual: Planes, ids: Set<Int> = expected.rgba.keys) {
        assertEquals(expected.palette, actual.palette)
        assertEquals(expected.rgba.keys, actual.rgba.keys)
        assertEquals(expected.indices.keys, actual.indices.keys)
        assertEquals(expected.masks.keys, actual.masks.keys)
        for (id in ids) {
            assertContentEquals(expected.rgba.getValue(id), actual.rgba.getValue(id), "RGBA $id")
            expected.indices[id]?.let {
                assertContentEquals(it, actual.indices.getValue(id), "Indices $id")
            }
        }
        for ((id, mask) in expected.masks) assertContentEquals(
            mask,
            actual.masks.getValue(id),
            "Mask $id",
        )
    }

    private suspend fun assistantSet(bytes: ByteArray) =
        probe(bytes) { engine ->
            json(engine, """{"type":"state"}""").getValue("assistants").jsonObject
        }

    private fun svg(engine: NativeEngine): ByteArray {
        val state = json(engine, """{"type":"state"}""")
        return engine.call(
            EngineOperation.VECTOR_SVG,
            buildJsonObject {
                put("id", state.getValue("active"))
                put("revision", state.getValue("revision"))
            }
                .toString()
                .encodeToByteArray(),
        )
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, byte ->
            value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
        }

    private fun imagePixels(image: ImageBitmap) =
        IntArray(image.width * image.height).also { image.readPixels(it) }

    private fun nativePixels(engine: NativeEngine): IntArray {
        val bytes = engine.call(EngineOperation.FRAME, byteArrayOf(0))
        val transparent = engine.call(EngineOperation.FRAME, byteArrayOf(1))
        assertEquals(intAt(bytes, 0), intAt(transparent, 0))
        val width = intAt(transparent, 0)
        val height = intAt(transparent, 4)
        val size = intAt(transparent, 8)
        val output = IntArray(width * height)
        var offset = 16
        repeat(intAt(transparent, 12)) {
            val x = intAt(transparent, offset) * size
            val y = intAt(transparent, offset + 4) * size
            val image = rgbaBitmap(transparent, offset + 8, size)
            val pixels = imagePixels(image)
            for (row in 0 until minOf(size, height - y)) pixels.copyInto(
                output,
                (y + row) * width + x,
                row * size,
                row * size + minOf(size, width - x),
            )
            offset += 8 + size * size * 4
        }
        return output
    }

    private data class Sample(val point: Offset, val pressure: Float)

    private fun projectSamples(guide: JsonObject, samples: List<Sample>): List<Sample> {
        val origin = samples.first().point
        val geometry = guide.getValue("geometry").jsonObject
        fun point(value: JsonElement): Offset =
            value.jsonObject.let {
                Offset(it.getValue("x").jsonPrimitive.float, it.getValue("y").jsonPrimitive.float)
            }
        fun normalize(value: Offset): Pair<Double, Double> {
            val length = hypot(value.x.toDouble(), value.y.toDouble())
            require(length > 0.0)
            return value.x / length to value.y / length
        }
        val movement = samples.first { it.point != origin }.point - origin
        val directions =
            when (geometry.getValue("kind").jsonPrimitive.content) {
                "parallel" ->
                    listOf(normalize(point(geometry.getValue("b")) - point(geometry.getValue("a"))))
                "radial" ->
                    listOf(
                        normalize(
                            (point(geometry.getValue("center")) - origin).takeUnless {
                                it == Offset.Zero
                            } ?: movement
                        )
                    )
                "perspective" ->
                    geometry.getValue("families").jsonArray.map {
                        val family = it.jsonObject
                        normalize(
                            if (
                                family.getValue("kind").jsonPrimitive.content ==
                                    "infinite_direction"
                            )
                                point(family.getValue("direction"))
                            else
                                (point(family.getValue("point")) - origin).takeUnless {
                                    it == Offset.Zero
                                } ?: movement
                        )
                    }
                else -> error("Unsupported test guide")
            }
        val direction = directions.maxBy {
            abs(movement.x.toDouble() * it.first + movement.y.toDouble() * it.second)
        }
        return samples.map {
            val dx = it.point.x.toDouble() - origin.x
            val dy = it.point.y.toDouble() - origin.y
            val along = dx * direction.first + dy * direction.second
            Sample(
                Offset(
                    (origin.x + along * direction.first).toFloat(),
                    (origin.y + along * direction.second).toFloat(),
                ),
                it.pressure,
            )
        }
    }

    private fun brushJson(settings: BrushSettings) = buildJsonObject {
        val preset = settings.preset
        put("size", settings.size)
        put("opacity", settings.opacity)
        put("hardness", preset.hardness)
        put("tip", preset.tip.name.lowercase())
        put("texture", preset.texture.engineName)
        put("raster", preset.raster.engineName)
        put("aspect", preset.aspect)
        put("angle", preset.angle)
        put("follow_direction", preset.followDirection)
        put("grain", preset.grain)
        put("spacing", preset.spacing)
        put("stabilization", preset.stabilization)
        put("pressure_curve", preset.pressureCurve)
        put("size_pressure", preset.sizePressure)
        put("opacity_pressure", preset.opacityPressure)
        put("mix", 0f)
        put("paper", preset.paper)
        put("smudge", false)
        put("eraser", false)
        putJsonObject("symmetry") {
            put("mode", "off")
            put("x", 0.5f)
            put("y", 0.5f)
        }
        putJsonArray("color") {
            add((settings.color shr 16 and 255).toInt())
            add((settings.color shr 8 and 255).toInt())
            add((settings.color and 255).toInt())
        }
    }

    private fun sampleBytes(samples: List<Sample>): ByteArray =
        ByteArray(samples.size * 12).also { bytes ->
            samples.forEachIndexed { index, sample ->
                listOf(sample.point.x, sample.point.y, sample.pressure).forEachIndexed {
                    component,
                    value ->
                    repeat(4) { byte ->
                        bytes[index * 12 + component * 4 + byte] =
                            (value.toBits() ushr (byte * 8)).toByte()
                    }
                }
            }
        }

    private suspend fun baseline(
        bytes: ByteArray,
        brush: BrushSettings,
        samples: List<Sample>,
    ): IntArray =
        probe(bytes) { engine ->
            json(engine, """{"type":"set_assistant_snap","id":null}""")
            json(engine, """{"type":"begin","brush":${brushJson(brush)}}""")
            for (packet in samples.chunked(2)) engine.call(
                EngineOperation.SAMPLES,
                sampleBytes(packet),
            )
            json(engine, """{"type":"end"}""")
            nativePixels(engine)
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
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
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
                                controller.previews.revision == controller.document.revision
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

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun node(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.contains(action) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing assistant workflow control: $label")

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

        suspend fun text(label: String, value: String) =
            withContext(Dispatchers.Main) {
                assertTrue(
                    node(label, SemanticsActions.SetText).config[SemanticsActions.SetText].action!!(
                        AnnotatedString(value)
                    )
                )
                render().close()
            }

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + canvasBounds().topLeft

        suspend fun fit() =
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(zoom = 8f / Viewport().scale(view, controller.document))
                assertEquals(8f, controller.viewport.scale(view, controller.document), 0.000001f)
                for (point in listOf(Offset(8f, 12f), Offset(32f, 16f), Offset(54f, 36f))) {
                    val restored =
                        controller.viewport.toDocument(
                            controller.viewport.toView(point, view, controller.document),
                            view,
                            controller.document,
                        )
                    assertEquals(point.x, restored.x, 0.00001f)
                    assertEquals(point.y, restored.y, 0.00001f)
                }
            }

        suspend fun stylus(type: PointerEventType, sample: Sample, pressed: Boolean) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    type,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(sample.point),
                            pressed,
                            PointerType.Stylus,
                            sample.pressure,
                        )
                    ),
                )
                render().close()
            }

        fun pixels(frame: RenderFrame = controller.frame) =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in frame.tiles.values) {
                    val source = imagePixels(tile.image)
                    val x = tile.x * tile.size
                    val y = tile.y * tile.size
                    for (row in 0 until minOf(tile.size, controller.document.height - y)) source
                        .copyInto(
                            output,
                            (y + row) * controller.document.width + x,
                            row * tile.size,
                            row * tile.size + minOf(tile.size, controller.document.width - x),
                        )
                }
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

        suspend fun createAssistant(label: String): Int {
            val revision = controller.document.revision
            click("Add assistant")
            click(label)
            waitFor {
                controller.document.revision == revision + 1 &&
                    controller.selectedAssistantId != null
            }
            return assertNotNull(controller.selectedAssistantId)
        }

        suspend fun draft(start: Offset, end: Offset) {
            pointer(PointerEventType.Press, position(start))
            pointer(PointerEventType.Move, position(end))
            waitFor {
                controller.assistantPreview?.let {
                    !it.updating && !it.committing && it.value != it.initial
                } == true
            }
        }

        suspend fun release(point: Offset) {
            pointer(PointerEventType.Release, position(point))
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun escape() =
            withContext(Dispatchers.Main) {
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                render().close()
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

        suspend fun toggleBeside(label: String) {
            settle()
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
    }

    private suspend fun withSession(project: ByteArray, block: suspend Session.() -> Unit) {
        val originalAppearance = StudioTheme.appearance
        val files = MemoryFiles(project)
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
            session.waitFor { controller.hasCanvas && controller.document.width == 64 }
            session.click("Show panel")
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
    fun realMetadataControlsPreserveAllRgbaIndicesSelectedMaskAndReuseCleanImages() = runBlocking {
        for (indexed in listOf(false, true)) {
            val source = project(indexed = indexed)
            val originalPlanes = planes(source)
            withSession(source) {
                val secondMask = controller.document.layers.single().masks[1].id
                withContext(Dispatchers.Main) {
                    controller.selectLayer(1, mask = true, maskId = secondMask)
                }
                waitFor {
                    controller.document.maskEditing &&
                        controller.document.activeMaskId == secondMask
                }
                val target = controller.document
                val frame = controller.frame
                val previewImages = controller.previews.images.toMap()
                val composite = withContext(Dispatchers.Main) { pixels() }
                click("Drawing assistants")
                val id = createAssistant("Parallel lines")
                assertEquals(1, id)
                assertEquals(
                    DrawingAssistantSpec(
                        "Parallel 1",
                        geometry =
                            AssistantGeometry.Parallel(
                                AssistantPoint(16f, 24f),
                                AssistantPoint(48f, 24f),
                            ),
                    ),
                    controller.document.assistants.items.single().spec(),
                )
                assertNull(controller.document.assistants.snapId)
                var revision = controller.document.revision
                click("Snap to assistant")
                waitFor { controller.document.revision == revision + 1 }
                assertEquals(id, controller.document.assistants.snapId)
                revision = controller.document.revision
                click("Guide visibility")
                waitFor { controller.document.revision == revision + 1 }
                assertFalse(controller.document.assistants.items.single().visible)
                assertEquals(id, controller.document.assistants.snapId)
                val saved = save()
                assertEquals(target.layers, controller.document.layers)
                assertEquals(target.active, controller.document.active)
                assertEquals(secondMask, controller.document.activeMaskId)
                assertTrue(controller.document.maskEditing)
                assertPlanes(originalPlanes, planes(saved))
                assertSame(frame, controller.frame)
                previewImages.forEach { (layer, image) ->
                    assertSame(image, controller.previews.images.getValue(layer))
                }
                assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                revision = controller.document.revision
                click("Delete assistant")
                waitFor { controller.document.revision == revision + 1 }
                assertTrue(controller.document.assistants.items.isEmpty())
                assertNull(controller.document.assistants.snapId)
                click("Undo")
                waitFor { controller.document.revision == revision + 2 }
                assertEquals(assistantSet(saved), assistantSet(save()))
                click("Redo")
                waitFor { controller.document.revision == revision + 3 }
                assertTrue(controller.document.assistants.items.isEmpty())
                assertPlanes(originalPlanes, planes(save()))
                assertSame(frame, controller.frame)
                assertContentEquals(composite, withContext(Dispatchers.Main) { pixels() })
                val presets =
                    listOf(
                        "Concentration guide" to
                            DrawingAssistantSpec(
                                "Radial 2",
                                geometry = AssistantGeometry.Radial(AssistantPoint(32f, 24f)),
                            ),
                        "One-point perspective" to
                            DrawingAssistantSpec(
                                "OnePoint 3",
                                geometry =
                                    AssistantGeometry.Perspective(
                                        listOf(
                                            AssistantFamily.Vanishing(AssistantPoint(32f, 16f)),
                                            AssistantFamily.Infinite(AssistantPoint(1f, 0f)),
                                            AssistantFamily.Infinite(AssistantPoint(0f, 1f)),
                                        )
                                    ),
                            ),
                        "Two-point perspective" to
                            DrawingAssistantSpec(
                                "TwoPoint 4",
                                geometry =
                                    AssistantGeometry.Perspective(
                                        listOf(
                                            AssistantFamily.Vanishing(AssistantPoint(8f, 16f)),
                                            AssistantFamily.Vanishing(AssistantPoint(56f, 16f)),
                                            AssistantFamily.Infinite(AssistantPoint(0f, 1f)),
                                        )
                                    ),
                            ),
                        "Three-point perspective" to
                            DrawingAssistantSpec(
                                "ThreePoint 5",
                                geometry =
                                    AssistantGeometry.Perspective(
                                        listOf(
                                            AssistantFamily.Vanishing(AssistantPoint(8f, 16f)),
                                            AssistantFamily.Vanishing(AssistantPoint(56f, 16f)),
                                            AssistantFamily.Vanishing(AssistantPoint(32f, 72f)),
                                        )
                                    ),
                            ),
                    )
                for ((offset, entry) in presets.withIndex()) {
                    val (label, spec) = entry
                    assertEquals(offset + 2, createAssistant(label))
                    assertEquals(spec, controller.document.assistants.items.last().spec())
                    assertNull(controller.document.assistants.snapId)
                }
                assertEquals(6, controller.document.assistants.nextId)
                assertPlanes(originalPlanes, planes(save()))
                assertSame(frame, controller.frame)
                previewImages.forEach { (layer, image) ->
                    assertSame(image, controller.previews.images.getValue(layer))
                }
                probe(saved) { engine ->
                    engine.call(EngineOperation.FRAME, byteArrayOf(1))
                    val state = json(engine, """{"type":"state"}""")
                    val bytes = engine.call(EngineOperation.SAVE)
                    val item =
                        state
                            .getValue("assistants")
                            .jsonObject
                            .getValue("items")
                            .jsonArray
                            .single()
                            .jsonObject
                    val spec = JsonObject(item - "id")
                    json(
                        engine,
                        """{"type":"preview_assistant","assistant":$spec,"origin":{"x":8,"y":12},"point":{"x":42,"y":28}}""",
                    )
                    assertEquals(state, json(engine, """{"type":"state"}"""))
                    assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
                    assertEquals(0, intAt(engine.call(EngineOperation.FRAME, byteArrayOf(1)), 12))
                    json(engine, """{"type":"set_assistant","id":1,"assistant":$spec}""")
                    assertEquals(state, json(engine, """{"type":"state"}"""))
                    assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
                    assertEquals(0, intAt(engine.call(EngineOperation.FRAME, byteArrayOf(1)), 12))
                }
            }
        }
    }

    @Test
    fun realPressureStrokesForParallelRadialAndAllPerspectiveFamiliesMatchProjectedNativeBaseline() =
        runBlocking {
            val ordinary =
                listOf(
                    Sample(Offset(8f, 8f), 0.25f),
                    Sample(Offset(20f, 21f), 0.55f),
                    Sample(Offset(36f, 28f), 0.85f),
                    Sample(Offset(44f, 34f), 0.4f),
                )
            val cases =
                listOf(
                    parallel to
                        listOf(
                            Sample(Offset(8f, 12f), 0.25f),
                            Sample(Offset(20f, 16f), 0.55f),
                            Sample(Offset(40f, 4f), 0.85f),
                            Sample(Offset(56f, 20f), 0.4f),
                        ),
                    radial to ordinary,
                    perspective(1) to ordinary,
                    perspective(2) to
                        listOf(
                            Sample(Offset(32f, 16f), 0.25f),
                            Sample(Offset(42f, 27f), 0.55f),
                            Sample(Offset(48f, 22f), 0.85f),
                            Sample(Offset(56f, 35f), 0.4f),
                        ),
                    perspective(3) to
                        listOf(
                            Sample(Offset(32f, 16f), 0.25f),
                            Sample(Offset(35f, 4f), 0.55f),
                            Sample(Offset(30f, 12f), 0.85f),
                            Sample(Offset(38f, 32f), 0.4f),
                        ),
                )
            val brush =
                BrushSettings(
                    preset =
                        BrushPreset.Ink.copy(
                            hardness = 0.8f,
                            tip = BrushTip.Round,
                            aspect = 1f,
                            grain = 0f,
                            spacing = 0.06f,
                            stabilization = 0.45f,
                            pressureCurve = 0.2f,
                            sizePressure = 0.65f,
                            opacityPressure = 0.5f,
                            texture = BrushTexture.Smooth,
                        ),
                    size = 5f,
                    opacity = 0.8f,
                    color = 0xFF3877C5,
                )
            for ((index, entry) in cases.withIndex()) {
                val (visible, samples) = entry
                val guide =
                    if (index == 0) JsonObject(visible + ("visible" to JsonPrimitive(false)))
                    else visible
                val source = project(masks = false, ink = true, guide = guide)
                val originalPlanes = planes(source)
                val projected = projectSamples(guide, samples)
                assertTrue(projected.zip(samples).any { (a, b) -> a.point != b.point })
                assertEquals(samples.map { it.pressure }, projected.map { it.pressure })
                val settings =
                    if (index == 0)
                        brush.copy(
                            preset =
                                brush.preset.copy(
                                    tip = BrushTip.Leaf,
                                    aspect = 0.22f,
                                    followDirection = true,
                                )
                        )
                    else brush
                val expected = baseline(source, settings, projected)
                assertFalse(expected.contentEquals(baseline(source, settings, samples)))
                withSession(source) {
                    click("Drawing assistants")
                    click("Finish assistant editing")
                    assertEquals(Tool.Brush, controller.tool)
                    assertEquals(1, controller.document.assistants.snapId)
                    withContext(Dispatchers.Main) { controller.brush = settings }
                    val before = controller.document
                    val beforePixels = withContext(Dispatchers.Main) { pixels() }
                    assertFalse(expected.contentEquals(beforePixels))
                    stylus(PointerEventType.Press, samples.first(), true)
                    for (sample in samples.drop(1)) stylus(PointerEventType.Move, sample, true)
                    stylus(PointerEventType.Release, samples.last(), false)
                    waitFor { controller.document.revision == before.revision + 1 }
                    assertContentEquals(
                        expected,
                        withContext(Dispatchers.Main) { pixels() },
                        guide.getValue("name").jsonPrimitive.content,
                    )
                    assertEquals(before.assistants, controller.document.assistants)
                    assertTrue(controller.document.canUndo)
                    val drawn = save()
                    assertPlanes(originalPlanes, planes(drawn), setOf(1))
                    click("Undo")
                    waitFor { controller.document.revision == before.revision + 2 }
                    assertFalse(controller.document.canUndo)
                    assertEquals(before.layers, controller.document.layers)
                    assertEquals(before.assistants, controller.document.assistants)
                    assertContentEquals(beforePixels, withContext(Dispatchers.Main) { pixels() })
                    assertPlanes(originalPlanes, planes(save()))
                    click("Redo")
                    waitFor { controller.document.revision == before.revision + 3 }
                    assertContentEquals(expected, withContext(Dispatchers.Main) { pixels() })
                    assertEquals(before.assistants, controller.document.assistants)
                    val frame = controller.frame
                    val images = controller.previews.images.toMap()
                    settle()
                    assertSame(frame, controller.frame)
                    images.forEach { (id, image) ->
                        assertSame(image, controller.previews.images.getValue(id))
                    }
                }
            }
        }

    @Test
    fun actualGuideHandleDraftCancelsOrCommitsOneMetadataUndoAndPodorTenReopensEditable() =
        runBlocking {
            val source = project(guide = parallel)
            val originalPlanes = planes(source)
            val initial = Json.decodeFromString<DrawingAssistantSpec>(parallel.toString())
            val edited =
                initial.copy(
                    geometry =
                        AssistantGeometry.Parallel(
                            AssistantPoint(4f, 10f),
                            AssistantPoint(48f, 28f),
                        )
                )
            withSession(source) {
                click("Drawing assistants")
                click("Select assistant")
                click("Parallel")
                val baseline = save()
                val before = controller.document
                val frame = controller.frame
                val images = controller.previews.images.toMap()
                val rgba = withContext(Dispatchers.Main) { pixels() }
                draft(Offset(56f, 10f), Offset(48f, 28f))
                assertEquals(edited, controller.assistantPreview!!.value)
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertSame(frame, controller.frame)
                assertContentEquals(rgba, withContext(Dispatchers.Main) { pixels() })
                escape()
                waitFor { controller.assistantPreview == null }
                release(Offset(48f, 28f))
                assertEquals(before, controller.document)
                assertContentEquals(baseline, save())
                draft(Offset(56f, 10f), Offset(48f, 28f))
                assertEquals(edited, controller.assistantPreview!!.value)
                release(Offset(48f, 28f))
                waitFor {
                    controller.document.revision == before.revision + 1 &&
                        controller.assistantPreview == null
                }
                assertEquals(edited, controller.document.assistants.items.single().spec())
                assertSame(frame, controller.frame)
                images.forEach { (id, image) ->
                    assertSame(image, controller.previews.images.getValue(id))
                }
                assertTrue(controller.hasUnsavedChanges)
                val committed = save()
                assertPlanes(originalPlanes, planes(committed))
                assertContentEquals(rgba, withContext(Dispatchers.Main) { pixels() })
                click("Undo")
                waitFor { controller.document.revision == before.revision + 2 }
                assertEquals(initial, controller.document.assistants.items.single().spec())
                assertFalse(controller.document.canUndo)
                click("Redo")
                waitFor { controller.document.revision == before.revision + 3 }
                assertEquals(edited, controller.document.assistants.items.single().spec())
                assertContentEquals(committed, save())
                val unchanged = controller.document
                withContext(Dispatchers.Main) { controller.fingerDrawing = true }
                touch(PointerEventType.Press, true, listOf(Offset(4f, 10f)))
                touch(PointerEventType.Move, true, listOf(Offset(14f, 18f)))
                waitFor {
                    controller.assistantPreview?.value != null &&
                        controller.assistantPreview?.value != edited
                }
                touch(PointerEventType.Press, true, listOf(Offset(14f, 18f), Offset(40f, 30f)))
                touch(PointerEventType.Release, false, listOf(Offset(14f, 18f), Offset(40f, 30f)))
                waitFor { controller.assistantPreview == null }
                assertEquals(unchanged, controller.document)
                assertContentEquals(committed, save())
                open(committed)
                assertEquals(edited, controller.document.assistants.items.single().spec())
                assertEquals(1, controller.document.assistants.snapId)
                assertFalse(controller.document.canUndo)
                assertPlanes(originalPlanes, planes(save()))
                click("Drawing assistants")
                click("Select assistant")
                click("Parallel")
                draft(Offset(48f, 28f), Offset(52f, 30f))
                release(Offset(52f, 30f))
                waitFor { controller.assistantPreview == null && controller.document.canUndo }
                assertEquals(
                    initial.copy(
                        geometry =
                            AssistantGeometry.Parallel(
                                AssistantPoint(4f, 10f),
                                AssistantPoint(52f, 30f),
                            )
                    ),
                    controller.document.assistants.items.single().spec(),
                )
                assertPlanes(originalPlanes, planes(save()))
            }
        }

    private fun affine(set: DrawingAssistantSet, sx: Float, sy: Float, dx: Float, dy: Float) =
        set.copy(
            items =
                set.items.map { item ->
                    fun point(point: AssistantPoint) =
                        AssistantPoint(point.x * sx + dx, point.y * sy + dy)
                    item.copy(
                        geometry =
                            when (val geometry = item.geometry) {
                                is AssistantGeometry.Parallel ->
                                    geometry.copy(a = point(geometry.a), b = point(geometry.b))
                                is AssistantGeometry.Radial ->
                                    geometry.copy(center = point(geometry.center))
                                is AssistantGeometry.Perspective ->
                                    geometry.copy(
                                        families =
                                            geometry.families.map {
                                                when (it) {
                                                    is AssistantFamily.Vanishing ->
                                                        it.copy(point = point(it.point))
                                                    is AssistantFamily.Infinite ->
                                                        it.copy(
                                                            direction =
                                                                AssistantPoint(
                                                                    it.direction.x * sx,
                                                                    it.direction.y * sy,
                                                                )
                                                        )
                                                }
                                            }
                                    )
                            }
                    )
                }
        )

    @Test
    fun realCanvasAndImageResizeApplyGuideAffineAndExternalExportsContainOnlyArtwork() =
        runBlocking {
            val plain = project(masks = false)
            val source =
                probe(plain) { engine ->
                    for (guide in listOf(parallel, radial, perspective(2))) json(
                        engine,
                        """{"type":"add_assistant","assistant":$guide}""",
                    )
                    json(engine, """{"type":"set_assistant_snap","id":3}""")
                    engine.call(EngineOperation.SAVE)
                }
            val expected =
                probe(plain) { engine ->
                    json(engine, """{"type":"resize_canvas","width":96,"height":64,"anchor":4}""")
                    json(
                        engine,
                        """{"type":"resize_image","width":144,"height":128,"filter":"nearest"}""",
                    )
                    engine.call(EngineOperation.SAVE)
                }
            val expectedPixels = probe(expected) { nativePixels(it) }
            val expectedPlanes = planes(expected)
            val translatedPixels =
                probe(plain) { engine ->
                    json(engine, """{"type":"resize_canvas","width":96,"height":64,"anchor":4}""")
                    nativePixels(engine)
                }
            val originalPlanes = planes(plain)
            withSession(source) {
                val original = controller.document.assistants
                click("Adjust")
                click("Canvas size")
                text("Width", "96")
                text("Height", "64")
                click("Center")
                click("Apply")
                waitFor { controller.document.width == 96 && controller.document.height == 64 }
                val translated = affine(original, 1f, 1f, 16f, 8f)
                assertEquals(translated, controller.document.assistants)
                click("Image size")
                click("Lock proportions")
                text("Width", "144")
                text("Height", "128")
                click("Pixel")
                click("Apply")
                waitFor { controller.document.width == 144 && controller.document.height == 128 }
                val scaled = affine(translated, 1.5f, 2f, 0f, 0f)
                assertEquals(scaled, controller.document.assistants)
                assertEquals(3, scaled.snapId)
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                val saved = save()
                assertPlanes(expectedPlanes, planes(saved))
                assertEquals(
                    scaled,
                    Json.decodeFromString<DrawingAssistantSet>(assistantSet(saved).toString()),
                )
                var revision = controller.document.revision
                click("Undo")
                waitFor { controller.document.revision == revision + 1 }
                assertEquals(96, controller.document.width)
                assertEquals(64, controller.document.height)
                assertEquals(translated, controller.document.assistants)
                assertContentEquals(translatedPixels, withContext(Dispatchers.Main) { pixels() })
                click("Undo")
                waitFor { controller.document.revision == revision + 2 }
                assertEquals(64, controller.document.width)
                assertEquals(48, controller.document.height)
                assertEquals(original, controller.document.assistants)
                assertFalse(controller.document.canUndo)
                assertPlanes(originalPlanes, planes(save()))
                click("Redo")
                waitFor { controller.document.revision == revision + 3 }
                assertEquals(translated, controller.document.assistants)
                assertContentEquals(translatedPixels, withContext(Dispatchers.Main) { pixels() })
                click("Redo")
                waitFor { controller.document.revision == revision + 4 }
                assertEquals(scaled, controller.document.assistants)
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                assertContentEquals(saved, save())
                val svgFixture =
                    probe(saved) { engine ->
                        val vector =
                            command(
                                    engine,
                                    """{"type":"create_vector","name":"Vector","parent_id":null,"index":1}""",
                                )
                                .active
                        val shape =
                            VectorObjectSpec(
                                "Line",
                                geometry = VectorGeometry.Line(8f, 12f, 40f, 28f),
                                style =
                                    VectorStyle(
                                        fill = null,
                                        stroke = VectorStroke(listOf(0, 0, 0, 255), 2f),
                                    ),
                            )
                        json(
                            engine,
                            """{"type":"add_vector_object","id":$vector,"object":${shape.request()},"index":null}""",
                        )
                        engine.call(EngineOperation.SAVE)
                    }
                val svgWithGuides =
                    probe(svgFixture) { engine ->
                        val before = json(engine, """{"type":"state"}""")
                        val bytes = engine.call(EngineOperation.SAVE)
                        svg(engine).also {
                            assertEquals(before, json(engine, """{"type":"state"}"""))
                            assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
                        }
                    }
                val svgWithoutGuides =
                    probe(svgFixture) { engine ->
                        for (id in 1..3) json(engine, """{"type":"delete_assistant","id":$id}""")
                        svg(engine)
                    }
                assertContentEquals(svgWithoutGuides, svgWithGuides)
                for (format in listOf(ExportFormat.Png, ExportFormat.Psd, ExportFormat.Ora)) {
                    val before = controller.document
                    val frame = controller.frame
                    val saves = files.saves.get()
                    val exports = files.exports.get()
                    click("Export image")
                    click(format.label)
                    if (format == ExportFormat.Png) toggleBeside("Transparent background")
                    withContext(Dispatchers.Main) {
                        assertFalse(node("Export").config.contains(SemanticsProperties.Disabled))
                    }
                    click("Export")
                    waitFor { files.exports.get() == exports + 1 }
                    assertEquals(format, files.exported.get().format)
                    assertEquals(before, controller.document)
                    assertSame(frame, controller.frame)
                    assertEquals(saves, files.saves.get())
                    assertFalse(controller.hasUnsavedChanges)
                    assertContentEquals(saved, save())
                    val exported = files.exported.get().bytes
                    probe(exported) { engine ->
                        assertEquals(
                            DrawingAssistantSet(),
                            command(engine, """{"type":"state"}""").assistants,
                        )
                        assertContentEquals(expectedPixels, nativePixels(engine))
                    }
                    open(exported)
                    assertEquals(DrawingAssistantSet(), controller.document.assistants)
                    assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                    open(saved)
                    assertEquals(scaled, controller.document.assistants)
                    assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                }
            }
        }
}
