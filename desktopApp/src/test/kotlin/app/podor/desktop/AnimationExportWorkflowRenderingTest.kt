package app.podor.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.w3c.dom.Node

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AnimationExportWorkflowRenderingTest {
    private data class Exported(val format: AnimationExportFormat, val bytes: ByteArray)

    private class MemoryFiles(val project: ByteArray) : ProjectFiles {
        val saved = AtomicReference<ByteArray>()
        val exported = AtomicReference<Exported>()
        val saves = AtomicInteger()
        val animationExports = AtomicInteger()
        val imageExports = AtomicInteger()
        override val animationExportFormats = AnimationExportFormat.entries

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return project.copyOf()
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            assertFalse(png)
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return true
        }

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            imageExports.incrementAndGet()
            return true
        }

        override suspend fun exportAnimation(
            bytes: ByteArray,
            format: AnimationExportFormat,
        ): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            exported.set(Exported(format, bytes.copyOf()))
            animationExports.incrementAndGet()
            return true
        }

        override suspend fun readPreferences(): ByteArray =
            Json.encodeToString(
                    Preferences(language = Language.English, appearance = Appearance.Light)
                )
                .encodeToByteArray()
    }

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun command(engine: NativeEngine, value: String): DocumentInfo {
        val info = state(engine)
        val animation = info.animation
        val source = Json.parseToJsonElement(value).jsonObject
        val type = source.getValue("type").jsonPrimitive.content
        val request = buildJsonObject {
            source.forEach { (key, entry) -> put(key, entry) }
            put("revision", info.revision)
            if (animation != null && type != "select_frame") {
                put("frame_id", source["frame_id"] ?: JsonPrimitive(animation.activeFrameId))
                put("cel_id", animation.activeCelId?.let(::JsonPrimitive) ?: JsonNull)
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
            NativeLoader.load()
            val engine = createNativeEngine(64, 48)
            try {
                if (bytes != null) engine.call(EngineOperation.LOAD, bytes)
                block(engine)
            } finally {
                engine.close()
            }
        }

    private suspend fun project(): ByteArray = probe { engine ->
        command(engine, """{"type":"select","rect":{"left":0,"top":0,"right":16,"bottom":24}}""")
        command(engine, """{"type":"fill","x":0,"y":0,"color":[255,0,0,255],"tolerance":0}""")
        command(engine, """{"type":"select","rect":null}""")
        val first =
            command(engine, """{"type":"enable_animation","duration_ms":70}""")
                .animation!!
                .activeFrameId
        val second =
            command(engine, """{"type":"add_frame","index":1,"duration_ms":135}""")
                .animation!!
                .activeFrameId
        command(engine, """{"type":"select","rect":{"left":16,"top":8,"right":48,"bottom":40}}""")
        command(engine, """{"type":"fill","x":16,"y":8,"color":[0,255,0,200],"tolerance":0}""")
        command(engine, """{"type":"select","rect":{"left":0,"top":0,"right":8,"bottom":8}}""")
        command(engine, """{"type":"fill","x":0,"y":0,"color":[255,255,0,100],"tolerance":0}""")
        command(engine, """{"type":"select","rect":null}""")
        val third =
            command(engine, """{"type":"add_frame","index":2,"duration_ms":20}""")
                .animation!!
                .activeFrameId
        command(
            engine,
            """{"type":"add_frame_tag","tag":{"name":"Walk","color":[30,120,210,255],"from_frame":$second,"to_frame":$third,"direction":"reverse","repeat":3}}""",
        )
        command(engine, """{"type":"select_frame","frame_id":$first}""")
        engine.call(EngineOperation.SAVE).also {
            assertContentEquals("PODOR\u000c".encodeToByteArray(), it.copyOf(6))
        }
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, byte ->
            value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
        }

    private suspend fun framePixels(bytes: ByteArray, frameId: Int): IntArray =
        probe(bytes) { engine ->
            val info = state(engine)
            val packet =
                engine.call(
                    EngineOperation.ANIMATION_FRAME,
                    """{"revision":${info.revision},"frame_id":$frameId,"transparent":true}"""
                        .encodeToByteArray(),
                )
            assertEquals(info.width, intAt(packet, 0))
            assertEquals(info.height, intAt(packet, 4))
            val tileSize = intAt(packet, 8)
            var offset = 16
            IntArray(info.width * info.height).also { pixels ->
                repeat(intAt(packet, 12)) {
                    val left = intAt(packet, offset) * tileSize
                    val top = intAt(packet, offset + 4) * tileSize
                    for (y in 0 until minOf(tileSize, info.height - top)) {
                        for (x in 0 until minOf(tileSize, info.width - left)) {
                            val position = offset + 8 + (y * tileSize + x) * 4
                            val alpha = packet[position + 3].toInt() and 255
                            fun channel(index: Int): Int =
                                if (alpha == 0) 0
                                else
                                    (((packet[position + index].toInt() and 255) * 255 +
                                            alpha / 2) / alpha)
                                        .coerceAtMost(255)
                            pixels[(top + y) * info.width + left + x] =
                                (alpha shl 24) or
                                    (channel(0) shl 16) or
                                    (channel(1) shl 8) or
                                    channel(2)
                        }
                    }
                    offset += 8 + tileSize * tileSize * 4
                }
                assertEquals(packet.size, offset)
                assertEquals(info, state(engine))
                assertContentEquals(bytes, engine.call(EngineOperation.SAVE))
            }
        }

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

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun control(label: String): SemanticsNode? =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    val finished =
                        withContext(Dispatchers.Main) {
                            render()
                            if (!allowError) assertNull(controller.error)
                            predicate() &&
                                !controller.busy &&
                                !controller.animationTransition &&
                                controller.previews.revision == controller.document.revision
                        }
                    if (finished) break
                    delay(5)
                }
            }

        suspend fun click(label: String, allowError: Boolean = false) {
            waitFor(allowError) {
                control(label)?.config?.contains(SemanticsProperties.Disabled) == false
            }
            withContext(Dispatchers.Main) {
                val point = assertNotNull(control(label), label).boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                render()
                scene.sendPointerEvent(PointerEventType.Release, point)
                render()
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                render()
            }
            repeat(20) {
                withContext(Dispatchers.Main) { render() }
                delay(2)
            }
        }

        suspend fun save(): ByteArray {
            val count = files.saves.get()
            click("Save project")
            waitFor { files.saves.get() == count + 1 && !controller.hasUnsavedChanges }
            return assertNotNull(files.saved.get()).copyOf()
        }

        suspend fun animationPanel() {
            if (withContext(Dispatchers.Main) { control("Show panel") != null }) click("Show panel")
            click("Animation")
        }

        suspend fun animationExport() {
            animationPanel()
            click("Export animation")
        }

        suspend fun confirm(): Exported {
            val count = files.animationExports.get()
            click("Export")
            waitFor { files.animationExports.get() == count + 1 }
            assertEquals(0, files.imageExports.get())
            return assertNotNull(files.exported.get())
        }
    }

    private suspend fun withSession(bytes: ByteArray, block: suspend Session.() -> Unit) {
        val appearance = StudioTheme.appearance
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
            session.waitFor {
                controller.hasCanvas && controller.document.animation?.frames?.size == 3
            }
            assertEquals(64, controller.document.width)
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

    private fun children(node: Node): Sequence<Node> = sequence {
        yield(node)
        repeat(node.childNodes.length) { yieldAll(children(node.childNodes.item(it))) }
    }

    @Test
    fun defaultTimelineGifExportsRealFramesWithThresholdAndRoundedDurationsWithoutEditingTheProject() =
        runBlocking {
            withSession(project()) {
                val source = save()
                val before = controller.document
                val frame = controller.frame
                val animation = assertNotNull(before.animation)
                val expected =
                    animation.frames.map { value ->
                        framePixels(source, value.id)
                            .map { pixel ->
                                if ((pixel ushr 24) < 128) 0 else pixel or (255 shl 24)
                            }
                            .toIntArray()
                    }
                assertTrue(expected[0].any { it != 0 })
                assertTrue(expected[1].any { it != 0 })
                assertTrue(expected[2].all { it == 0 })
                animationExport()
                assertNotNull(withContext(Dispatchers.Main) { control("GIF") })
                val exported = confirm()
                assertEquals(AnimationExportFormat.Gif, exported.format)
                assertContentEquals("GIF89a".encodeToByteArray(), exported.bytes.copyOf(6))
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                try {
                    ImageIO.createImageInputStream(ByteArrayInputStream(exported.bytes)).use { input
                        ->
                        reader.input = input
                        assertEquals(3, reader.getNumImages(true))
                        for (index in expected.indices) {
                            val image = reader.read(index)
                            assertEquals(64, image.width)
                            assertEquals(48, image.height)
                            assertContentEquals(
                                expected[index],
                                image.getRGB(0, 0, 64, 48, null, 0, 64),
                            )
                            val metadata = reader.getImageMetadata(index)
                            val gce =
                                children(metadata.getAsTree(metadata.nativeMetadataFormatName))
                                    .single { it.nodeName == "GraphicControlExtension" }
                            assertEquals(
                                listOf(70, 140, 20)[index],
                                gce.attributes.getNamedItem("delayTime").nodeValue.toInt() * 10,
                            )
                            assertEquals(
                                "TRUE",
                                gce.attributes.getNamedItem("transparentColorFlag").nodeValue,
                            )
                            assertEquals(
                                "restoreToBackgroundColor",
                                gce.attributes.getNamedItem("disposalMethod").nodeValue,
                            )
                        }
                    }
                } finally {
                    reader.dispose()
                }
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(source, save())
            }
        }

    @Test
    fun timelineAtlasZipKeepsTagOrderOriginalDurationAndPartialAlphaAgainstNativeFramePixels() =
        runBlocking {
            withSession(project()) {
                val source = save()
                val before = controller.document
                val frame = controller.frame
                val animation = assertNotNull(before.animation)
                val tag = animation.tags.single()
                withContext(Dispatchers.Main) { controller.animationTagId = tag.id }
                animationExport()
                click("PNG atlas")
                val exported = confirm()
                assertEquals(AnimationExportFormat.Atlas, exported.format)
                assertContentEquals(byteArrayOf(80, 75, 3, 4), exported.bytes.copyOf(4))
                val entries = mutableMapOf<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(exported.bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        assertNull(entries.put(entry.name, zip.readBytes()))
                        zip.closeEntry()
                    }
                }
                assertEquals(setOf("atlas.png", "atlas.json"), entries.keys)
                val metadata =
                    Json.parseToJsonElement(entries.getValue("atlas.json").decodeToString())
                        .jsonObject
                val image =
                    assertNotNull(ImageIO.read(ByteArrayInputStream(entries.getValue("atlas.png"))))
                assertEquals("podor_atlas", metadata.getValue("format").jsonPrimitive.content)
                assertEquals(image.width, metadata.getValue("width").jsonPrimitive.int)
                assertEquals(image.height, metadata.getValue("height").jsonPrimitive.int)
                assertEquals(
                    listOf(tag.fromFrame, tag.toFrame),
                    metadata.getValue("frames").jsonArray.map {
                        it.jsonObject.getValue("id").jsonPrimitive.int
                    },
                )
                for (entry in metadata.getValue("frames").jsonArray) {
                    val value = entry.jsonObject
                    val id = value.getValue("id").jsonPrimitive.int
                    val rect = value.getValue("rect").jsonObject
                    assertEquals(64, rect.getValue("width").jsonPrimitive.int)
                    assertEquals(48, rect.getValue("height").jsonPrimitive.int)
                    assertEquals(
                        animation.frame(id)!!.durationMs,
                        value.getValue("durationMs").jsonPrimitive.int,
                    )
                    assertContentEquals(
                        framePixels(source, id),
                        image.getRGB(
                            rect.getValue("x").jsonPrimitive.int,
                            rect.getValue("y").jsonPrimitive.int,
                            64,
                            48,
                            null,
                            0,
                            64,
                        ),
                    )
                }
                val playback = metadata.getValue("playback").jsonObject
                assertEquals(
                    listOf(tag.toFrame, tag.fromFrame),
                    playback.getValue("frameIds").jsonArray.map { it.jsonPrimitive.int },
                )
                assertEquals("reverse", playback.getValue("direction").jsonPrimitive.content)
                assertEquals(3, playback.getValue("repeat").jsonPrimitive.int)
                val encodedTag = metadata.getValue("tag").jsonObject
                assertEquals(tag.id, encodedTag.getValue("id").jsonPrimitive.int)
                assertEquals(tag.name, encodedTag.getValue("name").jsonPrimitive.content)
                assertEquals(
                    tag.color,
                    encodedTag.getValue("color").jsonArray.map { it.jsonPrimitive.int },
                )
                assertTrue(
                    image.getRGB(0, 0, image.width, image.height, null, 0, image.width).any {
                        (it ushr 24) in 1..254
                    }
                )
                assertEquals(before, controller.document)
                assertSame(frame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                assertContentEquals(source, save())
            }
        }

    @Test
    fun unavailableNativeCapabilityHidesTheTimelineExportAndRejectsProgrammaticExport() =
        runBlocking {
            withSession(project()) {
                val before = controller.document
                animationPanel()
                withContext(Dispatchers.Main) {
                    val field = controller.javaClass.getDeclaredField("document\$delegate")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val documentState = field.get(controller) as MutableState<DocumentInfo>
                    try {
                        documentState.value = before.copy(animationExport = null)
                        render()
                        assertNull(control("Export animation"))
                        assertTrue(controller.animationExportFormats.isEmpty())
                        controller.exportAnimation(AnimationExportOptions())
                        assertEquals(0, files.animationExports.get())
                        assertEquals(0, files.imageExports.get())
                        assertEquals(before.copy(animationExport = null), controller.document)
                    } finally {
                        documentState.value = before
                        render()
                    }
                }
            }
        }

    @Test
    fun staleQueuedExportWritesNothingAndKeepsTheRealDurationEditAsTheOnlyUndoStep() = runBlocking {
        withSession(project()) {
            val source = save()
            val before = controller.document
            val first = assertNotNull(before.animation).activeFrameId
            withContext(Dispatchers.Main) {
                controller.command("set_frame_duration") {
                    put("revision", before.revision)
                    put("frame_id", first)
                    put("duration_ms", 80)
                }
                controller.exportAnimation(AnimationExportOptions())
            }
            waitFor(allowError = true) {
                controller.error != null && controller.document.revision == before.revision + 1
            }
            assertTrue(assertNotNull(controller.error).contains("动画已改变"))
            assertEquals(80, controller.document.animation!!.frame(first)!!.durationMs)
            assertEquals(first, controller.document.animation!!.activeFrameId)
            assertTrue(controller.document.canUndo)
            assertFalse(controller.document.canRedo)
            assertEquals(0, files.animationExports.get())
            assertEquals(0, files.imageExports.get())
            withContext(Dispatchers.Main) {
                controller.dismissError()
                controller.command("undo")
            }
            waitFor { controller.document.animation!!.frame(first)!!.durationMs == 70 }
            assertFalse(controller.document.canUndo)
            assertTrue(controller.document.canRedo)
            assertContentEquals(source, save())
        }
    }

    @Test
    fun exactAlphaFailureFromTheRealGifDialogDoesNotWriteAnyFileOrModifyTheSource() = runBlocking {
        withSession(project()) {
            val source = save()
            val before = controller.document
            val frame = controller.frame
            animationExport()
            click("Advanced settings")
            click("Alpha threshold")
            click("Exact alpha")
            click("Export")
            waitFor(allowError = true) { controller.error != null }
            assertTrue(assertNotNull(controller.error).contains("GIF 不能精确保留半透明像素"))
            assertEquals(0, files.animationExports.get())
            assertEquals(0, files.imageExports.get())
            assertEquals(before, controller.document)
            assertSame(frame, controller.frame)
            assertFalse(controller.hasUnsavedChanges)
            click("OK", allowError = true)
            waitFor { control("OK") == null && control("Export") == null }
            assertContentEquals(source, save())
        }
    }
}
