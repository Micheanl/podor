package app.podor.desktop.input

import app.podor.desktop.WindowApi
import app.podor.domain.StudioDefaults
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.awt.Window
import java.awt.event.InputEvent

internal class WindowsPointerInput(
    window: Window,
    private val user: WindowApi,
    private val api: WindowsPointerApi = Native.load("user32", WindowsPointerApi::class.java),
    private val acceptPen: Boolean = true,
    private val externalPenInRange: () -> Boolean = { false },
) : AutoCloseable {
    private val handle = Native.getWindowPointer(window)
    private val dispatcher = AwtPointerDispatcher(window)
    private val touch = TouchPointerState(dispatcher::offer)
    private val touchInfo = PointerInfo()
    private val info = PointerPenInfo()
    private val history =
        PointerPenInfo().toArray(StudioDefaults.maxBatchSamples).map { it as PointerPenInfo }
    private val type = IntByReference()
    private val count = IntByReference()
    private val origin = Memory(8)
    private val suppressedTouches = mutableSetOf<Int>()
    private var active: WindowPointerFrame? = null
    private var penInRange = false

    fun message(message: Int, wParam: Long): Boolean {
        if (message in 0x0200..0x020E) {
            val extra = api.GetMessageExtraInfo()
            if (
                extra and 0xffffff80L == 0xff515780L ||
                    (penInRange && extra and 0xffffff00L == 0xff515700L)
            )
                return true
        }
        if (message == 0x001F || (message == 0x001C && wParam == 0L)) {
            cancel()
            return false
        }
        if (message !in WindowsPointer.UPDATE..WindowsPointer.CAPTURE_CHANGED) return false
        val id = (wParam and 0xffff).toInt()
        if (message == WindowsPointer.CAPTURE_CHANGED) {
            if (id in touch.ids) {
                cancelTouch()
                return true
            }
            if (active?.id == id) {
                cancel()
                return true
            }
            if (suppressedTouches.remove(id)) return true
            return api.GetPointerType(id, type) &&
                (type.value == WindowsPointer.PEN || type.value == WindowsPointer.TOUCH)
        }
        if (!api.GetPointerType(id, type)) return false
        if (type.value == WindowsPointer.TOUCH) {
            if (message == WindowsPointer.DOWN) {
                suppressedTouches.remove(id)
                if (penInRange || externalPenInRange()) suppressedTouches.add(id)
            }
            val suppressed = id in suppressedTouches
            if (message == WindowsPointer.UP || message == WindowsPointer.LEAVE)
                suppressedTouches.remove(id)
            if (suppressed) return true
            if (
                message == WindowsPointer.DOWN &&
                    touch.ids.size >= StudioDefaults.nativeTouchContacts
            ) {
                cancelTouch()
                suppressedTouches.add(id)
                return true
            }
            when (message) {
                WindowsPointer.DOWN,
                WindowsPointer.UPDATE,
                WindowsPointer.UP -> Unit
                else -> return true
            }
            if (!api.GetPointerInfo(id, touchInfo.pointer)) {
                cancelTouch()
                return true
            }
            touchInfo.read()
            if (touchInfo.flags and WindowsPointer.CANCELLED != 0) {
                cancelTouch()
                return true
            }
            origin.setLong(0, 0)
            if (!user.ScreenToClient(handle, origin)) {
                cancelTouch()
                return true
            }
            val scale = user.GetDpiForWindow(handle) / 96f
            touch.update(
                when (message) {
                    WindowsPointer.DOWN -> PointerPhase.Down
                    WindowsPointer.UP -> PointerPhase.Up
                    else -> PointerPhase.Move
                },
                id,
                WindowPointerPoint(
                    (touchInfo.pixel.x + origin.getInt(0)) / scale,
                    (touchInfo.pixel.y + origin.getInt(4)) / scale,
                    1f,
                ),
                modifiers(),
            )
            return true
        }
        if (type.value != WindowsPointer.PEN) return false
        if (!acceptPen) return true
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
        if (penInRange && touch.ids.isNotEmpty()) cancelTouch()
        if (info.pointerInfo.flags and WindowsPointer.CANCELLED != 0) {
            cancel()
            return true
        }
        if (message == WindowsPointer.LEAVE && active != null) return true
        val phase =
            when (message) {
                WindowsPointer.DOWN -> PointerPhase.Down
                WindowsPointer.UP -> PointerPhase.Up
                WindowsPointer.LEAVE -> PointerPhase.Leave
                else ->
                    if (info.pointerInfo.flags and WindowsPointer.IN_CONTACT != 0) PointerPhase.Move
                    else PointerPhase.Hover
            }
        origin.setLong(0, 0)
        if (!user.ScreenToClient(handle, origin)) {
            cancel()
            return true
        }
        val scale = user.GetDpiForWindow(handle) / 96f
        fun point(value: PointerPenInfo) =
            WindowPointerPoint(
                (value.pointerInfo.pixel.x + origin.getInt(0)) / scale,
                (value.pointerInfo.pixel.y + origin.getInt(4)) / scale,
                if (value.mask and WindowsPointer.PEN_PRESSURE != 0)
                    (value.pressure / 1024f).coerceIn(0f, 1f)
                else 1f,
            )
        val samples =
            if (
                (phase == PointerPhase.Move || phase == PointerPhase.Up) &&
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
        val frame =
            WindowPointerFrame(
                phase,
                id,
                samples,
                info.flags and (WindowsPointer.PEN_ERASER or WindowsPointer.PEN_INVERTED) != 0,
                modifiers(),
                barrel = info.flags and 1 != 0,
            )
        if (phase == PointerPhase.Down || phase == PointerPhase.Move) active = frame
        if (phase == PointerPhase.Up) active = null
        dispatcher.offer(frame)
        return true
    }

    private fun cancel() {
        cancelTouch()
        active?.let {
            dispatcher.offer(
                it.copy(phase = PointerPhase.Cancel, points = listOf(it.points.last()))
            )
        }
        active = null
        penInRange = false
    }

    fun cancelTouch() {
        suppressedTouches.addAll(touch.ids)
        touch.cancel()
    }

    private fun modifiers() =
        (if (api.GetKeyState(0x10) < 0) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (api.GetKeyState(0x11) < 0) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (api.GetKeyState(0x12) < 0) InputEvent.ALT_DOWN_MASK else 0)

    override fun close() {
        dispatcher.close()
        touch.cancel()
        active = null
        suppressedTouches.clear()
    }
}
