package app.podor.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import app.podor.data.ImageClipboard
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ClipboardRenderingTest {
    @Test
    fun clipboardShortcutsRespectTextFocusAndCustomBindingsAndTheMenuBecomesIdle() = runBlocking {
        NativeLoader.load()
        val engine = createNativeEngine(128, 96)
        val project =
            try {
                engine.call(
                    EngineOperation.COMMAND,
                    """{"type":"fill","x":0,"y":0,"color":[140,40,80,255],"tolerance":0}"""
                        .encodeToByteArray(),
                )
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
            }
        val imageClipboard =
            object : ImageClipboard {
                @Volatile var image: ClipboardImage? = null
                @Volatile var writes = 0

                override suspend fun read() = image

                override suspend fun write(image: ClipboardImage) {
                    this.image = image
                    writes++
                }
            }
        val textClipboard =
            object : Clipboard {
                var entry: ClipEntry? = null

                override suspend fun getClipEntry() = entry

                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    entry = clipEntry
                }
            }
        val files =
            object : ProjectFiles {
                override val clipboard = imageClipboard

                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Clipboard must not save")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1360, 900) {
                    CompositionLocalProvider(LocalClipboard provides textClipboard) {
                        StudioApp(controller)
                    }
                }
            }
        var frame = 0L
        fun render() = scene.render(frame++ * 16_666_667L)
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            repeat(35) { render().close() }
        }
        fun key(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(
                KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = true, isShiftPressed = shift)
            )
            scene.sendKeyEvent(
                KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = true, isShiftPressed = shift)
            )
            repeat(3) { render().close() }
        }
        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { predicate() }) delay(5)
            }
        try {
            waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            waitFor { controller.hasCanvas && !controller.busy }
            val before = withContext(Dispatchers.Main) { controller.document }
            withContext(Dispatchers.Main) {
                repeat(4) { render().close() }
                click(1158f, 194f)
                click(1175f, 586f)
                key(Key.A)
                key(Key.C)
                assertEquals(
                    "000000",
                    textClipboard.entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor),
                )
                key(Key.X)
                textClipboard.entry = ClipEntry(StringSelection("A17B23"))
                key(Key.V)
            }
            waitFor { controller.brush.color == 0xFFA17B23 }
            withContext(Dispatchers.Main) {
                assertEquals(0, imageClipboard.writes)
                assertEquals(before, controller.document)
                controller.tool = Tool.Hand
                click(750f, 650f)
                key(Key.C)
            }
            waitFor { imageClipboard.writes == 1 && !controller.busy }
            withContext(Dispatchers.Main) { key(Key.V) }
            waitFor { controller.document.layers.size == 2 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences.assign(ShortcutAction.Copy, Shortcut("P", true))
                )
                key(Key.P)
            }
            waitFor { imageClipboard.writes == 2 && !controller.busy }
            withContext(Dispatchers.Main) {
                controller.updatePreferences(
                    controller.preferences.assign(ShortcutAction.Eraser, Shortcut("C", true))
                )
                click(1175f, 586f)
                key(Key.A)
                key(Key.C)
                assertEquals(Tool.Hand, controller.tool)
                assertEquals(
                    "A17B23",
                    textClipboard.entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor),
                )
                click(750f, 650f)
                key(Key.C)
                assertEquals(Tool.Eraser, controller.tool)
                controller.tool = Tool.Hand
            }
            for ((index, language) in Language.entries.withIndex()) {
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(controller.preferences.copy(language = language))
                    click(241f, 32f)
                }
                delay(30)
                withContext(Dispatchers.Main) {
                    repeat(35) { render().close() }
                    assertFalse(scene.hasInvalidations())
                    Files.createDirectories(Path.of("build/reports/screenshots"))
                    render().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(
                                Path.of(
                                    "build/reports/screenshots/clipboard-${language.name.lowercase()}.png"
                                ),
                                it.bytes,
                            )
                        }
                    }
                    click(310f, 87f)
                }
                waitFor { imageClipboard.writes == index + 3 && !controller.busy }
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
