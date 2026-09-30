package app.podor.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

internal interface WinTabApi : StdCallLibrary {
    fun WTInfoW(category: Int, index: Int, output: Pointer?): Int

    fun WTOpenW(window: Pointer, context: Pointer, enabled: Boolean): Pointer?

    fun WTClose(context: Pointer): Boolean

    fun WTEnable(context: Pointer, enabled: Boolean): Boolean

    fun WTOverlap(context: Pointer, top: Boolean): Boolean

    fun WTPacketsGet(context: Pointer, count: Int, packets: Pointer): Int

    fun WTQueueSizeSet(context: Pointer, count: Int): Boolean
}

@Structure.FieldOrder(
    "name",
    "options",
    "status",
    "locks",
    "messageBase",
    "device",
    "packetRate",
    "packetData",
    "packetMode",
    "moveMask",
    "buttonDownMask",
    "buttonUpMask",
    "inputX",
    "inputY",
    "inputZ",
    "inputWidth",
    "inputHeight",
    "inputDepth",
    "outputX",
    "outputY",
    "outputZ",
    "outputWidth",
    "outputHeight",
    "outputDepth",
    "sensitivityX",
    "sensitivityY",
    "sensitivityZ",
    "systemMode",
    "systemX",
    "systemY",
    "systemWidth",
    "systemHeight",
    "systemSensitivityX",
    "systemSensitivityY",
)
internal class WinTabContext : Structure() {
    @JvmField var name = ShortArray(40)
    @JvmField var options = 0
    @JvmField var status = 0
    @JvmField var locks = 0
    @JvmField var messageBase = 0
    @JvmField var device = 0
    @JvmField var packetRate = 0
    @JvmField var packetData = 0
    @JvmField var packetMode = 0
    @JvmField var moveMask = 0
    @JvmField var buttonDownMask = 0
    @JvmField var buttonUpMask = 0
    @JvmField var inputX = 0
    @JvmField var inputY = 0
    @JvmField var inputZ = 0
    @JvmField var inputWidth = 0
    @JvmField var inputHeight = 0
    @JvmField var inputDepth = 0
    @JvmField var outputX = 0
    @JvmField var outputY = 0
    @JvmField var outputZ = 0
    @JvmField var outputWidth = 0
    @JvmField var outputHeight = 0
    @JvmField var outputDepth = 0
    @JvmField var sensitivityX = 0
    @JvmField var sensitivityY = 0
    @JvmField var sensitivityZ = 0
    @JvmField var systemMode = 0
    @JvmField var systemX = 0
    @JvmField var systemY = 0
    @JvmField var systemWidth = 0
    @JvmField var systemHeight = 0
    @JvmField var systemSensitivityX = 0
    @JvmField var systemSensitivityY = 0
}

internal object WinTab {
    const val MESSAGE_BASE = 0x7FF0
    const val PROXIMITY = MESSAGE_BASE + 5
    const val PACKET_DATA = 0x5E2
    const val PACKET_BYTES = 24
}
