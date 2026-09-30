package app.podor.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import app.podor.domain.HsvColor
import app.podor.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class InteractionRenderingTest {
    @Test
    fun colorWheelSeparatesHueAndPlaneGesturesAndIgnoresCorners() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val color = mutableStateOf(HsvColor(0f, 0.5f, 0.8f))
                val scene =
                    ImageComposeScene(300, 300) {
                        PodorTheme {
                            ColorWheel(color.value, { color.value = it }, Modifier.fillMaxSize())
                        }
                    }
                var frame = 0L
                fun render() {
                    scene.render(frame++ * 16_666_667L).close()
                }
                fun input(type: PointerEventType, x: Float, y: Float) {
                    scene.sendPointerEvent(type, Offset(x, y))
                    render()
                }
                try {
                    render()
                    input(PointerEventType.Press, 150f, 286.5f)
                    assertEquals(90f, color.value.hue, 0.01f)
                    input(PointerEventType.Move, 13.5f, 150f)
                    assertEquals(180f, color.value.hue, 0.01f)
                    input(PointerEventType.Release, 13.5f, 150f)
                    assertEquals(0.5f, color.value.saturation)
                    assertEquals(0.8f, color.value.brightness)
                    input(PointerEventType.Press, 150f, 150f)
                    assertEquals(0.5f, color.value.saturation, 0.01f)
                    assertEquals(0.5f, color.value.brightness, 0.01f)
                    input(PointerEventType.Move, 290f, 290f)
                    input(PointerEventType.Release, 290f, 290f)
                    assertEquals(HsvColor(180f, 1f, 0f), color.value)
                    input(PointerEventType.Press, 2f, 2f)
                    input(PointerEventType.Release, 2f, 2f)
                    assertEquals(HsvColor(180f, 1f, 0f), color.value)
                    repeat(3) { render() }
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                }
            }
        }

    @Test
    fun interruptedPagesStayClippedDiscardOldInputAndBecomeIdle() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                val page = mutableIntStateOf(0)
                val mounted = mutableSetOf<Int>()
                val clicked = mutableListOf<Int>()
                val surround = Color(0xFF452D50)
                val scene =
                    ImageComposeScene(360, 260) {
                        Box(Modifier.fillMaxSize().background(surround)) {
                            PageTransition(
                                page.intValue,
                                Modifier.offset(30.dp, 30.dp).size(300.dp, 200.dp),
                            ) { active ->
                                DisposableEffect(active) {
                                    mounted.add(active)
                                    onDispose { mounted.remove(active) }
                                }
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(if (active == 0) Color.Red else Color.Blue)
                                ) {
                                    Box(
                                        Modifier.offset(y = if (active == 0) 140.dp else 0.dp)
                                            .size(60.dp)
                                            .clickable { clicked.add(active) }
                                    )
                                }
                            }
                        }
                    }
                try {
                    for (frame in 0..85) {
                        if (frame == 3) page.intValue = 1
                        if (frame == 6) {
                            scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 195f))
                            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 195f))
                        }
                        if (frame == 9) page.intValue = 2
                        if (frame == 12) page.intValue = 0
                        if (frame == 15) page.intValue = 1
                        scene.render(frame * 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            for (x in 0 until 360) {
                                assertEquals(surround, pixels[x, 29])
                                assertEquals(surround, pixels[x, 230])
                            }
                            for (y in 0 until 260) {
                                assertEquals(surround, pixels[29, y])
                                assertEquals(surround, pixels[330, y])
                            }
                        }
                        yield()
                    }
                    assertTrue(clicked.isEmpty(), "outgoing page accepted input: $clicked")
                    assertEquals(setOf(1), mounted)
                    assertFalse(
                        scene.hasInvalidations(),
                        "page motion kept rendering after completion",
                    )
                    scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 60f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 60f))
                    assertEquals(listOf(1), clicked)
                } finally {
                    scene.close()
                }
            }
        }

    @Test
    fun buttonsKeepTheirHitAreaWhilePressedAndRespectDisabledState() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                var clicks = 0
                val enabled = mutableStateOf(true)
                val scene =
                    ImageComposeScene(320, 110) {
                        PodorTheme {
                            Box(
                                Modifier.fillMaxSize().background(StudioTheme.panel).padding(24.dp)
                            ) {
                                ActionButton(
                                    "导出",
                                    { clicks++ },
                                    Modifier.width(160.dp),
                                    enabled.value,
                                    Glyph.Export,
                                )
                            }
                        }
                    }
                try {
                    val output = Path.of("build", "reports", "screenshots")
                    Files.createDirectories(output)
                    for (frame in 0..75) {
                        if (frame == 3)
                            scene.sendPointerEvent(PointerEventType.Move, Offset(104f, 46f))
                        if (frame == 16)
                            scene.sendPointerEvent(PointerEventType.Press, Offset(26f, 46f))
                        if (frame == 25)
                            scene.sendPointerEvent(PointerEventType.Release, Offset(26f, 46f))
                        if (frame == 45) enabled.value = false
                        if (frame == 65) {
                            scene.sendPointerEvent(PointerEventType.Press, Offset(104f, 46f))
                            scene.sendPointerEvent(PointerEventType.Release, Offset(104f, 46f))
                        }
                        scene.render(frame * 16_666_667L).use { image ->
                            if (frame in listOf(0, 15, 23, 74))
                                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                    Files.write(output.resolve("button-$frame.png"), it.bytes)
                                }
                        }
                        yield()
                    }
                    assertEquals(1, clicks)
                    assertFalse(scene.hasInvalidations())
                } finally {
                    scene.close()
                }
            }
        }
}
