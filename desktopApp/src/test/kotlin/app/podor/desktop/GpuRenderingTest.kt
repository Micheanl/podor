package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
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
            val ready = mutableStateOf(false)
            val cadence = CompletableDeferred<List<Double>>()
            val window =
                withContext(Dispatchers.Main) {
                    ComposeWindow().apply {
                        isUndecorated = true
                        focusableWindowState = false
                        setBounds(-3000, -2000, 1360, 900)
                        setContent {
                            LaunchedEffect(Unit) {
                                repeat(30) { withFrameNanos {} }
                                val intervals = ArrayList<Double>()
                                var previous = withFrameNanos { it }
                                repeat(120) {
                                    val current = withFrameNanos { it }
                                    intervals.add((current - previous) / 1_000_000.0)
                                    previous = current
                                }
                                cadence.complete(intervals)
                            }
                            StudioLaunch(ready.value) {
                                Box(Modifier.fillMaxSize().background(StudioTheme.panel))
                            }
                        }
                        isVisible = true
                    }
                }
            try {
                val samples = withTimeout(20_000) { cadence.await() }
                withContext(Dispatchers.Main) { ready.value = true }
                delay((StudioMotion.revealMillis + 200).toLong())
                withContext(Dispatchers.Main) {
                    window.renderImmediately()
                    val api = window.renderApi.toString()
                    val report = Path.of("build", "reports", "gpu-renderer.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api\n1360x900 Swirl, 120 Compose frame-clock intervals after 30 warm-up frames.\nMedian: ${samples.sorted()[60]} ms\nP95: ${samples.sorted()[114]} ms\nMaximum: ${samples.max()} ms\nNot a GPU completion or full-app benchmark.\n",
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
