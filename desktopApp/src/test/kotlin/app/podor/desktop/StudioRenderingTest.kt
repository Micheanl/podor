package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.ExportFormat
import app.podor.domain.ExportOptions
import app.podor.domain.Language
import app.podor.domain.LayerBlendMode
import app.podor.domain.Viewport
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.CanvasWorkspace
import app.podor.ui.ExportSettings
import app.podor.ui.Inspector
import app.podor.ui.LayerBlendOptions
import app.podor.ui.PodorTheme
import app.podor.ui.StudioApp
import app.podor.ui.StudioPanel
import app.podor.ui.StudioTheme
import app.podor.ui.trValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class StudioRenderingTest {
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun canvasBounds(scene: ImageComposeScene): Rect =
        scene.semanticsOwners
            .asSequence()
            .flatMap { descendants(it.rootSemanticsNode) }
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" }
            .boundsInWindow

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
            val controller = openController(project, scope)
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
                            Viewport(
                                zoom = 4f,
                                pan = pan,
                                rotation = rotation,
                                mirrored = rotation != 0f,
                            )
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
    fun sidebarInspectorReservesItsWidthAndKeepsTheCanvasInteractive() =
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
                    ImageComposeScene(1360, 900, density = Density(1f)) { StudioApp(controller) }
                }
            var frame = 0L
            fun render() {
                scene.render(frame++ * 16_666_667L).close()
            }
            fun click(label: String) =
                scene.clickControl(trValue(label, controller.preferences.language), ::render)
            fun whiteCanvasEdge() {
                val bounds = canvasBounds(scene)
                scene.render(frame++ * 16_666_667L).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    for (point in
                        listOf(
                            Offset(bounds.right - 8f, bounds.top + 24f),
                            Offset(bounds.right - 8f, bounds.bottom - 24f),
                        )) {
                        assertEquals(
                            1f,
                            pixels[point.x.toInt(), point.y.toInt()].red,
                            0.005f,
                            "Inspector hides canvas at $point",
                        )
                    }
                }
            }
            fun assertPaletteVisible() {
                val bounds =
                    scene.controlBounds(trValue("饱和度与明度色板", controller.preferences.language))
                scene.render(frame++ * 16_666_667L).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val colorful =
                        (bounds.left.toInt() until bounds.right.toInt() step 2).sumOf { x ->
                            (bounds.top.toInt() until bounds.bottom.toInt() step 2).count { y ->
                                val color = pixels[x, y]
                                maxOf(color.red, color.green, color.blue) -
                                    minOf(color.red, color.green, color.blue) > 0.3f
                            }
                        }
                    assertTrue(colorful > 500, "Palette selection was not retained")
                }
            }
            try {
                val revision =
                    withContext(Dispatchers.Main) {
                        repeat(35) { render() }
                        val collapsed = canvasBounds(scene)
                        whiteCanvasEdge()
                        click("展开面板")
                        val expanded = canvasBounds(scene)
                        assertEquals(collapsed.left, expanded.left)
                        assertEquals(collapsed.top, expanded.top)
                        assertEquals(collapsed.bottom, expanded.bottom)
                        assertEquals(
                            StudioTheme.inspectorWidth.value,
                            collapsed.width - expanded.width,
                            0.5f,
                        )
                        assertEquals(collapsed.right, 1360f)
                        val surface = Offset(expanded.right + 3f, expanded.top + 3f)
                        scene.render(frame++ * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            assertEquals(
                                StudioTheme.panel.red,
                                pixels[surface.x.toInt(), surface.y.toInt()].red,
                                0.005f,
                            )
                            val path = Path.of("build/reports/screenshots/desktop-sidebar.png")
                            Files.createDirectories(path.parent)
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(path, it.bytes)
                            }
                        }
                        whiteCanvasEdge()
                        scene.sendPointerEvent(PointerEventType.Press, surface)
                        scene.sendPointerEvent(PointerEventType.Release, surface)
                        scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        controller.document.revision
                    }
                delay(150)
                withContext(Dispatchers.Main) {
                    assertEquals(
                        revision,
                        controller.document.revision,
                        "Panel surface painted through to canvas",
                    )
                    click("颜色")
                    assertPaletteVisible()
                    val brush = controller.brush
                    val pixels = controller.frame
                    val viewport = controller.viewport
                    click("图层")
                    click("颜色")
                    assertPaletteVisible()
                    click("收起面板")
                    whiteCanvasEdge()
                    scene.render(frame++ * 16_666_667L).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(
                                Path.of("build/reports/screenshots/desktop-collapsed.png"),
                                it.bytes,
                            )
                        }
                    }
                    click("展开面板")
                    assertPaletteVisible()
                    repeat(3) {
                        click("收起面板")
                        click("展开面板")
                    }
                    assertPaletteVisible()
                    assertEquals(brush, controller.brush)
                    assertEquals(viewport, controller.viewport)
                    assertSame(pixels, controller.frame)
                    assertEquals(revision, controller.document.revision)
                    assertFalse(scene.hasInvalidations(), "Panel toggle keeps rendering while idle")
                }
                val points =
                    withContext(Dispatchers.Main) {
                        val bounds = canvasBounds(scene)
                        listOf(
                            Offset(bounds.right - 8f, bounds.top + 24f),
                            Offset(bounds.right - 8f, bounds.bottom - 24f),
                        )
                    }
                for (point in points) {
                    val before =
                        withContext(Dispatchers.Main) {
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
            val controller = openController(project, scope)
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
                    val activePanel = mutableStateOf(StudioPanel.ToolOptions)
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
                                val buttons =
                                    StudioPanel.entries
                                        .map { turning.controlBounds(it.label) }
                                        .sortedWith(compareBy({ it.top }, { it.left }))
                                val gaps =
                                    buttons.zipWithNext().filter { (left, right) ->
                                        kotlin.math.abs(left.top - right.top) < 0.5f &&
                                            left.right < right.left
                                    }
                                assertTrue(gaps.isNotEmpty())
                                for ((left, right) in gaps) {
                                    val x = ((left.right + right.left) / 2).toInt()
                                    val y = left.center.y.toInt()
                                    assertEquals(
                                        StudioTheme.panel.red,
                                        pixels[x, y].red,
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
                    for (format in controller.exportFormats) {
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
    fun exportCardsKeepLayeredSettingsSeparateAndFinishTheirAnimation() =
        runBlocking<Unit> {
            NativeLoader.load()
            val engine = createNativeEngine(64, 32)
            val project =
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = openController(project, scope)
            try {
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
                }
                withContext(Dispatchers.Main) {
                    val options = mutableStateOf(ExportOptions())
                    val scene =
                        ImageComposeScene(432, 720) {
                            PodorTheme(Language.English) {
                                Surface(color = StudioTheme.panel) {
                                    Box(Modifier.padding(16.dp)) {
                                        ExportSettings(controller, options.value) {
                                            options.value = it
                                        }
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
                                Files.write(
                                    Path.of("build/reports/screenshots/export-ora-en.png"),
                                    png.bytes,
                                )
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

    private suspend fun openController(
        project: ByteArray,
        scope: CoroutineScope,
    ): StudioController {
        val controller =
            withContext(Dispatchers.Main) { StudioController(MemoryFiles(project), scope) }
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
        }
        withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
        withTimeout(10_000) {
            while (
                !withContext(Dispatchers.Main) {
                    controller.document.revision > 0 && !controller.busy
                }
            ) delay(10)
        }
        return controller
    }
}
