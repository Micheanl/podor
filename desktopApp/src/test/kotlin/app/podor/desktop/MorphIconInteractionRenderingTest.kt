package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.domain.Appearance
import app.podor.domain.WorkspaceAppearance
import app.podor.ui.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*

@OptIn(ExperimentalComposeUiApi::class)
class MorphIconInteractionRenderingTest {
    @Test
    fun fixedGlyphButtonInteractionsChangeTheActualOutlineAndSettleWithoutChangingClicks() =
        runBlocking {
            val originalAppearance = StudioTheme.appearance
            val reducedMotion = mutableStateOf(false)
            val selected = mutableStateOf(false)
            var clicks = 0
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(256, 128) {
                        PodorTheme(
                            appearance = Appearance.Light,
                            workspaceAppearance =
                                WorkspaceAppearance(reducedMotion = reducedMotion.value),
                        ) {
                            Row(Modifier.padding(16.dp)) {
                                ToolButton(Glyph.Export, "Export", plain = true) { clicks++ }
                                ToolButton(
                                    Glyph.Plus,
                                    "Add",
                                    selected = selected.value,
                                    plain = true,
                                ) {
                                    selected.value = !selected.value
                                }
                                ToolButton(Glyph.Trash, "Disabled", enabled = false, plain = true) {
                                    clicks++
                                }
                            }
                        }
                    }
                }
            val session = Session(scene)
            try {
                session.settle()
                for (label in listOf("Export", "Add")) {
                    val idle = session.outline(label)
                    assertTrue(idle.count { it > 0 } > 10)
                    session.pointer(PointerEventType.Move, label)
                    session.frames(4)
                    val first = session.outline(label)
                    session.frames(4)
                    val second = session.outline(label)
                    assertTrue(
                        differs(idle, first),
                        "$label hover did not change the glyph outline",
                    )
                    assertTrue(differs(first, second), "$label hover outline did not animate")
                    session.settle()
                    withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
                    val hovered = session.outline(label)
                    session.pointer(PointerEventType.Press, label)
                    session.frames(4)
                    assertTrue(
                        differs(hovered, session.outline(label)),
                        "$label press did not change the glyph outline",
                    )
                    session.pointer(PointerEventType.Release, label)
                    session.outside()
                    session.settle()
                    if (label == "Add") {
                        withContext(Dispatchers.Main) {
                            assertTrue(session.button(label).config[SemanticsProperties.Selected])
                            selected.value = false
                        }
                        session.frames(4)
                        val stateFirst = session.outline(label)
                        session.frames(4)
                        assertTrue(
                            differs(stateFirst, session.outline(label)),
                            "Selection did not animate the fixed glyph outline",
                        )
                        session.settle()
                    }
                    assertContentEquals(idle, session.outline(label))
                    withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
                }
                assertEquals(1, clicks)
                val disabled = session.outline("Disabled")
                session.pointer(PointerEventType.Move, "Disabled")
                session.pointer(PointerEventType.Press, "Disabled")
                session.frames(8)
                session.pointer(PointerEventType.Release, "Disabled")
                session.settle()
                assertContentEquals(disabled, session.outline("Disabled"))
                assertEquals(1, clicks)
                session.outside()
                withContext(Dispatchers.Main) { reducedMotion.value = true }
                session.settle()
                val reduced = session.outline("Export")
                session.pointer(PointerEventType.Move, "Export")
                session.frames(8)
                assertContentEquals(reduced, session.outline("Export"))
                session.pointer(PointerEventType.Press, "Export")
                session.frames(8)
                assertContentEquals(reduced, session.outline("Export"))
                session.pointer(PointerEventType.Release, "Export")
                session.outside()
                session.settle()
                assertEquals(2, clicks)
                withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
            } finally {
                withContext(Dispatchers.Main) {
                    scene.close()
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

    private fun differs(first: IntArray, second: IntArray): Boolean =
        first.indices.count { abs(first[it] - second[it]) >= 4 } >= 4

    private class Session(val scene: ImageComposeScene) {
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
                        descendants(node).any { child ->
                            child.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains(label) == true
                        }
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }

        suspend fun frames(count: Int) {
            repeat(count) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun settle() {
            frames(90)
            val settled =
                withTimeoutOrNull(5_000) {
                    var idleFrames = 0
                    while (idleFrames < 3) {
                        withContext(Dispatchers.Main) { render().close() }
                        delay(16)
                        val idle = withContext(Dispatchers.Main) { !scene.hasInvalidations() }
                        idleFrames = if (idle) idleFrames + 1 else 0
                    }
                    true
                }
            assertTrue(settled == true, "Scene did not reach three consecutive idle frames in 5s")
        }

        suspend fun pointer(type: PointerEventType, label: String) {
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, button(label).boundsInWindow.center)
                render().close()
            }
        }

        suspend fun outside() {
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(250f, 120f))
                render().close()
            }
        }

        suspend fun outline(label: String): IntArray =
            withContext(Dispatchers.Main) {
                val center = button(label).boundsInWindow.center
                val left = center.x.roundToInt() - 12
                val top = center.y.roundToInt() - 12
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    IntArray(24 * 24) { index ->
                        (pixels[left + index % 24, top + index / 24].alpha * 255).roundToInt()
                    }
                }
            }
    }
}
