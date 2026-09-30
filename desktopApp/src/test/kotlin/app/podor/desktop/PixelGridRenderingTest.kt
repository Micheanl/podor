package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class PixelGridRenderingTest {
    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val paper: MutableState<Color>,
    ) {
        val view = Size(680f, 560f)
        private var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun settle() {
            repeat(25) {
                withContext(Dispatchers.Main) { render().close() }
                delay(3)
            }
        }

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() && !controller.busy }) {
                    withContext(Dispatchers.Main) { render().close() }
                    delay(5)
                }
            }

        fun difference(point: Offset, background: Color): Float {
            val screen = controller.viewport.toView(point, view, controller.document)
            return render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                var greatest = 0f
                for (x in screen.x.roundToInt() - 1..screen.x.roundToInt() + 1) for (y in
                    screen.y.roundToInt() - 1..screen.y.roundToInt() + 1) {
                    val color = pixels[x, y]
                    greatest =
                        maxOf(
                            greatest,
                            abs(color.red - background.red) +
                                abs(color.green - background.green) +
                                abs(color.blue - background.blue),
                        )
                }
                greatest
            }
        }

        fun capture(name: String) {
            val file = Path.of("build/reports/screenshots/$name.png")
            Files.createDirectories(file.parent)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
            }
        }

        fun colors(points: List<Offset>): List<Int> =
            render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                points.map { point ->
                    val screen = controller.viewport.toView(point, view, controller.document)
                    pixels[screen.x.roundToInt(), screen.y.roundToInt()].toArgb()
                }
            }
    }

    private suspend fun withSession(
        workspace: Boolean = true,
        artwork: Boolean = false,
        block: suspend Session.() -> Unit,
    ) {
        NativeLoader.load()
        val native = createNativeEngine(64, 48)
        val project =
            try {
                if (artwork) {
                    fun command(value: String) =
                        native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                    command("""{"type":"fill","x":0,"y":0,"color":[225,25,50,255],"tolerance":0}""")
                    command(
                        """{"type":"select","rect":{"left":32,"top":0,"right":64,"bottom":48}}"""
                    )
                    command(
                        """{"type":"fill","x":40,"y":20,"color":[30,85,210,255],"tolerance":0}"""
                    )
                    command("""{"type":"select","rect":null}""")
                }
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Grid must never save artwork")

                override suspend fun readPreferences() =
                    Json.encodeToString(
                            Preferences(language = Language.English, appearance = Appearance.Light)
                        )
                        .encodeToByteArray()

                override suspend fun writePreferences(bytes: ByteArray) {}
            }
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val paper = mutableStateOf(Color.White)
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(680, 560) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        if (workspace) CanvasWorkspace(controller, Modifier.fillMaxSize())
                        else
                            Box(Modifier.fillMaxSize().background(paper.value)) {
                                PixelGridOverlay(
                                    controller,
                                    Size(680f, 560f),
                                    Modifier.fillMaxSize(),
                                )
                            }
                    }
                }
            }
        val session = Session(controller, scene, paper)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 64 && controller.document.height == 48 }
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
    fun pixelGridFollowsTheRealCanvasRotationAndMirroringWithoutChangingArtwork() = runBlocking {
        withSession {
            val originalFrame = controller.frame
            val originalDocument = controller.document
            withContext(Dispatchers.Main) {
                controller.changeCanvasGrid(CanvasGridSettings(pixels = true))
            }
            for (rotation in listOf(0f, 23f, -90f)) {
                for (mirrored in listOf(false, true)) {
                    withContext(Dispatchers.Main) {
                        controller.viewport = Viewport(rotation = rotation, mirrored = mirrored)
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        assertTrue(
                            difference(Offset(20f, 16.5f), Color.White) > 0.03f,
                            "Missing pixel line at $rotation / $mirrored",
                        )
                        assertEquals(0f, difference(Offset(20.5f, 16.5f), Color.White), 0.01f)
                        assertFalse(scene.hasInvalidations())
                    }
                }
            }
            withContext(Dispatchers.Main) {
                assertSame(originalFrame, controller.frame)
                assertEquals(originalDocument, controller.document)
                assertFalse(controller.document.canUndo)
                controller.viewport = Viewport()
                controller.changeCanvasGrid(
                    CanvasGridSettings(pixels = true, tiles = true, tileWidth = 8, tileHeight = 8)
                )
            }
            settle()
            withContext(Dispatchers.Main) { capture("pixel-grid-light") }
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences.copy(appearance = Appearance.Dark)
                )
            }
            settle()
            withContext(Dispatchers.Main) {
                assertTrue(difference(Offset(20f, 16.5f), Color.White) > 0.03f)
                capture("pixel-grid-dark")
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun rectangularTilesRemainVisibleWhenPixelLinesAreTooDenseAndDisappearWhenDisabled() =
        runBlocking {
            withSession(workspace = false) {
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(zoom = 0.5f, rotation = 31f, mirrored = true)
                    controller.changeCanvasGrid(CanvasGridSettings(pixels = true))
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(0f, difference(Offset(20f, 16.5f), Color.White), 0.01f)
                    controller.changeCanvasGrid(
                        CanvasGridSettings(
                            pixels = true,
                            tiles = true,
                            tileWidth = 8,
                            tileHeight = 4,
                        )
                    )
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(difference(Offset(24f, 18f), Color.White) > 0.05f)
                    assertTrue(difference(Offset(20f, 20f), Color.White) > 0.05f)
                    assertEquals(0f, difference(Offset(20f, 18f), Color.White), 0.01f)
                    assertEquals(0f, difference(Offset(-4f, 18f), Color.White), 0.01f)
                    paper.value = Color(0xFF17171B)
                    controller.updatePreferences(
                        controller.preferences.copy(appearance = Appearance.Dark)
                    )
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(difference(Offset(24f, 18f), paper.value) > 0.03f)
                    assertEquals(0f, difference(Offset(20f, 18f), paper.value), 0.01f)
                    controller.changeCanvasGrid(CanvasGridSettings())
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertEquals(0f, difference(Offset(24f, 18f), paper.value), 0.01f)
                    assertEquals(0f, difference(Offset(20f, 20f), paper.value), 0.01f)
                    assertFalse(scene.hasInvalidations())
                    assertFalse(controller.document.canUndo)
                }
            }
        }

    @Test
    fun pixelBrushesAndPixelGridDisplayNearestPixelsWithoutInventingEdgeColors() = runBlocking {
        withSession(artwork = true) {
            val expected = setOf(0xFFE11932.toInt(), 0xFF1E55D2.toInt())
            val edge = (-4..4).map { Offset(32f + it / 10f, 16.5f) }
            withContext(Dispatchers.Main) {
                assertTrue(
                    colors(edge).any { it !in expected },
                    "Normal drawing keeps smooth image scaling",
                )
            }
            val frame = controller.frame
            val document = controller.document
            for (preset in listOf(BrushPreset.PixelPencil, BrushPreset.PixelPerfect)) {
                withContext(Dispatchers.Main) {
                    controller.brush = controller.brush.copy(preset = preset)
                    controller.viewport = Viewport(zoom = 1.3f, rotation = 17f, mirrored = true)
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(
                        colors(edge).all { it in expected },
                        "$preset introduces interpolated edge colors",
                    )
                }
            }
            withContext(Dispatchers.Main) {
                controller.brush = controller.brush.copy(preset = BrushPreset.Marker)
                controller.viewport = Viewport()
                controller.changeCanvasGrid(CanvasGridSettings(pixels = true))
            }
            settle()
            withContext(Dispatchers.Main) {
                assertTrue(
                    colors(listOf(Offset(31.75f, 16.5f), Offset(32.25f, 16.5f))).all {
                        it in expected
                    },
                    "Pixel grid display must preserve color blocks between the grid lines",
                )
                assertSame(frame, controller.frame)
                assertEquals(document, controller.document)
            }
        }
    }
}
