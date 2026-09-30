package app.podor.desktop

import app.podor.desktop.data.DesktopFiles
import app.podor.desktop.data.DesktopStorage
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.AnimationDirection
import app.podor.domain.DocumentInfo
import app.podor.domain.ProjectReference
import app.podor.engine.EngineOperation
import app.podor.engine.NativeEngine
import app.podor.engine.createNativeEngine
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ProjectSuffixFilesTest {
    private data class Source(
        val bytes: ByteArray,
        val document: DocumentInfo,
        val pixels: List<IntArray>,
    )

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun command(engine: NativeEngine, value: String): DocumentInfo {
        val info = state(engine)
        val source = Json.parseToJsonElement(value).jsonObject
        val request = buildJsonObject {
            source.forEach { (key, entry) -> put(key, entry) }
            put("revision", info.revision)
            info.animation
                ?.takeIf { source.getValue("type").jsonPrimitive.content != "select_frame" }
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

    private suspend fun <T> native(bytes: ByteArray? = null, block: (NativeEngine) -> T): T =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            NativeLoader.load()
            val engine = createNativeEngine(32, 24)
            try {
                if (bytes != null) engine.call(EngineOperation.LOAD, bytes)
                block(engine)
            } finally {
                engine.close()
            }
        }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        assertTrue(offset >= 0 && offset + 4 <= bytes.size)
        return (0..3).fold(0) { value, byte ->
            value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
        }
    }

    private fun framePixels(engine: NativeEngine, frameId: Int): IntArray {
        val before = state(engine)
        val saved = engine.call(EngineOperation.SAVE)
        val packet =
            engine.call(
                EngineOperation.ANIMATION_FRAME,
                """{"revision":${before.revision},"frame_id":$frameId,"transparent":true}"""
                    .encodeToByteArray(),
            )
        assertEquals(32, intAt(packet, 0))
        assertEquals(24, intAt(packet, 4))
        val size = intAt(packet, 8)
        assertTrue(size > 0)
        var offset = 16
        return IntArray(32 * 24).also { pixels ->
            repeat(intAt(packet, 12)) {
                val left = intAt(packet, offset) * size
                val top = intAt(packet, offset + 4) * size
                assertTrue(left in 0 until 32 && top in 0 until 24)
                for (row in 0 until minOf(size, 24 - top)) for (column in
                    0 until minOf(size, 32 - left)) {
                    val position = offset + 8 + (row * size + column) * 4
                    val red = packet[position].toInt() and 255
                    val green = packet[position + 1].toInt() and 255
                    val blue = packet[position + 2].toInt() and 255
                    val alpha = packet[position + 3].toInt() and 255
                    assertTrue(alpha == 0 || alpha == 255)
                    pixels[(top + row) * 32 + left + column] =
                        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
                offset += 8 + size * size * 4
            }
            assertEquals(packet.size, offset)
            assertEquals(before, state(engine))
            assertContentEquals(saved, engine.call(EngineOperation.SAVE))
        }
    }

    private suspend fun source(): Source = native { engine ->
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String) {
            command(
                engine,
                """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}""",
            )
            command(engine, """{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""")
            command(engine, """{"type":"select","rect":null}""")
        }
        command(engine, """{"type":"set_layer","id":1,"name":"Base","visible":true,"opacity":1}""")
        fill(0, 0, 8, 24, "[240,40,20,255]")
        val overlay = command(engine, """{"type":"add_layer"}""").active
        command(
            engine,
            """{"type":"set_layer","id":$overlay,"name":"Overlay","visible":true,"opacity":1}""",
        )
        fill(8, 8, 16, 16, "[20,160,200,255]")
        val first =
            command(engine, """{"type":"enable_animation","duration_ms":90}""")
                .animation!!
                .activeFrameId
        val second =
            command(
                    engine,
                    """{"type":"duplicate_frame","frame_id":$first,"index":1,"linked":true}""",
                )
                .animation!!
                .activeFrameId
        command(engine, """{"type":"set_frame_duration","frame_id":$second,"duration_ms":180}""")
        val third =
            command(engine, """{"type":"add_frame","index":2,"duration_ms":70}""")
                .animation!!
                .activeFrameId
        command(engine, """{"type":"select_layer","id":1}""")
        fill(16, 4, 24, 12, "[40,180,70,255]")
        command(
            engine,
            """{"type":"add_frame_tag","tag":{"name":"Walk","color":[30,120,210,255],"from_frame":$second,"to_frame":$third,"direction":"reverse","repeat":3}}""",
        )
        command(engine, """{"type":"select_frame","frame_id":$first}""")
        command(engine, """{"type":"select_layer","id":1}""")
        val info = state(engine)
        val animation = assertNotNull(info.animation)
        assertEquals(listOf(90, 180, 70), animation.frames.map { it.durationMs })
        assertEquals(listOf(2, 2, 1), animation.frames.map { it.cels.size })
        assertEquals(animation.frames[0].cels, animation.frames[1].cels)
        assertEquals(setOf("Base", "Overlay"), info.layers.map { it.name }.toSet())
        val tag = animation.tags.single()
        assertEquals("Walk", tag.name)
        assertEquals(second, tag.fromFrame)
        assertEquals(third, tag.toFrame)
        assertEquals(AnimationDirection.Reverse, tag.direction)
        assertEquals(3, tag.repeat)
        assertEquals(listOf(30, 120, 210, 255), tag.color)
        val firstPixels =
            IntArray(32 * 24).also { pixels ->
                for (row in 0 until 24) for (column in 0 until 8) pixels[row * 32 + column] =
                    0xfff02814.toInt()
                for (row in 8 until 16) for (column in 8 until 16) pixels[row * 32 + column] =
                    0xff14a0c8.toInt()
            }
        val thirdPixels =
            IntArray(32 * 24).also { pixels ->
                for (row in 4 until 12) for (column in 16 until 24) pixels[row * 32 + column] =
                    0xff28b446.toInt()
            }
        val expected = listOf(firstPixels, firstPixels.copyOf(), thirdPixels)
        animation.frames.forEachIndexed { index, frame ->
            assertContentEquals(
                expected[index],
                framePixels(engine, frame.id),
                "Source frame $index",
            )
        }
        Source(engine.call(EngineOperation.SAVE), info, expected).also {
            assertContentEquals("PODOR\u000c".encodeToByteArray(), it.bytes.copyOf(6))
        }
    }

    private suspend fun assertProject(bytes: ByteArray, source: Source) =
        native(bytes) { engine ->
            val restored = state(engine)
            assertEquals(32, restored.width)
            assertEquals(24, restored.height)
            assertEquals(source.document.active, restored.active)
            assertEquals(source.document.layers, restored.layers)
            assertEquals(source.document.animation, restored.animation)
            assertFalse(restored.canUndo)
            assertFalse(restored.canRedo)
            restored.animation!!.frames.forEachIndexed { index, frame ->
                assertContentEquals(
                    source.pixels[index],
                    framePixels(engine, frame.id),
                    "Reloaded frame $index",
                )
            }
            assertContentEquals(source.bytes, engine.call(EngineOperation.SAVE))
        }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun directory(block: suspend (Path) -> Unit) {
        val root = Path.of("build", "project-suffix-tests").toAbsolutePath().normalize()
        val directory =
            withContext(Dispatchers.IO) {
                assertFalse(EventQueue.isDispatchThread())
                Files.createDirectories(root)
                Files.createTempDirectory(root, "project-")
            }
        try {
            block(directory)
        } finally {
            withContext(Dispatchers.IO) {
                assertFalse(EventQueue.isDispatchThread())
                check(directory.toRealPath().startsWith(root.toRealPath()))
                directory.deleteRecursively()
            }
        }
    }

    private fun files(directory: Path) =
        DesktopFiles(DesktopStorage(directory.resolve("preferences"))) {
            error("An already selected project path must not open a file dialog")
        }

    @Test
    fun regularPodSaveAndOpenKeepTheExactDestinationAndCompleteAnimatedProject() = runBlocking {
        val source = source()
        directory { directory ->
            val store = files(directory)
            for ((fileName, title) in
                listOf(
                    "Study lower.final.pod" to "Study lower.final",
                    "画作 upper.final.POD" to "画作 upper.final",
                )) {
                val path = directory.resolve(fileName)
                withContext(Dispatchers.IO) {
                    assertFalse(EventQueue.isDispatchThread())
                    Files.write(path, byteArrayOf(9, 7, 3))
                }
                val before = source.bytes.copyOf()
                val saved =
                    assertNotNull(
                        store.saveDocument(
                            source.bytes,
                            ProjectReference(path.toString(), "Wrong title.podor"),
                            saveAs = false,
                        )
                    )
                assertEquals(
                    ProjectReference(
                        path.toAbsolutePath().normalize().toString(),
                        title,
                        editable = true,
                    ),
                    saved,
                )
                withContext(Dispatchers.IO) {
                    assertContentEquals(source.bytes, Files.readAllBytes(path))
                    assertFalse(Files.exists(directory.resolve("$fileName.pod")))
                }
                val opened = assertNotNull(files(directory).openDocument(saved))
                assertEquals(saved, opened.reference)
                assertContentEquals(source.bytes, opened.bytes)
                assertContentEquals(before, source.bytes)
                assertProject(opened.bytes, source)
            }
            withContext(Dispatchers.IO) {
                Files.list(directory).use { assertEquals(2L, it.count()) }
            }
        }
    }

    @Test
    fun legacyPodorFilesRemainReadableAndTheirReturnedReferencesRequireConversion() = runBlocking {
        val source = source()
        directory { directory ->
            for ((fileName, title) in listOf("Legacy.podor" to "Legacy", "旧画作.PODOR" to "旧画作")) {
                val path = directory.resolve(fileName)
                withContext(Dispatchers.IO) {
                    assertFalse(EventQueue.isDispatchThread())
                    Files.write(path, source.bytes)
                }
                val opened =
                    assertNotNull(
                        files(directory)
                            .openDocument(
                                ProjectReference(path.toString(), "Wrong title", editable = true)
                            )
                    )
                assertEquals(
                    ProjectReference(
                        path.toAbsolutePath().normalize().toString(),
                        title,
                        editable = false,
                    ),
                    opened.reference,
                )
                assertContentEquals(source.bytes, opened.bytes)
                assertProject(opened.bytes, source)
                withContext(Dispatchers.IO) {
                    assertContentEquals(source.bytes, Files.readAllBytes(path))
                    assertFalse(Files.exists(directory.resolve("$title.pod")))
                }
            }
            withContext(Dispatchers.IO) {
                Files.list(directory).use { assertEquals(2L, it.count()) }
            }
        }
    }
}
