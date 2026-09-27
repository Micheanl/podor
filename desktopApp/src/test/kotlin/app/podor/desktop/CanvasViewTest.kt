package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class CanvasViewTest {
    private val view = Size(760f, 720f)
    private val colors =
        listOf(0xFF893A55, 0xFFE0A563, 0xFF568D77, 0xFF49698E, 0xFFA99AC0, 0xFFBFB69A)

    private fun project(tiled: Boolean): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(384, 256)
        try {
            if (tiled) {
                for (i in colors.indices) {
                    val x = i % 3 * 128
                    val y = i / 3 * 128
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"select","rect":{"left":$x,"top":$y,"right":${x+128},"bottom":${y+128}}}"""
                            .encodeToByteArray(),
                    )
                    val c = colors[i]
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":$x,"y":$y,"color":[${c shr 16 and 255},${c shr 8 and 255},${c and 255},255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                }
            }
            return engine.call(EngineOperation.SAVE)
        } finally {
            engine.close()
        }
    }

    private class FilesInMemory(val bytes: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null

        override suspend fun open() = bytes

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved = bytes
            return true
        }
    }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: FilesInMemory,
    ) {
        var frame = 0L

        suspend fun settle() =
            withContext(Dispatchers.Main) {
                repeat(3) { scene.render(frame++ * 16_666_667L).close() }
            }

        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
            }

        suspend fun mouse(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    type,
                    controller.viewport.toView(point, view, controller.document),
                )
            }

        suspend fun click(point: Offset) {
            mouse(PointerEventType.Press, point)
            mouse(PointerEventType.Release, point)
        }

        suspend fun snapshot(name: String) =
            withContext(Dispatchers.Main) {
                val path = Path.of("build/reports/screenshots/$name.png")
                Files.createDirectories(path.parent)
                scene.render(frame++ * 16_666_667L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) }
                }
            }

        suspend fun exported(): ByteArray {
            files.saved = null
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
            awaitState { files.saved != null && !controller.busy }
            val engine = createNativeEngine(1, 1)
            try {
                engine.call(EngineOperation.LOAD, assertNotNull(files.saved))
                return engine.call(EngineOperation.EXPORT_IMAGE)
            } finally {
                engine.close()
            }
        }
    }

    private suspend fun withCanvas(
        tiled: Boolean = false,
        artwork: ByteArray = project(tiled),
        block: suspend Session.() -> Unit,
    ) {
        val files = FilesInMemory(artwork)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        var scene: ImageComposeScene? = null
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        controller.document.revision > 0 &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                ) delay(10)
            }
            scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(920, 720) {
                        CanvasWorkspace(
                            controller,
                            Modifier.fillMaxSize().background(StudioTheme.background),
                            endInset = 160.dp,
                        )
                    }
                }
            val session = Session(controller, scene, files)
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene?.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun rotatedMirroredTilesRenderWithoutGapsOrPixelUpdates() =
        runBlocking<Unit> {
            withCanvas(tiled = true) {
                val originalFrame = withContext(Dispatchers.Main) { controller.frame }
                val originalDocument = withContext(Dispatchers.Main) { controller.document }
                for (angle in listOf(-125f, -90f, -35f, 0f, 37f, 90f)) {
                    for (mirrored in listOf(false, true)) {
                        withContext(Dispatchers.Main) {
                            controller.viewport = Viewport(2.3f, Offset(12f, -18f), angle, mirrored)
                        }
                        settle()
                        withContext(Dispatchers.Main) {
                            scene.render(frame++ * 16_666_667L).use { image ->
                                val pixels = image.toComposeImageBitmap().toPixelMap()
                                var sampled = 0
                                for (x in 9 until 920 step 17) for (y in 9 until 720 step 19) {
                                    val p =
                                        controller.viewport.toDocument(
                                            Offset(x + 0.5f, y + 0.5f),
                                            view,
                                            controller.document,
                                        )
                                    if (p.x !in 1f..383f || p.y !in 1f..255f) continue
                                    if (
                                        p.x % 128f < 1f ||
                                            p.x % 128f > 127f ||
                                            p.y % 128f < 1f ||
                                            p.y % 128f > 127f
                                    )
                                        continue
                                    val expected =
                                        Color(colors[p.y.toInt() / 128 * 3 + p.x.toInt() / 128])
                                    val actual = pixels[x, y]
                                    assertEquals(
                                        expected.red,
                                        actual.red,
                                        0.012f,
                                        "$angle/$mirrored at $p",
                                    )
                                    assertEquals(expected.green, actual.green, 0.012f)
                                    assertEquals(expected.blue, actual.blue, 0.012f)
                                    sampled++
                                }
                                assertTrue(sampled > 300)
                                for (edge in listOf(128f, 256f)) for (y in 2 until 254) {
                                    val seam =
                                        controller.viewport.toView(
                                            Offset(edge, y.toFloat()),
                                            view,
                                            controller.document,
                                        )
                                    if (seam.x !in 2f..917f || seam.y !in 2f..717f) continue
                                    val color = pixels[seam.x.toInt(), seam.y.toInt()]
                                    assertTrue(
                                        maxOf(color.red, color.green, color.blue) < 0.9f,
                                        "Tile seam at $seam, angle $angle",
                                    )
                                }
                            }
                            assertSame(originalFrame, controller.frame)
                            assertEquals(originalDocument, controller.document)
                            assertFalse(controller.hasUnsavedChanges)
                            assertFalse(scene.hasInvalidations())
                        }
                    }
                }
                snapshot("canvas-rotated-mirrored")
            }
        }

    @Test
    fun largeCanvasUsesHardwareTransformsWithoutUploadingNewPixels() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(4096, 4096)
            val artwork =
                try {
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[137,58,85,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            withCanvas(artwork = artwork) {
                val originalFrame = withContext(Dispatchers.Main) { controller.frame }
                val original = withContext(Dispatchers.Main) { controller.document }
                assertEquals(1024, originalFrame.tiles.size)
                val window =
                    withContext(Dispatchers.Main) {
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3000, -2000, 920, 720)
                            setContent {
                                CanvasWorkspace(
                                    controller,
                                    Modifier.fillMaxSize().background(StudioTheme.background),
                                    endInset = 160.dp,
                                )
                            }
                            isVisible = true
                        }
                    }
                try {
                    repeat(90) { index ->
                        withContext(Dispatchers.Main) {
                            controller.viewport =
                                Viewport(
                                    zoom = if (index < 45) 1.3f else 5.3f,
                                    rotation = index * 4f,
                                    mirrored = index % 30 < 15,
                                )
                        }
                        delay(16)
                        withContext(Dispatchers.Main) { window.renderImmediately() }
                    }
                    withContext(Dispatchers.Main) {
                        assertEquals(original, controller.document)
                        assertSame(originalFrame, controller.frame)
                        assertFalse(controller.hasUnsavedChanges)
                        val api = window.renderApi.toString()
                        assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                        val report = Path.of("build/reports/canvas-view-gpu.txt")
                        Files.createDirectories(report.parent)
                        Files.writeString(
                            report,
                            "Renderer: $api\n4096 x 4096, 1024 tiles, 90 rotation/mirror/zoom updates, original canvas pixel frame retained. This is not a full-app frame-rate benchmark.\n",
                        )
                    }
                    files.saved = null
                    withContext(Dispatchers.Main) {
                        controller.file(StudioController.FileAction.Save)
                    }
                    awaitState { files.saved != null && !controller.busy }
                    assertContentEquals(artwork, files.saved)
                } finally {
                    withContext(Dispatchers.Main) { window.dispose() }
                }
            }
        }

    @Test
    fun stylusPressurePickerSelectionAndFillUseTransformedCoordinates() =
        runBlocking<Unit> {
            withCanvas {
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(rotation = 57f, mirrored = true)
                    controller.brush =
                        controller.brush.copy(size = 24f, opacity = 1f, color = 0xFF0000FF)
                }
                settle()
                for ((point, pressure) in
                    listOf(Offset(96f, 96f) to 0.25f, Offset(192f, 96f) to 1f)) {
                    val revision =
                        withContext(Dispatchers.Main) {
                            val revision = controller.document.revision
                            val position =
                                controller.viewport.toView(point, view, controller.document)
                            for (pressed in listOf(true, false)) {
                                scene.sendPointerEvent(
                                    if (pressed) PointerEventType.Press
                                    else PointerEventType.Release,
                                    listOf(
                                        ComposeScenePointer(
                                            PointerId(1),
                                            position,
                                            pressed,
                                            PointerType.Stylus,
                                            pressure,
                                        )
                                    ),
                                )
                            }
                            revision
                        }
                    awaitState { controller.document.revision > revision }
                }
                Image.makeFromEncoded(exported()).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val thin = (75..117).count { pixels[96, it].red < 0.1f }
                    val thick = (75..117).count { pixels[192, it].red < 0.1f }
                    assertTrue(thin > 0 && thick > thin * 2, "Pressure footprint: $thin / $thick")
                    assertEquals(1f, pixels[96, 96].blue)
                    assertEquals(0f, pixels[96, 96].red, 0.01f)
                }
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.Picker
                    controller.brush = controller.brush.copy(color = 0xFF000000)
                }
                click(Offset(192f, 96f))
                awaitState { controller.brush.color == 0xFF0000FF }
                withContext(Dispatchers.Main) { controller.tool = Tool.Select }
                mouse(PointerEventType.Press, Offset(240.25f, 144.25f))
                mouse(PointerEventType.Move, Offset(300.75f, 200.75f))
                mouse(PointerEventType.Release, Offset(300.75f, 200.75f))
                awaitState { controller.document.selection != null }
                withContext(Dispatchers.Main) {
                    assertEquals(Selection(240, 144, 301, 201), controller.document.selection)
                    controller.tool = Tool.Fill
                    controller.brush = controller.brush.copy(color = 0xFF00AA66)
                }
                val revision = withContext(Dispatchers.Main) { controller.document.revision }
                click(Offset(270f, 170f))
                awaitState { controller.document.revision > revision }
                settle()
                snapshot("canvas-transformed-selection")
                Image.makeFromEncoded(exported()).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    assertEquals(170f / 255, pixels[270, 170].green, 0.01f)
                    assertEquals(1f, pixels[230, 170].red)
                    assertEquals(1f, pixels[270, 210].red)
                }
            }
        }

    @Test
    fun wheelAndTwoFingerGesturePreserveAnchorAndDoNotDraw() =
        runBlocking<Unit> {
            withCanvas {
                val original = withContext(Dispatchers.Main) { controller.document }
                withContext(Dispatchers.Main) {
                    fun touches(
                        type: PointerEventType,
                        a: Offset,
                        b: Offset,
                        pressed: Boolean = true,
                    ) {
                        scene.sendPointerEvent(
                            type,
                            listOf(
                                ComposeScenePointer(PointerId(2), a, pressed, PointerType.Touch),
                                ComposeScenePointer(PointerId(3), b, pressed, PointerType.Touch),
                            ),
                        )
                    }
                    val center = Offset(380f, 360f)
                    touches(
                        PointerEventType.Press,
                        center - Offset(100f, 0f),
                        center + Offset(100f, 0f),
                    )
                    assertTrue(
                        controller.viewport.pan.x.isFinite() &&
                            controller.viewport.pan.y.isFinite(),
                        "First simultaneous touch must not corrupt the viewport",
                    )
                    val moved = center + Offset(30f, -25f)
                    val arm = Offset(cos(PI.toFloat() / 6) * 120f, sin(PI.toFloat() / 6) * 120f)
                    touches(PointerEventType.Move, moved - arm, moved + arm)
                    assertEquals(1.2f, controller.viewport.zoom, 0.001f)
                    assertEquals(30f, controller.viewport.rotation, 0.001f)
                    assertEquals(30f, controller.viewport.pan.x, 0.001f)
                    assertEquals(-25f, controller.viewport.pan.y, 0.001f)
                    touches(PointerEventType.Release, moved - arm, moved + arm, false)
                    val anchor = Offset(221f, 279f)
                    val before = controller.viewport.toDocument(anchor, view, controller.document)
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        anchor,
                        scrollDelta = Offset(0f, -1f),
                        keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true),
                    )
                    assertEquals(45f, controller.viewport.rotation, 0.001f)
                    assertEquals(1.2f, controller.viewport.zoom, 0.001f)
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        anchor,
                        scrollDelta = Offset(0f, -1f),
                        keyboardModifiers = PointerKeyboardModifiers(),
                    )
                    assertEquals(1.344f, controller.viewport.zoom, 0.001f)
                    val after = controller.viewport.toDocument(anchor, view, controller.document)
                    assertEquals(before.x, after.x, 0.001f)
                    assertEquals(before.y, after.y, 0.001f)
                }
                delay(100)
                withContext(Dispatchers.Main) {
                    assertEquals(original, controller.document)
                    assertFalse(controller.hasUnsavedChanges)
                }
            }
        }
}
