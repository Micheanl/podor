package app.podor.desktop

import app.podor.data.ProjectFiles
import app.podor.desktop.data.DesktopFiles
import app.podor.desktop.data.DesktopStorage
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.put

class OpenRasterImportTest {
    @Test
    fun oraOpensAsEditableLayersAndSavesSeparatelyWithoutChangingTheSource() = checkImport("ora", 0.5f)

    @Test
    fun psdOpensAsEditableLayersAndSavesSeparatelyWithoutChangingTheSource() = checkImport("psd", 128f / 255f)

    @OptIn(ExperimentalPathApi::class)
    private fun checkImport(extension: String, opacity: Float) =
        runBlocking<Unit> {
            NativeLoader.load()
            val root = Path.of("build", "$extension-tests").toAbsolutePath().normalize()
            Files.createDirectories(root)
            val directory = Files.createTempDirectory(root, "import-")
            val source = directory.resolve("drawing.$extension")
            val original = Files.readAllBytes(Path.of("../engine/tests/fixtures/gimp-layers.$extension"))
            Files.write(source, original)
            val output = ProjectReference(directory.resolve("drawing.podor").toString(), "drawing")
            val disk = DesktopFiles(DesktopStorage(directory.resolve("data"))) { null }
            var writes = 0
            val files =
                object : ProjectFiles by disk {
                    override suspend fun openDocument(
                        reference: ProjectReference?
                    ): OpenedProject? {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        return disk.openDocument(reference)
                    }

                    override suspend fun saveDocument(
                        bytes: ByteArray,
                        reference: ProjectReference?,
                        saveAs: Boolean,
                    ): ProjectReference? {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        assertFalse(reference!!.editable)
                        assertEquals(source.toString(), reference.id)
                        writes++
                        return disk.saveDocument(bytes, output, false)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.navigate(
                        WorkspaceDestination.Open(ProjectReference(source.toString(), "drawing"))
                    )
                }
                awaitState {
                    controller.document.layers.size == 3 &&
                        !controller.busy &&
                        controller.previews.images.keys == setOf(0, 1, 2, 3)
                }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.projectReference!!.editable)
                    assertFalse(controller.showWorkspace)
                    assertEquals(129, controller.document.width)
                    assertEquals(131, controller.document.height)
                    assertEquals(LayerBlendMode.Multiply, controller.document.layers[1].blend)
                    assertEquals(opacity, controller.document.layers[1].opacity)
                    assertFalse(controller.document.layers[2].visible)
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(0, writes)
                    controller.command("select_layer") { put("id", 2) }
                }
                awaitState { controller.document.active == 2 }
                if (extension == "psd") {
                    withContext(Dispatchers.Main) {
                        assertTrue(controller.document.layers[1].alphaLocked)
                        controller.setLayerProtection(2, alphaLocked = false)
                    }
                    awaitState { !controller.document.layers[1].alphaLocked }
                }
                val beforeClear = withContext(Dispatchers.Main) {
                    controller.document.revision.also { controller.command("clear") }
                }
                awaitState { controller.hasUnsavedChanges && controller.document.revision > beforeClear }
                assertEquals(0, writes)
                assertContentEquals(original, Files.readAllBytes(source))
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
                awaitState {
                    !controller.busy &&
                        !controller.hasUnsavedChanges &&
                        controller.projectReference == output
                }
                assertEquals(1, writes)
                assertContentEquals(original, Files.readAllBytes(source))
                assertTrue(Files.size(Path.of(output.id)) > 0)
                val beforeReopen =
                    withContext(Dispatchers.Main) {
                        val revision = controller.document.revision
                        controller.navigate(WorkspaceDestination.Open(output))
                        revision
                    }
                awaitState {
                    !controller.busy &&
                        controller.document.revision > beforeReopen &&
                        controller.document.active == 2 &&
                        controller.previews.revision == controller.document.revision
                }
                val document = withContext(Dispatchers.Main) { controller.document }
                val pixels = withContext(Dispatchers.Main) { controller.frame }
                val bad = directory.resolve("damaged.$extension")
                Files.write(bad, original.copyOf(original.size / 2))
                withContext(Dispatchers.Main) {
                    controller.navigate(
                        WorkspaceDestination.Open(ProjectReference(bad.toString(), "damaged"))
                    )
                }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertEquals(document, controller.document)
                    assertSame(pixels, controller.frame)
                    assertEquals(output, controller.projectReference)
                    assertEquals(setOf(0, 1, 2, 3), controller.previews.images.keys)
                    assertFalse(controller.hasUnsavedChanges)
                    assertTrue(controller.recentProjects.none { it.reference.id == bad.toString() })
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
                check(directory.toRealPath().startsWith(root.toRealPath()))
                directory.deleteRecursively()
            }
        }
}
