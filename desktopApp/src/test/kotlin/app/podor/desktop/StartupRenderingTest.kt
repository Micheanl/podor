package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class StartupRenderingTest {
    private open class Files : ProjectFiles {
        override suspend fun open(): ByteArray? = null

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
            error("Startup must not save the project")
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun nodes(scene: ImageComposeScene) =
        scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

    private fun control(scene: ImageComposeScene, label: String) =
        nodes(scene)
            .filter {
                it.config.contains(SemanticsActions.OnClick) &&
                    descendants(it).any { child ->
                        child.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains(label) == true ||
                            child.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                text.text == label
                            } == true
                    }
            }
            .minBy { it.size.width * it.size.height }

    private fun click(scene: ImageComposeScene, label: String) {
        val target = control(scene, label)
        assertFalse(target.config.contains(SemanticsProperties.Disabled), label)
        assertFalse(target.boundsInWindow.isEmpty, label)
        scene.sendPointerEvent(PointerEventType.Press, target.boundsInWindow.center)
        scene.sendPointerEvent(PointerEventType.Release, target.boundsInWindow.center)
    }

    private suspend fun waitFor(predicate: () -> Boolean) =
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(5)
        }

    private suspend fun restore(appearance: Appearance) =
        withContext(Dispatchers.Main) {
            val scene = ImageComposeScene(1, 1) { PodorTheme(appearance = appearance) {} }
            try {
                scene.render(0).close()
            } finally {
                scene.close()
            }
        }

    @Test
    fun savedStartupPageAndThemeAreInteractiveOnTheFirstFrameWithoutWaiting() = runBlocking {
        NativeLoader.load()
        val originalAppearance = StudioTheme.appearance
        try {
            for (appearance in Appearance.entries) for (startupScreen in StartupScreen.entries) {
                val preferences =
                    Preferences(
                        appearance = appearance,
                        language = Language.English,
                        startupScreen = startupScreen,
                    )
                val files =
                    object : Files() {
                        override suspend fun readPreferences(): ByteArray {
                            assertFalse(EventQueue.isDispatchThread())
                            return Json.encodeToString(preferences).encodeToByteArray()
                        }
                    }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
                var scene: ImageComposeScene? = null
                var minimized = 0
                var dragRegion = Rect.Zero
                try {
                    waitFor { controller.ready }
                    withContext(Dispatchers.Main) {
                        val readyScene =
                            ImageComposeScene(960, 720) {
                                PodorApp(
                                    controller,
                                    titleBarHeight = StudioTheme.windowTitleHeight,
                                    onTitleDragRegion = { dragRegion = it },
                                ) {
                                    WindowTitleBar(
                                        Language.English,
                                        false,
                                        { minimized++ },
                                        {},
                                        {},
                                        if (controller.showWorkspace) StudioTheme.background
                                        else StudioTheme.panel,
                                    )
                                }
                            }
                        scene = readyScene
                        readyScene.render(0).use { image ->
                            val pixel = image.toComposeImageBitmap().toPixelMap()[10, 10]
                            assertEquals(1f, pixel.alpha)
                            assertEquals(appearance, StudioTheme.appearance)
                            assertEquals(appearance == Appearance.Light, pixel.red > 0.4f)
                        }
                        assertTrue(controller.document.maxLayers > 0)
                        assertEquals(1, controller.document.rasterLayerCount)
                        assertTrue(controller.frame.tiles.isEmpty())
                        assertFalse(controller.startupFailed)
                        val canvases =
                            nodes(readyScene)
                                .filter {
                                    it.config.getOrNull(SemanticsProperties.TestTag) ==
                                        "canvas-workspace"
                                }
                                .toList()
                        assertEquals(
                            if (startupScreen == StartupScreen.Canvas) 1 else 0,
                            canvases.size,
                        )
                        if (canvases.isNotEmpty()) {
                            assertTrue(canvases.single().boundsInWindow.width > 100f)
                            assertTrue(dragRegion.width > 0f)
                        }
                        click(readyScene, "Minimize")
                        assertEquals(1, minimized)
                        val owners = readyScene.semanticsOwners.size
                        click(
                            readyScene,
                            trValue(
                                if (startupScreen == StartupScreen.Workspace) "新建画布" else "工程菜单",
                                Language.English,
                            ),
                        )
                        readyScene.render(16_666_667L).close()
                        assertTrue(
                            readyScene.semanticsOwners.size > owners,
                            "First click was blocked",
                        )
                        assertFalse(controller.document.canUndo)
                        assertFalse(controller.hasUnsavedChanges)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        scene?.close()
                        controller.shutdown()
                    }
                    scope.cancel()
                }
            }
        } finally {
            restore(originalAppearance)
        }
    }

    @Test
    fun preferenceWarningDoesNotRevealContentBeforeNativeInitializationCompletes() = runBlocking {
        NativeLoader.load()
        val readRecent = CompletableDeferred<Unit>()
        val releaseRecent = CompletableDeferred<Unit>()
        val files =
            object : Files() {
                override suspend fun readPreferences() = "invalid preferences".encodeToByteArray()

                override suspend fun recentProjects(): List<RecentProject> {
                    assertFalse(EventQueue.isDispatchThread())
                    readRecent.complete(Unit)
                    releaseRecent.await()
                    return emptyList()
                }
            }
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) { ImageComposeScene(400, 300) { PodorApp(controller) } }
        try {
            withTimeout(10_000) { readRecent.await() }
            withContext(Dispatchers.Main) {
                assertNotNull(controller.error)
                assertFalse(controller.ready)
                assertFalse(controller.startupFailed)
                scene.render(0).use { image ->
                    assertEquals(0f, image.toComposeImageBitmap().toPixelMap()[10, 80].alpha)
                }
                assertTrue(nodes(scene).none { it.config.contains(SemanticsActions.OnClick) })
            }
            releaseRecent.complete(Unit)
            waitFor { controller.ready }
            withContext(Dispatchers.Main) {
                scene.render(16_666_667L).use { image ->
                    assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[10, 80].alpha)
                }
                assertTrue(controller.showWorkspace)
                assertTrue(controller.document.maxLayers > 0)
                assertEquals(1, controller.document.rasterLayerCount)
                assertTrue(controller.frame.tiles.isEmpty())
                assertFalse(controller.startupFailed)
                assertTrue(
                    nodes(scene).any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == controller.error
                        } == true
                    }
                )
                click(scene, "知道了")
                for (frame in 2L..35L) {
                    scene.render(frame * 16_666_667L).close()
                    yield()
                }
                assertNull(controller.error)
                val owners = scene.semanticsOwners.size
                click(scene, "新建画布")
                scene.render(36L * 16_666_667L).close()
                assertTrue(scene.semanticsOwners.size > owners)
            }
        } finally {
            releaseRecent.complete(Unit)
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
            restore(originalAppearance)
        }
    }

    @Test
    fun fatalInitializationShowsTheErrorWithoutEnablingAnUninitializedCanvas() = runBlocking {
        NativeLoader.load()
        val files =
            object : Files() {
                override suspend fun readPreferences() =
                    Json.encodeToString(
                            Preferences(
                                startupScreen = StartupScreen.Canvas,
                                appearance = Appearance.Light,
                                language = Language.English,
                            )
                        )
                        .encodeToByteArray()

                override suspend fun recentProjects(): List<RecentProject> =
                    throw LinkageError("Startup fixture failure")
            }
        val originalAppearance = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        var scene: ImageComposeScene? = null
        var minimized = 0
        try {
            waitFor { controller.startupFailed }
            withContext(Dispatchers.Main) {
                assertFalse(controller.ready)
                assertEquals("Startup fixture failure", controller.error)
                assertEquals(StartupScreen.Canvas, controller.preferences.startupScreen)
                val failedScene =
                    ImageComposeScene(400, 300) {
                        PodorApp(controller, titleBarHeight = StudioTheme.windowTitleHeight) {
                            WindowTitleBar(Language.English, false, { minimized++ }, {}, {})
                        }
                    }
                scene = failedScene
                failedScene.render(0).use { image ->
                    assertEquals(1f, image.toComposeImageBitmap().toPixelMap()[10, 80].alpha)
                }
                assertTrue(
                    nodes(failedScene).any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == controller.error
                        } == true
                    }
                )
                assertTrue(
                    nodes(failedScene).none {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace"
                    }
                )
                assertTrue(
                    nodes(failedScene).none {
                        it.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains("New canvas") == true
                    }
                )
                click(failedScene, "Minimize")
                assertEquals(1, minimized)
            }
        } finally {
            withContext(Dispatchers.Main) {
                scene?.close()
                controller.shutdown()
            }
            scope.cancel()
            restore(originalAppearance)
        }
    }
}
