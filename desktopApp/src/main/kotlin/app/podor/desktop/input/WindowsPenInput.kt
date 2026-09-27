package app.podor.desktop.input

import app.podor.desktop.WindowApi
import app.podor.domain.StudioDefaults
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.awt.Window
import java.awt.event.InputEvent

internal class WindowsPenInput(
    window: Window,
    private val user: WindowApi,
    private val api: WindowsPenApi = Native.load("user32", WindowsPenApi::class.java),
) : AutoCloseable {
    private val handle = Native.getWindowPointer(window)
    private val dispatcher = AwtPenDispatcher(window)
    private val info = PointerPenInfo()
    private val history =
        PointerPenInfo().toArray(StudioDefaults.maxBatchSamples).map { it as PointerPenInfo }
    private val type = IntByReference()
    private val count = IntByReference()
    private val origin = Memory(8)
    private val suppressedTouches = mutableSetOf<Int>()
    private var active: WindowPenFrame? = null
    private var penInRange = false

    fun message(message: Int, wParam: Long): Boolean {
        if (message in 0x0200..0x020E && penInRange) {
            val extra = api.GetMessageExtraInfo()
            if (extra and 0xffffff00L == 0xff515700L) return true
        }
        if (message == 0x001F || (message == 0x001C && wParam == 0L)) {
            cancel()
            return false
        }
        if (message !in WindowsPointer.UPDATE..WindowsPointer.CAPTURE_CHANGED) return false
        val id = (wParam and 0xffff).toInt()
        if (message == WindowsPointer.CAPTURE_CHANGED) {
            if (active?.id == id) {
                cancel()
                return true
            }
            if (suppressedTouches.remove(id)) return true
            return api.GetPointerType(id, type) && type.value == WindowsPointer.PEN
        }
        if (!api.GetPointerType(id, type)) return false
        if (type.value == WindowsPointer.TOUCH) {
            if (message == WindowsPointer.DOWN && penInRange) suppressedTouches.add(id)
            val suppressed = id in suppressedTouches
            if (message == WindowsPointer.UP || message == WindowsPointer.LEAVE)
                suppressedTouches.remove(id)
            return suppressed
        }
        if (type.value != WindowsPointer.PEN) return false
        when (message) {
            WindowsPointer.DOWN,
            WindowsPointer.UP,
            WindowsPointer.UPDATE,
            WindowsPointer.ENTER,
            WindowsPointer.LEAVE -> Unit
            else -> return false
        }
        if (!api.GetPointerPenInfo(id, info.pointer)) {
            cancel()
            return true
        }
        info.read()
        penInRange =
            info.pointerInfo.flags and WindowsPointer.IN_RANGE != 0 &&
                message != WindowsPointer.LEAVE
        if (info.pointerInfo.flags and WindowsPointer.CANCELLED != 0) {
            cancel()
            return true
        }
        if (message == WindowsPointer.LEAVE && active != null) return true
        val phase =
            when (message) {
                WindowsPointer.DOWN -> PenPhase.Down
                WindowsPointer.UP -> PenPhase.Up
                WindowsPointer.LEAVE -> PenPhase.Leave
                else ->
                    if (info.pointerInfo.flags and WindowsPointer.IN_CONTACT != 0) PenPhase.Move
                    else PenPhase.Hover
            }
        origin.setLong(0, 0)
        if (!user.ScreenToClient(handle, origin)) {
            cancel()
            return true
        }
        val scale = user.GetDpiForWindow(handle) / 96f
        fun point(value: PointerPenInfo) =
            WindowPenPoint(
                (value.pointerInfo.pixel.x + origin.getInt(0)) / scale,
                (value.pointerInfo.pixel.y + origin.getInt(4)) / scale,
                if (value.mask and WindowsPointer.PEN_PRESSURE != 0)
                    (value.pressure / 1024f).coerceIn(0f, 1f)
                else 1f,
            )
        val samples =
            if (
                (phase == PenPhase.Move || phase == PenPhase.Up) &&
                    info.pointerInfo.historyCount > 1
            ) {
                count.value = StudioDefaults.maxBatchSamples
                if (api.GetPointerPenInfoHistory(id, count, history[0].pointer)) {
                    (minOf(count.value, StudioDefaults.maxBatchSamples) - 1 downTo 0).map { index ->
                        history[index].read()
                        point(history[index])
                    }
                } else listOf(point(info))
            } else listOf(point(info))
        if (samples.isEmpty()) return true
        val modifiers =
            (if (api.GetKeyState(0x10) < 0) InputEvent.SHIFT_DOWN_MASK else 0) or
                (if (api.GetKeyState(0x11) < 0) InputEvent.CTRL_DOWN_MASK else 0) or
                (if (api.GetKeyState(0x12) < 0) InputEvent.ALT_DOWN_MASK else 0)
        val frame =
            WindowPenFrame(
                phase,
                id,
                samples,
                info.flags and (WindowsPointer.PEN_ERASER or WindowsPointer.PEN_INVERTED) != 0,
                modifiers,
            )
        if (phase == PenPhase.Down || phase == PenPhase.Move) active = frame
        if (phase == PenPhase.Up) active = null
        dispatcher.offer(frame)
        return true
    }

    private fun cancel() {
        active?.let {
            dispatcher.offer(it.copy(phase = PenPhase.Cancel, points = listOf(it.points.last())))
        }
        active = null
        penInRange = false
    }

    override fun close() {
        dispatcher.close()
        active = null
        suppressedTouches.clear()
    }
}
