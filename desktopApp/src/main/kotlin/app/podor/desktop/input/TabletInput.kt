package app.podor.desktop.input

import app.podor.desktop.WindowApi
import app.podor.domain.TabletInputMode
import com.sun.jna.Native
import java.awt.Window

internal class TabletInput(window: Window, user: WindowApi, mode: TabletInputMode) : AutoCloseable {
    private val api = Native.load("user32", WindowsPointerApi::class.java)
    private val winTab =
        if (mode == TabletInputMode.WindowsInk) null else WinTabInput.open(window, user, api)
    private val ink =
        WindowsPointerInput(
            window,
            user,
            api,
            acceptPen = winTab == null,
            externalPenInRange = { winTab?.inRange == true },
        )

    fun message(message: Int, wParam: Long, lParam: Long): Boolean {
        if (winTab?.message(message, wParam, lParam) == true) {
            if (winTab.inRange) ink.cancelTouch()
            return true
        }
        if (winTab?.inRange == true && message in 0x0200..0x020D && message != 0x020A) return true
        return ink.message(message, wParam)
    }

    override fun close() {
        winTab?.close()
        ink.close()
    }
}
