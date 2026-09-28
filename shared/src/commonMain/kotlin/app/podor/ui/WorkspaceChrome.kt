package app.podor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.resources.Res
import app.podor.resources.brand
import org.jetbrains.compose.resources.painterResource

@Composable
fun StudioHeader(
    controller: StudioController,
    compact: Boolean,
    showDocument: Boolean,
    onDialog: (StudioDialog) -> Unit,
    inspectorExpanded: Boolean = false,
    onToggleInspector: (() -> Unit)? = null,
    windowControls: (@Composable () -> Unit)? = null,
    onTitleDragRegion: (Rect) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val integrated = windowControls != null
    CompositionLocalProvider(LocalHeaderButtons provides true) {
        Row(
            Modifier.fillMaxWidth()
                .borderTrail(integrated)
                .height(if (integrated) StudioTheme.windowTitleHeight else 64.dp)
                .background(StudioTheme.panel)
                .padding(
                    start = if (integrated) 12.dp else if (compact) 16.dp else 24.dp,
                    end = if (integrated) 0.dp else if (compact) 16.dp else 24.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(Res.drawable.brand),
                AppIdentity.name,
                Modifier.size(if (integrated) 24.dp else 30.dp),
            )
            Spacer(Modifier.width(if (integrated || compact) 8.dp else 20.dp))
            if (!compact && !integrated) {
                Box(Modifier.width(1.dp).height(20.dp).background(StudioTheme.border))
                Spacer(Modifier.width(12.dp))
            }
            Box {
                ToolButton(Glyph.Folder, "工程菜单") { menu = true }
                StudioDropdownMenu(
                    menu,
                    { menu = false },
                ) {
                    FlowRow(Modifier.width(192.dp).padding(8.dp), maxItemsInEachRow = 4) {
                        ToolButton(Glyph.Plus, "新建画布", plain = true) { onDialog(StudioDialog.New); menu = false }
                        ToolButton(Glyph.Folder, "打开工程 / 图片", plain = true) { onDialog(StudioDialog.Open); menu = false }
                        ToolButton(Glyph.Save, "保存工程", plain = true) { controller.file(StudioController.FileAction.Save); menu = false }
                        ToolButton(Glyph.Export, "导出图像", plain = true) { onDialog(StudioDialog.Export); menu = false }
                        ToolButton(Glyph.Copy, "另存为", plain = true) { controller.file(StudioController.FileAction.SaveAs); menu = false }
                        ToolButton(Glyph.Trash, "清空当前图层", enabled = controller.document.layers.none {
                            it.id == controller.document.active && (it.locked || it.alphaLocked)
                        }, plain = true) { onDialog(StudioDialog.Clear); menu = false }
                        ToolButton(Glyph.Settings, "设置", plain = true) { onDialog(StudioDialog.Settings); menu = false }
                        ToolButton(Glyph.Deselect, controller.shortcutLabel(ShortcutAction.Deselect),
                            enabled = controller.document.selection != null, plain = true) { controller.clearSelection(); menu = false }
                        if (integrated && compact) {
                            ToolButton(Glyph.Undo, controller.shortcutLabel(ShortcutAction.Undo), enabled = controller.document.canUndo, plain = true) { controller.command("undo"); menu = false }
                            ToolButton(Glyph.Redo, controller.shortcutLabel(ShortcutAction.Redo), enabled = controller.document.canRedo, plain = true) { controller.command("redo"); menu = false }
                        }
                        if (compact) ReferenceMenu(controller)
                        if (compact && controller.clipboardAvailable) ClipboardMenu(controller)
                    }
                }
            }
            ToolButton(Glyph.Home, "作品首页") { controller.home() }
            if (!integrated || !compact)
                ToolButton(Glyph.Settings, "设置") { onDialog(StudioDialog.Settings) }
            if (!compact && controller.clipboardAvailable) ClipboardMenu(controller)
            if (!compact) ReferenceMenu(controller)
            Box(
                Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                    if (integrated) onTitleDragRegion(it.boundsInWindow())
                },
                contentAlignment = Alignment.Center,
            ) {
                if (showDocument)
                    Row(
                        if (integrated) Modifier
                        else
                            Modifier.background(
                                    StudioTheme.background.copy(alpha = 0.5f),
                                    CircleShape,
                                )
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            (controller.projectReference?.name ?: tr("未命名")) +
                                if (controller.hasUnsavedChanges) " ·" else "",
                            Modifier.widthIn(max = 160.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${controller.document.width} × ${controller.document.height} px",
                            fontSize = 10.sp,
                            color = StudioTheme.muted,
                        )
                    }
            }
            if (!integrated || !compact)
                Row(Modifier.padding(horizontal = 2.dp)) {
                    ToolButton(
                        Glyph.Undo,
                        controller.shortcutLabel(ShortcutAction.Undo),
                        enabled = controller.document.canUndo,
                    ) {
                        controller.command("undo")
                    }
                    ToolButton(
                        Glyph.Redo,
                        controller.shortcutLabel(ShortcutAction.Redo),
                        enabled = controller.document.canRedo,
                    ) {
                        controller.command("redo")
                    }
                }
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                ToolButton(
                    Glyph.Save,
                    controller.shortcutLabel(ShortcutAction.Save),
                    enabled = controller.ready && !controller.busy,
                ) {
                    controller.file(StudioController.FileAction.Save)
                }
            }
            Spacer(Modifier.width(8.dp))
            ToolButton(
                Glyph.Export,
                controller.shortcutLabel(ShortcutAction.Export),
                prominent = true,
                enabled = controller.ready && !controller.busy,
            ) {
                onDialog(StudioDialog.Export)
            }
            if (onToggleInspector != null) {
                Spacer(Modifier.width(8.dp))
                ToolButton(
                    if (inspectorExpanded) Glyph.Sidebar else Glyph.SidebarClosed,
                    if (inspectorExpanded) "收起面板" else "展开面板",
                    selected = inspectorExpanded,
                    onClick = onToggleInspector,
                )
            }
            if (windowControls != null)
                Box(Modifier.width(StudioTheme.windowButtonWidth * 3)) { windowControls() }
        }
    }
}

@Composable
fun StudioTools(controller: StudioController, compact: Boolean = false) {
    var more by remember { mutableStateOf(false) }
    QuickToolButton(controller, Tool.Brush, Glyph.Brush, ShortcutAction.Brush)
    QuickToolButton(controller, Tool.Eraser, Glyph.Eraser, ShortcutAction.Eraser)
    if (compact)
        Box {
            ToolButton(Glyph.More, "更多工具", controller.tool !in listOf(Tool.Brush, Tool.Eraser)) {
                more = true
            }
            StudioDropdownMenu(
                more,
                { more = false },
            ) {
                FlowRow(Modifier.width(192.dp).padding(8.dp), maxItemsInEachRow = 4) {
                    listOf(
                        Tool.Select to Glyph.Selection,
                        Tool.Fill to Glyph.Fill,
                        Tool.Picker to Glyph.Picker,
                        Tool.Hand to Glyph.Hand,
                        Tool.MoveLayer to Glyph.Move,
                        Tool.TransformLayer to Glyph.Transform,
                        Tool.Gradient to Glyph.Gradient,
                        Tool.Smudge to Glyph.Smudge,
                    ).forEach { (tool, glyph) ->
                        ToolButton(glyph, tool.label, selected = controller.tool == tool, plain = true) {
                            controller.tool = tool
                            more = false
                        }
                    }
                }
            }
        }
    else {
        ToolButton(
            Glyph.Selection,
            controller.shortcutLabel(ShortcutAction.Select),
            controller.tool == Tool.Select,
        ) {
            controller.tool = Tool.Select
        }
        ToolButton(
            Glyph.Fill,
            controller.shortcutLabel(ShortcutAction.Fill),
            controller.tool == Tool.Fill,
        ) {
            controller.tool = Tool.Fill
        }
        ToolButton(
            Glyph.Picker,
            controller.shortcutLabel(ShortcutAction.Picker),
            controller.tool == Tool.Picker,
        ) {
            controller.tool = Tool.Picker
        }
        ToolButton(
            Glyph.Hand,
            controller.shortcutLabel(ShortcutAction.Hand),
            controller.tool == Tool.Hand,
        ) {
            controller.tool = Tool.Hand
        }
        ToolButton(
            Glyph.Move,
            controller.shortcutLabel(ShortcutAction.MoveLayer),
            controller.tool == Tool.MoveLayer,
        ) {
            controller.tool = Tool.MoveLayer
        }
        ToolButton(
            Glyph.Transform,
            controller.shortcutLabel(ShortcutAction.TransformLayer),
            controller.tool == Tool.TransformLayer,
        ) {
            controller.tool = Tool.TransformLayer
        }
        ToolButton(
            Glyph.Gradient,
            controller.shortcutLabel(ShortcutAction.Gradient),
            controller.tool == Tool.Gradient,
        ) {
            controller.tool = Tool.Gradient
        }
        QuickToolButton(controller, Tool.Smudge, Glyph.Smudge, ShortcutAction.Smudge)
    }
}

@Composable
private fun QuickToolButton(
    controller: StudioController,
    tool: Tool,
    glyph: Glyph,
    shortcut: ShortcutAction,
) {
    var expanded by remember { mutableStateOf(false) }
    fun open() {
        controller.tool = tool
        expanded = true
    }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                var secondary = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.buttons.isSecondaryPressed || secondary) {
                        if (!secondary && event.buttons.isSecondaryPressed) open()
                        secondary = event.buttons.isSecondaryPressed
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    ) {
        ToolButton(
            glyph,
            controller.shortcutLabel(shortcut),
            controller.tool == tool,
            onLongClick = { open() },
        ) {
            if (controller.tool == tool) expanded = !expanded else controller.tool = tool
        }
        if (expanded)
            QuickBrushPopup(
                controller,
                androidx.compose.ui.geometry.Offset.Zero,
                besideTool = true,
            ) {
                expanded = false
            }
    }
}

@Composable
fun CanvasFooter(controller: StudioController, modifier: Modifier = Modifier) {
    val active = controller.document.layers.firstOrNull { it.id == controller.document.active }
    Row(
        modifier.fillMaxWidth().height(46.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active?.locked == true || active?.alphaLocked == true) {
            StudioIcon(
                if (active.locked) Glyph.Lock else Glyph.AlphaLock,
                StudioTheme.accent,
                Modifier.size(StudioTheme.layerStatusIconSize),
            )
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        CanvasBackgroundMenu(controller)
        ViewportControls(controller.viewport, controller.shortcutLabel(ShortcutAction.Fit)) {
            controller.viewport = it
        }
    }
}
