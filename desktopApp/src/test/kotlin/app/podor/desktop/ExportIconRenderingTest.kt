package app.podor.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.podor.domain.Appearance
import app.podor.domain.WorkspaceAppearance
import app.podor.ui.Glyph
import app.podor.ui.PodorTheme
import app.podor.ui.StudioIcon
import app.podor.ui.StudioTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@OptIn(ExperimentalComposeUiApi::class)
class ExportIconRenderingTest {
    @Test
    fun officialBrandIconsPreserveEverySourcePixelInBothThemesAndDisabledState() = runBlocking {
        val originalAppearance = StudioTheme.appearance
        try {
            for ((glyph, name) in
                listOf(
                    Glyph.Aseprite to "format_aseprite.png",
                    Glyph.SvgLogo to "format_svg.png",
                )) {
                val source =
                    withContext(Dispatchers.Default) {
                        Image.makeFromEncoded(
                                Files.readAllBytes(
                                    Path.of(
                                        "..",
                                        "shared",
                                        "src",
                                        "commonMain",
                                        "composeResources",
                                        "drawable",
                                        name,
                                    )
                                )
                            )
                            .use { image ->
                                val pixels = image.toComposeImageBitmap().toPixelMap()
                                Triple(
                                    image.width,
                                    image.height,
                                    IntArray(image.width * image.height) { index ->
                                        pixels[index % image.width, index / image.width].toArgb()
                                    },
                                )
                            }
                    }
                assertTrue(source.third.any { it ushr 24 == 255 })
                for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                    for (enabled in listOf(true, false)) withContext(Dispatchers.Main) {
                        val scene =
                            ImageComposeScene(source.first, source.second, density = Density(1f)) {
                                PodorTheme(
                                    appearance = appearance,
                                    workspaceAppearance = WorkspaceAppearance(reducedMotion = true),
                                ) {
                                    StudioIcon(
                                        glyph,
                                        Color.Magenta,
                                        Modifier.size(source.first.dp, source.second.dp),
                                        enabled = enabled,
                                        selected = true,
                                    )
                                }
                            }
                        try {
                            scene.render(0).close()
                            scene.render(16_666_667).use { image ->
                                val actual = image.toComposeImageBitmap().toPixelMap()
                                for (index in source.third.indices) {
                                    assertEquals(
                                        source.third[index],
                                        actual[index % source.first, index / source.first].toArgb(),
                                        "$glyph $appearance enabled=$enabled pixel=$index",
                                    )
                                }
                            }
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = originalAppearance) {} }
                try {
                    restore.render(0).close()
                } finally {
                    restore.close()
                }
            }
        }
    }
}
