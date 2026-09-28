package app.podor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController
import app.podor.resources.Res
import app.podor.resources.brand
import org.jetbrains.compose.resources.painterResource

@Composable
fun WorkspaceHome(controller: StudioController, updates: UpdateController? = null) {
    var dialog by remember { mutableStateOf(StudioDialog.None) }
    var query by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun handleShortcut(event: KeyEvent): Boolean {
        if (
            event.type != KeyEventType.KeyDown ||
                dialog != StudioDialog.None ||
                !controller.ready ||
                controller.busy ||
                controller.pendingNavigation != null
        )
            return false
        val binding = event.shortcut() ?: return false
        when {
            controller.preferences.shortcut(ShortcutAction.New) == binding ->
                dialog = StudioDialog.New
            controller.preferences.shortcut(ShortcutAction.Open) == binding ->
                controller.file(StudioController.FileAction.Open)
            controller.hasCanvas &&
                controller.preferences.shortcut(ShortcutAction.Save) == binding ->
                controller.file(StudioController.FileAction.Save)
            else -> return false
        }
        return true
    }
    val projects =
        remember(controller.recentProjects, query, byName) {
            val filtered =
                controller.recentProjects.filter {
                    it.reference.name.contains(query, ignoreCase = true)
                }
            if (byName) filtered.sortedBy { it.reference.name.lowercase() } else filtered
        }
    PodorTheme(controller.preferences.language) {
        Surface(Modifier.fillMaxSize(), color = StudioTheme.background) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .safeDrawingPadding()
                    .focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.isCtrlPressed || it.isMetaPressed || it.isAltPressed)
                            handleShortcut(it)
                        else false
                    }
                    .onKeyEvent {
                        if (!it.isCtrlPressed && !it.isMetaPressed && !it.isAltPressed)
                            handleShortcut(it)
                        else false
                    }
                    .focusable(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val narrow = maxWidth < 680.dp
                val compact = maxHeight < 700.dp
                Column(
                    Modifier.widthIn(max = StudioTheme.workspaceWidth)
                        .fillMaxSize()
                        .padding(
                            horizontal = if (narrow) 20.dp else 48.dp,
                            vertical = if (compact) 16.dp else 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(Res.drawable.brand), "podor", Modifier.size(38.dp))
                        Text(
                            "podor",
                            Modifier.padding(start = 12.dp).weight(1f),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        ToolButton(Glyph.Settings, "设置") { dialog = StudioDialog.Settings }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("作品"),
                            Modifier.weight(1f),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (narrow) {
                            ToolButton(
                                Glyph.Folder,
                                "打开作品",
                                enabled = controller.ready && !controller.busy,
                            ) {
                                controller.file(StudioController.FileAction.Open)
                            }
                            ToolButton(
                                Glyph.Plus,
                                "新建画布",
                                prominent = true,
                                enabled = controller.ready && !controller.busy,
                            ) {
                                dialog = StudioDialog.New
                            }
                        } else {
                            ActionButton(
                                "打开作品",
                                { controller.file(StudioController.FileAction.Open) },
                                enabled = controller.ready && !controller.busy,
                                glyph = Glyph.Folder,
                                primary = false,
                            )
                            Spacer(Modifier.width(12.dp))
                            ActionButton(
                                "新建画布",
                                { dialog = StudioDialog.New },
                                enabled = controller.ready && !controller.busy,
                                glyph = Glyph.Plus,
                            )
                        }
                    }
                    if (controller.hasCanvas) {
                        Surface(
                            Modifier.fillMaxWidth().clickable(enabled = !controller.busy) {
                                controller.resumeCanvas()
                            },
                            color = StudioTheme.panel,
                            shape = StudioTheme.cardShape,
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ArtworkPreview(
                                    controller.previews.images[0],
                                    controller.document.width,
                                    controller.document.height,
                                    Modifier.size(if (compact) 42.dp else 64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        controller.projectReference?.name ?: tr("未命名"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        tr(if (controller.hasUnsavedChanges) "有未保存的改动" else "当前画布"),
                                        fontSize = 12.sp,
                                        color = StudioTheme.muted,
                                    )
                                }
                                StudioIcon(Glyph.Brush, StudioTheme.accent)
                            }
                        }
                    }
                    if (controller.recentProjects.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                query,
                                { query = it },
                                Modifier.weight(1f),
                                placeholder = { Text(tr("查找作品"), fontSize = 13.sp) },
                                leadingIcon = { StudioIcon(Glyph.Search, StudioTheme.muted) },
                                singleLine = true,
                                shape = StudioTheme.cardShape,
                            )
                            StudioTextButton({ byName = !byName }) {
                                ButtonLabel(tr(if (byName) "名称" else "最近"))
                            }
                        }
                    }
                    if (!controller.ready || controller.busy)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (projects.isEmpty()) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                StudioIcon(
                                    if (query.isBlank()) Glyph.Brush else Glyph.Search,
                                    StudioTheme.muted.copy(alpha = 0.5f),
                                    Modifier.size(36.dp),
                                )
                                Text(
                                    tr(if (query.isBlank()) "从一张空白画布开始" else "没有找到作品"),
                                    color = StudioTheme.muted,
                                    fontSize = 16.sp,
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            GridCells.Adaptive(StudioTheme.projectCardWidth),
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(projects, key = { it.reference.id }) { project ->
                                ProjectCard(controller, project)
                            }
                        }
                    }
                    Text(tr("作品仅在手动保存时写入文件"), fontSize = 11.sp, color = StudioTheme.muted)
                }
                StudioDialogs(controller, dialog, updates) { dialog = StudioDialog.None }
            }
        }
    }
}

@Composable
private fun ProjectCard(controller: StudioController, project: RecentProject) {
    val preview by
        produceState<ImageBitmap?>(null, project.reference.id, project.openedAt) {
            value = controller.projectThumbnail(project.reference)
        }
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = StudioTheme.cardShape
    val rim = remember {
        Brush.linearGradient(
            listOf(StudioTheme.surfaceRim.copy(alpha = 0.5f), StudioTheme.border.copy(alpha = 0.3f))
        )
    }
    Surface(
        Modifier.fillMaxWidth()
            .clickable(interaction, indication = null, enabled = !controller.busy) {
                controller.navigate(WorkspaceDestination.Open(project.reference))
            }
            .controlFeedback(
                interaction,
                shape,
                enabled = !controller.busy,
                pressedScale = StudioMotion.cardPressScale,
            ),
        shape = shape,
        color = StudioTheme.panel,
        border = BorderStroke(StudioTheme.hairline, rim),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth()
                    .height(StudioTheme.projectPreviewHeight)
                    .background(StudioTheme.elevated)
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (preview == null) StudioIcon(Glyph.Layers, StudioTheme.muted.copy(alpha = 0.4f))
                else ArtworkPreview(preview, project.width, project.height, Modifier.fillMaxSize())
            }
            Row(
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        project.reference.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    Text(
                        "${project.width} × ${project.height}",
                        fontSize = 11.sp,
                        color = StudioTheme.muted,
                    )
                }
                Box {
                    ToolButton(Glyph.More, "作品选项") { menu = true }
                    DropdownMenu(
                        menu,
                        { menu = false },
                        shape = StudioTheme.menuShape,
                        containerColor = StudioTheme.panel,
                    ) {
                        DropdownMenuItem(
                            { Text(tr("从列表移除")) },
                            {
                                controller.forgetProject(project.reference)
                                menu = false
                            },
                            leadingIcon = { StudioIcon(Glyph.Close) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UnsavedChangesDialog(controller: StudioController) {
    if (controller.pendingNavigation == null) return
    PodorTheme(controller.preferences.language) {
        StudioModal("保存这份作品？", Glyph.Save, { controller.resolveUnsaved(UnsavedChoice.Cancel) }) {
            Text(controller.projectReference?.name ?: tr("未命名"), fontSize = 18.sp)
            Text(tr("未保存的改动会丢失。"), color = StudioTheme.muted, fontSize = 13.sp)
            controller.error?.let {
                Text(tr(it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioTextButton(
                    { controller.resolveUnsaved(UnsavedChoice.Discard) },
                    enabled = !controller.busy,
                ) {
                    ButtonLabel(tr("不保存"), color = StudioTheme.muted)
                }
                Spacer(Modifier.weight(1f))
                StudioTextButton(
                    { controller.resolveUnsaved(UnsavedChoice.Cancel) },
                    enabled = !controller.busy,
                ) {
                    ButtonLabel(tr("取消"))
                }
                ActionButton(
                    "保存",
                    { controller.resolveUnsaved(UnsavedChoice.Save) },
                    enabled = !controller.busy,
                )
            }
        }
    }
}
