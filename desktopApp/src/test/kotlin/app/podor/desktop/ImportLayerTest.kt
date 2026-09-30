package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ImportLayerTest {
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    @Test
    fun layerButtonImportsWithoutReplacingTheArtworkAndCancellationIsHarmless() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(512, 384)
            val original =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[70,45,55,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val reference = ProjectReference("current.podor", "Current")
            var nextImage: OpenedProject? =
                OpenedProject(
                    Files.readAllBytes(Path.of("../engine/tests/fixtures/gimp-interlaced.png")),
                    ProjectReference("reference.png", "Reference", false),
                )
            var imports = 0
            var saved: ByteArray? = null
            val files =
                object : ProjectFiles {
                    override suspend fun open() = original

                    override suspend fun openDocument(reference: ProjectReference?) =
                        OpenedProject(original, reference)

                    override suspend fun openImage(): OpenedProject? {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        imports++
                        return nextImage
                    }

                    override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
                        error("Use saveDocument")

                    override suspend fun saveDocument(
                        bytes: ByteArray,
                        reference: ProjectReference?,
                        saveAs: Boolean,
                    ): ProjectReference {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        assertEquals("current.podor", reference!!.id)
                        saved = bytes
                        return reference
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var scene: ImageComposeScene? = null
            var time = 0L
            suspend fun render(count: Int = 1) =
                withContext(Dispatchers.Main) {
                    repeat(count) {
                        scene?.render(time++ * 16_666_667L)?.close()
                        yield()
                    }
                }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) {
                        render()
                        delay(10)
                    }
                }
            suspend fun importFromButton() {
                render()
                withContext(Dispatchers.Main) {
                    val button =
                        scene!!
                            .semanticsOwners
                            .asSequence()
                            .flatMap { descendants(it.rootSemanticsNode) }
                            .filter {
                                it.config.contains(SemanticsActions.OnClick) &&
                                    !it.boundsInWindow.isEmpty &&
                                    it.config
                                        .getOrNull(SemanticsProperties.ContentDescription)
                                        ?.contains("导入为图层") == true
                            }
                            .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                            ?: error("Import layer button was not rendered")
                    assertFalse(button.config.contains(SemanticsProperties.Disabled))
                    val point = button.boundsInWindow.center
                    scene!!.sendPointerEvent(PointerEventType.Press, point)
                    scene!!.sendPointerEvent(PointerEventType.Release, point)
                    scene!!.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                }
                render()
            }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.navigate(WorkspaceDestination.Open(reference))
                }
                awaitState {
                    controller.hasCanvas &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                val view = Viewport(zoom = 1.1f, pan = Offset(12f, 16f), rotation = 17f)
                withContext(Dispatchers.Main) {
                    controller.viewport = view
                    scene =
                        ImageComposeScene(900, 640) {
                            PodorTheme {
                                Row {
                                    CanvasWorkspace(controller, Modifier.weight(1f).fillMaxHeight())
                                    Surface(
                                        Modifier.width(300.dp).fillMaxHeight(),
                                        color = StudioTheme.panel,
                                    ) {
                                        LayerControls(controller)
                                    }
                                }
                            }
                        }
                }
                render(40)
                importFromButton()
                awaitState {
                    controller.document.layers.size == 2 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision &&
                        controller.layerMove != null
                }
                withContext(Dispatchers.Main) {
                    scene!!.sendPointerEvent(PointerEventType.Move, Offset(580f, 600f))
                }
                render(50)
                withContext(Dispatchers.Main) {
                    assertEquals(1, imports)
                    assertEquals("Reference", controller.document.layers.last().name)
                    assertEquals(reference, controller.projectReference)
                    assertEquals(view, controller.viewport)
                    assertEquals(Tool.MoveLayer, controller.tool)
                    assertTrue(controller.hasUnsavedChanges)
                    assertNull(saved)
                    val path = Path.of("build/reports/screenshots/import-layer.png")
                    Files.createDirectories(path.parent)
                    scene!!.render(time++ * 16_666_667L).use { image ->
                        val label =
                            "Reference · ${trValue("拖动缩略图排序", controller.preferences.language)}"
                        val bounds =
                            scene!!
                                .semanticsOwners
                                .asSequence()
                                .flatMap { descendants(it.unmergedRootSemanticsNode) }
                                .single {
                                    it.config
                                        .getOrNull(SemanticsProperties.ContentDescription)
                                        ?.contains(label) == true
                                }
                                .boundsInWindow
                        assertTrue(bounds.width > 0f && bounds.height > 0f)
                        val preview =
                            image
                                .toComposeImageBitmap()
                                .toPixelMap()[bounds.center.x.toInt(), bounds.center.y.toInt()]
                        assertTrue(preview.blue > preview.red + 0.2f)
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(path, it.bytes)
                        }
                    }
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy && !controller.hasUnsavedChanges }
                val reopened = createNativeEngine(1, 1)
                try {
                    val info = reopened.call(EngineOperation.LOAD, saved!!).decodeToString()
                    assertTrue(info.contains("Reference"))
                } finally {
                    reopened.close()
                }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState { controller.document.layers.size == 1 }
                withContext(Dispatchers.Main) { controller.command("redo") }
                awaitState {
                    controller.document.layers.size == 2 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                val beforeCancel = withContext(Dispatchers.Main) { controller.document }
                nextImage = null
                importFromButton()
                awaitState { imports == 2 && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertEquals(beforeCancel, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                }
                nextImage =
                    OpenedProject(byteArrayOf(1, 2, 3), ProjectReference("bad.png", "bad", false))
                importFromButton()
                awaitState { imports == 3 && controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertEquals(beforeCancel, controller.document)
                    assertEquals(reference, controller.projectReference)
                    assertFalse(controller.hasUnsavedChanges)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    scene?.close()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }

    @Test
    fun malformedLayerRequestsCannotReplaceTheDocument() {
        NativeLoader.load()
        val native = createNativeEngine(16, 16)
        try {
            val before = native.call(EngineOperation.SAVE)
            for (input in
                listOf(
                    byteArrayOf(),
                    byteArrayOf(1, 0, 0, 0),
                    byteArrayOf(-1, -1, -1, -1),
                    byteArrayOf(1, 0, 0, 0, -1),
                )) {
                assertFails { native.call(EngineOperation.IMPORT_LAYER, input) }
                assertContentEquals(before, native.call(EngineOperation.SAVE))
            }
        } finally {
            native.close()
        }
    }
}
