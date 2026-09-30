package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.*
import app.podor.ui.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalComposeUiApi::class)
class ClippingWorkflowRenderingTest {
    private class MemoryFiles(project: ByteArray) : ProjectFiles {
        private val project = project.copyOf()
        val saved = AtomicReference<ByteArray>()
        val saves = AtomicInteger()
        val opens = AtomicInteger()

        override suspend fun open(): ByteArray {
            opens.incrementAndGet()
            return saved.get() ?: project
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(png)
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return true
        }

        override suspend fun readPreferences() =
            Json.encodeToString(
                    Preferences(language = Language.English, appearance = Appearance.Light)
                )
                .encodeToByteArray()

        override suspend fun writePreferences(bytes: ByteArray) {}
    }

    private fun project(mask: Boolean): ByteArray {
        NativeLoader.load()
        val native = createNativeEngine(384, 256)
        fun command(value: String) = native.call(EngineOperation.COMMAND, value.encodeToByteArray())
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: String) {
            command(
                """{"type":"select","rect":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}}"""
            )
            command("""{"type":"fill","x":$left,"y":$top,"color":$color,"tolerance":0}""")
            command("""{"type":"select","rect":null}""")
        }
        try {
            command("""{"type":"set_layer","id":1,"name":"Anchor","visible":true,"opacity":1}""")
            fill(272, 144, 336, 208, "[80,144,96,255]")
            command("""{"type":"add_layer"}""")
            command("""{"type":"set_layer","id":2,"name":"Base","visible":true,"opacity":1}""")
            fill(16, 16, 96, 112, "[36,92,160,128]")
            if (mask) {
                command("""{"type":"add_mask","mode":"reveal"}""")
                fill(40, 40, 72, 80, "[128,128,128,255]")
                command("""{"type":"set_mask_editing","id":2,"enabled":false}""")
            }
            command("""{"type":"add_layer"}""")
            command("""{"type":"set_layer","id":3,"name":"Glaze","visible":true,"opacity":1}""")
            fill(8, 8, 112, 120, "[210,64,80,255]")
            return native.call(EngineOperation.SAVE)
        } finally {
            native.close()
        }
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var frame = 0L
        private val view = Size(640f, 640f)
        private val samplePoints =
            listOf(Offset(60.5f, 60.5f), Offset(84.5f, 88.5f), Offset(300.5f, 180.5f))
        private val remoteKey = (2L shl 32) or 1L

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(allowError: Boolean = false, predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        if (!allowError) assertNull(controller.error)
                    }
                    delay(5)
                    if (
                        withContext(Dispatchers.Main) {
                            predicate() &&
                                !controller.busy &&
                                controller.previews.revision == controller.document.revision
                        }
                    )
                        break
                }
            }

        suspend fun settle() {
            repeat(30) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }

        suspend fun click(label: String) {
            settle()
            withContext(Dispatchers.Main) {
                val control =
                    scene.semanticsOwners
                        .asSequence()
                        .flatMap { descendants(it.rootSemanticsNode) }
                        .filter { node ->
                            node.config.contains(SemanticsActions.OnClick) &&
                                !node.boundsInWindow.isEmpty &&
                                descendants(node).any {
                                    it.config
                                        .getOrNull(SemanticsProperties.ContentDescription)
                                        ?.contains(label) == true ||
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                            text.text == label
                                        } == true
                                }
                        }
                        .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                        ?: error("Missing rendered clipping control: $label")
                assertFalse(control.config.contains(SemanticsProperties.Disabled), label)
                val point = control.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        suspend fun target(id: Int, mask: Boolean = false) {
            val layer = controller.document.layers.first { it.id == id }
            click(
                if (mask) "${layer.name} · Edit mask"
                else if (layer.mask != null) "${layer.name} · Edit layer pixels" else layer.name
            )
            waitFor { controller.document.active == id && controller.document.maskEditing == mask }
        }

        suspend fun command(type: String) =
            withContext(Dispatchers.Main) { controller.command(type) }

        fun pixel(x: Int, y: Int, value: RenderFrame = controller.frame): Color {
            val tile =
                value.tiles.values.firstOrNull { x / it.size == it.x && y / it.size == it.y }
                    ?: return Color.Transparent
            return tile.image.toPixelMap()[x % tile.size, y % tile.size]
        }

        fun pixels(value: RenderFrame = controller.frame): IntArray =
            IntArray(controller.document.width * controller.document.height).also { output ->
                for (tile in value.tiles.values) {
                    val source = IntArray(tile.image.width * tile.image.height)
                    tile.image.readPixels(source)
                    val left = tile.x * tile.size
                    val top = tile.y * tile.size
                    val width = minOf(tile.size, controller.document.width - left)
                    for (y in 0 until minOf(tile.size, controller.document.height - top)) source
                        .copyInto(
                            output,
                            (top + y) * controller.document.width + left,
                            y * tile.image.width,
                            y * tile.image.width + width,
                        )
                }
            }

        fun canvasSamples(): List<Color> =
            render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                samplePoints.map { point ->
                    val position = controller.viewport.toView(point, view, controller.document)
                    pixels[position.x.toInt(), position.y.toInt()]
                }
            }

        suspend fun enableClipping() {
            click("Clip to layer below")
            waitFor { controller.document.layers.last().clipping }
            assertEquals(2, controller.document.layers.last().clippingBase)
        }

        suspend fun prepareMove(transform: Boolean = false): LayerMovePreview {
            withContext(Dispatchers.Main) {
                controller.tool = if (transform) Tool.TransformLayer else Tool.MoveLayer
                controller.prepareLayerMove()
            }
            waitFor { controller.layerMove?.let { (it.sourceBounds != null) == transform } == true }
            return assertNotNull(controller.layerMove).also { assertNotNull(it.canonical) }
        }

        fun action(value: LayerActionPreview) = value.renderedAction?.get("action")?.jsonObject

        suspend fun assertPreviewIdle(value: LayerActionPreview) {
            val rendered = value.frame
            val request = value.renderedAction
            val remote = assertNotNull(value.original.tiles[remoteKey]).image
            assertSame(remote, assertNotNull(rendered.tiles[remoteKey]).image)
            settle()
            withContext(Dispatchers.Main) {
                assertSame(rendered, value.frame)
                assertEquals(request, value.renderedAction)
                assertFalse(scene.hasInvalidations())
            }
        }

        suspend fun assertCommitMatches(
            before: DocumentInfo,
            previewPixels: IntArray,
            scenePixels: List<Color>,
        ) {
            waitFor { controller.document.revision == before.revision + 1 }
            withContext(Dispatchers.Main) {
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.Brush
            }
            settle()
            withContext(Dispatchers.Main) {
                assertContentEquals(previewPixels, pixels())
                assertEquals(scenePixels, canvasSamples())
                assertTrue(controller.document.canUndo)
            }
        }
    }

    private suspend fun withSession(mask: Boolean = false, block: suspend Session.() -> Unit) {
        val files = MemoryFiles(project(mask))
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(1000, 640) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        Row {
                            CanvasWorkspace(controller, Modifier.weight(1f).fillMaxHeight())
                            Surface(
                                Modifier.width(360.dp).fillMaxHeight(),
                                color = StudioTheme.panel,
                            ) {
                                Box(Modifier.padding(12.dp)) { LayerControls(controller) }
                            }
                        }
                    }
                }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor {
                controller.hasCanvas &&
                    controller.document.width == 384 &&
                    controller.document.layers.size == 3
            }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val restore =
                    ImageComposeScene(1, 1) { PodorTheme(appearance = originalAppearance) {} }
                try {
                    restore.render(0).close()
                } finally {
                    restore.close()
                }
            }
            scope.cancel()
        }
    }

    @Test
    fun realClippingIconBaseOpacityAndGrayMaskPaintingPreserveBaseAlphaUndoAndVersionTenSave() =
        runBlocking {
            withSession {
                val original = controller.document
                withContext(Dispatchers.Main) { assertEquals(1f, pixel(40, 40).alpha) }
                enableClipping()
                val clippedScene =
                    withContext(Dispatchers.Main) {
                        assertEquals(original.revision + 1, controller.document.revision)
                        assertEquals(128f / 255f, pixel(40, 40).alpha)
                        assertEquals(0f, pixel(10, 10).alpha)
                        canvasSamples()
                    }
                click("Release clipping mask")
                waitFor { !controller.document.layers.last().clipping }
                withContext(Dispatchers.Main) { assertEquals(1f, pixel(10, 10).alpha) }
                command("undo")
                waitFor { controller.document.layers.last().clipping }
                withContext(Dispatchers.Main) { assertEquals(clippedScene, canvasSamples()) }
                command("redo")
                waitFor { !controller.document.layers.last().clipping }
                withContext(Dispatchers.Main) { assertEquals(1f, pixel(10, 10).alpha) }
                command("undo")
                waitFor { controller.document.layers.last().clipping }
                withContext(Dispatchers.Main) { assertEquals(clippedScene, canvasSamples()) }
                target(2)
                withContext(Dispatchers.Main) {
                    controller.setLayer(controller.document.layers[1].copy(opacity = .5f))
                }
                waitFor { controller.document.layers[1].opacity == .5f }
                withContext(Dispatchers.Main) { assertEquals(64f / 255f, pixel(40, 48).alpha) }
                click("Add mask")
                click("Reveal all")
                waitFor {
                    controller.document.maskEditing && controller.document.layers[1].mask != null
                }
                val beforePaint = controller.document
                withContext(Dispatchers.Main) {
                    controller.selectPreset(BrushPreset.PixelPencil)
                    controller.brush = controller.brush.copy(color = 0xFF808080)
                    val start =
                        controller.viewport.toView(
                            Offset(40.5f, 48.5f),
                            Size(640f, 640f),
                            controller.document,
                        )
                    val end =
                        controller.viewport.toView(
                            Offset(80.5f, 48.5f),
                            Size(640f, 640f),
                            controller.document,
                        )
                    scene.sendPointerEvent(PointerEventType.Press, start)
                    scene.sendPointerEvent(PointerEventType.Move, end)
                    scene.sendPointerEvent(PointerEventType.Release, end)
                    scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                }
                waitFor { controller.document.revision == beforePaint.revision + 1 }
                withContext(Dispatchers.Main) {
                    assertEquals(32f / 255f, pixel(60, 48).alpha)
                    assertEquals(64f / 255f, pixel(60, 47).alpha)
                }
                target(2)
                command("undo")
                waitFor { controller.document.contentId == beforePaint.contentId }
                withContext(Dispatchers.Main) { assertEquals(64f / 255f, pixel(60, 48).alpha) }
                assertEquals(0, files.saves.get())
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
                waitFor { files.saves.get() == 1 && !controller.hasUnsavedChanges }
                assertContentEquals(
                    "PODOR".encodeToByteArray() + byteArrayOf(12),
                    assertNotNull(files.saved.get()).copyOf(6),
                )
                val savedPixels = withContext(Dispatchers.Main) { pixels() }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                waitFor { files.opens.get() == 2 && !controller.document.canUndo }
                withContext(Dispatchers.Main) {
                    assertTrue(controller.document.layers.last().clipping)
                    assertEquals(2, controller.document.layers.last().clippingBase)
                    assertEquals(.5f, controller.document.layers[1].opacity)
                    assertNotNull(controller.document.layers[1].mask)
                    assertContentEquals(savedPixels, pixels())
                    assertEquals(1, files.saves.get())
                }
            }
        }

    @Test
    fun canonicalBaseClipAndGrayMaskMovesAndTransformsMatchEveryCommittedPixelAndReuseOtherTiles() =
        runBlocking {
            withSession(mask = true) {
                enableClipping()
                withContext(Dispatchers.Main) {
                    controller.selectPreset(BrushPreset.PixelPencil)
                    controller.viewport = Viewport(zoom = 1.07f, rotation = 11f, mirrored = true)
                }
                for ((id, mask) in listOf(2 to false, 3 to false, 2 to true)) {
                    target(id, mask)
                    val original = withContext(Dispatchers.Main) { pixels() }
                    val beforeMove = controller.document
                    val move = prepareMove()
                    val canonical = assertNotNull(move.canonical)
                    assertSame(controller.frame, canonical.original)
                    withContext(Dispatchers.Main) { controller.previewLayerMove(IntOffset(7, 5)) }
                    waitFor {
                        action(canonical)?.let {
                            it["dx"]?.jsonPrimitive?.int == 7 && it["dy"]?.jsonPrimitive?.int == 5
                        } == true
                    }
                    assertEquals(beforeMove, controller.document)
                    assertPreviewIdle(canonical)
                    val previewPixels = withContext(Dispatchers.Main) { pixels(canonical.frame) }
                    val previewScene = withContext(Dispatchers.Main) { canvasSamples() }
                    withContext(Dispatchers.Main) { controller.commitLayerMove() }
                    assertCommitMatches(beforeMove, previewPixels, previewScene)
                    command("undo")
                    waitFor { controller.document.contentId == beforeMove.contentId }
                    withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                    val beforeTransform = controller.document
                    val transformPreview = prepareMove(transform = true)
                    val transformed = assertNotNull(transformPreview.canonical)
                    val value =
                        assertNotNull(transformPreview.transform).let {
                            it.copy(
                                width = (it.width * .875f).roundToInt(),
                                height = (it.height * .9f).roundToInt(),
                                dx = 7.25f,
                                dy = -3.5f,
                                angle = 17f,
                                flipX = true,
                                filter = ResampleFilter.Nearest,
                            )
                        }
                    withContext(Dispatchers.Main) { controller.previewLayerTransform(value) }
                    waitFor {
                        action(transformed)?.get("transform")?.let {
                            Json.decodeFromJsonElement<LayerTransform>(it) == value
                        } == true
                    }
                    assertEquals(beforeTransform, controller.document)
                    assertPreviewIdle(transformed)
                    val transformedPixels =
                        withContext(Dispatchers.Main) { pixels(transformed.frame) }
                    val transformedScene = withContext(Dispatchers.Main) { canvasSamples() }
                    withContext(Dispatchers.Main) { controller.commitLayerTransform() }
                    assertCommitMatches(beforeTransform, transformedPixels, transformedScene)
                    command("undo")
                    waitFor { controller.document.contentId == beforeTransform.contentId }
                    withContext(Dispatchers.Main) { assertContentEquals(original, pixels()) }
                }
                assertEquals(0, files.saves.get())
            }
        }

    @Test
    fun canonicalGradientUsesTheLatestRequestAndInvalidLinesAndCancelRestoreTheOriginalCanvas() =
        runBlocking {
            withSession(mask = true) {
                enableClipping()
                withContext(Dispatchers.Main) { controller.selectPreset(BrushPreset.PixelPencil) }
                target(2, mask = true)
                val masked = controller.frame
                withContext(Dispatchers.Main) {
                    controller.tool = Tool.Gradient
                    controller.prepareGradient()
                }
                waitFor(allowError = true) { controller.error != null }
                withContext(Dispatchers.Main) {
                    assertEquals("请先切换到图层像素再使用渐变", controller.error)
                    assertNull(controller.gradientPreview)
                    assertSame(masked, controller.frame)
                    controller.dismissError()
                    controller.tool = Tool.Brush
                }
                for ((id, shape) in listOf(2 to GradientShape.Linear, 3 to GradientShape.Radial)) {
                    target(id)
                    val before = controller.document
                    val original = controller.frame
                    val originalPixels = withContext(Dispatchers.Main) { pixels() }
                    val originalScene = withContext(Dispatchers.Main) { canvasSamples() }
                    withContext(Dispatchers.Main) {
                        controller.tool = Tool.Gradient
                        controller.gradient =
                            GradientSettings(
                                shape = shape,
                                from = 0xFF44AAFF,
                                to = 0xFFFF66AA,
                                transparent = true,
                                opacity = .65f,
                            )
                        controller.prepareGradient()
                    }
                    waitFor { controller.gradientPreview != null }
                    val preview = assertNotNull(controller.gradientPreview)
                    val canonical = assertNotNull(preview.canonical)
                    assertSame(original, canonical.original)
                    val line = GradientLine(Offset(24f, 72f), Offset(112f, 72f))
                    withContext(Dispatchers.Main) {
                        controller.previewGradient(GradientLine(Offset(8f, 48f), Offset(88f, 96f)))
                        controller.previewGradient(line)
                    }
                    waitFor {
                        action(canonical)?.get("settings")?.jsonObject?.let {
                            it["start"]?.jsonArray?.map { value -> value.jsonPrimitive.float } ==
                                listOf(24f, 72f) &&
                                it["end"]?.jsonArray?.map { value -> value.jsonPrimitive.float } ==
                                    listOf(112f, 72f)
                        } == true
                    }
                    assertPreviewIdle(canonical)
                    withContext(Dispatchers.Main) {
                        assertFalse(originalPixels.contentEquals(pixels(canonical.frame)))
                        assertSame(original, controller.frame)
                        assertEquals(before, controller.document)
                        controller.previewGradient(GradientLine(line.start, line.start))
                        assertSame(original, canonical.frame)
                        assertNull(canonical.renderedAction)
                        assertEquals(originalScene, canvasSamples())
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        assertSame(original, canonical.frame)
                        assertNull(canonical.renderedAction)
                        assertEquals(originalScene, canvasSamples())
                        controller.previewGradient(line)
                    }
                    waitFor { canonical.renderedAction != null }
                    withContext(Dispatchers.Main) {
                        controller.cancelGradient()
                        controller.tool = Tool.Brush
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        assertNull(controller.gradientPreview)
                        assertSame(original, controller.frame)
                        assertContentEquals(originalPixels, pixels())
                        assertEquals(originalScene, canvasSamples())
                        assertEquals(before, controller.document)
                        controller.tool = Tool.Gradient
                        controller.prepareGradient()
                    }
                    waitFor { controller.gradientPreview != null }
                    val committed = assertNotNull(controller.gradientPreview).canonical!!
                    withContext(Dispatchers.Main) { controller.previewGradient(line) }
                    waitFor { committed.renderedAction != null }
                    val previewPixels = withContext(Dispatchers.Main) { pixels(committed.frame) }
                    val previewScene = withContext(Dispatchers.Main) { canvasSamples() }
                    withContext(Dispatchers.Main) { controller.commitGradient() }
                    assertCommitMatches(before, previewPixels, previewScene)
                    command("undo")
                    waitFor { controller.document.contentId == before.contentId }
                    withContext(Dispatchers.Main) { assertContentEquals(originalPixels, pixels()) }
                }
                assertEquals(0, files.saves.get())
            }
        }
}
