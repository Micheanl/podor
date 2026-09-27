package app.podor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import app.podor.ui.StudioTheme
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

internal interface WindowProcedure : StdCallLibrary.StdCallCallback {
    fun invoke(windowHandle: Pointer, message: Int, wParam: Long, lParam: Long): Long
}

internal interface WindowApi : StdCallLibrary {
    fun GetWindowLongW(window: Pointer, index: Int): Int

    fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int

    fun GetWindowLongPtrW(window: Pointer, index: Int): Pointer

    fun SetWindowLongPtrW(window: Pointer, index: Int, procedure: Callback): Pointer

    fun SetWindowLongPtrW(window: Pointer, index: Int, procedure: Pointer): Pointer

    fun CallWindowProcW(
        procedure: Pointer,
        window: Pointer,
        message: Int,
        wParam: Long,
        lParam: Long,
    ): Long

    fun SetWindowPos(
        window: Pointer,
        after: Pointer?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int,
    ): Boolean

    fun GetClientRect(window: Pointer, rect: Pointer): Boolean

    fun GetWindowRect(window: Pointer, rect: Pointer): Boolean

    fun ScreenToClient(window: Pointer, point: Pointer): Boolean

    fun GetDpiForWindow(window: Pointer): Int

    fun IsZoomed(window: Pointer): Boolean

    fun IsWindow(window: Pointer): Boolean

    fun MonitorFromWindow(window: Pointer, flags: Int): Pointer

    fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean

    fun SendMessageW(window: Pointer, message: Int, wParam: Long, lParam: Long): Long
}

private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        window: Pointer,
        attribute: Int,
        value: IntByReference,
        size: Int,
    ): Int
}

internal object WindowHit {
    const val CLIENT = 1
    const val CAPTION = 2
    const val LEFT = 10
    const val RIGHT = 11
    const val TOP = 12
    const val TOP_LEFT = 13
    const val TOP_RIGHT = 14
    const val BOTTOM = 15
    const val BOTTOM_LEFT = 16
    const val BOTTOM_RIGHT = 17

    fun at(x: Int, y: Int, width: Int, height: Int, scale: Float, maximized: Boolean): Int {
        val border = (StudioTheme.windowResizeBorder.value * scale).roundToInt()
        if (!maximized) {
            val left = x < border
            val right = x >= width - border
            if (y < border) return if (left) TOP_LEFT else if (right) TOP_RIGHT else TOP
            if (y >= height - border)
                return if (left) BOTTOM_LEFT else if (right) BOTTOM_RIGHT else BOTTOM
            if (left) return LEFT
            if (right) return RIGHT
        }
        val titleHeight = (StudioTheme.windowTitleHeight.value * scale).roundToInt()
        val controls = (StudioTheme.windowButtonWidth.value * 3 * scale).roundToInt()
        return if (y < titleHeight && x < width - controls) CAPTION else CLIENT
    }
}

internal class NativeWindowChrome(window: Window) : AutoCloseable {
    private val user = Native.load("user32", WindowApi::class.java)
    private val handle = Native.getWindowPointer(window)
    private val originalProcedure = user.GetWindowLongPtrW(handle, -4)
    private val originalStyle = user.GetWindowLongW(handle, -16)
    private val rect = Memory(16)
    private val point = Memory(8)
    private val monitor = Memory(40)
    private val procedure =
        object : WindowProcedure {
            override fun invoke(
                windowHandle: Pointer,
                message: Int,
                wParam: Long,
                lParam: Long,
            ): Long {
                when (message) {
                    0x0083 -> return 0L
                    0x0084 -> {
                        point.setInt(0, (lParam and 0xffff).toShort().toInt())
                        point.setInt(4, ((lParam shr 16) and 0xffff).toShort().toInt())
                        if (
                            user.ScreenToClient(windowHandle, point) &&
                                user.GetClientRect(windowHandle, rect)
                        ) {
                            return WindowHit.at(
                                    point.getInt(0),
                                    point.getInt(4),
                                    rect.getInt(8),
                                    rect.getInt(12),
                                    user.GetDpiForWindow(windowHandle) / 96f,
                                    user.IsZoomed(windowHandle),
                                )
                                .toLong()
                        }
                    }
                    0x0024 -> {
                        user.CallWindowProcW(
                            originalProcedure,
                            windowHandle,
                            message,
                            wParam,
                            lParam,
                        )
                        monitor.setInt(0, 40)
                        if (
                            user.GetMonitorInfoW(user.MonitorFromWindow(windowHandle, 2), monitor)
                        ) {
                            val limits = Pointer(lParam)
                            limits.setInt(8, monitor.getInt(28) - monitor.getInt(20))
                            limits.setInt(12, monitor.getInt(32) - monitor.getInt(24))
                            limits.setInt(16, monitor.getInt(20) - monitor.getInt(4))
                            limits.setInt(20, monitor.getInt(24) - monitor.getInt(8))
                            val scale = user.GetDpiForWindow(windowHandle) / 96f
                            limits.setInt(
                                24,
                                (StudioTheme.minimumWindowWidth.value * scale).roundToInt(),
                            )
                            limits.setInt(
                                28,
                                (StudioTheme.minimumWindowHeight.value * scale).roundToInt(),
                            )
                        }
                        return 0L
                    }
                }
                return user.CallWindowProcW(
                    originalProcedure,
                    windowHandle,
                    message,
                    wParam,
                    lParam,
                )
            }
        }

    init {
        user.SetWindowLongPtrW(handle, -4, procedure)
        user.SetWindowLongW(handle, -16, (originalStyle and Int.MIN_VALUE.inv()) or 0x00CF0000)
        val dwm = Native.load("dwmapi", DwmApi::class.java)
        dwm.DwmSetWindowAttribute(handle, 20, IntByReference(1), 4)
        dwm.DwmSetWindowAttribute(handle, 33, IntByReference(2), 4)
        dwm.DwmSetWindowAttribute(handle, 34, IntByReference(StudioTheme.border.colorRef()), 4)
        user.SetWindowPos(handle, null, 0, 0, 0, 0, 0x0037)
    }

    override fun close() {
        if (user.IsWindow(handle)) {
            user.SetWindowLongPtrW(handle, -4, originalProcedure)
            user.SetWindowLongW(handle, -16, originalStyle)
        }
    }
}

@Composable
fun WindowsChrome(window: Window) {
    DisposableEffect(window) {
        var chrome: NativeWindowChrome? = null
        fun install() {
            if (chrome == null) chrome = NativeWindowChrome(window)
        }
        val listener =
            object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) = install()
            }
        window.addWindowListener(listener)
        if (window.isDisplayable) install()
        onDispose {
            window.removeWindowListener(listener)
            chrome?.close()
        }
    }
}

private fun Color.colorRef() =
    (red * 255).toInt() or ((green * 255).toInt() shl 8) or ((blue * 255).toInt() shl 16)
