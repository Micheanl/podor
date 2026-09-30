package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class WorkspaceRenderingTest {
    private data class Artwork(
        val reference: ProjectReference,
        val bytes: ByteArray,
        val thumbnail: ByteArray,
    )

    private class Library(val artwork: List<Artwork>) : ProjectFiles {
        override suspend fun open() = artwork.firstOrNull()?.bytes

        override suspend fun openDocument(reference: ProjectReference?) =
            artwork
                .firstOrNull { reference == null || it.reference == reference }
                ?.let { OpenedProject(it.bytes, it.reference) }

        override suspend fun save(bytes: ByteArray, png: Boolean) = false

        override suspend fun recentProjects() = artwork.mapIndexed { index, art ->
            RecentProject(art.reference, 512, 384, (10 - index).toLong())
        }

        override suspend fun readThumbnail(reference: ProjectReference) =
            artwork.first { it.reference == reference }.thumbnail
    }

    private fun artwork(name: String, color: String, layers: Int = 1): Artwork {
        val engine = createNativeEngine(512, 384)
        try {
            engine.call(
                EngineOperation.COMMAND,
                """{"type":"fill","x":0,"y":0,"color":[$color,255],"tolerance":0}"""
                    .encodeToByteArray(),
            )
            repeat(layers - 1) {
                engine.call(EngineOperation.COMMAND, """{"type":"add_layer"}""".encodeToByteArray())
            }
            engine.call(
                EngineOperation.COMMAND,
                """{"type":"select_layer","id":1}""".encodeToByteArray(),
            )
            return Artwork(
                ProjectReference(name + ".podor", name),
                engine.call(EngineOperation.SAVE),
                engine.call(EngineOperation.THUMBNAIL),
            )
        } finally {
            engine.close()
        }
    }

    private suspend fun awaitState(predicate: () -> Boolean) =
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
        }

    private fun capture(scene: ImageComposeScene, name: String, time: Long) {
        val directory = Path.of("build", "reports", "screenshots")
        Files.createDirectories(directory)
        scene.render(time).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                Files.write(directory.resolve("$name.png"), it.bytes)
            }
        }
    }

    @Test
    fun workspaceLayoutsRenderAndArtworkCardOpensItsFile() =
        runBlocking<Unit> {
            NativeLoader.load()
            val library =
                Library(
                    listOf(
                        artwork("远山", "67,86,90"),
                        artwork("Bordeaux", "119,49,64"),
                        artwork("Light study", "190,172,135"),
                    )
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(library, scope) }
            try {
                awaitState { controller.ready }
                for ((name, width, height) in
                    listOf(
                        Triple("workspace", 1360, 900),
                        Triple("workspace-small", 680, 600),
                        Triple("workspace-narrow", 400, 600),
                    )) {
                    val scene =
                        withContext(Dispatchers.Main) {
                            ImageComposeScene(width, height) {
                                Column(Modifier.fillMaxSize()) {
                                    WindowTitleBar(
                                        controller.preferences.language,
                                        false,
                                        {},
                                        {},
                                        {},
                                        background = StudioTheme.background,
                                    )
                                    Box(Modifier.weight(1f)) { WorkspaceHome(controller) }
                                }
                            }
                        }
                    try {
                        withContext(Dispatchers.Main) { scene.render().close() }
                        delay(100)
                        withContext(Dispatchers.Main) {
                            repeat(30) { scene.render((it + 1) * 16_666_667L).close() }
                            capture(scene, name, 600_000_000L)
                            if (name == "workspace") {
                                val nextPosition = Offset(732f, 854f)
                                scene.sendPointerEvent(PointerEventType.Press, nextPosition)
                                scene.sendPointerEvent(PointerEventType.Release, nextPosition)
                                repeat(45) { scene.render(700_000_000L + it * 16_666_667L).close() }
                                capture(scene, "workspace-next", 1_500_000_000L)
                                val artworkPosition = Offset(680f, 514f)
                                scene.sendPointerEvent(PointerEventType.Press, artworkPosition)
                                scene.sendPointerEvent(PointerEventType.Release, artworkPosition)
                            }
                        }
                        if (name == "workspace") {
                            awaitState { controller.document.width == 512 && !controller.busy }
                            withContext(Dispatchers.Main) {
                                assertEquals("Bordeaux", controller.projectReference?.name)
                                controller.home()
                                repeat(40) {
                                    scene.render(1_600_000_000L + it * 16_666_667L).close()
                                }
                                scene.sendPointerEvent(PointerEventType.Press, Offset(680f, 514f))
                                repeat(20) {
                                    scene.sendPointerEvent(
                                        PointerEventType.Move,
                                        Offset(680f - (it + 1) * 13f, 514f),
                                    )
                                    scene.render(2_400_000_000L + it * 16_666_667L).close()
                                }
                                scene.sendPointerEvent(PointerEventType.Release, Offset(420f, 514f))
                                repeat(45) {
                                    scene.render(2_800_000_000L + it * 16_666_667L).close()
                                }
                                scene.sendPointerEvent(PointerEventType.Press, Offset(680f, 514f))
                                scene.sendPointerEvent(PointerEventType.Release, Offset(680f, 514f))
                            }
                            awaitState {
                                controller.projectReference?.name == "Light study" &&
                                    !controller.busy
                            }
                            withContext(Dispatchers.Main) {
                                controller.home()
                                scene.sendPointerEvent(PointerEventType.Move, Offset(1f, 1f))
                                repeat(320) {
                                    scene.render(3_700_000_000L + it * 16_666_667L).close()
                                }
                                assertFalse(
                                    scene.hasInvalidations(),
                                    "Workspace animations must settle",
                                )
                            }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { scene.close() }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }

    @Test
    fun layerToolbarStaysReachableAtThirtyTwoLayersAndAnimationsSettle() =
        runBlocking<Unit> {
            NativeLoader.load()
            val library = Library(listOf(artwork("Layers", "80,120,160", 31)))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = withContext(Dispatchers.Main) { StudioController(library, scope) }
            try {
                awaitState { controller.ready }
                withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
                awaitState {
                    controller.document.layers.size == 31 &&
                        !controller.busy &&
                        controller.previews.revision == controller.document.revision
                }
                val scene =
                    withContext(Dispatchers.Main) {
                        ImageComposeScene(300, 580) {
                            PodorTheme {
                                Surface(color = StudioTheme.panel) {
                                    Box(Modifier.padding(20.dp)) { LayerControls(controller) }
                                }
                            }
                        }
                    }
                var frame = 0L
                suspend fun settle() =
                    withContext(Dispatchers.Main) {
                        repeat(35) { scene.render(frame++ * 16_666_667L).close() }
                    }
                suspend fun click(x: Float, y: Float) =
                    withContext(Dispatchers.Main) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                        scene.sendPointerEvent(PointerEventType.Move, Offset(1f, 1f))
                    }
                try {
                    settle()
                    click(48f, 532f)
                    awaitState {
                        controller.document.layers.size == 32 &&
                            controller.previews.revision == controller.document.revision
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        capture(scene, "layers-32", frame++ * 16_666_667L)
                    }
                    click(48f, 532f)
                    settle()
                    assertEquals(
                        32,
                        withContext(Dispatchers.Main) { controller.document.layers.size },
                    )
                    click(200f, 532f)
                    awaitState {
                        controller.document.layers.size == 1 &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                    settle()
                    withContext(Dispatchers.Main) {
                        assertFalse(scene.hasInvalidations())
                        capture(scene, "layers-merged", frame++ * 16_666_667L)
                    }
                } finally {
                    withContext(Dispatchers.Main) { scene.close() }
                }
            } finally {
                withContext(Dispatchers.Main) { controller.shutdown() }
                scope.cancel()
            }
        }
}
