package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.data.AsePaletteCodec
import app.podor.data.ProjectFiles
import app.podor.data.TextPaletteCodec
import app.podor.data.TextPaletteFile
import app.podor.data.TextPaletteFormat
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class TextPaletteFileWorkflowRenderingTest {
    private val flowerFile =
        "GIMP Palette\nName: Flowers 🖌\nColumns: 3\n#\n255 0 0 Rose\n255 0 0 Ruby\n0 128 255 Sky\n"
            .encodeToByteArray()
    private val flowerPalette =
        AsePalette(
            listOf(
                AseSwatch("Rose", 0xFFFF0000),
                AseSwatch("Ruby", 0xFFFF0000),
                AseSwatch("Sky", 0xFF0080FF),
            )
        )
    private val flowerMetadata = PaletteFileMetadata("Flowers 🖌", 3)

    private data class PaletteWrite(val bytes: ByteArray, val format: PaletteFileFormat)

    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        private val project = project.copyOf()
        val preferenceBytes =
            AtomicReference(
                Json.encodeToString(
                        Preferences(
                            language = Language.English,
                            appearance = Appearance.Light,
                            palette = emptyList(),
                        )
                    )
                    .encodeToByteArray()
            )
        val preferenceWrites = AtomicInteger()
        val projectSaves = AtomicInteger()
        val paletteOpens = AtomicInteger()
        val paletteExports = AtomicInteger()
        val paletteInput = AtomicReference<ByteArray>()
        val paletteOutput = AtomicReference<PaletteWrite>()
        override val supportsPaletteFiles = true
        override val paletteFormats = PaletteFileFormat.entries

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return project.copyOf()
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            projectSaves.incrementAndGet()
            return true
        }

        override suspend fun readPreferences(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return preferenceBytes.get().copyOf()
        }

        override suspend fun writePreferences(bytes: ByteArray) {
            assertFalse(EventQueue.isDispatchThread())
            preferenceBytes.set(bytes.copyOf())
            preferenceWrites.incrementAndGet()
        }

        override suspend fun openPalette(): ByteArray? {
            assertFalse(EventQueue.isDispatchThread())
            paletteOpens.incrementAndGet()
            return paletteInput.get()?.copyOf()
        }

        override suspend fun savePalette(bytes: ByteArray, format: PaletteFileFormat): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            paletteOutput.set(PaletteWrite(bytes.copyOf(), format))
            paletteExports.incrementAndGet()
            return true
        }
    }

    private data class Artwork(
        val document: DocumentInfo,
        val frame: RenderFrame,
        val pixels: IntArray,
        val unsaved: Boolean,
    )

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        if (!allowError) assertNull(controller.error)
                    }
                    delay(5)
                    if (withContext(Dispatchers.Main) { predicate() && !controller.busy }) break
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
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) ==
                true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun hasLabel(label: String) = nodes().any { matches(it, label) }

        private fun control(label: String) =
            nodes()
                .filter { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        !node.boundsInWindow.isEmpty &&
                        descendants(node).any { matches(it, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing rendered palette control: $label")

        suspend fun click(label: String) {
            settle()
            withContext(Dispatchers.Main) {
                val node = control(label)
                assertFalse(node.config.contains(SemanticsProperties.Disabled), label)
                scene.sendPointerEvent(PointerEventType.Press, node.boundsInWindow.center)
                scene.sendPointerEvent(PointerEventType.Release, node.boundsInWindow.center)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        suspend fun importPalette(bytes: ByteArray, allowError: Boolean = false) {
            files.paletteInput.set(bytes.copyOf())
            val opens = files.paletteOpens.get()
            click("Palette files")
            click("Import palette")
            waitFor(allowError) { files.paletteOpens.get() == opens + 1 }
        }

        suspend fun chooseExport(format: PaletteFileFormat) {
            click("Palette files")
            click("Export palette · ${format.label}")
        }

        suspend fun awaitExport(count: Int, format: PaletteFileFormat): TextPaletteFile {
            waitFor { files.paletteExports.get() == count }
            val write = assertNotNull(files.paletteOutput.get())
            assertEquals(format, write.format)
            return TextPaletteCodec.decode(write.bytes).also {
                assertTrue(it.palette.swatches.all { swatch -> swatch.color ushr 24 == 255L })
            }
        }

        private fun pixels(): IntArray {
            val image = controller.frame.tiles.values.single().image
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }

        fun artwork() =
            Artwork(controller.document, controller.frame, pixels(), controller.hasUnsavedChanges)

        fun assertArtworkUnchanged(before: Artwork) {
            assertEquals(before.document, controller.document)
            assertSame(before.frame, controller.frame)
            assertContentEquals(before.pixels, pixels())
            assertEquals(before.unsaved, controller.hasUnsavedChanges)
            assertEquals(0, files.projectSaves.get())
        }

        fun assertPreferencesUnchanged(before: Preferences, persisted: ByteArray, writes: Int) {
            assertEquals(before, controller.preferences)
            assertContentEquals(persisted, files.preferenceBytes.get())
            assertEquals(writes, files.preferenceWrites.get())
        }
    }

    private fun createFiles(): MemoryFiles {
        NativeLoader.load()
        val native = createNativeEngine(32, 24)
        return try {
            native.call(
                EngineOperation.COMMAND,
                """{"type":"fill","x":0,"y":0,"color":[31,87,128,192],"tolerance":0}"""
                    .encodeToByteArray(),
            )
            MemoryFiles(native.call(EngineOperation.SAVE))
        } finally {
            native.close()
        }
    }

    private suspend fun withSession(files: MemoryFiles, block: suspend Session.() -> Unit) {
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(600, 360) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Surface(color = StudioTheme.panel) {
                            Box(Modifier.fillMaxSize().padding(24.dp)) {
                                PersonalPalette(controller, controller.brush.color) {
                                    controller.brush = controller.brush.copy(color = it)
                                }
                            }
                        }
                    }
                }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 32 }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
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
    fun gplPreservesNamesHeadersAndDuplicateSlotsWhilePalRequiresExplicitColorOnlyExport() =
        runBlocking {
            val files = createFiles()
            withSession(files) {
                val artwork = withContext(Dispatchers.Main) { artwork() }
                importPalette(flowerFile)
                waitFor { files.preferenceWrites.get() == 1 }
                val preferences = withContext(Dispatchers.Main) { controller.preferences }
                val persisted = files.preferenceBytes.get().copyOf()
                assertEquals(flowerPalette, preferences.asePalette)
                assertEquals(flowerMetadata, preferences.paletteFileMetadata)
                assertEquals(listOf(0xFFFF0000L, 0xFF0080FFL), preferences.palette)
                chooseExport(PaletteFileFormat.Gpl)
                val gpl = awaitExport(1, PaletteFileFormat.Gpl)
                assertEquals(
                    TextPaletteFile(flowerPalette, TextPaletteFormat.Gpl, "Flowers 🖌", 3),
                    gpl,
                )
                withContext(Dispatchers.Main) { assertFalse(hasLabel("Export colors")) }
                chooseExport(PaletteFileFormat.JascPal)
                withContext(Dispatchers.Main) { assertTrue(hasLabel("Export colors")) }
                assertEquals(1, files.paletteExports.get())
                click("Cancel")
                assertEquals(1, files.paletteExports.get())
                withContext(Dispatchers.Main) {
                    assertFalse(hasLabel("Export colors"))
                    assertPreferencesUnchanged(preferences, persisted, 1)
                    assertArtworkUnchanged(artwork)
                }
                chooseExport(PaletteFileFormat.JascPal)
                click("Export colors")
                val pal = awaitExport(2, PaletteFileFormat.JascPal)
                assertEquals(TextPaletteFormat.JascPal, pal.format)
                assertEquals(
                    flowerPalette.swatches.map { it.color },
                    pal.palette.swatches.map { it.color },
                )
                assertTrue(pal.palette.swatches.all { it.name.isEmpty() && it.path.isEmpty() })
                assertEquals("", pal.name)
                assertEquals(0, pal.columns)
                withContext(Dispatchers.Main) {
                    assertPreferencesUnchanged(preferences, persisted, 1)
                    assertArtworkUnchanged(artwork)
                }
            }
        }

    @Test
    fun groupedSpotAndGlobalAseSwatchesNeedGplConfirmationWithoutFlatteningStoredMetadata() =
        runBlocking {
            val grouped =
                AsePalette(
                    listOf(
                        AseSwatch("Petal", 0xFFCC1166, AseSwatchType.Spot, listOf("Botanical")),
                        AseSwatch("Stem", 0xFF116644, AseSwatchType.Global, listOf("Botanical")),
                    ),
                    listOf(
                        AseGroup(listOf("Botanical"), 0, 2),
                        AseGroup(listOf("Botanical", "Empty"), 1, 1),
                    ),
                )
            val files = createFiles()
            withSession(files) {
                val artwork = withContext(Dispatchers.Main) { artwork() }
                importPalette(flowerFile)
                waitFor { files.preferenceWrites.get() == 1 }
                importPalette(AsePaletteCodec.encode(grouped))
                waitFor { files.preferenceWrites.get() == 2 }
                val preferences = withContext(Dispatchers.Main) { controller.preferences }
                val persisted = files.preferenceBytes.get().copyOf()
                val expected =
                    AsePalette(
                        flowerPalette.swatches + grouped.swatches,
                        grouped.groups.map { it.copy(start = it.start + 3, end = it.end + 3) },
                    )
                assertEquals(expected, preferences.asePalette)
                assertEquals(flowerMetadata, preferences.paletteFileMetadata)
                chooseExport(PaletteFileFormat.Gpl)
                withContext(Dispatchers.Main) { assertTrue(hasLabel("Export colors")) }
                assertEquals(0, files.paletteExports.get())
                click("Cancel")
                assertEquals(0, files.paletteExports.get())
                withContext(Dispatchers.Main) {
                    assertPreferencesUnchanged(preferences, persisted, 2)
                }
                chooseExport(PaletteFileFormat.Gpl)
                click("Export colors")
                val exported = awaitExport(1, PaletteFileFormat.Gpl)
                assertEquals("Flowers 🖌", exported.name)
                assertEquals(3, exported.columns)
                assertEquals(
                    expected.swatches.map { AseSwatch(it.name, it.color) },
                    exported.palette.swatches,
                )
                assertTrue(exported.palette.groups.isEmpty())
                withContext(Dispatchers.Main) {
                    assertPreferencesUnchanged(preferences, persisted, 2)
                    assertArtworkUnchanged(artwork)
                }
            }
        }

    @Test
    fun badPalCountsFailAtomicallyAndPaletteMetadataSurvivesAControllerRestart() = runBlocking {
        val files = createFiles()
        lateinit var preferences: Preferences
        withSession(files) {
            val artwork = withContext(Dispatchers.Main) { artwork() }
            importPalette(flowerFile)
            waitFor { files.preferenceWrites.get() == 1 }
            val original = withContext(Dispatchers.Main) { controller.preferences }
            val persisted = files.preferenceBytes.get().copyOf()
            for (bad in
                listOf(
                    "JASC-PAL\r\n0100\r\n3\r\n12 34 56\r\n78 90 12\r\n",
                    "JASC-PAL\r\n0100\r\n1\r\n12 34 56\r\n78 90 12\r\n",
                )) {
                importPalette(bad.encodeToByteArray(), allowError = true)
                waitFor(allowError = true) { controller.error != null }
                withContext(Dispatchers.Main) {
                    assertTrue(assertNotNull(controller.error).isNotBlank())
                    assertPreferencesUnchanged(original, persisted, 1)
                    assertArtworkUnchanged(artwork)
                    controller.dismissError()
                }
            }
            importPalette("JASC-PAL\r\n0100\r\n2\r\n12 34 56\r\n12 34 56\r\n".encodeToByteArray())
            waitFor { files.preferenceWrites.get() == 2 }
            preferences = withContext(Dispatchers.Main) { controller.preferences }
            assertEquals(flowerMetadata, preferences.paletteFileMetadata)
            assertEquals(
                AsePalette(flowerPalette.swatches + List(2) { AseSwatch("", 0xFF0C2238) }),
                preferences.asePalette,
            )
            assertEquals(listOf(0xFFFF0000L, 0xFF0080FFL, 0xFF0C2238L), preferences.palette)
            assertEquals(
                preferences,
                Json.decodeFromString<Preferences>(files.preferenceBytes.get().decodeToString()),
            )
            withContext(Dispatchers.Main) { assertArtworkUnchanged(artwork) }
        }
        withSession(files) {
            val artwork = withContext(Dispatchers.Main) { artwork() }
            withContext(Dispatchers.Main) { assertEquals(preferences, controller.preferences) }
            val persisted = files.preferenceBytes.get().copyOf()
            chooseExport(PaletteFileFormat.Gpl)
            val exported = awaitExport(1, PaletteFileFormat.Gpl)
            assertEquals(preferences.asePalette, exported.palette)
            assertEquals(flowerMetadata.name, exported.name)
            assertEquals(flowerMetadata.columns, exported.columns)
            withContext(Dispatchers.Main) {
                assertFalse(hasLabel("Export colors"))
                assertPreferencesUnchanged(preferences, persisted, 2)
                assertArtworkUnchanged(artwork)
            }
            assertEquals(4, files.paletteOpens.get())
        }
    }
}
