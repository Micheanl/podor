package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SymmetryDrawingTest {
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

    @Test
    fun controlsGuidesAndRotatedCanvasPaintingShareTheSameAxes() =
        runBlocking<Unit> {
            NativeLoader.load()
            val native = createNativeEngine(320, 240)
            val project =
                try {
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
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(1360, 900) { StudioApp(controller) }
                }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            assertNull(controller.error)
                            predicate()
                        }
                    ) delay(5)
                }
            var frame = 0L
            fun render() {
                repeat(30) { scene.render(frame++ * 16_666_667L).close() }
            }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                render()
            }
            fun screenshot(name: String) {
                scene.render(frame++ * 16_666_667L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        val path = Path.of("build/reports/screenshots/$name.png")
                        Files.createDirectories(path.parent)
                        Files.write(path, it.bytes)
                    }
                }
            }
            fun position(point: Offset): Offset {
                val bounds = canvasBounds(scene)
                return controller.viewport.toView(point, bounds.size, controller.document) +
                    bounds.topLeft
            }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.hasCanvas && !controller.busy }
                withContext(Dispatchers.Main) {
                    scene.openInspector { render() }
                    controller.viewport = Viewport(rotation = 17f, mirrored = true)
                    controller.brush =
                        controller.brush.copy(
                            size = 24f,
                            opacity = 0.7f,
                            color = 0xFF8B2942,
                            preset = BrushPreset.Ink.copy(hardness = 1f),
                        )
                    render()
                    val pixels = controller.frame
                    val revision = controller.document.revision
                    scene.clickControl("对称绘画") { render() }
                    screenshot("symmetry-menu")
                    scene.clickControl("双轴对称") { render() }
                    screenshot("symmetry-menu-selected")
                    assertEquals(SymmetryMode.Quadrant, controller.symmetry.mode)
                    val axis = scene.sliderBounds("横向位置")
                    val axisPoint = Offset(axis.left + axis.width * 0.2f, axis.center.y)
                    click(axisPoint.x, axisPoint.y)
                    assertTrue(controller.symmetry.x < 0.4f)
                    scene.clickControl("对称轴居中") { render() }
                    assertEquals(0.5f, controller.symmetry.x)
                    val toggle =
                        scene.semanticsOwners
                            .asSequence()
                            .flatMap { descendants(it.rootSemanticsNode) }
                            .single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
                            .boundsInWindow
                            .center
                    click(toggle.x, toggle.y)
                    assertFalse(controller.symmetry.guides)
                    click(toggle.x, toggle.y)
                    assertTrue(controller.symmetry.guides)
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    render()
                    screenshot("symmetry-menu-english")
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.Chinese)
                    )
                    render()
                    assertSame(pixels, controller.frame)
                    assertEquals(revision, controller.document.revision)
                    click(900f, 740f)
                    screenshot("symmetry-guides")
                    scene.sendPointerEvent(PointerEventType.Press, position(Offset(40f, 40f)))
                    scene.sendPointerEvent(PointerEventType.Move, position(Offset(65f, 75f)))
                    scene.sendPointerEvent(PointerEventType.Release, position(Offset(65f, 75f)))
                }
                awaitState { controller.hasUnsavedChanges && !controller.busy }
                withContext(Dispatchers.Main) {
                    render()
                    screenshot("symmetry-painting")
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy }
                val painted = saved!!
                val check = createNativeEngine(1, 1)
                try {
                    check.call(EngineOperation.LOAD, painted)
                    val image =
                        ImageIO.read(
                            ByteArrayInputStream(
                                check.call(
                                    EngineOperation.EXPORT_IMAGE,
                                    """{"format":"png","transparent":true}""".encodeToByteArray(),
                                )
                            )
                        )
                    for ((x, y) in listOf(50 to 54, 269 to 54, 50 to 185, 269 to 185)) assertTrue(
                        (image.getRGB(x, y) ushr 24) > 0,
                        "Missing reflected stroke at $x,$y",
                    )
                    assertEquals(
                        0,
                        image.getRGB(160, 20) ushr 24,
                        "Guide was exported into the artwork",
                    )
                } finally {
                    check.close()
                }
                val beforeUndo =
                    withContext(Dispatchers.Main) {
                        controller.document.revision.also { controller.command("undo") }
                    }
                awaitState { controller.document.revision > beforeUndo }
                withContext(Dispatchers.Main) {
                    saved = null
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy }
                assertContentEquals(project, saved)
                withContext(Dispatchers.Main) {
                    controller.symmetry = controller.symmetry.copy(guides = false)
                    controller.tool = Tool.Smudge
                    render()
                    assertFalse(scene.hasInvalidations())
                    controller.file(StudioController.FileAction.Open)
                }
                awaitState { controller.symmetry.mode == SymmetryMode.Off && !controller.busy }
            } finally {
                withContext(Dispatchers.Main) {
                    scene.close()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
