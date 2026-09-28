package app.podor.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawWithContent
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.presentation.StudioController
import app.podor.ui.PodorApp
import app.podor.ui.StudioTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*

class StartupWindowTest {
    @Test
    fun undecoratedWindowDrawsStartupAndWorkspace() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("PODOR_GPU_TEST") == "1")
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        NativeLoader.load()
        val frames = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false
            }
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val window =
            withContext(Dispatchers.Main) {
                ComposeWindow().apply {
                    isUndecorated = true
                    isAutoRequestFocus = false
                    setBounds(-3000, -2000, 800, 600)
                    val host = this
                    setContent {
                        WindowsChrome(host)
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                frames.incrementAndGet()
                            }
                        ) {
                            PodorApp(controller, titleBarHeight = StudioTheme.windowTitleHeight) {
                                WindowTitleBar(app.podor.domain.Language.English, false, {}, {}, {})
                            }
                        }
                    }
                    isVisible = true
                }
            }
        try {
            withTimeout(10000) { while (frames.get() < 1) delay(50) }
            withTimeout(10000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(20)
            }
            println("Startup frames: ${frames.get()}, backend: ${window.renderApi}")
            delay(4000)
            withContext(Dispatchers.Main) {
                fun layer(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? {
                    if (component is org.jetbrains.skiko.SkiaLayer) return component
                    return (component as? java.awt.Container)?.components?.firstNotNullOfOrNull {
                        layer(it)
                    }
                }
                val surface = assertNotNull(layer(window))
                assertNotNull(surface.screenshot()).use { bitmap ->
                    val output =
                        java.nio.file.Path.of("build/reports/screenshots/native-startup.png")
                    java.nio.file.Files.createDirectories(output.parent)
                    org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { image ->
                        image.encodeToData()!!.use { java.nio.file.Files.write(output, it.bytes) }
                    }
                    var brightPixels = 0
                    for (y in 0 until bitmap.height step 8) {
                        for (x in 0 until bitmap.width step 8) {
                            val color = bitmap.getColor(x, y)
                            if (((color ushr 16) and 255) > 160 && ((color ushr 8) and 255) > 160)
                                brightPixels++
                        }
                    }
                    assertTrue(brightPixels > 100, "Native window has no visible workspace content")
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                window.dispose()
                controller.shutdown()
            }
            scope.cancel()
        }
    }
}
