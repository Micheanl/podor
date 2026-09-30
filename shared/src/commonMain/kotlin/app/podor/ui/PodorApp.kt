package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController

fun studioStartupReady(controller: StudioController): Boolean {
    if (!controller.ready && !controller.startupFailed) return false
    StudioTheme.appearance = controller.preferences.appearance
    return true
}

@Composable
fun PodorApp(
    controller: StudioController,
    updates: UpdateController? = null,
    titleBarHeight: Dp = 0.dp,
    onTitleDragRegion: (Rect) -> Unit = {},
    titleBar: @Composable () -> Unit = {},
) {
    if (!studioStartupReady(controller)) return
    var workspace by
        remember(controller) { mutableStateOf(controller.preferences.workspaceAppearance) }
    val drawingInput = controller.drawingInput
    SideEffect {
        if (!drawingInput) workspace = controller.preferences.workspaceAppearance
    }
    PodorTheme(
        controller.preferences.language,
        controller.preferences.appearance,
        androidx.compose.ui.graphics.Color(controller.brush.color),
        workspaceAppearance = workspace,
    ) {
        val integrated =
            titleBarHeight > 0.dp && !controller.showWorkspace && !controller.startupFailed
        Box(Modifier.fillMaxSize().background(StudioTheme.background)) {
            Box(Modifier.fillMaxSize().padding(top = if (integrated) 0.dp else titleBarHeight)) {
                if (controller.startupFailed)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        controller.error?.let {
                            Text(tr(it), Modifier.padding(StudioTheme.workspacePadding))
                        }
                    }
                else if (controller.showWorkspace) WorkspaceHome(controller, updates)
                else
                    StudioApp(
                        controller,
                        updates,
                        if (integrated) titleBar else null,
                        onTitleDragRegion,
                    )
            }
            if (!integrated) Box(Modifier.align(Alignment.TopCenter)) { titleBar() }
            UnsavedChangesDialog(controller)
        }
    }
}
