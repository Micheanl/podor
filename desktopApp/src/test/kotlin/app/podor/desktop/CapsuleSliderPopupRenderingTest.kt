package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalComposeUiApi::class)
class CapsuleSliderPopupRenderingTest {
    @Test
    fun realOpacityPopupsStayCapsulesInNarrowLightAndDarkScaledWorkspaces() = runBlocking {
        val project =
            withContext(Dispatchers.Default) {
                NativeLoader.load()
                createNativeEngine(8, 8).let { engine ->
                    try {
                        engine.call(
                            EngineOperation.COMMAND,
                            """{"type":"fill","x":0,"y":0,"color":[30,100,170,255],"tolerance":0}"""
                                .encodeToByteArray(),
                        )
                        engine.call(EngineOperation.SAVE)
                    } finally {
                        engine.close()
                    }
                }
            }
        val sourceFrame = nativeFrame(project)
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            for (scale in listOf(1f, 2f)) {
                val preferences =
                    Preferences(
                        appearance = appearance,
                        workspaceAppearance =
                            WorkspaceAppearance(
                                toolDock = ToolDockPosition.Top,
                                scale = scale,
                                reducedMotion = appearance == Appearance.Light,
                            ),
                    )
                withSession(project, preferences) {
                    val document = controller.document
                    val dirty = controller.hasUnsavedChanges
                    for (tool in listOf(Tool.LassoFill, Tool.Gradient)) {
                        selectTool(tool)
                        waitFor {
                            controller.tool == tool &&
                                (tool != Tool.Gradient || controller.gradientPreview != null)
                        }
                        settle()
                        val entry = if (tool == Tool.Gradient) "渐变不透明度" else "不透明度"
                        scrollContextToEnd(entry)
                        pointer(PointerEventType.Move, Offset.Zero)
                        settle()
                        val baseline = withContext(Dispatchers.Main) { pixels() }
                        val anchor = withContext(Dispatchers.Main) { button(entry).boundsInWindow }
                        click(entry)
                        val effective = Density(scale)
                        withContext(Dispatchers.Main) {
                            assertCapsule(baseline, anchor, effective)
                            val progress = slider().config[SemanticsProperties.ProgressBarRangeInfo]
                            assertEquals(opacity(tool), progress.current, 0.0001f)
                            assertEquals(
                                if (tool == Tool.Gradient) 0f..1f else 0.01f..1f,
                                progress.range,
                            )
                            assertTrue(slider().config.contains(SemanticsActions.SetProgress))
                        }
                        val before = withContext(Dispatchers.Main) { opacity(tool) }
                        dragSlider()
                        withContext(Dispatchers.Main) {
                            val changed = opacity(tool)
                            assertTrue(changed in 0.01f..0.8f, "$tool/$appearance/$scale: $changed")
                            assertNotEquals(before, changed)
                            assertEquals(
                                changed,
                                slider().config[SemanticsProperties.ProgressBarRangeInfo].current,
                                0.0001f,
                            )
                            assertEquals(document, controller.document)
                            assertEquals(dirty, controller.hasUnsavedChanges)
                            assertFalse(controller.drawingInput)
                            assertFalse(scene.hasInvalidations())
                        }
                        pointer(PointerEventType.Press, Offset(1f, 1f))
                        pointer(PointerEventType.Release, Offset(1f, 1f))
                        settle()
                        withContext(Dispatchers.Main) {
                            assertFalse(
                                nodes().any {
                                    it.config.getOrNull(SemanticsProperties.TestTag) ==
                                        "capsule-slider-popup"
                                }
                            )
                            assertEquals(1, scene.semanticsOwners.size)
                            assertEquals(document, controller.document)
                            assertEquals(dirty, controller.hasUnsavedChanges)
                            assertFalse(scene.hasInvalidations())
                        }
                        val saved = save()
                        assertContentEquals(project, saved)
                        assertContentEquals(sourceFrame, nativeFrame(saved))
                    }
                }
            }
        }
    }

    @Test
    fun brushEraserAndLineShortcutsEditParametersWithoutPaintingOrAddingHistory() = runBlocking {
        val project =
            withContext(Dispatchers.Default) {
                NativeLoader.load()
                val engine = createNativeEngine(8, 8)
                try {
                    engine.call(EngineOperation.SAVE)
                } finally {
                    engine.close()
                }
            }
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            for (scale in listOf(1f, 2f)) {
                withSession(
                    project,
                    Preferences(
                        appearance = appearance,
                        workspaceAppearance =
                            WorkspaceAppearance(
                                toolDock = ToolDockPosition.Top,
                                scale = scale,
                                reducedMotion = appearance == Appearance.Light,
                            ),
                    ),
                ) {
                    val document = controller.document
                    val frame = controller.frame
                    assertFalse(document.canUndo)
                    for (tool in listOf(Tool.Brush, Tool.Eraser)) {
                        selectTool(tool)
                        waitFor { controller.tool == tool }
                        for (entry in listOf("大小", "不透明度")) {
                            revealDockControl(entry)
                            val before =
                                if (entry == "大小") controller.brush.size
                                else controller.brush.opacity
                            click(entry)
                            dragParameter(if (tool == Tool.Brush) 0.2f else 0.7f)
                            withContext(Dispatchers.Main) {
                                val changed =
                                    if (entry == "大小") controller.brush.size
                                    else controller.brush.opacity
                                val progress =
                                    slider().config[SemanticsProperties.ProgressBarRangeInfo]
                                assertNotEquals(before, changed, "$tool/$entry/$appearance/$scale")
                                assertEquals(changed, progress.current, 0.0001f)
                                assertEquals(
                                    if (entry == "大小")
                                        StudioDefaults.minBrushSize..StudioDefaults.maxBrushSize
                                    else 0.01f..1f,
                                    progress.range,
                                )
                                assertTrue(changed in progress.range)
                                assertEquals(document, controller.document)
                                assertSame(frame, controller.frame)
                                assertFalse(controller.hasUnsavedChanges)
                            }
                            dismissParameter()
                        }
                    }
                    selectTool(Tool.LineGenerator)
                    waitFor { controller.lineGeneratorPreview?.updating == false }
                    revealDockControl("线条类型")
                    click("线条类型")
                    click(LineGeneratorKind.Speed.label)
                    waitFor {
                        controller.lineGeneratorPreview?.let {
                            it.settings.kind == LineGeneratorKind.Speed && !it.updating
                        } == true
                    }
                    for (entry in listOf("线条数量", "线宽")) {
                        revealDockControl(entry)
                        val preview = assertNotNull(controller.lineGeneratorPreview)
                        val beforeFrame = preview.frame
                        val before =
                            if (entry == "线条数量") preview.settings.count.toFloat()
                            else preview.settings.stroke.width
                        click(entry)
                        dragParameter(if (entry == "线条数量") 0.25f else 0.7f)
                        waitFor { controller.lineGeneratorPreview?.updating == false }
                        withContext(Dispatchers.Main) {
                            val changed =
                                if (entry == "线条数量") preview.settings.count.toFloat()
                                else preview.settings.stroke.width
                            assertNotEquals(before, changed, "$entry/$appearance/$scale")
                            assertEquals(
                                changed,
                                slider().config[SemanticsProperties.ProgressBarRangeInfo].current,
                                0.0001f,
                            )
                            assertNull(preview.error)
                            assertEquals(preview.request(), preview.renderedAction)
                            assertNotSame(beforeFrame, preview.frame)
                            assertNotSame(frame, preview.frame)
                            assertTrue(preview.frame.tiles.isNotEmpty())
                            assertSame(frame, controller.frame)
                            assertEquals(document, controller.document)
                            assertFalse(controller.hasUnsavedChanges)
                        }
                        dismissParameter()
                    }
                    click("取消线条")
                    waitFor { controller.lineGeneratorPreview == null }
                    withContext(Dispatchers.Main) {
                        assertSame(frame, controller.frame)
                        assertEquals(document, controller.document)
                        assertFalse(controller.document.canUndo)
                        assertFalse(controller.hasUnsavedChanges)
                        assertFalse(scene.hasInvalidations())
                    }
                    assertContentEquals(project, save())
                }
            }
        }
    }

    private suspend fun nativeFrame(project: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            createNativeEngine(1, 1).let { engine ->
                try {
                    engine.call(EngineOperation.LOAD, project)
                    engine.call(EngineOperation.FRAME, byteArrayOf(1)).also { packet ->
                        fun intAt(offset: Int) =
                            (0..3).fold(0) { value, index ->
                                value or ((packet[offset + index].toInt() and 255) shl (index * 8))
                            }
                        assertEquals(8, intAt(0))
                        assertEquals(8, intAt(4))
                        assertTrue(intAt(12) > 0)
                    }
                } finally {
                    engine.close()
                }
            }
        }

    private class MemoryFiles(val project: ByteArray, val preferences: Preferences) : ProjectFiles {
        val saved = AtomicReference<ByteArray>()
        val saves = AtomicInteger()

        override suspend fun open() = project.copyOf()

        override suspend fun save(bytes: ByteArray, png: Boolean): Boolean {
            saved.set(bytes.copyOf())
            saves.incrementAndGet()
            return false
        }

        override suspend fun readPreferences() =
            Json.encodeToString(preferences).encodeToByteArray()
    }

    private class Session(
        val controller: StudioController,
        val scene: ImageComposeScene,
        val files: MemoryFiles,
    ) {
        private var frame = 1L

        fun render() = scene.render(frame++ * 16_666_667L)

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        fun nodes() =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun label(node: SemanticsNode) =
            node.config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.singleOrNull()
                ?.substringBefore(" ·")

        fun button(name: String) =
            nodes().single {
                it.config.contains(SemanticsActions.OnClick) &&
                    label(it) == name &&
                    !it.boundsInWindow.isEmpty
            }

        private fun capsule() =
            nodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "capsule-slider-popup"
            }

        fun slider() =
            descendants(capsule()).single {
                it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
            }

        fun opacity(tool: Tool) =
            if (tool == Tool.Gradient) controller.gradient.opacity else controller.brush.opacity

        fun pixels(): IntArray =
            render().use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                IntArray(400 * 1200) { pixels[it % 400, it / 400].toArgb() }
            }

        fun assertCapsule(baseline: IntArray, anchor: Rect, density: Density) {
            val bounds = capsule().boundsInWindow
            val padding = with(density) { StudioTheme.quickShadow.roundToPx() }
            val width =
                minOf(400, with(density) { StudioTheme.quickControlsWidth.roundToPx() }) -
                    padding * 2
            assertEquals(width.toFloat(), bounds.width, 0.01f)
            assertEquals(
                with(density) { StudioTheme.quickControlHeight.roundToPx() }.toFloat(),
                bounds.height,
                0.01f,
            )
            assertTrue(bounds.left >= padding && bounds.right <= 400 - padding)
            assertTrue(bounds.top >= padding && bounds.bottom <= 1200 - padding)
            assertTrue(
                bounds.bottom <=
                    anchor.top - with(density) { StudioTheme.workspacePadding.roundToPx() }
            )
            assertEquals(2, scene.semanticsOwners.size)
            val image = pixels()
            val panel = StudioTheme.panel.toArgb()
            val inset = with(density) { 2.dp.roundToPx() }
            assertEquals(
                panel,
                image[(bounds.top.roundToInt() + inset) * 400 + bounds.center.x.roundToInt()],
            )
            fun distance(first: Int, second: Int) =
                listOf(0, 8, 16).sumOf {
                    abs((first shr it and 255) - (second shr it and 255))
                }
            for (x in listOf(bounds.left.roundToInt() + inset, bounds.right.roundToInt() - inset)) {
                for (y in
                    listOf(bounds.top.roundToInt() + inset, bounds.bottom.roundToInt() - inset)) {
                    val pixel = image[y * 400 + x]
                    val background = baseline[y * 400 + x]
                    assertNotEquals(panel, background)
                    assertTrue(
                        distance(pixel, background) < distance(pixel, panel),
                        "Opaque rectangular popup corner at $x,$y",
                    )
                }
            }
            assertFalse(scene.hasInvalidations())
        }

        suspend fun pointer(type: PointerEventType, point: Offset) {
            withContext(Dispatchers.Main) {
                scene.sendPointerEvent(type, point)
                render().close()
            }
        }

        suspend fun click(name: String) {
            val point =
                withContext(Dispatchers.Main) {
                    val node = button(name)
                    assertFalse(node.config.contains(SemanticsProperties.Disabled), name)
                    node.boundsInWindow.center.also {
                        assertTrue(Rect(0f, 0f, 400f, 1200f).contains(it), name)
                    }
                }
            pointer(PointerEventType.Press, point)
            pointer(PointerEventType.Release, point)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun selectTool(tool: Tool) {
            if (withContext(Dispatchers.Main) { controller.tool == tool }) return
            val visible =
                withContext(Dispatchers.Main) {
                    nodes().any {
                        it.config.contains(SemanticsActions.OnClick) &&
                            label(it) == tool.label &&
                            !it.boundsInWindow.isEmpty
                    }
                }
            if (!visible) click("更多工具")
            click(tool.label)
        }

        suspend fun scrollContextToEnd(name: String) {
            withContext(Dispatchers.Main) {
                val dock =
                    nodes().single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "context-tool-dock"
                    }
                val scroll =
                    descendants(dock).single {
                        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                    }
                scene.sendPointerEvent(
                    PointerEventType.Scroll,
                    scroll.boundsInWindow.center,
                    scrollDelta = Offset(100f, 0f),
                )
                render().close()
            }
            settle()
            withContext(Dispatchers.Main) {
                val bounds = button(name).boundsInWindow
                assertEquals(
                    controller.preferences.workspaceAppearance.scale *
                        StudioTheme.controlSize.value,
                    bounds.width,
                    0.01f,
                )
            }
        }

        suspend fun dragSlider() {
            val bounds = withContext(Dispatchers.Main) { slider().boundsInWindow }
            pointer(PointerEventType.Press, bounds.center)
            pointer(
                PointerEventType.Move,
                Offset(bounds.left + bounds.width * 0.3f, bounds.center.y),
            )
            pointer(
                PointerEventType.Release,
                Offset(bounds.left + bounds.width * 0.3f, bounds.center.y),
            )
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun revealDockControl(name: String) {
            repeat(100) {
                val request =
                    withContext(Dispatchers.Main) {
                        val dock =
                            nodes().single {
                                it.config.getOrNull(SemanticsProperties.TestTag) ==
                                    "context-tool-dock"
                            }
                        assertTrue(dock.boundsInWindow.top > 600f)
                        val scroll =
                            descendants(dock).single {
                                it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                            }
                        val target =
                            descendants(scroll).single {
                                it.config.contains(SemanticsActions.OnClick) && label(it) == name
                            }
                        if (target.boundsInWindow.width == target.size.width.toFloat()) null
                        else
                            scroll.boundsInWindow.center to
                                if (target.positionInWindow.x < scroll.boundsInWindow.left) -0.125f
                                else 0.125f
                    }
                if (request == null) return
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
            error("Horizontal pointer scroll did not expose $name")
        }

        suspend fun dragParameter(fraction: Float) {
            val bounds = withContext(Dispatchers.Main) { slider().boundsInWindow }
            val target = Offset(bounds.left + bounds.width * fraction, bounds.center.y)
            pointer(PointerEventType.Press, bounds.center)
            pointer(PointerEventType.Move, target)
            pointer(PointerEventType.Release, target)
            pointer(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun dismissParameter() {
            pointer(PointerEventType.Press, Offset(1f, 1f))
            pointer(PointerEventType.Release, Offset(1f, 1f))
            settle()
            withContext(Dispatchers.Main) { assertEquals(1, scene.semanticsOwners.size) }
        }

        suspend fun settle() {
            repeat(60) {
                withContext(Dispatchers.Main) { render().close() }
                delay(2)
            }
        }

        suspend fun waitFor(predicate: () -> Boolean) =
            withTimeout(15_000) {
                while (
                    !withContext(Dispatchers.Main) {
                        render().close()
                        assertNull(controller.error)
                        predicate() &&
                            !controller.busy &&
                            controller.previews.revision == controller.document.revision
                    }
                ) delay(5)
            }

        suspend fun save(): ByteArray {
            val count = files.saves.get()
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
            waitFor { files.saves.get() == count + 1 }
            return assertNotNull(files.saved.get()).copyOf()
        }
    }

    private suspend fun withSession(
        project: ByteArray,
        preferences: Preferences,
        block: suspend Session.() -> Unit,
    ) {
        val original = StudioTheme.appearance
        val originalWorkspace =
            WorkspaceAppearance(
                density = StudioTheme.interfaceDensity,
                reducedMotion = StudioMotion.reducedMotion,
            )
        val files = MemoryFiles(project, preferences)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) { StudioController(files, scope) }
        val scene =
            withContext(Dispatchers.Main) {
                ImageComposeScene(400, 1200, density = Density(1f)) { StudioApp(controller) }
            }
        val session = Session(controller, scene, files)
        try {
            session.waitFor { controller.ready }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Open) }
            session.waitFor { controller.hasCanvas && controller.document.width == 8 }
            session.settle()
            session.block()
        } finally {
            withContext(Dispatchers.Main) {
                scene.close()
                controller.shutdown()
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
            scope.cancel()
        }
    }
}
