package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.AdjustmentControls
import kotlin.test.*
import kotlinx.coroutines.*

@OptIn(ExperimentalComposeUiApi::class)
class GradientMapWorkflowTest {
    @Test
    fun livePreviewPreservesPixelsUntilCommitAndUndoRestoresMaskedTransparency() = runBlocking {
        NativeLoader.load()
        val native = createNativeEngine(32, 16)
        val project =
            try {
                listOf(
                        """{"type":"fill","x":0,"y":0,"color":[0,0,0,128],"tolerance":0}""",
                        """{"type":"select","rect":{"left":16,"top":0,"right":32,"bottom":16}}""",
                        """{"type":"fill","x":17,"y":0,"color":[255,255,255,255],"tolerance":0}""",
                        """{"type":"select","rect":null}""",
                        """{"type":"add_mask","id":1,"mode":"reveal"}""",
                        """{"type":"fill","x":0,"y":0,"color":[128,128,128,255],"tolerance":0}""",
                        """{"type":"set_mask_editing","id":1,"enabled":false}""",
                    )
                    .forEach { native.call(EngineOperation.COMMAND, it.encodeToByteArray()) }
                native.call(EngineOperation.SAVE)
            } finally {
                native.close()
            }
        var saves = 0
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
                    saves++
                    return true
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(420, 880) { AdjustmentControls(controller) }
            }
        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        scene.render().close()
                        assertNull(controller.error)
                        predicate() && !controller.busy
                    }
                ) delay(5)
            }
        suspend fun pixel(preview: Boolean, x: Int): Color =
            withContext(Dispatchers.Main) {
                val frame = if (preview) controller.adjustmentPreview!!.frame else controller.frame
                frame.tiles.values.single().image.toPixelMap()[x, 8]
            }
        try {
            awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            awaitState { controller.hasCanvas && controller.document.width == 32 }
            val content = controller.document.contentId
            val originalLeft = pixel(false, 4)
            val originalRight = pixel(false, 24)
            assertTrue(originalLeft.alpha > .2f)
            assertEquals(1f, originalRight.red, .01f)
            withContext(Dispatchers.Main) {
                controller.prepareAdjustment(AdjustmentKind.GradientMap)
            }
            awaitState { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                val settings = controller.adjustmentPreview!!.settings
                controller.updateAdjustment(
                    settings.copy(
                        gradientMap =
                            GradientMapSettings(
                                listOf(
                                    GradientMapStop(0f, listOf(230, 40, 60)),
                                    GradientMapStop(1f, listOf(30, 90, 210)),
                                )
                            )
                    )
                )
            }
            awaitState { controller.adjustmentPreview?.let { !it.updating && it.changed } == true }
            val mappedLeft = pixel(true, 4)
            val mappedRight = pixel(true, 24)
            assertEquals(originalLeft, pixel(false, 4))
            assertEquals(originalRight, pixel(false, 24))
            assertEquals(content, controller.document.contentId)
            assertFalse(controller.hasUnsavedChanges)
            assertEquals(originalLeft.alpha, mappedLeft.alpha, 1f / 255)
            assertEquals(originalRight.alpha, mappedRight.alpha, 1f / 255)
            assertEquals(230f / 255, mappedLeft.red, .025f)
            assertEquals(210f / 255, mappedRight.blue, .025f)
            assertEquals(0, saves)
            withContext(Dispatchers.Main) { controller.commitAdjustment() }
            awaitState {
                controller.adjustmentPreview == null && controller.document.contentId != content
            }
            assertEquals(mappedLeft, pixel(false, 4))
            assertEquals(mappedRight, pixel(false, 24))
            assertTrue(controller.hasUnsavedChanges)
            withContext(Dispatchers.Main) { controller.command("undo") }
            awaitState { controller.document.contentId == content }
            assertEquals(originalLeft, pixel(false, 4))
            assertEquals(originalRight, pixel(false, 24))
            withContext(Dispatchers.Main) {
                controller.prepareAdjustment(AdjustmentKind.GradientMap)
            }
            awaitState { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                val preview = controller.adjustmentPreview!!
                controller.updateAdjustment(
                    preview.settings.copy(
                        gradientMap =
                            GradientMapSettings(
                                listOf(
                                    GradientMapStop(.5f, listOf(0, 0, 0)),
                                    GradientMapStop(.5f, listOf(255, 255, 255)),
                                )
                            )
                    )
                )
                assertTrue(preview.settings.gradientMap.valid())
                controller.cancelAdjustment()
            }
            assertEquals(content, controller.document.contentId)
            assertEquals(0, saves)
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.close()
            }
            scope.cancel()
        }
    }
}
