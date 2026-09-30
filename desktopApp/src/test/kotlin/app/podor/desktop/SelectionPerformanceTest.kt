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
                val timings = linkedMapOf<String, Double>()
                val renders = linkedMapOf<String, Double>()
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
                suspend fun measure(name: String, change: () -> Unit, ready: () -> Boolean) {
                    val start = System.nanoTime()
                    withContext(Dispatchers.Main) { change() }
                    waitFor { ready() && !controller.busy }
                    timings[name] = (System.nanoTime() - start) / 1_000_000.0
                    withContext(Dispatchers.Main) {
                        val renderStart = System.nanoTime()
                        nativeWindow.renderImmediately()
                        renders[name] = (System.nanoTime() - renderStart) / 1_000_000.0
                    }
                }
                try {
                    measure(
                        "4096-point lasso",
                        {
                            controller.select(
                                Selection(4, 4, 4092, 4092, SelectionKind.Lasso, points)
                            )
                        },
                        { controller.document.selection?.kind == SelectionKind.Lasso },
                    )
                    withContext(Dispatchers.Main) {
                        assertEquals(points.size, controller.document.selection!!.points.size)
                        assertTrue(controller.document.selection!!.raster)
                        assertNull(controller.selectionOutline!!.path)
                        assertTrue(controller.selectionOutline!!.mask.isNotEmpty())
                    }
                    for (mode in listOf(SelectionMode.Add, SelectionMode.Subtract)) {
                        val id =
                            withContext(Dispatchers.Main) { controller.document.selection!!.id }
                        measure(
                            mode.name,
                            {
                                controller.changeSelectionMode(mode)
                                controller.select(
                                    Selection(512, 512, 3584, 3584, SelectionKind.Ellipse)
                                )
                            },
                            { controller.document.selection?.id != id },
                        )
                    }
                    val previous =
                        withContext(Dispatchers.Main) { controller.document.selection!!.id }
                    measure(
                        "Invert",
                        { controller.invertSelection() },
                        { controller.document.selection?.id != previous },
                    )
                    val comb = buildList {
                        add(SelectionPoint(0f, 0f))
                        add(SelectionPoint(4096f, 0f))
                        add(SelectionPoint(4096f, 4096f))
                        for (index in 511 downTo 1) {
                            val x = index * 8f
                            val (first, last) = if (index % 2 == 1) 4096f to 8f else 8f to 4096f
                            add(SelectionPoint(x, first))
                            add(SelectionPoint(x, last))
                        }
                        add(SelectionPoint(0f, 8f))
                    }
                    measure(
                        "Vertical strips",
                        {
                            controller.changeSelectionMode(SelectionMode.Replace)
                            controller.select(
                                Selection(0, 0, 4096, 4096, SelectionKind.Lasso, comb)
                            )
                        },
                        { controller.document.selection?.kind == SelectionKind.Lasso },
                    )
                    measure(
                        "Intersect 512 x 512 strips, cached mask",
                        {
                            controller.changeSelectionMode(SelectionMode.Intersect)
                            controller.select(
                                Selection(
                                    0,
                                    0,
                                    4096,
                                    4096,
                                    SelectionKind.Lasso,
                                    comb.map { SelectionPoint(it.y, it.x) },
                                )
                            )
                        },
                        {
                            controller.document.selection?.combined == true &&
                                controller.selectionOutline?.mask?.isNotEmpty() == true
                        },
                    )
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
                    val outline = assertNotNull(controller.selectionOutline)
                    assertNull(outline.path)
                    assertTrue(outline.mask.isNotEmpty())
                    assertTrue(outline.mask.size <= 64)
                    val pixels = controller.frame
                    val rotationRenders = DoubleArray(20) {
                        val start = System.nanoTime()
                        controller.viewport = Viewport(rotation = it * 3f)
                        nativeWindow.renderImmediately()
                        assertSame(outline, controller.selectionOutline)
                        assertSame(pixels, controller.frame)
                        (System.nanoTime() - start) / 1_000_000.0
                    }
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
                    Selection build and state publication:
                    ${timings.entries.joinToString("\n                    ") { (name, time) -> "$name: %.2f ms".format(time) }}
                    Forced synchronous renders, measured separately:
                    ${renders.entries.joinToString("\n                    ") { (name, time) -> "$name: %.2f ms".format(time) }}
                    Repeated cached mask render: %.2f ms
                    Cached mask rotation, max of 20 forced renders: %.2f ms
                    Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms; $busyTicks responses during mask build.
                    Native window is outside the visible desktop. This does not measure presented FPS.
                """
                            .trimIndent()
                            .format(
                                renderMillis,
                                rotationRenders.max(),
                                waits[waits.size / 2],
                                waits[waits.size * 95 / 100],
                                waits.last(),
                            ) + "\n" + slowDispatches.joinToString("\n\n"),
                    )
                    assertTrue(
                        renders.values.all { it < 250.0 },
                        "Selection renders: $renders",
                    )
                    assertTrue(renderMillis < 100.0)
                    assertTrue(rotationRenders.max() < 100.0, "Rotation renders: ${rotationRenders.toList()}")
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
