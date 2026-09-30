package app.podor.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.LayerMoveOverlay
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class PixelPreviewLargeCanvasTest {
    @Test
    fun sparseSixteenMegapixelMaskPreviewsReuseTilesAndBecomeIdle() = runBlocking {
        NativeLoader.load()
        val native = createNativeEngine(4096, 4096)
        val project =
            try {
                fun command(value: String) =
                    native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                command(
                    """{"type":"select","rect":{"left":2048,"top":2048,"right":2112,"bottom":2112}}"""
                )
                command(
                    """{"type":"fill","x":2050,"y":2050,"color":[80,120,190,255],"tolerance":0}"""
                )
                command("""{"type":"select","rect":null}""")
                command("""{"type":"add_mask","mode":"reveal"}""")
                command(
                    """{"type":"select","rect":{"left":2064,"top":2064,"right":2096,"bottom":2096}}"""
                )
                command("""{"type":"fill","x":2070,"y":2070,"color":[0,0,0,255],"tolerance":0}""")
                command("""{"type":"select","rect":null}""")
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Preview must not save")

                override suspend fun readPreferences() =
                    Json.encodeToString(Preferences(language = Language.English))
                        .encodeToByteArray()

                override suspend fun writePreferences(bytes: ByteArray) {}
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(640, 480) {
                    controller.layerMove?.let {
                        LayerMoveOverlay(controller, it, Size(640f, 480f), Modifier.fillMaxSize())
                    }
                }
            }
        var time = 0L
        suspend fun render() =
            withContext(Dispatchers.Main) {
                scene.render(time++ * 16_666_667L).close()
            }
        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(30_000) {
                while (!withContext(Dispatchers.Main) { predicate() && !controller.busy }) {
                    render()
                    delay(5)
                }
            }
        try {
            waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            waitFor { controller.document.width == 4096 }
            withContext(Dispatchers.Main) { controller.selectLayer(1, mask = true) }
            waitFor { controller.document.maskEditing }
            withContext(Dispatchers.Main) {
                controller.selectPreset(BrushPreset.PixelPencil)
                controller.viewport = Viewport(zoom = 2f)
                controller.tool = Tool.TransformLayer
                controller.prepareLayerMove()
            }
            waitFor { controller.layerMove?.transform != null }
            val frame = controller.frame
            val document = controller.document
            val preview = assertNotNull(controller.layerMove)
            val layers = preview.layers
            assertEquals(StudioDefaults.maxCanvasPixels, document.width.toLong() * document.height)
            assertEquals(1, layers.sumOf { it.tiles.size })
            assertEquals(1, layers.sumOf { it.mask?.tiles?.size ?: 0 })
            val pixels = layers.first().tiles.first().image
            val mask = layers.first().mask!!.tiles.first().image
            repeat(20) { index ->
                withContext(Dispatchers.Main) {
                    controller.previewLayerTransform(
                        LayerTransform(
                            3072,
                            2048,
                            dx = index * 8f,
                            dy = index * -4f,
                            angle = 17f,
                            flipX = index % 2 == 0,
                            filter = ResampleFilter.Nearest,
                        )
                    )
                }
                render()
            }
            repeat(8) { render() }
            withContext(Dispatchers.Main) {
                assertSame(frame, controller.frame)
                assertEquals(document, controller.document)
                assertSame(layers, controller.layerMove!!.layers)
                assertSame(pixels, layers.first().tiles.first().image)
                assertSame(mask, layers.first().mask!!.tiles.first().image)
                assertFalse(scene.hasInvalidations())
                assertNull(controller.error)
                controller.cancelLayerMove(exit = true)
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
