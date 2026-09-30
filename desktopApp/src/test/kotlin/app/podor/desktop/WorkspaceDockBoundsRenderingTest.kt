package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class WorkspaceDockBoundsRenderingTest {
    @Test
    fun everyDockPreservesFullControlsAndPaddingAtFractionalAndScaledDensities() = runBlocking {
        val original = StudioTheme.appearance
        val originalWorkspace =
            WorkspaceAppearance(
                density = StudioTheme.interfaceDensity,
                reducedMotion = StudioMotion.reducedMotion,
            )
        try {
            withController { controller ->
                for ((baseDensity, scale) in
                    listOf(1.25f to 0.75f, 1.25f to 1f, 1f to 1.5f, 1f to 2f)) {
                    for (density in InterfaceDensity.entries) {
                        for (dock in ToolDockPosition.entries) {
                            val appearance =
                                WorkspaceAppearance(
                                    toolDock = dock,
                                    density = density,
                                    scale = scale,
                                    reducedMotion = true,
                                )
                            val metrics =
                                assertDock(controller, appearance, baseDensity, 1600, false)
                            assertDock(
                                controller,
                                appearance,
                                baseDensity,
                                metrics.control * 5 + metrics.padding * 2 - 1,
                                true,
                            )
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
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

    private data class Metrics(val control: Int, val padding: Int)

    private suspend fun assertDock(
        controller: StudioController,
        appearance: WorkspaceAppearance,
        baseDensity: Float,
        length: Int,
        overflow: Boolean,
    ): Metrics =
        withContext(Dispatchers.Main) {
            val vertical =
                appearance.toolDock in listOf(ToolDockPosition.Left, ToolDockPosition.Right)
            val width = if (vertical) 320 else length
            val height = if (vertical) length else 320
            var actualDensity: Density? = null
            val scene =
                ImageComposeScene(width, height, density = Density(baseDensity)) {
                    PodorTheme(
                        appearance = Appearance.Light,
                        workspaceAppearance = appearance,
                    ) {
                        val density = LocalDensity.current
                        SideEffect { actualDensity = density }
                        Box(Modifier.fillMaxSize().background(StudioTheme.background)) {
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
                                        )
                                        .testTag("workspace-tool-dock"),
                            )
                        }
                    }
                }
            try {
                repeat(4) { scene.render((it + 1) * 16_666_667L).close() }
                val effective = assertNotNull(actualDensity)
                val context =
                    "${appearance.toolDock}/${appearance.density}/${effective.density}, ${width}x$height"
                assertEquals(baseDensity * appearance.scale, effective.density, 0.0001f, context)
                assertEquals(appearance.density, StudioTheme.interfaceDensity, context)
                assertEquals(6.dp, StudioTheme.workspacePadding)
                val metrics =
                    with(effective) {
                        Metrics(
                            StudioTheme.controlSize.roundToPx(),
                            StudioTheme.workspacePadding.roundToPx(),
                        )
                    }
                val root = scene.semanticsOwners.single().rootSemanticsNode
                val nodes = descendants(root).toList()
                val dock =
                    nodes
                        .single {
                            it.config.getOrNull(SemanticsProperties.TestTag) ==
                                "workspace-tool-dock"
                        }
                        .boundsInWindow
                val cross = (metrics.control + metrics.padding * 2).toFloat()
                val expectedDock =
                    when (appearance.toolDock) {
                        ToolDockPosition.Left -> Rect(0f, 0f, cross, height.toFloat())
                        ToolDockPosition.Right ->
                            Rect(width - cross, 0f, width.toFloat(), height.toFloat())
                        ToolDockPosition.Top -> Rect(0f, 0f, width.toFloat(), cross)
                        ToolDockPosition.Bottom ->
                            Rect(0f, height - cross, width.toFloat(), height.toFloat())
                    }
                assertEquals(expectedDock, dock, context)
                val controls = nodes.filter { it.config.contains(SemanticsActions.OnClick) }
                val buttons = controls.filter {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button
                }
                val tools = appearance.orderedTools().filter { it != Tool.Vector }.map { it.label }
                val view =
                    listOf("缩放", "旋转视图", "镜像视图", "适合窗口", "画布背景", "网格") +
                        (if (controller.clipboardAvailable) listOf("剪贴板") else emptyList()) +
                        "参考图"
                val slots = (length - metrics.padding * 2) / metrics.control
                val viewCount = if (slots >= tools.size + view.size + 1) view.size else 0
                val more = viewCount == 0 || tools.size + 1 + viewCount > slots
                val visible =
                    tools.take((slots - 1 - viewCount - if (more) 1 else 0).coerceAtLeast(0))
                assertEquals(
                    visible +
                        (if (more) listOf("更多工具") else emptyList()) +
                        (if (viewCount > 0) view else emptyList()),
                    buttons.map { label(it)?.substringBefore(" ·") },
                    context,
                )
                buttons.forEach {
                    assertEquals(
                        metrics.control.toFloat(),
                        it.boundsInWindow.width,
                        0.01f,
                        "$context/${label(it)}",
                    )
                    assertEquals(
                        metrics.control.toFloat(),
                        it.boundsInWindow.height,
                        0.01f,
                        "$context/${label(it)}",
                    )
                }
                val color = controls.single {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
                }
                val colorSize =
                    minOf(metrics.control, with(effective) { 36.dp.roundToPx() }).toFloat()
                assertEquals(colorSize, color.boundsInWindow.width, 0.01f, context)
                assertEquals(colorSize, color.boundsInWindow.height, 0.01f, context)
                assertEquals(buttons.size + 1, controls.size, context)
                val first = buttons.first().boundsInWindow
                assertEquals(Tool.Brush.label, label(buttons.first()), context)
                assertEquals(dock.left + metrics.padding, first.left, 0.01f, context)
                assertEquals(dock.top + metrics.padding, first.top, 0.01f, context)
                val content =
                    Rect(
                        dock.left + metrics.padding,
                        dock.top + metrics.padding,
                        dock.right - metrics.padding,
                        dock.bottom - metrics.padding,
                    )
                val bounds = controls.map { it.boundsInWindow }
                bounds.forEachIndexed { index, rect ->
                    assertTrue(
                        rect.left >= content.left &&
                            rect.top >= content.top &&
                            rect.right <= content.right &&
                            rect.bottom <= content.bottom,
                        "$context/$rect in $content",
                    )
                    bounds.drop(index + 1).forEach { assertFalse(rect.overlaps(it), context) }
                }
                scene.render(5 * 16_666_667L).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val radius = with(effective) { StudioTheme.iconSize.roundToPx() } / 2
                    bounds.forEach { rect ->
                        val x = rect.center.x.roundToInt()
                        val y = rect.center.y.roundToInt()
                        val background =
                            pixels[rect.left.roundToInt() + 1, rect.top.roundToInt() + 1].toArgb()
                        val ink =
                            (-radius until radius).sumOf { dy ->
                                (-radius until radius).count { dx ->
                                    pixels[x + dx, y + dy].toArgb() != background
                                }
                            }
                        assertTrue(ink >= 8, "Missing rendered control at $context/$rect")
                    }
                }
                metrics
            } finally {
                scene.close()
            }
        }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun label(node: SemanticsNode) =
        node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.singleOrNull()
            ?.substringBefore(" ·")

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
}
