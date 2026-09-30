package app.podor.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class LayerMaskPreviewTest {
    private val view = Size(640f, 512f)

    private fun project(signedOrigin: Boolean = false): ByteArray {
        NativeLoader.load()
        val native = createNativeEngine(64, 64)
        fun command(value: String) = native.call(EngineOperation.COMMAND, value.encodeToByteArray())
        try {
            command("""{"type":"fill","x":0,"y":0,"color":[80,120,190,255],"tolerance":0}""")
            command("""{"type":"add_mask","mode":"reveal"}""")
            command("""{"type":"select","rect":{"left":16,"top":12,"right":40,"bottom":44}}""")
            command("""{"type":"fill","x":20,"y":20,"color":[0,0,0,255],"tolerance":0}""")
            command(
                """{"type":"select_shape","selection":{"kind":"ellipse","left":34,"top":20,"right":60,"bottom":54}}"""
            )
            command("""{"type":"fill","x":48,"y":36,"color":[128,128,128,255],"tolerance":0}""")
            command("""{"type":"select","rect":null}""")
            if (signedOrigin) command("""{"type":"translate_layer","id":1,"dx":-7,"dy":-5}""")
            command("""{"type":"set_mask_editing","id":1,"enabled":false}""")
            return native.call(EngineOperation.SAVE)
        } finally {
            native.close()
        }
    }

    private inner class Session(val controller: StudioController, val scene: ImageComposeScene) {
        private var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        predicate() &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                ) delay(5)
            }

        suspend fun snapshot(): PixelMap =
            withContext(Dispatchers.Main) {
                repeat(4) { render().close() }
                render().use { it.toComposeImageBitmap().toPixelMap() }
            }

        suspend fun prepareMove(
            mask: Boolean,
            selection: Selection? = null,
            linked: Boolean = true,
        ) {
            withContext(Dispatchers.Main) {
                controller.selectLayer(1, mask)
            }
            waitFor { controller.document.maskEditing == mask }
            withContext(Dispatchers.Main) {
                controller.setLayerMask(1, linked = linked)
            }
            waitFor { controller.document.layers.first().mask?.linked == linked }
            if (selection != null) {
                withContext(Dispatchers.Main) { controller.select(selection) }
                waitFor { controller.document.selection != null }
            }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.MoveLayer
                controller.prepareLayerMove()
            }
            waitFor { controller.layerMove != null }
            assertEquals(mask, controller.layerMove!!.maskEditing)
        }

        suspend fun compareMove(offset: IntOffset, name: String) {
            val before = controller.document
            val pixels = controller.frame
            withContext(Dispatchers.Main) { controller.previewLayerMove(offset) }
            val preview = snapshot()
            assertSame(pixels, controller.frame)
            assertEquals(before, controller.document)
            withContext(Dispatchers.Main) { controller.commitLayerMove() }
            waitFor { controller.document.revision == before.revision + 1 }
            withContext(Dispatchers.Main) { controller.cancelLayerMove(exit = true) }
            compare(preview, snapshot(), name)
            assertNull(controller.error)
            withContext(Dispatchers.Main) { controller.command("undo") }
            waitFor { controller.document.revision == before.revision + 2 }
            assertEquals(before.contentId, controller.document.contentId)
        }

        fun compare(before: PixelMap, after: PixelMap, name: String, minimumY: Int = 1) {
            for (y in minimumY..62) for (x in 1..62) {
                val point =
                    controller.viewport.toView(Offset(x + .5f, y + .5f), view, controller.document)
                val px = floor(point.x).toInt()
                val py = floor(point.y).toInt()
                if (px !in 0 until 640 || py !in 0 until 512) continue
                val a = before[px, py]
                val b = after[px, py]
                val channelsA =
                    listOf(a.red * a.alpha, a.green * a.alpha, a.blue * a.alpha, a.alpha)
                val channelsB =
                    listOf(b.red * b.alpha, b.green * b.alpha, b.blue * b.alpha, b.alpha)
                for (channel in channelsA.indices) {
                    assertTrue(
                        abs(
                            (channelsA[channel] * 255f).roundToInt() -
                                (channelsB[channel] * 255f).roundToInt()
                        ) <= 1,
                        "$name differs at document $x,$y channel $channel: $a / $b",
                    )
                }
            }
        }
    }

    private suspend fun withSession(
        signedOrigin: Boolean = false,
        workspace: Boolean = false,
        block: suspend Session.() -> Unit,
    ) {
        val project = project(signedOrigin)
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Preview must not save")

                override suspend fun readPreferences() =
                    Json.encodeToString(
                            Preferences(language = Language.English, appearance = Appearance.Light)
                        )
                        .encodeToByteArray()

                override suspend fun writePreferences(bytes: ByteArray) {}
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val originalAppearance = StudioTheme.appearance
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(640, 512) {
                    PodorTheme(Language.English, controller.preferences.appearance) {
                        if (workspace) CanvasWorkspace(controller, Modifier.fillMaxSize())
                        else {
                            val move = controller.layerMove
                            val gradient = controller.gradientPreview
                            if (move != null)
                                LayerMoveOverlay(controller, move, view, Modifier.fillMaxSize())
                            else if (gradient != null)
                                GradientOverlay(controller, gradient, view, Modifier.fillMaxSize())
                            else
                                Canvas(Modifier.fillMaxSize()) {
                                    val document = controller.document
                                    val viewport = controller.viewport
                                    val origin = viewport.origin(view, document)
                                    val scale = viewport.scale(view, document)
                                    val paint =
                                        Paint().apply {
                                            isAntiAlias = false
                                            filterQuality = FilterQuality.None
                                        }
                                    withTransform({
                                        translate(origin.x, origin.y)
                                        rotate(viewport.rotation, Offset.Zero)
                                        scale(scale * viewport.horizontalSign, scale, Offset.Zero)
                                    }) {
                                        clipRect(
                                            0f,
                                            0f,
                                            document.width.toFloat(),
                                            document.height.toFloat(),
                                        ) {
                                            controller.frame.tiles.values.forEach { tile ->
                                                drawContext.canvas.drawImage(
                                                    tile.image,
                                                    Offset(
                                                        (tile.x * tile.size).toFloat(),
                                                        (tile.y * tile.size).toFloat(),
                                                    ),
                                                    paint,
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        val session = Session(controller, scene)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor {
                controller.document.width == 64 && controller.document.layers.first().mask != null
            }
            withContext(Dispatchers.Main) { controller.selectPreset(BrushPreset.PixelPencil) }
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
    fun maskOnlyLinkedUnlinkedAndSelectedPixelMovesMatchCommittedPixels() = runBlocking {
        for ((mask, linked, selection) in
            listOf(
                Triple(true, true, null),
                Triple(false, true, null),
                Triple(false, false, null),
                Triple(false, true, Selection(8, 8, 48, 52)),
            )) withSession {
            prepareMove(mask, selection, linked)
            withContext(Dispatchers.Main) {
                controller.viewport = Viewport(rotation = 17f, mirrored = true)
            }
            compareMove(
                IntOffset(11, -4),
                "mask=$mask linked=$linked selected=${selection != null}",
            )
        }
    }

    @Test
    fun selectedMaskMoveHandlesOverlapPartialCoverageAndSignedOrigins() = runBlocking {
        for (signedOrigin in listOf(false, true)) withSession(signedOrigin) {
            prepareMove(true, Selection(8, 6, 48, 52, kind = SelectionKind.Ellipse))
            val mask = controller.layerMove!!.layers.first().mask!!
            assertTrue(mask.split)
            assertTrue(mask.selectedTiles.isNotEmpty())
            if (signedOrigin) assertTrue(mask.bounds.left < 0f && mask.bounds.top < 0f)
            compareMove(IntOffset(8, 3), "selected mask signed=$signedOrigin")
        }
    }

    @Test
    fun nearestTransformsKeepTheMaskTargetAndLinkedPixelMasksInSync() = runBlocking {
        for ((mask, linked) in listOf(true to true, false to true, false to false)) withSession(
            signedOrigin = mask
        ) {
            prepareMove(mask, linked = linked)
            withContext(Dispatchers.Main) {
                controller.cancelLayerMove(exit = true)
                controller.tool = Tool.TransformLayer
                controller.prepareLayerMove()
            }
            waitFor { controller.layerMove?.transform != null }
            val source = controller.layerMove!!.sourceBounds!!
            if (mask) assertTrue(source.left < 0f && source.top < 0f)
            val revision = controller.document.revision
            withContext(Dispatchers.Main) {
                controller.previewLayerTransform(
                    LayerTransform(
                        (source.width * .75f).roundToInt(),
                        (source.height * .5f).roundToInt(),
                        dx = 5f,
                        dy = -3f,
                        angle = 90f,
                        flipX = true,
                        filter = ResampleFilter.Nearest,
                    )
                )
            }
            val preview = snapshot()
            withContext(Dispatchers.Main) { controller.commitLayerTransform() }
            waitFor { controller.layerMove == null && controller.document.revision == revision + 1 }
            compare(preview, snapshot(), "transform mask=$mask linked=$linked")
            assertNull(controller.error)
        }
    }

    @Test
    fun zeroMoveCancellationAndChangingEditTargetsPreserveCanonicalPixels() = runBlocking {
        withSession(workspace = true) {
            withContext(Dispatchers.Main) {
                controller.select(Selection(8, 6, 48, 52, kind = SelectionKind.Ellipse))
            }
            waitFor { controller.document.selection != null }
            val before = snapshot()
            prepareMove(true)
            compare(before, snapshot(), "zero selected mask move")
            val document = controller.document
            val canonical = controller.frame
            withContext(Dispatchers.Main) {
                controller.previewLayerMove(IntOffset(8, 3))
                controller.cancelLayerMove()
            }
            compare(before, snapshot(), "cancel selected mask move")
            assertSame(canonical, controller.frame)
            assertEquals(document, controller.document)
            withContext(Dispatchers.Main) { controller.tool = Tool.Brush }
            waitFor { controller.layerMove == null }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.MoveLayer
                controller.prepareLayerMove()
            }
            waitFor { controller.layerMove != null }
            val old = controller.layerMove
            withContext(Dispatchers.Main) { controller.selectLayer(1, mask = false) }
            waitFor { !controller.document.maskEditing && controller.layerMove !== old }
            assertEquals(document.contentId, controller.document.contentId)
            assertSame(canonical, controller.frame)
            if (controller.layerMove != null) assertFalse(controller.layerMove!!.maskEditing)
            assertNull(controller.error)
        }
    }

    @Test
    fun gradientIsMaskedAfterCompositingAndMatchesItsCommit() = runBlocking {
        for (selected in listOf(false, true)) withSession {
            if (selected) {
                withContext(Dispatchers.Main) {
                    controller.select(Selection(6, 10, 58, 60, kind = SelectionKind.Ellipse))
                }
                waitFor { controller.document.selection != null }
            }
            withContext(Dispatchers.Main) {
                controller.tool = Tool.Gradient
                controller.gradient =
                    GradientSettings(from = 0xFFC05030, to = 0xFF3040B0, opacity = .7f)
                controller.prepareGradient()
            }
            waitFor { controller.gradientPreview != null }
            assertNotNull(controller.gradientPreview!!.layers.first().mask)
            withContext(Dispatchers.Main) {
                controller.previewGradient(GradientLine(Offset(4f, 4f), Offset(60f, 4f)))
            }
            val preview = snapshot()
            val revision = controller.document.revision
            withContext(Dispatchers.Main) { controller.commitGradient() }
            waitFor {
                controller.gradientPreview == null && controller.document.revision == revision + 1
            }
            compare(preview, snapshot(), "gradient selected=$selected", minimumY = 10)
            assertNull(controller.error)
        }
    }
}
