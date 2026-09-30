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
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class PaletteDrawingTest {
    @Test
    fun personalColorsCanBeExtractedSelectedRemovedAndReopenedWithoutSavingTheArtwork() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(128, 128)
            val project =
                try {
                    assertFails { engine.call(EngineOperation.PALETTE) }
                    assertFails { engine.call(EngineOperation.PALETTE, byteArrayOf(0)) }
                    assertFails { engine.call(EngineOperation.PALETTE, byteArrayOf(25)) }
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[144,24,64,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val saved = AtomicReference<ByteArray>()
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Palette must not save the artwork")

                    override suspend fun readPreferences() = saved.get()

                    override suspend fun writePreferences(bytes: ByteArray) {
                        saved.set(bytes)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            assertNull(controller.error)
                            predicate()
                        }
                    ) delay(5)
                }
            var scene: ImageComposeScene? = null
            var reopened: StudioController? = null
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.hasCanvas && !controller.busy }
                val before = withContext(Dispatchers.Main) { controller.document }
                val beforeFrame = withContext(Dispatchers.Main) { controller.frame }
                val paletteScene =
                    withContext(Dispatchers.Main) {
                        ImageComposeScene(300, 260) {
                            PodorTheme {
                                Surface(color = StudioTheme.panel) {
                                    Box(Modifier.padding(20.dp)) {
                                        PersonalPalette(controller, controller.brush.color) {
                                            controller.brush = controller.brush.copy(color = it)
                                        }
                                    }
                                }
                            }
                        }
                    }
                scene = paletteScene
                var frame = 0L
                fun render() {
                    repeat(30) { paletteScene.render(frame++ * 16_666_667L).close() }
                }
                suspend fun click(x: Float, y: Float) =
                    withContext(Dispatchers.Main) {
                        paletteScene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        paletteScene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        paletteScene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        render()
                    }
                fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                    yield(node)
                    for (child in node.children) yieldAll(descendants(child))
                }
                suspend fun click(label: String) =
                    withContext(Dispatchers.Main) {
                        render()
                        val button =
                            paletteScene.semanticsOwners
                                .flatMap { descendants(it.rootSemanticsNode).toList() }
                                .filter {
                                    it.config.contains(SemanticsActions.OnClick) &&
                                        !it.boundsInWindow.isEmpty &&
                                        it.config
                                            .getOrNull(SemanticsProperties.ContentDescription)
                                            ?.contains(label) == true
                                }
                                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                                ?: error("Button not found: $label")
                        assertFalse(button.config.contains(SemanticsProperties.Disabled), label)
                        val point = button.boundsInWindow.center
                        click(point.x, point.y)
                    }
                withContext(Dispatchers.Main) { render() }
                click("保存当前颜色")
                awaitState { controller.preferences.palette == listOf(0xFF000000) }
                click("从画布提取颜色")
                awaitState {
                    !controller.extractingPalette && controller.preferences.palette.size == 2
                }
                withContext(Dispatchers.Main) { render() }
                click(87f, 98f)
                assertEquals(0xFF901840, withContext(Dispatchers.Main) { controller.brush.color })
                click("移除此颜色")
                awaitState { controller.preferences.palette == listOf(0xFF000000) }
                click("保存当前颜色")
                awaitState { controller.preferences.palette == listOf(0xFF000000, 0xFF901840) }
                click("从画布提取颜色")
                awaitState { !controller.extractingPalette }
                withContext(Dispatchers.Main) {
                    render()
                    assertEquals(before, controller.document)
                    assertSame(beforeFrame, controller.frame)
                    assertFalse(controller.hasUnsavedChanges)
                    assertFalse(paletteScene.hasInvalidations())
                    assertEquals(listOf(0xFF000000, 0xFF901840), controller.preferences.palette)
                    for (language in Language.entries) {
                        val preview =
                            ImageComposeScene(300, 1100) {
                                PodorTheme(language) {
                                    Surface(Modifier.fillMaxSize(), color = StudioTheme.panel) {
                                        Column(
                                            Modifier.padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(18.dp),
                                        ) {
                                            ColorControls(controller)
                                        }
                                    }
                                }
                            }
                        try {
                            preview.render(0).close()
                            preview.render(16_666_667L).use { image ->
                                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                    val path =
                                        Path.of(
                                            "build/reports/screenshots/personal-palette-${language.name.lowercase()}.png"
                                        )
                                    Files.createDirectories(path.parent)
                                    Files.write(path, it.bytes)
                                }
                            }
                            controller.tool = Tool.Gradient
                            controller.gradientEditingStart = false
                            repeat(3) { preview.render((it + 2) * 16_666_667L).close() }
                            preview.sendPointerEvent(PointerEventType.Press, Offset(38f, 488f))
                            preview.sendPointerEvent(PointerEventType.Release, Offset(38f, 488f))
                            repeat(30) { preview.render((it + 5) * 16_666_667L).close() }
                            assertEquals(0xFF000000, controller.gradient.to)
                            assertEquals(0xFF901840, controller.brush.color)
                            controller.tool = Tool.Brush
                        } finally {
                            preview.close()
                        }
                    }
                }
                withTimeout(5_000) {
                    while (
                        saved.get()?.let {
                            Json.decodeFromString<Preferences>(it.decodeToString()).palette.size
                        } != 2
                    ) delay(5)
                }
                withContext(Dispatchers.Main) {
                    reopened = StudioController(files, scope)
                }
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { reopened!!.ready }) delay(5)
                }
                assertEquals(
                    listOf(0xFF000000, 0xFF901840),
                    withContext(Dispatchers.Main) { reopened!!.preferences.palette },
                )
                withContext(Dispatchers.Main) {
                    val full = List(StudioDefaults.maxPaletteColors) { 0xFF000000L + it }
                    controller.updatePreferences(controller.preferences.copy(palette = full))
                    assertFalse(controller.addPaletteColors(listOf(0xFFFFFFFF)))
                    assertEquals(full, controller.preferences.palette)
                    assertEquals("色卡空间不足，请先移除一些颜色", controller.error)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    scene?.close()
                    reopened?.shutdown()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
