package app.podor.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.podor.desktop.data.DesktopFiles
import app.podor.desktop.data.DesktopUpdates
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.AppBuildInfo
import app.podor.domain.AppIdentity
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import app.podor.resources.Res
import app.podor.resources.brand
import app.podor.ui.PodorApp
import java.awt.Dimension
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

fun main() {
    NativeLoader.load()
    application {
        val scope = rememberCoroutineScope()
        var windowRef by remember { mutableStateOf<java.awt.Frame?>(null) }
        val controller = remember { StudioController(DesktopFiles { windowRef }, scope) }
        val updates = remember {
            if (System.getProperty("os.name").startsWith("Windows"))
                UpdateController(DesktopUpdates(), scope, AppBuildInfo.version)
            else null
        }
        DisposableEffect(Unit) {
            onDispose {
                updates?.close()
                controller.close()
            }
        }
        Window(
            onCloseRequest = {
                scope.launch {
                    if (controller.shutdown()) exitApplication()
                }
            },
            title = AppIdentity.name,
            icon = painterResource(Res.drawable.brand),
            state = rememberWindowState(width = 1360.dp, height = 900.dp),
        ) {
            WindowsChrome(window)
            SideEffect {
                windowRef = window
                window.minimumSize = Dimension(400, 600)
            }
            PodorApp(controller, updates)
        }
    }
}
