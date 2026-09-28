package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalComposeUiApi::class)
class AdjustmentPerformanceTest {
    @Test
    fun latestAdjustmentWinsWithoutBlockingTheWindowOrChangingOriginalPixels() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
            NativeLoader.load()
            val native = createNativeEngine(2048, 2048)
            val project =
                try {
                    fun command(value: String) =
                        native.call(EngineOperation.COMMAND, value.encodeToByteArray())
                    for (index in 0..5) {
                        if (index > 0) command("""{"type":"add_layer"}""")
                        if (index == 5)
                            command(
                                """{"type":"select","rect":{"left":256,"top":256,"right":1792,"bottom":1792}}"""
                            )
                        command(
                            """{"type":"fill","x":1024,"y":1024,"color":[${50 + index * 20},${110 - index * 8},140,180],"tolerance":0}"""
                        )
                        val mode = if (index % 2 == 0) "multiply" else "screen"
                        command("""{"type":"set_blend","id":${index + 1},"mode":"$mode"}""")
                    }
                    native.call(EngineOperation.SAVE)
                } finally {
                    native.close()
                }
            val files =
                object : ProjectFiles {
                    override suspend fun open() = project

                    override suspend fun save(bytes: ByteArray, png: Boolean) =
                        error("Preview must not save")
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
            var window: ComposeWindow? = null
            suspend fun waitFor(predicate: () -> Boolean) =
                withTimeout(20_000) {
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
                val original = withContext(Dispatchers.Main) { controller.frame }
                val nativeWindow =
                    withContext(Dispatchers.Main) {
                        ComposeWindow().apply {
                            isUndecorated = true
                            focusableWindowState = false
                            setBounds(-3400, -2200, 1360, 900)
                            setContent {
                                PodorTheme {
                                    Row(Modifier.fillMaxSize().background(StudioTheme.background)) {
                                        CanvasWorkspace(
                                            controller,
                                            Modifier.weight(1f).fillMaxHeight(),
                                        )
                                        Inspector(
                                            controller,
                                            StudioPanel.Adjustments,
                                            {},
                                            Modifier.width(300.dp).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                            isVisible = true
                        }
                    }
                window = nativeWindow
                delay(250)
                withContext(Dispatchers.Main) { nativeWindow.renderImmediately() }
                val waits = mutableListOf<Double>()
                val measuring = AtomicBoolean(true)
                val heartbeat =
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
                val updates = linkedMapOf<String, Double>()
                val renders = linkedMapOf<String, Double>()
                val intermediateFrames =
                    linkedMapOf<AdjustmentKind, MutableSet<AdjustmentSettings>>()
                try {
                    for (kind in AdjustmentKind.entries) {
                        withContext(Dispatchers.Main) { controller.prepareAdjustment(kind) }
                        waitFor {
                            controller.adjustmentPreview != null &&
                                !controller.adjustmentPreview!!.updating
                        }
                        val start = System.nanoTime()
                        val expected =
                            withContext(Dispatchers.Main) {
                                val settings = controller.adjustmentPreview!!.settings
                                repeat(300) { index ->
                                    controller.updateAdjustment(
                                        settings.copy(
                                            brightness = (index % 100) / 100f,
                                            sigma = 0.5f + index % 32,
                                            opacity = (index % 100) / 100f,
                                            blend =
                                                LayerBlendMode.entries[
                                                        index % LayerBlendMode.entries.size],
                                            curves =
                                                ColorCurves(
                                                    rgb =
                                                        ToneCurve()
                                                            .insert(
                                                                CurvePoint(128, 40 + index % 160)
                                                            )
                                                ),
                                        )
                                    )
                                }
                                settings
                                    .copy(
                                        brightness = 0.3f,
                                        contrast = 0.15f,
                                        saturation = -0.2f,
                                        sigma = 14f,
                                        opacity = 0.45f,
                                        blend = LayerBlendMode.Multiply,
                                        curves =
                                            ColorCurves(
                                                rgb = ToneCurve().insert(CurvePoint(128, 175))
                                            ),
                                    )
                                    .also(controller::updateAdjustment)
                            }
                        waitFor {
                            controller.adjustmentPreview!!.renderedSettings == expected &&
                                !controller.adjustmentPreview!!.updating
                        }
                        updates[kind.name] = (System.nanoTime() - start) / 1_000_000.0
                        withContext(Dispatchers.Main) {
                            val renderStart = System.nanoTime()
                            nativeWindow.renderImmediately()
                            renders[kind.name] = (System.nanoTime() - renderStart) / 1_000_000.0
                            assertSame(original, controller.frame)
                            assertTrue(controller.adjustmentPreview!!.changed)
                            assertFalse(controller.hasUnsavedChanges)
                            assertFalse(controller.document.canUndo)
                        }
                        if (kind != AdjustmentKind.Blur) {
                            val received = mutableSetOf<AdjustmentSettings>()
                            intermediateFrames[kind] = received
                            repeat(90) { index ->
                                withContext(Dispatchers.Main) {
                                    val preview = controller.adjustmentPreview!!
                                    preview.renderedSettings
                                        ?.takeIf { it != expected }
                                        ?.let(received::add)
                                    controller.updateAdjustment(
                                        expected.copy(
                                            brightness = 0.05f + index / 120f,
                                            opacity = 0.1f + index / 120f,
                                            curves =
                                                ColorCurves(
                                                    rgb =
                                                        ToneCurve()
                                                            .insert(CurvePoint(128, 80 + index))
                                                ),
                                        )
                                    )
                                }
                                delay(16)
                            }
                            waitFor { !controller.adjustmentPreview!!.updating }
                        }
                        withContext(Dispatchers.Main) { controller.cancelAdjustment() }
                    }
                } finally {
                    measuring.set(false)
                    heartbeat.join()
                }
                val api = withContext(Dispatchers.Main) { nativeWindow.renderApi.toString() }
                assertTrue(api in listOf("DIRECT3D", "OPENGL", "METAL"))
                waits.sort()
                val report = buildString {
                    appendLine("Renderer: $api")
                    appendLine(
                        "2048 x 2048 canvas, six mixed layers, 300 slider changes per burst."
                    )
                    intermediateFrames.forEach { (kind, frames) ->
                        appendLine(
                            "$kind distinct previews during 90 continuous slider changes: ${frames.size}"
                        )
                    }
                    updates.forEach { (kind, time) ->
                        appendLine(
                            "$kind latest settings to matching preview: %.2f ms".format(time)
                        )
                    }
                    renders.forEach { (kind, time) ->
                        appendLine("$kind forced native render: %.2f ms".format(time))
                    }
                    appendLine(
                        "Main-thread dispatch: median %.2f ms, p95 %.2f ms, max %.2f ms"
                            .format(
                                waits[waits.size / 2],
                                waits[waits.size * 95 / 100],
                                waits.last(),
                            )
                    )
                    appendLine(
                        "The window is outside the visible desktop. This does not measure presented FPS or physical pen latency."
                    )
                }
                val output = Path.of("build/reports/adjustment-performance.txt")
                Files.createDirectories(output.parent)
                Files.writeString(output, report)
                assertTrue(updates.values.all { it < 5_000.0 }, report)
                assertTrue(intermediateFrames.values.all { it.size >= 2 }, report)
                assertTrue(renders.values.all { it < 250.0 }, report)
                assertTrue(waits.last() < 250.0, report)
            } finally {
                withContext(Dispatchers.Main) {
                    window?.dispose()
                    controller.shutdown()
                }
                scope.cancel()
            }
        }
}
