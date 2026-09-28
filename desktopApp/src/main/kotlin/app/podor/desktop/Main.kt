package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
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
import app.podor.ui.borderTrail
import app.podor.ui.input.nativeTouchGuard
import app.podor.ui.rememberStudioStartupReady
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
        if (!rememberStudioStartupReady(controller)) return@application
        val windowState = rememberWindowState(width = 1360.dp, height = 900.dp)
        val customChrome = remember { System.getProperty("os.name").startsWith("Windows") }
        Window(
            onCloseRequest = { controller.navigate(WorkspaceDestination.Exit) },
            title = AppIdentity.name,
            icon = painterResource(Res.drawable.brand),
            state = windowState,
            undecorated = customChrome,
        ) {
            var dragRegion by remember { mutableStateOf(Rect.Zero) }
            if (customChrome)
                WindowsChrome(window, controller.preferences.tabletInputMode) { x, y ->
                    controller.showWorkspace ||
                        dragRegion.contains(Offset(x.toFloat(), y.toFloat()))
                }
            SideEffect {
                windowRef = window
                window.minimumSize =
                    Dimension(
                        StudioTheme.minimumWindowWidth.value.toInt(),
                        StudioTheme.minimumWindowHeight.value.toInt(),
                    )
            }
            Box(
                Modifier.fillMaxSize()
                    .nativeTouchGuard()
                    .borderTrail(active = customChrome && LocalWindowInfo.current.isWindowFocused)
            ) {
                PodorApp(
                    controller,
                    updates,
                    titleBarHeight = if (customChrome) StudioTheme.windowTitleHeight else 0.dp,
                    onTitleDragRegion = { dragRegion = it },
                ) { launching ->
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
                                if (launching) Color.Transparent
                                else if (controller.showWorkspace) StudioTheme.background
                                else StudioTheme.panel,
                        )
                }
            }
        }
    }
}
