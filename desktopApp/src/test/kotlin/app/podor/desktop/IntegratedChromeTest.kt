package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.Language
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class IntegratedChromeTest {
    @Test
    fun mergedHeaderKeepsToolsWindowControlsAndDraggingSeparate() = runBlocking {
        NativeLoader.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files =
            object : ProjectFiles {
                override suspend fun open(): ByteArray? = null

                override suspend fun save(bytes: ByteArray, png: Boolean) = false
            }
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        try {
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { controller.ready }) delay(10)
            }
            withContext(Dispatchers.Main) {
                for (width in listOf(1360, 680, 400)) {
                    var drag = Rect.Zero
                    var closed = false
                    val scene =
                        ImageComposeScene(width, 900) {
                            Box(Modifier.fillMaxSize().borderTrail(true)) {
                                StudioApp(
                                    controller,
                                    windowControls = {
                                        WindowTitleBar(
                                            Language.English,
                                            false,
                                            {},
                                            {},
                                            { closed = true },
                                        )
                                    },
                                    onTitleDragRegion = { drag = it },
                                )
                            }
                        }
                    try {
                        val recording =
                            width == 1360 && System.getenv("PODOR_CAPTURE_BORDER") == "1"
                        val frames = Path.of("build/reports/border-trail")
                        if (recording) Files.createDirectories(frames)
                        repeat(240) { frame ->
                            if (recording && frame == 40)
                                scene.sendPointerEvent(PointerEventType.Move, Offset(1110f, 650f))
                            if (recording && frame == 210)
                                scene.sendPointerEvent(PointerEventType.Move, Offset(1f, 400f))
                            scene.render(frame * 16_666_667L).use { image ->
                                if (recording)
                                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                        Files.write(
                                            frames.resolve("%03d.png".format(frame)),
                                            it.bytes,
                                        )
                                    }
                            }
                        }
                        assertEquals(0f, drag.top)
                        assertEquals(StudioTheme.windowTitleHeight.value, drag.bottom)
                        assertTrue(drag.width >= 40f, "A usable drag region must remain at $width")
                        val hit: (Int, Int) -> Boolean = { x, y ->
                            drag.contains(Offset(x.toFloat(), y.toFloat()))
                        }
                        assertEquals(
                            WindowHit.CLIENT,
                            WindowHit.at(60, 22, width, 900, 1f, false, hit),
                        )
                        assertEquals(
                            WindowHit.CAPTION,
                            WindowHit.at(drag.center.x.toInt(), 22, width, 900, 1f, false, hit),
                        )
                        scene.sendPointerEvent(PointerEventType.Press, Offset(width - 23f, 22f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(width - 23f, 22f))
                        assertTrue(closed)
                        scene.sendPointerEvent(PointerEventType.Move, Offset(1f, 400f))
                        repeat(30) { scene.render(4_100_000_000L + it * 16_666_667L).close() }
                        assertFalse(scene.hasInvalidations())
                        val output =
                            Path.of("build/reports/screenshots/title-integrated-$width.png")
                        Files.createDirectories(output.parent)
                        scene.render(4_700_000_000L).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(output, it.bytes)
                            }
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }
}
