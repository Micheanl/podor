package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import app.podor.domain.*
import app.podor.presentation.StudioController

@Composable
fun WorkspaceToolDock(
    controller: StudioController,
    appearance: WorkspaceAppearance,
    onColors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vertical = appearance.toolDock in listOf(ToolDockPosition.Left, ToolDockPosition.Right)
    val padding = StudioTheme.workspacePadding
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .background(StudioTheme.panel)
            .drawBehind {
                val width = StudioTheme.hairline.toPx()
                val inset = width / 2
                val (start, end) =
                    when (appearance.toolDock) {
                        ToolDockPosition.Left ->
                            Offset(this.size.width - inset, 0f) to
                                Offset(this.size.width - inset, this.size.height)
                        ToolDockPosition.Right ->
                            Offset(inset, 0f) to Offset(inset, this.size.height)
                        ToolDockPosition.Top ->
                            Offset(0f, this.size.height - inset) to
                                Offset(this.size.width, this.size.height - inset)
                        ToolDockPosition.Bottom ->
                            Offset(0f, inset) to Offset(this.size.width, inset)
                    }
                drawLine(StudioTheme.border, start, end, strokeWidth = width)
            }
            .padding(padding)
            .then(
                if (vertical) Modifier.width(StudioTheme.controlSize).fillMaxHeight()
                else Modifier.height(StudioTheme.controlSize).fillMaxWidth()
            )
    ) {
        val available = if (vertical) maxHeight else maxWidth
        val slots = with(density) { available.roundToPx() / StudioTheme.controlSize.roundToPx() }
        val showColors = slots >= 2
        val utilityCount = 7 + if (controller.clipboardAvailable) 1 else 0
        val tools = appearance.orderedTools().filter { workspaceToolAvailable(controller, it) }
        val visibleTools = tools.filter { it.name !in appearance.hiddenTools }
        val showView =
            slots >=
                visibleTools.size +
                    utilityCount +
                    (if (showColors) 1 else 0) +
                    (if (visibleTools.size != tools.size) 1 else 0)
        val needsMore =
            visibleTools.size + (if (showColors) 1 else 0) + (if (showView) utilityCount else 0) >
                slots || visibleTools.size != tools.size || !showView || !showColors
        val reserved =
            (if (needsMore) 1 else 0) +
                (if (showColors) 1 else 0) +
                (if (showView) utilityCount else 0)
        val capacity = (slots - reserved).coerceAtLeast(0)
        val visible = visibleTools.take(capacity)
        val overflow = tools.filter { it !in visible }
        val content: @Composable () -> Unit = {
            visible.forEach { tool -> key(tool) { WorkspaceToolButton(controller, tool) } }
            if (needsMore)
                WorkspaceToolMenu(
                    controller,
                    overflow,
                    controller.tool !in visible,
                    onColors = if (showColors) null else onColors,
                    includeView = !showView,
                )
            if (showView) {
                ViewportControls(
                    controller.viewport,
                    controller.shortcutLabel(ShortcutAction.Fit),
                    iconsOnly = true,
                ) {
                    controller.viewport = it
                }
                CanvasBackgroundMenu(controller)
                CanvasGridControls(controller)
                if (controller.clipboardAvailable) ClipboardMenu(controller)
                ReferenceMenu(controller)
            }
            if (showColors)
                Box(Modifier.size(StudioTheme.controlSize), contentAlignment = Alignment.Center) {
                    ColorSwatch(controller.brush.color, true) {
                        if (!controller.drawingInput) onColors()
                    }
                }
        }
        if (vertical)
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                content()
            }
        else
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                content()
            }
    }
}

@Composable
internal fun WorkspaceToolMenu(
    controller: StudioController,
    tools: List<Tool>,
    selected: Boolean = false,
    onColors: (() -> Unit)? = null,
    includeView: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton(Glyph.More, "更多工具", selected = selected, enabled = !controller.drawingInput) {
            expanded = !expanded
        }
        StudioDropdownMenu(expanded, { expanded = false }) {
            FlowRow(
                Modifier.width(StudioTheme.controlSize * 4 + StudioTheme.brushSettingsGap * 2)
                    .padding(StudioTheme.brushSettingsGap),
                maxItemsInEachRow = 4,
            ) {
                tools.forEach { tool ->
                    ToolButton(
                        workspaceToolGlyph(tool),
                        tool.label,
                        selected = controller.tool == tool,
                        enabled = workspaceToolEnabled(controller, tool),
                        plain = true,
                    ) {
                        selectWorkspaceTool(controller, tool)
                        expanded = false
                    }
                }
                if (onColors != null)
                    ToolButton(
                        Glyph.Palette,
                        "颜色",
                        enabled = !controller.drawingInput,
                        plain = true,
                    ) {
                        expanded = false
                        onColors()
                    }
            }
            if (includeView) {
                androidx.compose.material3.HorizontalDivider(
                    color = StudioTheme.border,
                    thickness = StudioTheme.hairline,
                )
                Column(
                    Modifier.width(StudioTheme.controlSize * 4 + StudioTheme.brushSettingsGap * 2)
                        .padding(StudioTheme.brushSettingsGap)
                ) {
                    ViewportControls(
                        controller.viewport,
                        controller.shortcutLabel(ShortcutAction.Fit),
                        iconsOnly = true,
                    ) {
                        controller.viewport = it
                    }
                    Row {
                        CanvasBackgroundMenu(controller)
                        CanvasGridControls(controller)
                        if (controller.clipboardAvailable) ClipboardMenu(controller)
                        ReferenceMenu(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceToolButton(controller: StudioController, tool: Tool) {
    val shortcut = workspaceToolShortcut(tool)
    val enabled = workspaceToolEnabled(controller, tool)
    val quick = tool in listOf(Tool.Brush, Tool.Eraser, Tool.Smudge, Tool.LassoFill)
    var expanded by remember { mutableStateOf(false) }
    fun open() {
        if (!enabled) return
        controller.tool = tool
        expanded = true
    }
    Box(
        if (!quick) Modifier
        else
            Modifier.pointerInput(controller, tool, enabled) {
                awaitPointerEventScope {
                    var secondary = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.buttons.isSecondaryPressed || secondary) {
                            if (!secondary && event.buttons.isSecondaryPressed) open()
                            secondary = event.buttons.isSecondaryPressed
                            if (enabled) event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        ToolButton(
            workspaceToolGlyph(tool),
            if (shortcut != null) controller.shortcutLabel(shortcut) else tool.label,
            selected = controller.tool == tool,
            enabled = enabled,
            onLongClick = if (quick) ({ open() }) else null,
        ) {
            if (quick && controller.tool == tool) {
                controller.tool = tool
                expanded = !expanded
            } else selectWorkspaceTool(controller, tool)
        }
        if (expanded)
            QuickBrushPopup(controller, Offset.Zero, besideTool = true) { expanded = false }
    }
}

private fun workspaceToolAvailable(controller: StudioController, tool: Tool): Boolean =
    when (tool) {
        Tool.Vector ->
            controller.document.layers.any {
                it.id == controller.document.active && it.kind == LayerKind.Vector
            }
        Tool.Assistant -> controller.document.maxDrawingAssistants > 0
        Tool.LineGenerator -> controller.document.maxGeneratedLines > 0
        else -> true
    }

private fun workspaceToolEnabled(controller: StudioController, tool: Tool): Boolean =
    !controller.drawingInput &&
        !controller.animationPlaying &&
        !controller.animationTransition &&
        (tool != Tool.LineGenerator ||
            (!controller.busy &&
                controller.document.colorMode == DocumentColorMode.Rgba &&
                controller.document.selection == null &&
                !controller.document.maskEditing &&
                controller.document.drawableLayerCount < controller.document.maxLayers &&
                controller.document.layers.size < controller.document.maxLayerNodes))

private fun selectWorkspaceTool(controller: StudioController, tool: Tool) {
    if (tool == Tool.LineGenerator) controller.prepareLineGenerator() else controller.tool = tool
}

private fun workspaceToolGlyph(tool: Tool): Glyph =
    when (tool) {
        Tool.Brush -> Glyph.Brush
        Tool.Eraser -> Glyph.Eraser
        Tool.Picker -> Glyph.Picker
        Tool.Hand -> Glyph.Hand
        Tool.Select -> Glyph.Selection
        Tool.Fill -> Glyph.Fill
        Tool.MoveLayer -> Glyph.Move
        Tool.TransformLayer -> Glyph.Transform
        Tool.Gradient -> Glyph.Gradient
        Tool.Smudge -> Glyph.Smudge
        Tool.LassoFill -> Glyph.LassoFill
        Tool.Vector -> Glyph.Vector
        Tool.Assistant -> Glyph.Assistant
        Tool.LineGenerator -> Glyph.Line
    }

private fun workspaceToolShortcut(tool: Tool): ShortcutAction? =
    when (tool) {
        Tool.Brush -> ShortcutAction.Brush
        Tool.Eraser -> ShortcutAction.Eraser
        Tool.Picker -> ShortcutAction.Picker
        Tool.Hand -> ShortcutAction.Hand
        Tool.Select -> ShortcutAction.Select
        Tool.Fill -> ShortcutAction.Fill
        Tool.MoveLayer -> ShortcutAction.MoveLayer
        Tool.TransformLayer -> ShortcutAction.TransformLayer
        Tool.Gradient -> ShortcutAction.Gradient
        Tool.Smudge -> ShortcutAction.Smudge
        Tool.LassoFill -> ShortcutAction.LassoFill
        else -> null
    }
