package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class PalettePerformanceTest {
    @Test
    fun extractionOnEightLargeLayersLeavesTheWindowResponsiveAndReusesTheCanvas() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(2048, 2048)
            val project =
                try {
                    fun command(value: String) =
                        Json.parseToJsonElement(
                                engine
                                    .call(EngineOperation.COMMAND, value.encodeToByteArray())
                                    .decodeToString()
                            )
                            .jsonObject
                    val state = command("""{"type":"state"}""")
                    command(
                        """{"type":"gradient","id":1,"revision":${state["revision"]},"settings":{"start":[0,0],"end":[2048,2048],"from":[210,40,80,255],"to":[30,180,215,255],"opacity":1,"shape":"linear"}}"""
                    )
                    repeat(7) { index ->
                        val layer = command("""{"type":"duplicate_layer","id":1}""")["active"]
                        command(
                            """{"type":"set_layer","id":$layer,"visible":true,"opacity":0.3,"name":"Layer $index"}"""
                        )
                        command(
                            """{"type":"set_blend","id":$layer,"mode":"${if (index % 2 == 0) "multiply" else "screen"}"}"""
                        )
                    }
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Palette must not save the artwork")
                }
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(60_000) {
                    while (
                        !withContext(Dispatchers.Main) {
                            assertNull(controller.error)
                            predicate()
                        }
                    ) delay(5)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState {
                    controller.hasCanvas &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                withContext(Dispatchers.Main) {
                    window =
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3000, -2000, 1360, 960)
                            setContent {
                                PodorTheme {
                                    Row(Modifier.fillMaxSize()) {
                                        CanvasWorkspace(
                                            controller,
                                            Modifier.weight(1f).fillMaxHeight(),
                                        )
                                        Inspector(
                                            controller,
                                            StudioPanel.Colors,
                                            {},
                                            Modifier.width(300.dp).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                            isVisible = true
                        }
                }
                delay(500)
                val running = AtomicBoolean(true)
                val waits = mutableListOf<Double>()
                val times = mutableListOf<Double>()
                val heartbeat =
                    launch(Dispatchers.Default) {
                        while (running.get()) {
                            delay(16)
                            val start = System.nanoTime()
                            withContext(Dispatchers.Main) {
                                waits.add((System.nanoTime() - start) / 1_000_000.0)
                            }
                        }
                    }
                val before = withContext(Dispatchers.Main) { controller.document }
                val frame = withContext(Dispatchers.Main) { controller.frame }
                try {
                    repeat(6) {
                        val start = System.nanoTime()
                        withContext(Dispatchers.Main) { controller.extractPalette() }
                        awaitState { !controller.extractingPalette }
                        times.add((System.nanoTime() - start) / 1_000_000.0)
                        withContext(Dispatchers.Main) {
                            assertEquals(before, controller.document)
                            assertSame(frame, controller.frame)
                            assertEquals(12, controller.preferences.palette.size)
                            assertFalse(controller.hasUnsavedChanges)
                        }
                    }
                } finally {
                    running.set(false)
                    heartbeat.join()
                }
                val report =
                    "2048 x 2048, eight mixed layers, full color panel\nExtraction ms: ${times.joinToString { "%.2f".format(it) }}\nMain queue max: %.2f ms\nCanvas frame reused: true\n"
                        .format(waits.max())
                val path = Path.of("build/reports/palette-performance.txt")
                Files.createDirectories(path.parent)
                Files.writeString(path, report)
                assertTrue(waits.size >= 6)
                assertTrue(waits.max() < 150, report)
                assertTrue(times.max() < 5000, report)
            } finally {
                withContext(Dispatchers.Main) {
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
