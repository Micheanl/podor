package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class BrushLibraryPerformanceTest {
    @Test
    fun fullLibrarySearchAndScrollingReuseCanvasAndKeepTheWindowResponsive() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val native = createNativeEngine(2048, 2048)
            val project =
                try {
                    native.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[80,120,140,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val customs = List(64) { BrushPreset.Ink.copy(id = "custom-$it", label = "Custom $it") }
            val packs =
                List(16) { pack ->
                    BrushPack(
                        "pack-$pack",
                        "Pack $pack",
                        brushes =
                            List(64) { brush ->
                                BrushPreset.Ink.copy(
                                    id = "brush-$brush",
                                    label = "Brush $brush",
                                    grain = brush / 64f,
                                    size = 2f + brush * 3f,
                                )
                            },
                    )
                }
            val initial = Preferences(brushes = customs, plugins = packs)
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Library must not save artwork")

                    override suspend fun readPreferences() =
                        Json.encodeToString(initial).encodeToByteArray()

                    override suspend fun writePreferences(bytes: ByteArray) {
                        assertTrue(
                            Json.decodeFromString<Preferences>(bytes.decodeToString()).valid()
                        )
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun waitFor(predicate: () -> Boolean) =
                withTimeout(15_000) {
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
                waitFor { controller.document.width == 2048 && !controller.busy }
                val ui =
                    withContext(Dispatchers.Main) {
                        assertEquals(1104, controller.brushes.size)
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3000, -2000, 1360, 900)
                            setContent { StudioApp(controller) }
                            isVisible = true
                        }
                    }
                window = ui
                delay(300)
                val original = withContext(Dispatchers.Main) { controller.frame }
                val viewport = withContext(Dispatchers.Main) { controller.viewport }
                val waits = mutableListOf<Double>()
                val normalWaits = mutableListOf<Double>()
                val normalRendering = AtomicBoolean(false)
                val renders = mutableListOf<Double>()
                val steps = mutableListOf<String>()
                val slowDispatches = mutableListOf<String>()
                val eventThread = withContext(Dispatchers.Main) { Thread.currentThread() }
                val sampling = AtomicBoolean(true)
                val heartbeat =
                    launch(Dispatchers.Default) {
                        while (isActive && sampling.get()) {
                            delay(16)
                            val start = System.nanoTime()
                            val normal = normalRendering.get()
                            val dispatch =
                                async(Dispatchers.Main) {
                                    (System.nanoTime() - start) / 1_000_000.0
                                }
                            val elapsed =
                                withTimeoutOrNull(40) { dispatch.await() }
                                    ?: run {
                                        slowDispatches.add(
                                            eventThread.stackTrace.take(28).joinToString("\n")
                                        )
                                        dispatch.await()
                                    }
                            if (normal) normalWaits.add(elapsed) else waits.add(elapsed)
                        }
                    }
                try {
                    repeat(24) { index ->
                        withContext(Dispatchers.Main) {
                            val target =
                                assertNotNull(SwingUtilities.getDeepestComponentAt(ui, 1200, 700))
                            val point = SwingUtilities.convertPoint(ui, 1200, 700, target)
                            val start = System.nanoTime()
                            target.dispatchEvent(
                                MouseWheelEvent(
                                    target,
                                    MouseWheelEvent.MOUSE_WHEEL,
                                    System.currentTimeMillis(),
                                    0,
                                    point.x,
                                    point.y,
                                    0,
                                    false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                                    3,
                                    if (index < 12) 8 else -8,
                                )
                            )
                            ui.renderImmediately()
                            renders.add((System.nanoTime() - start) / 1_000_000.0)
                            steps.add("scroll $index: %.2f ms".format(renders.last()))
                        }
                        delay(16)
                    }
                    repeat(48) { index ->
                        withContext(Dispatchers.Main) {
                            val start = System.nanoTime()
                            controller.brushCollection =
                                if (index % 2 == 0) BrushCollection.Extensions
                                else BrushCollection.All
                            controller.brushLibraryQuery = "Brush ${index % 16}"
                            if (index % 8 == 0)
                                controller.toggleBrushFavorite("plugin:pack-0/brush-${index % 16}")
                            ui.renderImmediately()
                            renders.add((System.nanoTime() - start) / 1_000_000.0)
                            steps.add("filter $index: %.2f ms".format(renders.last()))
                            assertSame(original, controller.frame)
                            assertEquals(viewport, controller.viewport)
                            assertFalse(controller.hasUnsavedChanges)
                        }
                        delay(16)
                    }
                    normalRendering.set(true)
                    repeat(48) { index ->
                        withContext(Dispatchers.Main) {
                            controller.brushCollection =
                                if (index % 2 == 0) BrushCollection.All
                                else BrushCollection.Extensions
                            controller.brushLibraryQuery = "Brush ${index % 16}"
                            assertSame(original, controller.frame)
                        }
                        delay(16)
                    }
                } finally {
                    sampling.set(false)
                    heartbeat.join()
                }
                withContext(Dispatchers.Main) {
                    val api = ui.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    waits.sort()
                    renders.sort()
                    normalWaits.sort()
                    val report = Path.of("build/reports/brush-library-performance.txt")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        "Renderer: $api\n1104 brushes, 24 wheel events, 48 filtered renders, six favorite changes.\n" +
                            "Input/filter and forced render: median %.2f ms, p95 %.2f ms, max %.2f ms\n"
                                .format(
                                    renders[renders.size / 2],
                                    renders[renders.size * 95 / 100],
                                    renders.last(),
                                ) +
                            "Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms\n"
                                .format(
                                    waits[waits.size / 2],
                                    waits[waits.size * 95 / 100],
                                    waits.last(),
                                ) +
                            "Normal asynchronous rendering, 48 additional filters, dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms\n"
                                .format(
                                    normalWaits[normalWaits.size / 2],
                                    normalWaits[normalWaits.size * 95 / 100],
                                    normalWaits.last(),
                                ) +
                            "2048 x 2048 painted canvas reused. Offscreen native window; not presented FPS.\n" +
                            steps.joinToString("\n") +
                            "\n" +
                            slowDispatches.joinToString("\n\n"),
                    )
                    assertTrue(renders.last() < 250.0, "Library render: ${renders.last()} ms")
                    assertTrue(waits.last() < 250.0, "Main-thread dispatch: ${waits.last()} ms")
                    assertTrue(
                        normalWaits.last() < 150.0,
                        "Normal rendering dispatch: ${normalWaits.last()} ms",
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
