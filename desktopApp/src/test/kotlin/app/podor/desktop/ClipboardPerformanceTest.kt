package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import app.podor.data.ProjectFiles
import app.podor.desktop.data.DesktopClipboard
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.ClipboardAction
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.awt.datatransfer.Clipboard
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class ClipboardPerformanceTest {
    @Test
    fun largeClipboardCopyCutAndPasteLeaveTheGpuWindowResponsive() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(4096, 4096)
            val project =
                try {
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[140,40,80,173],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            val privateClipboard = Clipboard("performance-test")
            val files =
                object : ProjectFiles {
                    override val clipboard = DesktopClipboard { privateClipboard }

                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Clipboard must not save")
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
                waitFor {
                    controller.hasCanvas &&
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
                delay(500)
                val waits = mutableListOf<Double>()
                val timings = mutableListOf<String>()
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
                try {
                    for ((action, status) in
                        listOf(
                            ClipboardAction.CopyVisible to "已复制图片",
                            ClipboardAction.Cut to "已剪切，可撤销",
                            ClipboardAction.Paste to "已粘贴到新图层",
                        )) {
                        val start = System.nanoTime()
                        withContext(Dispatchers.Main) { controller.clipboard(action) }
                        waitFor { controller.status == status && !controller.busy }
                        timings.add(
                            "$action: %.2f ms".format((System.nanoTime() - start) / 1_000_000.0)
                        )
                    }
                    waitFor { controller.previews.revision == controller.document.revision }
                    delay(250)
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                withContext(Dispatchers.Main) {
                    assertTrue(busyTicks > 0)
                    assertEquals(2, controller.document.layers.size)
                    assertEquals(4096, controller.document.width)
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    val report = Path.of("build/reports/clipboard-performance.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api; 4096 x 4096 RGBA, PNG encoding/decoding and private AWT clipboard.\n" +
                            timings.joinToString("\n") +
                            "\nMain-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms; $busyTicks responses while busy.\n"
                                .format(
                                    waits[waits.size / 2],
                                    waits[waits.size * 95 / 100],
                                    waits.last(),
                                ) +
                            "Native window is outside the visible desktop; this does not measure presented FPS.\n" +
                            slow.joinToString("\n\n"),
                    )
                    assertTrue(
                        waits.last() < 250.0,
                        "Clipboard blocked the main thread for ${waits.last()} ms",
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
