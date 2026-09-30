package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ContextToolDockRenderingTest {
    private class MemoryFiles(val source: ByteArray, val preferences: Preferences) : ProjectFiles {
        val saved = AtomicReference<ByteArray>()
        val saves = AtomicInteger()

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return source.copyOf()
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            assertFalse(EventQueue.isDispatchThread())
            assertFalse(png)
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return true
        }

        override suspend fun readPreferences(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return Json.encodeToString(preferences).encodeToByteArray()
        }
    }

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun command(engine: NativeEngine, request: String): DocumentInfo {
        val fields = Json.parseToJsonElement(request).jsonObject
        val value = buildJsonObject {
            fields.forEach { (key, entry) -> put(key, entry) }
            put("revision", state(engine).revision)
        }
        return Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, value.toString().encodeToByteArray())
                .decodeToString()
        )
    }

    private suspend fun project(): ByteArray =
        withContext(Dispatchers.Default) {
            NativeLoader.load()
            val engine = createNativeEngine(64, 48)
            try {
                command(
                    engine,
                    """{"type":"fill","x":0,"y":0,"color":[32,96,128,255],"tolerance":0}""",
                )
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
            }
        }

    private suspend fun <T> probe(bytes: ByteArray, block: (NativeEngine) -> T): T =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            val engine = createNativeEngine(1, 1)
            try {
                engine.call(EngineOperation.LOAD, bytes)
                block(engine)
            } finally {
                engine.close()
            }
        }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { result, index ->
            result or ((bytes[offset + index].toInt() and 255) shl (index * 8))
        }

    private fun pixels(engine: NativeEngine): IntArray {
        val bytes = engine.call(EngineOperation.FRAME, byteArrayOf(1))
        assertEquals(64, intAt(bytes, 0))
        assertEquals(48, intAt(bytes, 4))
        val tile = intAt(bytes, 8)
        var offset = 16
        return IntArray(64 * 48).also { output ->
            repeat(intAt(bytes, 12)) {
                val left = intAt(bytes, offset) * tile
                val top = intAt(bytes, offset + 4) * tile
                for (y in 0 until minOf(tile, 48 - top)) {
                    for (x in 0 until minOf(tile, 64 - left)) {
                        val source = offset + 8 + (y * tile + x) * 4
                        output[(top + y) * 64 + left + x] =
                            ((bytes[source + 3].toInt() and 255) shl 24) or
                                ((bytes[source].toInt() and 255) shl 16) or
                                ((bytes[source + 1].toInt() and 255) shl 8) or
                                (bytes[source + 2].toInt() and 255)
                    }
                }
                offset += 8 + tile * tile * 4
            }
            assertEquals(bytes.size, offset)
        }
    }

    private suspend fun pixels(bytes: ByteArray): IntArray = probe(bytes, ::pixels)

    private suspend fun rotatedPixels(bytes: ByteArray): IntArray =
        probe(bytes) { engine ->
            command(
                engine,
                """{"type":"transform_layer","id":1,"transform":{"width":64,"height":48,"dx":0,"dy":0,"angle":90,"flip_x":false,"flip_y":false,"filter":"lanczos3"}}""",
            )
            pixels(engine)
        }

    private suspend fun gradientPixels(
        bytes: ByteArray,
        start: Offset,
        end: Offset,
        opacity: Float,
    ): IntArray =
        probe(bytes) { engine ->
            command(
                engine,
                """{"type":"gradient","id":1,"settings":{"start":[${start.x},${start.y}],"end":[${end.x},${end.y}],"from":[0,0,0,255],"to":[255,255,255,255],"opacity":$opacity,"shape":"linear"}}""",
            )
            pixels(engine)
        }

    private val stroke = listOf(Offset(14f, 20f), Offset(30f, 20f), Offset(46f, 20f))
    private val brush =
        BrushSettings(
            preset = BrushPreset.PixelPencil.copy(pressureCurve = 0f, opacityPressure = 0f),
            size = 3f,
            opacity = 1f,
            color = 0xFFB35734,
        )

    private suspend fun strokePixels(bytes: ByteArray, points: List<Offset>): IntArray =
        probe(bytes) { engine ->
            command(
                engine,
                """{"type":"begin","brush":{"size":3,"opacity":1,"hardness":1,"tip":"round","texture":"smooth","raster":"pixel","aspect":1,"angle":0,"follow_direction":false,"grain":0,"spacing":0.08,"stabilization":0,"pressure_curve":0,"size_pressure":0,"opacity_pressure":0,"mix":0,"paper":0,"smudge":false,"eraser":false,"symmetry":{"mode":"off","x":0.5,"y":0.5},"color":[179,87,52]}}""",
            )
            val samples = ByteArray(points.size * 12)
            points.forEachIndexed { index, point ->
                listOf(point.x, point.y, 1f).forEachIndexed { component, value ->
                    repeat(4) { byte ->
                        samples[index * 12 + component * 4 + byte] =
                            (value.toBits() ushr (byte * 8)).toByte()
                    }
                }
            }
            engine.call(EngineOperation.SAMPLES, samples)
            command(engine, """{"type":"end"}""")
            pixels(engine)
        }

    private inner class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
        val width: Int,
        val height: Int,
    ) {
        private var frame = 1L

        fun render() = scene.render(frame++ * 16_666_667L)

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        private fun nodes(unmerged: Boolean = false) =
            scene.semanticsOwners.asSequence().flatMap {
                descendants(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
            }

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun buttons(label: String) =
            nodes()
                .filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .toList()

        fun button(label: String) =
            buttons(label).minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error("Expected one visible button: $label, found ${buttons(label).size}")

        fun sliders(label: String) =
            nodes()
                .filter {
                    it.config.contains(SemanticsActions.SetProgress) &&
                        matches(it, label) &&
                        !it.boundsInWindow.isEmpty
                }
                .toList()

        suspend fun slider(label: String, fraction: Float) {
            val points =
                withContext(Dispatchers.Main) {
                    val target = sliders(label).single()
                    val bounds = target.boundsInWindow
                    val range = target.config[SemanticsProperties.ProgressBarRangeInfo]
                    val current =
                        (range.current - range.range.start) /
                            (range.range.endInclusive - range.range.start)
                    val left = bounds.left + 12f
                    val width = bounds.width - 24f
                    Offset(left + width * current, bounds.center.y) to
                        Offset(left + width * fraction, bounds.center.y)
                }
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(PointerEventType.Press, points.first)
                render().close()
                scene.sendPointerEvent(PointerEventType.Move, points.second)
                render().close()
                scene.sendPointerEvent(PointerEventType.Release, points.second)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        private fun tagged(tag: String) =
            nodes(true).filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.toList()

        fun canvasBounds() = tagged("canvas-workspace").single().boundsInWindow

        fun dockBounds(): Rect? = tagged("context-tool-dock").singleOrNull()?.boundsInWindow

        private fun position(point: Offset): Offset {
            val bounds = canvasBounds()
            return controller.viewport.toView(point, bounds.size, controller.document) +
                bounds.topLeft
        }

        fun sampled(point: Offset): Offset {
            val bounds = canvasBounds()
            return controller.viewport.toDocument(
                position(point) - bounds.topLeft,
                bounds.size,
                controller.document,
            )
        }

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                    }
                    delay(5)
                    if (
                        withContext(Dispatchers.Main) {
                            predicate() &&
                                !controller.busy &&
                                controller.previews.revision == controller.document.revision
                        }
                    )
                        break
                }
            }

        suspend fun settle() {
            repeat(30) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun click(label: String) {
            withContext(Dispatchers.Main) {
                val target = button(label)
                assertFalse(target.config.contains(SemanticsProperties.Disabled), label)
                scene.sendPointerEvent(PointerEventType.Press, target.boundsInWindow.center)
                render().close()
                scene.sendPointerEvent(PointerEventType.Release, target.boundsInWindow.center)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        suspend fun tool(tool: Tool) {
            if (withContext(Dispatchers.Main) { buttons(tool.label).isEmpty() }) click("更多工具")
            click(tool.label)
            waitFor { controller.tool == tool }
        }

        suspend fun key(key: Key, command: Boolean = false, shift: Boolean = false) {
            withContext(Dispatchers.Main) {
                val mac = System.getProperty("os.name").startsWith("Mac")
                for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) scene.sendKeyEvent(
                    KeyEvent(
                        key,
                        type,
                        isCtrlPressed = command && !mac,
                        isMetaPressed = command && mac,
                        isShiftPressed = shift,
                    )
                )
                render().close()
            }
            settle()
        }

        suspend fun save(): ByteArray {
            val before = files.saves.get()
            key(Key.S, command = true)
            waitFor { files.saves.get() == before + 1 }
            return assertNotNull(files.saved.get()).copyOf()
        }

        suspend fun stylus(type: PointerEventType, point: Offset, pressed: Boolean) {
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(
                    type,
                    listOf(
                        ComposeScenePointer(
                            PointerId(1),
                            position(point),
                            pressed,
                            PointerType.Stylus,
                            1f,
                        )
                    ),
                )
                render().close()
            }
        }

        private fun scrollNode() =
            tagged("context-tool-dock").single().let { root ->
                descendants(root).single {
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                        !it.boundsInWindow.isEmpty
                }
            }

        suspend fun scrollTo(label: String) {
            repeat(100) {
                val request =
                    withContext(Dispatchers.Main) {
                        val scroll = scrollNode()
                        val target =
                            descendants(scroll).single {
                                it.config.contains(SemanticsActions.OnClick) && matches(it, label)
                            }
                        if (target.boundsInWindow.width >= target.size.width * 0.75f) null
                        else
                            scroll.boundsInWindow.center to
                                if (target.positionInWindow.x < scroll.boundsInWindow.left) -1f
                                else 1f
                    } ?: return
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        request.first,
                        scrollDelta = Offset(request.second, 0f),
                    )
                    render().close()
                }
                settle()
            }
            error("Horizontal pointer scroll did not expose $label")
        }

        fun scrollAmount() =
            scrollNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

        fun assertDockContained(singleRowLabel: String) {
            val canvas = canvasBounds()
            val dock = assertNotNull(dockBounds())
            assertTrue(dock.left > canvas.left && dock.right < canvas.right, "$dock in $canvas")
            assertTrue(dock.top > canvas.top && dock.bottom < canvas.bottom, "$dock in $canvas")
            val control = button(singleRowLabel).boundsInWindow
            assertTrue(
                dock.height <= control.height * 1.5f,
                "Dock is no longer a single row: $dock",
            )
            assertTrue(dock.width <= 620f * files.preferences.workspaceAppearance.scale)
            assertTrue(control.left >= dock.left && control.right <= dock.right)
            assertEquals(control.width, button(singleRowLabel).size.width.toFloat())
            assertEquals(control.height, button(singleRowLabel).size.height.toFloat())
        }

        suspend fun screenshot(name: String) {
            val image = withContext(Dispatchers.Main) { render() }
            try {
                withContext(Dispatchers.IO) {
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        val directory = Path.of("build", "reports", "screenshots")
                        Files.createDirectories(directory)
                        Files.write(directory.resolve(name), it.bytes)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { image.close() }
            }
        }
    }

    private suspend fun withSession(
        source: ByteArray,
        preferences: Preferences =
            Preferences(workspaceAppearance = WorkspaceAppearance(reducedMotion = true)),
        width: Int = 1360,
        height: Int = 900,
        block: suspend Session.() -> Unit,
    ) {
        val previousAppearance = StudioTheme.appearance
        val previousWorkspace =
            WorkspaceAppearance(
                density = StudioTheme.interfaceDensity,
                reducedMotion = StudioMotion.reducedMotion,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val files = MemoryFiles(source, preferences)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(width, height) { StudioApp(controller) }
            }
        val session = Session(controller, scene, files, width, height)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 64 }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val restore =
                    ImageComposeScene(1, 1) {
                        PodorTheme(
                            appearance = previousAppearance,
                            workspaceAppearance = previousWorkspace,
                        ) {}
                    }
                try {
                    restore.render().close()
                } finally {
                    restore.close()
                }
            }
            scope.cancel()
        }
    }

    @Test
    fun activePanelButtonsCloseOnSecondClickAndViewControlsRemainInTheToolDock() = runBlocking {
        val source = project()
        for (theme in Appearance.entries) withSession(
            source,
            Preferences(
                appearance = theme,
                workspaceAppearance =
                    WorkspaceAppearance(toolDock = ToolDockPosition.Left, reducedMotion = true),
            ),
        ) {
            val initial = withContext(Dispatchers.Main) { canvasBounds() }
            click("笔刷库")
            withContext(Dispatchers.Main) {
                assertTrue(button("工具选项").config[SemanticsProperties.Selected])
                assertTrue(canvasBounds().width < initial.width)
                assertTrue(buttons("视图").isEmpty())
            }
            click("笔刷库")
            withContext(Dispatchers.Main) { assertEquals(initial, canvasBounds()) }
            click("笔刷库")
            click("工具选项")
            withContext(Dispatchers.Main) { assertEquals(initial, canvasBounds()) }
            click("展开面板")
            click("颜色")
            withContext(Dispatchers.Main) { scene.constraints = Constraints.fixed(800, height) }
            settle()
            withContext(Dispatchers.Main) { assertEquals(1, buttons("颜色").size) }
            withContext(Dispatchers.Main) { scene.constraints = Constraints.fixed(width, height) }
            settle()
            withContext(Dispatchers.Main) {
                assertEquals(1, buttons("颜色").size)
                assertTrue(button("颜色").config[SemanticsProperties.Selected])
            }
            click("颜色")
            withContext(Dispatchers.Main) {
                assertEquals(initial, canvasBounds())
                val mirror = button("镜像视图").boundsInWindow
                assertTrue(mirror.right <= initial.left)
                assertTrue(buttons("视图").isEmpty())
            }
            click("镜像视图")
            withContext(Dispatchers.Main) { assertTrue(controller.viewport.mirrored) }
            click("适合窗口")
            tool(Tool.Hand)
            click("展开面板")
            click("正常 · 100%")
            waitFor { controller.adjustmentPreview?.updating == false }
            click("取消调整")
            waitFor { controller.adjustmentPreview == null }
            withContext(Dispatchers.Main) {
                assertTrue(button("图层").config[SemanticsProperties.Selected])
            }
            click("图层")
            withContext(Dispatchers.Main) {
                assertEquals(initial, canvasBounds())
                assertEquals(Viewport(), controller.viewport)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.hasUnsavedChanges)
            }
            assertContentEquals(source, save())
        }
    }

    @Test
    fun realPanelTabsKeepOneFloatingActionRowAndHideEmptyToolOptions() = runBlocking {
        val source = project()
        withSession(source) {
            val document = withContext(Dispatchers.Main) { controller.document }
            val canvas =
                withContext(Dispatchers.Main) {
                    assertDockContained("笔刷库")
                    canvasBounds().also { assertEquals(height.toFloat(), it.bottom) }
                }
            tool(Tool.Select)
            withContext(Dispatchers.Main) {
                assertDockContained("取消选区")
                assertEquals(canvas, canvasBounds())
            }
            click("椭圆选区")
            withContext(Dispatchers.Main) {
                assertEquals(SelectionKind.Ellipse, controller.selectionKind)
            }
            click("展开面板")
            withContext(Dispatchers.Main) {
                assertDockContained("取消选区")
                assertEquals(1, buttons("椭圆选区").size)
                assertTrue(button("图层").config[SemanticsProperties.Selected])
                assertTrue(buttons("工具选项").isEmpty())
            }
            click("调整")
            withContext(Dispatchers.Main) {
                assertTrue(button("调整").config[SemanticsProperties.Selected])
                assertDockContained("取消选区")
                assertEquals(1, buttons("椭圆选区").size)
            }
            click("图层")
            withContext(Dispatchers.Main) {
                assertDockContained("取消选区")
                assertEquals(1, buttons("椭圆选区").size)
            }
            click("收起面板")
            withContext(Dispatchers.Main) {
                assertEquals(canvas, canvasBounds())
                assertDockContained("取消选区")
            }
            tool(Tool.LassoFill)
            click("展开面板")
            withContext(Dispatchers.Main) {
                assertDockContained("圈选擦除")
                assertTrue(
                    assertNotNull(dockBounds()).width < 250f,
                    "Small tool fills the whole dock",
                )
                assertTrue(button("颜色").config[SemanticsProperties.Selected])
                assertTrue(buttons("工具选项").isEmpty())
                assertEquals(1, buttons("圈选擦除").size)
            }
            click("圈选擦除")
            withContext(Dispatchers.Main) {
                assertTrue(controller.lassoErase)
                assertEquals(document, controller.document)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
            }
            tool(Tool.Brush)
            withContext(Dispatchers.Main) {
                assertTrue(button("工具选项").config[SemanticsProperties.Selected])
                assertTrue(sliders("大小").isEmpty())
                assertTrue(sliders("不透明度").isEmpty())
                assertDockContained("笔刷库")
            }
            assertContentEquals(source, save())
            val beforeAnimation = controller.document
            tool(Tool.Select)
            click("动画")
            waitFor { controller.document.animation != null }
            withContext(Dispatchers.Main) {
                assertTrue(button("动画").config[SemanticsProperties.Selected])
                assertDockContained("取消选区")
                assertEquals(1, buttons("椭圆选区").size)
                assertTrue(buttons("启用动画").isEmpty())
                assertEquals(beforeAnimation.revision + 1, controller.document.revision)
                assertEquals(1, controller.document.animation!!.frames.size)
            }
            assertContentEquals(pixels(source), pixels(save()))
        }
    }

    @Test
    fun detailedPanelsComplementFloatingActionsAndGradientCommitsOneNativeUndo() = runBlocking {
        val source = project()
        val original = pixels(source)
        withSession(source) {
            tool(Tool.Fill)
            click("展开面板")
            withContext(Dispatchers.Main) {
                assertTrue(button("工具选项").config[SemanticsProperties.Selected])
                assertEquals(1, sliders("颜色容差").size)
                assertTrue(sliders("大小").isEmpty())
                assertNull(dockBounds())
            }
            slider("颜色容差", 0.35f)
            click("仅连续区域")
            click("取样所有可见图层")
            withContext(Dispatchers.Main) {
                assertTrue(controller.fillTolerance in 80f..100f)
                assertFalse(controller.fillContiguous)
                assertTrue(controller.fillMerged)
                assertFalse(controller.hasUnsavedChanges)
                assertFalse(controller.document.canUndo)
            }
            assertContentEquals(source, save())

            tool(Tool.LineGenerator)
            waitFor {
                controller.lineGeneratorPreview?.let {
                    !it.updating && !it.committing && it.error == null
                } == true
            }
            val widthBefore =
                withContext(Dispatchers.Main) {
                    assertDockContained("取消线条")
                    assertDockContained("确认线条")
                    assertEquals(1, buttons("取消线条").size)
                    assertEquals(1, buttons("确认线条").size)
                    assertTrue(sliders("线条数量").isEmpty())
                    assertTrue(sliders("线宽").isEmpty())
                    assertEquals(1, buttons("线条数量").size)
                    assertEquals(1, buttons("线宽").size)
                    controller.lineGeneratorPreview!!.settings.stroke.width
                }
            click("线宽")
            slider("线宽", 0.4f)
            withContext(Dispatchers.Main) {
                val tab = button("工具选项").boundsInWindow
                val outside = Offset(tab.center.x, tab.top - tab.height)
                scene.sendPointerEvent(PointerEventType.Press, outside)
                scene.sendPointerEvent(PointerEventType.Release, outside)
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
            waitFor { controller.lineGeneratorPreview?.updating == false }
            withContext(Dispatchers.Main) {
                assertNotEquals(
                    widthBefore,
                    controller.lineGeneratorPreview!!.settings.stroke.width,
                )
            }
            click("取消线条")
            waitFor { controller.tool == Tool.Brush && controller.lineGeneratorPreview == null }
            assertContentEquals(source, save())

            click("图层")
            click("正常 · 100%")
            waitFor {
                controller.adjustmentPreview?.let {
                    it.settings.kind == AdjustmentKind.LayerBlend && !it.updating
                } == true
            }
            for (tab in listOf("工具选项", "图层", "调整")) {
                if (
                    !withContext(Dispatchers.Main) {
                        button(tab).config[SemanticsProperties.Selected]
                    }
                )
                    click(tab)
                withContext(Dispatchers.Main) {
                    assertTrue(button(tab).config[SemanticsProperties.Selected])
                    assertDockContained("取消调整")
                    for (label in listOf("原图对比", "重置调整", "取消调整", "确认调整")) assertEquals(
                        1,
                        buttons(label).size,
                        label,
                    )
                    assertEquals(1, sliders("图层不透明度").size)
                }
            }
            slider("图层不透明度", 0.55f)
            waitFor { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                assertTrue(controller.adjustmentPreview!!.settings.opacity in 0.5f..0.6f)
            }
            click("原图对比")
            withContext(Dispatchers.Main) { assertTrue(controller.adjustmentPreview!!.comparing) }
            click("重置调整")
            waitFor { controller.adjustmentPreview?.updating == false }
            withContext(Dispatchers.Main) {
                assertFalse(controller.adjustmentPreview!!.comparing)
                assertEquals(1f, controller.adjustmentPreview!!.settings.opacity)
            }
            click("取消调整")
            waitFor { controller.adjustmentPreview == null }
            withContext(Dispatchers.Main) { assertFalse(controller.document.canUndo) }
            assertContentEquals(source, save())

            tool(Tool.Gradient)
            waitFor { controller.gradientPreview != null }
            withContext(Dispatchers.Main) {
                assertTrue(button("工具选项").config[SemanticsProperties.Selected])
                assertEquals(1, sliders("不透明度").size)
                for (label in
                    listOf("线性渐变", "径向渐变", "反转渐变", "淡出到透明", "渐变不透明度", "取消渐变", "确认渐变")) assertEquals(
                    1,
                    buttons(label).size,
                    label,
                )
                assertDockContained("取消渐变")
            }
            slider("不透明度", 0.35f)
            val opacity =
                withContext(Dispatchers.Main) {
                    assertTrue(controller.gradient.opacity in 0.3f..0.4f)
                    assertEquals(0xFF000000L, controller.gradient.from)
                    assertEquals(0xFFFFFFFFL, controller.gradient.to)
                    assertEquals(GradientShape.Linear, controller.gradient.shape)
                    controller.gradient.opacity
                }
            click("取消渐变")
            waitFor { controller.tool == Tool.Brush && controller.gradientPreview == null }
            assertContentEquals(source, save())

            tool(Tool.Gradient)
            waitFor { controller.gradientPreview != null }
            val revision = withContext(Dispatchers.Main) { controller.document.revision }
            val start = Offset(8f, 16f)
            val end = Offset(54f, 30f)
            val mappedLine = withContext(Dispatchers.Main) { sampled(start) to sampled(end) }
            val expected = gradientPixels(source, mappedLine.first, mappedLine.second, opacity)
            assertFalse(original.contentEquals(expected))
            stylus(PointerEventType.Press, start, true)
            stylus(PointerEventType.Move, end, true)
            stylus(PointerEventType.Release, end, false)
            settle()
            withContext(Dispatchers.Main) {
                val line = assertNotNull(controller.gradientPreview!!.line)
                assertTrue((line.start - mappedLine.first).getDistance() < 0.0001f)
                assertTrue((line.end - mappedLine.second).getDistance() < 0.0001f)
                assertEquals(opacity, controller.gradient.opacity)
                assertEquals(revision, controller.document.revision)
                assertFalse(controller.document.canUndo)
            }
            click("确认渐变")
            waitFor {
                controller.document.revision == revision + 1 && controller.gradientPreview == null
            }
            assertContentEquals(expected, pixels(save()))
            key(Key.Z, command = true)
            waitFor { controller.document.revision == revision + 2 }
            withContext(Dispatchers.Main) {
                assertFalse(controller.document.canUndo)
                assertTrue(controller.document.canRedo)
            }
            assertContentEquals(original, pixels(save()))
        }
    }

    @Test
    fun narrowCapsulesScrollWithRealPointersAndKeepCancelAndConfirmFixedInBothThemes() =
        runBlocking {
            val source = project()
            val original = pixels(source)
            val rotated = rotatedPixels(source)
            assertFalse(original.contentEquals(rotated))
            for (theme in listOf(Appearance.Light, Appearance.Dark)) {
                withSession(
                    source,
                    Preferences(
                        appearance = theme,
                        workspaceAppearance = WorkspaceAppearance(scale = 2f, reducedMotion = true),
                    ),
                    width = 400,
                    height = 800,
                ) {
                    tool(Tool.TransformLayer)
                    waitFor { controller.layerMove?.transform != null }
                    val fixed =
                        withContext(Dispatchers.Main) {
                            assertDockContained("取消变换")
                            assertDockContained("确认变换")
                            assertEquals(height.toFloat(), canvasBounds().bottom)
                            render().use {
                                val color = it.toComposeImageBitmap().toPixelMap()[2, 2]
                                if (theme == Appearance.Light) assertTrue(color.red > 0.8f)
                                else assertTrue(color.red < 0.25f)
                            }
                            button("取消变换").boundsInWindow to button("确认变换").boundsInWindow
                        }
                    val proportional =
                        withContext(Dispatchers.Main) { controller.layerMove!!.proportional }
                    scrollTo("锁定比例")
                    click("锁定比例")
                    withContext(Dispatchers.Main) {
                        assertTrue(scrollAmount() > 0f)
                        assertEquals(!proportional, controller.layerMove!!.proportional)
                        assertEquals(fixed.first, button("取消变换").boundsInWindow)
                        assertEquals(fixed.second, button("确认变换").boundsInWindow)
                    }
                    click("取消变换")
                    waitFor { controller.tool == Tool.Brush && controller.layerMove == null }
                    assertContentEquals(original, pixels(save()))
                    withContext(Dispatchers.Main) {
                        assertDockContained("笔刷库")
                        assertFalse(controller.document.canUndo)
                    }
                    tool(Tool.TransformLayer)
                    waitFor { controller.layerMove?.transform != null }
                    val revision = withContext(Dispatchers.Main) { controller.document.revision }
                    scrollTo("顺时针旋转 90°")
                    click("顺时针旋转 90°")
                    withContext(Dispatchers.Main) {
                        assertEquals(90f, controller.layerMove!!.transform!!.angle)
                        assertEquals(fixed.first, button("取消变换").boundsInWindow)
                        assertEquals(fixed.second, button("确认变换").boundsInWindow)
                    }
                    screenshot("context-tool-dock-${theme.name.lowercase()}.png")
                    click("确认变换")
                    waitFor {
                        controller.tool == Tool.Brush &&
                            controller.document.revision == revision + 1
                    }
                    assertContentEquals(rotated, pixels(save()))
                    key(Key.Z, command = true)
                    waitFor { controller.document.revision == revision + 2 }
                    withContext(Dispatchers.Main) {
                        assertFalse(controller.document.canUndo)
                        assertTrue(controller.document.canRedo)
                    }
                    assertContentEquals(original, pixels(save()))
                }
            }
        }

    @Test
    fun acceptedNativeStrokeHidesShortcutActivatedDockWithoutChangingCanvasPixelsOrUndo() =
        runBlocking {
            val source = project()
            val original = pixels(source)
            withSession(source) {
                val before =
                    withContext(Dispatchers.Main) {
                        controller.brush = brush
                        controller.document
                    }
                val canvas = withContext(Dispatchers.Main) { canvasBounds() }
                val actual = withContext(Dispatchers.Main) { stroke.map { sampled(it) } }
                actual.zip(stroke).forEach { (a, b) -> assertTrue((a - b).getDistance() < 0.0001f) }
                val expected = strokePixels(source, actual)
                assertFalse(original.contentEquals(expected))
                stylus(PointerEventType.Press, stroke.first(), true)
                waitFor { controller.drawingInput }
                key(Key.M)
                waitFor { controller.tool == Tool.Select && controller.drawingInput }
                withContext(Dispatchers.Main) {
                    assertNull(dockBounds())
                    assertEquals(canvas, canvasBounds())
                    assertTrue(button("展开面板").config.contains(SemanticsProperties.Disabled))
                }
                for (point in stroke.drop(1)) {
                    stylus(PointerEventType.Move, point, true)
                    withContext(Dispatchers.Main) {
                        assertNull(dockBounds())
                        assertEquals(canvas, canvasBounds())
                        assertTrue(controller.drawingInput)
                    }
                }
                stylus(PointerEventType.Release, stroke.last(), false)
                waitFor {
                    !controller.drawingInput && controller.document.revision == before.revision + 1
                }
                withContext(Dispatchers.Main) {
                    assertDockContained("取消选区")
                    assertEquals(canvas, canvasBounds())
                    assertTrue(controller.document.canUndo)
                    assertNull(controller.document.selection)
                    assertEquals(before.layers, controller.document.layers)
                }
                assertContentEquals(expected, pixels(save()))
                key(Key.Z, command = true)
                waitFor { controller.document.revision == before.revision + 2 }
                withContext(Dispatchers.Main) {
                    assertFalse(controller.document.canUndo)
                    assertTrue(controller.document.canRedo)
                    assertEquals(canvas, canvasBounds())
                }
                assertContentEquals(original, pixels(save()))
                key(Key.Z, command = true, shift = true)
                waitFor { controller.document.revision == before.revision + 3 }
                assertContentEquals(expected, pixels(save()))
            }
        }
}
