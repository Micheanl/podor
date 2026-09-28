package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.OpenedProject
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
class ReferencePerformanceTest {
    @Test
    fun largeReferenceLoadsAndMovesWithoutBlockingTheNativeWindow() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(4096, 4096)
            val image =
                try {
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[190,110,130,190],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    engine.call(EngineOperation.EXPORT_IMAGE)
                } finally {
                    engine.close()
                }
            val files =
                object : ProjectFiles {
                    override suspend fun open(): ByteArray? = null

                    override suspend fun openReference() = OpenedProject(image, null)

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Reference must not save")
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun await(predicate: () -> Boolean) =
                withTimeout(20_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(5)
                }
            val measuring = AtomicBoolean(false)
            var heartbeat: Job? = null
            try {
                await { controller.ready }
                val native =
                    withContext(Dispatchers.Main) {
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3400, -2200, 1360, 900)
                            setContent { StudioApp(controller) }
                            isVisible = true
                        }
                    }
                window = native
                delay(300)
                withContext(Dispatchers.Main) { native.renderImmediately() }
                val original = withContext(Dispatchers.Main) { controller.frame }
                val waits = mutableListOf<Double>()
                measuring.set(true)
                heartbeat =
                    launch(Dispatchers.Default) {
                        while (isActive && measuring.get()) {
                            delay(16)
                            val start = System.nanoTime()
                            waits +=
                                withContext(Dispatchers.Main) {
                                    (System.nanoTime() - start) / 1_000_000.0
                                }
                        }
                    }
                val start = System.nanoTime()
                withContext(Dispatchers.Main) {
                    controller.references.load(false, controller.document)
                }
                await { !controller.references.loading }
                val loadMillis = (System.nanoTime() - start) / 1_000_000.0
                val bitmap =
                    withContext(Dispatchers.Main) {
                        assertNull(controller.references.error)
                        controller.references.selected!!.bitmap
                    }
                repeat(90) { index ->
                    withContext(Dispatchers.Main) {
                        val reference = controller.references.selected!!
                        reference.placement =
                            reference.placement.copy(
                                bounds =
                                    reference.placement.bounds.translate(
                                        Offset(if (index % 2 == 0) 2f else -2f, 0f)
                                    ),
                                mirrored = index % 20 < 10,
                            )
                        assertSame(bitmap, reference.bitmap)
                    }
                    delay(16)
                }
                measuring.set(false)
                heartbeat.join()
                val render =
                    withContext(Dispatchers.Main) {
                        val started = System.nanoTime()
                        native.renderImmediately()
                        assertSame(original, controller.frame)
                        assertFalse(controller.hasUnsavedChanges)
                        (System.nanoTime() - started) / 1_000_000.0
                    }
                waits.sort()
                val api = withContext(Dispatchers.Main) { native.renderApi.toString() }
                val report =
                    "Renderer: $api\n4096 x 4096 source, 2048 x 2048 cached preview, 90 view changes.\nReference load: %.2f ms\nForced native render: %.2f ms\nMain-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms\nOffscreen window; not presented FPS or physical pen latency.\n"
                        .format(
                            loadMillis,
                            render,
                            waits[waits.size / 2],
                            waits[waits.size * 95 / 100],
                            waits.last(),
                        )
                val path = Path.of("build/reports/reference-performance.txt")
                Files.createDirectories(path.parent)
                Files.writeString(path, report)
                assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"), report)
                assertTrue(loadMillis < 5000, report)
                assertTrue(render < 250 && waits.last() < 300, report)
            } finally {
                measuring.set(false)
                heartbeat?.cancelAndJoin()
                withContext(Dispatchers.Main) {
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
