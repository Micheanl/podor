@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.podor.platform

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import app.podor.presentation.StudioController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    lateinit var host: UIViewController
    val files = IosFiles { host }
    host = ComposeUIViewController {
        val scope = rememberCoroutineScope()
        val controller = remember { StudioController(files, scope) }
        DisposableEffect(Unit) { onDispose { controller.close() } }
        StudioAppContent(controller)
    }
    return host
}

@Composable
private fun StudioAppContent(controller: StudioController) {
    app.podor.ui.PodorApp(controller)
}
