package app.podor.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AsepriteWorkflowRenderingTest {
    private data class SaveRequest(
        val bytes: ByteArray,
        val reference: ProjectReference?,
        val saveAs: Boolean,
    )

    private class MemoryFiles(
        val input: ByteArray,
        val inputReference: ProjectReference = ProjectReference("memory/source.pod", "Source.pod"),
        override val supportsAsepriteProjects: Boolean = true,
        val appearance: Appearance = Appearance.Light,
    ) : ProjectFiles {
        val documents = ConcurrentHashMap<String, ByteArray>()
        val saveRequests = CopyOnWriteArrayList<SaveRequest>()
        val cancelSave = AtomicBoolean(true)
        val cancelExport = AtomicBoolean(false)
        val exported = AtomicReference<ByteArray>()
        val opens = AtomicInteger()
        val asepriteCalls = AtomicInteger()
        val asepriteWrites = AtomicInteger()
        val imageCalls = AtomicInteger()
        val animationCalls = AtomicInteger()
        val preferenceWrites = AtomicInteger()
        val saveGate = AtomicReference<CompletableDeferred<Unit>?>()
        override val exportFormats = listOf(ExportFormat.Png)
        override val animationExportFormats = AnimationExportFormat.entries

        init {
            documents[inputReference.id] = input.copyOf()
        }

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            opens.incrementAndGet()
            return input.copyOf()
        }

        override suspend fun openDocument(reference: ProjectReference?): OpenedProject {
            assertFalse(EventQueue.isDispatchThread())
            assertNull(reference)
            opens.incrementAndGet()
            return OpenedProject(input.copyOf(), inputReference)
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            error("Unexpected legacy save route")
        }

        override suspend fun saveDocument(
            bytes: ByteArray,
            reference: ProjectReference?,
            saveAs: Boolean,
        ): ProjectReference? {
            assertFalse(EventQueue.isDispatchThread())
            saveRequests += SaveRequest(bytes.copyOf(), reference, saveAs)
            saveGate.get()?.await()
            if (cancelSave.get()) return null
            val target =
                reference?.takeIf { ProjectFileNames.existingDestination(it, saveAs) != null }
                    ?: ProjectReference("memory/converted.pod", "Converted.pod")
            documents[target.id] = bytes.copyOf()
            return target
        }

        override suspend fun exportAseprite(bytes: ByteArray): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            asepriteCalls.incrementAndGet()
            exported.set(bytes.copyOf())
            if (cancelExport.get()) return false
            documents["memory/copy.aseprite"] = bytes.copyOf()
            asepriteWrites.incrementAndGet()
            return true
        }

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            imageCalls.incrementAndGet()
            error("A project export reached the image writer")
        }

        override suspend fun exportAnimation(
            bytes: ByteArray,
            format: AnimationExportFormat,
        ): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            animationCalls.incrementAndGet()
            error("A project export reached the animation writer")
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
            preferenceWrites.incrementAndGet()
        }

        override suspend fun rememberProject(
            reference: ProjectReference,
            width: Int,
            height: Int,
            thumbnail: ByteArray,
        ) {
            assertFalse(EventQueue.isDispatchThread())
        }
    }

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun command(engine: NativeEngine, value: String): DocumentInfo {
        val info = state(engine)
        val source = Json.parseToJsonElement(value).jsonObject
        val type = source.getValue("type").jsonPrimitive.content
        val request = buildJsonObject {
            source.forEach { (key, entry) -> put(key, entry) }
            put("revision", info.revision)
            info.animation
                ?.takeIf { type !in setOf("select_frame", "state", "undo", "redo") }
                ?.let {
                    put("frame_id", source["frame_id"] ?: JsonPrimitive(it.activeFrameId))
                    put("cel_id", it.activeCelId?.let(::JsonPrimitive) ?: JsonNull)
                    put("target_layer_id", info.active)
                }
        }
        return Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, request.toString().encodeToByteArray())
                .decodeToString()
        )
    }

    private suspend fun <T> probe(bytes: ByteArray? = null, block: (NativeEngine) -> T): T =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            NativeLoader.load()
            val engine = createNativeEngine(64, 48)
            try {
                if (bytes != null) engine.call(EngineOperation.LOAD, bytes)
                block(engine)
            } finally {
                engine.close()
            }
        }

    private suspend fun project(
        extras: Boolean = false,
        finitePingPong: Boolean = false,
    ): ByteArray = probe { engine ->
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String) {
            command(
                engine,
                """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}""",
            )
            command(engine, """{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""")
            command(engine, """{"type":"select","rect":null}""")
        }
        command(engine, """{"type":"set_layer","id":1,"name":"Base","visible":true,"opacity":1}""")
        fill(0, 0, 16, 24, "[240,40,20,255]")
        val overlay = command(engine, """{"type":"add_layer"}""").active
        command(
            engine,
            """{"type":"set_layer","id":$overlay,"name":"Overlay","visible":true,"opacity":1}""",
        )
        fill(32, 8, 48, 32, "[20,160,200,255]")
        val first =
            command(engine, """{"type":"enable_animation","duration_ms":100}""")
                .animation!!
                .activeFrameId
        val second =
            command(
                    engine,
                    """{"type":"duplicate_frame","frame_id":$first,"index":1,"linked":true}""",
                )
                .animation!!
                .activeFrameId
        command(engine, """{"type":"set_frame_duration","frame_id":$second,"duration_ms":230}""")
        val third =
            command(engine, """{"type":"add_frame","index":2,"duration_ms":60}""")
                .animation!!
                .activeFrameId
        command(engine, """{"type":"select_layer","id":1}""")
        fill(16, 8, 32, 24, "[40,180,70,255]")
        if (extras) {
            command(engine, """{"type":"add_mask","mode":"hide","name":"Other frame mask"}""")
            command(engine, """{"type":"set_mask_editing","id":1,"enabled":false}""")
            val vector =
                command(
                        engine,
                        """{"type":"create_vector","name":"Other frame vector","parent_id":null,"index":2}""",
                    )
                    .active
            val shape =
                VectorObjectSpec(
                    name = "Gold rectangle",
                    geometry = VectorGeometry.Rectangle(48f, 8f, 8f, 12f),
                    style = VectorStyle(fill = listOf(230, 190, 40, 255)),
                )
            command(
                engine,
                """{"type":"add_vector_object","id":$vector,"index":null,"object":${shape.request()}}""",
            )
            val settings =
                AdjustmentLayerSettings(AdjustmentKind.Tone, brightness = 0.15f).request()
            command(
                engine,
                """{"type":"create_adjustment","name":"Tone","parent_id":null,"index":3,"selection_id":${state(engine).selectionId},"settings":$settings}""",
            )
        }
        command(
            engine,
            """{"type":"add_frame_tag","tag":{"name":"Tail","color":[30,120,210,255],"from_frame":$second,"to_frame":$third,"direction":"${if (finitePingPong) "ping_pong_reverse" else "reverse"}","repeat":3}}""",
        )
        command(engine, """{"type":"select_frame","frame_id":$first}""")
        command(engine, """{"type":"select_layer","id":1}""")
        command(engine, """{"type":"select","rect":{"left":2,"top":2,"right":6,"bottom":6}}""")
        engine.call(EngineOperation.SAVE)
    }

    private fun wordAt(bytes: ByteArray, offset: Int): Int {
        assertTrue(offset >= 0 && offset + 2 <= bytes.size)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        assertTrue(offset >= 0 && offset + 4 <= bytes.size)
        return (0..3).fold(0) { value, byte ->
            value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
        }
    }

    private fun framePixels(engine: NativeEngine, frameId: Int): IntArray {
        val info = state(engine)
        val saved = engine.call(EngineOperation.SAVE)
        val packet =
            engine.call(
                EngineOperation.ANIMATION_FRAME,
                """{"revision":${info.revision},"frame_id":$frameId,"transparent":true}"""
                    .encodeToByteArray(),
            )
        assertEquals(info.width, intAt(packet, 0))
        assertEquals(info.height, intAt(packet, 4))
        val size = intAt(packet, 8)
        var offset = 16
        return IntArray(info.width * info.height).also { output ->
            repeat(intAt(packet, 12)) {
                val left = intAt(packet, offset) * size
                val top = intAt(packet, offset + 4) * size
                for (y in 0 until minOf(size, info.height - top)) for (x in
                    0 until minOf(size, info.width - left)) {
                    val position = offset + 8 + (y * size + x) * 4
                    val alpha = packet[position + 3].toInt() and 255
                    fun channel(index: Int): Int =
                        if (alpha == 0) 0
                        else
                            (((packet[position + index].toInt() and 255) * 255 + alpha / 2) / alpha)
                                .coerceAtMost(255)
                    output[(top + y) * info.width + left + x] =
                        (alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                }
                offset += 8 + size * size * 4
            }
            assertEquals(packet.size, offset)
            assertEquals(info, state(engine))
            assertContentEquals(saved, engine.call(EngineOperation.SAVE))
        }
    }

    private suspend fun allPixels(bytes: ByteArray): List<IntArray> =
        probe(bytes) { engine ->
            state(engine).animation!!.frames.map { framePixels(engine, it.id) }
        }

    private val strokeBrush =
        BrushSettings(preset = BrushPreset.PixelPencil, size = 1f, opacity = 1f, color = 0xFF38BDF8)
    private val strokePoints =
        listOf(Triple(2.5f, 3.5f, 1f), Triple(3.5f, 3.5f, 1f), Triple(5.5f, 3.5f, 1f))

    private suspend fun strokePixels(bytes: ByteArray): List<IntArray> =
        probe(bytes) { engine ->
            command(
                engine,
                """{"type":"begin","brush":{"size":1,"opacity":1,"hardness":1,"color":[56,189,248],"eraser":false,"size_pressure":0,"raster":"pixel"}}""",
            )
            val packet = ByteArray(strokePoints.size * 12)
            strokePoints.forEachIndexed { index, point ->
                listOf(point.first, point.second, point.third).forEachIndexed { component, value ->
                    repeat(4) { byte ->
                        packet[index * 12 + component * 4 + byte] =
                            (value.toBits() ushr (byte * 8)).toByte()
                    }
                }
            }
            engine.call(EngineOperation.SAMPLES, packet)
            command(engine, """{"type":"end"}""")
            state(engine).animation!!.frames.map { framePixels(engine, it.id) }
        }

    private data class Snapshot(
        val document: DocumentInfo,
        val frame: RenderFrame,
        val reference: ProjectReference?,
        val dirty: Boolean,
        val tagId: Int?,
        val direction: AnimationDirection,
        val bytes: ByteArray,
    )

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var time = 0L

        fun render() = scene.render(time++ * 16_666_667L).close()

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        private fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun matches(node: SemanticsNode, label: String, exact: Boolean = false) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || (!exact && it.startsWith("$label ·"))
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun control(label: String): SemanticsNode? {
            val candidates =
                nodes().filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
            val exact = candidates.filter {
                descendants(it).any { child -> matches(child, label, true) }
            }
            return (exact.ifEmpty { candidates }).minByOrNull {
                it.boundsInWindow.width * it.boundsInWindow.height
            }
        }

        fun hasText(label: String) =
            nodes().any { matches(it, label) && !it.boundsInWindow.isEmpty }

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    val done =
                        withContext(Dispatchers.Main) {
                            render()
                            if (!allowError) assertNull(controller.error)
                            predicate() &&
                                !controller.busy &&
                                !controller.animationTransition &&
                                controller.previews.revision == controller.document.revision
                        }
                    if (done) break
                    delay(5)
                }
            }

        suspend fun settle() {
            repeat(20) {
                withContext(Dispatchers.Main) { render() }
                delay(2)
            }
        }

        fun clickNow(label: String) {
            val node = assertNotNull(control(label), label)
            assertFalse(node.config.contains(SemanticsProperties.Disabled), label)
            val point = node.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            render()
        }

        suspend fun click(label: String, allowError: Boolean = false) {
            waitFor(allowError) {
                control(label)?.config?.contains(SemanticsProperties.Disabled) == false
            }
            withContext(Dispatchers.Main) { clickNow(label) }
            settle()
        }

        suspend fun exportFromMenu() {
            click("Project")
            click("Export Aseprite project copy")
        }

        fun assertExportBlocked() {
            val node = assertNotNull(control("Export Aseprite project copy"))
            assertTrue(node.config.contains(SemanticsProperties.Disabled))
            scene.sendPointerEvent(PointerEventType.Press, node.boundsInWindow.center)
            render()
            scene.sendPointerEvent(PointerEventType.Release, node.boundsInWindow.center)
            render()
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            render()
            controller.exportAseprite()
            controller.exportAseprite(AsepriteExportOptions(bakeLayers = true))
        }

        suspend fun finishStroke(before: Snapshot, expected: List<IntArray>) {
            withContext(Dispatchers.Main) {
                controller.points(strokePoints.drop(1))
                controller.end()
            }
            waitFor {
                !controller.drawingInput &&
                    controller.document.revision == before.document.revision + 1
            }
            assertEquals(before.reference, controller.projectReference)
            assertEquals(before.document.selection, controller.document.selection)
            assertTrue(controller.hasUnsavedChanges)
            assertTrue(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            val actual = allPixels(snapshot().bytes)
            assertEquals(expected.size, actual.size)
            expected.indices.forEach {
                assertContentEquals(expected[it], actual[it], "Stroke frame $it")
            }
            assertFalse(actual.first().contentEquals(allPixels(before.bytes).first()))
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
            assertEquals(0, files.imageCalls.get())
            assertEquals(0, files.animationCalls.get())
            click("Undo")
            waitFor { controller.document.contentId == before.document.contentId }
            assertFalse(controller.document.canUndo)
            assertTrue(controller.document.canRedo)
            assertFalse(controller.hasUnsavedChanges)
            assertEquals(before.reference, controller.projectReference)
            assertContentEquals(before.bytes, snapshot().bytes)
        }

        suspend fun snapshot(): Snapshot {
            val before = controller.document
            val frame = controller.frame
            val reference = controller.projectReference
            val dirty = controller.hasUnsavedChanges
            val tagId = controller.animationTagId
            val direction = controller.animationDirection
            val count = files.saveRequests.size
            val cancel = files.cancelSave.getAndSet(true)
            try {
                if (withContext(Dispatchers.Main) { control("Save project") == null })
                    click("Project")
                click("Save project")
                waitFor { files.saveRequests.size == count + 1 }
            } finally {
                files.cancelSave.set(cancel)
            }
            assertEquals(before, controller.document)
            assertSame(frame, controller.frame)
            assertEquals(reference, controller.projectReference)
            assertEquals(dirty, controller.hasUnsavedChanges)
            assertEquals(tagId, controller.animationTagId)
            assertEquals(direction, controller.animationDirection)
            return Snapshot(
                before,
                frame,
                reference,
                dirty,
                tagId,
                direction,
                files.saveRequests.last().bytes.copyOf(),
            )
        }

        suspend fun assertUnchanged(before: Snapshot) {
            assertEquals(before.document, controller.document)
            assertSame(before.frame, controller.frame)
            assertEquals(before.reference, controller.projectReference)
            assertEquals(before.dirty, controller.hasUnsavedChanges)
            assertEquals(before.tagId, controller.animationTagId)
            assertEquals(before.direction, controller.animationDirection)
            assertContentEquals(before.bytes, snapshot().bytes)
            assertEquals(0, files.imageCalls.get())
            assertEquals(0, files.animationCalls.get())
        }

        suspend fun editDuration(value: Int) {
            val revision = controller.document.revision
            withContext(Dispatchers.Main) {
                controller.animationCommand("set_frame_duration") {
                    put("frame_id", controller.document.animation!!.activeFrameId)
                    put("duration_ms", value)
                }
            }
            waitFor { controller.document.revision == revision + 1 }
            assertTrue(controller.hasUnsavedChanges)
            assertTrue(controller.document.canUndo)
        }

        suspend fun screenshot(name: String) {
            val image = withContext(Dispatchers.Main) { scene.render(time++ * 16_666_667L) }
            try {
                withContext(Dispatchers.Default) {
                    assertFalse(EventQueue.isDispatchThread())
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                        val directory = Path.of("build", "reports", "screenshots")
                        Files.createDirectories(directory)
                        Files.write(directory.resolve(name), png.bytes)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { image.close() }
            }
        }
    }

    private suspend fun withSession(
        files: MemoryFiles,
        frames: Int = 3,
        width: Int = 1600,
        height: Int = 1200,
        block: suspend Session.() -> Unit,
    ) {
        val appearance = StudioTheme.appearance
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
            session.click("Project")
            session.click("Open project / image")
            session.click("Choose file")
            session.waitFor {
                controller.hasCanvas &&
                    files.opens.get() == 1 &&
                    controller.document.animation?.frames?.size == frames
            }
            assertEquals(64, controller.document.width)
            assertEquals(48, controller.document.height)
            assertFalse(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.close()
                val restore = ImageComposeScene(1, 1) { PodorTheme(appearance = appearance) {} }
                try {
                    restore.render(0).close()
                } finally {
                    restore.close()
                }
            }
            scope.cancel()
        }
    }

    private data class EncodedCel(
        val type: Int,
        val x: Int,
        val y: Int,
        val link: Int?,
        val pixels: IntArray,
    )

    private data class EncodedTag(
        val from: Int,
        val to: Int,
        val direction: Int,
        val repeat: Int,
        val name: String,
        val color: List<Int>,
    )

    private data class AsepriteFile(
        val layers: List<String>,
        val durations: List<Int>,
        val cels: List<Map<Int, EncodedCel>>,
        val tags: List<EncodedTag>,
    ) {
        fun pixels(frame: Int): IntArray =
            IntArray(64 * 48).also { output ->
                layers.indices.forEach { layer ->
                    cels[frame][layer]?.pixels?.forEachIndexed { index, color ->
                        if (color ushr 24 != 0) {
                            assertTrue(output[index] == 0 || color ushr 24 == 255)
                            output[index] = color
                        }
                    }
                }
            }
    }

    private fun readAseprite(bytes: ByteArray): AsepriteFile {
        assertTrue(bytes.size >= 128)
        assertEquals(bytes.size, intAt(bytes, 0))
        assertEquals(0xa5e0, wordAt(bytes, 4))
        assertEquals(64, wordAt(bytes, 8))
        assertEquals(48, wordAt(bytes, 10))
        assertEquals(32, wordAt(bytes, 12))
        assertEquals(1, intAt(bytes, 14) and 1)
        val layers = mutableListOf<String>()
        val durations = mutableListOf<Int>()
        val frames = mutableListOf<Map<Int, EncodedCel>>()
        val tags = mutableListOf<EncodedTag>()
        var tagColor = 0
        var position = 128
        fun string(offset: Int, end: Int): Pair<String, Int> {
            val count = wordAt(bytes, offset)
            assertTrue(offset + 2 + count <= end)
            return bytes.copyOfRange(offset + 2, offset + 2 + count).decodeToString() to
                offset + 2 + count
        }
        repeat(wordAt(bytes, 6)) { frame ->
            val end = position + intAt(bytes, position)
            assertTrue(end in position + 16..bytes.size)
            assertEquals(0xf1fa, wordAt(bytes, position + 4))
            durations += wordAt(bytes, position + 8)
            val chunks =
                intAt(bytes, position + 12).takeIf { it != 0 } ?: wordAt(bytes, position + 6)
            position += 16
            val cels = mutableMapOf<Int, EncodedCel>()
            repeat(chunks) {
                val chunkEnd = position + intAt(bytes, position)
                assertTrue(chunkEnd in position + 6..end)
                val data = position + 6
                when (wordAt(bytes, position + 4)) {
                    0x2004 -> {
                        assertEquals(0, frame)
                        assertEquals(3, wordAt(bytes, data) and 3)
                        assertEquals(0, wordAt(bytes, data + 2))
                        assertEquals(0, wordAt(bytes, data + 4))
                        assertEquals(0, wordAt(bytes, data + 10))
                        assertEquals(255, bytes[data + 12].toInt() and 255)
                        val name = string(data + 16, chunkEnd)
                        assertEquals(chunkEnd, name.second)
                        layers += name.first
                    }
                    0x2005 -> {
                        val layer = wordAt(bytes, data)
                        assertTrue(layer in layers.indices)
                        assertFalse(layer in cels)
                        val x = wordAt(bytes, data + 2).toShort().toInt()
                        val y = wordAt(bytes, data + 4).toShort().toInt()
                        assertEquals(255, bytes[data + 6].toInt() and 255)
                        val type = wordAt(bytes, data + 7)
                        assertEquals(0, wordAt(bytes, data + 9))
                        if (type == 1) {
                            assertEquals(data + 18, chunkEnd)
                            val prior = wordAt(bytes, data + 16)
                            assertTrue(prior in 0 until frame)
                            val source = assertNotNull(frames[prior][layer])
                            assertEquals(source.x, x)
                            assertEquals(source.y, y)
                            cels[layer] = EncodedCel(type, x, y, prior, source.pixels.copyOf())
                        } else {
                            assertTrue(type == 0 || type == 2)
                            val width = wordAt(bytes, data + 16)
                            val height = wordAt(bytes, data + 18)
                            assertTrue(
                                width > 0 &&
                                    height > 0 &&
                                    x >= 0 &&
                                    y >= 0 &&
                                    x + width <= 64 &&
                                    y + height <= 48
                            )
                            val input = bytes.copyOfRange(data + 20, chunkEnd)
                            val rgba =
                                if (type == 0) input
                                else {
                                    val inflater = Inflater()
                                    try {
                                        inflater.setInput(input)
                                        ByteArray(width * height * 4).also { output ->
                                            var written = 0
                                            while (written < output.size) {
                                                val count =
                                                    inflater.inflate(
                                                        output,
                                                        written,
                                                        output.size - written,
                                                    )
                                                assertTrue(count > 0)
                                                written += count
                                            }
                                            assertEquals(0, inflater.inflate(ByteArray(1)))
                                            assertTrue(inflater.finished())
                                            assertEquals(0, inflater.remaining)
                                        }
                                    } finally {
                                        inflater.end()
                                    }
                                }
                            assertEquals(width * height * 4, rgba.size)
                            val pixels = IntArray(64 * 48)
                            for (row in 0 until height) for (column in 0 until width) {
                                val offset = (row * width + column) * 4
                                val alpha = rgba[offset + 3].toInt() and 255
                                val red = rgba[offset].toInt() and 255
                                val green = rgba[offset + 1].toInt() and 255
                                val blue = rgba[offset + 2].toInt() and 255
                                if (alpha == 0) assertEquals(0, red or green or blue)
                                pixels[(y + row) * 64 + x + column] =
                                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                            cels[layer] = EncodedCel(type, x, y, null, pixels)
                        }
                    }
                    0x2018 -> {
                        assertEquals(0, frame)
                        var cursor = data + 10
                        repeat(wordAt(bytes, data)) {
                            val name = string(cursor + 17, chunkEnd)
                            tags +=
                                EncodedTag(
                                    wordAt(bytes, cursor),
                                    wordAt(bytes, cursor + 2),
                                    bytes[cursor + 4].toInt() and 255,
                                    wordAt(bytes, cursor + 5),
                                    name.first,
                                    (13..15).map { bytes[cursor + it].toInt() and 255 } + 255,
                                )
                            cursor = name.second
                        }
                        assertEquals(chunkEnd, cursor)
                        tagColor = 0
                    }
                    0x2020 -> {
                        assertEquals(2, intAt(bytes, data))
                        assertEquals(data + 8, chunkEnd)
                        assertTrue(tagColor in tags.indices)
                        tags[tagColor] =
                            tags[tagColor].copy(
                                color = (4..7).map { bytes[data + it].toInt() and 255 }
                            )
                        tagColor++
                    }
                    0x2007,
                    0x2019,
                    0x0004,
                    0x0011 -> Unit
                    else -> fail("Unexpected Aseprite output chunk ${wordAt(bytes, position + 4)}")
                }
                position = chunkEnd
            }
            assertEquals(end, position)
            frames += cels
        }
        assertEquals(bytes.size, position)
        assertTrue(layers.isNotEmpty())
        return AsepriteFile(layers, durations, frames, tags)
    }

    private suspend fun assertExchange(
        bytes: ByteArray,
        source: ByteArray,
        baked: Boolean,
    ): AsepriteFile {
        val file = readAseprite(bytes)
        val expected = allPixels(source)
        assertEquals(expected.size, file.durations.size)
        assertEquals(if (baked) 1 else 2, file.layers.size)
        expected.indices.forEach {
            assertContentEquals(expected[it], file.pixels(it), "Independent frame $it")
        }
        probe(bytes) { engine ->
            val imported = state(engine)
            assertEquals(file.durations, imported.animation!!.frames.map { it.durationMs })
            assertEquals(file.layers.toSet(), imported.layers.map { it.name }.toSet())
            assertEquals(file.tags.size, imported.animation!!.tags.size)
            file.tags.zip(imported.animation!!.tags).forEach { (tag, value) ->
                assertEquals(tag.name, value.name)
                assertEquals(imported.animation!!.frames[tag.from].id, value.fromFrame)
                assertEquals(imported.animation!!.frames[tag.to].id, value.toFrame)
                assertEquals(
                    listOf(
                        AnimationDirection.Forward,
                        AnimationDirection.Reverse,
                        AnimationDirection.PingPong,
                        AnimationDirection.PingPongReverse,
                    )[tag.direction],
                    value.direction,
                )
                assertEquals(tag.repeat, value.repeat)
                assertEquals(tag.color, value.color)
            }
            imported.animation!!.frames.forEachIndexed { index, frame ->
                assertContentEquals(
                    expected[index],
                    framePixels(engine, frame.id),
                    "Imported frame $index",
                )
            }
        }
        return file
    }

    @Test
    fun projectMenuExportsEveryEditableTrackFrameAndTagWithoutSavingOrFlatteningTheSource() =
        runBlocking {
            withSession(MemoryFiles(project())) {
                editDuration(125)
                withContext(Dispatchers.Main) {
                    controller.select(Offset(0f, 0f), Offset(20f, 20f))
                    controller.animationTagId = controller.document.animation!!.tags.single().id
                    controller.animationDirection = AnimationDirection.Forward
                }
                waitFor { controller.document.selection != null }
                val before = snapshot()
                val capability = assertNotNull(before.document.asepriteExport)
                assertTrue(capability.available)
                assertTrue(capability.editableIssues.isEmpty())
                assertTrue(capability.blockingIssues.isEmpty())
                assertNotNull(before.document.selection)
                exportFromMenu()
                waitFor { files.asepriteWrites.get() == 1 }
                withContext(Dispatchers.Main) { assertFalse(hasText("Aseprite project copy")) }
                val file =
                    assertExchange(assertNotNull(files.exported.get()), before.bytes, baked = false)
                assertEquals(setOf("Base", "Overlay"), file.layers.toSet())
                assertEquals(listOf(125, 230, 60), file.durations)
                assertEquals(listOf(2, 2, 1), file.cels.map { it.size })
                assertTrue(file.cels[1].values.all { it.type == 1 && it.link == 0 })
                assertEquals(
                    listOf(EncodedTag(1, 2, 1, 3, "Tail", listOf(30, 120, 210, 255))),
                    file.tags,
                )
                assertEquals(1, files.asepriteCalls.get())
                assertUnchanged(before)
                click("Undo")
                waitFor { controller.document.animation!!.frames.first().durationMs == 100 }
                assertFalse(controller.document.canUndo)
                assertTrue(controller.document.canRedo)
                assertFalse(controller.hasUnsavedChanges)
                click("Redo")
                waitFor { controller.document.animation!!.frames.first().durationMs == 125 }
                assertFalse(controller.document.canRedo)
                assertTrue(controller.hasUnsavedChanges)
                assertContentEquals(before.bytes, snapshot().bytes)
            }
        }

    @Test
    fun masksAndVectorsInOtherFramesRequireAnExplicitCompositedCopyAndCancelWritesNothing() =
        runBlocking {
            val source = project(extras = true)
            for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                withSession(
                    MemoryFiles(source, appearance = appearance),
                    width = 400,
                    height = 800,
                ) {
                    editDuration(125)
                    val before = snapshot()
                    val issues =
                        assertNotNull(before.document.asepriteExport).editableIssues.associate {
                            it.kind to it.count
                        }
                    assertEquals(1, issues["mask"])
                    assertEquals(1, issues["vector"])
                    assertEquals(1, issues["adjustment"])
                    assertTrue(before.document.layers.single { it.id == 1 }.masks.isEmpty())
                    assertTrue(
                        assertNotNull(before.document.asepriteExport).blockingIssues.isEmpty()
                    )
                    exportFromMenu()
                    withContext(Dispatchers.Main) {
                        assertTrue(hasText("Aseprite project copy"))
                        assertTrue(hasText("Vector layer"))
                        assertTrue(hasText("Adjustment layer"))
                        assertEquals(
                            ToggleableState.Off,
                            assertNotNull(control("Export composited frame copy"))
                                .config[SemanticsProperties.ToggleableState],
                        )
                        assertTrue(
                            assertNotNull(control("Export"))
                                .config
                                .contains(SemanticsProperties.Disabled)
                        )
                    }
                    screenshot("aseprite-export-${appearance.name.lowercase()}.png")
                    click("Cancel")
                    assertEquals(0, files.asepriteCalls.get())
                    assertUnchanged(before)
                    exportFromMenu()
                    click("Export composited frame copy")
                    withContext(Dispatchers.Main) {
                        assertEquals(
                            ToggleableState.On,
                            assertNotNull(control("Export composited frame copy"))
                                .config[SemanticsProperties.ToggleableState],
                        )
                        assertFalse(
                            assertNotNull(control("Export"))
                                .config
                                .contains(SemanticsProperties.Disabled)
                        )
                    }
                    click("Export")
                    waitFor { files.asepriteWrites.get() == 1 }
                    val file =
                        assertExchange(
                            assertNotNull(files.exported.get()),
                            before.bytes,
                            baked = true,
                        )
                    assertEquals(listOf(125, 230, 60), file.durations)
                    assertEquals(1, file.tags.size)
                    assertEquals("Tail", file.tags.single().name)
                    assertUnchanged(before)
                }
            }
        }

    @Test
    fun finitePingPongCannotBeBakedOrWrittenFromTheDialogOrProgrammaticRoute() = runBlocking {
        withSession(MemoryFiles(project(finitePingPong = true))) {
            val before = snapshot()
            assertEquals(
                listOf(AsepriteExportIssue("finite_ping_pong_repeat", 1)),
                assertNotNull(before.document.asepriteExport).blockingIssues,
            )
            exportFromMenu()
            withContext(Dispatchers.Main) {
                assertTrue(hasText("Finite ping-pong playback"))
                assertNull(control("Export composited frame copy"))
                assertTrue(
                    assertNotNull(control("Export")).config.contains(SemanticsProperties.Disabled)
                )
            }
            click("Cancel")
            withContext(Dispatchers.Main) {
                controller.exportAseprite(AsepriteExportOptions(bakeLayers = true))
            }
            waitFor(allowError = true) { controller.error != null }
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
            click("OK", allowError = true)
            assertUnchanged(before)
        }
    }

    @Test
    fun absentNativeCapabilityOrPlatformWriterHidesTheEntryAndRejectsProgrammaticExports() =
        runBlocking {
            val source = project()
            for (platform in listOf(true, false)) {
                withSession(MemoryFiles(source, supportsAsepriteProjects = platform)) {
                    val before = snapshot()
                    withContext(Dispatchers.Main) {
                        val field = controller.javaClass.getDeclaredField("document\$delegate")
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val documentState = field.get(controller) as MutableState<DocumentInfo>
                        if (platform)
                            documentState.value = before.document.copy(asepriteExport = null)
                        render()
                    }
                    click("Project")
                    withContext(Dispatchers.Main) {
                        assertFalse(controller.asepriteExportAvailable)
                        assertNull(control("Export Aseprite project copy"))
                        controller.exportAseprite()
                        controller.exportAseprite(AsepriteExportOptions(bakeLayers = true))
                    }
                    settle()
                    assertEquals(0, files.asepriteCalls.get())
                    assertEquals(0, files.asepriteWrites.get())
                    withContext(Dispatchers.Main) {
                        val field = controller.javaClass.getDeclaredField("document\$delegate")
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val documentState = field.get(controller) as MutableState<DocumentInfo>
                        documentState.value = before.document
                        render()
                    }
                    assertUnchanged(before)
                }
            }
        }

    @Test
    fun aHeldStrokeBlocksTheMenuAndProgrammaticExportWithoutInterruptingDrawing() = runBlocking {
        withSession(MemoryFiles(project())) {
            val before = snapshot()
            val expected = strokePixels(before.bytes)
            click("Project")
            withContext(Dispatchers.Main) {
                controller.brush = strokeBrush
                controller.begin(
                    Offset(strokePoints.first().first, strokePoints.first().second),
                    1f,
                )
                assertTrue(controller.drawingInput)
                render()
                assertExportBlocked()
            }
            settle()
            assertTrue(controller.drawingInput)
            assertEquals(0, files.asepriteCalls.get())
            finishStroke(before, expected)
        }
    }

    @Test
    fun aRealPendingSaveBlocksAsepriteExportWithoutChangingTheSourceOrHistory() = runBlocking {
        withSession(MemoryFiles(project())) {
            val before = snapshot()
            val saves = files.saveRequests.size
            val gate = CompletableDeferred<Unit>()
            files.saveGate.set(gate)
            try {
                click("Project")
                withContext(Dispatchers.Main) { clickNow("Save project") }
                withTimeout(15_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            render()
                            assertNull(controller.error)
                            controller.busy && files.saveRequests.size == saves + 1
                        }
                    ) delay(5)
                }
                withContext(Dispatchers.Main) {
                    clickNow("Project")
                    assertTrue(controller.busy)
                    assertExportBlocked()
                }
                settle()
                assertTrue(controller.busy)
                assertEquals(0, files.asepriteCalls.get())
                assertEquals(0, files.asepriteWrites.get())
            } finally {
                files.saveGate.set(null)
                gate.complete(Unit)
            }
            waitFor { files.saveRequests.size == saves + 1 }
            assertUnchanged(before)
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
        }
    }

    @Test
    fun realAnimationPlaybackBlocksTheMenuAndProgrammaticAsepriteExport() = runBlocking {
        withSession(MemoryFiles(project())) {
            val before = snapshot()
            click("Project")
            withContext(Dispatchers.Main) {
                controller.startAnimation()
                assertTrue(controller.animationPlaying)
                render()
                assertExportBlocked()
            }
            waitFor {
                controller.animationPlaying &&
                    controller.animationDisplayFrameId != null &&
                    controller.animationDisplayFrameId != before.document.animation!!.activeFrameId
            }
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
            withContext(Dispatchers.Main) { controller.stopAnimation() }
            waitFor { !controller.animationPlaying && controller.animationDisplayFrame == null }
            assertUnchanged(before)
        }
    }

    @Test
    fun anExportQueuedBeforeBeginInTheSameMainTurnCannotCancelTheAcceptedStroke() = runBlocking {
        withSession(MemoryFiles(project())) {
            val before = snapshot()
            val expected = strokePixels(before.bytes)
            val preferences = files.preferenceWrites.get()
            withContext(Dispatchers.Main) {
                controller.brush = strokeBrush
                controller.exportAseprite()
                controller.begin(
                    Offset(strokePoints.first().first, strokePoints.first().second),
                    1f,
                )
                assertTrue(controller.drawingInput)
                controller.updatePreferences(controller.preferences)
            }
            waitFor(allowError = true) { files.preferenceWrites.get() == preferences + 1 }
            assertTrue(controller.drawingInput)
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
            if (controller.error != null) click("OK", allowError = true)
            finishStroke(before, expected)
        }
    }

    @Test
    fun aRealQueuedDurationEditMakesTheClickedExportStaleWithoutAddingAnUndoStep() = runBlocking {
        withSession(MemoryFiles(project())) {
            val before = snapshot()
            val first = before.document.animation!!.activeFrameId
            click("Project")
            withContext(Dispatchers.Main) {
                controller.command("set_frame_duration") {
                    put("revision", before.document.revision)
                    put("frame_id", first)
                    put("duration_ms", 111)
                }
                clickNow("Export Aseprite project copy")
            }
            waitFor(allowError = true) {
                controller.error != null &&
                    controller.document.revision == before.document.revision + 1
            }
            assertTrue(assertNotNull(controller.error).contains("工程已改变"))
            assertEquals(111, controller.document.animation!!.frame(first)!!.durationMs)
            assertEquals(before.document.selection, controller.document.selection)
            assertEquals(before.reference, controller.projectReference)
            assertTrue(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            assertEquals(0, files.asepriteCalls.get())
            assertEquals(0, files.asepriteWrites.get())
            click("OK", allowError = true)
            click("Undo")
            waitFor { controller.document.animation!!.frame(first)!!.durationMs == 100 }
            assertFalse(controller.document.canUndo)
            assertTrue(controller.document.canRedo)
            assertFalse(controller.hasUnsavedChanges)
            assertContentEquals(before.bytes, snapshot().bytes)
            assertEquals(0, files.imageCalls.get())
            assertEquals(0, files.animationCalls.get())
        }
    }

    @Test
    fun cancellingTheAsepriteSaveDestinationLeavesTheDirtyProjectAndHistoryUntouched() =
        runBlocking {
            withSession(MemoryFiles(project())) {
                editDuration(125)
                val before = snapshot()
                files.cancelExport.set(true)
                exportFromMenu()
                waitFor { files.asepriteCalls.get() == 1 }
                assertNotNull(files.exported.get())
                assertEquals(0, files.asepriteWrites.get())
                assertFalse(files.documents.containsKey("memory/copy.aseprite"))
                assertUnchanged(before)
            }
        }

    @Test
    fun asepriteMagicOverridesAnEditablePodorReferenceAndSavingPreservesFramesAndNamedCompanionColors() =
        runBlocking {
            val external = manualAseprite()
            val expectedPixels =
                listOf(0xff14a0c8.toInt(), 0xff28b446.toInt()).map { overlay ->
                    IntArray(64 * 48).also { pixels ->
                        for (row in 0..1) for (column in 0..1) pixels[row * 64 + column] =
                            0xfff02814.toInt()
                        for (row in 8..9) for (column in 8..9) pixels[row * 64 + column] = overlay
                    }
                }
            val independent = readAseprite(external)
            assertEquals(listOf("Base", "Overlay"), independent.layers)
            assertEquals(listOf(100, 230), independent.durations)
            assertEquals(1, independent.cels[1].getValue(0).type)
            assertEquals(0, independent.cels[1].getValue(0).link)
            assertEquals(
                listOf(EncodedTag(0, 1, 0, 3, "Walk", listOf(30, 120, 210, 255))),
                independent.tags,
            )
            expectedPixels.indices.forEach {
                assertContentEquals(expectedPixels[it], independent.pixels(it))
            }
            allPixels(external).forEachIndexed { index, pixels ->
                assertContentEquals(expectedPixels[index], pixels)
            }
            val reference =
                ProjectReference("memory/foreign.pod", "Looks native.pod", editable = true)
            withSession(MemoryFiles(external, reference), frames = 2) {
                assertEquals(reference.copy(editable = false), controller.projectReference)
                assertEquals(DocumentColorMode.Rgba, controller.document.colorMode)
                val metadata = assertNotNull(controller.document.asepriteMetadata)
                assertEquals(AsepriteGrid(0, 0, 16, 16), metadata.grid)
                assertFalse(metadata.srgb)
                assertEquals(
                    listOf(listOf(240, 40, 20, 255), listOf(20, 160, 200, 255)),
                    assertNotNull(metadata.companionPalette).colors,
                )
                assertEquals(
                    listOf("Ruby", "Ocean"),
                    assertNotNull(metadata.companionPalette).names,
                )
                assertEquals(
                    listOf(100, 230),
                    controller.document.animation!!.frames.map { it.durationMs },
                )
                val importedAnimation = assertNotNull(controller.document.animation)
                val importedTag = importedAnimation.tags.single()
                assertEquals("Walk", importedTag.name)
                assertEquals(importedAnimation.frames[0].id, importedTag.fromFrame)
                assertEquals(importedAnimation.frames[1].id, importedTag.toFrame)
                assertEquals(AnimationDirection.Forward, importedTag.direction)
                assertEquals(3, importedTag.repeat)
                assertEquals(listOf(30, 120, 210, 255), importedTag.color)
                assertEquals(
                    setOf("Base", "Overlay"),
                    controller.document.layers.map { it.name }.toSet(),
                )
                val baseTrack = controller.document.layers.single { it.name == "Base" }.id
                assertEquals(
                    assertNotNull(
                        importedAnimation.exposure(importedAnimation.frames[0].id, baseTrack)?.celId
                    ),
                    assertNotNull(
                        importedAnimation.exposure(importedAnimation.frames[1].id, baseTrack)?.celId
                    ),
                )
                val beforeColor = snapshot()
                val personalPalette = controller.preferences.palette.toList()
                click("Show panel")
                click("Color")
                click("Ruby · #F02814")
                assertEquals(0xfff02814L, controller.brush.color)
                withContext(Dispatchers.Main) {
                    assertTrue(
                        assertNotNull(control("Ruby · #F02814"))
                            .config[SemanticsProperties.Selected]
                    )
                    assertFalse(
                        assertNotNull(control("Ocean · #14A0C8"))
                            .config[SemanticsProperties.Selected]
                    )
                }
                assertEquals(personalPalette, controller.preferences.palette)
                assertUnchanged(beforeColor)
                click("Ocean · #14A0C8")
                assertEquals(0xff14a0c8L, controller.brush.color)
                withContext(Dispatchers.Main) {
                    assertFalse(
                        assertNotNull(control("Ruby · #F02814"))
                            .config[SemanticsProperties.Selected]
                    )
                    assertTrue(
                        assertNotNull(control("Ocean · #14A0C8"))
                            .config[SemanticsProperties.Selected]
                    )
                }
                assertEquals(personalPalette, controller.preferences.palette)
                assertUnchanged(beforeColor)
                val beforeSave = controller.document
                val count = files.saveRequests.size
                files.cancelSave.set(false)
                if (withContext(Dispatchers.Main) { control("Save project") == null })
                    click("Project")
                click("Save project")
                waitFor {
                    files.saveRequests.size == count + 1 &&
                        controller.projectReference?.id == "memory/converted.pod"
                }
                assertEquals(reference.copy(editable = false), files.saveRequests.last().reference)
                assertFalse(files.saveRequests.last().saveAs)
                assertContentEquals(external, files.documents.getValue(reference.id))
                val saved = files.documents.getValue("memory/converted.pod")
                assertContentEquals("PODOR\u000c".encodeToByteArray(), saved.copyOf(6))
                assertFalse(controller.hasUnsavedChanges)
                assertEquals(beforeSave, controller.document)
                assertEquals(metadata, controller.document.asepriteMetadata)
                probe(saved) { engine ->
                    val restored = state(engine)
                    assertEquals(beforeSave.animation, restored.animation)
                    assertEquals(metadata, restored.asepriteMetadata)
                    assertEquals(setOf("Base", "Overlay"), restored.layers.map { it.name }.toSet())
                    restored.animation!!.frames.forEachIndexed { index, frame ->
                        assertContentEquals(expectedPixels[index], framePixels(engine, frame.id))
                    }
                }
                assertEquals(0, files.asepriteCalls.get())
                assertEquals(0, files.imageCalls.get())
                assertEquals(0, files.animationCalls.get())
            }
        }

    private fun manualAseprite(): ByteArray {
        fun data(block: ByteArrayOutputStream.() -> Unit) =
            ByteArrayOutputStream().apply(block).toByteArray()
        fun ByteArrayOutputStream.word(value: Int) {
            write(value and 255)
            write((value ushr 8) and 255)
        }
        fun ByteArrayOutputStream.dword(value: Int) {
            repeat(4) { write((value ushr (it * 8)) and 255) }
        }
        fun ByteArrayOutputStream.name(value: String) {
            val bytes = value.encodeToByteArray()
            word(bytes.size)
            write(bytes)
        }
        fun chunk(type: Int, body: ByteArray) = data {
            dword(body.size + 6)
            word(type)
            write(body)
        }
        fun layer(layerName: String) =
            chunk(
                0x2004,
                data {
                    word(3)
                    word(0)
                    word(0)
                    word(0)
                    word(0)
                    word(0)
                    write(255)
                    write(ByteArray(3))
                    name(layerName)
                },
            )
        fun cel(layer: Int, x: Int, y: Int, color: List<Int>) =
            chunk(
                0x2005,
                data {
                    word(layer)
                    word(x)
                    word(y)
                    write(255)
                    word(0)
                    word(0)
                    write(ByteArray(5))
                    word(2)
                    word(2)
                    repeat(4) { color.forEach(::write) }
                },
            )
        val linked =
            chunk(
                0x2005,
                data {
                    word(0)
                    word(0)
                    word(0)
                    write(255)
                    word(1)
                    word(0)
                    write(ByteArray(5))
                    word(0)
                },
            )
        val palette =
            chunk(
                0x2019,
                data {
                    dword(2)
                    dword(0)
                    dword(1)
                    write(ByteArray(8))
                    word(1)
                    listOf(240, 40, 20, 255).forEach(::write)
                    name("Ruby")
                    word(1)
                    listOf(20, 160, 200, 255).forEach(::write)
                    name("Ocean")
                },
            )
        val tag =
            chunk(
                0x2018,
                data {
                    word(1)
                    write(ByteArray(8))
                    word(0)
                    word(1)
                    write(0)
                    word(3)
                    write(ByteArray(6))
                    write(byteArrayOf(30, 120, 210.toByte(), 0))
                    name("Walk")
                },
            )
        fun frame(duration: Int, chunks: List<ByteArray>) = data {
            dword(16 + chunks.sumOf { it.size })
            word(0xf1fa)
            word(chunks.size)
            word(duration)
            word(0)
            dword(chunks.size)
            chunks.forEach(::write)
        }
        val frames =
            listOf(
                frame(
                    100,
                    listOf(
                        layer("Base"),
                        layer("Overlay"),
                        palette,
                        tag,
                        cel(0, 0, 0, listOf(240, 40, 20, 255)),
                        cel(1, 8, 8, listOf(20, 160, 200, 255)),
                    ),
                ),
                frame(230, listOf(linked, cel(1, 8, 8, listOf(40, 180, 70, 255)))),
            )
        val header = ByteArray(128)
        fun put(offset: Int, value: Int, size: Int) {
            repeat(size) { header[offset + it] = (value ushr (it * 8)).toByte() }
        }
        put(0, 128 + frames.sumOf { it.size }, 4)
        put(4, 0xa5e0, 2)
        put(6, 2, 2)
        put(8, 64, 2)
        put(10, 48, 2)
        put(12, 32, 2)
        put(14, 1, 4)
        put(18, 100, 2)
        put(32, 2, 2)
        put(34, 1, 1)
        put(35, 1, 1)
        put(40, 16, 2)
        put(42, 16, 2)
        return data {
            write(header)
            frames.forEach(::write)
        }
    }
}
