package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.SymmetryMode
import app.podor.domain.SymmetrySettings
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class PaintingPerformanceTest {
    @Test
    fun multilayerPaintingRefreshesDirtyTilesWhileDesktopRemainsResponsive() =
        painting(SymmetryMode.Off, "painting-performance.txt")

    @Test
    fun fourWaySymmetryRefreshesDirtyTilesWhileDesktopRemainsResponsive() =
        painting(SymmetryMode.Quadrant, "symmetry-performance.txt")

    private fun painting(symmetry: SymmetryMode, reportName: String) =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val engine = createNativeEngine(2048, 2048)
            val project =
                try {
                    for (id in 1..8) {
                        if (id > 1)
                            engine.call(
                                EngineOperation.COMMAND,
                                """{"type":"add_layer"}""".encodeToByteArray(),
                            )
                        engine.call(
                            EngineOperation.COMMAND,
                            """{"type":"fill","x":0,"y":0,"color":[${id * 20},${160 - id * 10},120,180],"tolerance":0}"""
                                .encodeToByteArray(),
                        )
                        if (id > 1)
                            engine.call(
                                EngineOperation.COMMAND,
                                """{"type":"set_blend","id":$id,"mode":"${if (id % 2 == 0) "soft_light" else "multiply"}"}"""
                                    .encodeToByteArray(),
                            )
                    }
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
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
                withTimeout(20_000) {
                    while (!withContext(Dispatchers.Main) { predicate() }) delay(1)
                }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState {
                    controller.document.width == 2048 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                val original = withContext(Dispatchers.Main) { controller.frame }
                val originalRevision =
                    withContext(Dispatchers.Main) { controller.document.revision }
                assertEquals(256, original.tiles.size)
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
                val inputMillis = mutableListOf<Double>()
                val renderMillis = mutableListOf<Double>()
                val dispatchMillis = mutableListOf<Double>()
                val slowDispatches = mutableListOf<String>()
                val batchIndex = AtomicInteger(-1)
                val eventThread = withContext(Dispatchers.Main) { Thread.currentThread() }
                val heartbeat =
                    launch(Dispatchers.Default) {
                        while (isActive) {
                            delay(16)
                            val start = System.nanoTime()
                            val batch = batchIndex.get()
                            val dispatch =
                                async(Dispatchers.Main) {
                                    (System.nanoTime() - start) / 1_000_000.0
                                }
                            val elapsed =
                                withTimeoutOrNull(50) { dispatch.await() }
                                    ?: run {
                                        slowDispatches.add(
                                            "Batch $batch: " +
                                                eventThread.stackTrace.take(24).joinToString("\n")
                                        )
                                        dispatch.await()
                                    }
                            dispatchMillis.add(elapsed)
                        }
                    }
                try {
                    withContext(Dispatchers.Main) {
                        controller.symmetry = SymmetrySettings(mode = symmetry)
                        controller.brush =
                            controller.brush.copy(
                                size = 256f,
                                opacity = 0.6f,
                                preset = controller.brush.preset.copy(
                                    hardness = 0.5f,
                                    pressureCurve = 0.5f,
                                    sizePressure = 0.5f,
                                    opacityPressure = 0.75f,
                                ),
                                color = 0xFF893A55,
                            )
                        controller.begin(Offset(256f, 1024f), 0.8f)
                    }
                    repeat(60) { batch ->
                        batchIndex.set(batch)
                        val points =
                            (0..7).map { index ->
                                val t = (batch * 8 + index) / 480f
                                Triple(
                                    256f + t * 1536f,
                                    1024f + sin(t * 8f) * 200f,
                                    0.75f + 0.2f * sin(t * 12f),
                                )
                            }
                        val start = System.nanoTime()
                        val before =
                            withContext(Dispatchers.Main) {
                                val before = controller.frame
                                controller.points(points)
                                before
                            }
                        awaitState { controller.frame !== before || controller.error != null }
                        val input = (System.nanoTime() - start) / 1_000_000.0
                        val rendered =
                            withContext(Dispatchers.Main) {
                                assertNull(controller.error)
                                assertSame(
                                    original.tiles.getValue(0L),
                                    controller.frame.tiles.getValue(0L),
                                )
                                val renderStart = System.nanoTime()
                                nativeWindow.renderImmediately()
                                (System.nanoTime() - renderStart) / 1_000_000.0
                            }
                        if (batch >= 10) {
                            inputMillis.add(input)
                            renderMillis.add(rendered)
                        }
                    }
                    batchIndex.set(60)
                    withContext(Dispatchers.Main) { controller.end() }
                    awaitState {
                        controller.document.revision > originalRevision &&
                            controller.previews.revision == controller.document.revision
                    }
                } finally {
                    heartbeat.cancelAndJoin()
                }
                assertTrue(dispatchMillis.size > 20)
                fun summary(values: List<Double>): String {
                    val sorted = values.sorted()
                    return "median %.2f ms, p95 %.2f ms, max %.2f ms"
                        .format(
                            sorted[sorted.size / 2],
                            sorted[sorted.size * 95 / 100],
                            sorted.last(),
                        )
                }
                withContext(Dispatchers.Main) {
                    assertNull(controller.error)
                    assertTrue(controller.hasUnsavedChanges)
                    assertEquals(originalRevision + 1, controller.document.revision)
                    val api = nativeWindow.renderApi.toString()
                    assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                    val report = Path.of("build/reports/$reportName")
                    Files.createDirectories(report.parent)
                    Files.writeString(
                        report,
                        """
                    Renderer: $api
                    Symmetry: $symmetry
                    1360 x 900 native StudioApp window outside the visible desktop; 2048 x 2048 canvas, 8 mixed layers, brush 256 px, 480 samples.
                    Pressure curve: ${controller.brush.preset.pressureCurve}; size response: ${controller.brush.preset.sizePressure}; opacity response: ${controller.brush.preset.opacityPressure}.
                    Input batch to published pixel frame: ${summary(inputMillis)}
                    Forced native render call: ${summary(renderMillis)}
                    Main-thread dispatch wait during drawing and thumbnail completion: ${summary(dispatchMillis)}
                    Untouched canvas tile retained; stroke commits once; thumbnails catch up.
                    Forced rendering and an offscreen window do not measure presented FPS or physical pen-to-display latency.
                """
                            .trimIndent() + "\n" + slowDispatches.joinToString("\n\n"),
                    )
                    if (symmetry != SymmetryMode.Off) {
                        val dispatch = dispatchMillis.sorted()
                        assertTrue(dispatch[dispatch.size * 95 / 100] < 50.0, "Symmetry dispatch p95 exceeded 50 ms")
                        assertTrue(dispatch.last() < 250.0, "Symmetry blocked the main thread for ${dispatch.last()} ms")
                    }
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
