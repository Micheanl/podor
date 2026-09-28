package app.podor.desktop

import app.podor.desktop.engine.NativeLoader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.domain.ExportFormat
import app.podor.domain.ExportOptions
import app.podor.domain.Language
import app.podor.domain.LayerBlendMode
import app.podor.domain.Viewport
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.CanvasWorkspace
import app.podor.ui.PodorTheme
import app.podor.ui.ExportSettings
import app.podor.ui.Inspector
import app.podor.ui.LayerBlendOptions
import app.podor.ui.StudioApp
import app.podor.ui.StudioPanel
import app.podor.ui.StudioTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class StudioRenderingTest {
    private class MemoryFiles(private val project: ByteArray) : ProjectFiles {
        override val exportFormats = ExportFormat.entries

        override suspend fun open(): ByteArray? = project

        override suspend fun save(bytes: ByteArray, png: Boolean) = true
    }

    @Test
    fun zoomAndPanCannotPaintOverSurroundingControls() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(64, 64)
            val project =
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller =
                openController(project, scope)
            try {
                withTimeout(10_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            controller.ready &&
                                controller.previews.revision == controller.document.revision
                        }
                    ) delay(10)
                }
                withContext(Dispatchers.Main) {
                    val surround = Color(0xFF452D50)
                    for ((pan, rotation) in
                        listOf(Offset(-35f, -25f), Offset(40f, 30f)).flatMap { pan ->
                            listOf(0f, 37f, 90f).map { pan to it }
                        }) {
                        controller.viewport =
                            Viewport(zoom = 4f, pan = pan, rotation = rotation, mirrored = rotation != 0f)
                        val scene =
                            ImageComposeScene(320, 240) {
                                Box(Modifier.fillMaxSize().background(surround)) {
                                    CanvasWorkspace(
                                        controller,
                                        Modifier.offset(80.dp, 60.dp).size(160.dp, 120.dp),
                                    )
                                }
                            }
                        try {
                            scene.render().close()
                            val pixels =
                                scene.render(16_000_000).toComposeImageBitmap().toPixelMap()
                            fun unchanged(x: Int, y: Int) {
                                assertEquals(
                                    surround.red,
                                    pixels[x, y].red,
                                    0.005f,
                                    "canvas escaped at $x,$y",
                                )
                                assertEquals(
                                    surround.blue,
                                    pixels[x, y].blue,
                                    0.005f,
                                    "canvas escaped at $x,$y",
                                )
                            }
                            for (x in 0 until 320) {
                                unchanged(x, 59)
                                unchanged(x, 180)
                            }
                            for (y in 0 until 240) {
                                unchanged(79, y)
                                unchanged(240, y)
                            }
                            assertEquals(1f, pixels[160, 120].red, 0.005f)
                        } finally {
                            scene.close()
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun floatingInspectorLeavesCanvasVisibleAndInteractiveOutsideItsSurface() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(64, 64)
            val project =
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = openController(project, scope)
            val scene =
                withContext(Dispatchers.Main) {
                    controller.viewport = Viewport(zoom = 4f)
                    ImageComposeScene(1360, 900) { StudioApp(controller) }
                }
            try {
                val revision =
                    withContext(Dispatchers.Main) {
                        scene.render(0).close()
                        scene.render(16_666_667L).use { image ->
                            assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[1200, 93].red, 0.005f, "Inspector should start collapsed")
                        }
                        scene.sendPointerEvent(PointerEventType.Press, Offset(1314f, 32f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(1314f, 32f))
                        scene.render(33_333_334L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            for (point in listOf(1200 to 70, 1200 to 890, 1350 to 300)) {
                                assertEquals(
                                    1f,
                                    pixels[point.first, point.second].red,
                                    0.005f,
                                    "Inspector margin hides canvas at $point",
                                )
                            }
                            assertEquals(StudioTheme.panel.red, pixels[1200, 93].red, 0.005f)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(
                                    Path.of("build/reports/screenshots/desktop-overlay.png"),
                                    it.bytes,
                                )
                            }
                        }
                        scene.sendPointerEvent(PointerEventType.Press, Offset(1200f, 93f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(1200f, 93f))
                        controller.document.revision
                    }
                delay(150)
                withContext(Dispatchers.Main) {
                    assertEquals(
                        revision,
                        controller.document.revision,
                        "Panel surface painted through to canvas",
                    )
                    var frame = 2L
                    fun settle() {
                        repeat(35) { scene.render(frame++ * 16_666_667L).close() }
                    }
                    fun click(x: Float, y: Float) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        settle()
                    }
                    fun assertPaletteVisible() {
                        scene.render(frame++ * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            var colorful = 0
                            for (x in 1070..1310 step 2) {
                                for (y in 240..500 step 2) {
                                    val color = pixels[x, y]
                                    if (
                                        maxOf(color.red, color.green, color.blue) -
                                            minOf(color.red, color.green, color.blue) > 0.3f
                                    )
                                        colorful++
                                }
                            }
                            assertTrue(colorful > 500, "Palette selection was not retained")
                        }
                    }
                    click(1158f, 184f)
                    assertPaletteVisible()
                    val brush = controller.brush
                    val pixels = controller.frame
                    val viewport = controller.viewport
                    click(1300f, 124f)
                    assertPaletteVisible()
                    click(1314f, 32f)
                    scene.render(frame++ * 16_666_667L).use { image ->
                        assertEquals(
                            1f,
                            image.toComposeImageBitmap().toPixelMap()[1200, 93].red,
                            0.005f,
                            "Inspector did not collapse",
                        )
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(
                                Path.of("build/reports/screenshots/desktop-collapsed.png"),
                                it.bytes,
                            )
                        }
                    }
                    click(1314f, 32f)
                    assertPaletteVisible()
                    repeat(3) {
                        click(1314f, 32f)
                        click(1314f, 32f)
                    }
                    assertPaletteVisible()
                    assertEquals(brush, controller.brush)
                    assertEquals(viewport, controller.viewport)
                    assertSame(pixels, controller.frame)
                    assertEquals(revision, controller.document.revision)
                    assertFalse(scene.hasInvalidations(), "Panel toggle keeps rendering while idle")
                }
                for (point in listOf(Offset(1200f, 70f), Offset(1200f, 890f))) {
                    val before = withContext(Dispatchers.Main) {
                        val currentRevision = controller.document.revision
                        scene.sendPointerEvent(PointerEventType.Press, point)
                        scene.sendPointerEvent(PointerEventType.Release, point)
                        currentRevision
                    }
                    withTimeout(5_000) {
                        while (
                            !withContext(Dispatchers.Main) { controller.document.revision > before }
                        ) delay(10)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    scene.close()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }

    @Test
    fun fractionalZoomHasNoTileSeamsAndAllLayoutsRender() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(256, 256)
            val project =
                try {
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"begin","brush":{"size":100,"opacity":1,"hardness":1,"color":[80,123,245],"eraser":false}}"""
                            .encodeToByteArray(),
                    )
                    val samples =
                        ByteBuffer.allocate(24)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putFloat(20f)
                            .putFloat(128f)
                            .putFloat(1f)
                            .putFloat(236f)
                            .putFloat(128f)
                            .putFloat(1f)
                            .array()
                    engine.call(EngineOperation.SAMPLES, samples)
                    engine.call(EngineOperation.COMMAND, """{"type":"end"}""".encodeToByteArray())
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller =
                openController(project, scope)
            try {
                withTimeout(10_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            controller.ready &&
                                controller.previews.revision == controller.document.revision
                        }
                    ) delay(10)
                }
                withContext(Dispatchers.Main) {
                    val canvas =
                        ImageComposeScene(1001, 733) {
                            CanvasWorkspace(controller, Modifier.fillMaxSize())
                        }
                    try {
                        val bitmap = canvas.render().toComposeImageBitmap().toPixelMap()
                        for (x in 300..700) {
                            assertEquals(80f / 255, bitmap[x, 366].red, 0.005f, "tile seam at x=$x")
                            assertEquals(
                                123f / 255,
                                bitmap[x, 366].green,
                                0.005f,
                                "tile seam at x=$x",
                            )
                        }
                    } finally {
                        canvas.close()
                    }
                    val output = Path.of("build", "reports", "screenshots")
                    Files.createDirectories(output)
                    for ((name, size) in
                        listOf(
                            "desktop" to (1360 to 960),
                            "tablet" to (900 to 700),
                            "small-desktop" to (680 to 600),
                            "phone" to (393 to 852),
                            "phone-landscape" to (852 to 393),
                        )) {
                        val scene =
                            ImageComposeScene(size.first, size.second) { StudioApp(controller) }
                        try {
                            scene.render().close()
                            scene.render(16_000_000).use { image ->
                                val png = image.encodeToData(EncodedImageFormat.PNG)!!
                                Files.write(output.resolve("$name.png"), png.bytes)
                                png.close()
                            }
                        } finally {
                            scene.close()
                        }
                    }
                    for (panel in StudioPanel.entries) {
                        val scene =
                            ImageComposeScene(330, 760) {
                                PodorTheme {
                                    Surface(color = StudioTheme.panel) {
                                        Inspector(controller, panel, {}, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        try {
                            scene.render().close()
                            scene.render(16_000_000).use { image ->
                                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                    Files.write(
                                        output.resolve("panel-${panel.name.lowercase()}.png"),
                                        png.bytes,
                                    )
                                }
                            }
                        } finally {
                            scene.close()
                        }
                    }
                    val activePanel = mutableStateOf(StudioPanel.Brushes)
                    val turning =
                        ImageComposeScene(330, 760) {
                            PodorTheme {
                                Surface(color = StudioTheme.panel) {
                                    Inspector(
                                        controller,
                                        activePanel.value,
                                        { activePanel.value = it },
                                        Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    try {
                        for (frame in 0..32) {
                            if (frame == 2) activePanel.value = StudioPanel.Colors
                            turning.render(frame * 16_666_667L).use { image ->
                                val pixels = image.toComposeImageBitmap().toPixelMap()
                                for (x in listOf(86, 165, 244)) {
                                    assertEquals(
                                        StudioTheme.background.red,
                                        pixels[x, 91].red,
                                        0.01f,
                                        "selection background crossed the button gap at frame $frame, x=$x",
                                    )
                                }
                                if (frame in listOf(2, 6, 12, 30))
                                    image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                        Files.write(
                                            output.resolve("page-turn-$frame.png"),
                                            png.bytes,
                                        )
                                    }
                            }
                            yield()
                        }
                    } finally {
                        turning.close()
                    }
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    for (language in Language.entries) {
                        val scene =
                            ImageComposeScene(384, 440) {
                                PodorTheme(language) {
                                    Surface(color = StudioTheme.panel) {
                                        Box(Modifier.padding(16.dp)) {
                                            LayerBlendOptions(LayerBlendMode.Multiply) {}
                                        }
                                    }
                                }
                            }
                        try {
                            scene.render().close()
                            scene.render(16_000_000).use { image ->
                                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                    Files.write(
                                        output.resolve(
                                            "blend-modes-${language.name.lowercase()}.png"
                                        ),
                                        png.bytes,
                                    )
                                }
                            }
                        } finally {
                            scene.close()
                        }
                    }
                    for (format in ExportFormat.entries) {
                        val scene =
                            ImageComposeScene(432, 720) {
                                PodorTheme {
                                    Surface(color = StudioTheme.panel) {
                                        Box(Modifier.padding(16.dp)) {
                                            ExportSettings(
                                                controller,
                                                ExportOptions(
                                                    format,
                                                    transparent = format.supportsTransparency,
                                                ),
                                            ) {}
                                        }
                                    }
                                }
                            }
                        try {
                            scene.render().close()
                            scene.render(16_000_000).use { image ->
                                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                    Files.write(
                                        output.resolve("export-${format.extension}.png"),
                                        png.bytes,
                                    )
                                }
                            }
                        } finally {
                            scene.close()
                        }
                    }
                    val english = ImageComposeScene(1360, 960) { StudioApp(controller) }
                    try {
                        english.render().close()
                        english.render(16_000_000).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                                Files.write(output.resolve("desktop-en.png"), png.bytes)
                            }
                        }
                    } finally {
                        english.close()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun exportCardsKeepLayeredSettingsSeparateAndFinishTheirAnimation() = runBlocking<Unit> {
        NativeLoader.load()
        val engine = createNativeEngine(64, 32)
        val project = try { engine.call(EngineOperation.SAVE) } finally { engine.close() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = openController(project, scope)
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            withContext(Dispatchers.Main) {
                val options = mutableStateOf(ExportOptions())
                val scene = ImageComposeScene(432, 720) {
                    PodorTheme(Language.English) {
                        Surface(color = StudioTheme.panel) {
                            Box(Modifier.padding(16.dp)) {
                                ExportSettings(controller, options.value) { options.value = it }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { scene.render(frame++ * 16_666_667L).close() }
                fun click(x: Float, y: Float) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                    render()
                }
                try {
                    render()
                    click(80f, 400f)
                    assertEquals(ExportFormat.Ora, options.value.format)
                    click(380f, 565f)
                    assertFalse(options.value.transparent)
                    repeat(30) { render() }
                    scene.render(frame++ * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                            Files.write(Path.of("build/reports/screenshots/export-ora-en.png"), png.bytes)
                        }
                    }
                    click(215f, 320f)
                    assertEquals(ExportFormat.Jpeg, options.value.format)
                    repeat(30) { render() }
                    click(100f, 320f)
                    assertEquals(ExportFormat.Png, options.value.format)
                    repeat(30) { render() }
                    click(380f, 565f)
                    assertTrue(options.value.transparent)
                    click(215f, 400f)
                    assertEquals(ExportFormat.Tiff, options.value.format)
                    assertTrue(options.value.transparent)
                    click(350f, 400f)
                    assertEquals(ExportFormat.Bmp, options.value.format)
                    assertTrue(options.value.transparent)
                    click(80f, 480f)
                    assertEquals(ExportFormat.Psd, options.value.format)
                    repeat(30) { render() }
                    click(380f, 565f)
                    assertTrue(options.value.transparent)
                    repeat(30) { render() }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }

    private suspend fun openController(project: ByteArray, scope: CoroutineScope): StudioController {
        val controller = withContext(Dispatchers.Main) { StudioController(MemoryFiles(project), scope) }
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { controller.ready }) delay(10) }
        withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { controller.document.revision > 0 && !controller.busy }) delay(10) }
        return controller
    }
}
