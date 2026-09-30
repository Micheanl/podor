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
class PaletteFileWorkflowRenderingTest {
    private val initialColors = listOf(0xFF224466L, 0xFF6699AAL)
    private val flowerPalette =
        AsePalette(
            listOf(
                AseSwatch("Rose", 0xFFFF0000, AseSwatchType.Global, listOf("Flowers")),
                AseSwatch("Ruby", 0xFFFF0000, AseSwatchType.Spot, listOf("Flowers", "Petals")),
                AseSwatch("Leaf", 0xFF00FF00, AseSwatchType.Process, listOf("Flowers")),
            ),
            listOf(
                AseGroup(listOf("Flowers"), 0, 3),
                AseGroup(listOf("Flowers", "Petals"), 1, 2),
                AseGroup(listOf("Flowers", "Empty"), 2, 2),
            ),
        )
    private val flowerFile =
        ("415345460001000000000009c0010000001200080046006c006f00770065007200730000" +
                "00010000001e00050052006f007300650000524742203f80000000000000000000000000" +
                "c0010000001000070050006500740061006c00730000" +
                "00010000001e000500520075006200790000524742203f80000000000000000000000001" +
                "c00200000000c0010000000e00060045006d0070007400790000c00200000000" +
                "00010000001e0005004c006500610066000052474220000000003f800000000000000002" +
                "c00200000000")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private class MemoryFiles(project: ByteArray, preferences: Preferences) : ProjectFiles {
        private val project = project.copyOf()
        val preferenceBytes = AtomicReference(Json.encodeToString(preferences).encodeToByteArray())
        val preferenceWrites = AtomicInteger()
        val projectSaves = AtomicInteger()
        val paletteOpens = AtomicInteger()
        val paletteExports = AtomicInteger()
        val paletteInput = AtomicReference<ByteArray>()
        val paletteOutput = AtomicReference<ByteArray>()
        override val supportsPaletteFiles = true

        override suspend fun open() = project

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            projectSaves.incrementAndGet()
            return true
        }

        override suspend fun readPreferences() = preferenceBytes.get()

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

        override suspend fun savePalette(bytes: ByteArray): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            paletteOutput.set(bytes.copyOf())
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
            repeat(25) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        private fun node(label: String) =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        it.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains(label) == true
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Missing rendered palette control: $label")

        suspend fun click(label: String) {
            settle()
            withContext(Dispatchers.Main) {
                val control = node(label)
                assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                val point = control.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
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

        suspend fun exportPalette(): AsePalette {
            val exports = files.paletteExports.get()
            click("Palette files")
            click("Export palette · Adobe ASE")
            waitFor { files.paletteExports.get() == exports + 1 }
            return AsePaletteCodec.decode(assertNotNull(files.paletteOutput.get()))
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
    }

    private suspend fun withSession(block: suspend Session.() -> Unit) {
        NativeLoader.load()
        val native = createNativeEngine(32, 24)
        val project =
            try {
                native.call(
                    EngineOperation.COMMAND,
                    """{"type":"fill","x":0,"y":0,"color":[31,87,128,192],"tolerance":0}"""
                        .encodeToByteArray(),
                )
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            MemoryFiles(
                project,
                Preferences(
                    language = Language.English,
                    appearance = Appearance.Light,
                    palette = initialColors,
                ),
            )
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(600, 320) {
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
    fun realFileMenuAppendsNamedGroupedSwatchesAndExportsManualEditsWithoutChangingArtwork() =
        runBlocking {
            withSession {
                val before = withContext(Dispatchers.Main) { artwork() }
                assertEquals(flowerPalette, AsePaletteCodec.decode(flowerFile))
                importPalette(flowerFile)
                waitFor { files.preferenceWrites.get() == 1 }
                val appended =
                    AsePalette(
                        listOf(
                            AseSwatch("#224466", initialColors[0]),
                            AseSwatch("#6699AA", initialColors[1]),
                        ) + flowerPalette.swatches,
                        flowerPalette.groups.map {
                            it.copy(start = it.start + 2, end = it.end + 2)
                        },
                    )
                withContext(Dispatchers.Main) {
                    assertEquals(appended, controller.preferences.asePalette)
                    assertEquals(
                        initialColors + listOf(0xFFFF0000L, 0xFF00FF00L),
                        controller.preferences.palette,
                    )
                    assertTrue(controller.preferences.valid())
                    assertArtworkUnchanged(before)
                }
                assertEquals(appended, exportPalette())
                val header = assertNotNull(files.paletteOutput.get()).copyOf(8)
                assertContentEquals("ASEF".encodeToByteArray() + byteArrayOf(0, 1, 0, 0), header)
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(color = 0xFF1133CC)
                }
                click("Save current color")
                waitFor { files.preferenceWrites.get() == 2 }
                withContext(Dispatchers.Main) {
                    assertEquals(appended.groups, controller.preferences.asePalette!!.groups)
                    assertEquals(
                        AseSwatch("#1133CC", 0xFF1133CC),
                        controller.preferences.asePalette!!.swatches.last(),
                    )
                    controller.brush = controller.brush.copy(color = 0xFFFF0000)
                }
                click("Remove this color")
                waitFor { files.preferenceWrites.get() == 3 }
                val edited =
                    AsePalette(
                        listOf(
                            AseSwatch("#224466", initialColors[0]),
                            AseSwatch("#6699AA", initialColors[1]),
                            flowerPalette.swatches.last(),
                            AseSwatch("#1133CC", 0xFF1133CC),
                        ),
                        listOf(
                            AseGroup(listOf("Flowers"), 2, 3),
                            AseGroup(listOf("Flowers", "Petals"), 2, 2),
                            AseGroup(listOf("Flowers", "Empty"), 2, 2),
                        ),
                    )
                assertEquals(edited, exportPalette())
                withContext(Dispatchers.Main) {
                    assertEquals(edited, controller.preferences.asePalette)
                    assertEquals(
                        edited.swatches.map { it.color }.distinct(),
                        controller.preferences.palette,
                    )
                    assertEquals(
                        controller.preferences,
                        Json.decodeFromString<Preferences>(
                            files.preferenceBytes.get().decodeToString()
                        ),
                    )
                    assertArtworkUnchanged(before)
                    assertNull(controller.error)
                }
            }
        }

    @Test
    fun damagedTruncatedAndOverflowingImportsAreRejectedAtomicallyAndTheFileMenuRemainsUsable() =
        runBlocking {
            withSession {
                val before = withContext(Dispatchers.Main) { artwork() }
                val preferences = controller.preferences
                val persisted = files.preferenceBytes.get().copyOf()
                val full =
                    AsePaletteCodec.encode(
                        AsePalette(List(256) { AseSwatch("Color $it", 0xFF000000L or it.toLong()) })
                    )
                for (bytes in
                    listOf(
                        flowerFile.copyOf().also { it[0] = 'X'.code.toByte() },
                        flowerFile.copyOf(flowerFile.size - 1),
                        full,
                    )) {
                    importPalette(bytes, allowError = true)
                    waitFor(allowError = true) { controller.error != null }
                    withContext(Dispatchers.Main) {
                        assertTrue(assertNotNull(controller.error).isNotBlank())
                        assertEquals(preferences, controller.preferences)
                        assertContentEquals(persisted, files.preferenceBytes.get())
                        assertEquals(0, files.preferenceWrites.get())
                        assertArtworkUnchanged(before)
                        controller.dismissError()
                    }
                }
                assertEquals(3, files.paletteOpens.get())
                importPalette(flowerFile)
                waitFor { files.preferenceWrites.get() == 1 }
                assertEquals(controller.preferences.asePalette, exportPalette())
                withContext(Dispatchers.Main) {
                    assertArtworkUnchanged(before)
                    assertNull(controller.error)
                }
            }
        }
}
