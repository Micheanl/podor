package app.podor.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.domain.Language
import app.podor.ui.StudioTheme
import com.sun.jna.Memory
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class WindowsChromeTest {
    @Test
    fun titleHitTestingKeepsControlsClickableAndSupportsResizingAtBothScales() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            fun hit(x: Int, y: Int, maximized: Boolean = false) =
                WindowHit.at(
                    (x * scale).toInt(),
                    (y * scale).toInt(),
                    (800 * scale).toInt(),
                    (600 * scale).toInt(),
                    scale,
                    maximized,
                )
            assertEquals(WindowHit.CAPTION, hit(200, 20))
            assertEquals(WindowHit.CLIENT, hit(770, 20))
            assertEquals(WindowHit.CLIENT, hit(200, 80))
            assertEquals(WindowHit.TOP_LEFT, hit(1, 1))
            assertEquals(WindowHit.BOTTOM_RIGHT, hit(799, 599))
            assertEquals(WindowHit.LEFT, hit(1, 300))
            assertEquals(WindowHit.CAPTION, hit(1, 1, true))
            assertEquals(WindowHit.CLIENT, hit(799, 599, true))
        }
    }

    @Test
    fun customButtonsMinimizeToggleMaximizeAndRequestClose() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                var minimized = false
                var closed = false
                val maximized = mutableStateOf(false)
                val scene =
                    ImageComposeScene(800, StudioTheme.windowTitleHeight.value.toInt()) {
                        WindowTitleBar(
                            Language.English,
                            maximized.value,
                            { minimized = true },
                            { maximized.value = !maximized.value },
                            { closed = true },
                        )
                    }
                try {
                    scene.render(0).close()
                    var frame = 0L
                    fun click(x: Float) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, 20f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, 20f))
                        frame += 16_666_667L
                        scene.render(frame).close()
                    }
                    click(800 - 46 * 2.5f)
                    assertTrue(minimized)
                    click(800 - 46 * 1.5f)
                    assertTrue(maximized.value)
                    click(800 - 46 * 1.5f)
                    assertFalse(maximized.value)
                    click(800 - 46 * 0.5f)
                    assertTrue(closed)
                    repeat(30) {
                        frame += 16_666_667L
                        scene.render(frame).close()
                    }
                    scene.render(frame + 16_666_667L).use { image ->
                        val output =
                            Path.of("build", "reports", "screenshots", "window-titlebar.png")
                        Files.createDirectories(output.parent)
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output, it.bytes)
                        }
                    }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                }
            }
        }

    @Test
    fun nativeWindowHasNoCaptionInsetAndKeepsDragAndResizeHitTargets() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val window =
                withContext(Dispatchers.Main) {
                    ComposeWindow().apply {
                        isUndecorated = true
                        focusableWindowState = false
                        setBounds(-3000, -2000, 800, 600)
                        val frame = this
                        setContent {
                            WindowsChrome(frame)
                            Column { WindowTitleBar(Language.English, false, {}, {}, {}) }
                        }
                        isVisible = true
                    }
                }
            try {
                delay(200)
                withContext(Dispatchers.Main) {
                    val api = Native.load("user32", WindowApi::class.java)
                    val handle = Native.getWindowPointer(window)
                    Memory(16).use { outer ->
                        Memory(16).use { client ->
                            assertTrue(api.GetWindowRect(handle, outer))
                            assertTrue(api.GetClientRect(handle, client))
                            val width = outer.getInt(8) - outer.getInt(0)
                            val height = outer.getInt(12) - outer.getInt(4)
                            assertEquals(width, client.getInt(8))
                            assertEquals(height, client.getInt(12))
                            val scale = api.GetDpiForWindow(handle) / 96f
                            fun hit(x: Int, y: Int): Long {
                                val screenX = outer.getInt(0) + (x * scale).toInt()
                                val screenY = outer.getInt(4) + (y * scale).toInt()
                                val position =
                                    ((screenY.toLong() and 0xffff) shl 16) or
                                        (screenX.toLong() and 0xffff)
                                return api.SendMessageW(handle, 0x0084, 0, position)
                            }
                            assertEquals(WindowHit.CAPTION.toLong(), hit(200, 20))
                            assertEquals(WindowHit.TOP_LEFT.toLong(), hit(1, 1))
                            assertEquals(
                                WindowHit.CLIENT.toLong(),
                                hit((width / scale).toInt() - 25, 20),
                            )
                            Memory(40).use { limits ->
                                api.SendMessageW(
                                    handle,
                                    0x0024,
                                    0,
                                    com.sun.jna.Pointer.nativeValue(limits),
                                )
                                assertTrue(limits.getInt(8) > 0 && limits.getInt(12) > 0)
                                assertTrue(limits.getInt(24) >= 400 && limits.getInt(28) >= 600)
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { window.dispose() }
            }
        }
}
