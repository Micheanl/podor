package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import app.podor.domain.AppIdentity
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class RepositoryLinksTest {
    @Test
    fun officialIconsLinkToTheProjectRepositoriesAndHandleBrowserFailures() =
        runBlocking(Dispatchers.Main) {
            val opened = mutableListOf<String>()
            var fail = false
            val links =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        if (fail) error("Browser unavailable")
                        opened += uri
                    }
                }
            val scene =
                ImageComposeScene(520, 300) {
                    PodorTheme {
                        CompositionLocalProvider(LocalUriHandler provides links) {
                            Surface(Modifier.fillMaxSize(), color = StudioTheme.panel) {
                                Box(Modifier.padding(24.dp)) {
                                    UpdateSettings(null) { _, _ -> error("No installer") }
                                }
                            }
                        }
                    }
                }
            var frame = 0L
            fun settle() {
                repeat(35) { scene.render(frame++ * 16_666_667L).close() }
            }
            fun click(x: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 145f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, 145f))
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                settle()
            }
            try {
                settle()
                click(140f)
                click(380f)
                assertEquals(listOf(AppIdentity.githubUrl, AppIdentity.giteeUrl), opened)
                val path = Path.of("build/reports/screenshots/update-repositories.png")
                Files.createDirectories(path.parent)
                scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        Files.write(path, data.bytes)
                    }
                }
                fail = true
                click(140f)
                assertEquals(2, opened.size)
                assertFalse(scene.hasInvalidations())
            } finally {
                scene.close()
            }
        }
}
