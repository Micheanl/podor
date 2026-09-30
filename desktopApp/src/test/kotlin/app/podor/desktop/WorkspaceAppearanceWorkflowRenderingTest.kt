package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
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
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class WorkspaceAppearanceWorkflowRenderingTest {
    private class MemoryFiles(val project: ByteArray, preferences: Preferences) : ProjectFiles {
        val preferences = AtomicReference(Json.encodeToString(preferences).encodeToByteArray())
        val saved = AtomicReference<ByteArray>()
        val writes = AtomicInteger()
        val saves = AtomicInteger()
        val reference = ProjectReference("memory/source.pod", "source.pod")

        override suspend fun open(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return project.copyOf()
        }

        override suspend fun openDocument(reference: ProjectReference?): OpenedProject {
            assertFalse(EventQueue.isDispatchThread())
            assertTrue(reference == null || reference == this.reference)
            return OpenedProject(project.copyOf(), this.reference)
        }

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean =
            error("Project saving must use the document route")

        override suspend fun saveDocument(
            bytes: ByteArray,
            reference: ProjectReference?,
            saveAs: Boolean,
        ): ProjectReference? {
            assertFalse(EventQueue.isDispatchThread())
            assertEquals(this.reference, reference)
            assertFalse(saveAs)
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return null
        }

        override suspend fun readPreferences(): ByteArray {
            assertFalse(EventQueue.isDispatchThread())
            return preferences.get().copyOf()
        }

        override suspend fun writePreferences(bytes: ByteArray) {
            assertFalse(EventQueue.isDispatchThread())
            preferences.set(bytes.copyOf())
            writes.incrementAndGet()
        }
    }

    private fun state(engine: NativeEngine): DocumentInfo =
        Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, """{"type":"state"}""".encodeToByteArray())
                .decodeToString()
        )

    private fun command(engine: NativeEngine, json: String): DocumentInfo {
        val value = Json.parseToJsonElement(json).jsonObject
        val request = buildJsonObject {
            value.forEach { (key, entry) -> put(key, entry) }
            put("revision", state(engine).revision)
        }
        return Json.decodeFromString(
            engine
                .call(EngineOperation.COMMAND, request.toString().encodeToByteArray())
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
                    """{"type":"fill","x":20,"y":20,"color":[20,80,100,255],"tolerance":0}""",
                )
                for ((point, color) in corners) {
                    val left = point.x.toInt() - 4
                    val top = point.y.toInt() - 4
                    command(
                        engine,
                        """{"type":"select","rect":{"left":$left,"top":$top,"right":${left + 8},"bottom":${top + 8}}}""",
                    )
                    command(
                        engine,
                        """{"type":"fill","x":$left,"y":$top,"color":[${color shr 16 and 255},${color shr 8 and 255},${color and 255},255],"tolerance":0}""",
                    )
                }
                command(engine, """{"type":"select","rect":null}""")
                engine.call(EngineOperation.SAVE)
            } finally {
                engine.close()
            }
        }

    private val corners =
        listOf(
            Offset(4f, 4f) to 0xFFDC3038.toInt(),
            Offset(60f, 4f) to 0xFF28B058.toInt(),
            Offset(4f, 44f) to 0xFF3060E0.toInt(),
            Offset(60f, 44f) to 0xFFE8C028.toInt(),
        )

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { value, index ->
            value or ((bytes[offset + index].toInt() and 255) shl (index * 8))
        }

    private suspend fun nativePixels(bytes: ByteArray): IntArray =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            val engine = createNativeEngine(1, 1)
            try {
                engine.call(EngineOperation.LOAD, bytes)
                val packet = engine.call(EngineOperation.FRAME, byteArrayOf(1))
                assertEquals(64, intAt(packet, 0))
                assertEquals(48, intAt(packet, 4))
                val size = intAt(packet, 8)
                var offset = 16
                IntArray(64 * 48).also { output ->
                    repeat(intAt(packet, 12)) {
                        val left = intAt(packet, offset) * size
                        val top = intAt(packet, offset + 4) * size
                        for (y in 0 until minOf(size, 48 - top)) {
                            for (x in 0 until minOf(size, 64 - left)) {
                                val pixel = offset + 8 + (y * size + x) * 4
                                output[(top + y) * 64 + left + x] =
                                    ((packet[pixel + 3].toInt() and 255) shl 24) or
                                        ((packet[pixel].toInt() and 255) shl 16) or
                                        ((packet[pixel + 1].toInt() and 255) shl 8) or
                                        (packet[pixel + 2].toInt() and 255)
                            }
                        }
                        offset += 8 + size * size * 4
                    }
                    assertEquals(packet.size, offset)
                }
            } finally {
                engine.close()
            }
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

        private fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it == label || it.startsWith("$label ·")
            } == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun button(label: String, modal: Boolean = false): SemanticsNode =
            (if (modal)
                    scene.semanticsOwners
                        .asSequence()
                        .map { it.rootSemanticsNode }
                        .filter { descendants(it).any { child -> matches(child, "关闭") } }
                        .flatMap { descendants(it) }
                else nodes())
                .filter {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.boundsInWindow.isEmpty &&
                        descendants(it).any { child -> matches(child, label) }
                }
                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                ?: error(
                    "Missing visible clickable control: $label (modal=$modal, scene=${width}x$height). " +
                        "Matching semantics: " +
                        nodes()
                            .filter { matches(it, label) }
                            .joinToString {
                                "position=${it.positionInWindow}, bounds=${it.boundsInWindow}"
                            }
                )

        fun canvasBounds(): Rect =
            nodes()
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" }
                .boundsInWindow

        fun position(point: Offset): Offset {
            val bounds = canvasBounds()
            return controller.viewport.toView(point, bounds.size, controller.document) +
                bounds.topLeft
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
            repeat(20) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun click(label: String, disabled: Boolean = false, modal: Boolean = false) {
            withContext(Dispatchers.Main) {
                val node = button(label, modal)
                assertEquals(disabled, node.config.contains(SemanticsProperties.Disabled), label)
                val point = node.boundsInWindow.center
                assertTrue(Rect(0f, 0f, width.toFloat(), height.toFloat()).contains(point), label)
                scene.sendPointerEvent(PointerEventType.Press, point)
                render().close()
                scene.sendPointerEvent(PointerEventType.Release, point)
                render().close()
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            }
            settle()
        }

        suspend fun scrollTo(label: String) {
            var details = ""
            repeat(12) {
                val request =
                    withContext(Dispatchers.Main) {
                        val scroll =
                            nodes()
                                .filter {
                                    it.config.contains(SemanticsActions.ScrollBy) &&
                                        !it.boundsInWindow.isEmpty &&
                                        it.config.contains(
                                            SemanticsProperties.VerticalScrollAxisRange
                                        ) &&
                                        descendants(it).any { child -> matches(child, label) }
                                }
                                .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
                                ?: error("Missing visible vertical scroll container for: $label")
                        val control =
                            descendants(scroll)
                                .filter {
                                    it.config.contains(SemanticsActions.OnClick) &&
                                        descendants(it).any { child -> matches(child, label) }
                                }
                                .minByOrNull { it.size.width * it.size.height }
                                ?: error("Missing clickable control in scroll container: $label")
                        val bounds = scroll.boundsInWindow
                        val top = control.positionInWindow.y
                        val bottom = top + control.size.height
                        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                        details =
                            "viewport=$bounds, control=$top..$bottom, scroll=${range.value()}/${range.maxValue()}"
                        if (
                            !control.boundsInWindow.isEmpty &&
                                top >= bounds.top &&
                                bottom <= bounds.bottom
                        )
                            null
                        else bounds.center to if (top < bounds.top) -3f else 3f
                    } ?: return
                withContext(Dispatchers.Main) {
                    scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        request.first,
                        scrollDelta = Offset(0f, request.second),
                    )
                    render().close()
                }
                settle()
            }
            error("Control did not scroll into view after 12 wheel steps: $label ($details)")
        }

        suspend fun clickSetting(label: String) {
            scrollTo(label)
            click(label)
        }

        suspend fun save(): ByteArray {
            val count = files.saves.get()
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
            waitFor { files.saves.get() == count + 1 }
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

        fun assertCornersVisible() {
            val bounds = canvasBounds()
            assertTrue(
                bounds.left >= 0f &&
                    bounds.top >= 0f &&
                    bounds.right <= width &&
                    bounds.bottom <= height
            )
            render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                val panel = pixels[2, 2]
                if (controller.preferences.appearance == Appearance.Light)
                    assertTrue(panel.red > 0.8f && panel.green > 0.8f && panel.blue > 0.8f)
                else assertTrue(panel.red < 0.25f && panel.green < 0.25f && panel.blue < 0.25f)
                corners.forEach { (point, color) ->
                    val view = position(point)
                    assertTrue(bounds.contains(view))
                    assertEquals(color, pixels[view.x.roundToInt(), view.y.roundToInt()].toArgb())
                }
            }
        }

        fun screenshot(name: String) {
            render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    val directory = Path.of("build", "reports", "screenshots")
                    Files.createDirectories(directory)
                    Files.write(directory.resolve(name), data.bytes)
                }
            }
        }
    }

    private suspend fun withSession(
        files: MemoryFiles,
        width: Int = 900,
        height: Int = 1200,
        block: suspend Session.() -> Unit,
    ) {
        val original = StudioTheme.appearance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(width, height) { PodorApp(controller) }
            }
        val session = Session(controller, scene, files, width, height)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor {
                controller.hasCanvas && !controller.showWorkspace && controller.document.width == 64
            }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
                val restore = ImageComposeScene(1, 1) { PodorTheme(appearance = original) {} }
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
    fun realSettingsPersistLayoutWithoutChangingTheProjectAndReducedMotionKeepsTheDialogOpen() =
        runBlocking {
            val source = project()
            val preferences =
                Preferences(
                    appearance = Appearance.Light,
                    palette = listOf(0xFF123456, 0xFFABCDEF),
                    shortcuts = mapOf(ShortcutAction.Brush to Shortcut("B", alt = true)),
                    workspaceAppearance = WorkspaceAppearance(reducedMotion = true),
                )
            val files = MemoryFiles(source, preferences)
            withSession(files) {
                val before = controller.document
                val reference = controller.projectReference
                val dirty = controller.hasUnsavedChanges
                assertContentEquals(source, save())
                click("设置")
                click("界面")
                clickSetting("减少动态效果")
                scrollTo("工具栏 · 顶部")
                withContext(Dispatchers.Main) {
                    assertFalse(controller.preferences.workspaceAppearance.reducedMotion)
                    assertNotNull(button("关闭"))
                    assertNotNull(button("工具栏 · 顶部"))
                }
                clickSetting("减少动态效果")
                clickSetting("工具栏 · 顶部")
                clickSetting("面板 · 左侧")
                clickSetting("紧凑")
                clickSetting("放大界面")
                clickSetting("下移工具 · 画笔")
                clickSetting("隐藏工具 · 画笔")
                click("关闭")
                waitFor { files.writes.get() == 8 }
                val persisted =
                    Json.decodeFromString<Preferences>(files.preferences.get().decodeToString())
                assertEquals(ToolDockPosition.Top, persisted.workspaceAppearance.toolDock)
                assertEquals(
                    InspectorPosition.Left,
                    persisted.workspaceAppearance.inspectorPosition,
                )
                assertEquals(InterfaceDensity.Compact, persisted.workspaceAppearance.density)
                assertEquals(1.25f, persisted.workspaceAppearance.scale)
                assertTrue(persisted.workspaceAppearance.reducedMotion)
                assertEquals(
                    listOf("Eraser", "Brush"),
                    persisted.workspaceAppearance.toolOrder.take(2),
                )
                assertEquals(setOf("Brush"), persisted.workspaceAppearance.hiddenTools)
                assertEquals(preferences.palette, persisted.palette)
                assertEquals(preferences.shortcuts, persisted.shortcuts)
                assertEquals(before, controller.document)
                assertEquals(reference, controller.projectReference)
                assertEquals(dirty, controller.hasUnsavedChanges)
                assertContentEquals(source, save())
                click("更多工具")
                click("画笔")
                assertEquals(Tool.Brush, controller.tool)
                assertEquals(before, controller.document)
            }
            withSession(files, width = 1600) {
                assertEquals(
                    ToolDockPosition.Top,
                    controller.preferences.workspaceAppearance.toolDock,
                )
                assertEquals(setOf("Brush"), controller.preferences.workspaceAppearance.hiddenTools)
                withContext(Dispatchers.Main) {
                    val bounds = canvasBounds()
                    val dock = button("橡皮").boundsInWindow
                    assertTrue(dock.bottom <= bounds.top)
                    assertCornersVisible()
                }
                click("展开面板")
                withContext(Dispatchers.Main) {
                    val layers = button("图层")
                    val canvas = canvasBounds()
                    assertTrue(layers.boundsInWindow.right <= canvas.left)
                    assertCornersVisible()
                }
                click("设置")
                click("界面")
                clickSetting("面板 · 右侧")
                click("关闭")
                withContext(Dispatchers.Main) {
                    val layers = button("图层")
                    val canvas = canvasBounds()
                    assertTrue(layers.boundsInWindow.left >= canvas.right)
                    assertCornersVisible()
                }
                assertContentEquals(source, save())
            }
        }

    @Test
    fun fourDockPositionsAtTwoHundredPercentKeepNarrowControlsAndEveryCanvasCornerVisible() =
        runBlocking {
            val source = project()
            for (theme in listOf(Appearance.Light, Appearance.Dark)) {
                for (dock in ToolDockPosition.entries) {
                    val files =
                        MemoryFiles(
                            source,
                            Preferences(
                                appearance = theme,
                                workspaceAppearance =
                                    WorkspaceAppearance(
                                        toolDock = dock,
                                        scale = 2f,
                                        reducedMotion = true,
                                    ),
                            ),
                        )
                    withSession(files, width = 400, height = 800) {
                        withContext(Dispatchers.Main) {
                            assertCornersVisible()
                            val canvas = canvasBounds()
                            val labels = listOf("工程菜单", "导出图像", "展开面板", "更多工具")
                            val controls = labels.map { button(it).boundsInWindow }
                            controls.forEach { bounds ->
                                assertTrue(
                                    bounds.left >= 0f &&
                                        bounds.top >= 0f &&
                                        bounds.right <= 400f &&
                                        bounds.bottom <= 800f,
                                    bounds.toString(),
                                )
                                assertFalse(bounds.overlaps(canvas), bounds.toString())
                            }
                            controls.forEachIndexed { index, bounds ->
                                controls.drop(index + 1).forEach { other ->
                                    assertFalse(bounds.overlaps(other))
                                }
                            }
                            if (dock == ToolDockPosition.Left)
                                screenshot("workspace-${theme.name.lowercase()}.png")
                        }
                        click("展开面板")
                        withContext(Dispatchers.Main) {
                            val tabs =
                                listOf("工具选项", "颜色", "图层", "调整", "动画").map {
                                    button(it, modal = true).boundsInWindow
                                }
                            tabs.forEach {
                                assertTrue(
                                    it.left >= 0f &&
                                        it.right <= 400f &&
                                        it.top >= 0f &&
                                        it.bottom <= 800f
                                )
                                assertTrue(it.width >= 64f && it.height >= 64f)
                            }
                            tabs.forEachIndexed { index, bounds ->
                                tabs.drop(index + 1).forEach { other ->
                                    assertFalse(bounds.overlaps(other))
                                }
                            }
                        }
                        click("关闭")
                        click("更多工具")
                        withContext(Dispatchers.Main) {
                            listOf("适合窗口", "镜像视图", "旋转视图").forEach {
                                val bounds = button(it).boundsInWindow
                                assertTrue(
                                    bounds.left >= 0f &&
                                        bounds.right <= 400f &&
                                        bounds.top >= 0f &&
                                        bounds.bottom <= 800f
                                )
                            }
                        }
                        click("适合窗口")
                        withContext(Dispatchers.Main) {
                            scene.sendPointerEvent(PointerEventType.Press, Offset.Zero)
                            scene.sendPointerEvent(PointerEventType.Release, Offset.Zero)
                            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                        }
                        settle()
                        withContext(Dispatchers.Main) { assertCornersVisible() }
                        assertContentEquals(source, save())
                        val beforeAnimation = controller.document
                        click("展开面板")
                        click("动画", modal = true)
                        waitFor { controller.document.animation != null }
                        withContext(Dispatchers.Main) {
                            assertTrue(
                                button("动画", modal = true).config[SemanticsProperties.Selected]
                            )
                            val play = button("播放动画", modal = true)
                            assertFalse(play.config.contains(SemanticsProperties.Disabled))
                            val bounds = play.boundsInWindow
                            assertTrue(
                                bounds.left >= 0f &&
                                    bounds.right <= 400f &&
                                    bounds.top >= 0f &&
                                    bounds.bottom <= 800f
                            )
                            assertEquals(beforeAnimation.revision + 1, controller.document.revision)
                            assertEquals(1, controller.document.animation!!.frames.size)
                        }
                        click("关闭")
                        withContext(Dispatchers.Main) { assertCornersVisible() }
                        assertContentEquals(nativePixels(source), nativePixels(save()))
                    }
                }
            }
        }

    @Test
    fun queuedLayoutChangesDuringARealStrokeKeepTheCanvasStableAndCreateOnlyOneUndo() =
        runBlocking {
            val source = project()
            val sourcePixels = nativePixels(source)
            val files =
                MemoryFiles(
                    source,
                    Preferences(
                        appearance = Appearance.Dark,
                        workspaceAppearance = WorkspaceAppearance(reducedMotion = true),
                    ),
                )
            withSession(files) {
                val before = controller.document
                val originalBounds = withContext(Dispatchers.Main) { canvasBounds() }
                withContext(Dispatchers.Main) {
                    controller.brush =
                        BrushSettings(
                            preset = BrushPreset.PixelPencil.copy(stabilization = 0f),
                            size = 3f,
                            opacity = 1f,
                            color = 0xFFFF0000,
                        )
                }
                stylus(PointerEventType.Press, Offset(20f, 24f), true)
                waitFor { controller.drawingInput }
                withContext(Dispatchers.Main) {
                    controller.updatePreferences(
                        controller.preferences.copy(
                            workspaceAppearance =
                                WorkspaceAppearance(
                                    toolDock = ToolDockPosition.Bottom,
                                    scale = 1.5f,
                                    reducedMotion = true,
                                )
                        )
                    )
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertTrue(controller.drawingInput)
                    assertEquals(originalBounds, canvasBounds())
                    assertTrue(button("展开面板").config.contains(SemanticsProperties.Disabled))
                }
                withContext(Dispatchers.Main) {
                    assertNotNull(button("展开面板").config[SemanticsActions.OnClick].action).invoke()
                    render().close()
                    assertEquals(originalBounds, canvasBounds())
                    assertTrue(controller.drawingInput)
                }
                stylus(PointerEventType.Move, Offset(28f, 24f), true)
                stylus(PointerEventType.Move, Offset(36f, 24f), true)
                stylus(PointerEventType.Release, Offset(36f, 24f), false)
                waitFor {
                    !controller.drawingInput && controller.document.revision == before.revision + 1
                }
                settle()
                withContext(Dispatchers.Main) {
                    assertNotEquals(originalBounds, canvasBounds())
                    assertTrue(button("画笔").boundsInWindow.top >= canvasBounds().bottom)
                    assertTrue(controller.document.canUndo)
                }
                val pixels = nativePixels(save())
                for (x in 20..36) assertEquals(0xFFFF0000.toInt(), pixels[24 * 64 + x])
                for (y in 0 until 48) for (x in 0 until 64) {
                    if (x !in 18..38 || y !in 22..26)
                        assertEquals(sourcePixels[y * 64 + x], pixels[y * 64 + x], "$x,$y")
                }
                withContext(Dispatchers.Main) { controller.command("undo") }
                waitFor { controller.document.revision == before.revision + 2 }
                assertFalse(controller.document.canUndo)
                assertTrue(controller.document.canRedo)
                assertContentEquals(sourcePixels, nativePixels(save()))
                assertEquals(files.reference, controller.projectReference)
            }
        }
}
