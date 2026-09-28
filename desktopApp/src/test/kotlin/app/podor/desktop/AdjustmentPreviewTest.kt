package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.StudioApp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AdjustmentPreviewTest {
    private class FilesMemory(val bytes: ByteArray) : ProjectFiles {
        var saved: ByteArray? = null

        override suspend fun open() = bytes

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved = bytes
            return true
        }
    }

    private fun project(hidden: Boolean = false): ByteArray {
        NativeLoader.load()
        val engine = createNativeEngine(256, 192)
        fun command(value: String) = engine.call(EngineOperation.COMMAND, value.encodeToByteArray())
        try {
            command("""{"type":"fill","x":0,"y":0,"color":[65,91,116,255],"tolerance":0}""")
            command("""{"type":"add_layer"}""")
            command("""{"type":"select","rect":{"left":32,"top":24,"right":224,"bottom":168}}""")
            command("""{"type":"fill","x":50,"y":50,"color":[177,91,111,190],"tolerance":0}""")
            command(
                """{"type":"set_layer","id":2,"name":"Color study","visible":true,"opacity":0.8}"""
            )
            if (hidden) {
                command(
                    """{"type":"set_layer","id":2,"name":"Color study","visible":false,"opacity":0.8}"""
                )
                command("""{"type":"set_protection","id":2,"locked":true}""")
            }
            return engine.call(EngineOperation.SAVE)
        } finally {
            engine.close()
        }
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: FilesMemory,
    ) {
        var frame = 0L

        fun render() = scene.render(frame++ * 16_666_667L)

        fun settle() {
            repeat(35) { render().close() }
        }

        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            settle()
        }

        fun key(key: Key) {
            assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            render().close()
        }

        suspend fun number(x: Float, y: Float, value: String) {
            click(x, y)
            delay(20)
            scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyUp, isCtrlPressed = true))
            render().close()
            delay(20)
            for (digit in value) {
                val typed =
                    java.awt.event.KeyEvent(
                        java.awt.Canvas(),
                        java.awt.event.KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        0,
                        java.awt.event.KeyEvent.VK_UNDEFINED,
                        digit,
                    )
                assertTrue(
                    scene.sendKeyEvent(
                        KeyEvent(
                            Key.Unknown,
                            KeyEventType.Unknown,
                            codePoint = digit.code,
                            nativeEvent = typed,
                        )
                    ),
                    "Numeric input did not receive typed character",
                )
                render().close()
                delay(20)
            }
            settle()
        }

        fun pixel() = render().use { it.toComposeImageBitmap().toPixelMap()[520, 480] }

        fun capture(name: String) {
            val directory = Path.of("build/reports/screenshots")
            Files.createDirectories(directory)
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(directory.resolve("$name.png"), it.bytes)
                }
            }
        }

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(10_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        predicate()
                    }
                ) delay(5)
            }
    }

    private suspend fun session(bytes: ByteArray = project(), block: suspend Session.() -> Unit) {
        val files = FilesMemory(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) { ImageComposeScene(1360, 900) { StudioApp(controller) } }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.document.width == 256 && !controller.busy }
            withContext(Dispatchers.Main) { scene.openInspector { session.render().close() } }
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
            }
            scope.cancel()
        }
    }

    @Test
    fun curveEditorAddsMovesDeletesAndResetsPointsWithoutChangingTheOriginalUntilConfirmed() =
        runBlocking {
            session {
                val original = withContext(Dispatchers.Main) { pixel() }
                val source = withContext(Dispatchers.Main) { controller.frame }
                withContext(Dispatchers.Main) {
                    click(1298f, 194f)
                    click(1190f, 545f)
                }
                waitFor {
                    controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
                }
                withContext(Dispatchers.Main) {
                    settle()
                    capture("curves-neutral")
                    assertEquals(original, pixel())
                    assertFalse(controller.adjustmentPreview!!.changed)
                    assertEquals(4, controller.adjustmentPreview!!.histogram.size)
                    click(1192f, 450f)
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertEquals(3, controller.adjustmentPreview!!.settings.curves.rgb.points.size)
                    val before = controller.adjustmentPreview!!.settings.curves.rgb.points[1]
                    key(Key.DirectionUp)
                    assertEquals(
                        before.y + 1,
                        controller.adjustmentPreview!!.settings.curves.rgb.points[1].y,
                    )
                    scene.sendPointerEvent(PointerEventType.Press, Offset(1192f, 449f))
                    scene.sendPointerEvent(PointerEventType.Move, Offset(1210f, 402f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(1210f, 402f))
                    settle()
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertNotEquals(original, pixel())
                    assertSame(source, controller.frame)
                    assertFalse(controller.hasUnsavedChanges)
                    number(1250f, 685f, "180")
                    capture("curves-number")
                    assertEquals(
                        180,
                        controller.adjustmentPreview!!.settings.curves.rgb.points[1].y,
                    )
                    click(1210f, 462f)
                    capture("curves-preview")
                    key(Key.Delete)
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertEquals(ToneCurve(), controller.adjustmentPreview!!.settings.curves.rgb)
                    assertEquals(original, pixel())
                    number(1250f, 685f, "999")
                    assertFalse(controller.adjustmentPreview!!.inputValid)
                    key(Key.Enter)
                    assertNotNull(controller.adjustmentPreview)
                    click(497f, 817f)
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertTrue(controller.adjustmentPreview!!.inputValid)
                    assertEquals(ColorCurves(), controller.adjustmentPreview!!.settings.curves)
                    click(1155f, 325f)
                    click(1192f, 450f)
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertEquals(3, controller.adjustmentPreview!!.settings.curves.red.points.size)
                    assertEquals(ToneCurve(), controller.adjustmentPreview!!.settings.curves.rgb)
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    settle()
                    capture("curves-english")
                    controller.adjustmentPreview!!.comparing = true
                    settle()
                    assertEquals(original, pixel())
                    controller.adjustmentPreview!!.comparing = false
                    settle()
                    key(Key.Enter)
                }
                waitFor { controller.adjustmentPreview == null && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertTrue(controller.hasUnsavedChanges)
                    controller.command("undo")
                }
                waitFor { !controller.document.canUndo && !controller.busy }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.hasUnsavedChanges)
                    controller.prepareAdjustment(AdjustmentKind.Curves)
                }
                waitFor {
                    controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
                }
                withContext(Dispatchers.Main) {
                    controller.updateAdjustment(
                        controller.adjustmentPreview!!
                            .settings
                            .copy(
                                curves =
                                    ColorCurves(blue = ToneCurve().insert(CurvePoint(100, 200)))
                            )
                    )
                    key(Key.Escape)
                    controller.file(StudioController.FileAction.Save)
                }
                waitFor { files.saved != null && !controller.busy }
                assertContentEquals(files.bytes, files.saved)
                withContext(Dispatchers.Main) {
                    settle()
                    assertFalse(scene.hasInvalidations())
                }
            }
        }

    @Test
    fun hiddenLockedLayerRemainsHiddenWhileItsDisplayPropertiesAreEdited() = runBlocking {
        session(project(hidden = true)) {
            val original = withContext(Dispatchers.Main) { pixel() }
            withContext(Dispatchers.Main) {
                click(1240f, 194f)
                click(1150f, 739f)
            }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                controller.updateAdjustment(
                    controller.adjustmentPreview!!
                        .settings
                        .copy(opacity = 0.2f, blend = LayerBlendMode.Overlay)
                )
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertTrue(controller.adjustmentPreview!!.changed)
                assertEquals(original, pixel())
                capture("layer-blend-hidden")
                controller.commitAdjustment()
            }
            waitFor { controller.adjustmentPreview == null && !controller.busy }
            withContext(Dispatchers.Main) {
                val layer = controller.document.layers.last()
                assertEquals(0.2f, layer.opacity)
                assertEquals(LayerBlendMode.Overlay, layer.blend)
                assertTrue(layer.locked)
                assertFalse(layer.visible)
                assertEquals(original, pixel())
                controller.command("undo")
            }
            waitFor { !controller.document.canUndo && !controller.busy }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
            waitFor { files.saved != null && !controller.busy }
            assertContentEquals(files.bytes, files.saved)
        }
    }

    @Test
    fun layerBlendPanelPreviewsOpacityAndModeAndRestoresOriginalWithOneUndo() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { pixel() }
            val document = withContext(Dispatchers.Main) { controller.document }
            val originalFrame = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) {
                click(1240f, 194f)
                capture("layer-blend-entry")
                click(1150f, 739f)
            }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                assertEquals(
                    AdjustmentKind.LayerBlend,
                    controller.adjustmentPreview!!.settings.kind,
                )
                assertEquals(0.8f, controller.adjustmentPreview!!.settings.opacity)
                assertFalse(controller.adjustmentPreview!!.changed)
                settle()
                capture("layer-blend-neutral")
                click(1260f, 402f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(LayerBlendMode.Multiply, controller.adjustmentPreview!!.settings.blend)
                click(1150f, 346f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertTrue(controller.adjustmentPreview!!.settings.opacity < 0.7f)
                assertNotEquals(original, pixel())
                assertEquals(document, controller.document)
                assertSame(originalFrame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                val settings = controller.adjustmentPreview!!.settings
                click(1300f, 124f)
                click(1320f, 104f)
                assertEquals(settings, controller.adjustmentPreview!!.settings)
                click(1298f, 194f)
                assertNotEquals(original, pixel())
                click(1240f, 194f)
                capture("layer-blend-preview")
                click(1115f, 795f)
                assertTrue(controller.adjustmentPreview!!.comparing)
                assertEquals(original, pixel())
                click(1165f, 795f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(0.8f, controller.adjustmentPreview!!.settings.opacity)
                assertEquals(LayerBlendMode.Normal, controller.adjustmentPreview!!.settings.blend)
                assertFalse(controller.adjustmentPreview!!.changed)
                assertEquals(original, pixel())
                controller.updateAdjustment(
                    controller.adjustmentPreview!!
                        .settings
                        .copy(opacity = 0.35f, blend = LayerBlendMode.Screen)
                )
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            val expected =
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    settle()
                    capture("layer-blend-english")
                    val result = pixel()
                    key(Key.Enter)
                    result
                }
            waitFor { controller.adjustmentPreview == null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(expected, pixel())
                assertEquals(0.35f, controller.document.layers.last().opacity)
                assertEquals(LayerBlendMode.Screen, controller.document.layers.last().blend)
                assertTrue(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
                controller.command("undo")
            }
            waitFor { !controller.document.canUndo && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(original, pixel())
                assertFalse(controller.hasUnsavedChanges)
                settle()
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun layerBlendRapidUpdatesAndCancelPreserveSavedPropertiesAndFrame() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) {
                controller.prepareAdjustment(AdjustmentKind.LayerBlend)
            }
            waitFor { controller.adjustmentPreview != null }
            withContext(Dispatchers.Main) {
                val settings = controller.adjustmentPreview!!.settings
                repeat(300) {
                    controller.updateAdjustment(
                        settings.copy(
                            opacity = (it % 100) / 100f,
                            blend = LayerBlendMode.entries[it % LayerBlendMode.entries.size],
                        )
                    )
                }
                controller.updateAdjustment(
                    settings.copy(opacity = 0f, blend = LayerBlendMode.Difference)
                )
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(0f, controller.adjustmentPreview!!.renderedSettings!!.opacity)
                assertEquals(
                    LayerBlendMode.Difference,
                    controller.adjustmentPreview!!.renderedSettings!!.blend,
                )
                assertSame(original, controller.frame)
                controller.updateAdjustment(
                    controller.adjustmentPreview!!.settings.copy(opacity = 0.6f)
                )
                key(Key.Escape)
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertContentEquals(files.bytes, files.saved)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                assertNull(controller.adjustmentPreview)
                settle()
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun slidersCompareResetConfirmAndUndoWorkOnTheCanvas() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { pixel() }
            val originalFrame = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) {
                click(1298f, 194f)
                capture("adjustments-menu")
                click(1190f, 407f)
            }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                capture("adjustment-neutral")
                click(1260f, 368f)
            }
            waitFor {
                controller.adjustmentPreview!!.settings.brightness > 0.1f &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                assertSame(originalFrame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
                assertNull(files.saved)
                assertNotEquals(original, pixel())
                capture("adjustment-preview")
                click(449f, 817f)
                assertTrue(controller.adjustmentPreview!!.comparing)
                assertEquals(original, pixel())
                click(449f, 817f)
                assertNotEquals(original, pixel())
                click(497f, 817f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertFalse(controller.adjustmentPreview!!.changed)
                assertEquals(original, pixel())
                val value =
                    controller.adjustmentPreview!!
                        .settings
                        .copy(brightness = 0.2f, contrast = 0.1f, saturation = -0.3f)
                controller.updateAdjustment(value)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            val expected =
                withContext(Dispatchers.Main) {
                    val value = pixel()
                    controller.updatePreferences(
                        controller.preferences.copy(language = Language.English)
                    )
                    settle()
                    capture("adjustment-english")
                    key(Key.Enter)
                    value
                }
            waitFor { controller.adjustmentPreview == null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(expected, pixel())
                assertTrue(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
                controller.command("undo")
            }
            waitFor { !controller.document.canUndo && !controller.busy }
            withContext(Dispatchers.Main) {
                settle()
                assertEquals(original, pixel())
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(scene.hasInvalidations())
            }
        }
    }

    @Test
    fun rapidChangesUseLatestSettingsAndCancelNeverSavesPreviewPixels() = runBlocking {
        session {
            val original = withContext(Dispatchers.Main) { controller.frame }
            withContext(Dispatchers.Main) { controller.prepareAdjustment(AdjustmentKind.Blur) }
            waitFor { controller.adjustmentPreview != null }
            withContext(Dispatchers.Main) {
                val settings = controller.adjustmentPreview!!.settings
                repeat(300) { controller.updateAdjustment(settings.copy(sigma = 0.5f + (it % 32))) }
                controller.updateAdjustment(settings.copy(sigma = 17f))
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(17f, controller.adjustmentPreview!!.renderedSettings!!.sigma)
                assertTrue(controller.adjustmentPreview!!.changed)
                assertSame(original, controller.frame)
                controller.updateAdjustment(
                    controller.adjustmentPreview!!.settings.copy(sigma = 32f)
                )
                key(Key.Escape)
                assertNull(controller.adjustmentPreview)
                assertSame(original, controller.frame)
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertContentEquals(files.bytes, files.saved)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
                settle()
                assertFalse(scene.hasInvalidations())
            }
        }
    }
}
