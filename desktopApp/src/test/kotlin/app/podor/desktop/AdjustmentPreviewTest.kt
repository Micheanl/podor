package app.podor.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import app.podor.data.ProjectFiles
import app.podor.desktop.engine.NativeLoader
import app.podor.domain.*
import app.podor.engine.*
import app.podor.presentation.StudioController
import app.podor.ui.*
import java.awt.EventQueue
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
            assertFalse(EventQueue.isDispatchThread())
            saved = bytes
            return true
        }
    }

    private suspend fun nativePixels(bytes: ByteArray, width: Int, height: Int): IntArray =
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            val engine = createNativeEngine(1, 1)
            fun intAt(value: ByteArray, offset: Int): Int =
                (0..3).fold(0) { result, index ->
                    result or ((value[offset + index].toInt() and 255) shl (index * 8))
                }
            try {
                engine.call(EngineOperation.LOAD, bytes)
                val frame = engine.call(EngineOperation.FRAME, byteArrayOf(1))
                assertEquals(width, intAt(frame, 0))
                assertEquals(height, intAt(frame, 4))
                val tile = intAt(frame, 8)
                var offset = 16
                IntArray(width * height).also { output ->
                    repeat(intAt(frame, 12)) {
                        val left = intAt(frame, offset) * tile
                        val top = intAt(frame, offset + 4) * tile
                        for (y in 0 until minOf(tile, height - top)) for (x in
                            0 until minOf(tile, width - left)) {
                            val source = offset + 8 + (y * tile + x) * 4
                            output[(top + y) * width + left + x] =
                                ((frame[source + 3].toInt() and 255) shl 24) or
                                    ((frame[source].toInt() and 255) shl 16) or
                                    ((frame[source + 1].toInt() and 255) shl 8) or
                                    (frame[source + 2].toInt() and 255)
                        }
                        offset += 8 + tile * tile * 4
                    }
                    assertEquals(frame.size, offset)
                }
            } finally {
                engine.close()
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

        fun click(label: String, settleAfter: Boolean = true) {
            val translated = trValue(label, controller.preferences.language)
            val target = reveal {
                nodes()
                    .filter {
                        it.config.contains(SemanticsActions.OnClick) &&
                            descendants(it).any { child -> matches(child, translated) }
                    }
                    .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
            }
            assertFalse(target.config.contains(SemanticsProperties.Disabled), "$label is disabled")
            val point = target.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            if (settleAfter) settle()
        }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        private fun nodes(): Sequence<SemanticsNode> =
            scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }

        private fun matches(node: SemanticsNode, label: String): Boolean =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) ==
                true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

        fun assertInlineAdjustment(ownerCount: Int) {
            assertEquals(ownerCount, scene.semanticsOwners.size, "Adjustment opened a dialog")
            val label = trValue("调整", controller.preferences.language)
            val tab =
                nodes().single {
                    it.config.contains(SemanticsActions.OnClick) && matches(it, label)
                }
            assertTrue(
                tab.config.getOrNull(SemanticsProperties.Selected) == true,
                "Adjustment preview left the Adjustments panel",
            )
        }

        private fun reveal(find: () -> SemanticsNode): SemanticsNode {
            repeat(24) {
                val target = find()
                val containers =
                    nodes()
                        .filter {
                            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                                descendants(it).any { child -> child.id == target.id }
                        }
                        .toList()
                if (containers.isEmpty()) return target
                assertEquals(1, containers.size, "Adjustment control has nested scrolling")
                val viewport = containers.single().boundsInWindow
                val top = target.positionInWindow.y
                val bottom = top + target.size.height
                if (
                    !target.boundsInWindow.isEmpty &&
                        top >= viewport.top - 1f &&
                        bottom <= viewport.bottom + 1f
                )
                    return target
                scene.sendPointerEvent(
                    PointerEventType.Scroll,
                    viewport.center,
                    scrollDelta = Offset(0f, if (top < viewport.top) -4f else 4f),
                )
                settle()
            }
            error("Adjustment control did not scroll fully into view")
        }

        fun canvasBounds(): Rect =
            scene.semanticsOwners
                .asSequence()
                .flatMap { descendants(it.rootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "canvas-workspace" }
                .boundsInWindow

        fun curvePoint(point: CurvePoint): Offset {
            val label = trValue("曲线编辑器", controller.preferences.language)
            val graph = reveal { nodes().single { matches(it, label) } }.boundsInWindow
            val inset = with(Density(1f)) { StudioTheme.curveInset.toPx() }
            return graph.topLeft +
                Offset(
                    inset + (graph.width - inset * 2) * point.x / 255f,
                    inset + (graph.height - inset * 2) * (1 - point.y / 255f),
                )
        }

        fun clickCurve(point: CurvePoint) = curvePoint(point).let { click(it.x, it.y) }

        fun slider(label: String, fraction: Float) {
            val translated = trValue(label, controller.preferences.language)
            val bounds = reveal {
                nodes().single {
                    it.config.contains(SemanticsActions.SetProgress) && matches(it, translated)
                }
            }
                .boundsInWindow
            click(bounds.left + bounds.width * fraction, bounds.center.y)
        }

        fun key(key: Key) {
            assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            render().close()
        }

        fun shortcut(action: ShortcutAction) {
            val binding = controller.preferences.shortcut(action)
            val key =
                when (binding.key) {
                    "Z" -> Key.Z
                    "Y" -> Key.Y
                    else -> error("Unexpected history shortcut")
                }
            val meta = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
            assertTrue(
                scene.sendKeyEvent(
                    KeyEvent(
                        key,
                        KeyEventType.KeyDown,
                        isCtrlPressed = binding.command && !meta,
                        isMetaPressed = binding.command && meta,
                        isShiftPressed = binding.shift,
                        isAltPressed = binding.alt,
                    )
                )
            )
            scene.sendKeyEvent(
                KeyEvent(
                    key,
                    KeyEventType.KeyUp,
                    isCtrlPressed = binding.command && !meta,
                    isMetaPressed = binding.command && meta,
                    isShiftPressed = binding.shift,
                    isAltPressed = binding.alt,
                )
            )
            settle()
        }

        fun stroke(start: Offset, end: Offset) {
            val bounds = canvasBounds()
            fun view(point: Offset) =
                controller.viewport.toView(point, bounds.size, controller.document) + bounds.topLeft
            val first = view(start)
            val last = view(end)
            scene.sendPointerEvent(PointerEventType.Press, first)
            for (step in 1..12) scene.sendPointerEvent(
                PointerEventType.Move,
                first + (last - first) * (step / 12f),
            )
            scene.sendPointerEvent(PointerEventType.Release, last)
            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            settle()
        }

        suspend fun number(label: String, value: String) {
            val translated = trValue(label, controller.preferences.language)
            val bounds = reveal {
                nodes()
                    .filter {
                        it.config.contains(SemanticsActions.SetText) &&
                            descendants(it).any { child ->
                                child.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                                    text.text == translated
                                } == true
                            }
                    }
                    .minBy { it.boundsInWindow.width * it.boundsInWindow.height }
            }
                .boundsInWindow
            click(bounds.center.x, bounds.center.y)
            delay(20)
            val meta = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
            scene.sendKeyEvent(
                KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = !meta, isMetaPressed = meta)
            )
            scene.sendKeyEvent(
                KeyEvent(Key.A, KeyEventType.KeyUp, isCtrlPressed = !meta, isMetaPressed = meta)
            )
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

        fun pixel() =
            render().use {
                val point = canvasBounds().center
                it.toComposeImageBitmap().toPixelMap()[point.x.toInt(), point.y.toInt()]
            }

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
            withContext(Dispatchers.Main) {
                ImageComposeScene(1360, 900, density = Density(1f)) { StudioApp(controller) }
            }
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
    fun switchingInlineKindsAndUndoWithoutHistoryDiscardRenderedAndQueuedPreviews() = runBlocking {
        session {
            val originalFrame = withContext(Dispatchers.Main) { controller.frame }
            val originalPixels = nativePixels(files.bytes, 256, 192)
            withContext(Dispatchers.Main) {
                click("调整")
                click("明暗与色彩")
            }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Tone &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) { slider("亮度", 0.85f) }
            waitFor {
                controller.adjustmentPreview!!.changed && !controller.adjustmentPreview!!.updating
            }
            for (kind in
                listOf(AdjustmentKind.Blur, AdjustmentKind.Curves, AdjustmentKind.GradientMap)) {
                withContext(Dispatchers.Main) { click(kind.label) }
                waitFor {
                    controller.adjustmentPreview?.settings?.kind == kind &&
                        !controller.adjustmentPreview!!.updating
                }
                withContext(Dispatchers.Main) {
                    assertSame(originalFrame, controller.frame)
                    assertFalse(controller.document.canUndo)
                    assertFalse(controller.hasUnsavedChanges)
                    assertEquals(Tool.Brush, controller.tool)
                }
            }
            withContext(Dispatchers.Main) {
                shortcut(ShortcutAction.Undo)
                assertNull(controller.adjustmentPreview)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.document.canRedo)
                assertSame(originalFrame, controller.frame)
                click("曲线", settleAfter = false)
                shortcut(ShortcutAction.Undo)
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            withContext(Dispatchers.Main) {
                assertNull(controller.adjustmentPreview)
                assertFalse(controller.document.canUndo)
                assertFalse(controller.hasUnsavedChanges)
                assertEquals(Tool.Brush, controller.tool)
            }
            assertContentEquals(files.bytes, files.saved)
            assertContentEquals(originalPixels, nativePixels(files.saved!!, 256, 192))
            withContext(Dispatchers.Main) { click("明暗与色彩") }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Tone &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                click(
                    trValue(ShortcutAction.Brush.label, controller.preferences.language) +
                        " · " +
                        controller.preferences.shortcut(ShortcutAction.Brush).display()
                )
                assertNull(controller.adjustmentPreview)
                assertEquals(Tool.Brush, controller.tool)
                assertFalse(controller.document.canUndo)
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            assertContentEquals(files.bytes, files.saved)
        }
    }

    @Test
    fun inlineSizeEditorsRebaseAfterHistoryChangesInsteadOfSubmittingAStaleRevision() =
        runBlocking {
            session {
                val source = nativePixels(files.bytes, 256, 192)
                for (section in listOf("画布大小", "图像尺寸")) {
                    withContext(Dispatchers.Main) { controller.command("add_layer") }
                    waitFor {
                        controller.document.layers.size == 3 &&
                            controller.document.canUndo &&
                            !controller.busy
                    }
                    withContext(Dispatchers.Main) {
                        click("调整")
                        click(section)
                        shortcut(ShortcutAction.Undo)
                    }
                    waitFor {
                        controller.document.layers.size == 2 &&
                            !controller.document.canUndo &&
                            !controller.busy
                    }
                    withContext(Dispatchers.Main) {
                        if (section == "画布大小") number("宽度", "320") else click("50%")
                        click("应用")
                    }
                    val width = if (section == "画布大小") 320 else 128
                    waitFor {
                        controller.document.width == width &&
                            controller.document.canUndo &&
                            !controller.busy
                    }
                    withContext(Dispatchers.Main) { shortcut(ShortcutAction.Undo) }
                    waitFor {
                        controller.document.width == 256 &&
                            !controller.document.canUndo &&
                            !controller.busy
                    }
                    withContext(Dispatchers.Main) {
                        files.saved = null
                        controller.file(StudioController.FileAction.Save)
                    }
                    waitFor { files.saved != null && !controller.busy }
                    assertContentEquals(files.bytes, files.saved)
                    assertContentEquals(source, nativePixels(files.saved!!, 256, 192))
                    withContext(Dispatchers.Main) { click("图层") }
                }
            }
        }

    @Test
    fun paintingAndHistoryShortcutsExitAdjustmentsWithoutApplyingPreviewPixels() = runBlocking {
        session {
            val originalPixels = nativePixels(files.bytes, 256, 192)
            withContext(Dispatchers.Main) {
                controller.brush =
                    controller.brush.copy(color = 0xFFFFD020, size = 12f, opacity = 1f)
                click("调整")
                click("明暗与色彩")
            }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Tone &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) { slider("亮度", 0.85f) }
            waitFor {
                controller.adjustmentPreview!!.changed && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                assertEquals(Tool.Brush, controller.tool)
                stroke(Offset(64f, 96f), Offset(176f, 96f))
            }
            waitFor {
                controller.adjustmentPreview == null &&
                    !controller.drawingInput &&
                    controller.document.canUndo &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) { controller.file(StudioController.FileAction.Save) }
            waitFor { files.saved != null && !controller.busy }
            val paintedBytes = files.saved!!
            val painted = nativePixels(paintedBytes, 256, 192)
            assertTrue(
                painted.indices.any { painted[it] != originalPixels[it] },
                "Canvas stroke was not accepted",
            )
            for (y in 0 until 192) for (x in 0 until 256) {
                if (x !in 48..192 || y !in 80..112)
                    assertEquals(
                        originalPixels[y * 256 + x],
                        painted[y * 256 + x],
                        "Preview changed a pixel outside the brush stroke: $x,$y",
                    )
            }
            withContext(Dispatchers.Main) {
                click("明暗与色彩")
            }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Tone &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) { slider("亮度", 0.15f) }
            waitFor {
                controller.adjustmentPreview!!.changed && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                shortcut(ShortcutAction.Undo)
            }
            waitFor {
                controller.adjustmentPreview == null &&
                    !controller.document.canUndo &&
                    controller.document.canRedo &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            assertContentEquals(files.bytes, files.saved)
            assertContentEquals(originalPixels, nativePixels(files.saved!!, 256, 192))
            withContext(Dispatchers.Main) { click("曲线") }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Curves &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) { clickCurve(CurvePoint(128, 192)) }
            waitFor {
                controller.adjustmentPreview!!.changed && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) { shortcut(ShortcutAction.Redo) }
            waitFor {
                controller.adjustmentPreview == null &&
                    controller.document.canUndo &&
                    !controller.document.canRedo &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            assertContentEquals(paintedBytes, files.saved)
            assertContentEquals(painted, nativePixels(files.saved!!, 256, 192))
            withContext(Dispatchers.Main) {
                click("明暗与色彩")
            }
            waitFor {
                controller.adjustmentPreview?.settings?.kind == AdjustmentKind.Tone &&
                    !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                click(
                    trValue(ShortcutAction.Eraser.label, controller.preferences.language) +
                        " · " +
                        controller.preferences.shortcut(ShortcutAction.Eraser).display()
                )
                assertNull(controller.adjustmentPreview)
                assertEquals(Tool.Eraser, controller.tool)
                assertNull(controller.error)
            }
        }
    }

    @Test
    fun inlineSizeEditorsCancelWithoutChangesAndResizeEveryPixelWithTwoUndoSteps() = runBlocking {
        session {
            val originalDocument = withContext(Dispatchers.Main) { controller.document }
            val originalFrame = withContext(Dispatchers.Main) { controller.frame }
            val originalPixels = nativePixels(files.bytes, 256, 192)
            val ownerCount =
                withContext(Dispatchers.Main) {
                    click("调整")
                    scene.semanticsOwners.size
                }
            withContext(Dispatchers.Main) {
                click("画布大小")
                assertEquals(ownerCount, scene.semanticsOwners.size, "Canvas size opened a dialog")
                number("宽度", "320")
                click(CanvasAnchor.TopLeft.label)
                assertEquals(originalDocument, controller.document)
                assertSame(originalFrame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                click("取消")
                assertEquals(originalDocument, controller.document)
                assertSame(originalFrame, controller.frame)
                assertNull(files.saved)
                click("画布大小")
                number("宽度", "320")
                click(CanvasAnchor.TopLeft.label)
                capture("canvas-size-inline")
                click("应用")
            }
            waitFor { controller.document.width == 320 && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(192, controller.document.height)
                assertTrue(controller.document.canUndo)
                click("图像尺寸")
                assertEquals(ownerCount, scene.semanticsOwners.size, "Image size opened a dialog")
                click("50%")
                click(ResampleFilter.Nearest.label)
                assertEquals(320, controller.document.width)
                assertEquals(192, controller.document.height)
                capture("image-size-inline")
                click("应用")
            }
            waitFor {
                controller.document.width == 160 &&
                    controller.document.height == 96 &&
                    !controller.busy
            }
            withContext(Dispatchers.Main) {
                assertEquals(originalDocument.layers.size, controller.document.layers.size)
                assertTrue(controller.hasUnsavedChanges)
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            val expected =
                IntArray(160 * 96) { index ->
                    val x = index % 160
                    val y = index / 160
                    if (x < 128) originalPixels[(y * 2 + 1) * 256 + x * 2 + 1] else 0
                }
            assertContentEquals(expected, nativePixels(files.saved!!, 160, 96))
            val undoLabel =
                withContext(Dispatchers.Main) {
                    "${trValue(ShortcutAction.Undo.label, controller.preferences.language)} · ${controller.preferences.shortcut(ShortcutAction.Undo).display()}"
                }
            withContext(Dispatchers.Main) { click(undoLabel) }
            waitFor { controller.document.width == 320 && !controller.busy }
            withContext(Dispatchers.Main) {
                assertEquals(192, controller.document.height)
                assertTrue(controller.document.canUndo)
                click(undoLabel)
            }
            waitFor {
                controller.document.width == 256 && !controller.document.canUndo && !controller.busy
            }
            withContext(Dispatchers.Main) {
                assertEquals(192, controller.document.height)
                assertTrue(controller.document.canRedo)
                files.saved = null
                controller.file(StudioController.FileAction.Save)
            }
            waitFor { files.saved != null && !controller.busy }
            assertContentEquals(files.bytes, files.saved)
            assertContentEquals(originalPixels, nativePixels(files.saved!!, 256, 192))
        }
    }

    @Test
    fun curveEditorAddsMovesDeletesAndResetsPointsWithoutChangingTheOriginalUntilConfirmed() =
        runBlocking {
            session {
                val original = withContext(Dispatchers.Main) { pixel() }
                val source = withContext(Dispatchers.Main) { controller.frame }
                val ownerCount =
                    withContext(Dispatchers.Main) {
                        click("调整")
                        val count = scene.semanticsOwners.size
                        click("曲线")
                        count
                    }
                waitFor {
                    controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
                }
                withContext(Dispatchers.Main) {
                    settle()
                    assertInlineAdjustment(ownerCount)
                    capture("curves-neutral")
                    assertEquals(original, pixel())
                    assertFalse(controller.adjustmentPreview!!.changed)
                    assertEquals(4, controller.adjustmentPreview!!.histogram.size)
                    clickCurve(CurvePoint(128, 128))
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
                    val start =
                        curvePoint(controller.adjustmentPreview!!.settings.curves.rgb.points[1])
                    val end = curvePoint(CurvePoint(148, 174))
                    scene.sendPointerEvent(PointerEventType.Press, start)
                    scene.sendPointerEvent(PointerEventType.Move, end)
                    scene.sendPointerEvent(PointerEventType.Release, end)
                    settle()
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertNotEquals(original, pixel())
                    assertSame(source, controller.frame)
                    assertFalse(controller.hasUnsavedChanges)
                    number("输出", "180")
                    capture("curves-number")
                    assertEquals(
                        180,
                        controller.adjustmentPreview!!.settings.curves.rgb.points[1].y,
                    )
                    clickCurve(controller.adjustmentPreview!!.settings.curves.rgb.points[1])
                    capture("curves-preview")
                    key(Key.Delete)
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertEquals(ToneCurve(), controller.adjustmentPreview!!.settings.curves.rgb)
                    assertEquals(original, pixel())
                    number("输出", "999")
                    assertFalse(controller.adjustmentPreview!!.inputValid)
                    key(Key.Enter)
                    assertNotNull(controller.adjustmentPreview)
                    click("重置调整")
                }
                waitFor { !controller.adjustmentPreview!!.updating }
                withContext(Dispatchers.Main) {
                    assertTrue(controller.adjustmentPreview!!.inputValid)
                    assertEquals(ColorCurves(), controller.adjustmentPreview!!.settings.curves)
                    click(CurveChannel.Red.label)
                    clickCurve(CurvePoint(128, 176))
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
                    click("原图对比")
                    assertEquals(original, pixel())
                    click("原图对比")
                    click("确认调整")
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
                click("图层")
                click(
                    "${trValue(LayerBlendMode.Normal.label, controller.preferences.language)} · 80%"
                )
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
                click("确认调整")
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
                click("图层")
                capture("layer-blend-entry")
                click(
                    "${trValue(LayerBlendMode.Normal.label, controller.preferences.language)} · 80%"
                )
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
                click(LayerBlendMode.Multiply.label)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertEquals(LayerBlendMode.Multiply, controller.adjustmentPreview!!.settings.blend)
                slider("图层不透明度", 0.3f)
            }
            waitFor { !controller.adjustmentPreview!!.updating }
            withContext(Dispatchers.Main) {
                assertTrue(controller.adjustmentPreview!!.settings.opacity < 0.7f)
                assertNotEquals(original, pixel())
                assertEquals(document, controller.document)
                assertSame(originalFrame, controller.frame)
                assertFalse(controller.hasUnsavedChanges)
                val settings = controller.adjustmentPreview!!.settings
                click("收起面板")
                click("展开面板")
                assertEquals(settings, controller.adjustmentPreview!!.settings)
                click("调整")
                assertNotEquals(original, pixel())
                click("图层")
                capture("layer-blend-preview")
                click("原图对比")
                assertTrue(controller.adjustmentPreview!!.comparing)
                assertEquals(original, pixel())
                click("重置调整")
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
                    click("确认调整")
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
            val ownerCount =
                withContext(Dispatchers.Main) {
                    click("调整")
                    capture("adjustments-menu")
                    val count = scene.semanticsOwners.size
                    click("明暗与色彩")
                    count
                }
            waitFor {
                controller.adjustmentPreview != null && !controller.adjustmentPreview!!.updating
            }
            withContext(Dispatchers.Main) {
                assertInlineAdjustment(ownerCount)
                capture("adjustment-neutral")
                slider("亮度", 0.85f)
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
                click("原图对比")
                assertTrue(controller.adjustmentPreview!!.comparing)
                assertEquals(original, pixel())
                click("原图对比")
                assertNotEquals(original, pixel())
                click("重置调整")
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
                    click("确认调整")
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
