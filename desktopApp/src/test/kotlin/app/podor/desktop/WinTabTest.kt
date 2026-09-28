package app.podor.desktop

import app.podor.desktop.input.*
import app.podor.domain.Preferences
import app.podor.domain.TabletInputMode
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Frame
import java.awt.Panel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.serialization.json.Json

class WinTabTest {
    @Test
    fun contextUsesWindowsWcharAndPressureUsesTheDeviceRange() {
        val context = WinTabContext()
        assertEquals(212, context.size())
        context.packetData = WinTab.PACKET_DATA
        context.systemX = -1920
        context.systemHeight = 1440
        context.write()
        assertEquals(WinTab.PACKET_DATA, context.pointer.getInt(104))
        assertEquals(-1920, context.pointer.getInt(188))
        assertEquals(1440, context.pointer.getInt(200))
        for (maximum in listOf(511, 1023, 2047, 4095, 8191)) {
            val cursor = WinTabCursor(10, maximum, 1)
            assertEquals(0f, cursor.pressure(0))
            assertEquals(1f, cursor.pressure(maximum + 100))
            assertTrue(cursor.pressure(11) in 0f..0.01f)
        }
        assertEquals(
            TabletInputMode.Automatic,
            Json.decodeFromString<Preferences>("{}").tabletInputMode,
        )
        for (mode in TabletInputMode.entries) {
            val value = Preferences(tabletInputMode = mode)
            assertEquals(value, Json.decodeFromString<Preferences>(Json.encodeToString(value)))
        }
    }

    @Test
    fun tipButtonsEraserProximityAndOverflowKeepSeparateStrokes() {
        val frames = mutableListOf<WindowPointerFrame>()
        val state = WinTabPenState(frames::add)
        val cursor = WinTabCursor(0, 8191, 4)
        fun sample(buttons: Int, pressure: Int = 0, status: Int = 0, id: Int = 9) =
            state.packet(id, status, buttons, pressure, cursor, -32f, 64f, 0)
        sample(0)
        sample(4, 8)
        sample(5, 4096)
        sample(1)
        assertEquals(
            listOf(PointerPhase.Hover, PointerPhase.Down, PointerPhase.Move, PointerPhase.Up),
            frames.map { it.phase },
        )
        assertTrue(frames[1].points.single().pressure < 0.002f)
        assertTrue(frames[2].barrel)
        assertFalse(frames[1].barrel)
        frames.clear()
        sample(4, 8191, 0x10)
        state.proximity(false)
        assertEquals(
            listOf(PointerPhase.Down, PointerPhase.Cancel, PointerPhase.Leave),
            frames.map { it.phase },
        )
        assertTrue(frames.all { it.eraser })
        assertFalse(state.inRange)
        frames.clear()
        sample(4, 100)
        sample(4, 200, 2)
        sample(4, 300)
        sample(0)
        sample(4, 10)
        sample(4, 20, id = 10)
        assertEquals(
            listOf(
                PointerPhase.Down,
                PointerPhase.Cancel,
                PointerPhase.Down,
                PointerPhase.Cancel,
                PointerPhase.Down,
            ),
            frames.map { it.phase },
        )
    }

    @Test
    fun queueDoesNotMergeSideButtonOrKeyboardTransitions() {
        val queue = PointerEventQueue()
        val frame =
            WindowPointerFrame(PointerPhase.Move, 1, listOf(WindowPointerPoint(0f, 0f, 1f)), false)
        queue.offer(frame)
        queue.offer(frame.copy(barrel = true))
        queue.offer(frame.copy(barrel = true, modifiers = 512))
        assertEquals(3, queue.size)
        assertFalse(queue.poll()!!.barrel)
        assertTrue(queue.poll()!!.barrel)
        assertEquals(512, queue.poll()!!.modifiers)
    }

    @Test
    fun leavingTheTabletContextCancelsWithoutPaintingAnOutsidePacket() {
        val frames = mutableListOf<WindowPointerFrame>()
        val state = WinTabPenState(frames::add)
        val cursor = WinTabCursor(0, 1023, 1)
        state.packet(1, 0, 1, 300, cursor, 10f, 20f, 0)
        state.packet(1, 1, 1, 300, cursor, 9000f, 9000f, 0)
        assertEquals(
            listOf(PointerPhase.Down, PointerPhase.Cancel, PointerPhase.Leave),
            frames.map { it.phase },
        )
        assertTrue(frames.all { it.points.single().x == 10f })
        assertFalse(state.inRange)
    }

    @Test
    fun nativePacketBatchReachesAwtWithMappedTipAndPressureAndCancelsOnFocusLoss() {
        val events = mutableListOf<PenMouseEvent>()
        val api = FakeWinTab()
        val user = Native.load("user32", WindowApi::class.java)
        val context = Pointer(123)
        lateinit var window: Frame
        lateinit var input: WinTabInput
        SwingUtilities.invokeAndWait {
            window =
                Frame().apply {
                    isUndecorated = true
                    focusableWindowState = false
                    setBounds(-3000, -2000, 300, 200)
                    add(
                        Panel().apply {
                            val listener =
                                object : MouseAdapter() {
                                    override fun mousePressed(e: MouseEvent) {
                                        if (e is PenMouseEvent) events.add(e)
                                    }

                                    override fun mouseDragged(e: MouseEvent) {
                                        if (e is PenMouseEvent) events.add(e)
                                    }

                                    override fun mouseReleased(e: MouseEvent) {
                                        if (e is PenMouseEvent) events.add(e)
                                    }
                                }
                            addMouseListener(listener)
                            addMouseMotionListener(listener)
                        }
                    )
                    isVisible = true
                }
            input = WinTabInput(window, user, FakeKeys(), api, context)
        }
        try {
            Memory(8).use { origin ->
                var x = 0
                var y = 0
                SwingUtilities.invokeAndWait {
                    window.validate()
                    val handle = Native.getWindowPointer(window)
                    origin.clear()
                    assertTrue(user.ScreenToClient(handle, origin))
                    val scale = user.GetDpiForWindow(handle) / 96f
                    x = (50 * scale).toInt() - origin.getInt(0)
                    y = (60 * scale).toInt() - origin.getInt(4)
                    assertIs<Panel>(SwingUtilities.getDeepestComponentAt(window, 50, 60))
                }
                api.packets =
                    listOf(
                        intArrayOf(0, 9, 4, x, y, 18),
                        intArrayOf(0, 9, 5, x + 20, y, 4106),
                        intArrayOf(0, 9, 0, x + 30, y, 10),
                    )
                SwingUtilities.invokeAndWait {
                    assertTrue(input.message(WinTab.MESSAGE_BASE, 1, 123))
                }
                SwingUtilities.invokeAndWait {}
                assertTrue(events.first().penInput.samples.single().pressure < 0.002f)
                assertTrue(
                    events.any { it.penInput.barrel && it.penInput.samples.last().pressure == 0.5f }
                )
                assertEquals(MouseEvent.MOUSE_RELEASED, events.last().id)
                assertEquals(1, api.pressureReads)
                events.clear()
                api.packets = listOf(intArrayOf(0x10, 9, 4, x, y, 8202))
                SwingUtilities.invokeAndWait {
                    input.message(WinTab.MESSAGE_BASE, 2, 123)
                    input.message(0x001C, 0, 0)
                }
                SwingUtilities.invokeAndWait {}
                assertTrue(events.any { it.penInput.eraser && it.penInput.cancelled })
                assertFalse(input.inRange)
                assertFalse(api.enabled)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                input.close()
                window.dispose()
            }
        }
        assertTrue(api.closed)
    }

    private class FakeWinTab : WinTabApi {
        var packets = emptyList<IntArray>()
        var pressureReads = 0
        var enabled = true
        var closed = false

        override fun WTInfoW(category: Int, index: Int, output: Pointer?): Int {
            when (category to index) {
                1 to 4 -> output!!.setInt(0, 1)
                100 to 4 -> output!!.setInt(0, 8)
                100 to 3 -> output!!.setInt(0, 2)
                100 to 15 -> {
                    pressureReads++
                    output!!.setInt(0, 10)
                    output.setInt(4, 8202)
                    return 16
                }
                209 to 9 -> {
                    output!!.setByte(0, 0)
                    return 1
                }
                209 to 7 -> {
                    output?.setByte(0, 2)
                    return 32
                }
                else -> return 0
            }
            return 4
        }

        override fun WTOpenW(window: Pointer, context: Pointer, enabled: Boolean): Pointer? = null

        override fun WTClose(context: Pointer): Boolean {
            closed = true
            return true
        }

        override fun WTEnable(context: Pointer, enabled: Boolean): Boolean {
            this.enabled = enabled
            return true
        }

        override fun WTOverlap(context: Pointer, top: Boolean) = true

        override fun WTQueueSizeSet(context: Pointer, count: Int) = true

        override fun WTPacketsGet(context: Pointer, count: Int, packets: Pointer): Int {
            val values = this.packets.take(count)
            values.forEachIndexed { index, packet ->
                packets.write((index * WinTab.PACKET_BYTES).toLong(), packet, 0, packet.size)
            }
            this.packets = this.packets.drop(values.size)
            return values.size
        }
    }

    private class FakeKeys : WindowsPointerApi {
        override fun GetPointerType(id: Int, type: IntByReference) = false

        override fun GetPointerInfo(id: Int, info: Pointer) = false

        override fun GetPointerPenInfo(id: Int, info: Pointer) = false

        override fun GetPointerPenInfoHistory(id: Int, count: IntByReference, info: Pointer) = false

        override fun GetKeyState(key: Int): Short = 0

        override fun GetMessageExtraInfo() = 0L
    }
}
