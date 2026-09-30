package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import kotlin.math.roundToInt

@Composable
fun StudioApp(
    controller: StudioController,
    updates: UpdateController? = null,
    windowControls: (@Composable () -> Unit)? = null,
    onTitleDragRegion: (Rect) -> Unit = {},
) {
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
        var panel by remember { mutableStateOf(StudioPanel.ToolOptions) }
        var inspectorVisible by remember { mutableStateOf(false) }
        var requestedPanel by remember { mutableStateOf<StudioPanel?>(null) }
        var dialog by remember { mutableStateOf(StudioDialog.None) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(controller.tool) {
            panel = controller.defaultToolPanel()
            focus.requestFocus()
        }
        LaunchedEffect(controller.adjustmentPreview != null) {
            if (controller.adjustmentPreview != null && panel != StudioPanel.Adjustments)
                panel = StudioPanel.ToolOptions
        }
        fun clipboardShortcut(event: KeyEvent): Boolean {
            if ((event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed) {
                when (event.key) {
                    Key.C,
                    Key.X,
                    Key.V -> return true
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
            if (event.key == Key.Escape && controller.animationPlaying) {
                controller.stopAnimation()
                return true
            }
            if (
                controller.references.visible &&
                    controller.references.selected != null &&
                    !controller.animationPlaying &&
                    !controller.animationTransition
            ) {
                if (event.key == Key.Escape || event.key == Key.Enter) {
                    controller.references.select(null)
                    return true
                }
                if (event.key == Key.Delete || event.key == Key.Backspace) {
                    controller.references.remove()
                    return true
                }
            }
            if (controller.tool == Tool.Vector) {
                if (event.key == Key.Enter) {
                    controller.commitVector()
                    return true
                }
                if (event.key == Key.Escape) {
                    controller.cancelVector()
                    return true
                }
                if (event.key == Key.Delete || event.key == Key.Backspace) {
                    controller.vectorObjectCommand("delete_vector_object")
                    return true
                }
            }
            if (controller.tool == Tool.Assistant) {
                if (event.key == Key.Enter) {
                    controller.commitAssistant()
                    return true
                }
                if (event.key == Key.Escape) {
                    controller.cancelAssistant()
                    return true
                }
            }
            if (controller.tool == Tool.LineGenerator) {
                if (event.key == Key.Enter) {
                    controller.commitLineGenerator()
                    return true
                }
                if (event.key == Key.Escape) {
                    controller.cancelLineGenerator()
                    return true
                }
            }
            if (controller.adjustmentPreview != null) {
                if (event.key == Key.Enter) {
                    controller.commitAdjustment()
                    return true
                }
                if (event.key == Key.Escape) {
                    controller.cancelAdjustment()
                    return true
                }
            }
            if (controller.tool == Tool.Gradient) {
                if (event.key == Key.Enter) {
                    controller.commitGradient()
                    return true
                }
                if (event.key == Key.Escape) {
                    if (!controller.busy) {
                        controller.cancelGradient()
                        controller.tool = Tool.Brush
                    }
                    return true
                }
            }
            if (controller.tool == Tool.TransformLayer) {
                if (!event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) {
                    val direction =
                        when (event.key) {
                            Key.DirectionLeft -> -1 to 0
                            Key.DirectionRight -> 1 to 0
                            Key.DirectionUp -> 0 to -1
                            Key.DirectionDown -> 0 to 1
                            else -> null
                        }
                    if (direction != null) {
                        controller.nudgeLayerTransform(
                            direction.first,
                            direction.second,
                            event.isShiftPressed,
                        )
                        return true
                    }
                }
                if (event.key == Key.Enter) {
                    controller.commitLayerTransform()
                    return true
                }
                if (event.key == Key.Escape) {
                    if (!controller.busy) {
                        controller.cancelLayerMove(exit = true)
                        controller.tool = Tool.Brush
                    }
                    return true
                }
            }
            if (event.key == Key.Escape && controller.tool == Tool.Select) {
                controller.cancelLayerMove(exit = true)
                controller.cancelSelectionGesture()
                return true
            }
            if (event.key == Key.Escape && controller.tool == Tool.LassoFill) {
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
                ShortcutAction.TransformLayer -> controller.tool = Tool.TransformLayer
                ShortcutAction.Gradient -> controller.tool = Tool.Gradient
                ShortcutAction.Smudge -> controller.tool = Tool.Smudge
                ShortcutAction.Select -> controller.tool = Tool.Select
                ShortcutAction.MagicWand -> {
                    controller.tool = Tool.Select
                    controller.selectionKind = SelectionKind.MagicWand
                }
                ShortcutAction.Fill -> controller.tool = Tool.Fill
                ShortcutAction.LassoFill -> controller.tool = Tool.LassoFill
                ShortcutAction.PixelPencil -> controller.selectPreset(BrushPreset.PixelPencil)
                ShortcutAction.PixelGrid ->
                    controller.changeCanvasGrid(
                        controller.preferences.canvasGrid.copy(
                            pixels = !controller.preferences.canvasGrid.pixels
                        )
                    )
                ShortcutAction.Fit -> controller.viewport = Viewport()
                ShortcutAction.ZoomIn ->
                    controller.viewport = controller.viewport.zoomBy(StudioDefaults.zoomStep)
                ShortcutAction.ZoomOut ->
                    controller.viewport = controller.viewport.zoomBy(1f / StudioDefaults.zoomStep)
                ShortcutAction.BrushSmaller ->
                    controller.brush =
                        controller.brush.copy(
                            size =
                                (if (controller.brush.preset.raster == BrushRaster.Antialiased)
                                        controller.brush.size / StudioDefaults.brushSizeStep
                                    else (controller.brush.size.roundToInt() - 1).toFloat())
                                    .coerceAtLeast(StudioDefaults.minBrushSize)
                        )
                ShortcutAction.BrushLarger ->
                    controller.brush =
                        controller.brush.copy(
                            size =
                                (if (controller.brush.preset.raster == BrushRaster.Antialiased)
                                        controller.brush.size * StudioDefaults.brushSizeStep
                                    else (controller.brush.size.roundToInt() + 1).toFloat())
                                    .coerceAtMost(StudioDefaults.maxBrushSize)
                        )
                ShortcutAction.Undo -> controller.command("undo")
                ShortcutAction.AnimationTimeline -> {
                    val wasVisible = controller.animationTimelineVisible
                    val wasTransitioning = controller.animationTransition
                    val panelOpen = panel == StudioPanel.Animation && inspectorVisible
                    controller.toggleAnimationTimeline()
                    if (
                        controller.animationTimelineVisible != wasVisible ||
                            (!wasTransitioning && controller.animationTransition)
                    ) {
                        controller.animationTimelineVisible = !panelOpen
                        if (panelOpen) {
                            inspectorVisible = false
                        } else requestedPanel = StudioPanel.Animation
                    }
                }
                ShortcutAction.PlayAnimation -> {
                    if (controller.animationPlaying) controller.stopAnimation()
                    else controller.startAnimation()
                }
                ShortcutAction.PreviousFrame -> controller.selectAdjacentAnimationFrame(-1)
                ShortcutAction.NextFrame -> controller.selectAdjacentAnimationFrame(1)
                ShortcutAction.AddFrame -> controller.addAnimationFrame()
                ShortcutAction.OnionSkin -> {
                    if (
                        controller.document.animation != null &&
                            !controller.animationPlaying &&
                            !controller.drawingInput
                    )
                        controller.onionEnabled = !controller.onionEnabled
                }
                ShortcutAction.Redo -> controller.command("redo")
                ShortcutAction.Deselect -> controller.clearSelection()
                ShortcutAction.InvertSelection -> controller.invertSelection()
                ShortcutAction.Save -> controller.file(StudioController.FileAction.Save)
                ShortcutAction.Open -> dialog = StudioDialog.Open
                ShortcutAction.Export -> dialog = StudioDialog.Export
                ShortcutAction.New -> dialog = StudioDialog.New
                ShortcutAction.Copy -> controller.clipboard(ClipboardAction.Copy)
                ShortcutAction.CopyVisible -> controller.clipboard(ClipboardAction.CopyVisible)
                ShortcutAction.Cut -> controller.clipboard(ClipboardAction.Cut)
                ShortcutAction.PasteReference ->
                    controller.references.load(true, controller.document)
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
                        val previewConfirmation =
                            (controller.adjustmentPreview != null ||
                                controller.tool == Tool.TransformLayer ||
                                controller.tool == Tool.Gradient) &&
                                (event.key == Key.Enter || event.key == Key.Escape)
                        if (
                            previewConfirmation ||
                                ((event.isCtrlPressed ||
                                    event.isMetaPressed ||
                                    event.isAltPressed) && !clipboardShortcut(event))
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
                val showDocument =
                    maxWidth >=
                        if (windowControls != null) 1100.dp
                        else if (controller.clipboardAvailable) 880.dp else 820.dp
                val headerCompact = compact || (windowControls != null && maxWidth < 1000.dp)
                fun openPanel(next: StudioPanel, toggle: Boolean = true) {
                    if (controller.drawingInput) return
                    val activePanel =
                        if (panel == StudioPanel.ToolOptions) controller.defaultToolPanel()
                        else panel
                    if (toggle && activePanel == next && inspectorVisible) {
                        inspectorVisible = false
                        return
                    }
                    panel = next
                    inspectorVisible = true
                }
                fun toggleInspector() {
                    if (controller.drawingInput) return
                    inspectorVisible = !inspectorVisible
                }
                LaunchedEffect(
                    wide,
                    inspectorVisible,
                    controller.busy,
                    controller.document.revision,
                    controller.adjustmentPreview != null,
                ) {
                    if ((wide || !inspectorVisible) && !controller.busy) focus.requestFocus()
                }
                LaunchedEffect(requestedPanel, wide, controller.drawingInput) {
                    if (!controller.drawingInput) {
                        requestedPanel?.let { openPanel(it, toggle = false) }
                        requestedPanel = null
                    }
                }
                @Composable
                fun tools() {
                    WorkspaceToolDock(
                        controller,
                        workspace,
                        { openPanel(StudioPanel.Colors) },
                    )
                }
                @Composable
                fun inspector() {
                    Row(
                        Modifier.width(StudioTheme.inspectorWidth)
                            .fillMaxHeight()
                            .background(StudioTheme.panel)
                    ) {
                        if (workspace.inspectorPosition == InspectorPosition.Right)
                            VerticalDivider(
                                thickness = StudioTheme.hairline,
                                color = StudioTheme.border,
                            )
                        Inspector(
                            controller,
                            panel,
                            { openPanel(it) },
                            Modifier.weight(1f).fillMaxHeight(),
                            onAnimationExport = { dialog = StudioDialog.AnimationExport },
                        )
                        if (workspace.inspectorPosition == InspectorPosition.Left)
                            VerticalDivider(
                                thickness = StudioTheme.hairline,
                                color = StudioTheme.border,
                            )
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    StudioHeader(
                        controller,
                        headerCompact,
                        showDocument,
                        { dialog = it },
                        inspectorExpanded = inspectorVisible,
                        onToggleInspector = { toggleInspector() },
                        windowControls = windowControls,
                        onTitleDragRegion = onTitleDragRegion,
                    )
                    HorizontalDivider(thickness = StudioTheme.hairline, color = StudioTheme.border)
                    if (workspace.toolDock == ToolDockPosition.Top) tools()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (workspace.toolDock == ToolDockPosition.Left) tools()
                        if (
                            wide &&
                                inspectorVisible &&
                                workspace.inspectorPosition == InspectorPosition.Left
                        )
                            inspector()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            CanvasWorkspace(controller, Modifier.fillMaxSize())
                            if (!controller.drawingInput)
                                ContextToolDock(
                                    controller,
                                    { openPanel(StudioPanel.Colors, toggle = it) },
                                    { openPanel(StudioPanel.ToolOptions) },
                                    Modifier.align(Alignment.BottomCenter)
                                        .padding(
                                            horizontal = StudioTheme.canvasDockInset,
                                            vertical =
                                                StudioTheme.floatingShadow +
                                                    StudioTheme.workspacePadding,
                                        ),
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
                        if (
                            wide &&
                                inspectorVisible &&
                                workspace.inspectorPosition == InspectorPosition.Right
                        )
                            inspector()
                        if (workspace.toolDock == ToolDockPosition.Right) tools()
                    }
                    if (workspace.toolDock == ToolDockPosition.Bottom) tools()
                }
                if (!wide && inspectorVisible)
                    StudioModal("工作台", Glyph.Layers, { inspectorVisible = false }, width = 380.dp) {
                        Inspector(
                            controller,
                            panel,
                            { openPanel(it) },
                            Modifier.weight(1f).fillMaxWidth(),
                            onAnimationExport = { dialog = StudioDialog.AnimationExport },
                        )
                    }
                StudioDialogs(controller, dialog, updates) { dialog = StudioDialog.None }
            }
        }
    }
}
