package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController

@Composable
fun StudioApp(controller: StudioController, updates: UpdateController? = null) {
    PodorTheme(controller.preferences.language) {
        var panel by remember { mutableStateOf(StudioPanel.Brushes) }
        var showInspector by remember { mutableStateOf(false) }
        var inspectorExpanded by remember { mutableStateOf(true) }
        var dialog by remember { mutableStateOf(StudioDialog.None) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun clipboardShortcut(event: KeyEvent): Boolean {
            if ((event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed) {
                when (event.key) {
                    Key.C, Key.X, Key.V -> return true
                    else -> Unit
                }
            }
            val binding = event.shortcut() ?: return false
            return ClipboardAction.entries.any {
                controller.preferences.shortcut(it.shortcut) == binding
            }
        }
        fun handleShortcut(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown || dialog != StudioDialog.None) return false
            if (event.key == Key.Escape && controller.tool == Tool.Select) {
                controller.cancelSelectionGesture()
                return true
            }
            if (event.key == Key.Escape && controller.tool == Tool.MoveLayer) {
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.Brush
                return true
            }
            val binding = event.shortcut() ?: return false
            val action =
                ShortcutAction.entries.firstOrNull {
                    controller.preferences.shortcut(it) == binding
                } ?: return false
            when (action) {
                ShortcutAction.Brush -> controller.tool = Tool.Brush
                ShortcutAction.Eraser -> controller.tool = Tool.Eraser
                ShortcutAction.Picker -> controller.tool = Tool.Picker
                ShortcutAction.Hand -> controller.tool = Tool.Hand
                ShortcutAction.MoveLayer -> controller.tool = Tool.MoveLayer
                ShortcutAction.Select -> controller.tool = Tool.Select
                ShortcutAction.Fill -> controller.tool = Tool.Fill
                ShortcutAction.Fit -> controller.viewport = Viewport()
                ShortcutAction.Undo -> controller.command("undo")
                ShortcutAction.Redo -> controller.command("redo")
                ShortcutAction.Deselect -> controller.clearSelection()
                ShortcutAction.Save -> controller.file(StudioController.FileAction.Save)
                ShortcutAction.Open -> dialog = StudioDialog.Open
                ShortcutAction.Export -> dialog = StudioDialog.Export
                ShortcutAction.New -> dialog = StudioDialog.New
                ShortcutAction.Copy -> controller.clipboard(ClipboardAction.Copy)
                ShortcutAction.CopyVisible -> controller.clipboard(ClipboardAction.CopyVisible)
                ShortcutAction.Cut -> controller.clipboard(ClipboardAction.Cut)
                ShortcutAction.Paste -> controller.clipboard(ClipboardAction.Paste)
            }
            return true
        }
        Surface(Modifier.fillMaxSize(), color = StudioTheme.background) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .safeDrawingPadding()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (
                            (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) &&
                                !clipboardShortcut(event)
                        )
                            handleShortcut(event)
                        else false
                    }
                    .onKeyEvent { event ->
                        if (
                            (!event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) ||
                                clipboardShortcut(event)
                        )
                            handleShortcut(event)
                        else false
                    }
                    .focusable()
            ) {
                val compact = maxWidth < 620.dp || maxHeight < 520.dp
                val wide = !compact && maxWidth >= 1000.dp
                val showDocument = maxWidth >= if (controller.clipboardAvailable) 880.dp else 820.dp
                fun openPanel(next: StudioPanel) {
                    panel = next
                    if (wide) inspectorExpanded = true else showInspector = true
                }
                val inspectorInset =
                    if (wide && inspectorExpanded)
                        StudioTheme.inspectorWidth + StudioTheme.inspectorMargin
                    else 0.dp
                Column(Modifier.fillMaxSize()) {
                    StudioHeader(
                        controller,
                        compact,
                        showDocument,
                        { dialog = it },
                        inspectorExpanded = inspectorExpanded,
                        onToggleInspector =
                            if (wide) ({ inspectorExpanded = !inspectorExpanded }) else null,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CanvasWorkspace(
                            controller,
                            Modifier.fillMaxSize(),
                            endInset = inspectorInset,
                        )
                        Box(Modifier.fillMaxSize().padding(end = inspectorInset)) {
                            if (!compact) {
                                Column(
                                    Modifier.align(Alignment.CenterStart)
                                        .padding(start = 16.dp)
                                        .shadow(14.dp, RoundedCornerShape(32.dp))
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(StudioTheme.panel)
                                        .border(
                                            1.dp,
                                            StudioTheme.border.copy(alpha = 0.65f),
                                            RoundedCornerShape(32.dp),
                                        )
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StudioTools(controller)
                                    HorizontalDivider(
                                        Modifier.width(26.dp).padding(vertical = 5.dp),
                                        color = StudioTheme.border,
                                    )
                                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                        ColorSwatch(controller.brush.color, true) {
                                            openPanel(StudioPanel.Colors)
                                        }
                                    }
                                    if (!wide)
                                        ToolButton(Glyph.Layers, "图层与工作台") {
                                            openPanel(StudioPanel.Layers)
                                        }
                                }
                                if (controller.tool == Tool.MoveLayer)
                                    LayerMoveDock(
                                        controller,
                                        Modifier.align(Alignment.BottomCenter)
                                            .padding(bottom = 55.dp),
                                    )
                                else if (controller.tool != Tool.Select)
                                    BrushDock(
                                        controller,
                                        Modifier.align(Alignment.BottomCenter)
                                            .padding(bottom = 55.dp),
                                    ) {
                                        openPanel(StudioPanel.Brushes)
                                    }
                            }
                            if (controller.tool == Tool.Select)
                                SelectionDock(
                                    controller,
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 55.dp),
                                )
                            CanvasFooter(
                                controller,
                                compact,
                                Modifier.align(Alignment.BottomCenter),
                            )
                            if (!controller.ready)
                                CircularProgressIndicator(
                                    Modifier.size(26.dp).align(Alignment.Center),
                                    strokeWidth = 2.dp,
                                )
                            if (controller.busy)
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                                )
                        }
                        if (wide && inspectorExpanded) {
                            Box(
                                Modifier.align(Alignment.CenterEnd)
                                    .padding(
                                        top = StudioTheme.inspectorMargin,
                                        end = StudioTheme.inspectorMargin,
                                        bottom = StudioTheme.inspectorMargin,
                                    )
                                    .width(StudioTheme.inspectorWidth)
                                    .fillMaxHeight()
                                    .clip(StudioTheme.inspectorShape)
                                    .background(StudioTheme.panel)
                                    .border(
                                        1.dp,
                                        StudioTheme.border.copy(alpha = 0.6f),
                                        StudioTheme.inspectorShape,
                                    )
                                    .pointerInput(Unit) { detectTapGestures {} }
                            ) {
                                Inspector(
                                    controller,
                                    panel,
                                    { panel = it },
                                    Modifier.fillMaxSize(),
                                    onCollapse = { inspectorExpanded = false },
                                )
                            }
                        }
                    }
                    if (compact) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(StudioTheme.panel)
                                .border(
                                    1.dp,
                                    StudioTheme.border.copy(alpha = 0.6f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StudioTools(controller, compact = true)
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                ColorSwatch(controller.brush.color, true) {
                                    openPanel(StudioPanel.Colors)
                                }
                            }
                            ToolButton(Glyph.Layers, "画笔、颜色与图层") { openPanel(StudioPanel.Brushes) }
                        }
                    }
                }
                if (showInspector)
                    StudioModal("工作台", Glyph.Layers, { showInspector = false }, width = 380.dp) {
                        Inspector(
                            controller,
                            panel,
                            { panel = it },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                StudioDialogs(controller, dialog, updates) { dialog = StudioDialog.None }
            }
        }
    }
}
