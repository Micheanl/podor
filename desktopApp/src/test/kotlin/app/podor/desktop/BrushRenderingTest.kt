package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.BrushPreset
import app.podor.presentation.BrushPreviewCache
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class BrushRenderingTest {
    @Test
    fun directionalPresetsRenderOnCanvasAndIdlePreviews() =
        runBlocking<Unit> {
            NativeLoader.load()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val files =
                object : ProjectFiles {
                    override suspend fun open(): ByteArray? = null

                    override suspend fun save(bytes: ByteArray, png: Boolean) = true
                }
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            try {
                awaitState { controller.ready }
                val brushes = BrushPreset.entries.filter { it.followDirection }
                withContext(Dispatchers.Main) {
                    controller.command("new") {
                        put("width", 512)
                        put("height", brushes.size * 128)
                    }
                }
                awaitState { controller.document.width == 512 && !controller.busy }
                for ((index, preset) in brushes.withIndex()) {
                    val revision = withContext(Dispatchers.Main) { controller.document.revision }
                    withContext(Dispatchers.Main) {
                        controller.selectPreset(preset)
                        controller.brush = controller.brush.copy(color = 0xFF8B2942)
                        val y = 64f + index * 128f
                        controller.begin(Offset(48f, y), 0.8f)
                        val points =
                            (1..104).map {
                                val t = it / 104f
                                Triple(48f + 416f * t, y + sin(t * 6.283f) * 26f, 0.8f)
                            }
                        for (batch in points.chunked(8)) controller.points(batch)
                        controller.end()
                    }
                    awaitState {
                        controller.document.revision > revision &&
                            controller.previews.revision == controller.document.revision
                    }
                }
                for (preset in brushes) BrushPreviewCache.get(preset)
                withContext(Dispatchers.Main) {
                    val scene =
                        ImageComposeScene(850, 720) {
                            PodorTheme {
                                Surface(color = StudioTheme.panel) {
                                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            brushes.forEach { brush ->
                                                Column(Modifier.weight(1f)) {
                                                    Text(tr(brush.label))
                                                    BrushStrokePreview(
                                                        brush,
                                                        Modifier.fillMaxWidth().height(56.dp),
                                                    )
                                                }
                                            }
                                        }
                                        CanvasWorkspace(
                                            controller,
                                            Modifier.fillMaxWidth().weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    try {
                        var frame = 0L
                        repeat(40) {
                            scene.render(frame++ * 16_666_667L).close()
                            delay(2)
                        }
                        assertFalse(scene.hasInvalidations())
                        scene.render(frame * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            var painted = 0
                            for (y in 130 until 690) for (x in 40 until 810) {
                                val pixel = pixels[x, y]
                                if (pixel.red - pixel.green > 0.15f) painted++
                            }
                            assertTrue(painted > 10_000, "Canvas did not display the brush strokes")
                            val path = Path.of("build/reports/screenshots/directional-brushes.png")
                            Files.createDirectories(path.parent)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(path, it.bytes)
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                scope.cancel()
            }
        }
}
