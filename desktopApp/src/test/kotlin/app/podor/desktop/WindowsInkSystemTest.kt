package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.desktop.input.PointerPenInfo
import app.podor.desktop.input.WindowsPointer
import app.podor.domain.PenEvent
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.CanvasWorkspace
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue

private interface SyntheticPenApi : StdCallLibrary {
    fun CreateSyntheticPointerDevice(type: Int, count: Int, feedback: Int): Pointer?

    fun InjectSyntheticPointerInput(device: Pointer, info: Pointer, count: Int): Boolean

    fun DestroySyntheticPointerDevice(device: Pointer)

    fun WindowFromPoint(point: Long): Pointer?

    fun GetAncestor(window: Pointer, flags: Int): Pointer?
}

@OptIn(ExperimentalComposeUiApi::class)
class WindowsInkSystemTest {
    @Test
    fun windowsInkDeliversPressureAndEraserWithoutPromotedMouseDuplicates() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_INK_SYSTEM_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(256, 192)
            val project =
                try {
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
            val user = Native.load("user32", WindowApi::class.java)
            val pen = Native.load("user32", SyntheticPenApi::class.java)
            var window: ComposeWindow? = null
            var device: Pointer? = null
            var chrome: NativeWindowChrome? = null
            val pressures = mutableListOf<Float?>()
            val erasers = mutableListOf<Boolean?>()
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.document.width == 256 && !controller.busy }
                val ui =
                    withContext(Dispatchers.Main) {
                        controller.brush =
                            controller.brush.copy(size = 32f, opacity = 1f, color = 0xFF0000FF)
                        ComposeWindow()
                            .apply {
                                isUndecorated = true
                                focusableWindowState = false
                                isAutoRequestFocus = false
                                opacity = 0.01f
                                val bounds =
                                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                                        .defaultScreenDevice
                                        .defaultConfiguration
                                        .bounds
                                setBounds(bounds.x + bounds.width - 500, bounds.y + 80, 480, 360)
                                setContent { CanvasWorkspace(controller, Modifier.fillMaxSize()) }
                                isAlwaysOnTop = true
                                isVisible = true
                            }
                            .also { window = it }
                    }
                delay(200)
                val handle =
                    withContext(Dispatchers.Main) {
                        chrome = NativeWindowChrome(ui).also { it.attachChildren() }
                        val target =
                            assertNotNull(SwingUtilities.getDeepestComponentAt(ui, 240, 180))
                        target.addMouseListener(
                            object : MouseAdapter() {
                                override fun mousePressed(event: MouseEvent) {
                                    pressures.add(
                                        (event as? PenEvent)?.penInput?.samples?.last()?.pressure
                                    )
                                    erasers.add((event as? PenEvent)?.penInput?.eraser)
                                }
                            }
                        )
                        ui.renderImmediately()
                        Native.getWindowPointer(ui)
                    }
                device = assertNotNull(pen.CreateSyntheticPointerDevice(WindowsPointer.PEN, 1, 3))
                Memory(8).use { origin ->
                    Memory(16).use { rect ->
                        origin.clear()
                        assertTrue(user.ScreenToClient(handle, origin))
                        assertTrue(user.GetClientRect(handle, rect))
                        val view = Size(rect.getInt(8).toFloat(), rect.getInt(12).toFloat())
                        suspend fun inject(
                            point: Offset,
                            pressure: Int,
                            pointerFlags: Int,
                            eraser: Boolean,
                        ) {
                            val local =
                                withContext(Dispatchers.Main) {
                                    controller.viewport.toView(point, view, controller.document)
                                }
                            val x = local.x.roundToInt() - origin.getInt(0)
                            val y = local.y.roundToInt() - origin.getInt(4)
                            val packed = (x.toLong() and 0xffffffffL) or (y.toLong() shl 32)
                            val target = assertNotNull(pen.WindowFromPoint(packed))
                            assertEquals(
                                handle,
                                pen.GetAncestor(target, 2),
                                "Injection left the test window",
                            )
                            val info =
                                PointerPenInfo().apply {
                                    pointerInfo.type = WindowsPointer.PEN
                                    pointerInfo.id = 1
                                    pointerInfo.pixel.x = x
                                    pointerInfo.pixel.y = y
                                    pointerInfo.flags = pointerFlags
                                    mask = WindowsPointer.PEN_PRESSURE
                                    this.pressure = pressure
                                    this.flags = if (eraser) WindowsPointer.PEN_INVERTED else 0
                                    write()
                                }
                            Memory(152).use { packet ->
                                packet.clear()
                                packet.setInt(0, WindowsPointer.PEN)
                                packet.write(
                                    8,
                                    info.pointer.getByteArray(0, info.size()),
                                    0,
                                    info.size(),
                                )
                                assertTrue(
                                    pen.InjectSyntheticPointerInput(device, packet, 1),
                                    "Native pen injection: ${Native.getLastError()}",
                                )
                            }
                            delay(30)
                        }
                        suspend fun dot(point: Offset, pressure: Int, eraser: Boolean = false) {
                            val revision =
                                withContext(Dispatchers.Main) { controller.document.revision }
                            inject(point, 0, 0x20000 or WindowsPointer.IN_RANGE, eraser)
                            inject(
                                point,
                                pressure,
                                0x10000 or WindowsPointer.IN_RANGE or WindowsPointer.IN_CONTACT,
                                eraser,
                            )
                            inject(point, pressure, 0x40000 or WindowsPointer.IN_RANGE, eraser)
                            inject(point, 0, 0x20000, false)
                            awaitState { controller.document.revision > revision }
                        }
                        suspend fun export(): ByteArray {
                            saved = null
                            withContext(Dispatchers.Main) {
                                controller.file(StudioController.FileAction.Save)
                            }
                            awaitState { saved != null && !controller.busy }
                            val copy = createNativeEngine(1, 1)
                            return try {
                                copy.call(EngineOperation.LOAD, saved!!)
                                copy.call(EngineOperation.EXPORT_IMAGE)
                            } finally {
                                copy.close()
                            }
                        }
                        dot(Offset(80f, 64f), 256)
                        dot(Offset(160f, 64f), 1024)
                        Image.makeFromEncoded(export()).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            val thin = (40..88).count { pixels[80, it].red < 0.1f }
                            val thick = (40..88).count { pixels[160, it].red < 0.1f }
                            assertTrue(
                                thin > 0 && thick > thin * 2,
                                "System pressure footprints: $thin/$thick",
                            )
                        }
                        dot(Offset(160f, 64f), 1024, true)
                        assertEquals(
                            listOf(0.25f, 1f, 1f),
                            withContext(Dispatchers.Main) { pressures.toList() },
                        )
                        assertEquals(
                            listOf(false, false, true),
                            withContext(Dispatchers.Main) { erasers.toList() },
                        )
                        Image.makeFromEncoded(export()).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            assertEquals(1f, pixels[160, 64].red, 0.01f)
                            assertEquals(0f, pixels[80, 64].red, 0.01f)
                        }
                    }
                }
            } finally {
                device?.let { pen.DestroySyntheticPointerDevice(it) }
                withContext(Dispatchers.Main) {
                    chrome?.close()
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
