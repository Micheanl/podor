package app.podor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.presentation.UpdateController

@Composable
fun SettingsDialog(controller: StudioController, onDismiss: () -> Unit, updates: UpdateController? = null) {
    var tab by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf<ShortcutAction?>(null) }
    var conflict by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(recording) { if (recording != null) focus.requestFocus() }
    StudioModal("设置", Glyph.Settings, onDismiss, width = 500.dp) {
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, false)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val action = recording ?: return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    if (event.key == Key.Escape) {
                        recording = null
                        return@onPreviewKeyEvent true
                    }
                    val binding = event.shortcut() ?: return@onPreviewKeyEvent true
                    runCatching { controller.preferences.assign(action, binding) }
                        .onSuccess {
                            controller.updatePreferences(it)
                            recording = null
                            conflict = null
                        }
                        .onFailure { conflict = it.message }
                    true
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(StudioTheme.background, CircleShape)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Glyph.Settings, "通用", tab == 0) {
                    tab = 0
                    recording = null
                }
                ToolButton(Glyph.Keyboard, "快捷键", tab == 1) {
                    tab = 1
                    recording = null
                }
                ToolButton(Glyph.Plugin, "插件", tab == 2) {
                    tab = 2
                    recording = null
                }
                ToolButton(Glyph.Update, "关于与更新", tab == 3) {
                    tab = 3
                    recording = null
                }
            }
            PageTransition(
                tab,
                Modifier.weight(1f, false).height(360.dp),
            ) { activeTab ->
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (activeTab) {
                        3 -> UpdateSettings(updates) { release, installer ->
                            onDismiss()
                            controller.navigate(WorkspaceDestination.InstallUpdate(release, installer))
                        }
                        0 -> {
                            SectionLabel("外观")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Appearance.entries.forEach { appearance ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        ToolButton(
                                            if (appearance == Appearance.Dark) Glyph.Moon else Glyph.Sun,
                                            appearance.label,
                                            selected = controller.preferences.appearance == appearance,
                                            plain = true,
                                        ) {
                                            controller.updatePreferences(controller.preferences.copy(appearance = appearance))
                                        }
                                    }
                                }
                            }
                            SectionLabel("语言")
                            Language.entries.forEach { language ->
                                ChoiceSurface(
                                    controller.preferences.language == language,
                                    {
                                        controller.updatePreferences(
                                            controller.preferences.copy(language = language)
                                        )
                                    },
                                    Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 26.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (language == Language.Chinese) "简体中文" else "English",
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (controller.preferences.language == language)
                                            StudioIcon(Glyph.Check, StudioTheme.accent)
                                    }
                                }
                            }
                            SectionLabel("启动时")
                            StartupScreen.entries.forEach { screen ->
                                ChoiceSurface(
                                    controller.preferences.startupScreen == screen,
                                    { controller.updatePreferences(controller.preferences.copy(startupScreen = screen)) },
                                    Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tr(screen.label), Modifier.weight(1f), fontSize = 13.sp)
                                        if (controller.preferences.startupScreen == screen) StudioIcon(Glyph.Check, StudioTheme.accent)
                                    }
                                }
                            }
                            SectionLabel("数位板输入")
                            TabletInputMode.entries.forEach { mode ->
                                ChoiceSurface(
                                    controller.preferences.tabletInputMode == mode,
                                    { controller.updatePreferences(controller.preferences.copy(tabletInputMode = mode)) },
                                    Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tr(mode.label), Modifier.weight(1f), fontSize = 13.sp)
                                        if (controller.preferences.tabletInputMode == mode) StudioIcon(Glyph.Check, StudioTheme.accent)
                                    }
                                }
                            }
                            Text(tr("自动优先使用 WinTab，驱动不可用时使用 Windows Ink。笔侧键按住落笔取色。"),
                                fontSize = 12.sp, color = StudioTheme.muted)
                        }
                        1 -> {
                            ShortcutAction.entries.forEach { action ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (recording == action)
                                                StudioTheme.accent.copy(alpha = 0.15f)
                                            else StudioTheme.elevated
                                        )
                                        .clickable {
                                            recording = action
                                            conflict = null
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        tr(action.label),
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (recording == action) tr("按下快捷键")
                                        else controller.preferences.shortcut(action).display(),
                                        modifier =
                                            Modifier.background(
                                                    StudioTheme.background,
                                                    RoundedCornerShape(6.dp),
                                                )
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                        fontSize = 11.sp,
                                        color = StudioTheme.accent,
                                    )
                                }
                            }
                            conflict?.let {
                                Text(tr(it), fontSize = 11.sp, color = StudioTheme.accent)
                            }
                            StudioTextButton({
                                controller.updatePreferences(
                                    controller.preferences.copy(shortcuts = emptyMap())
                                )
                                recording = null
                            }) {
                                ButtonLabel(tr("恢复默认"))
                            }
                        }
                        2 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ToolButton(Glyph.Folder, "导入笔刷包", enabled = !controller.busy) {
                                    controller.file(StudioController.FileAction.ImportBrushes)
                                }
                                ToolButton(Glyph.Export, "导出笔刷包", enabled = !controller.busy) {
                                    controller.file(StudioController.FileAction.ExportBrushes)
                                }
                            }
                            if (controller.preferences.plugins.isEmpty())
                                Text(
                                    tr("导入 JSON 笔刷扩展包"),
                                    fontSize = 12.sp,
                                    color = StudioTheme.muted,
                                )
                            controller.preferences.plugins.forEach { pack ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(StudioTheme.elevated)
                                        .padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pack.name, fontSize = 12.sp)
                                        Text(
                                            "${pack.brushes.size}",
                                            fontSize = 10.sp,
                                            color = StudioTheme.muted,
                                        )
                                    }
                                    Switch(
                                        pack.enabled,
                                        { enabled ->
                                            controller.updatePreferences(
                                                controller.preferences.copy(
                                                    plugins =
                                                        controller.preferences.plugins.map {
                                                            if (it.id == pack.id)
                                                                it.copy(enabled = enabled)
                                                            else it
                                                        }
                                                )
                                            )
                                        },
                                    )
                                    ToolButton(Glyph.Trash, "移除插件") {
                                        controller.updatePreferences(
                                            controller.preferences.copy(
                                                plugins =
                                                    controller.preferences.plugins.filterNot {
                                                        it.id == pack.id
                                                    }
                                            )
                                        )
                                    }
                                }
                            }
                            if (controller.preferences.brushes.isNotEmpty()) {
                                SectionLabel("自定义笔刷")
                                controller.preferences.brushes.forEach { brush ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            brush.label,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        ToolButton(Glyph.Trash, "删除笔刷") {
                                            controller.updatePreferences(
                                                controller.preferences.copy(
                                                    brushes =
                                                        controller.preferences.brushes.filterNot {
                                                            it.id == brush.id
                                                        }
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
