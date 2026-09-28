package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.Tool
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DrawingControlsTest {
    private suspend fun session(block: suspend (StudioController) -> Unit) {
        NativeLoader.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false
            }
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            block(controller)
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }

    @Test
    fun rightClickOpensCapsulesWithoutPaintingForBrushAndEraser() = runBlocking {
        session { controller ->
            for (tool in listOf(Tool.Brush, Tool.Eraser)) {
                withContext(Dispatchers.Main) {
                    controller.tool = tool
                    val scene =
                        ImageComposeScene(800, 650) {
                            PodorTheme { CanvasWorkspace(controller, Modifier.fillMaxSize()) }
                        }
                    try {
                        repeat(20) { scene.render(it * 16_666_667L).close() }
                        val revision = controller.document.revision
                        scene.sendPointerEvent(
                            PointerEventType.Press,
                            Offset(600f, 300f),
                            buttons = PointerButtons(isSecondaryPressed = true),
                            button = PointerButton.Secondary,
                        )
                        scene.sendPointerEvent(
                            PointerEventType.Release,
                            Offset(600f, 300f),
                            buttons = PointerButtons(),
                            button = PointerButton.Secondary,
                        )
                        repeat(40) { scene.render(1_000_000_000L + it * 16_666_667L).close() }
                        assertEquals(revision, controller.document.revision)
                        scene.sendPointerEvent(
                            PointerEventType.Press,
                            Offset(470f, if (tool == Tool.Eraser) 273f else 192f),
                        )
                        scene.sendPointerEvent(
                            PointerEventType.Release,
                            Offset(470f, if (tool == Tool.Eraser) 273f else 192f),
                        )
                        repeat(20) { scene.render(2_000_000_000L + it * 16_666_667L).close() }
                        val output =
                            Path.of("build/reports/screenshots/right-click-${tool.name}.png")
                        Files.createDirectories(output.parent)
                        scene.render(3_000_000_000L).use {
                            Files.write(output, it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                        assertEquals(revision, controller.document.revision)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    @Test
    fun zoomAndBrushSymbolsWorkWithoutEditingTheArtwork() = runBlocking {
        session { controller ->
            withContext(Dispatchers.Main) {
                val scene = ImageComposeScene(1000, 700) { StudioApp(controller) }
                try {
                    repeat(20) { scene.render(it * 16_666_667L).close() }
                    fun key(key: Key, command: Boolean = false, shift: Boolean = false) {
                        assertTrue(
                            scene.sendKeyEvent(
                                KeyEvent(
                                    key,
                                    KeyEventType.KeyDown,
                                    isCtrlPressed = command,
                                    isShiftPressed = shift,
                                )
                            )
                        )
                        scene.sendKeyEvent(
                            KeyEvent(
                                key,
                                KeyEventType.KeyUp,
                                isCtrlPressed = command,
                                isShiftPressed = shift,
                            )
                        )
                    }
                    val revision = controller.document.revision
                    key(Key.Equals, true, true)
                    assertEquals(1.2f, controller.viewport.zoom)
                    key(Key.Minus, true)
                    assertEquals(1f, controller.viewport.zoom, 0.0001f)
                    val size = controller.brush.size
                    key(Key.RightBracket)
                    assertTrue(controller.brush.size > size)
                    key(Key.LeftBracket)
                    assertEquals(size, controller.brush.size, 0.0001f)
                    assertEquals(revision, controller.document.revision)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun holdingLeftToolOpensQuickControlsAndHoldingValueFineTunesSize() = runBlocking {
        session { controller ->
            val initialSize = withContext(Dispatchers.Main) { controller.brush.size }
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(360, 600) {
                        PodorTheme {
                            Surface(color = StudioTheme.panel) {
                                Column(Modifier.width(280.dp).padding(16.dp)) {
                                    androidx.compose.material3.Text("Quick controls")
                                    LabeledSlider(
                                        "大小",
                                        controller.brush.size,
                                        1f..256f,
                                        "${controller.brush.size.toInt()} px",
                                    ) {
                                        controller.brush = controller.brush.copy(size = it)
                                    }
                                }
                            }
                        }
                    }
                }
            try {
                withContext(Dispatchers.Main) {
                    repeat(10) { scene.render(it * 16_666_667L).close() }
                    scene.sendPointerEvent(PointerEventType.Press, Offset(245f, 47f))
                }
                delay(650)
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(345f, 47f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(345f, 47f))
                    repeat(30) { scene.render(1_000_000_000L + it * 16_666_667L).close() }
                    assertEquals(initialSize + 42.5f, controller.brush.size, 0.01f)
                }
            } finally {
                withContext(Dispatchers.Main) { scene.close() }
            }
            assertEquals(
                initialSize + 42.5f,
                withContext(Dispatchers.Main) { controller.brush.size },
                0.01f,
            )
            val dock =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(800, 650) {
                        PodorTheme {
                            Surface(color = StudioTheme.background) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.TopStart,
                                ) {
                                    Column(Modifier.padding(top = 40.dp)) {
                                        StudioTools(controller)
                                    }
                                }
                            }
                        }
                    }
                }
            try {
                withContext(Dispatchers.Main) {
                    repeat(10) { dock.render(it * 16_666_667L).close() }
                    dock.sendPointerEvent(PointerEventType.Press, Offset(22f, 62f))
                }
                delay(650)
                withContext(Dispatchers.Main) {
                    dock.sendPointerEvent(PointerEventType.Release, Offset(22f, 62f))
                    repeat(40) { dock.render(1_000_000_000L + it * 16_666_667L).close() }
                    val output = Path.of("build/reports/screenshots/left-tool-controls.png")
                    Files.createDirectories(output.parent)
                    dock.render(2_000_000_000L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output, it.bytes)
                        }
                    }
                    repeat(20) { dock.render(4_000_000_000L + it * 16_666_667L).close() }
                    assertFalse(dock.hasInvalidations())
                }
            } finally {
                withContext(Dispatchers.Main) { dock.close() }
            }
        }
    }
}
