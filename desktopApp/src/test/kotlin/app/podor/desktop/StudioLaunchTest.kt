package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.podor.ui.StudioLaunch
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class StudioLaunchTest {
    @Test
    fun readyWorkspaceKeepsFullIntroAndDissolve() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val scene =
                    ImageComposeScene(160, 160) {
                        StudioLaunch(true) { Box(Modifier.fillMaxSize().background(Color.White)) }
                    }
                try {
                    val middle = (StudioMotion.launchMillis + StudioMotion.launchHoldMillis + StudioMotion.revealMillis * 0.7f).toInt() * 60 / 1000
                    val end = (StudioMotion.launchMillis + StudioMotion.launchHoldMillis + StudioMotion.revealMillis) * 60 / 1000 + 12
                    for (frame in 0..end) {
                        scene.render(frame * 16_666_667L).use { image ->
                            if (frame == 100 || frame == middle || frame == end) {
                                val red = image.toComposeImageBitmap().toPixelMap()[8, 8].red
                                when (frame) {
                                    100 -> assertEquals(StudioTheme.background.red, red, 0.03f)
                                    middle -> assertTrue(red > 0.2f && red < 0.99f, "red=$red")
                                    end -> assertEquals(1f, red, 0.005f)
                                }
                            }
                        }
                        yield()
                    }
                } finally {
                    scene.close()
                }
            }
        }

    @Test
    fun launchWaitsForDocumentThenRevealsWorkspace() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val ready = mutableStateOf(false)
                val scene =
                    ImageComposeScene(480, 360) {
                        StudioLaunch(ready.value) {
                            Box(Modifier.fillMaxSize().background(Color.White))
                        }
                    }
                try {
                    val output = Path.of("build", "reports", "screenshots")
                    Files.createDirectories(output)
                    for (frame in 0..270) {
                        if (frame == 140) ready.value = true
                        scene.render(frame * 16_666_667L).use { image ->
                            if (frame == 135 || frame == 270) {
                                val pixel = image.toComposeImageBitmap().toPixelMap()[8, 8]
                                val expected =
                                    if (frame == 135) StudioTheme.background else Color.White
                                assertEquals(expected.red, pixel.red, 0.01f)
                            }
                            if (frame in listOf(30, 80, 135, 170, 190, 210)) {
                                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                    Files.write(output.resolve("launch-$frame.png"), png.bytes)
                                }
                            }
                        }
                        yield()
                    }
                } finally {
                    scene.close()
                }
            }
        }
}
