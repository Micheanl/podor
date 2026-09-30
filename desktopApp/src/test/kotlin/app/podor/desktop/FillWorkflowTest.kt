package app.podor.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import kotlin.test.*
import kotlinx.coroutines.*

class FillWorkflowTest {
    @Test
    fun fillSettingsReachVisibleLayerSamplingOpacityAndHistory() = runBlocking {
        NativeLoader.load()
        val source = createNativeEngine(16, 8)
        val project =
            try {
                for (command in
                    listOf(
                        """{"type":"fill","x":0,"y":0,"color":[200,0,0,255],"tolerance":0}""",
                        """{"type":"select","rect":{"left":8,"top":0,"right":9,"bottom":8}}""",
                        """{"type":"fill","x":8,"y":0,"color":[0,0,200,255],"tolerance":0}""",
                        """{"type":"select","rect":null}""",
                        """{"type":"add_layer"}""",
                    )) source.call(EngineOperation.COMMAND, command.encodeToByteArray())
                source.call(EngineOperation.SAVE)
            } finally {
                source.close()
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) =
                    error("Fill must not save")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        predicate() &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                ) delay(5)
            }
        suspend fun change(block: StudioController.() -> Unit) {
            val revision =
                withContext(Dispatchers.Main) {
                    val revision = controller.document.revision
                    controller.block()
                    revision
                }
            awaitState { controller.document.revision > revision }
        }
        suspend fun color(x: Int): Color =
            withContext(Dispatchers.Main) {
                controller.frame.tiles.values.single().image.toPixelMap()[x, 4]
            }
        suspend fun check(x: Int, red: Int, green: Int, blue: Int) {
            val actual = color(x)
            assertEquals(red / 255f, actual.red, 0.006f)
            assertEquals(green / 255f, actual.green, 0.006f)
            assertEquals(blue / 255f, actual.blue, 0.006f)
            assertEquals(1f, actual.alpha)
        }
        try {
            awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            awaitState { controller.document.width == 16 }
            change {
                fillTolerance = 0f
                fillMerged = true
                fillContiguous = true
                brush = brush.copy(color = 0xFF00FF00, opacity = 128f / 255)
                fill(Offset(2f, 4f))
            }
            check(2, 100, 128, 0)
            check(8, 0, 0, 200)
            check(12, 200, 0, 0)
            change { command("undo") }
            check(2, 200, 0, 0)
            change {
                fillContiguous = false
                fill(Offset(2f, 4f))
            }
            check(2, 100, 128, 0)
            check(8, 0, 0, 200)
            check(12, 100, 128, 0)
            change { command("undo") }
            change {
                fillMerged = false
                fillContiguous = true
                brush = brush.copy(opacity = 1f)
                fill(Offset(2f, 4f))
            }
            for (x in listOf(2, 8, 12)) check(x, 0, 255, 0)
            change { command("undo") }
            change { command("redo") }
            for (x in listOf(2, 8, 12)) check(x, 0, 255, 0)
            assertNull(withContext(Dispatchers.Main) { controller.error })
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            scope.cancel()
        }
    }
}
