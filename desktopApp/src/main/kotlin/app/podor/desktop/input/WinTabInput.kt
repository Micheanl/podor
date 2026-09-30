package app.podor.desktop.input

import app.podor.desktop.WindowApi
import app.podor.domain.StudioDefaults
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path

internal class WinTabInput(
    window: Window,
    private val user: WindowApi,
    private val keys: WindowsPointerApi,
    private val api: WinTabApi,
    private val context: Pointer,
) : AutoCloseable {
    private val handle = Native.getWindowPointer(window)
    private val dispatcher = AwtPointerDispatcher(window)
    private val state = WinTabPenState(dispatcher::offer)
    private val packets = Memory((WinTab.PACKET_BYTES * StudioDefaults.maxBatchSamples).toLong())
    private val origin = Memory(8)
    private val cursors = mutableMapOf<Int, WinTabCursor>()
    val inRange: Boolean
        get() = state.inRange

    fun message(message: Int, wParam: Long, lParam: Long): Boolean {
        if (message == 0x001F || (message == 0x001C && wParam == 0L)) state.proximity(false)
        if (message == 0x001C) {
            api.WTEnable(context, wParam != 0L)
            if (wParam != 0L) {
                cursors.clear()
                api.WTOverlap(context, true)
            }
        }
        if (message == WinTab.PROXIMITY && wParam == Pointer.nativeValue(context)) {
            state.proximity(lParam and 0xffff != 0L)
            return true
        }
        if (message != WinTab.MESSAGE_BASE || lParam != Pointer.nativeValue(context)) return false
        val count =
            api.WTPacketsGet(context, StudioDefaults.maxBatchSamples, packets)
                .coerceIn(0, StudioDefaults.maxBatchSamples)
        origin.setLong(0, 0)
        if (!user.ScreenToClient(handle, origin)) {
            state.proximity(false)
            return true
        }
        val scale = user.GetDpiForWindow(handle).coerceAtLeast(96) / 96f
        val modifiers =
            (if (keys.GetKeyState(0x10) < 0) InputEvent.SHIFT_DOWN_MASK else 0) or
                (if (keys.GetKeyState(0x11) < 0) InputEvent.CTRL_DOWN_MASK else 0) or
                (if (keys.GetKeyState(0x12) < 0) InputEvent.ALT_DOWN_MASK else 0)
        repeat(count) { index ->
            val offset = (index * WinTab.PACKET_BYTES).toLong()
            val id = packets.getInt(offset + 4)
            val cursor = cursors[id] ?: readCursor(id)?.also { cursors[id] = it }
            if (cursor == null) {
                state.proximity(false)
                return true
            }
            state.packet(
                id,
                packets.getInt(offset),
                packets.getInt(offset + 8),
                packets.getInt(offset + 20),
                cursor,
                (packets.getInt(offset + 12).toFloat() + origin.getInt(0)) / scale,
                (packets.getInt(offset + 16).toFloat() + origin.getInt(4)) / scale,
                modifiers,
            )
        }
        return true
    }

    private fun readCursor(id: Int): WinTabCursor? {
        Memory(32).use { data ->
            if (api.WTInfoW(1, 4, data) != 4) return null
            val devices = data.getInt(0).coerceIn(0, StudioDefaults.nativeTabletDevices)
            repeat(devices) { device ->
                if (api.WTInfoW(100 + device, 4, data) != 4) return@repeat
                val first = data.getInt(0)
                if (api.WTInfoW(100 + device, 3, data) != 4) return@repeat
                if (id < first || id.toLong() >= first.toLong() + data.getInt(0)) return@repeat
                if (api.WTInfoW(100 + device, 15, data) != 16) return null
                val minimum = data.getInt(0)
                val maximum = data.getInt(4)
                if (maximum <= minimum) return null
                var tipMask = 0
                if (api.WTInfoW(200 + id, 9, data) == 1) {
                    val physical = data.getByte(0).toInt() and 0xff
                    val size = api.WTInfoW(200 + id, 7, null)
                    if (
                        size in 1..32 && physical < size && api.WTInfoW(200 + id, 7, data) == size
                    ) {
                        val logical = data.getByte(physical.toLong()).toInt() and 0xff
                        if (logical < 32) tipMask = 1 shl logical
                    }
                }
                return WinTabCursor(minimum, maximum, tipMask)
            }
        }
        return null
    }

    override fun close() {
        api.WTEnable(context, false)
        api.WTClose(context)
        dispatcher.close()
        packets.close()
        origin.close()
    }

    companion object {
        fun open(window: Window, user: WindowApi, keys: WindowsPointerApi): WinTabInput? {
            val root = System.getenv("SystemRoot") ?: return null
            val library = Path.of(root, "System32", "Wintab32.dll")
            if (!Files.isRegularFile(library)) return null
            return try {
                val api = Native.load(library.toString(), WinTabApi::class.java)
                val config = WinTabContext()
                if (api.WTInfoW(4, 0, config.pointer) != config.size()) return null
                config.read()
                if (config.systemWidth <= 0 || config.systemHeight <= 0) return null
                config.options = config.options or 4
                config.messageBase = WinTab.MESSAGE_BASE
                config.packetData = WinTab.PACKET_DATA
                config.packetMode = 0
                config.moveMask = WinTab.PACKET_DATA
                config.buttonUpMask = config.buttonDownMask
                config.outputX = config.systemX
                config.outputY = config.systemY
                config.outputWidth = config.systemWidth
                config.outputHeight = -config.systemHeight
                config.write()
                val context =
                    api.WTOpenW(Native.getWindowPointer(window), config.pointer, false)
                        ?: return null
                try {
                    api.WTQueueSizeSet(context, StudioDefaults.maxBatchSamples)
                    if (!api.WTEnable(context, true)) {
                        api.WTClose(context)
                        return null
                    }
                    api.WTOverlap(context, true)
                    WinTabInput(window, user, keys, api, context)
                } catch (error: Throwable) {
                    api.WTClose(context)
                    throw error
                }
            } catch (_: UnsatisfiedLinkError) {
                null
            }
        }
    }
}
