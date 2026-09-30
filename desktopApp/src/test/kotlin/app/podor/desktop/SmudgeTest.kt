package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SmudgeTest {
    private suspend fun session(
        block:
            suspend (
                StudioController,
                ImageComposeScene,
                ByteArray,
                suspend () -> ByteArray,
            ) -> Unit
    ) {
        NativeLoader.load()
        val engine = createNativeEngine(320, 200)
        val project =
            try {
                fun cmd(value: String) =
                    engine.call(EngineOperation.COMMAND, value.encodeToByteArray())
                cmd("""{"type":"fill","x":0,"y":0,"color":[180,40,60,255],"tolerance":0}""")
                cmd("""{"type":"select","rect":{"left":160,"top":0,"right":320,"bottom":200}}""")
                cmd("""{"type":"fill","x":200,"y":100,"color":[30,90,190,255],"tolerance":0}""")
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
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
        val scene =
            withContext(Dispatchers.Main) { ImageComposeScene(1360, 900) { StudioApp(controller) } }
        try {
            awaitState(controller) { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            awaitState(controller) { controller.hasCanvas && !controller.busy }
            withContext(Dispatchers.Main) {
                scene.render().close()
                scene.render(16_666_667L).close()
            }
            block(controller, scene, project) {
                withContext(Dispatchers.Main) {
                    saved = null
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState(controller) { saved != null && !controller.busy }
                saved!!
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    private suspend fun awaitState(controller: StudioController, predicate: () -> Boolean) =
        withTimeout(15_000) {
            while (
                !withContext(Dispatchers.Main) {
                    assertNull(controller.error)
                    predicate()
                }
            ) delay(5)
        }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }

    private fun canvasBounds(scene: ImageComposeScene): Rect =
        scene.semanticsOwners
            .asSequence()
            .flatMap { descendants(it.rootSemanticsNode) }
            .filter {
                it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" &&
                    it.boundsInWindow.width > 100f &&
                    it.boundsInWindow.height > 100f
            }
            .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
            .boundsInWindow

    private fun position(
        controller: StudioController,
        scene: ImageComposeScene,
        point: Offset,
    ): Offset {
        val bounds = canvasBounds(scene)
        return controller.viewport.toView(point, bounds.size, controller.document) + bounds.topLeft
    }

    @Test
    fun shortcutStrokeAndUndoKeepTheBrushColorAndOpacity() = runBlocking {
        session { controller, scene, original, save ->
            var frame = 2L
            withContext(Dispatchers.Main) {
                scene.openInspector { scene.render(frame++ * 16_666_667L).close() }
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.S, KeyEventType.KeyDown)))
                scene.sendKeyEvent(KeyEvent(Key.S, KeyEventType.KeyUp))
                assertEquals(Tool.Smudge, controller.tool)
                controller.selectPreset(BrushPreset.Soft)
                assertEquals(Tool.Smudge, controller.tool)
                controller.brush =
                    controller.brush.copy(
                        size = 64f,
                        preset =
                            controller.brush.preset.copy(hardness = 0.55f, sizePressure = 0.5f),
                    )
                controller.viewport = Viewport(rotation = 17f, mirrored = true)
                scene.render(frame++ * 16_666_667L).close()
                val opacity = controller.brush.opacity
                val strength = scene.sliderBounds("涂抹强度")
                val strengthPoint = Offset(strength.left + strength.width * 0.3f, strength.center.y)
                scene.sendPointerEvent(PointerEventType.Press, strengthPoint)
                scene.sendPointerEvent(PointerEventType.Release, strengthPoint)
                scene.render(frame++ * 16_666_667L).close()
                assertNotEquals(StudioDefaults.smudgeStrength, controller.smudgeStrength)
                assertEquals(opacity, controller.brush.opacity)
            }
            val before = withContext(Dispatchers.Main) { controller.document.revision }
            val brush = withContext(Dispatchers.Main) { controller.brush }
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    position(controller, scene, Offset(138f, 100f)),
                )
            }
            repeat(35) { i ->
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        PointerEventType.Move,
                        position(controller, scene, Offset(138f + i * 2, 100f)),
                    )
                    scene.render(frame++ * 16_666_667L).close()
                }
                delay(3)
            }
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    position(controller, scene, Offset(206f, 100f)),
                )
            }
            awaitState(controller) { controller.document.revision > before }
            withContext(Dispatchers.Main) {
                assertEquals(before + 1, controller.document.revision)
                assertEquals(brush, controller.brush)
                val tile =
                    controller.frame.tiles.values
                        .single { it.x == 1 && it.y == 0 }
                        .image
                        .toPixelMap()
                assertTrue(tile[40, 100].red > 30f / 255)
                for (language in Language.entries) {
                    controller.updatePreferences(controller.preferences.copy(language = language))
                    scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                    repeat(35) { scene.render(frame++ * 16_666_667L).close() }
                    Files.createDirectories(Path.of("build/reports/screenshots"))
                    scene.render(frame++ * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(
                                Path.of(
                                    "build/reports/screenshots/smudge-${language.name.lowercase()}.png"
                                ),
                                it.bytes,
                            )
                        }
                    }
                }
                controller.command("undo")
            }
            awaitState(controller) { controller.document.revision == before + 2 }
            assertContentEquals(original, save())
        }
    }

    @Test
    fun secondTouchCancelsTheSmudgeAndTheStylusEraserKeepsItsOwnBehavior() = runBlocking {
        session { controller, scene, original, save ->
            var frame = 2L
            val before =
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.Smudge
                    controller.brush = controller.brush.copy(size = 64f)
                    scene.render(frame++ * 16_666_667L).close()
                    controller.document.revision
                }
            fun touch(id: Long, x: Float, down: Boolean) =
                ComposeScenePointer(
                    PointerId(id),
                    position(controller, scene, Offset(x, 100f)),
                    down,
                    PointerType.Touch,
                )
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Press, listOf(touch(1, 135f, true)))
            }
            delay(20)
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Move, listOf(touch(1, 185f, true)))
            }
            delay(40)
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    listOf(touch(1, 185f, true), touch(2, 210f, true)),
                )
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    listOf(touch(1, 185f, false), touch(2, 210f, false)),
                )
                scene.render(frame++ * 16_666_667L).close()
            }
            assertContentEquals(original, save())
            withContext(Dispatchers.Main) {
                assertEquals(before, controller.document.revision)
                assertFalse(controller.hasUnsavedChanges)
                controller.begin(Offset(220f, 100f), 1f, stylusEraser = true)
                controller.end()
            }
            awaitState(controller) { controller.document.revision > before }
            assertFalse(original.contentEquals(save()))
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState(controller) { controller.document.revision == before + 2 }
            assertContentEquals(original, save())
        }
    }
}
