package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.podor.desktop.data.DesktopFiles
import app.podor.desktop.data.DesktopUpdates
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.AppBuildInfo
import app.podor.domain.AppIdentity
import app.podor.domain.WorkspaceDestination
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import app.podor.resources.Res
import app.podor.resources.brand
import app.podor.ui.PodorApp
import app.podor.ui.StudioTheme
import app.podor.ui.input.nativeTouchGuard
import java.awt.Dimension
import org.jetbrains.compose.resources.painterResource

fun main() {
    NativeLoader.load()
    application {
        val scope = rememberCoroutineScope()
        var windowRef by remember { mutableStateOf<java.awt.Frame?>(null) }
        val updateSource = remember { DesktopUpdates() }
        val controller = remember {
            StudioController(DesktopFiles { windowRef }, scope, updateSource::install)
        }
        val updates = remember {
            if (System.getProperty("os.name").startsWith("Windows"))
                UpdateController(updateSource, scope, AppBuildInfo.version)
            else null
        }
        LaunchedEffect(controller.exitRequested) {
            if (controller.exitRequested) exitApplication()
        }
        DisposableEffect(Unit) {
            onDispose {
                updates?.close()
                controller.close()
            }
        }
        val windowState = rememberWindowState(width = 1360.dp, height = 900.dp)
        val customChrome = remember { System.getProperty("os.name").startsWith("Windows") }
        Window(
            onCloseRequest = { controller.navigate(WorkspaceDestination.Exit) },
            title = AppIdentity.name,
            icon = painterResource(Res.drawable.brand),
            state = windowState,
            undecorated = customChrome,
        ) {
            if (customChrome) WindowsChrome(window)
            SideEffect {
                windowRef = window
                window.minimumSize =
                    Dimension(
                        StudioTheme.minimumWindowWidth.value.toInt(),
                        StudioTheme.minimumWindowHeight.value.toInt(),
                    )
            }
            Column(Modifier.fillMaxSize().nativeTouchGuard()) {
                if (customChrome)
                    WindowTitleBar(
                        language = controller.preferences.language,
                        maximized = windowState.placement == WindowPlacement.Maximized,
                        onMinimize = { windowState.isMinimized = true },
                        onMaximize = {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized)
                                    WindowPlacement.Floating
                                else WindowPlacement.Maximized
                        },
                        onClose = { controller.navigate(WorkspaceDestination.Exit) },
                        background =
                            if (controller.showWorkspace) StudioTheme.background
                            else StudioTheme.panel,
                    )
                Box(Modifier.weight(1f)) { PodorApp(controller, updates) }
            }
        }
    }
}
