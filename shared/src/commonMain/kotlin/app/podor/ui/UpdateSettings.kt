package app.podor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.UpdateController
import app.podor.resources.Res
import app.podor.resources.brand
import app.podor.resources.ic_gitee
import app.podor.resources.ic_github
import org.jetbrains.compose.resources.painterResource

@Composable
fun UpdateSettings(controller: UpdateController?, onInstall: (AppRelease, String) -> Unit) {
    val links = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(painterResource(Res.drawable.brand), AppIdentity.name, Modifier.size(76.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(AppIdentity.name, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${AppBuildInfo.version} · ${tr("稳定版")}",
                    fontSize = 12.sp,
                    color = StudioTheme.muted,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StudioTheme.repositoryLinkGap),
        ) {
            listOf(
                    Triple("GitHub", AppIdentity.githubUrl, Res.drawable.ic_github),
                    Triple("Gitee", AppIdentity.giteeUrl, Res.drawable.ic_gitee),
                )
                .forEach { (label, url, icon) ->
                    StudioButton(
                        { linkFailed = runCatching { links.openUri(url) }.isFailure },
                        Modifier.weight(1f),
                        primary = false,
                    ) {
                        Image(painterResource(icon), null, Modifier.size(StudioTheme.iconSize))
                        Spacer(Modifier.width(StudioTheme.repositoryLinkGap))
                        Text(label, color = StudioTheme.text)
                    }
                }
        }
        if (linkFailed)
            Text(
                tr("无法打开浏览器"),
                color = StudioTheme.accent,
                fontSize = StudioTheme.repositoryLinkLabelSize,
            )
        if (controller == null) return@Column
        val state = controller.state
        Column(
            Modifier.fillMaxWidth()
                .background(StudioTheme.elevated, StudioTheme.cardShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val label =
                when (state.phase) {
                    UpdatePhase.Idle -> "软件更新"
                    UpdatePhase.Checking -> "正在检查更新"
                    UpdatePhase.Current -> "已是最新版本"
                    UpdatePhase.Available -> "发现新版本"
                    UpdatePhase.Downloading -> "正在下载"
                    UpdatePhase.Downloaded -> "安装包已就绪"
                    UpdatePhase.Failed -> "暂时无法更新"
                }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(label), Modifier.weight(1f), fontSize = 14.sp)
                if (state.phase == UpdatePhase.Current || state.phase == UpdatePhase.Downloaded)
                    StudioIcon(Glyph.Check, StudioTheme.accent)
                else if (state.release != null)
                    Text(state.release.version, color = StudioTheme.accent, fontSize = 12.sp)
            }
            if (state.phase == UpdatePhase.Checking)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.phase == UpdatePhase.Downloading) {
                val progress =
                    state.received.toFloat() / (state.release?.size ?: 1).coerceAtLeast(1)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", color = StudioTheme.muted, fontSize = 12.sp)
            }
            state.problem?.let { Text(tr(it.label), color = StudioTheme.accent, fontSize = 12.sp) }
            if (state.phase == UpdatePhase.Downloaded)
                Text(
                    tr("安装前会退出 podor，完成后重新打开，不会自动重启电脑。"),
                    color = StudioTheme.muted,
                    fontSize = 12.sp,
                )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (state.phase) {
                    UpdatePhase.Checking,
                    UpdatePhase.Downloading -> StudioTextButton(controller::cancel) { Text(tr("取消")) }
                    UpdatePhase.Downloaded -> {
                        StudioTextButton(controller::reveal) { Text(tr("下载文件夹")) }
                        StudioButton({
                            val release = state.release
                            val installer = state.installer
                            if (release != null && installer != null) onInstall(release, installer)
                        }) {
                            Text(tr("安装并打开"))
                        }
                    }
                    UpdatePhase.Available -> StudioButton(controller::download) { Text(tr("下载更新")) }
                    UpdatePhase.Failed -> {
                        if (state.release != null)
                            StudioTextButton(controller::check) { Text(tr("重新检查")) }
                        StudioButton(
                            if (state.release != null) controller::download else controller::check
                        ) {
                            Text(tr("重试"))
                        }
                    }
                    else -> StudioButton(controller::check) { Text(tr("检查更新")) }
                }
            }
        }
        state.release?.let {
            if (it.notes.isNotBlank()) {
                SectionLabel("更新内容")
                Text(it.notes, fontSize = 12.sp, lineHeight = 20.sp, color = StudioTheme.muted)
            }
        }
    }
}
