package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
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
class PsdImportPerformanceTest {
    @Test
    fun layeredPsdLoadsWithoutBlockingTheDesktopThread() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val source = createNativeEngine(2048, 2048)
            val psd =
                try {
                    source.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[80,120,200,173],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    repeat(5) {
                        source.call(
                            EngineOperation.COMMAND,
                            """{"type":"duplicate_layer","id":1}""".encodeToByteArray(),
                        )
                    }
                    source.call(EngineOperation.EXPORT_IMAGE, """{"format":"psd"}""".encodeToByteArray())
                } finally {
                    source.close()
                }
            val files =
                object : ProjectFiles {
                    override suspend fun open() = psd

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Import must not save")
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
                val nativeWindow =
                    withContext(Dispatchers.Main) {
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3000, -2000, 1360, 900)
                            setContent { StudioApp(controller) }
                            isVisible = true
                        }
                    }
                window = nativeWindow
                delay(500)
                val waits = mutableListOf<Double>()
                val slow = mutableListOf<String>()
                val eventThread = withContext(Dispatchers.Main) { Thread.currentThread() }
                val measuring = AtomicBoolean(true)
                var busyTicks = 0
                val heartbeat =
                    launch(Dispatchers.Default) {
                        while (isActive && measuring.get()) {
                            delay(16)
                            val start = System.nanoTime()
                            val dispatch =
                                async(Dispatchers.Main) {
                                    if (controller.busy) busyTicks++
                                    (System.nanoTime() - start) / 1_000_000.0
                                }
                            waits.add(
                                withTimeoutOrNull(50) { dispatch.await() }
                                    ?: run {
                                        slow.add(eventThread.stackTrace.take(24).joinToString("\n"))
                                        dispatch.await()
                                    }
                            )
                        }
                    }
                val start = System.nanoTime()
                try {
                    withContext(Dispatchers.Main) {
                        controller.file(StudioController.FileAction.Open)
                    }
                    waitFor {
                        controller.document.layers.size == 6 &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                withContext(Dispatchers.Main) {
                    assertTrue(busyTicks > 0)
                    assertEquals(256, controller.frame.tiles.size)
                    assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), controller.previews.images.keys)
                    assertFalse(controller.hasUnsavedChanges)
                    val renderStart = System.nanoTime()
                    nativeWindow.renderImmediately()
                    val renderMillis = (System.nanoTime() - renderStart) / 1_000_000.0
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    val report = Path.of("build/reports/psd-import-performance.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api; 2048 x 2048, 6 RGBA layers, including composite tiles and layer thumbnails.\n" +
                            "Import: %.2f ms; forced render: %.2f ms.\n"
                                .format(elapsed, renderMillis) +
                            "Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms; $busyTicks responses while busy.\n"
                                .format(
                                    waits[waits.size / 2],
                                    waits[waits.size * 95 / 100],
                                    waits.last(),
                                ) +
                            "Native window is outside the visible desktop; this does not measure presented FPS.\n" +
                            slow.joinToString("\n\n"),
                    )
                    assertTrue(
                        waits[waits.size * 95 / 100] < 50.0,
                        "Import dispatch p95 exceeded 50 ms",
                    )
                    assertTrue(
                        waits.last() < 250.0,
                        "PSD import blocked the main thread for ${waits.last()} ms",
                    )
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
