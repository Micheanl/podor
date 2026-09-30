package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.podor.domain.Appearance
import app.podor.domain.WorkspaceAppearance
import app.podor.ui.*
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class StudioSwitchRenderingTest {
    @Test
    fun pointerAndKeyboardKeepSwitchStatesAndColorsWithoutInteractionHalos() = runBlocking {
        withContext(Dispatchers.Main) {
            val original = StudioTheme.appearance
            val originalWorkspace =
                WorkspaceAppearance(
                    density = StudioTheme.interfaceDensity,
                    reducedMotion = StudioMotion.reducedMotion,
                )
            try {
                for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                    for (scale in listOf(1f, 2f)) {
                        var checked by mutableStateOf(false)
                        var clicks = 0
                        var disabledClicks = 0
                        var colors: SwitchColors? = null
                        val scene =
                            ImageComposeScene(320, 400) {
                                PodorTheme(
                                    appearance = appearance,
                                    workspaceAppearance = WorkspaceAppearance(scale = scale),
                                ) {
                                    val currentColors = SwitchDefaults.colors()
                                    SideEffect { colors = currentColors }
                                    Column(
                                        Modifier.fillMaxSize()
                                            .background(StudioTheme.panel)
                                            .padding(top = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(36.dp),
                                    ) {
                                        StudioSwitch(
                                            checked,
                                            {
                                                checked = it
                                                clicks++
                                            },
                                            Modifier.testTag("interactive-switch"),
                                        )
                                        StudioSwitch(
                                            false,
                                            { disabledClicks++ },
                                            Modifier.testTag("disabled-switch"),
                                            enabled = false,
                                        )
                                    }
                                }
                            }
                        try {
                            var frame = 1L
                            fun render() = scene.render(frame++ * 16_666_667L)
                            suspend fun settle() {
                                repeat(60) {
                                    render().close()
                                    delay(2)
                                }
                            }
                            fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
                                sequence {
                                    yield(node)
                                    node.children.forEach { yieldAll(descendants(it)) }
                                }
                            fun node(tag: String) =
                                scene.semanticsOwners
                                    .asSequence()
                                    .flatMap { descendants(it.rootSemanticsNode) }
                                    .single {
                                        it.config.getOrNull(SemanticsProperties.TestTag) == tag
                                    }
                            fun pixels(): IntArray =
                                render().use {
                                    val map = it.toComposeImageBitmap().toPixelMap()
                                    IntArray(320 * 400) { index ->
                                        map[index % 320, index / 320].toArgb()
                                    }
                                }
                            settle()
                            val density = Density(scale)
                            val bounds = node("interactive-switch").boundsInWindow
                            val track =
                                with(density) {
                                    Rect(
                                        bounds.center - Offset(26.dp.toPx(), 16.dp.toPx()),
                                        bounds.center + Offset(26.dp.toPx(), 16.dp.toPx()),
                                    )
                                }
                            fun assertNoHalo() {
                                val image = pixels()
                                val background = StudioTheme.panel.toArgb()
                                val margin = with(density) { 24.dp.roundToPx() }
                                for (y in
                                    track.top.roundToInt() - margin until
                                        track.bottom.roundToInt() + margin) {
                                    for (x in
                                        track.left.roundToInt() - margin until
                                            track.right.roundToInt() + margin) {
                                        if (!track.contains(Offset(x + 0.5f, y + 0.5f)))
                                            assertEquals(
                                                background,
                                                image[y * 320 + x],
                                                "$appearance/$scale halo at $x,$y",
                                            )
                                    }
                                }
                            }
                            fun assertState(value: Boolean) {
                                val config = node("interactive-switch").config
                                assertEquals(Role.Switch, config[SemanticsProperties.Role])
                                assertEquals(
                                    if (value) ToggleableState.On else ToggleableState.Off,
                                    config[SemanticsProperties.ToggleableState],
                                )
                                assertTrue(config.contains(SemanticsActions.OnClick))
                                val image = pixels()
                                val resolved = assertNotNull(colors)
                                val inset = with(density) { 16.dp.toPx() }
                                val thumb = if (value) track.right - inset else track.left + inset
                                val uncovered =
                                    if (value) track.left + inset else track.right - inset
                                val y = track.center.y.roundToInt()
                                assertEquals(
                                    (if (value) resolved.checkedThumbColor
                                        else resolved.uncheckedThumbColor)
                                        .toArgb(),
                                    image[y * 320 + thumb.roundToInt()],
                                )
                                assertEquals(
                                    (if (value) resolved.checkedTrackColor
                                        else resolved.uncheckedTrackColor)
                                        .toArgb(),
                                    image[y * 320 + uncovered.roundToInt()],
                                )
                                assertEquals(value, checked)
                                assertNoHalo()
                            }
                            fun key(key: Key, type: KeyEventType) =
                                scene.sendKeyEvent(KeyEvent(key, type))
                            assertState(false)
                            key(Key.Tab, KeyEventType.KeyDown)
                            key(Key.Tab, KeyEventType.KeyUp)
                            settle()
                            assertTrue(
                                node("interactive-switch").config[SemanticsProperties.Focused]
                            )
                            assertNoHalo()
                            scene.sendPointerEvent(PointerEventType.Move, bounds.center)
                            settle()
                            assertNoHalo()
                            scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                            settle()
                            assertEquals(0, clicks)
                            assertNoHalo()
                            scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                            settle()
                            assertState(true)
                            assertEquals(1, clicks)
                            assertTrue(
                                node("interactive-switch").config[SemanticsProperties.Focused]
                            )
                            key(Key.Spacebar, KeyEventType.KeyDown)
                            settle()
                            assertNoHalo()
                            key(Key.Spacebar, KeyEventType.KeyUp)
                            settle()
                            assertState(false)
                            assertEquals(2, clicks)
                            val disabled = node("disabled-switch")
                            assertTrue(disabled.config.contains(SemanticsProperties.Disabled))
                            val beforeDisabled = pixels()
                            scene.sendPointerEvent(
                                PointerEventType.Press,
                                disabled.boundsInWindow.center,
                            )
                            scene.sendPointerEvent(
                                PointerEventType.Release,
                                disabled.boundsInWindow.center,
                            )
                            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                            settle()
                            assertEquals(0, disabledClicks)
                            assertContentEquals(beforeDisabled, pixels())
                            assertFalse(scene.hasInvalidations())
                        } finally {
                            scene.close()
                        }
                    }
                }
            } finally {
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
}
