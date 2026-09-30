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
import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class WindowsChromeTest {
    private fun clientRect(
        proposed: List<Int>,
        workArea: List<Int>?,
        maximized: Boolean = true,
        structureSize: Long = 16,
    ): List<Int> =
        Memory(structureSize).use { client ->
            Memory(40).use { monitor ->
                proposed.forEachIndexed { index, value -> client.setInt(index * 4L, value) }
                for (offset in 16 until structureSize.toInt() step 4) client.setInt(
                    offset.toLong(),
                    0x5A5A5A5A,
                )
                val window = Pointer(1)
                val targetMonitor = Pointer(2)
                var monitorRequests = 0
                val api =
                    Proxy.newProxyInstance(
                        WindowApi::class.java.classLoader,
                        arrayOf(WindowApi::class.java),
                    ) { _, method, arguments ->
                        when (method.name) {
                            "IsZoomed" -> {
                                assertEquals(window, arguments!![0])
                                maximized
                            }
                            "MonitorFromRect" -> {
                                assertEquals(
                                    proposed,
                                    List(4) { (arguments!![0] as Pointer).getInt(it * 4L) },
                                )
                                assertEquals(2, arguments!![1])
                                monitorRequests++
                                targetMonitor
                            }
                            "GetMonitorInfoW" -> {
                                assertEquals(targetMonitor, arguments!![0])
                                val info = arguments[1] as Pointer
                                assertEquals(40, info.getInt(0))
                                workArea?.forEachIndexed { index, value ->
                                    info.setInt(20 + index * 4L, value)
                                }
                                workArea != null
                            }
                            else -> error("Unexpected window API: ${method.name}")
                        }
                    } as WindowApi
                api.calculateClientArea(window, client, monitor)
                assertEquals(if (maximized) 1 else 0, monitorRequests)
                for (offset in 16 until structureSize.toInt() step 4) assertEquals(
                    0x5A5A5A5A,
                    client.getInt(offset.toLong()),
                )
                List(4) { client.getInt(it * 4L) }
            }
        }

    @Test
    fun maximizedClientsExcludeResizeFrameOverscanAndTaskbarsAtEachDpi() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val width = (1920 * scale).roundToInt()
            val height = (1080 * scale).roundToInt()
            val taskbar = (40 * scale).roundToInt()
            val frame = (8 * scale).roundToInt()
            for (workArea in
                listOf(
                    listOf(0, 0, width, height - taskbar),
                    listOf(0, taskbar, width, height),
                    listOf(taskbar, 0, width, height),
                    listOf(0, 0, width - taskbar, height),
                )) {
                val proposed =
                    listOf(
                        workArea[0] - frame,
                        workArea[1] - frame,
                        workArea[2] + frame,
                        workArea[3] + frame,
                    )
                for (size in listOf(16L, 48L + Native.POINTER_SIZE)) assertEquals(
                    workArea,
                    clientRect(proposed, workArea, structureSize = size),
                )
            }
        }
    }

    @Test
    fun maximizedClientsKeepNegativeSecondaryMonitorCoordinates() {
        for (workArea in
            listOf(
                listOf(-1920, 0, 0, 1040),
                listOf(-3840, -2160, -1920, -1120),
                listOf(-1080, -1920, 0, -40),
            )) {
            val proposed =
                listOf(workArea[0] - 12, workArea[1] - 12, workArea[2] + 12, workArea[3] + 12)
            assertEquals(workArea, clientRect(proposed, workArea))
        }
    }

    @Test
    fun floatingClientsKeepTheirFullUndecoratedArea() {
        val proposed = listOf(-3000, -2000, -2200, -1400)
        assertEquals(proposed, clientRect(proposed, listOf(0, 0, 1920, 1040), maximized = false))
    }

    @Test
    fun maximizedClientsAlreadyInsideTheWorkAreaDoNotGainInsets() {
        val workArea = listOf(0, 0, 1920, 1040)
        for (proposed in listOf(workArea, listOf(20, 30, 1300, 950))) assertEquals(
            proposed,
            clientRect(proposed, workArea),
        )
    }

    @Test
    fun missingMonitorInformationKeepsTheProposedClientArea() {
        val proposed = listOf(-8, -8, 1928, 1048)
        assertEquals(proposed, clientRect(proposed, null))
    }

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
                        opacity = 0f
                        isAutoRequestFocus = false
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
                            fun hit(x: Int, y: Int, target: Pointer = handle): Long {
                                val screenX = outer.getInt(0) + (x * scale).toInt()
                                val screenY = outer.getInt(4) + (y * scale).toInt()
                                val position =
                                    ((screenY.toLong() and 0xffff) shl 16) or
                                        (screenX.toLong() and 0xffff)
                                return api.SendMessageW(target, 0x0084, 0, position)
                            }
                            assertEquals(WindowHit.CAPTION.toLong(), hit(200, 20))
                            assertEquals(WindowHit.TOP_LEFT.toLong(), hit(1, 1))
                            assertEquals(
                                WindowHit.CLIENT.toLong(),
                                hit((width / scale).toInt() - 25, 20),
                            )
                            val children = mutableListOf<Pointer>()
                            api.EnumChildWindows(
                                handle,
                                object : WindowVisitor {
                                    override fun invoke(window: Pointer, data: Long): Boolean {
                                        children.add(window)
                                        return true
                                    }
                                },
                                0,
                            )
                            assertTrue(children.isNotEmpty())
                            for (child in children) {
                                assertEquals(-1L, hit(200, 20, child), "Child blocks title drag")
                                assertEquals(-1L, hit(1, 1, child), "Child blocks edge resize")
                                assertEquals(WindowHit.CLIENT.toLong(), hit(200, 80, child))
                                assertEquals(
                                    WindowHit.CLIENT.toLong(),
                                    hit((width / scale).toInt() - 25, 20, child),
                                )
                            }
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
                val api = Native.load("user32", WindowApi::class.java)
                val handle = withContext(Dispatchers.Main) { Native.getWindowPointer(window) }
                for (maximized in listOf(true, false)) {
                    assertTrue(api.PostMessageW(handle, 0x00A3, WindowHit.CAPTION.toLong(), 0))
                    assertTrue(api.PostMessageW(handle, 0x00A2, WindowHit.CAPTION.toLong(), 0))
                    withTimeoutOrNull(3_000) {
                        while (api.IsZoomed(handle) != maximized) delay(10)
                    }
                    assertEquals(maximized, api.IsZoomed(handle), "Native caption double-click")
                }
            } finally {
                withContext(Dispatchers.Main) { window.dispose() }
            }
        }
}
