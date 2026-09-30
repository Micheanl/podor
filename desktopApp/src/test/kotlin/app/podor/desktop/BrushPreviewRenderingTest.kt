package app.podor.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.Appearance
import app.podor.domain.BrushPreset
import app.podor.domain.BrushTexture
import app.podor.domain.Language
import app.podor.presentation.BrushPreviewCache
import app.podor.ui.BrushStrokePreview
import app.podor.ui.PodorTheme
import app.podor.ui.StudioTheme
import app.podor.ui.tr
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class BrushPreviewRenderingTest {
    @Test
    fun materialChangesAlterPixelsEvenWhenBrushGeometryIsIdentical() = runBlocking {
        NativeLoader.load()
        val preset = BrushPreset.Ink.copy(size = 64f, hardness = 0.75f)
        val signatures =
            BrushTexture.entries.map { texture ->
                val image = BrushPreviewCache.get(preset.copy(texture = texture))
                assertEquals(320, image.width)
                assertEquals(96, image.height)
                val alpha = image.alphaPixels()
                assertTrue(alpha.count { it > 0 } > 200, "$texture preview is empty")
                alpha.contentHashCode()
            }
        assertEquals(
            BrushTexture.entries.size,
            signatures.toSet().size,
            "Material previews must reflect different native brush masks",
        )
    }

    @Test
    fun allBrushesRenderInBothAppearancesAndReuseCachedPixelsWhenIdle() = runBlocking {
        NativeLoader.load()
        val previews = BrushPreset.entries.associateWith { BrushPreviewCache.get(it) }
        val originalAppearance = withContext(Dispatchers.Main) { StudioTheme.appearance }
        try {
            for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                val scene =
                    withContext(Dispatchers.Main) {
                        ImageComposeScene(1040, 84 + ((BrushPreset.entries.size + 1) / 2) * 148) {
                            PodorTheme(Language.English, appearance) {
                                Surface(color = StudioTheme.background) {
                                    Column(
                                        Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            "Brush library",
                                            color = StudioTheme.text,
                                            fontSize = 24.sp,
                                        )
                                        for (row in BrushPreset.entries.chunked(2)) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                for (preset in row) {
                                                    Surface(
                                                        Modifier.weight(1f)
                                                            .height(136.dp)
                                                            .border(
                                                                1.dp,
                                                                StudioTheme.border,
                                                                StudioTheme.cardShape,
                                                            ),
                                                        color = StudioTheme.panel,
                                                        shape = StudioTheme.cardShape,
                                                    ) {
                                                        Column(
                                                            Modifier.padding(14.dp),
                                                            verticalArrangement =
                                                                Arrangement.spacedBy(6.dp),
                                                        ) {
                                                            Text(
                                                                tr(preset.label),
                                                                color = StudioTheme.muted,
                                                                fontSize = 13.sp,
                                                            )
                                                            BrushStrokePreview(
                                                                preset,
                                                                Modifier.fillMaxWidth().weight(1f),
                                                                StudioTheme.text,
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
                var frame = 0L
                try {
                    repeat(50) {
                        withContext(Dispatchers.Main) {
                            scene.render(frame++ * 16_666_667L).close()
                        }
                        delay(3)
                    }
                    withContext(Dispatchers.Main) {
                        repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                        assertFalse(
                            scene.hasInvalidations(),
                            "$appearance brush previews keep repainting while idle",
                        )
                        val file =
                            Path.of(
                                "build/reports/screenshots/brush-materials-${appearance.name.lowercase()}.png"
                            )
                        Files.createDirectories(file.parent)
                        scene.render(frame++ * 16_666_667L).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(file, it.bytes)
                            }
                        }
                    }
                    for ((preset, image) in previews) {
                        assertSame(
                            image,
                            BrushPreviewCache.get(preset),
                            "Theme and tint changes must reuse the same $preset pixels",
                        )
                        assertTrue(image.alphaPixels().any { it > 0 }, "${preset.id} is empty")
                    }
                } finally {
                    withContext(Dispatchers.Main) { scene.close() }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                val scene =
                    ImageComposeScene(1, 1) {
                        PodorTheme(appearance = originalAppearance) {}
                    }
                try {
                    scene.render(0).close()
                } finally {
                    scene.close()
                }
            }
        }
    }

    private fun ImageBitmap.alphaPixels(): IntArray =
        IntArray(width * height).also { pixels ->
            readPixels(pixels)
            for (index in pixels.indices) pixels[index] = pixels[index] ushr 24
        }
}
