package app.podor.presentation

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import app.podor.data.ProjectFiles
import app.podor.domain.*
import app.podor.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

data class TileImage(val x: Int, val y: Int, val size: Int, val image: ImageBitmap)

data class RenderFrame(val tiles: Map<Long, TileImage> = emptyMap())

data class RenderPreviews(val revision: Long = -1, val images: Map<Int, ImageBitmap> = emptyMap())

class StudioController(
    private val files: ProjectFiles,
    parentScope: CoroutineScope,
    private val installUpdate: suspend (AppRelease, String) -> Unit = { _, _ ->
        error("当前平台不支持安装更新")
    },
) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
        )
    private val actions = Channel<Action>(StudioDefaults.inputQueueCapacity)
    private val parser = Json { ignoreUnknownKeys = true }
    var document by mutableStateOf(DocumentInfo())
        private set

    var frame by mutableStateOf(RenderFrame())
        private set

    var previews by mutableStateOf(RenderPreviews())
        private set

    var layerMove by mutableStateOf<LayerMovePreview?>(null)
        private set

    private var preparingLayerMove = false
    private var currentGradient by mutableStateOf(GradientSettings())
    var gradient: GradientSettings
        get() = currentGradient
        set(value) {
            if (gradientPreview?.committing != true) currentGradient = value
        }

    var gradientEditingStart by mutableStateOf(true)
    var gradientPreview by mutableStateOf<GradientPreview?>(null)
        private set

    private var preparingGradient = false

    val exportFormats = files.exportFormats
    val clipboardAvailable = files.clipboard != null

    var brush by mutableStateOf(BrushSettings())
    var smudgeStrength by mutableStateOf(StudioDefaults.smudgeStrength)
    var preferences by mutableStateOf(Preferences())
        private set

    val brushes: List<BrushPreset> by derivedStateOf {
        BrushPreset.entries +
            preferences.brushes +
            preferences.plugins
                .filter { it.enabled }
                .flatMap { pack -> pack.brushes.map { it.copy(id = "plugin:${pack.id}/${it.id}") } }
    }

    private var currentTool by mutableStateOf(Tool.Brush)
    var tool: Tool
        get() = currentTool
        set(value) {
            if (value != currentTool && previewPending()) return
            if (value == Tool.Gradient && currentTool != value) {
                gradient = gradient.copy(from = brush.color)
                gradientEditingStart = true
            }
            currentTool = value
        }

    var selectionKind by mutableStateOf(StudioDefaults.selectionKind)
    var selectionCancellation by mutableIntStateOf(0)
        private set
    var viewport by mutableStateOf(Viewport())
    var fingerDrawing by mutableStateOf(true)
    var fillTolerance by mutableStateOf(24f)
    var status by mutableStateOf("准备画布…")
        private set

    var ready by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var showWorkspace by mutableStateOf(true)
        private set

    var hasCanvas by mutableStateOf(false)
        private set

    var hasUnsavedChanges by mutableStateOf(false)
        private set

    var projectReference by mutableStateOf<ProjectReference?>(null)
        private set

    var recentProjects by mutableStateOf<List<RecentProject>>(emptyList())
        private set

    var pendingNavigation by mutableStateOf<WorkspaceDestination?>(null)
        private set

    var exitRequested by mutableStateOf(false)
        private set

    private var worker: Job? = null
    private val producers = mutableListOf<Job>()

    private sealed interface Action {
        data class Command(val json: JsonObject) : Action

        data class Points(val values: List<Triple<Float, Float, Float>>) : Action

        data class File(val kind: FileAction) : Action

        data class Export(val options: ExportOptions) : Action

        data class Clipboard(val kind: ClipboardAction) : Action

        data object Frame : Action

        data object PrepareGradient : Action

        data class ApplyGradient(
            val id: Int,
            val revision: Long,
            val line: GradientLine,
            val settings: GradientSettings,
        ) : Action

        data class PrepareLayerMove(val transform: Boolean) : Action

        data class TransformLayer(val id: Int, val revision: Long, val value: LayerTransform) :
            Action

        data class TranslateLayer(val id: Int, val revision: Long, val offset: IntOffset) : Action

        data class Navigate(
            val destination: WorkspaceDestination,
            val choice: UnsavedChoice? = null,
        ) : Action

        data class Forget(val reference: ProjectReference) : Action

        data class Settings(val value: Preferences) : Action

        data class Shutdown(val finished: CompletableDeferred<Boolean>) : Action
    }

    enum class FileAction {
        Open,
        Save,
        SaveAs,
        ImportBrushes,
        ImportLayer,
        ExportBrushes,
    }

    init {
        worker =
            scope.launch(Dispatchers.Default) {
                var engine: NativeEngine? = null
                var info = DocumentInfo()
                var drawing = false
                var frameDirty = false
                var previewRevision = -1L
                var savedContentId = 0L
                var currentReference: ProjectReference? = null
                val tiles = mutableMapOf<Long, TileImage>()
                var frameWidth = 0
                var frameHeight = 0
                val pending = ArrayList<Triple<Float, Float, Float>>(StudioDefaults.maxBatchSamples)
                fun command(value: JsonObject): DocumentInfo {
                    val result =
                        engine!!.call(EngineOperation.COMMAND, value.toString().encodeToByteArray())
                    return parser.decodeFromString(result.decodeToString())
                }
                fun flushPoints() {
                    if (pending.isEmpty()) return
                    val bytes = ByteArray(pending.size * 12)
                    pending.forEachIndexed { index, point ->
                        repeat(3) { component ->
                            val value =
                                when (component) {
                                    0 -> point.first
                                    1 -> point.second
                                    else -> point.third
                                }
                            val bits = value.toBits()
                            repeat(4) { byte ->
                                bytes[index * 12 + component * 4 + byte] =
                                    (bits ushr (byte * 8)).toByte()
                            }
                        }
                    }
                    engine!!.call(EngineOperation.SAMPLES, bytes)
                    pending.clear()
                    frameDirty = true
                }
                fun readLayers(): List<LayerFrame> {
                    val bytes = engine!!.call(EngineOperation.LAYERS)
                    val size = bytes.intAt(8)
                    var position = 16
                    val layers = buildList {
                        repeat(bytes.intAt(12)) {
                            val id = bytes.intAt(position)
                            val count = bytes.intAt(position + 4)
                            position += 8
                            val images = buildList {
                                repeat(count) {
                                    add(
                                        TileImage(
                                            bytes.intAt(position),
                                            bytes.intAt(position + 4),
                                            size,
                                            rgbaBitmap(bytes, position + 8, size),
                                        )
                                    )
                                    position += 8 + size * size * 4
                                }
                            }
                            add(
                                LayerFrame(
                                    info.layers.first { it.id == id },
                                    images,
                                )
                            )
                        }
                    }
                    return layers
                }
                suspend fun publishFrame() {
                    val bytes = engine!!.call(EngineOperation.FRAME)
                    val width = bytes.intAt(0)
                    val height = bytes.intAt(4)
                    val resized = frameWidth != width || frameHeight != height
                    if (resized) {
                        tiles.clear()
                        frameWidth = width
                        frameHeight = height
                    }
                    val size = bytes.intAt(8)
                    val count = bytes.intAt(12)
                    var offset = 16
                    repeat(count) {
                        val x = bytes.intAt(offset)
                        val y = bytes.intAt(offset + 4)
                        val bitmap = rgbaBitmap(bytes, offset + 8, size)
                        tiles[(x.toLong() shl 32) or y.toLong()] = TileImage(x, y, size, bitmap)
                        offset += 8 + size * size * 4
                    }
                    val updated = if (count > 0 || resized) RenderFrame(tiles.toMap()) else null
                    withContext(Dispatchers.Main) {
                        if (resized) {
                            viewport = Viewport()
                            previews = RenderPreviews()
                        }
                        document = info
                        hasUnsavedChanges = info.contentId != savedContentId
                        if (updated != null) frame = updated
                        layerMove?.let {
                            if (it.revision != info.revision || it.layerId != info.active)
                                layerMove = null
                        }
                        gradientPreview?.let {
                            if (
                                it.revision != info.revision ||
                                    it.layerId != info.active ||
                                    it.selection != info.selection
                            )
                                gradientPreview = null
                        }
                    }
                    frameDirty = false
                }
                suspend fun finishDrawing() {
                    flushPoints()
                    if (drawing) {
                        info = command(jsonCommand("end"))
                        drawing = false
                    }
                    publishFrame()
                }
                suspend fun refreshRecent() {
                    val entries = files.recentProjects()
                    withContext(Dispatchers.Main) { recentProjects = entries }
                }
                suspend fun rememberCurrent() {
                    val reference = currentReference?.takeIf { it.id.isNotEmpty() } ?: return
                    try {
                        files.rememberProject(
                            reference,
                            info.width,
                            info.height,
                            engine!!.call(EngineOperation.THUMBNAIL),
                        )
                        refreshRecent()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { error = "作品已打开或保存，但作品列表更新失败" }
                    }
                }
                suspend fun saveCurrent(saveAs: Boolean = false): Boolean {
                    val reference =
                        files.saveDocument(
                            engine!!.call(EngineOperation.SAVE),
                            currentReference,
                            saveAs,
                        ) ?: return false
                    currentReference = reference
                    savedContentId = info.contentId
                    withContext(Dispatchers.Main) {
                        projectReference = reference
                        hasUnsavedChanges = false
                        status = "工程已保存"
                    }
                    rememberCurrent()
                    return true
                }
                suspend fun resetCanvas(reference: ProjectReference?) {
                    currentReference = reference
                    savedContentId = info.contentId
                    tiles.clear()
                    withContext(Dispatchers.Main) {
                        frame = RenderFrame()
                        previews = RenderPreviews()
                        viewport = Viewport()
                        projectReference = reference
                        hasCanvas = true
                        showWorkspace = false
                        status = if (reference == null) "画布已就绪" else "工程已打开"
                    }
                    publishFrame()
                }
                suspend fun openProject(reference: ProjectReference?) {
                    val opened = files.openDocument(reference) ?: return
                    info =
                        parser.decodeFromString(
                            engine!!.call(EngineOperation.LOAD, opened.bytes).decodeToString()
                        )
                    resetCanvas(opened.reference)
                    rememberCurrent()
                }
                try {
                    engine = createNativeEngine(info.width, info.height)
                    files.readPreferences()?.let { bytes ->
                        runCatching {
                            parser
                                .decodeFromString<Preferences>(bytes.decodeToString())
                                .withNewShortcuts()
                                .also {
                                    require(it.valid())
                                }
                        }
                            .onSuccess { value ->
                                withContext(Dispatchers.Main) { preferences = value }
                            }
                            .onFailure {
                                withContext(Dispatchers.Main) { error = "设置文件无法读取，已使用默认设置" }
                            }
                    }
                    info = command(jsonCommand("state"))
                    try {
                        refreshRecent()
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { error = "作品列表无法读取" }
                    }
                    publishFrame()
                    withContext(Dispatchers.Main) {
                        showWorkspace = preferences.startupScreen == StartupScreen.Workspace
                        hasCanvas = !showWorkspace
                        ready = true
                        status = "画布已就绪"
                    }
                    for (action in actions) {
                        try {
                            when (action) {
                                Action.PrepareGradient -> {
                                    finishDrawing()
                                    val layers = readLayers()
                                    val data = engine.call(EngineOperation.SELECTION_FRAME)
                                    val size = data.intAt(0)
                                    var offset = 8
                                    val mask = buildList {
                                        repeat(data.intAt(4)) {
                                            add(
                                                TileImage(
                                                    data.intAt(offset),
                                                    data.intAt(offset + 4),
                                                    size,
                                                    rgbaBitmap(data, offset + 8, size),
                                                )
                                            )
                                            offset += 8 + size * size * 4
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (tool == Tool.Gradient && document.active == info.active)
                                            gradientPreview =
                                                GradientPreview(
                                                    info.active,
                                                    info.revision,
                                                    info.selection,
                                                    layers,
                                                    mask,
                                                )
                                    }
                                }
                                is Action.ApplyGradient -> {
                                    info =
                                        command(
                                            jsonCommand("gradient") {
                                                put("id", action.id)
                                                put("revision", action.revision)
                                                putJsonObject("settings") {
                                                    putJsonArray("start") {
                                                        add(action.line.start.x)
                                                        add(action.line.start.y)
                                                    }
                                                    putJsonArray("end") {
                                                        add(action.line.end.x)
                                                        add(action.line.end.y)
                                                    }
                                                    fun color(name: String, value: Long) {
                                                        putJsonArray(name) {
                                                            add((value shr 16 and 255).toInt())
                                                            add((value shr 8 and 255).toInt())
                                                            add((value and 255).toInt())
                                                            add((value shr 24 and 255).toInt())
                                                        }
                                                    }
                                                    color("from", action.settings.startColor)
                                                    color("to", action.settings.endColor)
                                                    put("opacity", action.settings.opacity)
                                                    put(
                                                        "shape",
                                                        action.settings.shape.name.lowercase(),
                                                    )
                                                }
                                            }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) {
                                        gradientPreview = null
                                        tool = Tool.Brush
                                        status = "渐变已完成"
                                    }
                                }
                                is Action.Settings ->
                                    files.writePreferences(
                                        parser.encodeToString(action.value).encodeToByteArray()
                                    )
                                is Action.Shutdown -> {
                                    action.finished.complete(true)
                                    break
                                }
                                is Action.PrepareLayerMove -> {
                                    finishDrawing()
                                    val bounds =
                                        if (action.transform) {
                                            val data = engine.call(EngineOperation.LAYER_BOUNDS)
                                            Rect(
                                                data.intAt(0).toFloat(),
                                                data.intAt(4).toFloat(),
                                                data.intAt(8).toFloat(),
                                                data.intAt(12).toFloat(),
                                            )
                                        } else null
                                    val layers = readLayers()
                                    withContext(Dispatchers.Main) {
                                        if (
                                            tool ==
                                                (if (action.transform) Tool.TransformLayer
                                                else Tool.MoveLayer) &&
                                                document.active == info.active
                                        ) {
                                            layerMove =
                                                LayerMovePreview(
                                                    info.active,
                                                    info.revision,
                                                    layers,
                                                    bounds,
                                                )
                                        }
                                    }
                                }
                                is Action.TranslateLayer -> {
                                    check(info.revision == action.revision) { "图层已变化，请重新移动" }
                                    info =
                                        command(
                                            jsonCommand("translate_layer") {
                                                put("id", action.id)
                                                put("dx", action.offset.x)
                                                put("dy", action.offset.y)
                                            }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) { layerMove = null }
                                }
                                is Action.TransformLayer -> {
                                    info =
                                        command(
                                            jsonCommand("transform_layer") {
                                                put("id", action.id)
                                                put("revision", action.revision)
                                                put(
                                                    "transform",
                                                    Json.encodeToJsonElement(action.value),
                                                )
                                            }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) {
                                        layerMove = null
                                        tool = Tool.Brush
                                        status = "图层变换已完成"
                                    }
                                }
                                is Action.Forget -> {
                                    files.forgetProject(action.reference)
                                    refreshRecent()
                                }
                                is Action.Navigate -> {
                                    finishDrawing()
                                    if (action.choice == UnsavedChoice.Cancel) {
                                        withContext(Dispatchers.Main) { pendingNavigation = null }
                                    } else if (
                                        info.contentId != savedContentId && action.choice == null
                                    ) {
                                        withContext(Dispatchers.Main) {
                                            pendingNavigation = action.destination
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) { busy = true }
                                        if (action.choice == UnsavedChoice.Save && !saveCurrent())
                                            continue
                                        withContext(Dispatchers.Main) { pendingNavigation = null }
                                        when (val destination = action.destination) {
                                            is WorkspaceDestination.New -> {
                                                info =
                                                    command(
                                                        jsonCommand("new") {
                                                            put("width", destination.width)
                                                            put("height", destination.height)
                                                        }
                                                    )
                                                resetCanvas(null)
                                            }
                                            is WorkspaceDestination.Open ->
                                                openProject(destination.reference)
                                            WorkspaceDestination.Exit -> {
                                                withContext(Dispatchers.Main) {
                                                    ready = false
                                                    exitRequested = true
                                                }
                                                break
                                            }
                                            is WorkspaceDestination.InstallUpdate -> {
                                                installUpdate(
                                                    destination.release,
                                                    destination.installer,
                                                )
                                                withContext(Dispatchers.Main) {
                                                    ready = false
                                                    exitRequested = true
                                                }
                                                break
                                            }
                                        }
                                    }
                                }
                                is Action.Points -> {
                                    if (drawing) {
                                        pending.addAll(action.values)
                                        if (pending.size >= StudioDefaults.maxBatchSamples)
                                            flushPoints()
                                    }
                                }
                                is Action.Command -> {
                                    flushPoints()
                                    val type = action.json["type"]!!.jsonPrimitive.content
                                    if (type == "pick") {
                                        val picked =
                                            parser
                                                .parseToJsonElement(
                                                    engine
                                                        .call(
                                                            EngineOperation.COMMAND,
                                                            action.json
                                                                .toString()
                                                                .encodeToByteArray(),
                                                        )
                                                        .decodeToString()
                                                )
                                                .jsonObject["color"]!!
                                                .jsonArray
                                        val color =
                                            0xFF000000L or
                                                (picked[0].jsonPrimitive.long shl 16) or
                                                (picked[1].jsonPrimitive.long shl 8) or
                                                picked[2].jsonPrimitive.long
                                        withContext(Dispatchers.Main) {
                                            brush = brush.copy(color = color)
                                            tool = Tool.Brush
                                        }
                                    } else {
                                        if (
                                            type in
                                                setOf(
                                                    "fill",
                                                    "select_shape",
                                                    "tone",
                                                    "blur",
                                                    "merge_visible",
                                                    "resize_canvas",
                                                    "resize_image",
                                                )
                                        )
                                            withContext(Dispatchers.Main) { busy = true }
                                        info = command(action.json)
                                        if (type == "begin") drawing = true
                                        if (type == "end" || type == "cancel") drawing = false
                                        if (type == "new") {
                                            resetCanvas(null)
                                        }
                                        if (type != "begin") publishFrame()
                                    }
                                }
                                Action.Frame -> {
                                    if (pending.isNotEmpty() || frameDirty) {
                                        flushPoints()
                                        publishFrame()
                                    }
                                    if (!drawing && previewRevision != info.revision) {
                                        val bytes = engine.call(EngineOperation.PREVIEWS)
                                        if (bytes.isNotEmpty()) {
                                            val revision =
                                                (bytes.intAt(0).toLong() and 0xFFFFFFFFL) or
                                                    (bytes.intAt(4).toLong() shl 32)
                                            val size = bytes.intAt(8)
                                            val count = bytes.intAt(12)
                                            var offset = 16
                                            val images = buildMap {
                                                repeat(count) {
                                                    put(
                                                        bytes.intAt(offset),
                                                        rgbaBitmap(bytes, offset + 4, size),
                                                    )
                                                    offset += 4 + size * size * 4
                                                }
                                            }
                                            previewRevision = revision
                                            withContext(Dispatchers.Main) {
                                                previews = RenderPreviews(revision, images)
                                            }
                                        }
                                    }
                                }
                                is Action.Clipboard -> {
                                    val clipboard =
                                        files.clipboard ?: error("当前平台暂不支持图片剪贴板")
                                    finishDrawing()
                                    withContext(Dispatchers.Main) { busy = true }
                                    if (action.kind == ClipboardAction.Paste) {
                                        val image = clipboard.read() ?: error("剪贴板中没有图片")
                                        info =
                                            parser.decodeFromString(
                                                engine
                                                    .call(EngineOperation.PASTE_IMAGE, image.toNativePacket())
                                                    .decodeToString()
                                            )
                                    } else {
                                        val mode =
                                            when (action.kind) {
                                                ClipboardAction.Copy -> 0
                                                ClipboardAction.CopyVisible -> 1
                                                ClipboardAction.Cut -> 2
                                                ClipboardAction.Paste -> error("复制选项无效")
                                            }
                                        val image =
                                            clipboardImage(
                                                engine.call(
                                                    EngineOperation.COPY_SELECTION,
                                                    byteArrayOf(mode.toByte()),
                                                )
                                            )
                                        clipboard.write(image)
                                        if (action.kind == ClipboardAction.Cut)
                                            info =
                                                command(
                                                    jsonCommand("cut_selection") {
                                                        put("revision", info.revision)
                                                    }
                                                )
                                    }
                                    publishFrame()
                                    withContext(Dispatchers.Main) {
                                        status =
                                            when (action.kind) {
                                                ClipboardAction.Copy, ClipboardAction.CopyVisible -> "已复制图片"
                                                ClipboardAction.Cut -> "已剪切，可撤销"
                                                ClipboardAction.Paste -> "已粘贴到新图层"
                                            }
                                    }
                                }
                                is Action.Export -> {
                                    flushPoints()
                                    if (drawing) {
                                        info = command(jsonCommand("end"))
                                        drawing = false
                                        publishFrame()
                                    }
                                    withContext(Dispatchers.Main) { busy = true }
                                    val bytes =
                                        engine.call(
                                            EngineOperation.EXPORT_IMAGE,
                                            parser
                                                .encodeToString(action.options)
                                                .encodeToByteArray(),
                                        )
                                    if (files.export(bytes, action.options.format)) {
                                        withContext(Dispatchers.Main) {
                                            status = "${action.options.format.label} 已导出"
                                        }
                                    }
                                }
                                is Action.File -> {
                                    flushPoints()
                                    if (drawing) {
                                        info = command(jsonCommand("end"))
                                        drawing = false
                                    }
                                    withContext(Dispatchers.Main) { busy = true }
                                    when (action.kind) {
                                        FileAction.ImportLayer -> {
                                            finishDrawing()
                                            files.openImage()?.let { opened ->
                                                val name =
                                                    opened.reference?.name?.take(60)?.takeIf {
                                                        it.isNotBlank()
                                                    } ?: "导入的图像"
                                                val encoded = name.encodeToByteArray()
                                                val input =
                                                    ByteArray(4 + encoded.size + opened.bytes.size)
                                                repeat(4) {
                                                    input[it] =
                                                        (encoded.size ushr (it * 8)).toByte()
                                                }
                                                encoded.copyInto(input, 4)
                                                opened.bytes.copyInto(input, 4 + encoded.size)
                                                info =
                                                    parser.decodeFromString(
                                                        engine
                                                            .call(
                                                                EngineOperation.IMPORT_LAYER,
                                                                input,
                                                            )
                                                            .decodeToString()
                                                    )
                                                publishFrame()
                                                withContext(Dispatchers.Main) {
                                                    tool = Tool.MoveLayer
                                                    status = "图像已导入为图层"
                                                }
                                            }
                                        }
                                        FileAction.ImportBrushes ->
                                            files.openBrushPack()?.let { bytes ->
                                                val pack = BrushPack.parse(bytes)
                                                val updated =
                                                    withContext(Dispatchers.Main) {
                                                        val updated =
                                                            preferences.copy(
                                                                plugins =
                                                                    preferences.plugins.filterNot {
                                                                        it.id == pack.id
                                                                    } + pack
                                                            )
                                                        require(updated.valid()) { "插件数量超过限制" }
                                                        preferences = updated
                                                        status = "笔刷包已导入"
                                                        updated
                                                    }
                                                files.writePreferences(
                                                    parser
                                                        .encodeToString(updated)
                                                        .encodeToByteArray()
                                                )
                                            }
                                        FileAction.ExportBrushes -> {
                                            val pack =
                                                withContext(Dispatchers.Main) {
                                                    BrushPack(
                                                        "podor.custom",
                                                        "podor brushes",
                                                        brushes =
                                                            preferences.brushes.ifEmpty {
                                                                listOf(
                                                                    brush.preset.copy(
                                                                        id = "custom",
                                                                        size = brush.size,
                                                                        opacity = brush.opacity,
                                                                    )
                                                                )
                                                            },
                                                    )
                                                }
                                            files.saveBrushPack(
                                                parser.encodeToString(pack).encodeToByteArray()
                                            )
                                        }
                                        FileAction.Open -> openProject(null)
                                        FileAction.Save -> saveCurrent()
                                        FileAction.SaveAs -> saveCurrent(saveAs = true)
                                    }
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (exception: Exception) {
                            if (action is Action.Shutdown) action.finished.complete(false)
                            pending.clear()
                            if (drawing) {
                                info = command(jsonCommand("cancel"))
                                drawing = false
                                publishFrame()
                            }
                            withContext(Dispatchers.Main) {
                                if (action is Action.ApplyGradient)
                                    gradientPreview?.committing = false
                                if (action == Action.PrepareGradient) {
                                    gradientPreview = null
                                    tool = Tool.Brush
                                }
                                if (
                                    action is Action.PrepareLayerMove ||
                                        action is Action.TranslateLayer ||
                                        action is Action.TransformLayer
                                ) {
                                    layerMove = null
                                    tool = Tool.Brush
                                }
                                error = exception.message ?: "操作失败，请重试"
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                if (action is Action.PrepareLayerMove) preparingLayerMove = false
                                if (action == Action.PrepareGradient) preparingGradient = false
                                busy = false
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (exception: Throwable) {
                    withContext(Dispatchers.Main) { error = exception.message ?: "绘图引擎加载失败" }
                } finally {
                    withContext(NonCancellable) {
                        engine?.let {
                            it.close()
                        }
                    }
                }
            }
        producers += scope.launch {
            while (isActive) {
                delay(StudioDefaults.frameMillis)
                actions.trySend(Action.Frame)
            }
        }
    }

    fun command(type: String, values: JsonObjectBuilder.() -> Unit = {}) {
        if (previewPending()) return
        if (ready && !busy) scope.launch { actions.send(Action.Command(jsonCommand(type, values))) }
    }

    private fun previewPending(): Boolean {
        if (gradientPreview?.line != null) {
            error = "请先确认或取消渐变"
            return true
        }
        if (layerMove?.transformChanged != true) return false
        error = "请先确认或取消图层变换"
        return true
    }

    fun prepareGradient() {
        if (!ready || busy || preparingGradient || gradientPreview != null || tool != Tool.Gradient)
            return
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (!active.visible || active.locked) return
        preparingGradient = true
        busy = true
        scope.launch { actions.send(Action.PrepareGradient) }
    }

    fun previewGradient(line: GradientLine?) {
        val preview = gradientPreview ?: return
        if (!preview.committing) preview.line = line
    }

    fun cancelGradient() {
        if (gradientPreview?.committing != true) gradientPreview = null
    }

    fun commitGradient() {
        if (!ready || busy) return
        val preview = gradientPreview ?: return
        val line = preview.line?.takeIf { it.valid() } ?: return
        if (preview.committing) return
        preview.committing = true
        busy = true
        val settings = gradient
        scope.launch {
            actions.send(Action.ApplyGradient(preview.layerId, preview.revision, line, settings))
        }
    }

    fun prepareLayerMove() {
        if (
            !ready ||
                busy ||
                preparingLayerMove ||
                (tool != Tool.MoveLayer && tool != Tool.TransformLayer)
        )
            return
        val transform = tool == Tool.TransformLayer
        layerMove?.let {
            if ((it.sourceBounds != null) == transform) return
            layerMove = null
        }
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (!active.visible || active.locked || active.alphaLocked || document.selection != null)
            return
        preparingLayerMove = true
        busy = true
        scope.launch { actions.send(Action.PrepareLayerMove(transform)) }
    }

    fun previewLayerTransform(value: LayerTransform) {
        val preview = layerMove ?: return
        if (preview.sourceBounds != null && !preview.committing && value.valid())
            preview.transform = value
    }

    fun nudgeLayerTransform(horizontal: Int, vertical: Int, fast: Boolean) {
        val value = layerMove?.transform ?: return
        val step = if (fast) StudioDefaults.transformFastNudge else StudioDefaults.transformNudge
        val limit = StudioDefaults.maxTransformOffset
        previewLayerTransform(
            value.copy(
                dx = (value.dx + horizontal * step).coerceIn(-limit, limit),
                dy = (value.dy + vertical * step).coerceIn(-limit, limit),
            )
        )
    }

    fun commitLayerTransform() {
        if (!ready || busy) return
        val preview = layerMove ?: return
        val value = preview.transform ?: return
        if (preview.committing || !value.valid()) return
        preview.committing = true
        busy = true
        scope.launch {
            actions.send(Action.TransformLayer(preview.layerId, preview.revision, value))
        }
    }

    fun previewLayerMove(offset: IntOffset) {
        val preview = layerMove ?: return
        if (preview.committing) return
        preview.offset =
            IntOffset(
                offset.x.coerceIn(-document.width, document.width),
                offset.y.coerceIn(-document.height, document.height),
            )
    }

    fun commitLayerMove() {
        val preview = layerMove ?: return
        if (preview.committing || preview.offset == IntOffset.Zero) return
        preview.committing = true
        busy = true
        scope.launch {
            actions.send(Action.TranslateLayer(preview.layerId, preview.revision, preview.offset))
        }
    }

    fun cancelLayerMove(exit: Boolean = false) {
        if (layerMove?.committing == true) return
        layerMove?.offset = IntOffset.Zero
        if (exit) layerMove = null
    }

    suspend fun begin(point: Offset, pressure: Float, stylusEraser: Boolean = false) {
        if (!ready || busy) return
        val settings = brush
        val smudge = tool == Tool.Smudge && !stylusEraser
        actions.send(
            Action.Command(
                jsonCommand("begin") {
                    putJsonObject("brush") {
                        put("size", settings.size)
                        put("opacity", if (smudge) smudgeStrength else settings.opacity)
                        put("smudge", smudge)
                        put("hardness", settings.preset.hardness)
                        put("tip", settings.preset.tip.name.lowercase())
                        put("aspect", settings.preset.aspect)
                        put("angle", settings.preset.angle)
                        put("follow_direction", settings.preset.followDirection)
                        put("grain", settings.preset.grain)
                        put("spacing", settings.preset.spacing)
                        put("stabilization", settings.preset.stabilization)
                        put("pressure_curve", settings.preset.pressureCurve)
                        put("size_pressure", settings.preset.sizePressure)
                        put("opacity_pressure", settings.preset.opacityPressure)
                        put("eraser", tool == Tool.Eraser || stylusEraser)
                        putJsonArray("color") {
                            add((settings.color shr 16 and 255).toInt())
                            add((settings.color shr 8 and 255).toInt())
                            add((settings.color and 255).toInt())
                        }
                    }
                }
            )
        )
        points(listOf(Triple(point.x, point.y, pressure)))
    }

    suspend fun points(values: List<Triple<Float, Float, Float>>) {
        actions.send(Action.Points(values))
    }

    suspend fun end(cancel: Boolean = false) {
        actions.send(Action.Command(jsonCommand(if (cancel) "cancel" else "end")))
    }

    fun file(action: FileAction) {
        if (previewPending()) return
        if (action == FileAction.ImportLayer && (!hasCanvas || showWorkspace)) return
        if (action == FileAction.Open) {
            navigate(WorkspaceDestination.Open())
            return
        }
        if (ready && !busy) scope.launch { actions.send(Action.File(action)) }
    }

    fun setLayer(layer: LayerInfo) =
        command("set_layer") {
            put("id", layer.id)
            put("name", layer.name)
            put("visible", layer.visible)
            put("opacity", layer.opacity)
        }

    fun resizeCanvas(width: Int, height: Int, anchor: CanvasAnchor, revision: Long) =
        command("resize_canvas") {
            put("width", width)
            put("height", height)
            put("anchor", anchor.ordinal)
            put("revision", revision)
        }

    fun resizeImage(width: Int, height: Int, filter: ResampleFilter, revision: Long) =
        command("resize_image") {
            put("width", width)
            put("height", height)
            put("filter", filter.wireName)
            put("revision", revision)
        }

    fun setLayerProtection(id: Int, alphaLocked: Boolean? = null, locked: Boolean? = null) =
        command("set_protection") {
            put("id", id)
            alphaLocked?.let { put("alpha_locked", it) }
            locked?.let { put("locked", it) }
        }

    fun selectPreset(preset: BrushPreset) {
        if (previewPending()) return
        brush = brush.copy(preset = preset, size = preset.size, opacity = preset.opacity)
        if (tool != Tool.Smudge) tool = Tool.Brush
    }

    fun setLayerBlend(id: Int, mode: LayerBlendMode) =
        command("set_blend") {
            put("id", id)
            put("mode", parser.encodeToJsonElement(mode))
        }

    fun updatePreferences(value: Preferences) {
        if (!value.valid()) {
            error = "设置参数无效"
            return
        }
        preferences = value
        scope.launch { actions.send(Action.Settings(value)) }
    }

    fun saveBrush(name: String) {
        if (preferences.brushes.size >= 64 || name.isBlank()) return
        val id =
            generateSequence(1) { it + 1 }
                .map { "custom-$it" }
                .first { candidate -> preferences.brushes.none { it.id == candidate } }
        val saved =
            brush.preset.copy(
                id = id,
                label = name.trim().take(60),
                size = brush.size,
                opacity = brush.opacity,
            )
        updatePreferences(preferences.copy(brushes = preferences.brushes + saved))
        selectPreset(saved)
    }

    fun reportError(message: String) {
        error = message
    }

    fun export(options: ExportOptions) {
        if (previewPending()) return
        if (!ready || busy) return
        require(options.format in exportFormats)
        scope.launch { actions.send(Action.Export(options)) }
    }

    fun select(start: Offset, end: Offset) {
        val gesture =
            SelectionGesture(
                selectionKind,
                start,
                document.width,
                document.height,
                StudioDefaults.selectionSampleDistance,
            )
        gesture.add(end)
        select(gesture.selection())
    }

    fun clipboard(action: ClipboardAction) {
        if (!ready || busy || !clipboardAvailable || !hasCanvas || showWorkspace) return
        if (previewPending()) return
        cancelSelectionGesture()
        scope.launch { actions.send(Action.Clipboard(action)) }
    }

    fun select(selection: Selection?) {
        if (selection == null) clearSelection()
        else command("select_shape") { put("selection", Json.encodeToJsonElement(selection)) }
    }

    fun cancelSelectionGesture() {
        selectionCancellation++
    }

    fun clearSelection() {
        cancelSelectionGesture()
        command("select") { put("rect", JsonNull) }
    }

    fun fill(point: Offset) =
        command("fill") {
            put("x", point.x.toInt())
            put("y", point.y.toInt())
            put("tolerance", fillTolerance.toInt())
            putJsonArray("color") {
                add((brush.color shr 16 and 255).toInt())
                add((brush.color shr 8 and 255).toInt())
                add((brush.color and 255).toInt())
                add((brush.opacity * 255).toInt())
            }
        }

    fun dismissError() {
        error = null
    }

    fun navigate(destination: WorkspaceDestination) {
        if (previewPending()) return
        if (destination == WorkspaceDestination.Exit && !ready) {
            exitRequested = true
            return
        }
        if (ready && !busy && pendingNavigation == null)
            scope.launch { actions.send(Action.Navigate(destination)) }
    }

    fun resolveUnsaved(choice: UnsavedChoice) {
        val destination = pendingNavigation ?: return
        if (!busy) {
            error = null
            scope.launch { actions.send(Action.Navigate(destination, choice)) }
        }
    }

    fun home() {
        if (previewPending()) return
        if (ready && !busy) showWorkspace = true
    }

    fun resumeCanvas() {
        if (hasCanvas) showWorkspace = false
    }

    fun forgetProject(reference: ProjectReference) {
        scope.launch { actions.send(Action.Forget(reference)) }
    }

    suspend fun projectThumbnail(reference: ProjectReference): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = files.readThumbnail(reference) ?: return@withContext null
                val edge = bytes.intAt(0)
                require(edge in 1..512 && bytes.size == 4 + edge * edge * 4)
                rgbaBitmap(bytes, 4, edge)
            }
                .getOrNull()
        }

    suspend fun shutdown(): Boolean {
        ready = false
        if (worker?.isActive == true) {
            val result = CompletableDeferred<Boolean>()
            actions.send(Action.Shutdown(result))
            if (!result.await()) {
                ready = true
                return false
            }
        }
        worker?.join()
        producers.forEach { it.cancel() }
        actions.close()
        scope.cancel()
        return true
    }

    fun close() {
        producers.forEach { it.cancel() }
        actions.close()
        worker?.invokeOnCompletion { scope.cancel() }
    }
}

private fun jsonCommand(type: String, values: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("type", type)
    values()
}

private fun ByteArray.intAt(offset: Int): Int =
    (this[offset].toInt() and 255) or
        ((this[offset + 1].toInt() and 255) shl 8) or
        ((this[offset + 2].toInt() and 255) shl 16) or
        ((this[offset + 3].toInt() and 255) shl 24)
