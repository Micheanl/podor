package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class ColorSelectionPerformanceTest {
    @Test
    fun tiledColorSamplingKeepsLargeDocumentAndWindowResponsive() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val native = createNativeEngine(4096, 4096)
            val project =
                try {
                    fun command(value: String) =
                        native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                    command("""{"type":"fill","x":0,"y":0,"color":[120,60,40,255],"tolerance":0}""")
                    for (index in 1..4) {
                        command("""{"type":"add_layer"}""")
                        val start = index * 500
                        command(
                            """{"type":"select","rect":{"left":$start,"top":$start,"right":${start + 1024},"bottom":${start + 1024}}}"""
                        )
                        command(
                            """{"type":"fill","x":$start,"y":$start,"color":[${60 + index * 20},80,160,200],"tolerance":0}"""
                        )
                        val mode = if (index % 2 == 0) "multiply" else "screen"
                        command("""{"type":"set_blend","id":${index + 1},"mode":"$mode"}""")
                    }
                    command("""{"type":"select","rect":null}""")
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Selection must not save")
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun waitFor(predicate: () -> Boolean) =
                withTimeout(30_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            assertNull(controller.error)
                            predicate()
                        }
                    ) delay(5)
                }
            try {
                waitFor { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                waitFor { controller.document.width == 4096 && !controller.busy }
                val nativeWindow =
                    withContext(Dispatchers.Main) {
                        controller.tool = Tool.Select
                        controller.selectionKind = SelectionKind.MagicWand
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3000, -2000, 1360, 900)
                            setContent { StudioApp(controller) }
                            isVisible = true
                        }
                    }
                window = nativeWindow
                delay(300)
                val original = withContext(Dispatchers.Main) { controller.frame }
                val waits = mutableListOf<Double>()
                val times = linkedMapOf<String, Double>()
                val renders = linkedMapOf<String, Double>()
                val measuring = AtomicBoolean(true)
                val heartbeat =
                    launch(Dispatchers.Default) {
                        while (isActive && measuring.get()) {
                            delay(16)
                            val start = System.nanoTime()
                            waits.add(
                                withContext(Dispatchers.Main) {
                                    (System.nanoTime() - start) / 1_000_000.0
                                }
                            )
                        }
                    }
                try {
                    for (merged in listOf(false, true)) for (contiguous in listOf(true, false)) {
                        val before =
                            withContext(Dispatchers.Main) { controller.document.selection?.id }
                        val name =
                            "${if (merged) "Merged" else "Layer"}, ${if (contiguous) "contiguous" else "global"}"
                        val start = System.nanoTime()
                        withContext(Dispatchers.Main) {
                            controller.selectionMerged = merged
                            controller.selectionContiguous = contiguous
                            controller.selectColor(Offset(100f, 100f))
                        }
                        waitFor { controller.document.selection?.id != before && !controller.busy }
                        times[name] = (System.nanoTime() - start) / 1_000_000.0
                        withContext(Dispatchers.Main) {
                            assertSame(original, controller.frame)
                            assertFalse(controller.hasUnsavedChanges)
                            assertFalse(controller.document.canUndo)
                            val renderStart = System.nanoTime()
                            nativeWindow.renderImmediately()
                            renders[name] = (System.nanoTime() - renderStart) / 1_000_000.0
                        }
                    }
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                withContext(Dispatchers.Main) {
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    assertTrue(waits.isNotEmpty())
                    val output = Path.of("build/reports/color-selection-performance.txt")
                    Files.createDirectories(output.parent)
                    Files.writeString(
                        output,
                        "Renderer: $api\n4096 x 4096 canvas, five mixed layers.\n" +
                            times.entries.joinToString("\n") { (name, ms) ->
                                "$name: %.2f ms, forced render %.2f ms"
                                    .format(ms, renders.getValue(name))
                            } +
                            "\nMain-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms\n"
                                .format(
                                    waits[waits.size / 2],
                                    waits[waits.size * 95 / 100],
                                    waits.last(),
                                ) +
                            "Offscreen native window. This does not measure presented FPS or physical pen latency.\n",
                    )
                    assertTrue(times.values.all { it < 5_000.0 }, "Selection timings: $times")
                    assertTrue(renders.values.all { it < 250.0 }, "Forced renders: $renders")
                    assertTrue(waits.last() < 250.0, "Main-thread dispatch: ${waits.last()} ms")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
