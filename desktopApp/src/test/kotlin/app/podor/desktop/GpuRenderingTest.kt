package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import app.podor.ui.StudioLaunch
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class GpuRenderingTest {
    @Test
    fun desktopLaunchUsesHardwareRenderer() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            val window =
                withContext(Dispatchers.Main) {
                    ComposeWindow().apply {
                        isUndecorated = true
                        focusableWindowState = false
                        setBounds(-3000, -2000, 1360, 900)
                        setContent {
                            StudioLaunch(true) {
                                Box(Modifier.fillMaxSize().background(StudioTheme.panel))
                            }
                        }
                        isVisible = true
                    }
                }
            try {
                delay((StudioMotion.launchHoldMillis + StudioMotion.revealMillis + 200).toLong())
                withContext(Dispatchers.Main) {
                    window.renderImmediately()
                    val api = window.renderApi.toString()
                    val report = Path.of("build", "reports", "gpu-renderer.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api\nLogo dissolve exercised in a 1360x900 native window outside the visible desktop. This is not a full-app frame-rate benchmark.\n",
                    )
                    assertTrue(
                        api in listOf("DIRECT3D", "OPENGL", "METAL"),
                        "Hardware renderer unavailable: $api",
                    )
                }
            } finally {
                withContext(Dispatchers.Main) { window.dispose() }
            }
        }
}
