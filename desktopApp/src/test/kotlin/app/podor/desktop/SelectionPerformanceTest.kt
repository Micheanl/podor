package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
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
class SelectionPerformanceTest {
    @Test
    fun maximumLassoBuildLeavesTheWindowResponsive() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(4096, 4096)
            val project =
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
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
                withTimeout(60_000) {
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
                val points =
                    List(StudioDefaults.maxSelectionPoints) { index ->
                        SelectionPoint(
                            4f + index * 4088f / (StudioDefaults.maxSelectionPoints - 1),
                            if (index % 2 == 0) 4f else 4092f,
                        )
                    }
                val waits = mutableListOf<Double>()
                val slowDispatches = mutableListOf<String>()
                val eventThread = withContext(Dispatchers.Main) { Thread.currentThread() }
                var busyTicks = 0
                var elapsed = 0.0
                val measuring = AtomicBoolean(true)
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
                                        slowDispatches.add(
                                            eventThread.stackTrace.take(24).joinToString("\n")
                                        )
                                        dispatch.await()
                                    }
                            )
                        }
                    }
                val start = System.nanoTime()
                try {
                    withContext(Dispatchers.Main) {
                        controller.select(Selection(4, 4, 4092, 4092, SelectionKind.Lasso, points))
                    }
                    waitFor {
                        controller.document.selection?.kind == SelectionKind.Lasso &&
                            !controller.busy
                    }
                    elapsed = (System.nanoTime() - start) / 1_000_000.0
                    delay(250)
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                withContext(Dispatchers.Main) {
                    val renderStart = System.nanoTime()
                    nativeWindow.renderImmediately()
                    val renderMillis = (System.nanoTime() - renderStart) / 1_000_000.0
                    assertTrue(busyTicks > 0)
                    assertNull(controller.error)
                    assertFalse(controller.hasUnsavedChanges)
                    assertFalse(controller.document.canUndo)
                    assertTrue(controller.frame.tiles.isEmpty())
                    assertEquals(points.size, controller.document.selection!!.points.size)
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    val output = Path.of("build/reports/selection-performance.txt")
                    Files.createDirectories(output.parent)
                    Files.writeString(
                        output,
                        """
                    Renderer: $api
                    4096 x 4096 canvas, 4096-point crossing zigzag lasso, antialiased mask.
                    Selection build and state publication: %.2f ms
                    Forced synchronous render, measured separately: %.2f ms
                    Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms; $busyTicks responses during mask build.
                    Native window is outside the visible desktop. This does not measure presented FPS.
                """
                            .trimIndent()
                            .format(
                                elapsed,
                                renderMillis,
                                waits[waits.size / 2],
                                waits[waits.size * 95 / 100],
                                waits.last(),
                            ) + "\n" + slowDispatches.joinToString("\n\n"),
                    )
                    assertTrue(
                        renderMillis < 250.0,
                        "Dense selection outline took $renderMillis ms to render",
                    )
                    assertTrue(
                        waits.last() < 250.0,
                        "Dense selection blocked the event thread for ${waits.last()} ms",
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
