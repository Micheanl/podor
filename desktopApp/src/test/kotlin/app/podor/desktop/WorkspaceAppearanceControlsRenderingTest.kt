package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import app.podor.domain.*
import app.podor.ui.PodorTheme
import app.podor.ui.StudioTheme
import app.podor.ui.WorkspaceAppearanceControls
import kotlin.test.*
import kotlinx.coroutines.*

@OptIn(ExperimentalComposeUiApi::class)
class WorkspaceAppearanceControlsRenderingTest {
    @Test
    fun narrowAppearanceControlsUseRealPointersAndPreserveOtherChoicesInBothThemes() =
        runBlocking<Unit> {
            val originalAppearance = StudioTheme.appearance
            try {
                for (theme in listOf(Appearance.Light, Appearance.Dark)) {
                    val value = mutableStateOf(WorkspaceAppearance())
                    val enabled = mutableStateOf(true)
                    val changes = mutableListOf<WorkspaceAppearance>()
                    val scene =
                        withContext(Dispatchers.Main) {
                            ImageComposeScene(400, 900) {
                                PodorTheme(language = Language.Chinese, appearance = theme) {
                                    Surface(color = StudioTheme.panel) {
                                        Column(
                                            Modifier.fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                                .padding(StudioTheme.brushSettingsGap)
                                        ) {
                                            WorkspaceAppearanceControls(
                                                value.value,
                                                {
                                                    assertTrue(it.valid())
                                                    changes += it
                                                    value.value = it
                                                },
                                                enabled.value,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    val session = ControlsScene(scene)
                    try {
                        session.settle()
                        withContext(Dispatchers.Main) {
                            session.render().use { image ->
                                val color = image.toComposeImageBitmap().toPixelMap()[2, 2]
                                if (theme == Appearance.Light)
                                    assertTrue(
                                        color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f
                                    )
                                else
                                    assertTrue(
                                        color.red < 0.25f &&
                                            color.green < 0.25f &&
                                            color.blue < 0.25f
                                    )
                            }
                            val positions =
                                listOf("工具栏 · 左侧", "工具栏 · 右侧", "工具栏 · 顶部", "工具栏 · 底部").map {
                                    session.button(it).boundsInWindow
                                }
                            positions.forEach {
                                assertTrue(
                                    it.left >= 0f &&
                                        it.top >= 0f &&
                                        it.right <= 400f &&
                                        it.bottom <= 900f
                                )
                            }
                            positions.forEachIndexed { index, bounds ->
                                positions.drop(index + 1).forEach { other ->
                                    assertFalse(bounds.overlaps(other), "$bounds overlaps $other")
                                }
                            }
                        }
                        session.click("工具栏 · 顶部")
                        session.click("面板 · 左侧")
                        session.click("紧凑")
                        session.click("放大界面")
                        session.click("减少动态效果")
                        session.click("下移工具 · 画笔")
                        session.click("隐藏工具 · 画笔")
                        val expected =
                            WorkspaceAppearance(
                                toolDock = ToolDockPosition.Top,
                                toolOrder =
                                    listOf(
                                        "Eraser",
                                        "Brush",
                                        "Select",
                                        "Fill",
                                        "Picker",
                                        "Hand",
                                        "MoveLayer",
                                        "TransformLayer",
                                        "Gradient",
                                        "Smudge",
                                        "LassoFill",
                                        "Vector",
                                        "Assistant",
                                        "LineGenerator",
                                    ),
                                hiddenTools = setOf("Brush"),
                                inspectorPosition = InspectorPosition.Left,
                                density = InterfaceDensity.Compact,
                                scale = 1.25f,
                                reducedMotion = true,
                            )
                        withContext(Dispatchers.Main) {
                            assertEquals(expected, value.value)
                            assertEquals(7, changes.size)
                            assertTrue(
                                session
                                    .button("工具栏 · 顶部")
                                    .config
                                    .getOrNull(SemanticsProperties.Selected) == true
                            )
                            assertFalse(
                                session
                                    .button("工具栏 · 左侧")
                                    .config
                                    .getOrNull(SemanticsProperties.Selected) == true
                            )
                            assertFalse(
                                session
                                    .button("显示工具 · 画笔")
                                    .config
                                    .getOrNull(SemanticsProperties.Selected) == true
                            )
                            enabled.value = false
                        }
                        session.settle()
                        withContext(Dispatchers.Main) {
                            assertTrue(
                                session.button("放大界面").config.contains(SemanticsProperties.Disabled)
                            )
                        }
                        session.click("放大界面", disabled = true)
                        session.click("工具栏 · 右侧", disabled = true)
                        withContext(Dispatchers.Main) {
                            assertEquals(expected, value.value)
                            assertEquals(7, changes.size)
                        }
                    } finally {
                        withContext(Dispatchers.Main) { scene.close() }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    val restore =
                        ImageComposeScene(1, 1) { PodorTheme(appearance = originalAppearance) {} }
                    try {
                        restore.render().close()
                    } finally {
                        restore.close()
                    }
                }
            }
        }

    private class ControlsScene(val scene: ImageComposeScene) {
        private var frame = 1L

        fun render() = scene.render(frame++ * 16_666_667L)

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        fun button(label: String): SemanticsNode =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .filter { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        !node.boundsInWindow.isEmpty &&
                        descendants(node).any {
                            it.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains(label) == true ||
                                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                    text.text == label
                                } == true
                        }
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }

        suspend fun settle() {
            repeat(20) {
                withContext(Dispatchers.Main) { render().close() }
                yield()
            }
        }

        suspend fun click(label: String, disabled: Boolean = false) {
            withContext(Dispatchers.Main) {
                val node = button(label)
                assertEquals(disabled, node.config.contains(SemanticsProperties.Disabled), label)
                val bounds = node.boundsInWindow
                assertTrue(Rect(0f, 0f, 400f, 900f).contains(bounds.center), label)
                scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                render().close()
                scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                render().close()
            }
            settle()
        }
    }
}
