package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.ResampleFilter
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
class ImageResizePerformanceTest {
    @Test
    fun largeImageResamplingLeavesTheDesktopThreadResponsive() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val native = createNativeEngine(1024, 768)
            val project =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[130,50,90,160],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) = true
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun awaitState(predicate: () -> Boolean) =
                withTimeout(30_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(1)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState {
                    controller.document.width == 1024 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
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
                val waits = mutableListOf<Double>()
                val slowDispatches = mutableListOf<String>()
                val eventThread = withContext(Dispatchers.Main) { Thread.currentThread() }
                val measuring = AtomicBoolean(true)
                var busyTicks = 0
                var renderMillis = 0.0
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
                            val elapsed =
                                withTimeoutOrNull(50) { dispatch.await() }
                                    ?: run {
                                        slowDispatches.add(
                                            eventThread.stackTrace.take(24).joinToString("\n")
                                        )
                                        dispatch.await()
                                    }
                            waits.add(elapsed)
                        }
                    }
                val start = System.nanoTime()
                try {
                    withContext(Dispatchers.Main) {
                        controller.resizeImage(
                            4096,
                            3072,
                            ResampleFilter.Lanczos3,
                            controller.document.revision,
                        )
                    }
                    awaitState {
                        controller.document.width == 4096 &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                    withContext(Dispatchers.Main) {
                        val renderStart = System.nanoTime()
                        nativeWindow.renderImmediately()
                        renderMillis = (System.nanoTime() - renderStart) / 1_000_000.0
                    }
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                withContext(Dispatchers.Main) {
                    assertTrue(busyTicks > 0)
                    assertNull(controller.error)
                    assertEquals(768, controller.frame.tiles.size)
                    assertTrue(controller.document.canUndo)
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    val output = Path.of("build/reports/image-resize-performance.txt")
                    Files.createDirectories(output.parent)
                    Files.writeString(
                        output,
                        """
                    Renderer: $api
                    1024 x 768 -> 4096 x 3072, one translucent layer, Lanczos3.
                    Resampling, tile upload, thumbnails and forced render: %.2f ms
                    Forced synchronous render alone: %.2f ms
                    Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms; $busyTicks responses during resampling.
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
