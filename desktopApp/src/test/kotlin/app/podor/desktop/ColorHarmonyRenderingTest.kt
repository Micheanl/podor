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
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.RenderFrame
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class ColorHarmonyRenderingTest {
    private class MemoryFiles(private val project: ByteArray, appearance: Appearance) :
        ProjectFiles {
        private val preferences = Preferences(language = Language.English, appearance = appearance)
        val projectSaves = AtomicInteger()
        val preferenceWrites = AtomicInteger()

        override suspend fun open() = project

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            projectSaves.incrementAndGet()
            return true
        }

        override suspend fun readPreferences() =
            Json.encodeToString(preferences).encodeToByteArray()

        override suspend fun writePreferences(bytes: ByteArray) {
            preferenceWrites.incrementAndGet()
        }
    }

    private data class Artwork(
        val document: DocumentInfo,
        val frame: RenderFrame,
        val pixels: IntArray,
        val preferences: Preferences,
        val unsaved: Boolean,
    )

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
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

        fun controls(label: String): List<SemanticsNode> =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        (it.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains(label) == true ||
                            it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                text.text == label
                            } == true)
                }
                .toList()

        suspend fun click(label: String) {
            settle()
            withContext(Dispatchers.Main) {
                val control =
                    controls(label).minByOrNull {
                        it.boundsInWindow.width * it.boundsInWindow.height
                    } ?: error("Missing rendered color control: $label")
                assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                val point = control.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        private fun pixels(): IntArray {
            val image = controller.frame.tiles.values.single().image
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }

        fun artwork() =
            Artwork(
                controller.document,
                controller.frame,
                pixels(),
                controller.preferences,
                controller.hasUnsavedChanges,
            )

        fun assertArtworkUnchanged(before: Artwork) {
            assertEquals(before.document, controller.document)
            assertSame(before.frame, controller.frame)
            assertContentEquals(before.pixels, pixels())
            assertEquals(before.preferences, controller.preferences)
            assertEquals(before.unsaved, controller.hasUnsavedChanges)
            assertEquals(0, files.projectSaves.get())
            assertEquals(0, files.preferenceWrites.get())
        }

        fun screenshot(appearance: Appearance) {
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    val path =
                        Path.of(
                            "build/reports/screenshots/color-harmony-${appearance.name.lowercase()}.png"
                        )
                    Files.createDirectories(path.parent)
                    Files.write(path, data.bytes)
                }
            }
        }
    }

    private suspend fun withSession(
        appearance: Appearance = Appearance.Light,
        block: suspend Session.() -> Unit,
    ) {
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
        val files = MemoryFiles(project, appearance)
        val previousAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(360, 1400) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Surface(Modifier.fillMaxSize(), color = StudioTheme.panel) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ColorControls(controller)
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
            withContext(Dispatchers.Main) {
                controller.brush = controller.brush.copy(color = 0x80FF0000L)
            }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = previousAppearance) {} }
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
    fun themeMenusAndSwatchesPreserveArtworkAndHideForMaskAndIndexedTargets() = runBlocking {
        for (appearance in Appearance.entries) {
            withSession(appearance) {
                val before = withContext(Dispatchers.Main) { artwork() }
                click("Color #00ffff")
                withContext(Dispatchers.Main) {
                    assertEquals(0x8000FFFFL, controller.brush.color)
                    assertEquals(appearance, StudioTheme.appearance)
                    assertArtworkUnchanged(before)
                    controller.brush = controller.brush.copy(color = 0x80FF0000L)
                }
                click("Color harmony")
                click("Triadic")
                withContext(Dispatchers.Main) {
                    assertEquals(1, controls("Color #00ff00").size)
                    assertEquals(1, controls("Color #0000ff").size)
                    screenshot(appearance)
                }
                click("Color #00ff00")
                withContext(Dispatchers.Main) {
                    assertEquals(0x8000FF00L, controller.brush.color)
                    assertArtworkUnchanged(before)
                    controller.brush = controller.brush.copy(color = 0x80808080L)
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(1, controls("Color #808080").size)
                    assertArtworkUnchanged(before)
                    assertFalse(scene.hasInvalidations())
                    controller.addLayerMask("reveal")
                }
                waitFor { controller.document.maskEditing }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controls("Color harmony").isEmpty())
                    controller.selectLayer(controller.document.active)
                }
                waitFor { !controller.document.maskEditing }
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(1, controls("Color harmony").size)
                }
                click("Convert to indexed color")
                waitFor { controller.document.colorMode == DocumentColorMode.Indexed }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controls("Color harmony").isEmpty())
                    assertTrue(controls("Indexed color 1").isNotEmpty())
                    assertEquals(0, files.projectSaves.get())
                    assertNull(controller.error)
                }
            }
        }
    }

    @Test
    fun harmonySwatchesChangeOnlyTheSelectedGradientEndpointWithoutSavingOrPainting() =
        runBlocking {
            withSession {
                val before = withContext(Dispatchers.Main) { artwork() }
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.Gradient
                    controller.gradient =
                        controller.gradient.copy(from = 0x80FF0000L, to = 0x40204060L)
                    controller.gradientEditingStart = true
                    controller.prepareGradient()
                }
                waitFor { controller.gradientPreview != null }
                click("Color #00ffff")
                withContext(Dispatchers.Main) {
                    assertEquals(0x8000FFFFL, controller.gradient.from)
                    assertEquals(0x40204060L, controller.gradient.to)
                    assertEquals(0x80FF0000L, controller.brush.color)
                    assertArtworkUnchanged(before)
                    controller.gradient = controller.gradient.copy(to = 0x40FF0000L)
                    controller.gradientEditingStart = false
                }
                click("Color harmony")
                click("Triadic")
                click("Color #00ff00")
                withContext(Dispatchers.Main) {
                    assertEquals(0x8000FFFFL, controller.gradient.from)
                    assertEquals(0x4000FF00L, controller.gradient.to)
                    assertEquals(0x80FF0000L, controller.brush.color)
                    assertArtworkUnchanged(before)
                    assertNull(controller.gradientPreview?.line)
                    assertFalse(scene.hasInvalidations())
                    controller.cancelGradient()
                    controller.tool = Tool.Brush
                }
                settle()
                withContext(Dispatchers.Main) { assertArtworkUnchanged(before) }
            }
        }
}
