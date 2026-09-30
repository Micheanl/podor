package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
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

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
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
                    val density = Density(1f)
                    val scene =
                        ImageComposeScene(width, 900, density = density) {
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
                        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                            yield(node)
                            node.children.forEach { yieldAll(descendants(it)) }
                        }
                        fun control(label: String) =
                            scene.semanticsOwners
                                .asSequence()
                                .flatMap { descendants(it.rootSemanticsNode) }
                                .filter {
                                    it.config.contains(SemanticsActions.OnClick) &&
                                        it.config
                                            .getOrNull(SemanticsProperties.ContentDescription)
                                            ?.contains(label) == true &&
                                        !it.boundsInWindow.isEmpty
                                }
                                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
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
                        assertEquals(with(density) { StudioTheme.headerHeight.toPx() }, drag.bottom)
                        assertTrue(drag.width >= 40f, "A usable drag region must remain at $width")
                        val hit: (Int, Int) -> Boolean = { x, y ->
                            drag.contains(Offset(x.toFloat(), y.toFloat()))
                        }
                        assertEquals(
                            WindowHit.CLIENT,
                            control(trValue("工程菜单", controller.preferences.language))
                                .boundsInWindow
                                .center
                                .let {
                                    WindowHit.at(
                                        it.x.toInt(),
                                        it.y.toInt(),
                                        width,
                                        900,
                                        density.density,
                                        false,
                                        hit,
                                    )
                                },
                        )
                        assertEquals(
                            WindowHit.CAPTION,
                            WindowHit.at(
                                drag.center.x.toInt(),
                                drag.center.y.toInt(),
                                width,
                                900,
                                density.density,
                                false,
                                hit,
                            ),
                        )
                        val close = control("Close").boundsInWindow.center
                        scene.sendPointerEvent(PointerEventType.Press, close)
                        scene.sendPointerEvent(PointerEventType.Release, close)
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
