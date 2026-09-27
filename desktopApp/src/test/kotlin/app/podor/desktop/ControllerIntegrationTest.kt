package app.podor.desktop

import app.podor.desktop.engine.NativeLoader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ControllerIntegrationTest {
    @Test
    fun imageOpeningRefreshesCanvasAndFailedDecodePreservesTheWork() =
        runBlocking<Unit> {
            NativeLoader.load()
            val source = createNativeEngine(24, 16)
            val images =
                try {
                    source.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[60,120,180,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    listOf("jpeg", "webp").map { format ->
                        source.call(
                            EngineOperation.EXPORT_IMAGE,
                            """{"format":"$format","transparent":false,"quality":100}"""
                                .encodeToByteArray(),
                        )
                    }
                } finally {
                    source.close()
                }
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                for (bytes in images) {
                    files.opened = bytes
                    val previous =
                        withContext(Dispatchers.Main) {
                            controller.viewport = Viewport(2f, Offset(40f, 30f))
                            val previous = controller.document.revision
                            controller.file(StudioController.FileAction.Open)
                            previous
                        }
                    awaitState {
                        controller.document.revision > previous &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                    withContext(Dispatchers.Main) {
                        assertEquals(24, controller.document.width)
                        assertEquals(16, controller.document.height)
                        assertEquals(Viewport(), controller.viewport)
                        assertFalse(controller.document.canUndo)
                        for (color in
                            listOf(
                                controller.frame.tiles.values.first().image.toPixelMap()[12, 8],
                                controller.previews.images.getValue(1).toPixelMap()[48, 48],
                            )) {
                            assertEquals(60 / 255f, color.red, 0.015f)
                            assertEquals(120 / 255f, color.green, 0.015f)
                            assertEquals(180 / 255f, color.blue, 0.015f)
                        }
                    }
                }
                withContext(Dispatchers.Main) { controller.fill(Offset(12f, 8f)) }
                awaitState {
                    controller.document.canUndo &&
                        controller.previews.revision == controller.document.revision
                }
                val before = withContext(Dispatchers.Main) { controller.document }
                val frame = withContext(Dispatchers.Main) { controller.frame }
                files.opened = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 0)
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.pendingNavigation != null }
                withContext(Dispatchers.Main) { controller.resolveUnsaved(UnsavedChoice.Discard) }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertEquals(before, controller.document)
                    assertSame(frame, controller.frame)
                    controller.dismissError()
                    controller.command("undo")
                }
                awaitState { !controller.document.canUndo }
                assertTrue(withContext(Dispatchers.Main) { controller.shutdown() })
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }

    @Test
    fun layerBlendChangesReachCanvasPreviewsHistoryAndSavedFile() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(32, 32)
            val project =
                try {
                    for (command in
                        listOf(
                            """{"type":"fill","x":0,"y":0,"color":[64,128,192,255],"tolerance":0}""",
                            """{"type":"add_layer"}""",
                            """{"type":"fill","x":0,"y":0,"color":[192,64,128,255],"tolerance":0}""",
                        )) engine.call(EngineOperation.COMMAND, command.encodeToByteArray())
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val files = Files().apply { opened = project }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            suspend fun assertColor(expected: List<Int>) =
                withContext(Dispatchers.Main) {
                    for (color in
                        listOf(
                            controller.frame.tiles.values.first().image.toPixelMap()[16, 16],
                            controller.previews.images.getValue(0).toPixelMap()[48, 48],
                        )) {
                        for ((actual, value) in
                            listOf(color.red, color.green, color.blue).zip(expected)) assertEquals(
                            value / 255f,
                            actual,
                            0.005f,
                        )
                        assertEquals(1f, color.alpha)
                    }
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.document.width == 32 && !controller.busy }
                awaitState { controller.ready }
                val expected =
                    listOf(
                        listOf(192, 64, 128),
                        listOf(48, 32, 96),
                        listOf(208, 160, 224),
                        listOf(96, 65, 192),
                        listOf(96, 96, 192),
                        listOf(64, 64, 128),
                        listOf(192, 128, 192),
                        listOf(128, 64, 64),
                    )
                for ((mode, color) in LayerBlendMode.entries.zip(expected)) {
                    val revision = withContext(Dispatchers.Main) { controller.document.revision }
                    withContext(Dispatchers.Main) { controller.setLayerBlend(2, mode) }
                    awaitState {
                        controller.document.revision > revision &&
                            controller.document.layers.last().blend == mode &&
                            controller.previews.revision == controller.document.revision
                    }
                    assertColor(color)
                }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState {
                    controller.document.layers.last().blend == LayerBlendMode.Lighten &&
                        controller.previews.revision == controller.document.revision
                }
                assertColor(expected[6])
                withContext(Dispatchers.Main) { controller.command("redo") }
                awaitState {
                    controller.document.layers.last().blend == LayerBlendMode.Difference &&
                        controller.previews.revision == controller.document.revision
                }
                assertColor(expected[7])
                assertNull(withContext(Dispatchers.Main) { controller.error })
                saveManually(controller, files)
                assertTrue(withContext(Dispatchers.Main) { controller.shutdown() })
                val restored = createNativeEngine(1, 1)
                try {
                    restored.call(EngineOperation.LOAD, assertNotNull(files.saved))
                    val color =
                        restored
                            .call(
                                EngineOperation.COMMAND,
                                """{"type":"pick","x":16,"y":16}""".encodeToByteArray(),
                            )
                            .decodeToString()
                    assertEquals("""{"color":[128,64,64]}""", color)
                } finally {
                    restored.close()
                }
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }

    @Test
    fun exportsSelectedFormatsAndRefreshesLayerPreviews() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.command("new") {
                        put("width", 64)
                        put("height", 64)
                    }
                }
                awaitState { controller.document.width == 64 }
                withContext(Dispatchers.Main) { controller.fill(Offset(32f, 32f)) }
                awaitState {
                    controller.document.canUndo &&
                        controller.previews.revision == controller.document.revision
                }
                val image = withContext(Dispatchers.Main) { controller.previews.images.getValue(1) }
                assertEquals(0f, image.toPixelMap()[48, 48].red, 0.01f)
                for (format in ExportFormat.entries) {
                    withContext(Dispatchers.Main) {
                        controller.export(
                            ExportOptions(format, transparent = format.supportsTransparency)
                        )
                    }
                    awaitState { files.exports.any { it.first == format } && !controller.busy }
                }
                assertContentEquals(
                    byteArrayOf(137.toByte(), 80, 78, 71),
                    files.exports[0].second.copyOfRange(0, 4),
                )
                assertContentEquals(
                    byteArrayOf(255.toByte(), 216.toByte()),
                    files.exports[1].second.copyOfRange(0, 2),
                )
                assertEquals("WEBP", files.exports[2].second.copyOfRange(8, 12).decodeToString())
                val ora = files.exports.single { it.first == ExportFormat.Ora }.second
                val entries = mutableMapOf<String, ByteArray>()
                java.util.zip.ZipInputStream(ora.inputStream()).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        entries[entry.name] = archive.readBytes()
                    }
                }
                assertEquals("image/openraster", entries.getValue("mimetype").decodeToString())
                val stack = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(entries.getValue("stack.xml").inputStream())
                assertEquals("64", stack.documentElement.getAttribute("w"))
                assertEquals("64", stack.documentElement.getAttribute("h"))
                assertEquals(1, stack.getElementsByTagName("layer").length)
                val merged = javax.imageio.ImageIO.read(entries.getValue("mergedimage.png").inputStream())
                assertEquals(0xFF000000.toInt(), merged.getRGB(32, 32))
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState {
                    !controller.document.canUndo &&
                        controller.previews.revision == controller.document.revision
                }
                val empty = withContext(Dispatchers.Main) { controller.previews.images.getValue(1) }
                assertEquals(0f, empty.toPixelMap()[48, 48].alpha)
                assertNull(withContext(Dispatchers.Main) { controller.error })
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun selectionFillAdjustmentAndUndoReachNativeEngine() = runBlocking<Unit> {
        NativeLoader.load()
        val files = Files()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
            }
        try {
            awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.select(Offset(10f, 10f), Offset(30f, 30f)) }
            awaitState { controller.document.selection?.right == 30 }
            withContext(Dispatchers.Main) { controller.fill(Offset(15f, 15f)) }
            awaitState { controller.document.revision == 1L }
            withContext(Dispatchers.Main) {
                controller.command("tone") {
                    putJsonObject("settings") {
                        put("brightness", 0)
                        put("contrast", 0)
                        put("saturation", -1)
                    }
                }
            }
            awaitState { controller.document.revision == 2L }
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState { controller.document.revision == 3L }
            assertNull(withContext(Dispatchers.Main) { controller.error })
            saveManually(controller, files)
                assertTrue(withContext(Dispatchers.Main) { controller.shutdown() })
            val engine = createNativeEngine(1, 1)
            try {
                engine.call(EngineOperation.LOAD, assertNotNull(files.saved))
                fun pick(x: Int) =
                    engine
                        .call(
                            EngineOperation.COMMAND,
                            """{"type":"pick","x":$x,"y":15}""".encodeToByteArray(),
                        )
                        .decodeToString()
                assertEquals("""{"color":[0,0,0]}""", pick(15))
                assertEquals("""{"color":[255,255,255]}""", pick(35))
            } finally {
                engine.close()
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            scope.cancel()
        }
    }

    private class Files : ProjectFiles {
        override val exportFormats = ExportFormat.entries
        val exports = java.util.concurrent.CopyOnWriteArrayList<Pair<ExportFormat, ByteArray>>()

        override suspend fun export(bytes: ByteArray, format: ExportFormat): Boolean {
            check(!javax.swing.SwingUtilities.isEventDispatchThread())
            exports += format to bytes
            return true
        }

        var settings: ByteArray? = null
        var brushPack: ByteArray? = null
        var exportedPack: ByteArray? = null

        override suspend fun readPreferences() = settings

        override suspend fun writePreferences(bytes: ByteArray) {
            settings = bytes
        }

        override suspend fun openBrushPack() = brushPack

        override suspend fun saveBrushPack(bytes: ByteArray): Boolean {
            exportedPack = bytes
            return true
        }

        var saved: ByteArray? = null
        var failWrites = false

        var opened: ByteArray? = null

        override suspend fun open(): ByteArray? {
            check(!javax.swing.SwingUtilities.isEventDispatchThread())
            return opened
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            check(!failWrites) { "模拟磁盘写入失败" }
            saved = bytes
            return true
        }

    }

    @Test
    fun pluginAndPreferencesSurviveRestartAndInvalidImportDoesNotReplaceThem() = runBlocking<Unit> {
        NativeLoader.load()
        val files =
            Files().apply {
                brushPack =
                    Json.encodeToString(
                            BrushPack("artist", "Artist", brushes = listOf(BrushPreset.Ink))
                        )
                        .encodeToByteArray()
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(10) }
        try {
            awaitState { controller.ready }
            withContext(Dispatchers.Main) {
                controller.file(StudioController.FileAction.ImportBrushes)
            }
            awaitState { controller.preferences.plugins.size == 1 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences
                        .copy(language = Language.English)
                        .assign(ShortcutAction.Brush, Shortcut("P"))
                )
                controller.brush = controller.brush.copy(
                    preset = controller.brush.preset.copy(stabilization = 0.7f)
                )
                controller.saveBrush("My ink")
            }
            assertTrue(withContext(Dispatchers.Main) { controller.shutdown() })
            controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            awaitState { controller.ready }
            assertEquals(
                Language.English,
                withContext(Dispatchers.Main) { controller.preferences.language },
            )
            assertEquals(
                Shortcut("P"),
                withContext(Dispatchers.Main) {
                    controller.preferences.shortcut(ShortcutAction.Brush)
                },
            )
            assertEquals(BrushPreset.entries.size + 2, withContext(Dispatchers.Main) { controller.brushes.size })
            assertEquals(0.7f, withContext(Dispatchers.Main) { controller.preferences.brushes.single().stabilization })
            withContext(Dispatchers.Main) {
                controller.file(StudioController.FileAction.ExportBrushes)
            }
            awaitState { files.exportedPack != null && !controller.busy }
            assertEquals("My ink", BrushPack.parse(files.exportedPack!!).brushes.single().label)
            assertEquals(0.7f, BrushPack.parse(files.exportedPack!!).brushes.single().stabilization)
            files.brushPack = "{}".encodeToByteArray()
            withContext(Dispatchers.Main) {
                controller.file(StudioController.FileAction.ImportBrushes)
            }
            awaitState { controller.error != null }
            assertEquals(1, withContext(Dispatchers.Main) { controller.preferences.plugins.size })
            withContext(Dispatchers.Main) { controller.shutdown() }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            scope.cancel()
        }
    }

    @Test
    fun stabilizationReachesTheNativeEngineAndManualSaveFinishesItsTail() =
        runBlocking<Unit> {
            NativeLoader.load()
            val files = Files()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.brush =
                        controller.brush.copy(
                            size = 3f,
                            preset = controller.brush.preset.copy(stabilization = 1f),
                        )
                    controller.begin(Offset(20.5f, 64.5f), 1f)
                    controller.points(listOf(Triple(210.5f, 64.5f, 1f)))
                }
                awaitState { controller.frame.tiles.size == 2 }
                withContext(Dispatchers.Main) {
                    val pixels =
                        controller.frame.tiles.values.single { it.x == 1 }.image.toPixelMap()
                    assertEquals(1f, pixels[210 - 128, 64].green)
                    assertTrue(pixels[160 - 128, 64].green < 0.3f)
                }
                saveManually(controller, files)
                assertTrue(withContext(Dispatchers.Main) { controller.shutdown() })
                val restored = createNativeEngine(1, 1)
                try {
                    restored.call(EngineOperation.LOAD, assertNotNull(files.saved))
                    assertEquals(
                        """{"color":[0,0,0]}""",
                        restored
                            .call(
                                EngineOperation.COMMAND,
                                """{"type":"pick","x":210,"y":64}""".encodeToByteArray(),
                            )
                            .decodeToString(),
                    )
                } finally {
                    restored.close()
                }
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }

    private suspend fun saveManually(controller: StudioController, files: Files) {
        val previous = files.saved
        withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { files.saved !== previous && !controller.busy }) delay(10)
        }
    }
}
