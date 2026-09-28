package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.desktop.input.PointerInfo
import app.podor.desktop.input.WindowsPointer
import app.podor.domain.TouchEvent
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import app.podor.ui.input.nativeTouchGuard
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
import org.junit.Assume.assumeTrue

private interface SyntheticTouchApi : StdCallLibrary {
    fun CreateSyntheticPointerDevice(type: Int, count: Int, feedback: Int): Pointer?

    fun InjectSyntheticPointerInput(device: Pointer, info: Pointer, count: Int): Boolean

    fun DestroySyntheticPointerDevice(device: Pointer)

    fun WindowFromPoint(point: Long): Pointer?

    fun GetAncestor(window: Pointer, flags: Int): Pointer?

    fun SendMessageW(window: Pointer, message: Int, wParam: Long, lParam: Long): Long
}

@OptIn(ExperimentalComposeUiApi::class)
class WindowsTouchSystemTest {
    private data class Contact(val id: Int, val position: Offset, val phase: Int)

    @Test
    fun nativeTouchTapsDrawsAndTransformsWithoutStrayPaintOrButtonClicks() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_INK_SYSTEM_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(256, 192)
            val original =
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            var saved: ByteArray? = null
            val files =
                object : ProjectFiles {
                    override suspend fun open() = original

                    override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
                        saved = bytes
                        return true
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            val user = Native.load("user32", WindowApi::class.java)
            val api = Native.load("user32", SyntheticTouchApi::class.java)
            var window: ComposeWindow? = null
            var chrome: NativeWindowChrome? = null
            var device: Pointer? = null
            var clicks = 0
            var promotedPresses = 0
            var cancelledEvents = 0
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            suspend fun save(): ByteArray {
                withContext(Dispatchers.Main) {
                    saved = null
                    controller.file(StudioController.FileAction.Save)
                }
                awaitState { saved != null && !controller.busy }
                return saved!!
            }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState { controller.document.width == 256 && !controller.busy }
                val ui =
                    withContext(Dispatchers.Main) {
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
                                setContent {
                                    PodorTheme {
                                        Box(Modifier.fillMaxSize().nativeTouchGuard()) {
                                            CanvasWorkspace(controller, Modifier.fillMaxSize())
                                            Box(Modifier.align(Alignment.TopEnd)) {
                                                ToolButton(Glyph.Plus, "新建图层") { clicks++ }
                                            }
                                        }
                                    }
                                }
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
                                    if (event !is TouchEvent) promotedPresses++
                                }
                            }
                        )
                        target.addMouseMotionListener(
                            object : MouseAdapter() {
                                override fun mouseDragged(event: MouseEvent) {
                                    if ((event as? TouchEvent)?.touchInput?.cancelled == true)
                                        cancelledEvents++
                                }
                            }
                        )
                        ui.renderImmediately()
                        Native.getWindowPointer(ui)
                    }
                device = assertNotNull(api.CreateSyntheticPointerDevice(WindowsPointer.TOUCH, 4, 3))
                Memory(8).use { origin ->
                    Memory(16).use { rect ->
                        origin.clear()
                        assertTrue(user.ScreenToClient(handle, origin))
                        assertTrue(user.GetClientRect(handle, rect))
                        val view = Size(rect.getInt(8).toFloat(), rect.getInt(12).toFloat())
                        val down = 0x10000
                        val update = 0x20000
                        val up = 0x40000
                        suspend fun inject(vararg contacts: Contact) {
                            Memory(152L * contacts.size).use { packets ->
                                packets.clear()
                                contacts.forEachIndexed { index, contact ->
                                    val x = contact.position.x.roundToInt() - origin.getInt(0)
                                    val y = contact.position.y.roundToInt() - origin.getInt(4)
                                    val target =
                                        assertNotNull(
                                            api.WindowFromPoint(
                                                (x.toLong() and 0xffffffffL) or (y.toLong() shl 32)
                                            )
                                        )
                                    assertEquals(
                                        handle,
                                        api.GetAncestor(target, 2),
                                        "Touch injection left the test window",
                                    )
                                    val info =
                                        PointerInfo().apply {
                                            type = WindowsPointer.TOUCH
                                            id = contact.id
                                            pixel.x = x
                                            pixel.y = y
                                            flags =
                                                contact.phase or
                                                    if (contact.phase and up != 0) 0
                                                    else
                                                        WindowsPointer.IN_RANGE or
                                                            WindowsPointer.IN_CONTACT
                                            write()
                                        }
                                    val base = index * 152L
                                    packets.setInt(base, WindowsPointer.TOUCH)
                                    packets.write(
                                        base + 8,
                                        info.pointer.getByteArray(0, info.size()),
                                        0,
                                        info.size(),
                                    )
                                }
                                assertTrue(
                                    api.InjectSyntheticPointerInput(device, packets, contacts.size),
                                    "Touch injection error ${Native.getLastError()}",
                                )
                            }
                            delay(35)
                        }
                        val first = Offset(view.width * 0.32f, view.height * 0.45f)
                        val second = Offset(view.width * 0.64f, view.height * 0.45f)
                        withContext(Dispatchers.Main) { controller.fingerDrawing = false }
                        inject(Contact(1, first, down))
                        inject(Contact(1, first + Offset(30f, 0f), update))
                        inject(Contact(1, first + Offset(30f, 0f), up))
                        assertContentEquals(
                            original,
                            save(),
                            "Disabled finger drawing changed pixels",
                        )
                        withContext(Dispatchers.Main) { controller.fingerDrawing = true }
                        inject(Contact(1, first, down))
                        inject(Contact(1, first + Offset(30f, 0f), update))
                        inject(Contact(1, first + Offset(30f, 0f), up))
                        val painted = save()
                        assertFalse(
                            original.contentEquals(painted),
                            "Single-finger painting was lost",
                        )
                        val cancelled = first + Offset(0f, 60f)
                        withContext(Dispatchers.Main) { controller.references.visible = true }
                        inject(Contact(1, cancelled, down))
                        inject(Contact(1, cancelled + Offset(25f, 0f), update))
                        api.SendMessageW(handle, 0x001F, 0, 0)
                        inject(Contact(1, cancelled + Offset(25f, 0f), up))
                        assertTrue(
                            withContext(Dispatchers.Main) { cancelledEvents > 0 },
                            "System cancellation metadata missing",
                        )
                        assertTrue(
                            painted.contentEquals(save()),
                            "System cancellation kept an unfinished touch stroke",
                        )
                        val start = withContext(Dispatchers.Main) { controller.viewport }
                        inject(Contact(1, first, down))
                        inject(Contact(1, first, update), Contact(2, second, down))
                        val nextFirst = first + Offset(12f, -30f)
                        val nextSecond = second + Offset(40f, 40f)
                        inject(Contact(1, nextFirst, update), Contact(2, nextSecond, update))
                        awaitState { controller.viewport.zoom > start.zoom * 1.1f }
                        val transformed = withContext(Dispatchers.Main) { controller.viewport }
                        for ((from, to) in listOf(first to nextFirst, second to nextSecond)) {
                            val actual =
                                withContext(Dispatchers.Main) {
                                    transformed.toView(
                                        start.toDocument(from, view, controller.document),
                                        view,
                                        controller.document,
                                    )
                                }
                            assertEquals(to.x, actual.x, 2f)
                            assertEquals(to.y, actual.y, 2f)
                        }
                        inject(Contact(1, nextFirst, up), Contact(2, nextSecond, update))
                        inject(Contact(2, nextSecond + Offset(20f, 20f), update))
                        inject(Contact(2, nextSecond + Offset(20f, 20f), up))
                        assertContentEquals(
                            painted,
                            save(),
                            "Gesture left a stroke or the last finger resumed painting",
                        )
                        assertEquals(
                            transformed,
                            withContext(Dispatchers.Main) { controller.viewport },
                        )
                        val scale = user.GetDpiForWindow(handle) / 96f
                        val button = Offset(view.width - 22f * scale, 22f * scale)
                        inject(Contact(1, button, down))
                        inject(Contact(1, button, up))
                        awaitState { clicks == 1 }
                        inject(Contact(1, button, down))
                        inject(Contact(1, button, update), Contact(2, second, down))
                        inject(Contact(1, button, up), Contact(2, second, update))
                        inject(Contact(2, second, up))
                        delay(100)
                        assertEquals(
                            1,
                            withContext(Dispatchers.Main) { clicks },
                            "Two-finger gesture activated a button",
                        )
                        assertEquals(0, withContext(Dispatchers.Main) { promotedPresses })
                        assertContentEquals(painted, save())
                    }
                }
            } finally {
                device?.let { api.DestroySyntheticPointerDevice(it) }
                withContext(Dispatchers.Main) {
                    chrome?.close()
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
