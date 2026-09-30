package app.podor.desktop

import androidx.compose.material3.DividerDefaults
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AnimationWorkflowRenderingTest {
    private data class Exported(val format: ExportFormat, val bytes: ByteArray)

    private class MemoryFiles(project: ByteArray, val appearance: Appearance = Appearance.Light) :
        ProjectFiles {
        val input = AtomicReference(project.copyOf())
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val opens = AtomicInteger()
        val exports = AtomicInteger()
        override val exportFormats = listOf(ExportFormat.Png)
        override val animationExportFormats = AnimationExportFormat.entries

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
                    Preferences(language = Language.English, appearance = appearance)
                )
                .encodeToByteArray()
        }

        override suspend fun writePreferences(bytes: ByteArray) {
            assertFalse(EventQueue.isDispatchThread())
        }
    }

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun request(info: DocumentInfo, value: String): JsonObject {
        val source = Json.parseToJsonElement(value).jsonObject
        val animation = info.animation
        return buildJsonObject {
            source.forEach { (key, entry) -> put(key, entry) }
            put("revision", info.revision)
            if (
                animation != null &&
                    source["type"]?.jsonPrimitive?.content !in
                        setOf("select_frame", "state", "undo", "redo")
            ) {
                put("frame_id", animation.activeFrameId)
                put("cel_id", animation.activeCelId?.let(::JsonPrimitive) ?: JsonNull)
                put("target_layer_id", info.active)
            }
        }
    }

    private fun command(engine: NativeEngine, value: String): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(
                    EngineOperation.COMMAND,
                    request(state(engine), value).toString().encodeToByteArray(),
                )
                .decodeToString()
        )

    private suspend fun project(mask: Boolean = false): ByteArray =
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
                    """{"type":"fill","x":0,"y":0,"color":[20,80,100,255],"tolerance":0}""",
                )
                command(engine, """{"type":"select","rect":null}""")
                command(
                    engine,
                    """{"type":"set_layer","id":1,"name":"Source","visible":true,"opacity":1}""",
                )
                if (mask) {
                    command(engine, """{"type":"add_mask","mode":"reveal","name":"Stencil"}""")
                    command(engine, """{"type":"set_mask_editing","id":1,"enabled":false}""")
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

    private suspend fun cornerAnimationProject(): ByteArray =
        probe(project()) { engine ->
            command(
                engine,
                """{"type":"fill","x":80,"y":40,"color":[20,80,100,255],"tolerance":0}""",
            )
            for ((left, top, color) in
                listOf(
                    Triple(0, 0, "[220,48,56,255]"),
                    Triple(112, 0, "[40,176,88,255]"),
                    Triple(0, 80, "[48,96,224,255]"),
                    Triple(112, 80, "[232,192,40,255]"),
                )) {
                command(
                    engine,
                    """{"type":"select","rect":{"left":$left,"top":$top,"right":${left + 16},"bottom":${top + 16}}}""",
                )
                command(
                    engine,
                    """{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""",
                )
            }
            command(engine, """{"type":"select","rect":null}""")
            command(engine, """{"type":"enable_animation","duration_ms":100}""")
            engine.call(EngineOperation.SAVE)
        }

    private suspend fun layeredAnimationProject(): ByteArray =
        probe(project()) { engine ->
            val layer = command(engine, """{"type":"add_layer"}""").active
            command(
                engine,
                """{"type":"set_layer","id":$layer,"name":"Ink track","visible":true,"opacity":1}""",
            )
            command(
                engine,
                """{"type":"select","rect":{"left":48,"top":16,"right":80,"bottom":48}}""",
            )
            command(
                engine,
                """{"type":"fill","x":48,"y":16,"color":[190,50,70,255],"tolerance":0}""",
            )
            command(engine, """{"type":"select","rect":null}""")
            val first =
                command(engine, """{"type":"enable_animation","duration_ms":100}""")
                    .animation!!
                    .activeFrameId
            command(engine, """{"type":"add_frame","index":1,"duration_ms":150}""")
            command(
                engine,
                """{"type":"fill","x":0,"y":0,"color":[40,160,80,255],"tolerance":0}""",
            )
            command(engine, """{"type":"select_frame","frame_id":$first}""")
            command(engine, """{"type":"select_layer","id":1}""")
            engine.call(EngineOperation.SAVE)
        }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, byte ->
            value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
        }

    private fun imagePixels(image: ImageBitmap) =
        IntArray(image.width * image.height).also { image.readPixels(it) }

    private fun framePixels(engine: NativeEngine, frameId: Int): IntArray {
        val before = state(engine)
        val saved = engine.call(EngineOperation.SAVE)
        val bytes =
            engine.call(
                EngineOperation.ANIMATION_FRAME,
                """{"revision":${before.revision},"frame_id":$frameId,"transparent":true}"""
                    .encodeToByteArray(),
            )
        assertEquals(before.width, intAt(bytes, 0))
        assertEquals(before.height, intAt(bytes, 4))
        val size = intAt(bytes, 8)
        var offset = 16
        return IntArray(before.width * before.height).also { output ->
            repeat(intAt(bytes, 12)) {
                val left = intAt(bytes, offset) * size
                val top = intAt(bytes, offset + 4) * size
                val pixels = imagePixels(rgbaBitmap(bytes, offset + 8, size))
                for (row in 0 until minOf(size, before.height - top)) pixels.copyInto(
                    output,
                    (top + row) * before.width + left,
                    row * size,
                    row * size + minOf(size, before.width - left),
                )
                offset += 8 + size * size * 4
            }
            assertEquals(bytes.size, offset)
            assertEquals(before, state(engine))
            assertContentEquals(saved, engine.call(EngineOperation.SAVE))
        }
    }

    private suspend fun framePixels(bytes: ByteArray, frameId: Int) =
        probe(bytes) { framePixels(it, frameId) }

    private suspend fun rawRaster(bytes: ByteArray, frameId: Int) =
        probe(bytes) { engine ->
            command(engine, """{"type":"select_frame","frame_id":$frameId}""")
            if (state(engine).maskEditing)
                command(engine, """{"type":"set_mask_editing","id":1,"enabled":false}""")
            engine.call(EngineOperation.COPY_SELECTION, byteArrayOf(0))
        }

    private suspend fun rawMask(bytes: ByteArray, frameId: Int): ByteArray =
        probe(bytes) { engine ->
            command(engine, """{"type":"select_frame","frame_id":$frameId}""")
            val info = state(engine)
            val mask = info.layers.single().masks.single()
            command(
                engine,
                """{"type":"set_mask_editing","id":1,"mask_id":${mask.id},"enabled":true}""",
            )
            val editing = state(engine)
            ByteArray(info.width * info.height) { pixel ->
                Json.parseToJsonElement(
                        engine
                            .call(
                                EngineOperation.COMMAND,
                                request(
                                        editing,
                                        """{"type":"pick","x":${pixel % info.width},"y":${pixel / info.width}}""",
                                    )
                                    .toString()
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

    private data class Sample(val point: Offset, val pressure: Float)

    private val hardBrush =
        BrushSettings(
            preset =
                BrushPreset.Ink.copy(
                    hardness = 1f,
                    stabilization = 0f,
                    sizePressure = 0.5f,
                    opacityPressure = 0f,
                ),
            size = 6f,
            opacity = 1f,
            color = 0xFFB35734,
        )

    private fun brushJson(settings: BrushSettings, eraser: Boolean) = buildJsonObject {
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
        put("eraser", eraser)
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

    private suspend fun baseline(
        bytes: ByteArray,
        samples: List<Sample>,
        brush: BrushSettings = hardBrush,
        eraser: Boolean = false,
    ): IntArray =
        probe(bytes) { engine ->
            command(engine, """{"type":"begin","brush":${brushJson(brush, eraser)}}""")
            val packet = ByteArray(samples.size * 12)
            samples.forEachIndexed { index, sample ->
                listOf(sample.point.x, sample.point.y, sample.pressure).forEachIndexed {
                    component,
                    value ->
                    repeat(4) { byte ->
                        packet[index * 12 + component * 4 + byte] =
                            (value.toBits() ushr (byte * 8)).toByte()
                    }
                }
            }
            engine.call(EngineOperation.SAMPLES, packet)
            command(engine, """{"type":"end"}""")
            framePixels(engine, state(engine).animation!!.activeFrameId)
        }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
        val density: Density,
        val width: Int,
        val height: Int,
    ) {
        private var time = 0L
        private val framePattern = Regex("(?:^| · )Frame (\\d+)$")

        fun render() = scene.render(time++ * 16_666_667L)

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
                                !controller.animationTransition &&
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

        private fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun frameNumber(label: String) =
            framePattern.find(label)?.groupValues?.get(1)?.toInt()

        private fun frameCardId(label: String): Int? {
            val number = frameNumber(label) ?: return null
            val id = controller.document.animation?.frames?.getOrNull(number - 1)?.id ?: return null
            return scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.unmergedRootSemanticsNode) }
                .singleOrNull { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        node.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains(label) == true &&
                        descendants(node).any {
                            it.config.getOrNull(SemanticsProperties.TestTag) ==
                                "animation-thumbnail-$id"
                        }
                }
                ?.id
        }

        fun thumbnailBounds(frameId: Int): Rect =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.unmergedRootSemanticsNode) }
                .single {
                    it.config.getOrNull(SemanticsProperties.TestTag) ==
                        "animation-thumbnail-$frameId"
                }
                .boundsInWindow

        fun taggedNode(tag: String): SemanticsNode =
            nodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }

        fun rulerNode(frameId: Int): SemanticsNode = taggedNode("animation-ruler-$frameId")

        fun tracksNode(): SemanticsNode =
            nodes().single {
                it.config.contains(SemanticsActions.ScrollToIndex) &&
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        private fun findNode(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
            visibleOnly: Boolean = true,
        ): SemanticsNode? {
            val frame = frameNumber(label) != null && label.startsWith("Frame ")
            val cardId = if (frame) frameCardId(label) else null
            return nodes()
                .filter {
                    it.config.contains(action) &&
                        (!visibleOnly || !it.boundsInWindow.isEmpty) &&
                        if (frame) it.id == cardId
                        else descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull {
                    if (visibleOnly) it.boundsInWindow.width * it.boundsInWindow.height
                    else it.size.width.toFloat() * it.size.height
                }
        }

        fun node(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode = findNode(label, action) ?: error("Missing animation control: $label")

        fun visibleControl(label: String) = findNode(label)

        fun popupBounds(label: String): Rect =
            nodes()
                .filter {
                    it.config.getOrNull(SemanticsProperties.PaneTitle) == "Animation settings" &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                .boundsInWindow

        fun canvasBounds(): Rect =
            nodes()
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" }
                .boundsInWindow

        fun screenshot(name: String) =
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                    val directory = Path.of("build", "reports", "screenshots")
                    Files.createDirectories(directory)
                    Files.write(directory.resolve(name), png.bytes)
                    ImageIO.read(ByteArrayInputStream(png.bytes))
                }
            }

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        private suspend fun scrollToFrame(label: String) {
            val number = frameNumber(label) ?: return
            val frame = label == "Frame $number"
            withContext(Dispatchers.Main) {
                assertTrue(number in 1..animation().frames.size, label)
                val axis =
                    if (frame) SemanticsProperties.VerticalScrollAxisRange
                    else SemanticsProperties.HorizontalScrollAxisRange
                val list =
                    nodes().single {
                        it.config.contains(SemanticsActions.ScrollToIndex) &&
                            it.config.contains(axis)
                    }
                val range = list.config[axis]
                assertTrue(range.value() >= 0f && range.value() <= range.maxValue())
                assertTrue(
                    assertNotNull(list.config[SemanticsActions.ScrollToIndex].action)(number - 1)
                )
            }
            settle()
            val scrolls =
                withContext(Dispatchers.Main) {
                    nodes()
                        .filter {
                            it.config.contains(SemanticsActions.ScrollBy) &&
                                !it.config.contains(SemanticsActions.ScrollToIndex) &&
                                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                                descendants(it).any { child -> matches(child, label) }
                        }
                        .sortedByDescending { it.size.width.toFloat() * it.size.height }
                        .toList()
                }
            for ((index, scroll) in scrolls.withIndex()) {
                for (attempt in 0 until 10) {
                    val complete =
                        withContext(Dispatchers.Main) {
                            val target =
                                scrolls.getOrNull(index + 1)
                                    ?: assertNotNull(findNode(label, visibleOnly = false))
                            val viewport = scroll.boundsInWindow
                            val top = target.positionInWindow.y
                            val bottom = top + target.size.height
                            val delta =
                                when {
                                    top < viewport.top -> top - viewport.top
                                    bottom > viewport.bottom -> bottom - viewport.bottom
                                    else -> 0f
                                }
                            if (delta == 0f) true
                            else {
                                assertTrue(
                                    assertNotNull(scroll.config[SemanticsActions.ScrollBy].action)(
                                        0f,
                                        delta,
                                    )
                                )
                                false
                            }
                        }
                    if (complete) break
                    settle()
                }
            }
            withContext(Dispatchers.Main) {
                val control = node(label)
                val bounds = control.boundsInWindow
                assertTrue(
                    bounds.left >= 0f &&
                        bounds.top >= 0f &&
                        bounds.right <= width &&
                        bounds.bottom <= height,
                    "$label escaped ${width}x$height: $bounds",
                )
                assertEquals(control.size.width.toFloat(), bounds.width, 0.5f, label)
                assertEquals(control.size.height.toFloat(), bounds.height, 0.5f, label)
            }
        }

        suspend fun click(label: String) {
            scrollToFrame(label)
            waitFor {
                findNode(label)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
            }
            val point =
                withContext(Dispatchers.Main) {
                    val button =
                        if (frameNumber(label) != null && label.startsWith("Frame ")) node(label)
                        else
                            nodes()
                                .filter {
                                    it.config.contains(SemanticsActions.OnClick) &&
                                        it.config.getOrNull(SemanticsProperties.Role) ==
                                            Role.Button &&
                                        !it.config.contains(SemanticsProperties.Disabled) &&
                                        !it.boundsInWindow.isEmpty &&
                                        descendants(it).any { child -> matches(child, label) }
                                }
                                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                                ?: node(label)
                    assertFalse(button.config.contains(SemanticsProperties.Disabled), label)
                    button.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        private fun view(): Size = canvasBounds().size

        suspend fun panel(label: String) {
            if (
                withContext(Dispatchers.Main) {
                    visibleControl(label)?.config?.getOrNull(SemanticsProperties.Selected) == true
                }
            )
                return
            if (withContext(Dispatchers.Main) { visibleControl("Show panel") != null })
                click("Show panel")
            if (
                withContext(Dispatchers.Main) {
                    visibleControl(label)?.config?.getOrNull(SemanticsProperties.Selected) != true
                }
            )
                click(label)
        }

        fun position(point: Offset) =
            controller.viewport.toView(point, view(), controller.document) + canvasBounds().topLeft

        fun sampled(point: Offset): Offset =
            controller.viewport.toDocument(
                position(point) - canvasBounds().topLeft,
                view(),
                controller.document,
            )

        suspend fun fit() {
            key(androidx.compose.ui.input.key.Key.Zero)
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(zoom = 4f / Viewport().scale(view(), controller.document))
                assertEquals(4f, controller.viewport.scale(view(), controller.document), 0.000001f)
            }
        }

        fun animation() = assertNotNull(controller.document.animation)

        fun active() = animation().activeFrameId

        fun cel(frameId: Int = active()) = animation().exposure(frameId, 1)?.celId

        suspend fun enable() {
            val before = controller.document
            panel("Animation")
            waitFor { controller.document.animation != null }
            assertEquals(before.revision + 1, controller.document.revision)
            assertEquals(1, animation().frames.size)
            assertNotNull(cel())
            assertEquals(LayerMaskScope.Cel, controller.document.layers.single().maskScope)
            fit()
        }

        suspend fun select(frameId: Int) {
            val before = controller.document
            val index = animation().frames.indexOfFirst { it.id == frameId }
            assertTrue(index >= 0)
            panel("Animation")
            click("Frame ${index + 1}")
            waitFor { active() == frameId }
            assertEquals(before.contentId, controller.document.contentId)
            assertEquals(before.canUndo, controller.document.canUndo)
            assertEquals(before.canRedo, controller.document.canRedo)
            assertEquals(
                before.revision + if (before.animation!!.activeFrameId == frameId) 0 else 1,
                controller.document.revision,
            )
        }

        suspend fun timeline(label: String): Int {
            if (withContext(Dispatchers.Main) { visibleControl(label) == null }) {
                panel("Animation")
                if (withContext(Dispatchers.Main) { visibleControl(label) == null })
                    click("Animation settings")
            }
            val before = controller.document.revision
            click(label)
            waitFor { controller.document.revision == before + 1 }
            return active()
        }

        suspend fun settings(label: String) {
            panel("Animation")
            click("Animation settings")
            timeline(label)
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

        suspend fun stroke(
            samples: List<Sample>,
            brush: BrushSettings = hardBrush,
            eraser: Boolean = false,
        ) {
            val tool = if (eraser) Tool.Eraser else Tool.Brush
            if (controller.tool != tool) click(if (eraser) "Eraser" else "Brush")
            withContext(Dispatchers.Main) { controller.brush = brush }
            val before = controller.document
            val actual =
                withContext(Dispatchers.Main) { samples.map { it.copy(point = sampled(it.point)) } }
            actual.zip(samples).forEach { (a, b) ->
                assertTrue((a.point - b.point).getDistance() < 0.0001f)
            }
            val expected = baseline(save(), actual, brush, eraser)
            stylus(PointerEventType.Press, samples.first(), true)
            for (sample in samples.drop(1)) stylus(PointerEventType.Move, sample, true)
            stylus(PointerEventType.Release, samples.last(), false)
            pointer(PointerEventType.Move, Offset.Zero)
            waitFor {
                controller.document.revision == before.revision + 1 && !controller.drawingInput
            }
            val rendered = withContext(Dispatchers.Main) { pixels() }
            val persisted = framePixels(save(), active())
            assertTrue(
                persisted.contentEquals(rendered),
                "Published stroke differs from stored frame",
            )
            val differences = expected.indices.filter { expected[it] != persisted[it] }
            assertTrue(
                differences.isEmpty(),
                "Stroke differs at ${differences.size} pixels: " +
                    differences.take(4).joinToString { "$it:${expected[it]}:${persisted[it]}" },
            )
            assertNotEquals(before.contentId, controller.document.contentId)
        }

        fun pixels(frame: RenderFrame = controller.frame): IntArray =
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

        suspend fun text(label: String, value: String) =
            withContext(Dispatchers.Main) {
                assertTrue(
                    node(label, SemanticsActions.SetText).config[SemanticsActions.SetText].action!!(
                        AnnotatedString(value)
                    )
                )
                render().close()
            }

        suspend fun transparentExport(): ByteArray {
            val count = files.exports.get()
            click("Export image")
            val point =
                withContext(Dispatchers.Main) {
                    val bounds =
                        nodes()
                            .filter {
                                matches(it, "Transparent background") && !it.boundsInWindow.isEmpty
                            }
                            .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
                            .boundsInWindow
                    val toggle =
                        nodes()
                            .filter {
                                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                                    it.config.contains(SemanticsActions.OnClick) &&
                                    !it.config.contains(SemanticsProperties.Disabled)
                            }
                            .minBy { abs(it.boundsInWindow.center.y - bounds.center.y) }
                    assertTrue(abs(toggle.boundsInWindow.center.y - bounds.center.y) < 24f)
                    toggle.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            click("Export")
            waitFor { files.exports.get() == count + 1 }
            val exported = assertNotNull(files.exported.get())
            assertEquals(ExportFormat.Png, exported.format)
            return exported.bytes
        }

        suspend fun key(
            key: androidx.compose.ui.input.key.Key,
            alt: Boolean = false,
            command: Boolean = false,
            shift: Boolean = false,
        ) =
            withContext(Dispatchers.Main) {
                nodes()
                    .single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace"
                    }
                    .config[SemanticsActions.RequestFocus]
                    .action!!
                    .invoke()
                assertTrue(
                    scene.sendKeyEvent(
                        androidx.compose.ui.input.key.KeyEvent(
                            key,
                            androidx.compose.ui.input.key.KeyEventType.KeyDown,
                            isAltPressed = alt,
                            isCtrlPressed = command,
                            isShiftPressed = shift,
                        )
                    )
                )
                scene.sendKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(
                        key,
                        androidx.compose.ui.input.key.KeyEventType.KeyUp,
                        isAltPressed = alt,
                        isCtrlPressed = command,
                        isShiftPressed = shift,
                    )
                )
                render().close()
            }
    }

    private suspend fun withSession(
        bytes: ByteArray,
        width: Int = 1600,
        height: Int = 1200,
        appearance: Appearance = Appearance.Light,
        block: suspend Session.() -> Unit,
    ) {
        val restoreAppearance = StudioTheme.appearance
        val files = MemoryFiles(bytes, appearance)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val density = Density(1f)
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(width, height, density = density) {
                    StudioApp(controller)
                    UnsavedChangesDialog(controller)
                }
            }
        val session = Session(controller, scene, files, density, width, height)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 128 }
            if (width >= 1000) session.fit()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.close()
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = restoreAppearance) {} }
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
    fun animationPageInitializesOnceAndOnlyOnEntryWithoutRevivingAnUndoneAnimation() = runBlocking {
        val bytes = project()
        val expected =
            probe(bytes) { engine ->
                command(
                    engine,
                    """{"type":"enable_animation","duration_ms":${StudioDefaults.animationFrameDuration}}""",
                )
                engine.call(EngineOperation.SAVE)
            }
        val frameId = probe(expected) { state(it).animation!!.frames.single().id }
        val expectedPixels = framePixels(expected, frameId)
        for (appearance in Appearance.entries) for (sceneWidth in listOf(400, 1600)) {
            withSession(bytes, width = sceneWidth, appearance = appearance) {
                val original = controller.document
                for (tab in
                    listOf(StudioPanel.Layers, StudioPanel.Colors, StudioPanel.Adjustments)) {
                    panel(trValue(tab.label, Language.English))
                    waitFor { !controller.busy }
                    assertNull(controller.document.animation)
                    assertEquals(original.revision, controller.document.revision)
                    assertFalse(controller.document.canUndo)
                    assertFalse(controller.hasUnsavedChanges)
                }
                if (sceneWidth < 1000) {
                    click("Close")
                    click("Project")
                }
                assertContentEquals(bytes, save())
                panel("Animation")
                waitFor { controller.document.animation != null }
                settle()
                val initialized = controller.document
                assertEquals(original.revision + 1, initialized.revision)
                assertEquals(1, animation().frames.size)
                assertTrue(initialized.canUndo)
                withContext(Dispatchers.Main) {
                    assertNull(visibleControl("Enable animation"))
                    assertNotNull(visibleControl("Frame 1"))
                    assertContentEquals(expectedPixels, pixels())
                }
                if (sceneWidth < 1000) {
                    click("Close")
                    click("Project")
                }
                assertContentEquals(expected, save())
                panel("Animation")
                settle()
                assertEquals(initialized.revision, controller.document.revision)
                if (sceneWidth >= 1000) {
                    withContext(Dispatchers.Main) {
                        scene.constraints = Constraints.fixed(800, height)
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        scene.constraints = Constraints.fixed(sceneWidth, height)
                    }
                    settle()
                    assertEquals(initialized.revision, controller.document.revision)
                    click("Undo")
                    waitFor { controller.document.animation == null }
                    settle()
                    assertNull(controller.document.animation)
                    assertFalse(controller.document.canUndo)
                    assertContentEquals(bytes, save())
                    panel("Layers")
                    enable()
                    assertEquals(1, animation().frames.size)
                    assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
                    assertContentEquals(expectedPixels, framePixels(save(), active()))
                }
            }
        }
    }

    @Test
    fun compactSettingsStayVisibleInBothThemesAndDurationAddsOnlyOneNativeUndo() = runBlocking {
        val bytes = cornerAnimationProject()
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            withSession(bytes, width = 400, height = 800, appearance = appearance) {
                val original = controller.document
                val frameId = active()
                val expectedPixels = framePixels(bytes, frameId)
                assertEquals(100, animation().frame(frameId)!!.durationMs)
                assertFalse(original.canUndo)
                assertFalse(original.canRedo)
                panel("Animation")
                click("Animation settings")
                withContext(Dispatchers.Main) {
                    assertTrue(node("Forward").config[SemanticsProperties.Selected])
                    for (label in listOf("Reverse", "Ping pong", "Reverse ping pong")) {
                        assertFalse(node(label).config[SemanticsProperties.Selected], label)
                    }
                }
                click("Reverse ping pong")
                assertEquals(AnimationDirection.PingPongReverse, controller.animationDirection)
                assertEquals(original, controller.document)
                click("Animation settings")
                text("Frame duration", "230")
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(original, controller.document)
                    val input = node("Frame duration", SemanticsActions.SetText)
                    assertEquals("230", input.config[SemanticsProperties.EditableText].text)
                    val popup = popupBounds("Frame duration")
                    val controlSize =
                        with(density) { StudioTheme.controlSize.roundToPx().toFloat() }
                    val settingsWidth =
                        with(density) { StudioTheme.animationSettingsWidth.roundToPx().toFloat() }
                    val settingsPadding =
                        with(density) { StudioTheme.animationSettingsPadding.roundToPx().toFloat() }
                    val inputWidth =
                        with(density) {
                            StudioTheme.animationDurationInputWidth.roundToPx().toFloat()
                        }
                    val divider = with(density) { DividerDefaults.Thickness.roundToPx().toFloat() }
                    val rangeParents =
                        generateSequence(node("New playback range").layoutInfo) { it.parentInfo }
                            .toList()
                    val ranges =
                        generateSequence(node("All frames").layoutInfo) { it.parentInfo }
                            .first { row -> rangeParents.any { it === row } }
                            .coordinates
                            .boundsInWindow()
                    assertTrue(
                        popup.left >= 0f &&
                            popup.top >= 0f &&
                            popup.right <= 400f &&
                            popup.bottom <= 800f,
                        "Settings escaped the 400 px window: $popup",
                    )
                    assertEquals(settingsWidth, popup.width, 0.5f, "Settings width: $popup")
                    assertEquals(
                        controlSize * 4 + ranges.height + divider * 3,
                        popup.height,
                        0.5f,
                        "Settings are not compact: $popup",
                    )
                    val controls =
                        listOf(
                                "Apply frame duration",
                                "Forward",
                                "Reverse",
                                "Ping pong",
                                "Reverse ping pong",
                                "All frames",
                                "New playback range",
                                "Move frame earlier",
                                "Move frame later",
                                "Make frame content independent",
                                "Clear layer in this frame",
                                "Duplicate linked frame",
                            )
                            .map { it to node(it).boundsInWindow } +
                            ("Frame duration" to input.boundsInWindow)
                    val picture =
                        screenshot("animation-settings-${appearance.name.lowercase()}.png")
                    val background = StudioTheme.panel.toArgb()
                    for ((label, bounds) in controls) {
                        assertTrue(
                            bounds.left >= popup.left &&
                                bounds.top >= popup.top &&
                                bounds.right <= popup.right &&
                                bounds.bottom <= popup.bottom,
                            "$label escaped Settings: $bounds",
                        )
                        val expectedWidth =
                            when (label) {
                                "Frame duration" -> inputWidth
                                "All frames" -> settingsWidth - settingsPadding * 2 - controlSize
                                else -> controlSize
                            }
                        assertEquals(
                            expectedWidth,
                            bounds.width,
                            0.5f,
                            "$label is clipped: $bounds",
                        )
                        if (label == "All frames")
                            assertTrue(bounds.height >= controlSize, "$label is clipped: $bounds")
                        else
                            assertEquals(
                                controlSize,
                                bounds.height,
                                0.5f,
                                "$label is clipped: $bounds",
                            )
                        val ink =
                            (bounds.top.toInt() + 4 until bounds.bottom.toInt() - 4).sumOf { y ->
                                (bounds.left.toInt() + 4 until bounds.right.toInt() - 4).count { x
                                    ->
                                    picture.getRGB(x, y) != background
                                }
                            }
                        assertTrue(ink > 6, "$label has no visible content in $appearance")
                    }
                    for (a in controls.indices) for (b in a + 1 until controls.size) {
                        val left = controls[a].second
                        val right = controls[b].second
                        assertTrue(
                            left.right <= right.left ||
                                right.right <= left.left ||
                                left.bottom <= right.top ||
                                right.bottom <= left.top,
                            "${controls[a].first} overlaps ${controls[b].first}",
                        )
                    }
                    for (label in listOf("Forward", "Reverse", "Ping pong", "Reverse ping pong")) {
                        assertEquals(
                            label == "Reverse ping pong",
                            node(label).config[SemanticsProperties.Selected],
                            label,
                        )
                    }
                }
                timeline("Apply frame duration")
                assertEquals(original.revision + 1, controller.document.revision)
                assertEquals(230, animation().frame(frameId)!!.durationMs)
                assertTrue(controller.document.canUndo)
                assertFalse(controller.document.canRedo)
                click("Close")
                click("Project")
                val saved = save()
                probe(saved) { engine ->
                    assertEquals(230, state(engine).animation!!.frame(frameId)!!.durationMs)
                    assertContentEquals(expectedPixels, framePixels(engine, frameId))
                }
                click("Undo")
                waitFor { animation().frame(frameId)!!.durationMs == 100 }
                assertEquals(original.revision + 2, controller.document.revision)
                assertEquals(original.contentId, controller.document.contentId)
                assertFalse(controller.document.canUndo)
                assertTrue(controller.document.canRedo)
                assertContentEquals(expectedPixels, withContext(Dispatchers.Main) { pixels() })
            }
        }
    }

    @Test
    fun narrowTimelineControlsAndAllThumbnailCornersStayVisibleInBothThemes() = runBlocking {
        val bytes = cornerAnimationProject()
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            for (width in listOf(400, 600)) {
                withSession(bytes, width = width, height = 800, appearance = appearance) {
                    panel("Animation")
                    waitFor { controller.animationThumbnails[active()] != null }
                    settle()
                    withContext(Dispatchers.Main) {
                        val picture =
                            screenshot(
                                "animation-timeline-${appearance.name.lowercase()}-$width.png"
                            )
                        val panel = StudioTheme.panel.toArgb()
                        val controlSize =
                            with(density) { StudioTheme.controlSize.roundToPx().toFloat() }
                        val required =
                            listOf(
                                "Play animation",
                                "Onion skin",
                                "Add blank frame",
                                "Duplicate frame",
                                "Delete frame",
                                "Animation settings",
                                "Close",
                            )
                        val labels =
                            required +
                                listOf(
                                    "Duplicate linked frame",
                                    "Export animation",
                                )
                        required.forEach { assertNotNull(visibleControl(it), it) }
                        val controls = labels.mapNotNull { label ->
                            visibleControl(label)?.let { label to it.boundsInWindow }
                        }
                        for ((label, bounds) in controls) {
                            assertTrue(
                                bounds.left >= 0f &&
                                    bounds.top >= 0f &&
                                    bounds.right <= width &&
                                    bounds.bottom <= 800f,
                                "$label escaped the $width px window: $bounds",
                            )
                            assertEquals(
                                controlSize,
                                bounds.width,
                                0.5f,
                                "$label is clipped: $bounds",
                            )
                            assertEquals(
                                controlSize,
                                bounds.height,
                                0.5f,
                                "$label is clipped: $bounds",
                            )
                            val ink =
                                (bounds.top.toInt() until bounds.bottom.toInt()).sumOf { y ->
                                    (bounds.left.toInt() until bounds.right.toInt()).count { x ->
                                        picture.getRGB(x, y) != panel
                                    }
                                }
                            assertTrue(ink > 6, "$label has no visible icon in $appearance")
                        }
                        for (a in controls.indices) for (b in a + 1 until controls.size) {
                            val left = controls[a].second
                            val right = controls[b].second
                            assertTrue(
                                left.right <= right.left ||
                                    right.right <= left.left ||
                                    left.bottom <= right.top ||
                                    right.bottom <= left.top,
                                "${controls[a].first} overlaps ${controls[b].first}",
                            )
                        }
                        val thumbnail = assertNotNull(controller.animationThumbnails[active()])
                        val nativePixels = imagePixels(thumbnail)
                        val opaque = nativePixels.indices.filter { nativePixels[it] ushr 24 != 0 }
                        assertTrue(opaque.isNotEmpty())
                        val nativeContent =
                            Rect(
                                opaque.minOf { it % thumbnail.width }.toFloat(),
                                opaque.minOf { it / thumbnail.width }.toFloat(),
                                (opaque.maxOf { it % thumbnail.width } + 1).toFloat(),
                                (opaque.maxOf { it / thumbnail.width } + 1).toFloat(),
                            )
                        assertEquals(4f / 3f, nativeContent.width / nativeContent.height, 0.02f)
                        val frameNode = node("Frame 1")
                        val frame = frameNode.boundsInWindow
                        val viewport = thumbnailBounds(active())
                        val track = node("Source · Frame 1").boundsInWindow
                        for (bounds in listOf(frame, viewport, track)) {
                            assertTrue(
                                bounds.left >= 0f &&
                                    bounds.top >= 0f &&
                                    bounds.right <= width &&
                                    bounds.bottom <= 800f,
                                "Frame escaped the $width px window: $bounds",
                            )
                        }
                        val inset = with(density) { StudioTheme.animationSelectionBorder.toPx() }
                        assertEquals(frame.width - inset * 2, viewport.width, 1f)
                        assertEquals(
                            frame.width / StudioTheme.animationPreviewAspectRatio - inset * 2,
                            viewport.height,
                            1f,
                        )
                        assertEquals(
                            with(density) { StudioTheme.animationExposureWidth.toPx() },
                            track.width,
                            0.5f,
                        )
                        assertTrue(track.top >= frame.bottom)
                        val timeline = taggedNode("animation-timeline").boundsInWindow
                        val tracks = taggedNode("animation-tracks").boundsInWindow
                        assertEquals(
                            timeline.bottom,
                            tracks.bottom,
                            0.5f,
                            "Exposure matrix is not pinned to the timeline bottom",
                        )
                        val trackName = taggedNode("animation-track-name-1").boundsInWindow
                        val lineInset = with(density) { StudioTheme.hairline.roundToPx() }
                        val contentInset = with(density) { StudioTheme.animationGap.roundToPx() }
                        for (y in
                            trackName.top.toInt() + contentInset until
                                trackName.bottom.toInt() - contentInset) {
                            assertEquals(
                                StudioTheme.selection.toArgb(),
                                picture.getRGB(trackName.left.toInt() + lineInset, y),
                                "Active track has an extra vertical marker",
                            )
                        }
                        assertTrue(
                            frameNode.config.getOrNull(SemanticsProperties.Text)?.any {
                                it.text == "100 ms"
                            } == true
                        )
                        val sourceColors =
                            setOf(
                                0xFF145064.toInt(),
                                0xFFDC3038.toInt(),
                                0xFF28B058.toInt(),
                                0xFF3060E0.toInt(),
                                0xFFE8C028.toInt(),
                            )
                        val sourcePixels =
                            (viewport.top.toInt() until viewport.bottom.toInt()).flatMap { y ->
                                (viewport.left.toInt() until viewport.right.toInt()).mapNotNull { x
                                    ->
                                    val color = picture.getRGB(x, y)
                                    if (color in sourceColors) Triple(x, y, color) else null
                                }
                            }
                        assertTrue(sourcePixels.isNotEmpty())
                        val content =
                            Rect(
                                sourcePixels.minOf { it.first }.toFloat(),
                                sourcePixels.minOf { it.second }.toFloat(),
                                (sourcePixels.maxOf { it.first } + 1).toFloat(),
                                (sourcePixels.maxOf { it.second } + 1).toFloat(),
                            )
                        assertEquals(4f / 3f, content.width / content.height, 0.08f)
                        val scale =
                            minOf(
                                viewport.width / controller.document.width,
                                viewport.height / controller.document.height,
                            )
                        val target =
                            Size(
                                controller.document.width * scale,
                                controller.document.height * scale,
                            )
                        val expected =
                            Rect(
                                viewport.center - Offset(target.width / 2, target.height / 2),
                                target,
                            )
                        assertEquals(expected.left, content.left, 1.5f)
                        assertEquals(expected.top, content.top, 1.5f)
                        assertEquals(expected.right, content.right, 1.5f)
                        assertEquals(expected.bottom, content.bottom, 1.5f)
                        for ((color, left, bottom) in
                            listOf(
                                Triple(0xFFDC3038.toInt(), true, false),
                                Triple(0xFF28B058.toInt(), false, false),
                                Triple(0xFF3060E0.toInt(), true, true),
                                Triple(0xFFE8C028.toInt(), false, true),
                            )) {
                            val sourceX =
                                if (left) nativeContent.left.toInt() + 4
                                else nativeContent.right.toInt() - 5
                            val sourceY =
                                if (bottom) nativeContent.bottom.toInt() - 5
                                else nativeContent.top.toInt() + 4
                            assertEquals(color, nativePixels[sourceY * thumbnail.width + sourceX])
                            val visible = sourcePixels.filter { it.third == color }
                            assertTrue(
                                visible.size >= 24,
                                "Thumbnail corner $color was lost in $appearance/$width",
                            )
                            if (left) assertTrue(visible.minOf { it.first } <= content.left + 2f)
                            else assertTrue(visible.maxOf { it.first } >= content.right - 3f)
                            if (bottom)
                                assertTrue(visible.maxOf { it.second } >= content.bottom - 3f)
                            else assertTrue(visible.minOf { it.second } <= content.top + 2f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun shortTimelineScrollsToCompleteFramesAndExposureCellsAtBothInterfaceScales() = runBlocking {
        val bytes =
            probe(cornerAnimationProject()) { engine ->
                val first = state(engine).animation!!.activeFrameId
                command(
                    engine,
                    """{"type":"duplicate_frame","frame_id":$first,"index":1,"linked":true}""",
                )
                command(engine, """{"type":"select_frame","frame_id":$first}""")
                engine.call(EngineOperation.SAVE)
            }
        val original = probe(bytes, ::state)
        val originalAnimation = assertNotNull(original.animation)
        val frames = originalAnimation.frames
        val expected =
            probe(bytes) { engine -> frames.associate { it.id to framePixels(engine, it.id) } }
        for (scale in listOf(1f, 2f)) {
            withSession(
                bytes,
                width = if (scale == 1f) 400 else 600,
                height = if (scale == 1f) 400 else 1000,
            ) {
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(
                        controller.preferences.copy(
                            workspaceAppearance =
                                WorkspaceAppearance(scale = scale, reducedMotion = true)
                        )
                    )
                }
                settle()
                panel("Animation")
                withContext(Dispatchers.Main) {
                    val body = taggedNode("animation-timeline-body")
                    assertTrue(body.config.contains(SemanticsActions.ScrollBy))
                    assertTrue(body.config.contains(SemanticsProperties.VerticalScrollAxisRange))
                    assertTrue(
                        body.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f,
                        "Short timeline does not overflow at $scale: ${body.boundsInWindow}",
                    )
                }
                val before = controller.document
                for ((index, frame) in frames.withIndex().reversed()) {
                    select(frame.id)
                    waitFor { controller.animationThumbnails[frame.id] != null }
                    withContext(Dispatchers.Main) {
                        val card = node("Frame ${index + 1}")
                        assertEquals(density.density * scale, card.layoutInfo.density.density)
                        val viewport = thumbnailBounds(frame.id)
                        assertTrue(viewport.width > 0f && viewport.height > 0f)
                        assertTrue(
                            viewport.left >= 0f &&
                                viewport.top >= 0f &&
                                viewport.right <= width &&
                                viewport.bottom <= height
                        )
                        assertEquals(
                            minOf(
                                card.boundsInWindow.width / StudioTheme.animationPreviewAspectRatio,
                                taggedNode("animation-timeline-body").boundsInWindow.height -
                                    with(card.layoutInfo.density) {
                                        StudioTheme.controlSize.toPx()
                                    },
                            ) -
                                with(card.layoutInfo.density) {
                                    (StudioTheme.animationSelectionBorder * 2).toPx()
                                },
                            viewport.height,
                            1f,
                        )
                    }
                    click("Source · Frame ${index + 1}")
                    waitFor { active() == frame.id && controller.document.active == 1 }
                    assertEquals(
                        originalAnimation.exposure(frame.id, 1)?.celId,
                        animation().activeCelId,
                    )
                    assertEquals(before.contentId, controller.document.contentId)
                    assertEquals(before.canUndo, controller.document.canUndo)
                    assertEquals(before.canRedo, controller.document.canRedo)
                    assertFalse(controller.hasUnsavedChanges)
                    assertContentEquals(
                        expected.getValue(frame.id),
                        withContext(Dispatchers.Main) { pixels() },
                    )
                    withContext(Dispatchers.Main) {
                        val body = taggedNode("animation-timeline-body")
                        assertTrue(
                            body.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
                        )
                    }
                }
                select(frames.first().id)
                withContext(Dispatchers.Main) {
                    screenshot("animation-short-${(scale * 100).toInt()}.png")
                }
            }
        }
    }

    @Test
    fun shownTailFrameGetsARealThumbnailWhenMoreThanTheRequestLimitAreVisible() = runBlocking {
        val bytes =
            probe(cornerAnimationProject()) { engine ->
                val first = state(engine).animation!!.activeFrameId
                for (index in 1 until 40) {
                    command(
                        engine,
                        """{"type":"duplicate_frame","frame_id":$first,"index":$index,"linked":true}""",
                    )
                }
                val tail = state(engine).animation!!.frames.last().id
                command(engine, """{"type":"select_frame","frame_id":$tail}""")
                engine.call(EngineOperation.SAVE)
            }
        val original = probe(bytes, ::state)
        val tail = original.animation!!.frames.last().id
        val expected = framePixels(bytes, tail)
        withSession(bytes, width = 1100, height = 2200) {
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences.copy(
                        workspaceAppearance =
                            WorkspaceAppearance(scale = 0.75f, reducedMotion = true)
                    )
                )
            }
            settle()
            panel("Animation")
            val before = controller.document
            select(tail)
            withContext(Dispatchers.Main) {
                val visible =
                    animation().frames.indices.count { index ->
                        visibleControl("Frame ${index + 1}")?.let { card ->
                            abs(card.boundsInWindow.height - card.size.height) < 0.5f
                        } == true
                    }
                assertEquals(32, controller.document.maxFrameThumbnails)
                assertTrue(
                    visible > controller.document.maxFrameThumbnails,
                    "Only $visible frames are visible",
                )
            }
            waitFor { controller.animationThumbnails[tail] != null }
            withContext(Dispatchers.Main) {
                assertTrue(
                    controller.animationThumbnails.size <= controller.document.maxFrameThumbnails
                )
                assertContentEquals(expected, pixels())
                assertEquals(before.contentId, controller.document.contentId)
                assertEquals(tail, active())
                assertFalse(controller.document.canUndo)
                assertFalse(controller.document.canRedo)
                assertFalse(controller.hasUnsavedChanges)
                val viewport = thumbnailBounds(tail)
                val picture = screenshot("animation-many-visible-frames.png")
                val rendered =
                    (viewport.top.toInt() until viewport.bottom.toInt())
                        .flatMap { y ->
                            (viewport.left.toInt() until viewport.right.toInt()).map { x ->
                                picture.getRGB(x, y)
                            }
                        }
                        .toSet()
                for (corner in
                    listOf(
                        0xFFDC3038.toInt(),
                        0xFF28B058.toInt(),
                        0xFF3060E0.toInt(),
                        0xFFE8C028.toInt(),
                    )) {
                    assertTrue(corner in rendered, "Shown tail thumbnail lost corner $corner")
                }
            }
            select(animation().frames.first().id)
            val dragStart = controller.document
            val rulerY =
                withContext(Dispatchers.Main) {
                    rulerNode(active()).boundsInWindow.center.y
                }
            for (attempt in 0 until 12) {
                val drag =
                    withContext(Dispatchers.Main) {
                        val strip = tracksNode()
                        val range = strip.config[SemanticsProperties.HorizontalScrollAxisRange]
                        if (range.value() == range.maxValue()) null
                        else {
                            val bounds = strip.boundsInWindow
                            val inset =
                                with(density) {
                                    StudioTheme.controlSize.toPx() *
                                        controller.preferences.workspaceAppearance.scale / 4
                                }
                            Triple(
                                Offset(bounds.right - inset, rulerY),
                                Offset(bounds.left + inset, rulerY),
                                range.value(),
                            )
                        }
                    } ?: break
                pointer(PointerEventType.Press, drag.first)
                for (step in 1..4) pointer(
                    PointerEventType.Move,
                    drag.first + (drag.second - drag.first) * (step / 4f),
                )
                pointer(PointerEventType.Release, drag.second)
                waitFor {
                    tracksNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() >
                        drag.third
                }
            }
            val tailCell =
                withContext(Dispatchers.Main) {
                    val strip = tracksNode()
                    val range = strip.config[SemanticsProperties.HorizontalScrollAxisRange]
                    assertEquals(range.maxValue(), range.value())
                    val cell = node("Source · Frame 40")
                    assertEquals(cell.size.width.toFloat(), cell.boundsInWindow.width, 0.5f)
                    assertEquals(dragStart, controller.document)
                    cell.boundsInWindow.center
                }
            pointer(PointerEventType.Press, tailCell)
            pointer(PointerEventType.Release, tailCell)
            waitFor { active() == tail }
            assertEquals(dragStart.contentId, controller.document.contentId)
            assertEquals(dragStart.canUndo, controller.document.canUndo)
            assertEquals(dragStart.canRedo, controller.document.canRedo)
            assertFalse(controller.hasUnsavedChanges)
            assertContentEquals(expected, withContext(Dispatchers.Main) { pixels() })
        }
    }

    @Test
    fun timelineTrackPointerClicksSelectTheFrameLayerAndCelWithoutChangingPixelsOrHistory() =
        runBlocking {
            val bytes = layeredAnimationProject()
            val original = probe(bytes, ::state)
            val originalAnimation = assertNotNull(original.animation)
            val frames = originalAnimation.frames
            val ink = original.layers.single { it.name == "Ink track" }.id
            val expected =
                probe(bytes) { engine -> frames.associate { it.id to framePixels(engine, it.id) } }
            withSession(bytes) {
                val before = controller.document
                panel("Animation")
                for ((label, frameId, layerId) in
                    listOf(
                        Triple("Ink track · Frame 2", frames[1].id, ink),
                        Triple("Source · Frame 2", frames[1].id, 1),
                        Triple("Ink track · Frame 1", frames[0].id, ink),
                        Triple("Frame 2", frames[1].id, ink),
                        Triple("Source · Frame 1", frames[0].id, 1),
                    )) {
                    click(label)
                    waitFor { active() == frameId && controller.document.active == layerId }
                    assertEquals(
                        originalAnimation.exposure(frameId, layerId)?.celId,
                        animation().activeCelId,
                        label,
                    )
                    assertEquals(before.contentId, controller.document.contentId)
                    assertEquals(before.canUndo, controller.document.canUndo)
                    assertEquals(before.canRedo, controller.document.canRedo)
                    assertFalse(controller.hasUnsavedChanges)
                    assertContentEquals(
                        expected.getValue(frameId),
                        withContext(Dispatchers.Main) { pixels() },
                        label,
                    )
                    withContext(Dispatchers.Main) {
                        val number = frames.indexOfFirst { it.id == frameId } + 1
                        assertEquals(
                            true,
                            node("Frame $number").config.getOrNull(SemanticsProperties.Selected),
                        )
                        val picture = screenshot("animation-exposure-$frameId-$layerId.png")
                        val markerInset =
                            with(density) {
                                (StudioTheme.hairline + StudioTheme.animationPlayIndicatorHeight)
                                    .roundToPx()
                            }
                        for ((frameIndex, frame) in frames.withIndex()) {
                            assertEquals(
                                frame.id == frameId,
                                rulerNode(frame.id).config.getOrNull(SemanticsProperties.Selected),
                            )
                            for (layer in original.layers) {
                                val cell = node("${layer.name} · Frame ${frameIndex + 1}")
                                assertEquals(
                                    frame.id == frameId && layer.id == layerId,
                                    cell.config.getOrNull(SemanticsProperties.Selected),
                                    "${layer.name} · Frame ${frameIndex + 1}",
                                )
                                val bounds = cell.boundsInWindow
                                val marker =
                                    picture.getRGB(
                                        bounds.center.x.toInt(),
                                        bounds.bottom.toInt() - markerInset,
                                    )
                                if (frame.id == frameId)
                                    assertEquals(StudioTheme.accent.toArgb(), marker, label)
                                else assertNotEquals(StudioTheme.accent.toArgb(), marker, label)
                            }
                        }
                    }
                }
                probe(save()) { engine ->
                    for ((id, pixels) in expected) assertContentEquals(
                        pixels,
                        framePixels(engine, id),
                    )
                }
            }
        }

    @Test
    fun panelHideButtonAndAnimationShortcutDuringAStrokePreserveCanvasBoundsAndEveryNativePixel() =
        runBlocking {
            withSession(project()) {
                enable()
                if (controller.tool != Tool.Brush) click("Brush")
                panel("Animation")
                withContext(Dispatchers.Main) { controller.brush = hardBrush }
                val before = controller.document
                val frameId = active()
                val samples =
                    listOf(
                        Sample(Offset(50f, 30f), 0.25f),
                        Sample(Offset(68f, 42f), 0.75f),
                        Sample(Offset(92f, 60f), 1f),
                    )
                val actual =
                    withContext(Dispatchers.Main) {
                        samples.map { it.copy(point = sampled(it.point)) }
                    }
                val expected = baseline(save(), actual)
                val bounds = withContext(Dispatchers.Main) { canvasBounds() }
                val viewport = controller.viewport
                stylus(PointerEventType.Press, samples.first(), true)
                waitFor { controller.drawingInput }
                withContext(Dispatchers.Main) {
                    val hide = node("Hide panel")
                    assertTrue(hide.config.contains(SemanticsProperties.Disabled))
                    assertNotNull(hide.config[SemanticsActions.OnClick].action).invoke()
                    render().close()
                    assertTrue(controller.drawingInput)
                    assertNotNull(visibleControl("Hide panel"))
                    assertEquals(bounds, canvasBounds())
                }
                key(androidx.compose.ui.input.key.Key.A, command = true, shift = true)
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controller.drawingInput)
                    assertNotNull(visibleControl("Hide panel"))
                    assertEquals(bounds, canvasBounds())
                    assertEquals(viewport, controller.viewport)
                    assertEquals(frameId, active())
                    assertEquals(before.width, controller.document.width)
                    assertEquals(before.height, controller.document.height)
                }
                for (sample in samples.drop(1)) stylus(PointerEventType.Move, sample, true)
                stylus(PointerEventType.Release, samples.last(), false)
                pointer(PointerEventType.Move, Offset.Zero)
                waitFor {
                    !controller.drawingInput && controller.document.revision == before.revision + 1
                }
                assertContentEquals(expected, withContext(Dispatchers.Main) { pixels() })
                assertContentEquals(expected, framePixels(save(), frameId))
                val completed = controller.document
                click("Hide panel")
                withContext(Dispatchers.Main) {
                    assertNotNull(visibleControl("Show panel"))
                    assertTrue(canvasBounds().width > bounds.width)
                    assertEquals(completed, controller.document)
                }
                key(androidx.compose.ui.input.key.Key.A, command = true, shift = true)
                settle()
                withContext(Dispatchers.Main) {
                    assertNotNull(visibleControl("Hide panel"))
                    assertEquals(bounds, canvasBounds())
                    assertEquals(completed, controller.document)
                }
                assertContentEquals(expected, framePixels(save(), frameId))
            }
        }

    @Test
    fun blankFramesAndIndependentDuplicatesContainRealEditableIsolatedPixels() = runBlocking {
        withSession(project()) {
            enable()
            val first = active()
            val firstCel = cel()
            val firstPixels = withContext(Dispatchers.Main) { pixels() }
            val second = timeline("Add blank frame")
            assertNull(cel())
            assertTrue(withContext(Dispatchers.Main) { pixels().all { it == 0 } })
            stroke(
                listOf(
                    Sample(Offset(56f, 24f), 0.25f),
                    Sample(Offset(64f, 30f), 0.75f),
                    Sample(Offset(80f, 38f), 1f),
                )
            )
            val secondCel = assertNotNull(cel())
            assertNotEquals(firstCel, secondCel)
            val secondPixels = withContext(Dispatchers.Main) { pixels() }
            val third = timeline("Duplicate frame")
            assertNotEquals(secondCel, cel())
            assertContentEquals(secondPixels, withContext(Dispatchers.Main) { pixels() })
            stroke(
                listOf(Sample(Offset(96f, 54f), 1f), Sample(Offset(108f, 62f), 0.5f)),
                hardBrush.copy(color = 0xFF477DAD),
            )
            val thirdPixels = withContext(Dispatchers.Main) { pixels() }
            assertFalse(secondPixels.contentEquals(thirdPixels))
            val saved = save()
            assertContentEquals(firstPixels, framePixels(saved, first))
            assertContentEquals(secondPixels, framePixels(saved, second))
            assertContentEquals(thirdPixels, framePixels(saved, third))
            select(first)
            assertContentEquals(firstPixels, withContext(Dispatchers.Main) { pixels() })
            select(second)
            assertContentEquals(secondPixels, withContext(Dispatchers.Main) { pixels() })
            probe(saved) { engine ->
                val original = state(engine)
                val stale =
                    request(
                        original,
                        """{"type":"fill","x":10,"y":10,"color":[255,0,0,255],"tolerance":0}""",
                    )
                command(engine, """{"type":"select_frame","frame_id":$first}""")
                val before = state(engine)
                val bytes = engine.call(EngineOperation.SAVE)
                assertFailsWith<IllegalStateException> {
                    engine.call(EngineOperation.COMMAND, stale.toString().encodeToByteArray())
                }
                val wrongFrame = JsonObject(stale + ("revision" to JsonPrimitive(before.revision)))
                assertFailsWith<IllegalStateException> {
                    engine.call(EngineOperation.COMMAND, wrongFrame.toString().encodeToByteArray())
                }
                assertEquals(before, state(engine))
                assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
                assertContentEquals(firstPixels, framePixels(engine, first))
                assertContentEquals(thirdPixels, framePixels(engine, third))
            }
        }
    }

    @Test
    fun linkedMaskEditingAndUnlinkKeepRasterAndOtherFrameMasksIndependent() = runBlocking {
        withSession(project(mask = true)) {
            enable()
            val first = active()
            val firstCel = cel()
            val firstMask = controller.document.layers.single().masks.single().id
            val original = save()
            val raster = rawRaster(original, first)
            val mask = rawMask(original, first)
            val second = timeline("Duplicate linked frame")
            assertEquals(firstCel, cel())
            assertEquals(firstMask, controller.document.layers.single().masks.single().id)
            panel("Layers")
            click("Source · Edit mask")
            waitFor { controller.document.maskEditing }
            stroke(
                listOf(Sample(Offset(12.25f, 20.25f), 1f), Sample(Offset(20.25f, 20.25f), 1f)),
                hardBrush.copy(
                    preset = hardBrush.preset.copy(raster = BrushRaster.Pixel),
                    color = 0xFF000000,
                    size = 8f,
                ),
                eraser = true,
            )
            val shared = save()
            assertContentEquals(rawMask(shared, first), rawMask(shared, second))
            assertFalse(mask.contentEquals(rawMask(shared, first)))
            assertContentEquals(raster, rawRaster(shared, first))
            assertContentEquals(raster, rawRaster(shared, second))
            assertContentEquals(framePixels(shared, first), framePixels(shared, second))
            settings("Make frame content independent")
            assertNotEquals(firstCel, cel())
            assertNotEquals(firstMask, controller.document.layers.single().masks.single().id)
            panel("Layers")
            click("Source · Edit mask")
            waitFor { controller.document.maskEditing }
            val beforeEdit = save()
            stroke(
                listOf(Sample(Offset(12f, 40f), 1f), Sample(Offset(20f, 40f), 1f)),
                hardBrush.copy(
                    preset = hardBrush.preset.copy(raster = BrushRaster.Pixel),
                    color = 0xFF000000,
                    size = 8f,
                ),
                eraser = true,
            )
            val isolated = save()
            assertContentEquals(rawMask(beforeEdit, first), rawMask(isolated, first))
            assertFalse(rawMask(beforeEdit, second).contentEquals(rawMask(isolated, second)))
            assertContentEquals(raster, rawRaster(isolated, second))
            val isolatedPixels = framePixels(isolated, second)
            click("Undo")
            waitFor { controller.document.canRedo }
            assertContentEquals(
                framePixels(beforeEdit, second),
                withContext(Dispatchers.Main) { pixels() },
            )
            click("Redo")
            waitFor { !controller.document.canRedo }
            assertContentEquals(isolatedPixels, withContext(Dispatchers.Main) { pixels() })
        }
    }

    @Test
    fun durationReorderClearDeleteAndThumbnailsRoundTripStableFrameIdentities() = runBlocking {
        withSession(project()) {
            enable()
            val first = active()
            val second = timeline("Duplicate frame")
            val third = timeline("Add blank frame")
            select(second)
            waitFor { animation().frames.all { it.id in controller.animationThumbnails } }
            val frame = controller.frame
            val thumbnail = controller.animationThumbnails.getValue(second)
            val before = controller.document
            click("Animation settings")
            text("Frame duration", "275")
            timeline("Apply frame duration")
            assertEquals(275, animation().frame(second)!!.durationMs)
            assertEquals(before.revision + 1, controller.document.revision)
            assertSame(frame, controller.frame)
            assertSame(thumbnail, controller.animationThumbnails.getValue(second))
            settings("Move frame earlier")
            assertEquals(listOf(second, first, third), animation().frames.map { it.id })
            assertEquals(second, active())
            settings("Clear layer in this frame")
            assertNull(cel())
            assertTrue(withContext(Dispatchers.Main) { pixels().all { it == 0 } })
            timeline("Delete frame")
            assertEquals(listOf(first, third), animation().frames.map { it.id })
            select(first)
            val info = animation()
            val expected = withContext(Dispatchers.Main) { pixels() }
            val saved = save()
            probe(saved) { engine ->
                val beforeRead = state(engine)
                val bytes = engine.call(EngineOperation.SAVE)
                val ids = info.frames.map { it.id }
                val previews =
                    engine.call(
                        EngineOperation.ANIMATION_PREVIEWS,
                        buildJsonObject {
                            put("revision", beforeRead.revision)
                            put("size", 96)
                            putJsonArray("frame_ids") { ids.forEach { add(it) } }
                        }
                            .toString()
                            .encodeToByteArray(),
                    )
                assertEquals(96, intAt(previews, 8))
                assertEquals(ids.size, intAt(previews, 12))
                assertEquals(16 + ids.size * (4 + 96 * 96 * 4), previews.size)
                ids.forEachIndexed { index, id ->
                    assertEquals(id, intAt(previews, 16 + index * (4 + 96 * 96 * 4)))
                }
                assertEquals(beforeRead, state(engine))
                assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
                assertContentEquals(expected, framePixels(engine, first))
                assertTrue(framePixels(engine, third).all { it == 0 })
            }
            open(saved)
            assertEquals(info, animation())
            assertFalse(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            assertFalse(controller.hasUnsavedChanges)
            assertContentEquals(expected, withContext(Dispatchers.Main) { pixels() })
            assertContentEquals(saved, save())
        }
    }

    @Test
    fun playbackAndOnionAreReadonlyAndPngExportsOnlyCurrentFrame() = runBlocking {
        withSession(project()) {
            enable()
            val first = active()
            val second = timeline("Add blank frame")
            stroke(
                listOf(Sample(Offset(84f, 30f), 0.5f), Sample(Offset(104f, 44f), 1f)),
                hardBrush.copy(preset = hardBrush.preset.copy(raster = BrushRaster.Pixel)),
            )
            val saved = save()
            val firstPixels = framePixels(saved, first)
            val secondPixels = framePixels(saved, second)
            panel("Animation")
            click("Onion skin")
            waitFor { controller.onionPrevious != null }
            assertContentEquals(
                firstPixels,
                withContext(Dispatchers.Main) { pixels(controller.onionPrevious!!) },
            )
            assertContentEquals(secondPixels, withContext(Dispatchers.Main) { pixels() })
            val before = controller.document
            val frame = controller.frame
            withContext(Dispatchers.Main) {
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                        val directory = Path.of("build", "reports", "screenshots")
                        Files.createDirectories(directory)
                        Files.write(directory.resolve("animation-ui-study.png"), png.bytes)
                    }
                }
            }
            val png = ImageIO.read(ByteArrayInputStream(transparentExport()))
            assertEquals(128, png.width)
            assertEquals(96, png.height)
            assertContentEquals(
                secondPixels,
                png.getRGB(0, 0, png.width, png.height, null, 0, png.width),
            )
            assertEquals(before, controller.document)
            assertSame(frame, controller.frame)
            click("Play animation")
            val seen = mutableSetOf<Int>()
            withTimeout(5_000) {
                while (seen.size < 2) {
                    settle()
                    withContext(Dispatchers.Main) {
                        controller.animationDisplayFrameId?.let { id ->
                            val display = assertNotNull(controller.animationDisplayFrame)
                            assertContentEquals(
                                if (id == first) firstPixels else secondPixels,
                                pixels(display),
                            )
                            seen += id
                        }
                        assertEquals(before, controller.document)
                        assertSame(frame, controller.frame)
                        assertTrue(
                            node("Add blank frame").config.contains(SemanticsProperties.Disabled)
                        )
                        assertFalse(node("Frame 1").config.contains(SemanticsProperties.Disabled))
                    }
                }
            }
            assertEquals(setOf(first, second), seen)
            stylus(PointerEventType.Press, Sample(Offset(10f, 10f), 1f), true)
            stylus(PointerEventType.Move, Sample(Offset(24f, 10f), 1f), true)
            stylus(PointerEventType.Release, Sample(Offset(24f, 10f), 1f), false)
            click("Stop animation")
            waitFor { !controller.animationPlaying && controller.animationDisplayFrame == null }
            assertEquals(before, controller.document)
            assertSame(frame, controller.frame)
            assertContentEquals(saved, save())
            if (controller.tool != Tool.Brush) click("Brush")
            panel("Animation")
            val revision = controller.document.revision
            stylus(PointerEventType.Press, Sample(Offset(70f, 60f), 1f), true)
            waitFor { controller.drawingInput }
            withContext(Dispatchers.Main) {
                assertTrue(node("Play animation").config.contains(SemanticsProperties.Disabled))
                assertTrue(node("Add blank frame").config.contains(SemanticsProperties.Disabled))
                controller.startAnimation()
                assertFalse(controller.animationPlaying)
            }
            stylus(PointerEventType.Move, Sample(Offset(82f, 60f), 1f), true)
            stylus(PointerEventType.Release, Sample(Offset(82f, 60f), 1f), false)
            waitFor { !controller.drawingInput && controller.document.revision == revision + 1 }
            assertFalse(controller.animationPlaying)
            assertFalse(secondPixels.contentEquals(withContext(Dispatchers.Main) { pixels() }))
            assertContentEquals(firstPixels, framePixels(save(), first))
        }
    }

    @Test
    fun rangeFieldsPersistAndButtonsAndShortcutsUseReadonlyPlaybackConfiguration() = runBlocking {
        withSession(project()) {
            enable()
            val first = active()
            click("Animation settings")
            text("Frame duration", "60000")
            timeline("Apply frame duration")
            val second = timeline("Duplicate frame")
            val third = timeline("Add blank frame")
            val fourth = timeline("Add blank frame")
            select(first)
            click("Animation settings")
            click("New playback range")
            text("Range name", "Middle frames")
            text("First frame", "2")
            text("Last frame", "3")
            text("Repeat count", "4")
            click("Forward")
            click("Reverse ping pong")
            timeline("Save")
            val tag = animation().tags.single()
            assertEquals("Middle frames", tag.name)
            assertEquals(second, tag.fromFrame)
            assertEquals(third, tag.toFrame)
            assertEquals(AnimationDirection.PingPongReverse, tag.direction)
            assertEquals(4, tag.repeat)
            val saved = save()
            probe(saved) { engine -> assertEquals(tag, state(engine).animation!!.tags.single()) }
            open(saved)
            assertEquals(tag, animation().tags.single())
            click("Animation settings")
            click("All frames")
            click(tag.name)
            assertEquals(tag.id, controller.animationTagId)
            click("Animation settings")
            click("Edit playback range")
            text("Repeat count", "0")
            timeline("Save")
            val loopingTag = tag.copy(repeat = 0)
            assertEquals(loopingTag, animation().tags.single())
            val looping = save()
            val storedPixels =
                probe(looping) { engine ->
                    val nativeAnimation = state(engine).animation!!
                    assertEquals(loopingTag, nativeAnimation.tags.single())
                    nativeAnimation.frames.associate { it.id to framePixels(engine, it.id) }
                }
            val before = controller.document
            val frame = controller.frame
            val originalPixels = withContext(Dispatchers.Main) { pixels() }
            for ((label, displayedId) in listOf("Reverse" to fourth, tag.name to third)) {
                click("Animation settings")
                if (label == tag.name) click("All frames")
                click(label)
                assertEquals(AnimationDirection.Reverse, controller.animationDirection)
                assertEquals(if (label == tag.name) tag.id else null, controller.animationTagId)
                for (keyboard in listOf(false, true)) {
                    if (keyboard) key(androidx.compose.ui.input.key.Key.P, alt = true)
                    else click("Play animation")
                    waitFor {
                        controller.animationPlaying &&
                            controller.animationDisplayFrameId == displayedId &&
                            controller.animationDisplayFrame != null
                    }
                    withContext(Dispatchers.Main) {
                        assertEquals(before, controller.document)
                        assertSame(frame, controller.frame)
                        assertContentEquals(originalPixels, pixels())
                        assertContentEquals(
                            storedPixels.getValue(displayedId),
                            pixels(controller.animationDisplayFrame!!),
                        )
                    }
                    key(androidx.compose.ui.input.key.Key.Escape)
                    waitFor {
                        !controller.animationPlaying &&
                            controller.animationDisplayFrameId == null &&
                            controller.animationDisplayFrame == null
                    }
                    assertEquals(before, controller.document)
                    assertSame(frame, controller.frame)
                }
            }
            assertContentEquals(looping, save())
            open(looping)
            assertEquals(loopingTag, animation().tags.single())
            probe(save()) { engine ->
                for ((frameId, expected) in storedPixels) assertContentEquals(
                    expected,
                    framePixels(engine, frameId),
                )
            }
        }
    }
}
