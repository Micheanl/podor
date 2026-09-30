package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
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
    ) {
        private var time = 0L

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

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        private fun findNode(
            label: String,
            action: SemanticsPropertyKey<*> = SemanticsActions.OnClick,
        ): SemanticsNode? =
            nodes()
                .filter {
                    it.config.contains(action) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }

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
                .filter {
                    it.config.contains(SemanticsProperties.Focused) &&
                        it.boundsInWindow.width > 1000f
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
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

        suspend fun click(label: String) {
            waitFor {
                findNode(label)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
            }
            val point =
                withContext(Dispatchers.Main) {
                    val button =
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
                    assertFalse(button.config.contains(SemanticsProperties.Disabled), label)
                    button.boundsInWindow.center
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        private fun view(): Size {
            val canvas =
                nodes()
                    .filter {
                        it.config.contains(SemanticsProperties.Focused) &&
                            it.boundsInWindow.width > 1000f
                    }
                    .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
            return Size(1282f, canvas.boundsInWindow.height)
        }

        fun position(point: Offset) =
            controller.viewport.toView(point, view(), controller.document) + Offset(0f, 64f)

        fun sampled(point: Offset): Offset =
            controller.viewport.toDocument(
                position(point) - Offset(0f, 64f),
                view(),
                controller.document,
            )

        suspend fun fit() =
            withContext(Dispatchers.Main) {
                controller.viewport =
                    Viewport(zoom = 4f / Viewport().scale(view(), controller.document))
                assertEquals(4f, controller.viewport.scale(view(), controller.document))
            }

        fun animation() = assertNotNull(controller.document.animation)

        fun active() = animation().activeFrameId

        fun cel(frameId: Int = active()) = animation().exposure(frameId, 1)?.celId

        suspend fun enable() {
            val before = controller.document
            click("Project")
            click("Animation timeline")
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
            val before = controller.document.revision
            click(label)
            waitFor { controller.document.revision == before + 1 }
            return active()
        }

        suspend fun settings(label: String) {
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
                    .filter {
                        it.config.contains(SemanticsProperties.Focused) &&
                            it.boundsInWindow.width > 1000f
                    }
                    .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
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
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(width, height) {
                    StudioApp(controller)
                    UnsavedChangesDialog(controller)
                }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 128 }
            if (width >= 1000) {
                session.click("Show panel")
                session.click("Layers")
                session.fit()
            }
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
                    assertTrue(
                        popup.left >= 0f &&
                            popup.top >= 0f &&
                            popup.right <= 400f &&
                            popup.bottom <= 800f,
                        "Settings escaped the 400 px window: $popup",
                    )
                    assertTrue(popup.width in 240f..280f, "Settings are too wide: $popup")
                    assertTrue(popup.height in 200f..270f, "Settings are not compact: $popup")
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
                                "Duplicate frame",
                                "Duplicate linked frame",
                                "Delete frame",
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
                        assertTrue(
                            bounds.width >= 40f && bounds.height >= 40f,
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
                    waitFor { controller.animationThumbnails[active()] != null }
                    settle()
                    withContext(Dispatchers.Main) {
                        val picture =
                            screenshot(
                                "animation-timeline-${appearance.name.lowercase()}-$width.png"
                            )
                        val panel = StudioTheme.panel.toArgb()
                        val required =
                            listOf(
                                "Play animation",
                                "Onion skin",
                                "Add blank frame",
                                "Animation settings",
                                "Hide timeline",
                            )
                        val labels =
                            required +
                                listOf(
                                    "Duplicate frame",
                                    "Duplicate linked frame",
                                    "Delete frame",
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
                            assertTrue(
                                bounds.width >= 40f && bounds.height >= 40f,
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
                        val frame = node("Frame 1").boundsInWindow
                        val track = node("Source · Frame 1").boundsInWindow
                        val sourceColors =
                            setOf(
                                0xFF145064.toInt(),
                                0xFFDC3038.toInt(),
                                0xFF28B058.toInt(),
                                0xFF3060E0.toInt(),
                                0xFFE8C028.toInt(),
                            )
                        val sourcePixels =
                            (frame.top.toInt() until track.top.toInt()).flatMap { y ->
                                (frame.left.toInt() until frame.right.toInt()).mapNotNull { x ->
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
                        assertEquals(
                            true,
                            node("Frame ${frames.indexOfFirst { it.id == frameId } + 1}")
                                .config
                                .getOrNull(SemanticsProperties.Selected),
                        )
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
    fun timelineHideButtonAndShortcutDuringAStrokePreserveCanvasBoundsAndEveryNativePixel() =
        runBlocking {
            withSession(project()) {
                enable()
                if (controller.tool != Tool.Brush) click("Brush")
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
                    val hide = node("Hide timeline")
                    assertTrue(hide.config.contains(SemanticsProperties.Disabled))
                    assertNotNull(hide.config[SemanticsActions.OnClick].action).invoke()
                    render().close()
                    assertTrue(controller.drawingInput)
                    assertTrue(controller.animationTimelineVisible)
                    assertEquals(bounds, canvasBounds())
                }
                key(androidx.compose.ui.input.key.Key.A, command = true, shift = true)
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controller.drawingInput)
                    assertTrue(controller.animationTimelineVisible)
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
                click("Hide timeline")
                withContext(Dispatchers.Main) {
                    assertFalse(controller.animationTimelineVisible)
                    assertTrue(canvasBounds().height > bounds.height)
                    assertEquals(completed, controller.document)
                }
                key(androidx.compose.ui.input.key.Key.A, command = true, shift = true)
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controller.animationTimelineVisible)
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
