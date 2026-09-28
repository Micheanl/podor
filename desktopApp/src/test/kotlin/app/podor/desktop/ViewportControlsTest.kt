package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.domain.Language
import app.podor.domain.Viewport
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class ViewportControlsTest {
    @Test
    fun mirrorRotationAndFitControlsWorkWithoutIdleAnimation() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                for (language in Language.entries) {
                    val value = mutableStateOf(Viewport(1.5f, Offset(20f, -10f), 35f))
                    val scene =
                        ImageComposeScene(400, 500) {
                            PodorTheme(language) {
                                Box(
                                    Modifier.fillMaxSize().background(StudioTheme.background),
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    ViewportControls(value.value, "适合窗口") { value.value = it }
                                }
                            }
                        }
                    var frame = 0L
                    fun settle() {
                        repeat(30) { scene.render(frame++ * 16_666_667L).close() }
                    }
                    fun click(x: Float, y: Float) {
                        settle()
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        settle()
                    }
                    try {
                        settle()
                        click(334f, 478f)
                        assertTrue(value.value.mirrored)
                        assertEquals(35f, value.value.rotation)
                        click(280f, 478f)
                        val path =
                            Path.of("build/reports/screenshots/view-controls-${language.name}.png")
                        Files.createDirectories(path.parent)
                        scene.render(frame++ * 16_666_667L).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(path, it.bytes)
                            }
                        }
                        repeat(200) { scene.render(frame++ * 16_666_667L).close() }
                        assertFalse(scene.hasInvalidations())
                        click(90f, 378f)
                        assertEquals(20f, value.value.rotation)
                        click(274f, 378f)
                        assertEquals(35f, value.value.rotation)
                        scene.sendPointerEvent(PointerEventType.Press, Offset(130f, 336f))
                        scene.sendPointerEvent(PointerEventType.Move, Offset(235f, 336f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(235f, 336f))
                        settle()
                        assertTrue(value.value.rotation > 60f)
                        assertEquals(1.5f, value.value.zoom)
                        assertEquals(Offset(20f, -10f), value.value.pan)
                        assertTrue(value.value.mirrored)
                        click(180f, 378f)
                        assertEquals(0f, value.value.rotation)
                        click(20f, 20f)
                        click(378f, 478f)
                        assertEquals(Viewport(), value.value)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
}
