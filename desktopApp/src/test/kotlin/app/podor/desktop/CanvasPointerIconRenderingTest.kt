package app.podor.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.CanvasBackground
import app.podor.domain.Preferences
import app.podor.domain.WorkspaceAppearance
import app.podor.engine.EngineOperation
import app.podor.engine.createNativeEngine
import app.podor.presentation.StudioController
import app.podor.ui.CanvasWorkspace
import app.podor.ui.PodorTheme
import app.podor.ui.StudioMotion
import app.podor.ui.StudioTheme
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeFalse

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class CanvasPointerIconRenderingTest {
    @Test
    fun mouseHoverChangesTheSystemCursorServiceAndStylusHoverDoesNot() = runBlocking {
        assumeFalse(
            "Native custom cursors require a desktop Toolkit",
            GraphicsEnvironment.isHeadless(),
        )
        val project =
            withContext(Dispatchers.Default) {
                NativeLoader.load()
                val engine = createNativeEngine(128, 128)
                try {
                    engine.call(
                        EngineOperation.COMMAND,
                        """{"type":"fill","x":0,"y":0,"color":[255,255,255,255],"tolerance":0}"""
                            .encodeToByteArray(),
                    )
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            }
        val files =
            object : ProjectFiles {
                override suspend fun open() = project

                override suspend fun save(bytes: ByteArray, png: Boolean) = false

                override suspend fun readPreferences() =
                    Json.encodeToString(Preferences(canvasBackground = CanvasBackground.White))
                        .encodeToByteArray()
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
            awaitState {
                controller.hasCanvas &&
                    controller.document.width == 128 &&
                    controller.frame.tiles.size == 1
            }
            withContext(Dispatchers.Main) {
                val updates = mutableListOf<PointerIcon>()
                val scale = mutableStateOf(1f)
                var canvasBounds = Rect.Zero
                val appearance = StudioTheme.appearance
                val density = StudioTheme.interfaceDensity
                val reducedMotion = StudioMotion.reducedMotion
                val recomposer = FrameRecomposer(Dispatchers.Unconfined)
                val surface = Surface.makeRasterN32Premul(280, 280)
                val scene =
                    CanvasLayersComposeScene(
                        frameRecomposer = recomposer,
                        size = IntSize(280, 280),
                        platformContext =
                            object : PlatformContext.Empty() {
                                override fun setPointerIcon(pointerIcon: PointerIcon) {
                                    updates += pointerIcon
                                }
                            },
                    )
                var time = 0L
                fun frame() {
                    time += 16_666_667L
                    recomposer.performFrame(time)
                    scene.measureAndLayout()
                    surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
                    scene.draw(surface.canvas.asComposeCanvas())
                }
                fun pointer(
                    event: PointerEventType,
                    x: Float,
                    type: PointerType = PointerType.Mouse,
                ) {
                    scene.sendPointerEvent(event, Offset(x, 64f), type = type)
                }
                fun nativePixels(): ByteArray {
                    val tile = controller.frame.tiles.values.single()
                    val argb = IntArray(tile.size * tile.size).also { tile.image.readPixels(it) }
                    return ByteArray(argb.size * 4) { index ->
                        val shift =
                            when (index % 4) {
                                0 -> 16
                                1 -> 8
                                2 -> 0
                                else -> 24
                            }
                        (argb[index / 4] ushr shift).toByte()
                    }
                }
                fun nativeCursor(icon: PointerIcon): Cursor =
                    icon.javaClass.getMethod("getCursor").apply { isAccessible = true }.invoke(icon)
                        as Cursor
                fun scenePixels(): IntArray {
                    surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
                    scene.draw(surface.canvas.asComposeCanvas())
                    return surface.makeImageSnapshot().use { snapshot ->
                        IntArray(280 * 280).also { snapshot.toComposeImageBitmap().readPixels(it) }
                    }
                }
                try {
                    scene.setContent {
                        PodorTheme(
                            workspaceAppearance =
                                WorkspaceAppearance(scale = scale.value, reducedMotion = true)
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                CanvasWorkspace(
                                    controller,
                                    Modifier.offset(16.dp, 16.dp).size(96.dp).onGloballyPositioned {
                                        canvasBounds = it.boundsInWindow()
                                    },
                                )
                            }
                        }
                    }
                    frame()
                    assertEquals(Rect(16f, 16f, 112f, 112f), canvasBounds)
                    val beforePixels = nativePixels()
                    val beforeScene = scenePixels()
                    assertEquals(Color.White.toArgb(), beforeScene[64 * 280 + 64])
                    val beforeDocument = controller.document
                    assertFalse(beforeDocument.canUndo)
                    assertFalse(beforeDocument.canRedo)
                    assertTrue(beforePixels.any { it != 0.toByte() })
                    updates.clear()
                    pointer(PointerEventType.Enter, 256f)
                    assertTrue(updates.isEmpty(), "The surrounding workspace must keep its cursor")
                    pointer(PointerEventType.Move, 64f)
                    withTimeout(15_000) {
                        while (updates.none { nativeCursor(it).type == Cursor.CUSTOM_CURSOR }) {
                            delay(5)
                            frame()
                        }
                    }
                    val nativeIcon = updates.last()
                    val cursor = nativeCursor(nativeIcon)
                    assertEquals(Cursor.CUSTOM_CURSOR, cursor.type)
                    assertEquals("Podor Stylus", cursor.name)
                    pointer(PointerEventType.Move, 80f)
                    assertSame(nativeIcon, updates.last())
                    frame()
                    assertContentEquals(
                        beforeScene,
                        scenePixels(),
                        "A native mouse cursor must not paint a circle into the white canvas",
                    )
                    pointer(PointerEventType.Move, 256f)
                    assertSame(PointerIcon.Default, updates.last())
                    scale.value = 2f
                    frame()
                    assertEquals(Rect(32f, 32f, 224f, 224f), canvasBounds)
                    assertTrue(canvasBounds.contains(Offset(96f, 64f)))
                    pointer(PointerEventType.Move, 96f)
                    assertSame(
                        nativeIcon,
                        updates.last(),
                        "Interface scaling must retain the native image and hotspot",
                    )
                    assertSame(cursor, nativeCursor(updates.last()))
                    pointer(PointerEventType.Exit, 96f)
                    assertSame(PointerIcon.Default, updates.last())
                    for (type in listOf(PointerType.Stylus, PointerType.Eraser)) {
                        scene.cancelPointerInput()
                        updates.clear()
                        pointer(PointerEventType.Enter, 64f, type)
                        pointer(PointerEventType.Move, 80f, type)
                        pointer(PointerEventType.Exit, 80f, type)
                        frame()
                        assertTrue(
                            updates.isEmpty(),
                            "$type hover must not replace the mouse cursor",
                        )
                    }
                    delay(100)
                    frame()
                    assertContentEquals(
                        beforePixels,
                        nativePixels(),
                        "Hover must not write native RGBA pixels",
                    )
                    assertEquals(
                        beforeDocument,
                        controller.document,
                        "Hover must not change revision, content or history",
                    )
                } finally {
                    scene.close()
                    surface.close()
                    recomposer.close()
                    ImageComposeScene(1, 1) {
                            PodorTheme(
                                appearance = appearance,
                                workspaceAppearance =
                                    WorkspaceAppearance(
                                        density = density,
                                        reducedMotion = reducedMotion,
                                    ),
                            ) {}
                        }
                        .use { it.render().close() }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.shutdown() }
            scope.cancel()
        }
    }
}
