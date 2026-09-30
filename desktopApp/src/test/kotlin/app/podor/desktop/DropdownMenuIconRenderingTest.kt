package app.podor.desktop

import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.podor.domain.Appearance
import app.podor.domain.WorkspaceAppearance
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
class DropdownMenuIconRenderingTest {
    @Test
    fun menuTextHoverAndPressMorphBothIconsWhileDisabledRowsKeepCanonicalPathsAndClicks() =
        runBlocking {
            val originalAppearance = StudioTheme.appearance
            val reducedMotion = mutableStateOf(false)
            val interaction = MutableInteractionSource()
            val events = mutableListOf<Interaction>()
            val collector =
                launch(Dispatchers.Main) { interaction.interactions.collect { events += it } }
            var clicks = 0
            val scene =
                withContext(Dispatchers.Main) {
                    ImageComposeScene(400, 260) {
                        PodorTheme(
                            appearance = Appearance.Light,
                            workspaceAppearance =
                                WorkspaceAppearance(reducedMotion = reducedMotion.value),
                        ) {
                            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                Box(Modifier.padding(16.dp)) {
                                    Text("Actions")
                                    DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = {},
                                        modifier = Modifier.width(320.dp),
                                        containerColor = Color.Transparent,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                    ) {
                                        StudioDropdownMenuItem(
                                            text = { Text("Enabled row") },
                                            onClick = { clicks++ },
                                            interactionSource = interaction,
                                            leadingIcon = {
                                                Box(
                                                    Modifier.semantics {
                                                        contentDescription = "Enabled leading"
                                                    }
                                                ) {
                                                    StudioIcon(Glyph.Export)
                                                }
                                            },
                                            trailingIcon = {
                                                Box(
                                                    Modifier.semantics {
                                                        contentDescription = "Enabled trailing"
                                                    }
                                                ) {
                                                    StudioIcon(Glyph.Plus)
                                                }
                                            },
                                        )
                                        StudioDropdownMenuItem(
                                            text = { Text("Disabled row") },
                                            onClick = { clicks++ },
                                            enabled = false,
                                            leadingIcon = {
                                                Box(
                                                    Modifier.semantics {
                                                        contentDescription = "Disabled leading"
                                                    }
                                                ) {
                                                    StudioIcon(Glyph.Plus, selected = true)
                                                }
                                            },
                                            trailingIcon = {
                                                Box(
                                                    Modifier.semantics {
                                                        contentDescription = "Disabled trailing"
                                                    }
                                                ) {
                                                    StudioIcon(Glyph.Eye, selected = true)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            val session = Session(scene)
            try {
                session.settle()
                session.screenshot("dropdown-menu-icons-rest.png")
                val leading = session.outline("Enabled leading")
                val trailing = session.outline("Enabled trailing")
                val disabledLeading = session.outline("Disabled leading")
                val disabledTrailing = session.outline("Disabled trailing")
                assertTrue(leading.count { it > 0 } > 10, "Export has no visible outline")
                assertContentEquals(trailing, disabledLeading)
                val rowPoint =
                    withContext(Dispatchers.Main) {
                        assertFalse(
                            session.row("Enabled row").config.contains(SemanticsProperties.Disabled)
                        )
                        assertTrue(
                            session
                                .row("Disabled row")
                                .config
                                .contains(SemanticsProperties.Disabled)
                        )
                        session.row("Enabled row").boundsInWindow.center.also {
                            assertFalse(
                                session.marker("Enabled leading").boundsInWindow.contains(it)
                            )
                            assertFalse(
                                session.marker("Enabled trailing").boundsInWindow.contains(it)
                            )
                        }
                    }
                session.pointer(PointerEventType.Move, rowPoint)
                session.frames(4)
                val hoverLeading = session.outline("Enabled leading")
                val hoverTrailing = session.outline("Enabled trailing")
                session.frames(4)
                assertTrue(differs(leading, hoverLeading))
                assertTrue(differs(trailing, hoverTrailing))
                assertTrue(differs(hoverLeading, session.outline("Enabled leading")))
                assertTrue(differs(hoverTrailing, session.outline("Enabled trailing")))
                session.settle()
                withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
                val heldLeading = session.outline("Enabled leading")
                val heldTrailing = session.outline("Enabled trailing")
                session.pointer(PointerEventType.Press, rowPoint)
                session.frames(4)
                assertTrue(differs(heldLeading, session.outline("Enabled leading")))
                assertTrue(differs(heldTrailing, session.outline("Enabled trailing")))
                session.pointer(PointerEventType.Release, rowPoint)
                session.outside()
                session.settle()
                assertEquals(1, clicks)
                assertContentEquals(leading, session.outline("Enabled leading"))
                assertContentEquals(trailing, session.outline("Enabled trailing"))
                withContext(Dispatchers.Main) {
                    assertEquals(1, events.count { it is HoverInteraction.Enter })
                    assertEquals(1, events.count { it is HoverInteraction.Exit })
                    assertEquals(1, events.count { it is PressInteraction.Press })
                    assertEquals(1, events.count { it is PressInteraction.Release })
                }
                for (point in
                    listOf(
                        withContext(Dispatchers.Main) {
                            session.row("Disabled row").boundsInWindow.center
                        },
                        withContext(Dispatchers.Main) {
                            session.marker("Disabled leading").boundsInWindow.center
                        },
                    )) {
                    session.pointer(PointerEventType.Move, point)
                    session.frames(8)
                    session.pointer(PointerEventType.Press, point)
                    session.frames(8)
                    session.pointer(PointerEventType.Release, point)
                    session.settle()
                    assertContentEquals(disabledLeading, session.outline("Disabled leading"))
                    assertContentEquals(disabledTrailing, session.outline("Disabled trailing"))
                    assertEquals(1, clicks)
                    withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
                }
                session.outside()
                withContext(Dispatchers.Main) { reducedMotion.value = true }
                session.settle()
                session.pointer(PointerEventType.Move, rowPoint)
                session.frames(8)
                assertContentEquals(leading, session.outline("Enabled leading"))
                assertContentEquals(trailing, session.outline("Enabled trailing"))
                session.pointer(PointerEventType.Press, rowPoint)
                session.frames(8)
                assertContentEquals(leading, session.outline("Enabled leading"))
                assertContentEquals(trailing, session.outline("Enabled trailing"))
                session.pointer(PointerEventType.Release, rowPoint)
                session.outside()
                session.settle()
                assertEquals(2, clicks)
                withContext(Dispatchers.Main) { assertFalse(scene.hasInvalidations()) }
            } finally {
                collector.cancelAndJoin()
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

        private fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        fun marker(label: String): SemanticsNode =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.unmergedRootSemanticsNode) }
                .filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) ==
                        true
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }

        fun row(label: String): SemanticsNode =
            nodes()
                .filter { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        descendants(node).any {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                text.text == label
                            } == true
                        }
                }
                .minBy { it.boundsInWindow.width * it.boundsInWindow.height }

        suspend fun pointer(type: PointerEventType, point: Offset) =
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }

        suspend fun frames(count: Int) {
            repeat(count) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun settle() = frames(90)

        suspend fun outside() = pointer(PointerEventType.Move, Offset(390f, 250f))

        suspend fun screenshot(name: String) {
            withContext(Dispatchers.Main) { render() }
                .use { image ->
                    withContext(Dispatchers.IO) {
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                            val directory = Path.of("build", "reports", "screenshots")
                            Files.createDirectories(directory)
                            Files.write(directory.resolve(name), data.bytes)
                        }
                    }
                }
        }

        suspend fun outline(label: String): IntArray =
            withContext(Dispatchers.Main) {
                val bounds = marker(label).boundsInWindow
                assertEquals(18f, bounds.width, "$label bounds: $bounds")
                assertEquals(18f, bounds.height, "$label bounds: $bounds")
                val left = bounds.center.x.roundToInt() - 12
                val top = bounds.center.y.roundToInt() - 12
                render().use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    IntArray(24 * 24) { index ->
                        (pixels[left + index % 24, top + index / 24].alpha * 255).roundToInt()
                    }
                }
            }
    }
}
