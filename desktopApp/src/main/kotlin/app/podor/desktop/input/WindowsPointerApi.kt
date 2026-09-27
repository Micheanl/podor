package app.podor.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

internal interface WindowsPointerApi : StdCallLibrary {
    fun GetPointerType(id: Int, type: IntByReference): Boolean

    fun GetPointerInfo(id: Int, info: Pointer): Boolean

    fun GetPointerPenInfo(id: Int, info: Pointer): Boolean

    fun GetPointerPenInfoHistory(id: Int, count: IntByReference, info: Pointer): Boolean

    fun GetKeyState(key: Int): Short

    fun GetMessageExtraInfo(): Long
}

@Structure.FieldOrder("x", "y")
internal class WinPoint : Structure() {
    @JvmField var x = 0
    @JvmField var y = 0
}

@Structure.FieldOrder(
    "type",
    "id",
    "frame",
    "flags",
    "device",
    "target",
    "pixel",
    "himetric",
    "rawPixel",
    "rawHimetric",
    "time",
    "historyCount",
    "input",
    "keys",
    "performanceCount",
    "buttonChange",
)
internal class PointerInfo : Structure() {
    @JvmField var type = 0
    @JvmField var id = 0
    @JvmField var frame = 0
    @JvmField var flags = 0
    @JvmField var device: Pointer? = null
    @JvmField var target: Pointer? = null
    @JvmField var pixel = WinPoint()
    @JvmField var himetric = WinPoint()
    @JvmField var rawPixel = WinPoint()
    @JvmField var rawHimetric = WinPoint()
    @JvmField var time = 0
    @JvmField var historyCount = 0
    @JvmField var input = 0
    @JvmField var keys = 0
    @JvmField var performanceCount = 0L
    @JvmField var buttonChange = 0
}

@Structure.FieldOrder("pointerInfo", "flags", "mask", "pressure", "rotation", "tiltX", "tiltY")
internal class PointerPenInfo : Structure() {
    @JvmField var pointerInfo = PointerInfo()
    @JvmField var flags = 0
    @JvmField var mask = 0
    @JvmField var pressure = 0
    @JvmField var rotation = 0
    @JvmField var tiltX = 0
    @JvmField var tiltY = 0
}

internal object WindowsPointer {
    const val UPDATE = 0x0245
    const val DOWN = 0x0246
    const val UP = 0x0247
    const val ENTER = 0x0249
    const val LEAVE = 0x024A
    const val CAPTURE_CHANGED = 0x024C
    const val TOUCH = 2
    const val PEN = 3
    const val IN_RANGE = 0x2
    const val IN_CONTACT = 0x4
    const val CANCELLED = 0x8000
    const val PEN_PRESSURE = 0x1
    const val PEN_INVERTED = 0x2
    const val PEN_ERASER = 0x4
}
