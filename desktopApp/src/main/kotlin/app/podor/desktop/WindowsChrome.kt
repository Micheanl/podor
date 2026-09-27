package app.podor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import app.podor.ui.StudioTheme
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        window: Pointer,
        attribute: Int,
        value: IntByReference,
        size: Int,
    ): Int
}

private interface ThemeApi : StdCallLibrary {
    fun SetWindowThemeAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private object WindowAttribute {
    const val DARK_MODE = 20
    const val CORNER_PREFERENCE = 33
    const val BORDER_COLOR = 34
    const val CAPTION_COLOR = 35
    const val TEXT_COLOR = 36
    const val ROUND_CORNERS = 2
    const val NON_CLIENT_THEME = 1
    const val HIDE_CAPTION = 1
    const val HIDE_ICON = 2
}

@Composable
fun WindowsChrome(window: Window) {
    DisposableEffect(window) {
        if (!System.getProperty("os.name").startsWith("Windows"))
            return@DisposableEffect onDispose {}
        fun applyTheme() {
            runCatching {
                val dwm = Native.load("dwmapi", DwmApi::class.java)
                val handle = Native.getWindowPointer(window)
                fun set(attribute: Int, value: Int) =
                    dwm.DwmSetWindowAttribute(
                        handle,
                        attribute,
                        IntByReference(value),
                        Int.SIZE_BYTES,
                    )
                set(WindowAttribute.DARK_MODE, 1)
                set(WindowAttribute.CORNER_PREFERENCE, WindowAttribute.ROUND_CORNERS)
                set(WindowAttribute.BORDER_COLOR, StudioTheme.border.colorRef())
                set(WindowAttribute.CAPTION_COLOR, StudioTheme.panel.colorRef())
                set(WindowAttribute.TEXT_COLOR, StudioTheme.text.colorRef())
                val theme = Native.load("uxtheme", ThemeApi::class.java)
                Memory(8).use { options ->
                    val hidden = WindowAttribute.HIDE_CAPTION or WindowAttribute.HIDE_ICON
                    options.setInt(0, hidden)
                    options.setInt(4, hidden)
                    theme.SetWindowThemeAttribute(
                        handle,
                        WindowAttribute.NON_CLIENT_THEME,
                        options,
                        8,
                    )
                }
            }
        }
        val listener =
            object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) = applyTheme()
            }
        window.addWindowListener(listener)
        if (window.isDisplayable) applyTheme()
        onDispose { window.removeWindowListener(listener) }
    }
}

private fun Color.colorRef() =
    (red * 255).toInt() or ((green * 255).toInt() shl 8) or ((blue * 255).toInt() shl 16)
