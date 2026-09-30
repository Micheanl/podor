package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class WorkspaceEntryRenderingTest {
    private val headerActions = setOf("保存工程", "导出图像", "设置", "展开面板")
    private val tools = Tool.entries.filter { it != Tool.Vector }.map { it.label }.toSet()

    @Test
    fun projectMenuOnlyRestoresActionsMissingFromTheActualHeader() = runBlocking {
        withController { controller ->
            for ((width, expectedHeader) in
                listOf(1600 to headerActions, 400 to setOf("导出图像", "展开面板"), 220 to emptySet())) {
                withScene(
                    width = width,
                    height = 500,
                    content = {
                        StudioHeader(
                            controller,
                            compact = width != 1600,
                            showDocument = false,
                            onDialog = {},
                            onToggleInspector = {},
                            windowControls = { Spacer(Modifier.fillMaxSize()) },
                        )
                    },
                ) {
                    val actualLabels =
                        withContext(Dispatchers.Main) {
                            assertEntries(mainOwner, expectedHeader, headerActions)
                            assertRendered(mainOwner, expectedHeader + "工程菜单")
                            controls(mainOwner.rootSemanticsNode)
                                .mapNotNull { label(it) }
                                .filter { it.substringBefore(" ·") in headerActions }
                        }
                    click(mainOwner, "工程菜单")
                    withContext(Dispatchers.Main) {
                        val popup = popupOwner()
                        assertEntries(mainOwner, expectedHeader, headerActions)
                        assertEntries(popup, headerActions - expectedHeader, headerActions)
                        assertRendered(popup, headerActions - expectedHeader)
                        val popupLabels = entries(popup)
                        actualLabels.forEach {
                            assertFalse(it.substringBefore(" ·") in popupLabels)
                        }
                        headerActions.forEach { action ->
                            assertEquals(
                                1,
                                (entries(mainOwner) + popupLabels).count { it == action },
                            )
                        }
                        listOf("工具选项", "视图", "动画", "更多工具", "剪贴板", "参考图").forEach {
                            assertFalse(it in popupLabels)
                            assertFalse(it in entries(mainOwner))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyDockDirectionOmitsMoreWhenEveryAvailableToolFits() = runBlocking {
        withController { controller ->
            for (dock in ToolDockPosition.entries) {
                withDock(controller, WorkspaceAppearance(toolDock = dock)) {
                    withContext(Dispatchers.Main) {
                        assertEntries(mainOwner, tools, tools + "更多工具")
                        assertRendered(mainOwner, tools)
                        assertEquals(1, scene.semanticsOwners.size)
                    }
                }
            }
        }
    }

    @Test
    fun hiddenToolsAreTheOnlyMenuEntriesAndRemainSelectable() = runBlocking {
        withController { controller ->
            val hidden = setOf(Tool.Picker, Tool.Hand)
            withDock(
                controller,
                WorkspaceAppearance(hiddenTools = hidden.map { it.name }.toSet()),
            ) {
                withContext(Dispatchers.Main) {
                    assertEntries(mainOwner, tools - hidden.map { it.label }.toSet(), tools)
                }
                for (tool in hidden) {
                    click(mainOwner, "更多工具")
                    val popup =
                        withContext(Dispatchers.Main) {
                            popupOwner().also {
                                assertEntries(it, hidden.map { tool -> tool.label }.toSet(), tools)
                                assertEquals(hidden.size, entries(it).size)
                                assertRendered(it, hidden.map { tool -> tool.label }.toSet())
                            }
                        }
                    click(popup, tool.label)
                    withContext(Dispatchers.Main) {
                        assertEquals(tool, controller.tool)
                        assertEquals(1, scene.semanticsOwners.size)
                    }
                }
            }
        }
    }

    @Test
    fun shortDockMenuContainsOnlyOverflowAndSelectingItClosesThePopup() = runBlocking {
        withController { controller ->
            withDock(controller, WorkspaceAppearance(), width = 420, height = 350) {
                val visible =
                    withContext(Dispatchers.Main) {
                        entries(mainOwner)
                            .filter { it in tools }
                            .toSet()
                            .also {
                                assertTrue(it.isNotEmpty())
                                assertTrue(it.size < tools.size)
                                assertRendered(mainOwner, it + "更多工具")
                            }
                    }
                click(mainOwner, "更多工具")
                val popup =
                    withContext(Dispatchers.Main) {
                        popupOwner().also {
                            assertEntries(it, tools - visible, tools)
                            assertTrue(visible.intersect(entries(it).toSet()).isEmpty())
                            assertEquals(
                                tools,
                                visible + entries(it).filter { label -> label in tools }.toSet(),
                            )
                            assertRendered(
                                it,
                                tools - visible + setOf("缩放", "旋转视图", "镜像视图", "适合窗口", "画布背景", "网格"),
                            )
                        }
                    }
                val selected =
                    Tool.entries.first {
                        it.label in tools - visible && it !in setOf(Tool.Brush, Tool.LineGenerator)
                    }
                click(popup, selected.label)
                withContext(Dispatchers.Main) {
                    assertEquals(selected, controller.tool)
                    assertEquals(1, scene.semanticsOwners.size)
                }
            }
        }
    }

    private suspend fun withController(block: suspend (StudioController) -> Unit) {
        val project =
            withContext(Dispatchers.Default) {
                NativeLoader.load()
                createNativeEngine(8, 8).let { engine ->
                    try {
                        engine.call(EngineOperation.SAVE)
                    } finally {
                        engine.close()
                    }
                }
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project.copyOf()

                override suspend fun save(bytes: ByteArray, png: Boolean) = false

                override suspend fun readPreferences() =
                    Json.encodeToString(Preferences()).encodeToByteArray()
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        suspend fun awaitState(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        assertNull(controller.error)
                        predicate() && !controller.busy
                    }
                ) delay(5)
            }
        try {
            awaitState { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            awaitState { controller.hasCanvas && controller.document.width == 8 }
            withContext(Dispatchers.Main) {
                assertTrue(controller.document.maxDrawingAssistants > 0)
                assertTrue(controller.document.maxGeneratedLines > 0)
            }
            block(controller)
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }

    private suspend fun withDock(
        controller: StudioController,
        appearance: WorkspaceAppearance,
        width: Int = 1000,
        height: Int = 1000,
        block: suspend Session.() -> Unit,
    ) =
        withScene(
            width,
            height,
            appearance,
            {
                Box(Modifier.fillMaxSize()) {
                    WorkspaceToolDock(
                        controller,
                        appearance,
                        onColors = {},
                        modifier =
                            Modifier.align(
                                when (appearance.toolDock) {
                                    ToolDockPosition.Left -> Alignment.CenterStart
                                    ToolDockPosition.Right -> Alignment.CenterEnd
                                    ToolDockPosition.Top -> Alignment.TopCenter
                                    ToolDockPosition.Bottom -> Alignment.BottomCenter
                                }
                            ),
                    )
                }
            },
            block,
        )

    private suspend fun withScene(
        width: Int,
        height: Int,
        appearance: WorkspaceAppearance = WorkspaceAppearance(),
        content: @Composable () -> Unit,
        block: suspend Session.() -> Unit,
    ) {
        val original = StudioTheme.appearance
        val originalWorkspace =
            WorkspaceAppearance(
                density = StudioTheme.interfaceDensity,
                reducedMotion = StudioMotion.reducedMotion,
            )
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(width, height) {
                    PodorTheme(
                        appearance = Appearance.Light,
                        workspaceAppearance = appearance.copy(reducedMotion = true),
                    ) {
                        Box(Modifier.fillMaxSize().background(StudioTheme.background)) { content() }
                    }
                }
            }
        val session = Session(scene, width, height)
        try {
            session.settle()
            withContext(Dispatchers.Main) { session.mainOwner = scene.semanticsOwners.single() }
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                val restore =
                    ImageComposeScene(1, 1) {
                        PodorTheme(
                            appearance = original,
                            workspaceAppearance = originalWorkspace,
                        ) {}
                    }
                try {
                    restore.render().close()
                } finally {
                    restore.close()
                }
            }
        }
    }

    private class Session(val scene: ImageComposeScene, val width: Int, val height: Int) {
        lateinit var mainOwner: SemanticsOwner
        private var frame = 1L

        private fun render() = scene.render(frame++ * 16_666_667L)

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        fun controls(root: SemanticsNode) =
            descendants(root)
                .filter {
                    it.config.contains(SemanticsActions.OnClick) && !it.boundsInWindow.isEmpty
                }
                .toList()

        fun label(node: SemanticsNode) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

        fun entries(owner: SemanticsOwner, unmerged: Boolean = false) =
            controls(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
                .mapNotNull { label(it)?.substringBefore(" ·") }

        fun popupOwner() =
            scene.semanticsOwners.single {
                it !== mainOwner && controls(it.rootSemanticsNode).isNotEmpty()
            }

        fun assertEntries(owner: SemanticsOwner, expected: Set<String>, domain: Set<String>) {
            for (unmerged in listOf(false, true)) {
                val labels = entries(owner, unmerged)
                domain.forEach {
                    assertEquals(
                        if (it in expected) 1 else 0,
                        labels.count { label -> label == it },
                        "$it (unmerged=$unmerged, scene=${width}x$height)",
                    )
                }
            }
        }

        private fun button(owner: SemanticsOwner, name: String) =
            controls(owner.rootSemanticsNode).single { label(it)?.substringBefore(" ·") == name }

        fun assertRendered(owner: SemanticsOwner, names: Set<String>) {
            val bounds = names.map { button(owner, it).boundsInWindow }
            bounds.forEachIndexed { index, rect ->
                assertTrue(
                    rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height,
                    "$rect in ${width}x$height",
                )
                bounds.drop(index + 1).forEach { assertFalse(rect.overlaps(it)) }
            }
            render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                bounds.forEach { rect ->
                    val x = rect.center.x.roundToInt()
                    val y = rect.center.y.roundToInt()
                    val background = pixels[x - 12, y - 12].toArgb()
                    val ink =
                        (0 until 400).count {
                            pixels[x - 10 + it % 20, y - 10 + it / 20].toArgb() != background
                        }
                    assertTrue(ink >= 8, "Missing rendered icon at $rect")
                }
            }
        }

        suspend fun settle() {
            repeat(20) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun click(owner: SemanticsOwner, name: String) {
            withContext(Dispatchers.Main) {
                val node = button(owner, name)
                assertFalse(node.config.contains(SemanticsProperties.Disabled), name)
                val point = node.boundsInWindow.center
                assertTrue(Rect(0f, 0f, width.toFloat(), height.toFloat()).contains(point), name)
                scene.sendPointerEvent(PointerEventType.Press, point)
                render().close()
                scene.sendPointerEvent(PointerEventType.Release, point)
                render().close()
                scene.sendPointerEvent(PointerEventType.Move, Offset(width - 1f, height - 1f))
            }
            settle()
        }
    }
}
