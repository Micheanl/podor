package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.Language
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class ButtonRenderingTest {
    @Test
    fun captureLetterSwapPreview() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("PODOR_CAPTURE_BUTTONS") == "1")
        withContext(Dispatchers.Main) {
            val scene =
                ImageComposeScene(680, 260) {
                    Column(
                        Modifier.fillMaxSize().background(StudioTheme.background).padding(32.dp)
                    ) {
                        Text("podor", color = StudioTheme.text, fontSize = 24.sp)
                        Spacer(Modifier.height(20.dp))
                        for (language in listOf(Language.English, Language.Chinese)) {
                            PodorTheme(language) {
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    ActionButton("新建画布", {}, Modifier.width(280.dp))
                                    ActionButton(
                                        "打开作品",
                                        {},
                                        Modifier.width(280.dp),
                                        primary = false,
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            val directory = Path.of("build/reports/letter-swap")
            Files.createDirectories(directory)
            try {
                repeat(240) { frame ->
                    when (frame) {
                        30 -> scene.sendPointerEvent(PointerEventType.Move, Offset(150f, 104f))
                        85 -> scene.sendPointerEvent(PointerEventType.Move, Offset(460f, 104f))
                        140 -> scene.sendPointerEvent(PointerEventType.Move, Offset(150f, 170f))
                        195 -> scene.sendPointerEvent(PointerEventType.Move, Offset(670f, 250f))
                    }
                    scene.render(frame * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(directory.resolve("%03d.png".format(frame)), it.bytes)
                        }
                    }
                }
                assertFalse(scene.hasInvalidations())
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun nativeButtonsKeepClicksAndDisabledStateAndStopAnimatingAfterHover() = runBlocking {
        withContext(Dispatchers.Main) {
            var clicks = 0
            val scene =
                ImageComposeScene(820, 300) {
                    PodorTheme(Language.English) {
                        Column(
                            Modifier.fillMaxSize().background(StudioTheme.background).padding(32.dp)
                        ) {
                            Text("podor", color = StudioTheme.text, fontSize = 24.sp)
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                ActionButton(
                                    "新建画布",
                                    { clicks++ },
                                    Modifier.width(180.dp),
                                    glyph = Glyph.Plus,
                                )
                                ActionButton(
                                    "打开作品",
                                    {},
                                    Modifier.width(180.dp),
                                    glyph = Glyph.Folder,
                                    primary = false,
                                )
                                ActionButton(
                                    "导出图像",
                                    { clicks++ },
                                    Modifier.width(180.dp),
                                    enabled = false,
                                    glyph = Glyph.Export,
                                )
                            }
                            Spacer(Modifier.height(32.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ToolButton(Glyph.Brush, "画笔", selected = true) {}
                                ToolButton(Glyph.Eraser, "橡皮") {}
                                ToolButton(Glyph.Palette, "颜色") {}
                                ToolButton(Glyph.Save, "保存工程", prominent = true) {}
                            }
                        }
                    }
                }
            var frame = 0L
            fun render() = scene.render(frame++ * 16_666_667L)
            fun settle() {
                repeat(90) { render().close() }
            }
            fun capture(name: String) {
                val output = Path.of("build/reports/screenshots/controls-$name.png")
                Files.createDirectories(output.parent)
                render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output, it.bytes)
                    }
                }
            }
            try {
                settle()
                capture("idle")
                assertFalse(scene.hasInvalidations())
                scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 102f))
                repeat(18) { render().close() }
                capture("hover")
                settle()
                assertFalse(scene.hasInvalidations(), "Hover must not keep a render loop alive")
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 102f))
                repeat(5) { render().close() }
                capture("pressed")
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 102f))
                settle()
                assertEquals(1, clicks)
                scene.sendPointerEvent(PointerEventType.Press, Offset(500f, 102f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(500f, 102f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(800f, 280f))
                settle()
                assertEquals(1, clicks)
                assertFalse(scene.hasInvalidations())
            } finally {
                scene.close()
            }
        }
    }
}
