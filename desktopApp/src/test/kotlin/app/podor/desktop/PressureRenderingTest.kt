package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class PressureRenderingTest {
    @Test
    fun pressureControlsKeepOtherBrushSettingsAndStopRenderingWhenIdle() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                for (language in Language.entries) {
                    val initial =
                        BrushPreset.Ink.copy(grain = 0.6f, size = 30f, stabilization = 0.4f)
                    val brush = mutableStateOf(initial)
                    val scene =
                        ImageComposeScene(420, 550) {
                            PodorTheme(language) {
                                Surface(color = StudioTheme.panel) {
                                    BrushPressureControls(
                                        brush.value,
                                        Modifier.fillMaxWidth().padding(24.dp),
                                    ) {
                                        brush.value = it
                                    }
                                }
                            }
                        }
                    var frame = 0L
                    fun render() {
                        scene.render(frame++ * 16_666_667L).close()
                    }
                    fun click(x: Float, y: Float) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        repeat(30) { render() }
                    }
                    try {
                        render()
                        click(70f, 276f)
                        assertEquals(1f, brush.value.pressureCurve)
                        click(350f, 276f)
                        assertEquals(-1f, brush.value.pressureCurve)
                        click(210f, 276f)
                        assertEquals(0f, brush.value.pressureCurve)
                        click(300f, 356f)
                        assertTrue(brush.value.pressureCurve in 0.3f..0.7f)
                        click(37f, 456f)
                        assertEquals(0f, brush.value.sizePressure, 0.03f)
                        click(383f, 525f)
                        assertEquals(1f, brush.value.opacityPressure, 0.03f)
                        assertEquals(
                            initial,
                            brush.value.copy(
                                pressureCurve = 0f,
                                sizePressure = 1f,
                                opacityPressure = 0f,
                            ),
                        )
                        assertFalse(scene.hasInvalidations())
                        scene.render(frame++ * 16_666_667L).use { image ->
                            val output =
                                Path.of(
                                    "build/reports/screenshots/pressure-${language.name.lowercase()}.png"
                                )
                            Files.createDirectories(output.parent)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(output, it.bytes)
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        }

    @Test
    fun pressureReachesNativePixelsAndSavedBrushes() =
        runBlocking<Unit> {
            NativeLoader.load()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var preferences: ByteArray? = null
            var pack: ByteArray? = null
            val files =
                object : ProjectFiles {
                    override suspend fun open(): ByteArray? = null

                    override suspend fun save(bytes: ByteArray, png: Boolean) = true

                    override suspend fun writePreferences(bytes: ByteArray) {
                        preferences = bytes
                    }

                    override suspend fun saveBrushPack(bytes: ByteArray): Boolean {
                        pack = bytes
                        return true
                    }
                }
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
                }
            suspend fun dot(preset: BrushPreset, y: Float) {
                val revision = withContext(Dispatchers.Main) { controller.document.revision }
                withContext(Dispatchers.Main) {
                    controller.selectPreset(preset)
                    controller.begin(Offset(64.5f, y), 0.5f)
                    controller.end()
                }
                awaitState {
                    controller.document.revision > revision &&
                        controller.previews.revision == controller.document.revision
                }
            }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) {
                    controller.command("new") {
                        put("width", 512)
                        put("height", 384)
                    }
                }
                awaitState { controller.document.width == 512 && !controller.busy }
                val ink = BrushPreset.Ink.copy(size = 64f, hardness = 1f, pressureCurve = -1f)
                dot(ink, 32.5f)
                dot(ink.copy(pressureCurve = 1f, sizePressure = 0f, opacityPressure = 1f), 96.5f)
                withContext(Dispatchers.Main) {
                    val pixels =
                        controller.frame.tiles.values
                            .single { it.x == 0 && it.y == 0 }
                            .image
                            .toPixelMap()
                    assertEquals(0f, pixels[64, 32].red, 0.005f)
                    assertEquals(1f, pixels[64, 32].alpha, 0.005f)
                    assertEquals(0f, pixels[74, 32].alpha, 0.005f)
                    assertEquals(191 / 255f, pixels[94, 96].alpha, 0.005f)
                    assertEquals(0f, pixels[94, 96].red, 0.005f)
                    controller.saveBrush("Pressure test")
                    controller.file(StudioController.FileAction.ExportBrushes)
                }
                awaitState { preferences != null && pack != null }
                val saved = BrushPack.parse(assertNotNull(pack)).brushes.single()
                assertEquals(1f, saved.pressureCurve)
                assertEquals(0f, saved.sizePressure)
                assertEquals(1f, saved.opacityPressure)
                assertEquals(
                    saved,
                    Json.decodeFromString<Preferences>(assertNotNull(preferences).decodeToString())
                        .brushes
                        .single(),
                )
                for ((index, id) in listOf("pressure-ink", "glaze").withIndex()) {
                    val revision = withContext(Dispatchers.Main) { controller.document.revision }
                    withContext(Dispatchers.Main) {
                        controller.selectPreset(BrushPreset.entries.single { it.id == id })
                        controller.brush = controller.brush.copy(color = 0xFF8B2942)
                        val y = 195f + index * 112f
                        controller.begin(Offset(48f, y), 0.1f)
                        for (batch in (1..104).chunked(8)) {
                            controller.points(
                                batch.map { i ->
                                    val t = i / 104f
                                    Triple(
                                        48f + 416f * t,
                                        y + sin(t * 6.283f) * 20f,
                                        0.1f + sin(t * 3.14159f) * 0.9f,
                                    )
                                }
                            )
                        }
                        controller.end()
                    }
                    awaitState {
                        controller.document.revision > revision &&
                            controller.previews.revision == controller.document.revision
                    }
                }
                withContext(Dispatchers.Main) {
                    val scene =
                        ImageComposeScene(850, 640) {
                            PodorTheme {
                                Row(Modifier.fillMaxSize()) {
                                    Surface(
                                        Modifier.width(330.dp).fillMaxHeight(),
                                        color = StudioTheme.panel,
                                    ) {
                                        BrushPressureControls(
                                            controller.brush.preset,
                                            Modifier.padding(24.dp),
                                        ) {}
                                    }
                                    CanvasWorkspace(controller, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    try {
                        repeat(3) { scene.render(it * 16_666_667L).close() }
                        assertFalse(scene.hasInvalidations())
                        scene.render(50_000_000).use { image ->
                            val output = Path.of("build/reports/screenshots/pressure-strokes.png")
                            Files.createDirectories(output.parent)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(output, it.bytes)
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
                assertNull(withContext(Dispatchers.Main) { controller.error })
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }
}
