package app.podor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.roundToInt
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
            DropdownMenu(
                menu,
                { menu = false },
                shape = StudioTheme.menuShape,
                containerColor = StudioTheme.panel,
            ) {
                if (integrated && compact) {
                    DropdownMenuItem(
                        { Text(controller.shortcutLabel(ShortcutAction.Undo)) },
                        {
                            controller.command("undo")
                            menu = false
                        },
                        enabled = controller.document.canUndo,
                    )
                    DropdownMenuItem(
                        { Text(controller.shortcutLabel(ShortcutAction.Redo)) },
                        {
                            controller.command("redo")
                            menu = false
                        },
                        enabled = controller.document.canRedo,
                    )
                }
                DropdownMenuItem(
                    { Text(tr("新建画布")) },
                    {
                        onDialog(StudioDialog.New)
                        menu = false
                    },
                    leadingIcon = { StudioIcon(Glyph.Plus) },
                )
                DropdownMenuItem(
                    { Text(tr("打开工程 / 图片")) },
                    {
                        onDialog(StudioDialog.Open)
                        menu = false
                    },
                    leadingIcon = { StudioIcon(Glyph.Folder) },
                )
                DropdownMenuItem(
                    { Text(tr("保存工程")) },
                    {
                        controller.file(StudioController.FileAction.Save)
                        menu = false
                    },
                    leadingIcon = { StudioIcon(Glyph.Save) },
                )
                DropdownMenuItem(
                    { Text(tr("导出图像")) },
                    {
                        onDialog(StudioDialog.Export)
                        menu = false
                    },
                    leadingIcon = { StudioIcon(Glyph.Export) },
                )
                DropdownMenuItem(
                    { Text(tr("另存为")) },
                    {
                        controller.file(StudioController.FileAction.SaveAs)
                        menu = false
                    },
                    leadingIcon = { StudioIcon(Glyph.Copy) },
                )
                HorizontalDivider(color = StudioTheme.border)
                if (compact) ReferenceMenuItems(controller) { menu = false }
                if (compact && controller.clipboardAvailable) {
                    ClipboardMenuItems(controller) { menu = false }
                    HorizontalDivider(color = StudioTheme.border)
                }
                DropdownMenuItem(
                    { Text(tr("清空当前图层")) },
                    {
                        onDialog(StudioDialog.Clear)
                        menu = false
                    },
                    enabled =
                        controller.document.layers.none {
                            it.id == controller.document.active && (it.locked || it.alphaLocked)
                        },
                )
                DropdownMenuItem(
                    { Text(tr("设置")) },
                    {
                        onDialog(StudioDialog.Settings)
                        menu = false
                    },
                )
                DropdownMenuItem(
                    { Text(controller.shortcutLabel(ShortcutAction.Deselect)) },
                    {
                        controller.clearSelection()
                        menu = false
                    },
                    enabled = controller.document.selection != null,
                )
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
                        Modifier.background(StudioTheme.background.copy(alpha = 0.5f), CircleShape)
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

@Composable
fun StudioTools(controller: StudioController, compact: Boolean = false) {
    var more by remember { mutableStateOf(false) }
    ToolButton(
        Glyph.Brush,
        controller.shortcutLabel(ShortcutAction.Brush),
        controller.tool == Tool.Brush,
    ) {
        controller.tool = Tool.Brush
    }
    ToolButton(
        Glyph.Eraser,
        controller.shortcutLabel(ShortcutAction.Eraser),
        controller.tool == Tool.Eraser,
    ) {
        controller.tool = Tool.Eraser
    }
    if (compact)
        Box {
            ToolButton(Glyph.More, "更多工具", controller.tool !in listOf(Tool.Brush, Tool.Eraser)) {
                more = true
            }
            DropdownMenu(
                more,
                { more = false },
                containerColor = StudioTheme.panel,
                shape = StudioTheme.menuShape,
            ) {
                listOf(
                        Tool.Select,
                        Tool.Fill,
                        Tool.Picker,
                        Tool.Hand,
                        Tool.MoveLayer,
                        Tool.TransformLayer,
                        Tool.Gradient,
                        Tool.Smudge,
                    )
                    .forEach { tool ->
                        DropdownMenuItem(
                            { Text(tr(tool.label)) },
                            {
                                controller.tool = tool
                                more = false
                            },
                        )
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
        ToolButton(
            Glyph.Smudge,
            controller.shortcutLabel(ShortcutAction.Smudge),
            controller.tool == Tool.Smudge,
        ) {
            controller.tool = Tool.Smudge
        }
    }
}

@Composable
fun BrushDock(controller: StudioController, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .clickable(interaction, indication = null, onClick = onClick)
            .controlFeedback(interaction, CircleShape)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(StudioTheme.panel)
            .border(1.dp, StudioTheme.border.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        StudioIcon(
            when (controller.tool) {
                Tool.Eraser -> Glyph.Eraser
                Tool.Smudge -> Glyph.Smudge
                else -> Glyph.Brush
            },
            StudioTheme.accent,
            Modifier.size(18.dp),
        )
        Text(
            tr(
                when (controller.tool) {
                    Tool.Eraser -> "橡皮"
                    Tool.Smudge -> "涂抹"
                    else -> controller.brush.preset.label
                }
            ),
            fontSize = 11.sp,
            color = StudioTheme.text,
        )
        Box(Modifier.width(1.dp).height(16.dp).background(StudioTheme.border))
        Text(
            "${controller.brush.size.roundToInt()} px",
            fontSize = 11.sp,
            color = StudioTheme.muted,
        )
        Text(
            "${((if (controller.tool == Tool.Smudge) controller.smudgeStrength else controller.brush.opacity)*100).roundToInt()}%",
            fontSize = 11.sp,
            color = StudioTheme.muted,
        )
        StudioIcon(Glyph.Chevron, StudioTheme.muted, Modifier.size(13.dp))
    }
}

@Composable
fun CanvasFooter(controller: StudioController, compact: Boolean, modifier: Modifier = Modifier) {
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
        Text(
            if (compact) "${controller.document.width} × ${controller.document.height}"
            else
                "${tr(active?.name ?: "图层")} · ${tr(if (controller.tool == Tool.Select) controller.selectionKind.label else controller.tool.label)}",
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = StudioTheme.muted.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f),
        )
        CanvasBackgroundMenu(controller)
        ViewportControls(controller.viewport, controller.shortcutLabel(ShortcutAction.Fit)) {
            controller.viewport = it
        }
    }
}
