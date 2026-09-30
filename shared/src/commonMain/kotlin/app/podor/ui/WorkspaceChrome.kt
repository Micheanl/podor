package app.podor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
    val baseDensity = LocalStudioBaseDensity.current ?: LocalDensity.current
    val scale = LocalWorkspaceAppearance.current.scale
    CompositionLocalProvider(LocalHeaderButtons provides true) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(StudioTheme.headerHeight).background(StudioTheme.panel)
        ) {
            val chromeWidth = if (integrated) StudioTheme.windowButtonWidth * 3 / scale else 0.dp
            val available = maxWidth - chromeWidth - StudioTheme.headerPadding * 2
            val minimal = available < StudioTheme.controlSize * 8 + StudioTheme.headerBrandSize
            val showSettings = !minimal && (!integrated || !compact)
            val showSave = !minimal && !compact
            val showExport = available >= StudioTheme.controlSize * 3
            val showInspector =
                onToggleInspector != null && available >= StudioTheme.controlSize * 2
            Row(
                Modifier.fillMaxSize()
                    .padding(
                        start = StudioTheme.headerPadding,
                        end = if (integrated) 0.dp else StudioTheme.headerPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!minimal) {
                    Image(
                        painterResource(Res.drawable.brand),
                        AppIdentity.name,
                        Modifier.size(StudioTheme.headerBrandSize),
                    )
                    Spacer(Modifier.width(StudioTheme.headerGap))
                }
                Box {
                    ToolButton(Glyph.Folder, "工程菜单") { menu = !menu }
                    StudioDropdownMenu(
                        menu,
                        { menu = false },
                    ) {
                        FlowRow(
                            Modifier.width(
                                    StudioTheme.controlSize * 4 + StudioTheme.menuPadding * 2
                                )
                                .padding(StudioTheme.menuPadding),
                            maxItemsInEachRow = 4,
                        ) {
                            ToolButton(Glyph.Plus, "新建画布", plain = true) {
                                onDialog(StudioDialog.New)
                                menu = false
                            }
                            ToolButton(Glyph.Folder, "打开工程 / 图片", plain = true) {
                                onDialog(StudioDialog.Open)
                                menu = false
                            }
                            if (!showSave)
                                ToolButton(
                                    Glyph.Save,
                                    "保存工程",
                                    plain = true,
                                    enabled = controller.ready && !controller.busy,
                                ) {
                                    controller.file(StudioController.FileAction.Save)
                                    menu = false
                                }
                            if (!showExport)
                                ToolButton(
                                    Glyph.Export,
                                    "导出图像",
                                    plain = true,
                                    enabled = controller.ready && !controller.busy,
                                ) {
                                    onDialog(StudioDialog.Export)
                                    menu = false
                                }
                            ToolButton(Glyph.Copy, "另存为", plain = true) {
                                controller.file(StudioController.FileAction.SaveAs)
                                menu = false
                            }
                            if (controller.asepriteExportAvailable)
                                ToolButton(
                                    Glyph.Aseprite,
                                    "导出 Aseprite 工程副本",
                                    plain = true,
                                    enabled =
                                        !controller.busy &&
                                            !controller.drawingInput &&
                                            !controller.animationTransition &&
                                            !controller.animationPlaying,
                                ) {
                                    menu = false
                                    val capability = controller.document.asepriteExport
                                    if (
                                        capability != null &&
                                            (capability.editableIssues.isNotEmpty() ||
                                                capability.blockingIssues.isNotEmpty())
                                    )
                                        onDialog(StudioDialog.AsepriteExport)
                                    else controller.exportAseprite()
                                }
                            ToolButton(
                                Glyph.Trash,
                                "清空当前图层",
                                enabled =
                                    controller.document.layers.any {
                                        it.id == controller.document.active &&
                                            !it.effectiveLocked &&
                                            (!it.alphaLocked || controller.document.maskEditing) &&
                                            (it.kind == LayerKind.Raster ||
                                                controller.document.maskEditing)
                                    },
                                plain = true,
                            ) {
                                onDialog(StudioDialog.Clear)
                                menu = false
                            }
                            if (!showSettings)
                                ToolButton(Glyph.Settings, "设置", plain = true) {
                                    onDialog(StudioDialog.Settings)
                                    menu = false
                                }
                            ToolButton(
                                Glyph.Deselect,
                                controller.shortcutLabel(ShortcutAction.Deselect),
                                enabled = controller.document.selection != null,
                                plain = true,
                            ) {
                                controller.clearSelection()
                                menu = false
                            }
                            if ((integrated && compact) || minimal) {
                                ToolButton(
                                    Glyph.Undo,
                                    controller.shortcutLabel(ShortcutAction.Undo),
                                    enabled =
                                        controller.document.canUndo ||
                                            controller.adjustmentPreview != null,
                                    plain = true,
                                ) {
                                    controller.command("undo")
                                    menu = false
                                }
                                ToolButton(
                                    Glyph.Redo,
                                    controller.shortcutLabel(ShortcutAction.Redo),
                                    enabled = controller.document.canRedo,
                                    plain = true,
                                ) {
                                    controller.command("redo")
                                    menu = false
                                }
                            }
                            if (minimal) {
                                ToolButton(Glyph.Home, "作品首页", plain = true) {
                                    menu = false
                                    controller.home()
                                }
                            }
                            if (onToggleInspector != null && !showInspector)
                                ToolButton(
                                    if (inspectorExpanded) Glyph.Sidebar else Glyph.SidebarClosed,
                                    if (inspectorExpanded) "收起面板" else "展开面板",
                                    selected = inspectorExpanded,
                                    enabled = !controller.drawingInput,
                                    plain = true,
                                ) {
                                    menu = false
                                    onToggleInspector()
                                }
                        }
                    }
                }
                if (!minimal) {
                    ToolButton(Glyph.Home, "作品首页") { controller.home() }
                    if (showSettings)
                        ToolButton(Glyph.Settings, "设置") { onDialog(StudioDialog.Settings) }
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                        if (integrated) onTitleDragRegion(it.boundsInWindow())
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (showDocument && !minimal)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioTheme.workspaceGap),
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
                if (!minimal && (!integrated || !compact)) {
                    ToolButton(
                        Glyph.Undo,
                        controller.shortcutLabel(ShortcutAction.Undo),
                        enabled =
                            controller.document.canUndo || controller.adjustmentPreview != null,
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
                if (showSave)
                    ToolButton(
                        Glyph.Save,
                        controller.shortcutLabel(ShortcutAction.Save),
                        enabled = controller.ready && !controller.busy,
                    ) {
                        controller.file(StudioController.FileAction.Save)
                    }
                if (showExport)
                    ToolButton(
                        Glyph.Export,
                        controller.shortcutLabel(ShortcutAction.Export),
                        enabled = controller.ready && !controller.busy,
                    ) {
                        onDialog(StudioDialog.Export)
                    }
                if (onToggleInspector != null && showInspector)
                    ToolButton(
                        if (inspectorExpanded) Glyph.Sidebar else Glyph.SidebarClosed,
                        if (inspectorExpanded) "收起面板" else "展开面板",
                        selected = inspectorExpanded,
                        enabled = !controller.drawingInput,
                        onClick = onToggleInspector,
                    )
                if (windowControls != null)
                    CompositionLocalProvider(LocalDensity provides baseDensity) {
                        Box(Modifier.width(StudioTheme.windowButtonWidth * 3)) { windowControls() }
                    }
            }
        }
    }
}
