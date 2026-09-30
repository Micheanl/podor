package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class LayerProtectionTest {
    @Test
    fun panelLocksPreserveAlphaBlockEditsAndSurviveSaving() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(64, 64)
            val project =
                try {
                    for (command in
                        listOf(
                            """{"type":"select","rect":{"left":12,"top":12,"right":52,"bottom":52}}""",
                            """{"type":"fill","x":20,"y":20,"color":[255,0,0,128],"tolerance":0}""",
                            """{"type":"add_layer"}""",
                            """{"type":"select_layer","id":1}""",
                        )) native.call(EngineOperation.COMMAND, command.encodeToByteArray())
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            var saved: ByteArray? = null
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
                        saved = bytes
                        return true
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            var scene: ImageComposeScene? = null
            var frame = 0L
            suspend fun render() =
                withContext(Dispatchers.Main) {
                    repeat(30) { scene!!.render(frame++ * 16_666_667L).close() }
                }
            suspend fun click(label: String, enabled: Boolean = true) {
                render()
                withContext(Dispatchers.Main) {
                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                        yield(node)
                        for (child in node.children) yieldAll(descendants(child))
                    }
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
                                        ?.contains(label) == true
                            }
                            .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                            ?: error("Layer control was not rendered: $label")
                    assertEquals(!enabled, button.config.contains(SemanticsProperties.Disabled))
                    val point = button.boundsInWindow.center
                    scene!!.sendPointerEvent(PointerEventType.Press, point)
                    scene!!.sendPointerEvent(PointerEventType.Release, point)
                    scene!!.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                }
                render()
            }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState {
                    controller.document.width == 64 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                withContext(Dispatchers.Main) {
                    scene =
                        ImageComposeScene(300, 640) {
                            PodorTheme {
                                Surface(color = StudioTheme.panel) { LayerControls(controller) }
                            }
                        }
                }
                render()
                val before = withContext(Dispatchers.Main) { controller.frame }
                click("锁定透明度")
                awaitState { controller.document.layers.first().alphaLocked }
                val painting =
                    withContext(Dispatchers.Main) {
                        val revision = controller.document.revision
                        assertSame(before, controller.frame)
                        controller.brush =
                            controller.brush.copy(size = 128f, opacity = 1f, color = 0xFF0000FF)
                        controller.begin(Offset(32f, 32f), 1f)
                        controller.end()
                        revision
                    }
                awaitState {
                    controller.document.revision > painting &&
                        controller.previews.revision == controller.document.revision &&
                        controller.document.canUndo
                }
                withContext(Dispatchers.Main) {
                    val preview = controller.previews.images.getValue(1).toPixelMap()
                    assertEquals(128 / 255f, preview[48, 48].alpha, 0.01f)
                    assertEquals(1f, preview[48, 48].blue, 0.01f)
                    assertEquals(0f, preview[0, 0].alpha)
                }
                click("锁定图层")
                awaitState { controller.document.layers.first().locked }
                render()
                withContext(Dispatchers.Main) {
                    val path = Path.of("build/reports/screenshots/layer-protection.png")
                    Files.createDirectories(path.parent)
                    val previewScene = assertNotNull(scene)
                    previewScene.render(frame++ * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(path, it.bytes)
                        }
                    }
                    assertFalse(previewScene.hasInvalidations())
                }
                val locked = withContext(Dispatchers.Main) { controller.document }
                click("删除图层", enabled = false)
                withContext(Dispatchers.Main) {
                    assertEquals(2, controller.document.layers.size)
                    controller.fill(Offset(32f, 32f))
                }
                awaitState { controller.error != null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertEquals(locked, controller.document)
                    controller.dismissError()
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy }
                val restored = createNativeEngine(1, 1)
                try {
                    val loaded =
                        kotlinx.serialization.json.Json.decodeFromString<DocumentInfo>(
                            restored
                                .call(EngineOperation.LOAD, assertNotNull(saved))
                                .decodeToString()
                        )
                    assertTrue(loaded.layers.first { it.id == 1 }.locked)
                    assertTrue(loaded.layers.first { it.id == 1 }.alphaLocked)
                } finally {
                    restored.close()
                }
                click("解锁图层")
                awaitState { !controller.document.layers.first().locked }
                click("解除透明度锁定")
                awaitState { !controller.document.layers.first().alphaLocked }
                withContext(Dispatchers.Main) { controller.command("undo") }
                awaitState { controller.document.layers.first().alphaLocked }
            } finally {
                withContext(Dispatchers.Main) {
                    scene?.close()
                    controller.close()
                }
                scope.cancel()
            }
        }
}
