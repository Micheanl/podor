package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.podor.ui.createDissolveTexture
import app.podor.ui.dissolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*

@OptIn(ExperimentalComposeUiApi::class)
class DissolveTest {
    @Test
    fun dissolvesIndividualPixelsAndPreservesBackground() =
        runBlocking<Unit> {
            val texture = withContext(Dispatchers.Default) { createDissolveTexture() }
            withContext(Dispatchers.Main) {
                val progress = mutableFloatStateOf(0f)
                val scene =
                    ImageComposeScene(96, 96) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            Box(Modifier.fillMaxSize().dissolve(texture) { progress.floatValue }) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .graphicsLayer { alpha = 0.99f }
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                try {
                    for ((frame, amount) in listOf(0f, 0.5f, 1f).withIndex()) {
                        progress.floatValue = amount
                        scene.render(frame * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            var visible = 0
                            var removed = 0
                            for (y in 0 until 96) for (x in 0 until 96) {
                                val pixel = pixels[x, y]
                                assertEquals(1f, pixel.alpha, 0.005f)
                                if (pixel.red > 0.95f) visible++
                                if (pixel.red < 0.05f) removed++
                            }
                            when (amount) {
                                0f -> assertEquals(96 * 96, visible)
                                1f -> assertEquals(96 * 96, removed)
                                else -> {
                                    assertTrue(visible > 2000, "visible=$visible")
                                    assertTrue(removed > 2000, "removed=$removed")
                                }
                            }
                        }
                    }
                } finally {
                    scene.close()
                }
            }
        }
}
