package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
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
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LineGeneratorWorkflowRenderingTest {

    private data class Exported(val format: ExportFormat, val bytes: ByteArray)

    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        val input = AtomicReference(project.copyOf())
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val opens = AtomicInteger()
        val exports = AtomicInteger()
        override val exportFormats = listOf(ExportFormat.Psd, ExportFormat.Ora, ExportFormat.Svg)

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

    private fun sample(seed: UInt, index: Int, salt: ULong): Double {
        var value = seed.toULong() xor (index.toULong() * 0x9e3779b97f4a7c15uL) xor salt
        value += 0x9e3779b97f4a7c15uL
        value = (value xor (value shr 30)) * 0xbf58476d1ce4e5b9uL
        value = (value xor (value shr 27)) * 0x94d049bb133111ebuL
        value = value xor (value shr 31)
        return (value shr 11).toDouble() / 9_007_199_254_740_992.0
    }

    private data class Point(val x: Double, val y: Double) {
        operator fun plus(other: Point) = Point(x + other.x, y + other.y)

        operator fun times(scale: Double) = Point(x * scale, y * scale)
    }

    private fun independentObjects(settings: JsonObject): List<VectorObjectSpec> {
        fun number(name: String) = settings.getValue(name).jsonPrimitive.double
        fun point(name: String) =
            settings.getValue(name).jsonObject.let {
                Point(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double)
            }
        fun radii(name: String) =
            settings.getValue(name).jsonObject.let {
                Point(
                    it.getValue("rx").jsonPrimitive.double,
                    it.getValue("ry").jsonPrimitive.double,
                )
            }
        val kind = settings.getValue("kind").jsonPrimitive.content
        val seed = settings.getValue("seed").jsonPrimitive.long.toUInt()
        val count = settings.getValue("count").jsonPrimitive.int
        val randomness = number("randomness")
        val startTaper = number("taper_start")
        val endTaper = number("taper_end")
        val originalStroke =
            Json.decodeFromString<VectorStroke>(settings.getValue("stroke").toString())
        val opacity = settings.getValue("opacity").jsonPrimitive.float
        return List(count) { index ->
            val width =
                originalStroke.width.toDouble() *
                    (1.0 - 0.5 * randomness + randomness * sample(seed, index, 0x7769647468uL))
            val p: Point
            val q: Point
            if (kind == "concentration") {
                val center = point("center")
                val inner = radii("inner")
                val outer = radii("outer")
                val slot =
                    index +
                        0.5 +
                        randomness * 0.45 * (2.0 * sample(seed, index, 0x616e676c65uL) - 1.0)
                val angle =
                    Math.toRadians(number("angle_start") + slot * number("angle_sweep") / count)
                val direction = Point(cos(angle), sin(angle))
                fun radius(radii: Point) = 1.0 / hypot(direction.x / radii.x, direction.y / radii.y)
                val margin =
                    width / 2.0 *
                        if (
                            startTaper == 1.0 &&
                                endTaper == 1.0 &&
                                originalStroke.cap == VectorCap.Square
                        )
                            sqrt(2.0)
                        else 1.0
                val support = margin + 1.0
                val safeStart = radius(inner) * (1.0 + support / min(inner.x, inner.y))
                val safeEnd = radius(outer) * (1.0 - support / min(outer.x, outer.y))
                val gap = safeEnd - safeStart
                require(gap > 0)
                p =
                    center +
                        direction *
                            (safeStart +
                                0.2 * randomness * sample(seed, index, 0x7374617274uL) * gap)
                q =
                    center +
                        direction *
                            (safeEnd - 0.2 * randomness * sample(seed, index, 0x656e64uL) * gap)
            } else {
                require(kind == "speed")
                val angle = Math.toRadians(number("angle"))
                val direction = Point(cos(angle), sin(angle))
                val normal = Point(-direction.y, direction.x)
                val lane =
                    (index - (count - 1) / 2.0 +
                        randomness * 0.45 * (2.0 * sample(seed, index, 0x6c61746572616cuL) - 1.0)) *
                        number("spacing")
                val start =
                    0.25 *
                        randomness *
                        (2.0 * sample(seed, index, 0x7374617274uL) - 1.0) *
                        number("length")
                val length =
                    number("length") *
                        (1.0 - 0.5 * randomness +
                            randomness * sample(seed, index, 0x6c656e677468uL))
                p = point("origin") + normal * lane + direction * start
                q = p + direction * length
            }
            val color =
                originalStroke.color.toMutableList().apply {
                    this[3] = (this[3].toFloat() * opacity).roundToInt()
                }
            val uniform = startTaper == 1.0 && endTaper == 1.0
            val geometry =
                if (uniform)
                    VectorGeometry.Line(p.x.toFloat(), p.y.toFloat(), q.x.toFloat(), q.y.toFloat())
                else {
                    val direction = Point(q.x - p.x, q.y - p.y)
                    val length = hypot(direction.x, direction.y)
                    val normal = Point(-direction.y / length, direction.x / length)
                    fun ribbon(t: Double, halfWidth: Double) =
                        p + direction * t + normal * halfWidth
                    fun cubic(
                        t1: Double,
                        h1: Double,
                        t2: Double,
                        h2: Double,
                        t: Double,
                        h: Double,
                    ): VectorSegment {
                        val first = ribbon(t1, h1)
                        val second = ribbon(t2, h2)
                        val end = ribbon(t, h)
                        return VectorSegment.Cubic(
                            first.x.toFloat(),
                            first.y.toFloat(),
                            second.x.toFloat(),
                            second.y.toFloat(),
                            end.x.toFloat(),
                            end.y.toFloat(),
                        )
                    }
                    val half = width / 2.0
                    val first = half * startTaper
                    val last = half * endTaper
                    val start = ribbon(0.0, first)
                    val end = ribbon(1.0, -last)
                    VectorGeometry.Path(
                        listOf(
                            VectorSegment.Move(start.x.toFloat(), start.y.toFloat()),
                            cubic(
                                1.0 / 6.0,
                                (first + 2.0 * half) / 3.0,
                                1.0 / 3.0,
                                half,
                                0.5,
                                half,
                            ),
                            cubic(2.0 / 3.0, half, 5.0 / 6.0, (last + 2.0 * half) / 3.0, 1.0, last),
                            VectorSegment.Line(end.x.toFloat(), end.y.toFloat()),
                            cubic(
                                5.0 / 6.0,
                                -(last + 2.0 * half) / 3.0,
                                2.0 / 3.0,
                                -half,
                                0.5,
                                -half,
                            ),
                            cubic(
                                1.0 / 3.0,
                                -half,
                                1.0 / 6.0,
                                -(first + 2.0 * half) / 3.0,
                                0.0,
                                -first,
                            ),
                            VectorSegment.Close,
                        )
                    )
                }
            VectorObjectSpec(
                "${if (kind == "concentration") "Concentration" else "Speed"} ${(index + 1).toString().padStart(3, '0')}",
                geometry = geometry,
                style =
                    VectorStyle(
                        fill = color.takeUnless { uniform },
                        stroke =
                            originalStroke.copy(color = color, width = width.toFloat()).takeIf {
                                uniform
                            },
                    ),
            )
        }
    }

    private suspend fun objects(bytes: ByteArray, id: Int): Map<Int, VectorObjectSpec> =
        probe(bytes) { engine ->
            val state = command(engine, """{"type":"state"}""")
            val saved = engine.call(EngineOperation.SAVE)
            val list =
                Json.decodeFromString<VectorObjects>(
                    json(engine, """{"type":"vector_objects","id":$id}""").toString()
                )
            list.objects
                .associate { summary ->
                    val value =
                        Json.decodeFromString<VectorObjectResult>(
                            json(
                                    engine,
                                    """{"type":"vector_object","id":$id,"object_id":${summary.id}}""",
                                )
                                .toString()
                        )
                    summary.id to value.`object`
                }
                .also {
                    assertEquals(state, command(engine, """{"type":"state"}"""))
                    assertContentEquals(saved, engine.call(EngineOperation.SAVE))
                }
        }

    private suspend fun project(
        indexed: Boolean = false,
        nested: String? = null,
        masks: Boolean = true,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(128, 96)
            fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String, slot: Int = 1) {
                command(
                    engine,
                    """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}""",
                )
                command(
                    engine,
                    if (indexed)
                        """{"type":"fill_indexed","x":$left,"y":$top,"index":$slot,"tolerance":0}"""
                    else """{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
            }
            try {
                if (indexed)
                    command(
                        engine,
                        """{"type":"new_indexed","width":128,"height":96,"palette":{"colors":[[0,0,0,0],[20,40,60,255],[20,40,60,255],[120,80,40,128]],"transparent":0,"order":[0,2,1,3]}}""",
                    )
                fill(0, 0, 32, 96, "[20,40,60,128]", 1)
                fill(32, 0, 64, 96, "[20,40,60,200]", 2)
                fill(64, 0, 96, 96, "[120,80,40,128]", 3)
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
                        """{"type":"select","rect":{"left":8,"top":8,"right":16,"bottom":16}}""",
                    )
                    command(
                        engine,
                        """{"type":"fill","x":8,"y":8,"color":[0,0,0,255],"tolerance":0}""",
                    )
                    command(engine, """{"type":"select","rect":null}""")
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":1,"mask_id":$second,"enabled":false}""",
                    )
                }
                if (nested != null) {
                    command(
                        engine,
                        """{"type":"create_group","name":"Outer","parent_id":null,"index":0,"isolation":"isolated"}""",
                    )
                    command(
                        engine,
                        """{"type":"create_group","name":"Inner","parent_id":2,"index":0,"isolation":"$nested"}""",
                    )
                    command(engine, """{"type":"move_node","id":1,"parent_id":3,"index":0}""")
                    command(
                        engine,
                        """{"type":"set_layer","id":2,"name":"Outer","visible":true,"opacity":0.8}""",
                    )
                    command(engine, """{"type":"select_layer","id":1}""")
                    command(engine, """{"type":"add_layer"}""")
                    fill(24, 16, 80, 72, "[30,180,100,96]")
                    command(
                        engine,
                        """{"type":"set_layer","id":4,"name":"Clipped accent","visible":true,"opacity":1}""",
                    )
                    command(engine, """{"type":"set_clipping","id":4,"clipping":true}""")
                    val settings =
                        AdjustmentLayerSettings(
                                AdjustmentKind.Tone,
                                brightness = 0.15f,
                                contrast = 0.1f,
                            )
                            .request()
                    val state = command(engine, """{"type":"state"}""")
                    command(
                        engine,
                        """{"type":"create_adjustment","name":"Higher tone","parent_id":3,"index":2,"selection_id":${state.selectionId},"settings":$settings}""",
                    )
                    command(engine, """{"type":"select_layer","id":1}""")
                }
                engine.call(EngineOperation.SAVE).also {
                    assertContentEquals("PODOR\u000c".encodeToByteArray(), it.copyOf(6))
                }
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
            fun pick(x: Int, y: Int) =
                Json.parseToJsonElement(
                        engine
                            .call(
                                EngineOperation.COMMAND,
                                """{"type":"pick","x":$x,"y":$y}""".encodeToByteArray(),
                            )
                            .decodeToString()
                    )
                    .jsonObject
            for (layer in state.layers) {
                command(engine, """{"type":"select_layer","id":${layer.id}}""")
                if (layer.kind == LayerKind.Raster) {
                    val palette = state.indexedPalette
                    if (palette == null)
                        rgba[layer.id] = engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
                    else {
                        val slots =
                            ByteArray(state.width * state.height) {
                                pick(it % state.width, it / state.width)
                                    .getValue("index")
                                    .jsonPrimitive
                                    .int
                                    .toByte()
                            }
                        indices[layer.id] = slots
                        rgba[layer.id] =
                            ByteArray(slots.size * 4) {
                                palette.colors[slots[it / 4].toInt() and 255][it % 4].toByte()
                            }
                    }
                }
                for (mask in layer.masks) {
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":${layer.id},"mask_id":${mask.id},"enabled":true}""",
                    )
                    masks[mask.id] =
                        ByteArray(state.width * state.height) {
                            pick(it % state.width, it / state.width)
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

    private fun assertPlanes(expected: Planes, actual: Planes) {
        assertEquals(expected.palette, actual.palette)
        val added = actual.layers.filter { layer -> expected.layers.none { it.id == layer.id } }
        for (layer in expected.layers) {
            val current = actual.layers.single { it.id == layer.id }
            assertEquals(
                layer.copy(
                    mask = current.mask,
                    childCount = layer.childCount + added.count { it.parentId == layer.id },
                ),
                current,
            )
            assertTrue(current.mask == null || current.masks.contains(current.mask))
        }
        assertEquals(expected.rgba.keys, actual.rgba.keys)
        assertEquals(expected.indices.keys, actual.indices.keys)
        assertEquals(expected.masks.keys, actual.masks.keys)
        expected.rgba.forEach { (id, value) ->
            assertContentEquals(value, actual.rgba.getValue(id), "RGBA $id")
        }
        expected.indices.forEach { (id, value) ->
            assertContentEquals(value, actual.indices.getValue(id), "Indices $id")
        }
        expected.masks.forEach { (id, value) ->
            assertContentEquals(value, actual.masks.getValue(id), "Mask $id")
        }
    }

    private suspend fun oracle(
        source: ByteArray,
        settings: JsonObject,
        parent: Int? = null,
        index: Int = 1,
    ): ByteArray =
        probe(source) { engine ->
            val name =
                if (settings.getValue("kind").jsonPrimitive.content == "concentration")
                    "Concentration lines"
                else "Speed lines"
            val id =
                command(
                        engine,
                        """{"type":"create_vector","name":"$name","parent_id":$parent,"index":$index}""",
                    )
                    .active
            independentObjects(settings).forEach { shape ->
                command(
                    engine,
                    """{"type":"add_vector_object","id":$id,"index":null,"object":${shape.request()}}""",
                )
            }
            engine.call(EngineOperation.SAVE)
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
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" }
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
            repeat(20) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        fun nodes() =
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
                ?: error("Missing line generator control: $label")

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        suspend fun click(label: String) {
            settle()
            if (Tool.entries.any { trValue(it.label, Language.English) == label }) {
                val visible =
                    withContext(Dispatchers.Main) {
                        nodes().any {
                            it.config.contains(SemanticsActions.OnClick) &&
                                !it.boundsInWindow.isEmpty &&
                                descendants(it).any { child -> matches(child, label) }
                        }
                    }
                if (!visible) click("More tools")
            }
            val point =
                withContext(Dispatchers.Main) {
                    val value =
                        nodes()
                            .filter {
                                it.config.contains(SemanticsActions.OnClick) &&
                                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                                    !it.config.contains(SemanticsProperties.Disabled) &&
                                    !it.boundsInWindow.isEmpty &&
                                    descendants(it).any { child -> matches(child, label) }
                            }
                            .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                            ?: node(label)
                    assertFalse(
                        value.config.contains(SemanticsProperties.Disabled),
                        "$label: generator=${controller.lineGeneratorPreview?.settings?.request()}, updating=${controller.lineGeneratorPreview?.updating}, committing=${controller.lineGeneratorPreview?.committing}, draftError=${controller.lineGeneratorPreview?.error}, controllerError=${controller.error}",
                    )
                    value.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun reveal(label: String, action: SemanticsPropertyKey<*>) {
            repeat(20) {
                val scroll =
                    withContext(Dispatchers.Main) {
                        val target =
                            nodes()
                                .filter {
                                    it.config.contains(action) &&
                                        descendants(it).any { child -> matches(child, label) }
                                }
                                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                                ?: error("Missing scrollable line generator control: $label")
                        val owner =
                            scene.semanticsOwners.single {
                                descendants(it.rootSemanticsNode).any { candidate ->
                                    candidate.id == target.id
                                }
                            }
                        val containers =
                            descendants(owner.rootSemanticsNode)
                                .filter {
                                    it.config.contains(
                                        SemanticsProperties.VerticalScrollAxisRange
                                    ) &&
                                        descendants(it).any { candidate ->
                                            candidate.id == target.id
                                        }
                                }
                                .toList()
                        assertEquals(1, containers.size, "$label has nested parameter scrolling")
                        val container = containers.single()
                        val targetTop = target.positionInWindow.y
                        val targetBottom = targetTop + target.size.height
                        if (
                            !target.boundsInWindow.isEmpty &&
                                targetTop >= container.boundsInWindow.top - 1f &&
                                targetBottom <= container.boundsInWindow.bottom + 1f
                        )
                            null
                        else
                            container.boundsInWindow.center to
                                if (targetTop < container.boundsInWindow.top) -4f else 4f
                    } ?: return
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        scroll.first,
                        scrollDelta = Offset(0f, scroll.second),
                    )
                    render().close()
                }
                settle()
            }
            error("Line generator control did not scroll into view: $label")
        }

        suspend fun slider(label: String, fraction: Float) {
            val quick = label == "Line count" || label == "Line width"
            if (quick) {
                withContext(Dispatchers.Main) {
                    assertTrue(
                        nodes().none {
                            it.config.contains(SemanticsActions.SetProgress) && matches(it, label)
                        },
                        "$label is duplicated in the sidebar",
                    )
                }
                click(label)
                withContext(Dispatchers.Main) {
                    val capsule =
                        nodes().single {
                            it.config.getOrNull(SemanticsProperties.TestTag) ==
                                "capsule-slider-popup"
                        }
                    val slider = node(label, SemanticsActions.SetProgress)
                    assertTrue(descendants(capsule).any { it.id == slider.id })
                    assertEquals(
                        1,
                        nodes().count {
                            it.config.contains(SemanticsActions.SetProgress) && matches(it, label)
                        },
                    )
                }
            } else reveal(label, SemanticsActions.SetProgress)
            val points =
                withContext(Dispatchers.Main) {
                    val slider = node(label, SemanticsActions.SetProgress)
                    val bounds = slider.boundsInWindow
                    val range = slider.config[SemanticsProperties.ProgressBarRangeInfo]
                    val current =
                        (range.current - range.range.start) /
                            (range.range.endInclusive - range.range.start)
                    val left = bounds.left + 12f
                    val width = bounds.width - 24f
                    Offset(left + width * current, bounds.center.y) to
                        Offset(left + width * fraction, bounds.center.y)
                }
            pointer(PointerEventType.Press, points.first)
            pointer(PointerEventType.Move, points.second)
            pointer(PointerEventType.Release, points.second)
            pointer(PointerEventType.Move, Offset.Zero)
            previewReady()
            if (quick) {
                val settings = controller.lineGeneratorPreview!!.settings.request()
                val outside =
                    withContext(Dispatchers.Main) {
                        Offset(
                            node("Tool options").boundsInWindow.center.x,
                            canvasBounds().top + 1f,
                        )
                    }
                pointer(PointerEventType.Press, outside)
                pointer(PointerEventType.Release, outside)
                pointer(PointerEventType.Move, Offset.Zero)
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(
                        nodes().none {
                            it.config.getOrNull(SemanticsProperties.TestTag) ==
                                "capsule-slider-popup"
                        }
                    )
                    assertEquals(settings, controller.lineGeneratorPreview!!.settings.request())
                }
            }
        }

        suspend fun openLineSettings() {
            val settings = controller.lineGeneratorPreview!!.settings.request()
            val shown =
                withContext(Dispatchers.Main) {
                    nodes().any {
                        it.config.contains(SemanticsActions.OnClick) &&
                            matches(it, "Tool options") &&
                            it.config.getOrNull(SemanticsProperties.Selected) == true &&
                            !it.boundsInWindow.isEmpty
                    }
                }
            if (!shown) click("Line settings")
            reveal("Line opacity", SemanticsActions.SetProgress)
            withContext(Dispatchers.Main) {
                assertTrue(node("Tool options").config[SemanticsProperties.Selected])
                assertNotNull(node("Line opacity", SemanticsActions.SetProgress))
                assertEquals(settings, controller.lineGeneratorPreview!!.settings.request())
            }
        }

        suspend fun text(label: String, value: String) {
            reveal(label, SemanticsActions.SetText)
            withContext(Dispatchers.Main) {
                assertTrue(
                    node(label, SemanticsActions.SetText).config[SemanticsActions.SetText].action!!(
                        AnnotatedString(value)
                    )
                )
                render().close()
            }
            settle()
        }

        fun position(point: Offset) =
            controller.viewport.toView(point, view, controller.document) + canvasBounds().topLeft

        suspend fun fit() =
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(zoom = 4f / Viewport().scale(view, controller.document))
                assertEquals(4f, controller.viewport.scale(view, controller.document))
            }

        suspend fun drag(start: Offset, end: Offset) {
            pointer(PointerEventType.Press, position(start))
            pointer(PointerEventType.Move, position(end))
            pointer(PointerEventType.Release, position(end))
            pointer(PointerEventType.Move, Offset.Zero)
            previewReady()
        }

        suspend fun previewReady() = waitFor {
            controller.lineGeneratorPreview?.let {
                !it.updating && !it.committing && it.error == null
            } == true
        }

        suspend fun begin(kind: LineGeneratorKind = LineGeneratorKind.Concentration) {
            assertEquals(Tool.Brush, controller.tool)
            click("Comic lines")
            previewReady()
            if (kind == LineGeneratorKind.Speed) {
                click("Line type")
                click("Speed lines")
                previewReady()
            }
        }

        fun pixels(frame: RenderFrame = controller.frame) =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in frame.tiles.values) {
                    val source = imagePixels(tile.image)
                    val left = tile.x * tile.size
                    val top = tile.y * tile.size
                    for (row in 0 until minOf(tile.size, controller.document.height - top)) source
                        .copyInto(
                            output,
                            (top + row) * controller.document.width + left,
                            row * tile.image.width,
                            row * tile.image.width +
                                minOf(tile.size, controller.document.width - left),
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

        suspend fun toggleBeside(label: String) {
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    val owner =
                        scene.semanticsOwners.single {
                            descendants(it.rootSemanticsNode).any { candidate ->
                                matches(candidate, label)
                            }
                        }
                    val text =
                        descendants(owner.rootSemanticsNode)
                            .filter { matches(it, label) }
                            .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                    val toggle =
                        descendants(owner.rootSemanticsNode)
                            .filter {
                                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                                    !it.boundsInWindow.isEmpty
                            }
                            .minBy {
                                abs(it.boundsInWindow.center.y - text.boundsInWindow.center.y)
                            }
                    assertTrue(
                        abs(toggle.boundsInWindow.center.y - text.boundsInWindow.center.y) < 24f
                    )
                    toggle.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun selectObject(id: Int) {
            val name = controller.vectorObjects!!.objects.single { it.id == id }.name
            val names = controller.vectorObjects!!.objects.map { it.name }.toSet()
            settle()
            withContext(Dispatchers.Main) {
                val list =
                    nodes()
                        .filter { candidate ->
                            candidate.config.contains(SemanticsActions.ScrollToIndex) &&
                                descendants(candidate).any { row ->
                                    row.config.getOrNull(SemanticsProperties.Role) ==
                                        Role.RadioButton &&
                                        names.any { label ->
                                            descendants(row).any { child -> matches(child, label) }
                                        }
                                }
                        }
                        .minByOrNull { it.boundsInWindow.height }
                        ?: error("Missing generated vector object list")
                val index =
                    controller.vectorObjects!!.objects.asReversed().indexOfFirst { it.id == id }
                assertTrue(index >= 0)
                assertTrue(assertNotNull(list.config[SemanticsActions.ScrollToIndex].action)(index))
                render().close()
            }
            settle()
            val point =
                withContext(Dispatchers.Main) {
                    nodes()
                        .single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                descendants(it).any { child -> matches(child, name) }
                        }
                        .boundsInWindow
                        .center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            waitFor {
                controller.selectedVectorObject?.objectId == id &&
                    controller.selectedVectorObject?.revision == controller.document.revision
            }
        }
    }

    private suspend fun withSession(
        source: ByteArray,
        height: Int = 1200,
        block: suspend Session.() -> Unit,
    ) {
        val files = MemoryFiles(source)
        val previousAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1600, height) {
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
            session.fit()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.close()
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = previousAppearance) {} }
                try {
                    restore.render(0).close()
                } finally {
                    restore.close()
                }
            }
            scope.cancel()
        }
    }

    private suspend fun assertReadonlyPreview(
        source: ByteArray,
        settings: JsonObject,
        parent: Int?,
        index: Int,
    ) =
        probe(source) { engine ->
            val state = json(engine, """{"type":"state"}""")
            val before = engine.call(EngineOperation.SAVE)
            engine.call(EngineOperation.FRAME, byteArrayOf(1))
            val request = buildJsonObject {
                put("id", state.getValue("active"))
                put("revision", state.getValue("revision"))
                put("selection_id", state.getValue("selectionId"))
                put("mask_editing", false)
                put("mask_id", JsonNull)
                putJsonObject("action") {
                    put("kind", "generate_lines")
                    put(
                        "name",
                        if (settings.getValue("kind").jsonPrimitive.content == "concentration")
                            "Concentration lines"
                        else "Speed lines",
                    )
                    put("parent_id", parent)
                    put("index", index)
                    put("settings", settings)
                }
            }
                .toString()
                .encodeToByteArray()
            val first = engine.call(EngineOperation.PREVIEW_LAYER_ACTION, request)
            assertContentEquals(first, engine.call(EngineOperation.PREVIEW_LAYER_ACTION, request))
            assertEquals(state, json(engine, """{"type":"state"}"""))
            assertContentEquals(before, engine.call(EngineOperation.SAVE))
            assertEquals(0, intAt(engine.call(EngineOperation.FRAME, byteArrayOf(1)), 12))
        }

    private suspend fun assertRejected(
        source: ByteArray,
        setup: (NativeEngine) -> Unit = {},
        change: (JsonObject) -> JsonObject,
    ) =
        probe(source) { engine ->
            setup(engine)
            val state = command(engine, """{"type":"state"}""")
            val saved = engine.call(EngineOperation.SAVE)
            engine.call(EngineOperation.FRAME, byteArrayOf(1))
            val request = buildJsonObject {
                put("type", "generate_lines")
                put("id", state.active)
                put("revision", state.revision)
                put("selection_id", state.selectionId)
                put("mask_editing", state.maskEditing)
                put("mask_id", state.activeMaskId.takeIf { state.maskEditing })
                put("name", "Concentration lines")
                put("parent_id", JsonNull)
                put("index", 1)
                put(
                    "settings",
                    defaultLineGenerator(state, BrushSettings(), LineGeneratorKind.Concentration)
                        .request(),
                )
            }
            assertFailsWith<IllegalStateException> {
                engine.call(EngineOperation.COMMAND, change(request).toString().encodeToByteArray())
            }
            assertEquals(state, command(engine, """{"type":"state"}"""))
            assertContentEquals(saved, engine.call(EngineOperation.SAVE))
            assertEquals(0, intAt(engine.call(EngineOperation.FRAME, byteArrayOf(1)), 12))
        }

    @Test
    fun concentrationPreviewIsDeterministicReadonlyAndOneUndoRestoresEverySourcePlaneAndId() =
        runBlocking {
            assertEquals(0.6650877773527619, sample(42u, 0, 0x7769647468uL))
            assertEquals(0.5314775068341939, sample(42u, 0, 0x616e676c65uL))
            assertEquals(0.9293050186846795, sample(42u, 3, 0x6c61746572616cuL))
            val source = project()
            val raw = planes(source)
            withSession(source) {
                val original = save()
                val before = controller.document
                val frame = controller.frame
                val images = controller.previews.images.toMap()
                val beforePixels = withContext(Dispatchers.Main) { pixels() }
                begin()
                slider("Line count", 7f / 255f)
                val draft = assertNotNull(controller.lineGeneratorPreview)
                val settings = draft.settings.request()
                assertTrue(draft.settings.count in 1..16)
                assertEquals(256, controller.document.maxGeneratedLines)
                val expected = oracle(original, settings, draft.parentId, draft.index)
                val expectedPixels = probe(expected) { nativePixels(it) }
                assertContentEquals(
                    expectedPixels,
                    withContext(Dispatchers.Main) { pixels(draft.frame) },
                )
                assertFalse(expectedPixels.contentEquals(beforePixels))
                val center = draft.settings.center
                val inner = draft.settings.inner
                for (y in 0 until before.height) for (x in 0 until before.width) {
                    val ellipse =
                        ((x + 0.5 - center.x) / inner.rx).pow(2) +
                            ((y + 0.5 - center.y) / inner.ry).pow(2)
                    if (ellipse <= 1.0)
                        assertEquals(
                            beforePixels[y * before.width + x],
                            expectedPixels[y * before.width + x],
                            "Central blank $x,$y",
                        )
                }
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertSame(frame, controller.frame)
                images.forEach { (id, image) ->
                    assertSame(image, controller.previews.images.getValue(id))
                }
                assertReadonlyPreview(original, settings, draft.parentId, draft.index)
                click("Cancel lines")
                waitFor { controller.lineGeneratorPreview == null }
                assertEquals(before, controller.document)
                assertContentEquals(original, save())
                assertContentEquals(beforePixels, withContext(Dispatchers.Main) { pixels() })
                begin()
                slider("Line count", 7f / 255f)
                assertEquals(settings, controller.lineGeneratorPreview!!.settings.request())
                assertContentEquals(
                    expectedPixels,
                    withContext(Dispatchers.Main) {
                        pixels(controller.lineGeneratorPreview!!.frame)
                    },
                )
                click("Generate lines")
                waitFor {
                    controller.document.revision == before.revision + 1 &&
                        controller.lineGeneratorPreview == null &&
                        controller.vectorObjects?.id == controller.document.active
                }
                val id = controller.document.active
                assertEquals(Tool.Vector, controller.tool)
                assertEquals(VectorEditorTool.Edit, controller.vectorTool)
                val committed = save()
                val expectedObjects = independentObjects(settings)
                assertEquals(expectedObjects, objects(committed, id).values.toList())
                assertEquals(
                    (1..expectedObjects.size).toList(),
                    objects(committed, id).keys.toList(),
                )
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                assertPlanes(raw, planes(committed))
                click("Undo")
                waitFor { controller.document.revision == before.revision + 2 }
                assertEquals(before.layers, controller.document.layers)
                assertEquals(before.active, controller.document.active)
                assertEquals(before.activeMaskId, controller.document.activeMaskId)
                assertFalse(controller.document.canUndo)
                assertContentEquals(original, save())
                assertContentEquals(beforePixels, withContext(Dispatchers.Main) { pixels() })
                click("Redo")
                waitFor { controller.document.revision == before.revision + 3 }
                assertEquals(id, controller.document.active)
                assertEquals(expectedObjects, objects(save(), id).values.toList())
                assertContentEquals(committed, save())
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
            }
        }

    @Test
    fun realSpeedSlidersAndOriginDragProduceTheIndependentCompleteRibbonGeometryAndLatestPixels() =
        runBlocking {
            val source = project()
            val raw = planes(source)
            withSession(source, height = 600) {
                val original = save()
                val before = controller.document
                val frame = controller.frame
                begin(LineGeneratorKind.Speed)
                val initial = controller.lineGeneratorPreview!!.settings
                slider("Line count", 7f / 255f)
                slider("Line width", 0.03f)
                val center = controller.lineGeneratorPreview!!.settings.origin.offset()
                drag(center, Offset(32f, 32f))
                assertEquals(
                    AssistantPoint(32f, 32f),
                    controller.lineGeneratorPreview!!.settings.origin,
                )
                openLineSettings()
                val controls =
                    listOf(
                        "Line opacity" to 0.65f,
                        "Random variation" to 0.6f,
                        "Start taper" to 0.25f,
                        "End taper" to 0.65f,
                        "Line angle" to 0.52f,
                        "Line length" to 0.08f,
                        "Line spacing" to 0.025f,
                    )
                for ((label, fraction) in controls) {
                    val previous = controller.lineGeneratorPreview!!.settings.request()
                    slider(label, fraction)
                    assertNotEquals(
                        previous,
                        controller.lineGeneratorPreview!!.settings.request(),
                        label,
                    )
                    assertEquals(before, controller.document)
                    assertSame(frame, controller.frame)
                }
                text("Seed", "1999")
                previewReady()
                assertEquals(1999L, controller.lineGeneratorPreview!!.settings.seed)
                openLineSettings()
                val draft = controller.lineGeneratorPreview!!
                assertNotEquals(initial, draft.settings)
                val settings = draft.settings.request()
                val shapes = independentObjects(settings)
                assertTrue(
                    shapes.all {
                        it.geometry is VectorGeometry.Path &&
                            it.style.fill != null &&
                            it.style.stroke == null
                    }
                )
                assertTrue(shapes.all { (it.geometry as VectorGeometry.Path).segments.size == 7 })
                val expected = oracle(original, settings, draft.parentId, draft.index)
                val expectedPixels = probe(expected) { nativePixels(it) }
                assertContentEquals(
                    expectedPixels,
                    withContext(Dispatchers.Main) { pixels(draft.frame) },
                )
                assertReadonlyPreview(original, settings, draft.parentId, draft.index)
                assertEquals(before, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                click("Generate lines")
                waitFor {
                    controller.document.revision == before.revision + 1 &&
                        controller.vectorObjects?.id == controller.document.active
                }
                val id = controller.document.active
                val committed = save()
                assertEquals(shapes, objects(committed, id).values.toList())
                assertEquals((1..shapes.size).toList(), objects(committed, id).keys.toList())
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                assertPlanes(raw, planes(committed))
                click("Undo")
                waitFor { controller.document.revision == before.revision + 2 }
                assertContentEquals(original, save())
                assertFalse(controller.document.canUndo)
                click("Redo")
                waitFor { controller.document.revision == before.revision + 3 }
                assertContentEquals(committed, save())
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                val idle = controller.frame
                val images = controller.previews.images.toMap()
                settle()
                assertSame(idle, controller.frame)
                images.forEach { (key, image) ->
                    assertSame(image, controller.previews.images.getValue(key))
                }
            }
        }

    @Test
    fun nestedGroupAndAdjustmentClippingPreviewMatchesCommitAndSavedLinesRemainTrulyEditable() =
        runBlocking {
            for (isolation in listOf("isolated", "pass_through")) {
                val source = project(nested = isolation)
                val raw = planes(source)
                withSession(source, height = 600) {
                    val original = save()
                    val before = controller.document
                    val oldPixels = withContext(Dispatchers.Main) { pixels() }
                    begin()
                    slider("Line count", 7f / 255f)
                    drag(Offset(64f, 48f), Offset(72f, 40f))
                    assertEquals(
                        AssistantPoint(72f, 40f),
                        controller.lineGeneratorPreview!!.settings.center,
                    )
                    openLineSettings()
                    for ((label, fraction) in
                        listOf(
                            "Start angle" to 0.6f,
                            "Sweep" to 0.8f,
                            "Inner radius X" to 0.12f,
                            "Inner radius Y" to 0.12f,
                            "Outer radius X" to 0.55f,
                            "Outer radius Y" to 0.55f,
                        )) {
                        val prior = controller.lineGeneratorPreview!!.settings.request()
                        slider(label, fraction)
                        assertNotEquals(
                            prior,
                            controller.lineGeneratorPreview!!.settings.request(),
                            label,
                        )
                    }
                    openLineSettings()
                    val draft = controller.lineGeneratorPreview!!
                    assertEquals(3, draft.parentId)
                    assertEquals(2, draft.index)
                    val settings = draft.settings.request()
                    val expected = oracle(original, settings, 3, 2)
                    val expectedPixels = probe(expected) { nativePixels(it) }
                    assertContentEquals(
                        expectedPixels,
                        withContext(Dispatchers.Main) { pixels(draft.frame) },
                    )
                    assertFalse(expectedPixels.contentEquals(oldPixels))
                    assertEquals(before, controller.document)
                    assertReadonlyPreview(original, settings, 3, 2)
                    click("Generate lines")
                    waitFor {
                        controller.document.revision == before.revision + 1 &&
                            controller.vectorObjects?.id == controller.document.active
                    }
                    val id = controller.document.active
                    assertEquals(
                        listOf(1, 4, id, 5),
                        controller.document.layers.filter { it.parentId == 3 }.map { it.id },
                    )
                    assertEquals(1, controller.document.layers.single { it.id == 4 }.clippingBase)
                    assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                    val generated = save()
                    val expectedObjects = independentObjects(settings)
                    assertEquals(expectedObjects, objects(generated, id).values.toList())
                    assertPlanes(raw, planes(generated))
                    click("Undo")
                    waitFor { controller.document.revision == before.revision + 2 }
                    assertEquals(before.layers, controller.document.layers)
                    assertContentEquals(original, save())
                    assertContentEquals(oldPixels, withContext(Dispatchers.Main) { pixels() })
                    click("Redo")
                    waitFor { controller.document.revision == before.revision + 3 }
                    assertContentEquals(generated, save())
                    if (isolation == "isolated") {
                        open(generated)
                        waitFor { controller.vectorObjects?.id == id }
                        assertFalse(controller.document.canUndo)
                        assertEquals(expectedObjects, objects(save(), id).values.toList())
                        if (
                            !withContext(Dispatchers.Main) {
                                node("Layers").config[SemanticsProperties.Selected]
                            }
                        )
                            click("Layers")
                        click("Edit nodes")
                        selectObject(1)
                        val first = controller.selectedVectorObject!!.`object`
                        val node = first.geometry.nodes().first()
                        val moved = node.position + Offset(2f, 3f)
                        val movedPointer = position(first.worldPoint(moved))
                        val sampled =
                            first.localPoint(
                                controller.viewport.toDocument(
                                    movedPointer - canvasBounds().topLeft,
                                    view,
                                    controller.document,
                                )
                            )
                        assertTrue((sampled - moved).getDistance() < 0.0001f)
                        val edited = first.copy(geometry = first.geometry.withNode(node, sampled))
                        val revision = controller.document.revision
                        pointer(PointerEventType.Press, position(first.worldPoint(node.position)))
                        pointer(PointerEventType.Move, movedPointer)
                        waitFor {
                            controller.vectorPreview?.let {
                                it.canonical.renderedAction == it.request()
                            } == true
                        }
                        assertEquals(edited, controller.vectorPreview!!.value)
                        val preview =
                            withContext(Dispatchers.Main) {
                                pixels(controller.vectorPreview!!.canonical.frame)
                            }
                        pointer(PointerEventType.Release, movedPointer)
                        pointer(PointerEventType.Move, Offset.Zero)
                        waitFor {
                            controller.document.revision == revision + 1 &&
                                controller.vectorPreview == null
                        }
                        assertContentEquals(preview, withContext(Dispatchers.Main) { pixels() })
                        val editedProject = save()
                        val all = objects(editedProject, id)
                        assertEquals(
                            expectedObjects.mapIndexed { index, value ->
                                if (index == 0) edited else value
                            },
                            all.values.toList(),
                        )
                        assertPlanes(raw, planes(editedProject))
                        waitFor {
                            controller.selectedVectorObject?.let {
                                it.objectId == 1 &&
                                    it.revision == controller.document.revision &&
                                    it.`object` == edited
                            } == true
                        }
                        selectObject(1)
                        click("Object visibility")
                        waitFor {
                            controller.document.revision == revision + 2 &&
                                controller.selectedVectorObject?.`object`?.visible == false
                        }
                        val hidden = save()
                        val hiddenSpecs = objects(hidden, id)
                        assertEquals(all + (1 to edited.copy(visible = false)), hiddenSpecs)
                        val hiddenOracle =
                            probe(editedProject) { engine ->
                                json(
                                    engine,
                                    """{"type":"set_vector_object","id":$id,"object_id":1,"object":${edited.copy(visible = false).request()}}""",
                                )
                                nativePixels(engine)
                            }
                        assertContentEquals(
                            hiddenOracle,
                            withContext(Dispatchers.Main) { pixels() },
                        )
                        click("Delete vector object")
                        waitFor {
                            controller.document.revision == revision + 3 &&
                                controller.selectedVectorObject == null
                        }
                        assertEquals(hiddenSpecs - 1, objects(save(), id))
                        assertContentEquals(
                            hiddenOracle,
                            withContext(Dispatchers.Main) { pixels() },
                        )
                        click("Undo")
                        waitFor { controller.document.revision == revision + 4 }
                        assertContentEquals(hidden, save())
                    }
                }
            }
        }

    @Test
    fun trueSvgPathsAndExplicitFlattenedPsdOraPreserveAlphaSourceHistoryWhileInvalidTargetsRejectAtomically() =
        runBlocking {
            val source = project(masks = false)
            val raw = planes(source)
            withSession(source) {
                begin(LineGeneratorKind.Speed)
                slider("Line count", 7f / 255f)
                val expected =
                    independentObjects(controller.lineGeneratorPreview!!.settings.request())
                val revision = controller.document.revision
                click("Generate lines")
                waitFor {
                    controller.document.revision == revision + 1 &&
                        controller.vectorObjects?.id == controller.document.active
                }
                val id = controller.document.active
                val original = save()
                val state = controller.document
                val frame = controller.frame
                val pixels = withContext(Dispatchers.Main) { pixels() }
                assertTrue(pixels.any { it.ushr(24) == 0 })
                assertTrue(pixels.any { it.ushr(24) in 1..254 })
                click("Export image")
                click("SVG")
                val count = files.exports.get()
                click("Export")
                waitFor { files.exports.get() == count + 1 }
                val result = files.exported.get()
                assertEquals(ExportFormat.Svg, result.format)
                val document =
                    withContext(Dispatchers.Default) {
                        DocumentBuilderFactory.newInstance()
                            .apply {
                                isNamespaceAware = true
                                setFeature(
                                    "http://apache.org/xml/features/disallow-doctype-decl",
                                    true,
                                )
                            }
                            .newDocumentBuilder()
                            .parse(ByteArrayInputStream(result.bytes))
                    }
                assertEquals("128", document.documentElement.getAttribute("width"))
                assertEquals("96", document.documentElement.getAttribute("height"))
                assertEquals(0, document.getElementsByTagName("image").length)
                val paths = document.getElementsByTagName("path")
                assertEquals(expected.size, paths.length)
                for ((index, shape) in expected.withIndex()) {
                    val path = paths.item(index) as org.w3c.dom.Element
                    assertEquals("object-${index + 1}", path.getAttribute("id"))
                    val segments = (shape.geometry as VectorGeometry.Path).segments
                    val text = path.getAttribute("d")
                    assertEquals(
                        listOf("M", "C", "C", "L", "C", "C", "Z"),
                        Regex("[A-Za-z]").findAll(text).map { it.value }.toList(),
                    )
                    val coordinates = segments.flatMap {
                        when (it) {
                            is VectorSegment.Move -> listOf(it.x, it.y)
                            is VectorSegment.Line -> listOf(it.x, it.y)
                            is VectorSegment.Cubic ->
                                listOf(it.c1x, it.c1y, it.c2x, it.c2y, it.x, it.y)
                            else -> emptyList()
                        }
                    }
                    assertEquals(
                        coordinates,
                        Regex("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
                            .findAll(text)
                            .map { it.value.toFloat() }
                            .toList(),
                    )
                    assertEquals("none", path.getAttribute("stroke"))
                }
                assertEquals(state, controller.document)
                assertSame(frame, controller.frame)
                assertContentEquals(original, save())
                for (format in listOf(ExportFormat.Psd, ExportFormat.Ora)) {
                    probe(original) { engine ->
                        val before = command(engine, """{"type":"state"}""")
                        val saved = engine.call(EngineOperation.SAVE)
                        val request = Json {
                            encodeDefaults = true
                        }
                            .encodeToString(ExportOptions(format))
                            .encodeToByteArray()
                        assertTrue(
                            assertFailsWith<IllegalStateException> {
                                    engine.call(EngineOperation.EXPORT_IMAGE, request)
                                }
                                .message!!
                                .contains("矢量")
                        )
                        assertEquals(before, command(engine, """{"type":"state"}"""))
                        assertContentEquals(saved, engine.call(EngineOperation.SAVE))
                    }
                    val before = controller.document
                    val liveFrame = controller.frame
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
                    val output = files.exported.get()
                    assertEquals(format, output.format)
                    assertEquals(saves, files.saves.get())
                    assertEquals(before, controller.document)
                    assertSame(liveFrame, controller.frame)
                    assertContentEquals(original, save())
                    assertContentEquals(pixels, probe(output.bytes) { nativePixels(it) })
                    assertPlanes(raw, planes(save()))
                    open(output.bytes)
                    assertTrue(controller.document.layers.all { it.kind == LayerKind.Raster })
                    assertContentEquals(pixels, withContext(Dispatchers.Main) { pixels() })
                    open(original)
                    waitFor { controller.vectorObjects?.id == id }
                    assertEquals(expected, objects(save(), id).values.toList())
                    assertContentEquals(pixels, withContext(Dispatchers.Main) { pixels() })
                }
            }
            val indexed = project(indexed = true)
            assertRejected(indexed) { it }
            val indexedRaw = planes(indexed)
            withSession(indexed) {
                val state = controller.document
                val before = save()
                val disabled =
                    withContext(Dispatchers.Main) {
                        node("Comic lines").config.contains(SemanticsProperties.Disabled)
                    }
                assertTrue(disabled)
                assertNull(controller.lineGeneratorPreview)
                assertEquals(state, controller.document)
                assertContentEquals(before, save())
                assertPlanes(indexedRaw, planes(before))
            }
            val masked = project()
            assertRejected(
                masked,
                { engine ->
                    val layer = command(engine, """{"type":"state"}""").layers.single()
                    command(
                        engine,
                        """{"type":"set_mask_editing","id":1,"mask_id":${layer.masks.last().id},"enabled":true}""",
                    )
                },
            ) {
                it
            }
            assertRejected(
                source,
                { engine ->
                    command(
                        engine,
                        """{"type":"select","rect":{"left":8,"top":8,"right":16,"bottom":16}}""",
                    )
                },
            ) {
                it
            }
            assertRejected(source) { JsonObject(it + ("index" to JsonPrimitive(99))) }
            assertRejected(source) { JsonObject(it + ("mask_id" to JsonPrimitive(99))) }
            val nested = project(nested = "pass_through")
            assertRejected(nested) {
                JsonObject(it + mapOf("parent_id" to JsonPrimitive(3), "index" to JsonPrimitive(1)))
            }
            withSession(masked) {
                val second = controller.document.layers.single().masks.last().id
                withContext(Dispatchers.Main) {
                    controller.selectLayer(1, mask = true, maskId = second)
                }
                waitFor {
                    controller.document.maskEditing &&
                        controller.document.activeMaskId == second &&
                        node("Comic lines").config.contains(SemanticsProperties.Disabled)
                }
                withContext(Dispatchers.Main) {
                    assertTrue(node("Comic lines").config.contains(SemanticsProperties.Disabled))
                }
                assertNull(controller.lineGeneratorPreview)
                withContext(Dispatchers.Main) { controller.selectLayer(1, mask = false) }
                waitFor { !controller.document.maskEditing }
                withContext(Dispatchers.Main) {
                    controller.command("select") {
                        putJsonObject("rect") {
                            put("left", 8)
                            put("top", 8)
                            put("right", 16)
                            put("bottom", 16)
                        }
                    }
                }
                waitFor {
                    controller.document.selection != null &&
                        node("Comic lines").config.contains(SemanticsProperties.Disabled)
                }
                withContext(Dispatchers.Main) {
                    assertTrue(node("Comic lines").config.contains(SemanticsProperties.Disabled))
                }
                assertNull(controller.lineGeneratorPreview)
            }
        }
}
