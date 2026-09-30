package app.podor.presentation

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import app.podor.data.AsePaletteCodec
import app.podor.data.ProjectFiles
import app.podor.data.TextPaletteCodec
import app.podor.data.TextPaletteFile
import app.podor.data.TextPaletteFormat
import app.podor.domain.*
import app.podor.engine.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

data class TileImage(val x: Int, val y: Int, val size: Int, val image: ImageBitmap)

data class RenderFrame(val tiles: Map<Long, TileImage> = emptyMap())

data class RenderPreviews(
    val revision: Long = -1,
    val images: Map<Int, ImageBitmap> = emptyMap(),
    val masks: Map<Int, ImageBitmap> = emptyMap(),
    val maskEntries: Map<Int, ImageBitmap> = emptyMap(),
    val maskLayerId: Int? = null,
)

data class SelectionOutline(
    val path: Path?,
    val mask: List<TileImage>,
    val fillMask: List<TileImage> = mask,
)

private data class AnimationPlaybackSession(
    val plan: AnimationPlaybackPlan,
    val contentId: Long,
    val started: TimeMark,
)

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
    val references = ReferenceController(files, scope)
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

    var adjustmentPreview by mutableStateOf<AdjustmentPreview?>(null)
        private set

    private var preparingAdjustment = false

    var vectorObjects by mutableStateOf<VectorObjects?>(null)
        private set

    var selectedVectorObject by mutableStateOf<VectorObjectResult?>(null)
        private set

    var vectorPreview by mutableStateOf<VectorEditPreview?>(null)
        private set

    var vectorTool by mutableStateOf(StudioDefaults.vectorTool)
    var vectorPendingPath by mutableStateOf<VectorGeometry.Path?>(null)
    var vectorCancellation by mutableIntStateOf(0)
        private set

    private var vectorQueryVersion = 0

    var selectedAssistantId by mutableStateOf<Int?>(null)
        private set

    var assistantPreview by mutableStateOf<AssistantEditPreview?>(null)
        private set

    var assistantCancellation by mutableIntStateOf(0)
        private set

    val selectedAssistant: DrawingAssistant?
        get() = document.assistants.items.firstOrNull { it.id == selectedAssistantId }

    var lineGeneratorPreview by mutableStateOf<LineGeneratorPreview?>(null)
        private set

    private var animationPlayback by mutableStateOf<AnimationPlaybackSession?>(null)
    val animationPlaying: Boolean
        get() = animationPlayback != null

    var animationDisplayFrame by mutableStateOf<RenderFrame?>(null)
        private set

    var animationDisplayFrameId by mutableStateOf<Int?>(null)
        private set

    var animationTransition by mutableStateOf(false)
        private set

    var drawingInput by mutableStateOf(false)
        private set

    private var brushInputEpoch = 0

    var onionEnabled by mutableStateOf(false)
    var animationTimelineVisible by mutableStateOf(true)
    var animationDirection by mutableStateOf(AnimationDirection.Forward)
    var animationTagId by mutableStateOf<Int?>(null)
    var onionPrevious by mutableStateOf<RenderFrame?>(null)
        private set

    var onionNext by mutableStateOf<RenderFrame?>(null)
        private set

    var animationThumbnails by mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())
        private set

    private var animationThumbnailRequests by mutableStateOf<List<Int>>(emptyList())

    val animationExportFormats: List<AnimationExportFormat>
        get() =
            if (document.animation != null && document.animationExport?.available == true)
                files.animationExportFormats
            else emptyList()

    val asepriteExportAvailable: Boolean
        get() =
            hasCanvas &&
                files.supportsAsepriteProjects &&
                document.asepriteExport?.available == true

    val exportFormats: List<ExportFormat>
        get() =
            files.exportFormats.filter {
                (it != ExportFormat.IndexedPng ||
                    document.colorMode == DocumentColorMode.Indexed) &&
                    (it != ExportFormat.Svg ||
                        document.layers.any { layer ->
                            layer.id == document.active && layer.kind == LayerKind.Vector
                        })
            }

    val clipboardAvailable = files.clipboard != null

    var brush by mutableStateOf(BrushSettings())
    var indexedColorIndex by mutableIntStateOf(1)
        private set

    var smudgeStrength by mutableStateOf(StudioDefaults.smudgeStrength)
    var brushLibraryQuery by mutableStateOf("")
    var brushCollection by mutableStateOf(StudioDefaults.brushCollection)
    var symmetry by mutableStateOf(SymmetrySettings())
    var preferences by mutableStateOf(Preferences())
        private set

    var extractingPalette by mutableStateOf(false)
        private set

    val brushes: List<BrushPreset> by derivedStateOf {
        (BrushPreset.entries +
                preferences.brushes +
                preferences.plugins
                    .filter { it.enabled }
                    .flatMap { pack ->
                        pack.brushes.map { it.copy(id = "plugin:${pack.id}/${it.id}") }
                    })
            .filter {
                document.colorMode != DocumentColorMode.Indexed ||
                    document.maskEditing ||
                    it.raster != BrushRaster.Antialiased
            }
    }

    private var currentTool by mutableStateOf(Tool.Brush)
    var tool: Tool
        get() = currentTool
        set(value) {
            if (value != currentTool && previewPending()) return
            if (document.maskEditing && value == Tool.Smudge) {
                error = "蒙版不支持涂抹笔"
                return
            }
            if (
                document.colorMode == DocumentColorMode.Indexed &&
                    !document.maskEditing &&
                    value in setOf(Tool.Smudge, Tool.Gradient, Tool.LassoFill)
            ) {
                error = "索引色模式暂不支持此工具"
                return
            }
            if (value == Tool.Gradient && currentTool != value) {
                gradient = gradient.copy(from = brush.color)
                gradientEditingStart = true
            }
            currentTool = value
        }

    var selectionKind by mutableStateOf(StudioDefaults.selectionKind)
    var selectionTolerance by mutableStateOf(StudioDefaults.selectionTolerance)
    var selectionContiguous by mutableStateOf(StudioDefaults.selectionContiguous)
    var selectionMerged by mutableStateOf(StudioDefaults.selectionMerged)
    var selectionMode by mutableStateOf(StudioDefaults.selectionMode)
        private set

    var selectionOutline by mutableStateOf<SelectionOutline?>(null)
        private set

    var selectionCancellation by mutableIntStateOf(0)
        private set

    var viewport by mutableStateOf(Viewport())
    var fingerDrawing by mutableStateOf(true)
    var lassoErase by mutableStateOf(false)
    var fillTolerance by mutableStateOf(StudioDefaults.fillTolerance)
    var fillContiguous by mutableStateOf(StudioDefaults.fillContiguous)
    var fillMerged by mutableStateOf(StudioDefaults.fillMerged)
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
        data class Command(val json: JsonObject, val strokeEpoch: Int? = null) : Action

        data class AnimationCommand(val json: JsonObject, val layerId: Int? = null) : Action

        data class Points(val values: List<Triple<Float, Float, Float>>) : Action

        data class File(
            val kind: FileAction,
            val paletteFormat: PaletteFileFormat = PaletteFileFormat.Ase,
            val flattenPalette: Boolean = false,
        ) : Action

        data class Export(val options: ExportOptions, val vectorTarget: JsonObject) : Action

        data class AnimationExport(val options: AnimationExportOptions, val revision: Long) : Action

        data class AsepriteExport(val options: AsepriteExportOptions, val revision: Long) : Action

        data class Clipboard(val kind: ClipboardAction) : Action

        data object ExtractPalette : Action

        data object Frame : Action

        data object PrepareGradient : Action

        data class PrepareAdjustment(val kind: AdjustmentKind, val previousTool: Tool) : Action

        data class ApplyAdjustment(
            val preview: AdjustmentPreview,
            val settings: AdjustmentSettings,
        ) : Action

        data class SelectVectorObject(
            val id: Int,
            val revision: Long,
            val objectId: Int?,
            val queryVersion: Int,
        ) : Action

        data class PickVectorObject(
            val id: Int,
            val revision: Long,
            val point: Offset,
            val tolerance: Float,
            val queryVersion: Int,
        ) : Action

        data class ApplyVector(val preview: VectorEditPreview, val value: VectorObjectSpec) : Action

        data class ApplyAssistant(
            val preview: AssistantEditPreview,
            val value: DrawingAssistantSpec,
        ) : Action

        data class ApplyLineGenerator(
            val preview: LineGeneratorPreview,
            val settings: LineGeneratorSettings,
        ) : Action

        data class ApplyGradient(
            val id: Int,
            val revision: Long,
            val line: GradientLine,
            val settings: GradientSettings,
        ) : Action

        data class PrepareLayerMove(val transform: Boolean) : Action

        data class TransformLayer(
            val id: Int,
            val revision: Long,
            val value: LayerTransform,
            val maskEditing: Boolean,
            val maskId: Int?,
        ) : Action

        data class TranslateLayer(
            val id: Int,
            val revision: Long,
            val offset: IntOffset,
            val maskEditing: Boolean,
            val maskId: Int?,
        ) : Action

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
        ImportPalette,
        ExportPalette,
    }

    init {
        worker =
            scope.launch(Dispatchers.Default) {
                var engine: NativeEngine? = null
                var info = DocumentInfo()
                var drawing = false
                var drawingEpoch = 0
                var frameDirty = false
                var previewRevision = -1L
                var maskPreviewTarget: Pair<Int, Int?>? = null
                var outlineId = -1L
                var outline: SelectionOutline? = null
                var savedContentId = 0L
                var currentReference: ProjectReference? = null
                fun previewImages(bytes: ByteArray): Map<Int, ImageBitmap> {
                    val size = bytes.intAt(8)
                    var offset = 16
                    return buildMap {
                        repeat(bytes.intAt(12)) {
                            put(bytes.intAt(offset), rgbaBitmap(bytes, offset + 4, size))
                            offset += 4 + size * size * 4
                        }
                    }
                }
                val tiles = mutableMapOf<Long, TileImage>()
                var frameWidth = 0
                var frameHeight = 0
                val pending = ArrayList<Triple<Float, Float, Float>>(StudioDefaults.maxBatchSamples)
                val animationFrames = AnimationFrameCache()
                var animationCacheContentId = -1L
                var animationThumbnailContentId = -1L
                val animationThumbnailImages = mutableMapOf<Int, ImageBitmap>()
                fun command(value: JsonObject): DocumentInfo {
                    val result =
                        engine!!.call(EngineOperation.COMMAND, value.toString().encodeToByteArray())
                    return parser.decodeFromString(result.decodeToString())
                }
                fun readVectorObject(id: Int, objectId: Int, revision: Long): VectorObjectResult =
                    parser.decodeFromString(
                        engine!!
                            .call(
                                EngineOperation.COMMAND,
                                jsonCommand("vector_object") {
                                        put("id", id)
                                        put("object_id", objectId)
                                        put("revision", revision)
                                    }
                                    .toString()
                                    .encodeToByteArray(),
                            )
                            .decodeToString()
                    )
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
                fun readLayerActionFrame(bytes: ByteArray, original: RenderFrame): RenderFrame {
                    val size = bytes.intAt(8)
                    val count = bytes.intAt(12)
                    if (count == 0) return original
                    var offset = 16
                    val images =
                        original.tiles.toMutableMap().apply {
                            repeat(count) {
                                val x = bytes.intAt(offset)
                                val y = bytes.intAt(offset + 4)
                                put(
                                    (x.toLong() shl 32) or y.toLong(),
                                    TileImage(x, y, size, rgbaBitmap(bytes, offset + 8, size)),
                                )
                                offset += 8 + size * size * 4
                            }
                        }
                    return RenderFrame(images)
                }
                fun animationFrame(id: Int): RenderFrame {
                    if (animationCacheContentId != info.contentId) {
                        animationFrames.clear()
                        animationCacheContentId = info.contentId
                    }
                    return animationFrames.get(id)
                        ?: readLayerActionFrame(
                                engine!!.call(
                                    EngineOperation.ANIMATION_FRAME,
                                    buildJsonObject {
                                        put("revision", info.revision)
                                        put("frame_id", id)
                                        put("transparent", true)
                                    }
                                        .toString()
                                        .encodeToByteArray(),
                                ),
                                RenderFrame(),
                            )
                            .also { animationFrames.put(id, it) }
                }
                fun readLayers(
                    selection: Boolean = false,
                    move: Boolean = false,
                ): List<LayerFrame> {
                    val bytes =
                        engine!!.call(
                            if (move) EngineOperation.MOVE_LAYERS else EngineOperation.LAYERS,
                            if (selection) byteArrayOf(1) else byteArrayOf(),
                        )
                    val size = bytes.intAt(8)
                    var position = 16
                    var stationary = emptyList<TileImage>()
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
                            if (id == 0) stationary = images
                            else {
                                add(
                                    LayerFrame(
                                        info.layers.first { it.id == id },
                                        images,
                                        if (id == info.active) stationary else emptyList(),
                                    )
                                )
                            }
                        }
                    }
                    if (!move || bytes.intAt(position) == 0) return layers
                    val split = bytes.intAt(position) == 2
                    position += 4
                    val default = bytes.intAt(position)
                    val enabled = bytes.intAt(position + 4) != 0
                    val linked = bytes.intAt(position + 8) != 0
                    val bounds =
                        Rect(
                            bytes.intAt(position + 12).toFloat(),
                            bytes.intAt(position + 16).toFloat(),
                            bytes.intAt(position + 20).toFloat(),
                            bytes.intAt(position + 24).toFloat(),
                        )
                    val count = bytes.intAt(position + 28)
                    position += 32
                    val maskTiles = buildList {
                        repeat(count) {
                            add(
                                TileImage(
                                    bytes.intAt(position),
                                    bytes.intAt(position + 4),
                                    size,
                                    if (split) rgbaBitmap(bytes, position + 8, size)
                                    else alphaBitmap(bytes, position + 8, size),
                                )
                            )
                            position += 8 + size * size * if (split) 4 else 1
                        }
                    }
                    val selectedTiles =
                        if (split) {
                            val selectedCount = bytes.intAt(position)
                            position += 4
                            buildList {
                                repeat(selectedCount) {
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
                        } else emptyList()
                    val mask =
                        LayerMaskFrame(
                            bounds,
                            default,
                            enabled,
                            linked,
                            maskTiles,
                            split,
                            selectedTiles,
                        )
                    return layers.map {
                        if (it.layer.id == info.active) it.copy(mask = mask) else it
                    }
                }
                suspend fun publishFrame() {
                    fun selectionMask(data: ByteArray): List<TileImage> {
                        val size = data.intAt(4)
                        var offset = 12
                        return buildList {
                            repeat(data.intAt(8)) {
                                add(
                                    TileImage(
                                        data.intAt(offset),
                                        data.intAt(offset + 4),
                                        size,
                                        alphaBitmap(data, offset + 8, size),
                                    )
                                )
                                offset += 8 + size * size
                            }
                        }
                    }
                    val selected = info.selection
                    if (outlineId != (selected?.id ?: 0L)) {
                        outline = null
                        if (
                            selected != null &&
                                (selected.combined || selected.raster) &&
                                !selected.empty
                        ) {
                            val data = engine!!.call(EngineOperation.SELECTION_OUTLINE)
                            outline =
                                if (data.intAt(0) == 0) {
                                    val path = Path()
                                    for (offset in 4 until data.size step 16) {
                                        path.moveTo(
                                            data.intAt(offset).toFloat(),
                                            data.intAt(offset + 4).toFloat(),
                                        )
                                        path.lineTo(
                                            data.intAt(offset + 8).toFloat(),
                                            data.intAt(offset + 12).toFloat(),
                                        )
                                    }
                                    SelectionOutline(
                                        path,
                                        emptyList(),
                                        selectionMask(
                                            engine!!.call(
                                                EngineOperation.SELECTION_OUTLINE,
                                                byteArrayOf(1),
                                            )
                                        ),
                                    )
                                } else {
                                    SelectionOutline(null, selectionMask(data))
                                }
                        }
                        outlineId = selected?.id ?: 0L
                    }
                    val bytes = engine!!.call(EngineOperation.FRAME, byteArrayOf(1))
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
                        val previousMode = document.colorMode
                        document = info
                        if (!info.maskEditing)
                            info.indexedPalette?.let { palette ->
                                if (previousMode != DocumentColorMode.Indexed)
                                    indexedColorIndex = nearestPaletteIndex(palette, brush.color)
                                indexedColorIndex =
                                    indexedColorIndex.coerceIn(palette.colors.indices)
                                if (brush.preset.raster == BrushRaster.Antialiased) {
                                    brush =
                                        brush.copy(
                                            preset = BrushPreset.PixelPencil,
                                            size = BrushPreset.PixelPencil.size,
                                            opacity = 1f,
                                        )
                                }
                                brush = brush.copy(color = palette.argb(indexedColorIndex))
                            }
                        if (info.maskEditing && tool in setOf(Tool.Smudge, Tool.Vector))
                            tool = Tool.Brush
                        selectionOutline = outline
                        hasUnsavedChanges = info.contentId != savedContentId
                        if (updated != null) frame = updated
                        layerMove?.let {
                            if (
                                it.revision != info.revision ||
                                    it.layerId != info.active ||
                                    it.maskEditing != info.maskEditing ||
                                    (it.maskEditing && it.maskId != info.activeMaskId) ||
                                    it.selection != info.selection
                            )
                                layerMove = null
                        }
                        gradientPreview?.let {
                            if (
                                it.revision != info.revision ||
                                    it.layerId != info.active ||
                                    it.selection != info.selection ||
                                    info.maskEditing
                            )
                                gradientPreview = null
                        }
                        vectorPreview?.let {
                            if (
                                it.revision != info.revision ||
                                    it.layerId != info.active ||
                                    it.selectionId != info.selectionId ||
                                    info.maskEditing
                            )
                                vectorPreview = null
                        }
                        assistantPreview?.let {
                            if (it.revision != info.revision) cancelAssistant()
                        }
                        lineGeneratorPreview?.let {
                            if (
                                it.revision != info.revision ||
                                    it.layerId != info.active ||
                                    it.selectionId != info.selectionId ||
                                    info.maskEditing
                            )
                                if (!it.committing) lineGeneratorPreview = null
                        }
                        if (selectedAssistantId !in info.assistants.items.map { it.id })
                            selectedAssistantId = info.assistants.snapId
                    }
                    frameDirty = false
                }
                suspend fun finishDrawing() {
                    flushPoints()
                    if (drawing) {
                        info = command(jsonCommand("end"))
                        drawing = false
                        withContext(Dispatchers.Main) {
                            if (brushInputEpoch == drawingEpoch) drawingInput = false
                        }
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
                    animationFrames.clear()
                    animationCacheContentId = -1
                    animationThumbnailContentId = -1
                    animationThumbnailImages.clear()
                    previewRevision = -1L
                    maskPreviewTarget = null
                    currentReference = reference
                    savedContentId = info.contentId
                    tiles.clear()
                    withContext(Dispatchers.Main) {
                        stopAnimation()
                        drawingInput = false
                        brushInputEpoch++
                        animationDirection = AnimationDirection.Forward
                        animationTagId = null
                        onionPrevious = null
                        onionNext = null
                        animationThumbnails = emptyMap()
                        animationThumbnailRequests = emptyList()
                        references.clear()
                        frame = RenderFrame()
                        previews = RenderPreviews()
                        vectorObjects = null
                        selectedVectorObject = null
                        vectorPreview = null
                        vectorPendingPath = null
                        vectorQueryVersion++
                        assistantPreview = null
                        selectedAssistantId = null
                        assistantCancellation++
                        lineGeneratorPreview = null
                        viewport = Viewport()
                        symmetry = SymmetrySettings()
                        selectionMode = StudioDefaults.selectionMode
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
                    val nativeProject =
                        opened.bytes.size >= 5 && opened.bytes.decodeToString(0, 5) == "PODOR"
                    resetCanvas(
                        opened.reference?.let { if (nativeProject) it else it.copy(editable = false) }
                    )
                    rememberCurrent()
                }
                suspend fun refreshPreviews() {
                    val activeEngine = engine ?: return
                    if (pending.isNotEmpty() || frameDirty) {
                        flushPoints()
                        publishFrame()
                    }
                    val playback = withContext(Dispatchers.Main) { animationPlayback }
                    val animation = info.animation
                    if (playback != null && animation != null) {
                        if (playback.contentId != info.contentId)
                            withContext(Dispatchers.Main) { stopAnimation() }
                        else {
                            val position =
                                playback.plan.at(playback.started.elapsedNow().inWholeNanoseconds)
                            if (
                                withContext(Dispatchers.Main) {
                                    animationDisplayFrameId != position.frameId
                                }
                            ) {
                                val displayed = animationFrame(position.frameId)
                                withContext(Dispatchers.Main) {
                                    if (animationPlayback === playback) {
                                        animationDisplayFrame = displayed
                                        animationDisplayFrameId = position.frameId
                                    }
                                }
                            }
                            if (position.finished)
                                withContext(Dispatchers.Main) {
                                    if (animationPlayback === playback) stopAnimation()
                                }
                        }
                    }
                    val onion =
                        withContext(Dispatchers.Main) {
                            onionEnabled && !animationPlaying
                        }
                    if (onion && animation != null) {
                        val index =
                            animation.frames.indexOfFirst {
                                it.id == animation.activeFrameId
                            }
                        val previous =
                            animation.frames.getOrNull(index - 1)?.let {
                                animationFrame(it.id)
                            }
                        val next =
                            animation.frames.getOrNull(index + 1)?.let {
                                animationFrame(it.id)
                            }
                        withContext(Dispatchers.Main) {
                            if (
                                document.contentId == info.contentId &&
                                    document.animation?.activeFrameId == animation.activeFrameId &&
                                    onionEnabled &&
                                    !animationPlaying
                            ) {
                                onionPrevious = previous
                                onionNext = next
                            }
                        }
                    } else
                        withContext(Dispatchers.Main) {
                            onionPrevious = null
                            onionNext = null
                        }
                    val requestedFrames =
                        withContext(Dispatchers.Main) { animationThumbnailRequests }
                            .filter { animation?.frame(it) != null }
                    if (animation != null && requestedFrames.isNotEmpty()) {
                        if (animationThumbnailContentId != info.contentId) {
                            animationThumbnailImages.clear()
                            animationThumbnailContentId = info.contentId
                        }
                        animationThumbnailImages.keys.retainAll(requestedFrames.toSet())
                        val missing =
                            requestedFrames
                                .filter { it !in animationThumbnailImages }
                                .take(
                                    minOf(
                                        info.maxFrameThumbnails,
                                        StudioDefaults.animationThumbnailBatchSize,
                                    )
                                )
                        if (missing.isNotEmpty()) {
                            val images =
                                previewImages(
                                    activeEngine.call(
                                        EngineOperation.ANIMATION_PREVIEWS,
                                        buildJsonObject {
                                            put("revision", info.revision)
                                            putJsonArray("frame_ids") {
                                                missing.forEach { add(it) }
                                            }
                                        }
                                            .toString()
                                            .encodeToByteArray(),
                                    )
                                )
                            animationThumbnailImages.putAll(images)
                            val published = animationThumbnailImages.toMap()
                            withContext(Dispatchers.Main) {
                                if (
                                    document.contentId == info.contentId &&
                                        animationThumbnailRequests.filter {
                                            animation.frame(it) != null
                                        } == requestedFrames
                                )
                                    animationThumbnails = published
                            }
                        }
                    }
                    val assistantDraft =
                        withContext(Dispatchers.Main) {
                            assistantPreview
                                ?.takeIf { it.updating && !it.committing }
                                ?.let { it to it.value }
                        }
                    if (assistantDraft != null) {
                        val (preview, value) = assistantDraft
                        activeEngine.call(
                            EngineOperation.COMMAND,
                            jsonCommand("preview_assistant") {
                                    put("revision", preview.revision)
                                    put("assistant", value.request())
                                    putJsonObject("origin") {
                                        put("x", 0f)
                                        put("y", 0f)
                                    }
                                    putJsonObject("point") {
                                        put("x", 1f)
                                        put("y", 0f)
                                    }
                                }
                                .toString()
                                .encodeToByteArray(),
                        )
                        withContext(Dispatchers.Main) {
                            if (assistantPreview === preview && preview.value == value)
                                preview.updating = false
                        }
                    }
                    val activeVector =
                        info.layers.firstOrNull {
                            it.id == info.active && it.kind == LayerKind.Vector
                        }
                    val cachedVectors = withContext(Dispatchers.Main) { vectorObjects }
                    if (activeVector == null) {
                        if (cachedVectors != null)
                            withContext(Dispatchers.Main) {
                                vectorObjects = null
                                selectedVectorObject = null
                            }
                    } else if (
                        cachedVectors?.id != info.active || cachedVectors.revision != info.revision
                    ) {
                        val objects =
                            parser.decodeFromString<VectorObjects>(
                                activeEngine
                                    .call(
                                        EngineOperation.COMMAND,
                                        jsonCommand("vector_objects") {
                                                put("id", info.active)
                                                put("revision", info.revision)
                                            }
                                            .toString()
                                            .encodeToByteArray(),
                                    )
                                    .decodeToString()
                            )
                        val (selectedId, queryVersion) =
                            withContext(Dispatchers.Main) {
                                selectedVectorObject?.takeIf { it.id == info.active }?.objectId to
                                    vectorQueryVersion
                            }
                        val selected =
                            selectedId
                                ?.takeIf { id ->
                                    objects.objects.any { it.id == id }
                                }
                                ?.let {
                                    readVectorObject(info.active, it, info.revision)
                                }
                        withContext(Dispatchers.Main) {
                            vectorObjects = objects
                            if (queryVersion == vectorQueryVersion) selectedVectorObject = selected
                        }
                    }
                    val layerAction =
                        withContext(Dispatchers.Main) {
                            canonicalLayerAction()?.takeIf { (preview, request) ->
                                preview.renderedAction != request
                            }
                        }
                    if (layerAction != null) {
                        val (preview, request) = layerAction
                        val result = runCatching {
                            readLayerActionFrame(
                                activeEngine.call(
                                    EngineOperation.PREVIEW_LAYER_ACTION,
                                    request.toString().encodeToByteArray(),
                                ),
                                preview.original,
                            )
                        }
                        val generated =
                            withContext(Dispatchers.Main) {
                                lineGeneratorPreview?.takeIf {
                                    it.canonical === preview
                                }
                            }
                        if (result.isFailure && generated == null) throw result.exceptionOrNull()!!
                        withContext(Dispatchers.Main) {
                            val current = canonicalLayerAction()
                            if (current?.first === preview && current.second == request) {
                                preview.frame = result.getOrElse {
                                    preview.original
                                }
                                preview.renderedAction = request
                                generated?.error = result.exceptionOrNull()?.message
                                generated?.renderedAction = request
                            }
                        }
                    }
                    val adjustment =
                        withContext(Dispatchers.Main) {
                            adjustmentPreview
                                ?.takeIf { !it.committing && it.updating }
                                ?.let { it to it.settings }
                        }
                    if (adjustment != null) {
                        val (preview, settings) = adjustment
                        val bytes =
                            activeEngine.call(
                                if (preview.nodeEditing) EngineOperation.PREVIEW_LAYER_ACTION
                                else EngineOperation.ADJUSTMENT_PREVIEW,
                                adjustmentRequest(preview, settings).toString().encodeToByteArray(),
                            )
                        if (
                            !withContext(Dispatchers.Main) {
                                adjustmentPreview === preview &&
                                    !preview.committing &&
                                    preview.settings == settings
                            }
                        )
                            return
                        val size = bytes.intAt(8)
                        val count = bytes.intAt(12)
                        var offset = 16
                        val images = preview.original.tiles.toMutableMap()
                        repeat(count) {
                            val x = bytes.intAt(offset)
                            val y = bytes.intAt(offset + 4)
                            images[(x.toLong() shl 32) or y.toLong()] =
                                TileImage(
                                    x,
                                    y,
                                    size,
                                    rgbaBitmap(bytes, offset + 8, size),
                                )
                            offset += 8 + size * size * 4
                        }
                        val adjusted = RenderFrame(images)
                        withContext(Dispatchers.Main) {
                            if (
                                adjustmentPreview === preview &&
                                    !preview.committing &&
                                    preview.settings == settings
                            ) {
                                preview.frame = adjusted
                                preview.changed =
                                    if (preview.nodeEditing)
                                        AdjustmentLayerSettings.from(settings) !=
                                            AdjustmentLayerSettings.from(preview.initialSettings)
                                    else if (settings.kind == AdjustmentKind.LayerBlend)
                                        settings.opacity != preview.initialSettings.opacity ||
                                            settings.blend != preview.initialSettings.blend
                                    else count > 0
                                preview.renderedSettings = settings
                            }
                        }
                    }
                    val maskTarget = info.active to info.activeMaskId
                    if (
                        !drawing &&
                            (previewRevision != info.revision || maskPreviewTarget != maskTarget)
                    ) {
                        val changed = previewRevision != info.revision
                        val imageBytes =
                            if (changed) activeEngine.call(EngineOperation.PREVIEWS) else null
                        if (imageBytes?.isEmpty() == true) return
                        val images =
                            if (imageBytes != null) previewImages(imageBytes)
                            else withContext(Dispatchers.Main) { previews.images }
                        val masks =
                            if (info.layers.none { it.mask != null }) emptyMap()
                            else previewImages(activeEngine.call(EngineOperation.MASK_PREVIEWS))
                        val entries =
                            if (
                                info.maxLayerMasks > 0 &&
                                    info.layers.first { it.id == info.active }.masks.isNotEmpty()
                            )
                                previewImages(
                                    activeEngine.call(
                                        EngineOperation.MASK_PREVIEWS,
                                        byteArrayOf(1),
                                    )
                                )
                            else emptyMap()
                        previewRevision = info.revision
                        maskPreviewTarget = maskTarget
                        withContext(Dispatchers.Main) {
                            previews =
                                RenderPreviews(
                                    info.revision,
                                    images,
                                    masks,
                                    entries,
                                    info.active,
                                )
                        }
                    }
                }
                try {
                    val native = createNativeEngine(info.width, info.height)
                    engine =
                        object : NativeEngine {
                            override fun call(operation: Int, input: ByteArray): ByteArray {
                                val bound =
                                    if (
                                        info.animation != null &&
                                            operation in
                                                setOf(
                                                    EngineOperation.COMMAND,
                                                    EngineOperation.ADJUSTMENT_PREVIEW,
                                                    EngineOperation.PREVIEW_LAYER_ACTION,
                                                    EngineOperation.VECTOR_SVG,
                                                )
                                    )
                                        parser
                                            .parseToJsonElement(input.decodeToString())
                                            .jsonObject
                                            .withAnimationTarget(info)
                                            .toString()
                                            .encodeToByteArray()
                                    else input
                                return native.call(operation, bound)
                            }

                            override fun close() = native.close()
                        }
                    files.readPreferences()?.let { bytes ->
                        runCatching {
                            parser
                                .decodeFromString<Preferences>(bytes.decodeToString())
                                .withNewShortcuts()
                                .withAvailableBrushFavorites()
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
                                is Action.AnimationCommand -> {
                                    check(!drawing) { "请先结束当前笔画" }
                                    withContext(Dispatchers.Main) { busy = true }
                                    val previousInfo = info
                                    info = command(action.json)
                                    action.layerId?.let { id ->
                                        if (info.active != id)
                                            info =
                                                command(
                                                    jsonCommand("select_layer") { put("id", id) }
                                                )
                                    }
                                    val metadataOnly =
                                        action.json["type"]?.jsonPrimitive?.content in
                                            setOf(
                                                "set_frame_duration",
                                                "add_frame_tag",
                                                "set_frame_tag",
                                                "delete_frame_tag",
                                                "reorder_frames",
                                            )
                                    if (metadataOnly) {
                                        if (animationCacheContentId == previousInfo.contentId)
                                            animationCacheContentId = info.contentId
                                        if (animationThumbnailContentId == previousInfo.contentId)
                                            animationThumbnailContentId = info.contentId
                                        if (previewRevision == previousInfo.revision) {
                                            previewRevision = info.revision
                                            withContext(Dispatchers.Main) {
                                                previews = previews.copy(revision = info.revision)
                                            }
                                        }
                                    }
                                    publishFrame()
                                }
                                is Action.ApplyLineGenerator -> {
                                    finishDrawing()
                                    withContext(Dispatchers.Main) { busy = true }
                                    val preview = action.preview
                                    info =
                                        command(
                                            jsonCommand("generate_lines") {
                                                put("id", preview.layerId)
                                                put("revision", preview.revision)
                                                put("selection_id", preview.selectionId)
                                                put("mask_editing", false)
                                                put("mask_id", JsonNull)
                                                put("name", preview.name)
                                                put("parent_id", preview.parentId)
                                                put("index", preview.index)
                                                put("settings", action.settings.request())
                                            }
                                        )
                                    withContext(Dispatchers.Main) {
                                        lineGeneratorPreview = null
                                        currentTool = Tool.Vector
                                        vectorTool = VectorEditorTool.Edit
                                    }
                                    publishFrame()
                                }
                                is Action.ApplyAssistant -> {
                                    finishDrawing()
                                    val previousRevision = info.revision
                                    info =
                                        command(
                                            jsonCommand("set_assistant") {
                                                put("id", action.preview.id)
                                                put("revision", action.preview.revision)
                                                put("assistant", action.value.request())
                                            }
                                        )
                                    withContext(Dispatchers.Main) {
                                        if (assistantPreview === action.preview)
                                            assistantPreview = null
                                        if (previewRevision == previousRevision) {
                                            previewRevision = info.revision
                                            previews = previews.copy(revision = info.revision)
                                        }
                                    }
                                    publishFrame()
                                }
                                is Action.SelectVectorObject -> {
                                    val selected =
                                        action.objectId?.let {
                                            readVectorObject(action.id, it, action.revision)
                                        }
                                    withContext(Dispatchers.Main) {
                                        if (
                                            action.queryVersion == vectorQueryVersion &&
                                                document.active == action.id &&
                                                document.revision == action.revision
                                        )
                                            selectedVectorObject = selected
                                    }
                                }
                                is Action.PickVectorObject -> {
                                    val picked =
                                        Json.parseToJsonElement(
                                                engine
                                                    .call(
                                                        EngineOperation.COMMAND,
                                                        jsonCommand("pick_vector_object") {
                                                                put("id", action.id)
                                                                put("revision", action.revision)
                                                                put("x", action.point.x)
                                                                put("y", action.point.y)
                                                                put("tolerance", action.tolerance)
                                                            }
                                                            .toString()
                                                            .encodeToByteArray(),
                                                    )
                                                    .decodeToString()
                                            )
                                            .jsonObject["object_id"]
                                            ?.jsonPrimitive
                                            ?.intOrNull
                                    val selected = picked?.let {
                                        readVectorObject(action.id, it, action.revision)
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (
                                            action.queryVersion == vectorQueryVersion &&
                                                document.active == action.id &&
                                                document.revision == action.revision
                                        )
                                            selectedVectorObject = selected
                                    }
                                }
                                is Action.ApplyVector -> {
                                    finishDrawing()
                                    val preview = action.preview
                                    val objectId =
                                        preview.objectId
                                            ?: info.layers
                                                .first { it.id == preview.layerId }
                                                .vector!!
                                                .nextObjectId
                                    info =
                                        command(
                                            jsonCommand(
                                                if (preview.objectId == null) "add_vector_object"
                                                else "set_vector_object"
                                            ) {
                                                put("id", preview.layerId)
                                                put("revision", preview.revision)
                                                preview.objectId?.let { put("object_id", it) }
                                                put("object", action.value.request())
                                            }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) {
                                        if (vectorPreview === preview) vectorPreview = null
                                        selectedVectorObject =
                                            VectorObjectResult(
                                                preview.layerId,
                                                objectId,
                                                info.revision,
                                                action.value,
                                            )
                                    }
                                }
                                Action.ExtractPalette -> {
                                    finishDrawing()
                                    val bytes =
                                        engine.call(
                                            EngineOperation.PALETTE,
                                            byteArrayOf(
                                                StudioDefaults.extractedPaletteSize.toByte()
                                            ),
                                        )
                                    check(
                                        bytes.size % 3 == 0 &&
                                            bytes.size <= StudioDefaults.extractedPaletteSize * 3
                                    )
                                    val colors =
                                        (bytes.indices step 3).map { offset ->
                                            0xFF000000L or
                                                ((bytes[offset].toLong() and 255) shl 16) or
                                                ((bytes[offset + 1].toLong() and 255) shl 8) or
                                                (bytes[offset + 2].toLong() and 255)
                                        }
                                    withContext(Dispatchers.Main) {
                                        if (colors.isEmpty()) error = "画布上没有可提取的颜色"
                                        else if (addPaletteColors(colors)) status = "已提取画布颜色"
                                    }
                                }
                                is Action.PrepareAdjustment -> {
                                    finishDrawing()
                                    check(
                                        !info.maskEditing ||
                                            action.kind == AdjustmentKind.LayerBlend
                                    ) {
                                        "请先切换到图层像素再使用调整"
                                    }
                                    publishFrame()
                                    val active = info.layers.first { it.id == info.active }
                                    val nodeEditing =
                                        active.kind == LayerKind.Adjustment &&
                                            action.kind != AdjustmentKind.LayerBlend
                                    val histogram =
                                        if (action.kind == AdjustmentKind.Curves && !nodeEditing)
                                            Json.decodeFromString<List<List<Float>>>(
                                                engine
                                                    .call(EngineOperation.CURVE_HISTOGRAM)
                                                    .decodeToString()
                                            )
                                        else emptyList()
                                    withContext(Dispatchers.Main) {
                                        check(
                                            !active.effectiveLocked ||
                                                action.kind == AdjustmentKind.LayerBlend
                                        ) {
                                            "图层已锁定，请先解锁"
                                        }
                                        check(
                                            active.effectiveVisible ||
                                                nodeEditing ||
                                                action.kind == AdjustmentKind.LayerBlend
                                        ) {
                                            "请先显示当前图层"
                                        }
                                        adjustmentPreview =
                                            AdjustmentPreview(
                                                info.active,
                                                info.revision,
                                                AdjustmentSettings.defaults(action.kind).let {
                                                    if (action.kind == AdjustmentKind.LayerBlend)
                                                        it.copy(
                                                            opacity = active.opacity,
                                                            blend = active.blend,
                                                        )
                                                    else if (nodeEditing)
                                                        active.adjustment!!.editingSettings()
                                                    else it
                                                },
                                                action.previousTool,
                                                frame,
                                                histogram,
                                                info.selectionId,
                                                nodeEditing,
                                            )
                                    }
                                }
                                is Action.ApplyAdjustment -> {
                                    withContext(Dispatchers.Main) { busy = true }
                                    info =
                                        command(
                                            if (action.preview.nodeEditing)
                                                jsonCommand("set_adjustment") {
                                                    put("id", action.preview.layerId)
                                                    put("revision", action.preview.revision)
                                                    put(
                                                        "settings",
                                                        AdjustmentLayerSettings.from(
                                                                action.settings
                                                            )
                                                            .request(),
                                                    )
                                                }
                                            else
                                                jsonCommand("apply_adjustment") {
                                                    put(
                                                        "request",
                                                        adjustmentRequest(
                                                            action.preview,
                                                            action.settings,
                                                        ),
                                                    )
                                                }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) {
                                        adjustmentPreview = null
                                        currentTool = action.preview.previousTool
                                        status = "调整已完成"
                                    }
                                }
                                Action.PrepareGradient -> {
                                    finishDrawing()
                                    check(!info.maskEditing) {
                                        "请先切换到图层像素再使用渐变"
                                    }
                                    check(
                                        info.layers.first { it.id == info.active }.kind ==
                                            LayerKind.Raster
                                    ) {
                                        "请选择组内图层"
                                    }
                                    val canonical =
                                        info.layers.any {
                                            it.clipping ||
                                                it.kind != LayerKind.Raster ||
                                                it.masks.size > 1
                                        }
                                    val layers =
                                        if (canonical) emptyList() else readLayers(move = true)
                                    val data =
                                        if (canonical) ByteArray(8)
                                        else engine.call(EngineOperation.SELECTION_FRAME)
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
                                                    info.selectionId,
                                                    if (canonical) LayerActionPreview(frame)
                                                    else null,
                                                )
                                    }
                                }
                                is Action.ApplyGradient -> {
                                    info =
                                        command(
                                            jsonCommand("gradient") {
                                                put("id", action.id)
                                                put("revision", action.revision)
                                                put(
                                                    "settings",
                                                    gradientEngineSettings(
                                                        action.line,
                                                        action.settings,
                                                    ),
                                                )
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
                                    val layers =
                                        if (
                                            info.layers.any {
                                                it.clipping ||
                                                    it.kind != LayerKind.Raster ||
                                                    it.masks.size > 1
                                            }
                                        )
                                            emptyList()
                                        else readLayers(info.selection != null, move = true)
                                    withContext(Dispatchers.Main) {
                                        if (
                                            (tool ==
                                                (if (action.transform) Tool.TransformLayer
                                                else Tool.MoveLayer) ||
                                                (tool == Tool.Select && info.selection != null)) &&
                                                document.active == info.active
                                        ) {
                                            layerMove =
                                                LayerMovePreview(
                                                        info.active,
                                                        info.revision,
                                                        layers,
                                                        bounds,
                                                        info.selection,
                                                        info.maskEditing,
                                                        info.selectionId,
                                                        if (
                                                            info.layers.any {
                                                                it.clipping ||
                                                                    it.kind != LayerKind.Raster ||
                                                                    it.masks.size > 1
                                                            }
                                                        )
                                                            LayerActionPreview(frame)
                                                        else null,
                                                        if (info.maskEditing) info.activeMaskId
                                                        else null,
                                                    )
                                                    .also { preview ->
                                                        if (
                                                            info.colorMode ==
                                                                DocumentColorMode.Indexed &&
                                                                !info.maskEditing
                                                        )
                                                            preview.transform =
                                                                preview.transform?.copy(
                                                                    filter = ResampleFilter.Nearest
                                                                )
                                                    }
                                        }
                                    }
                                }
                                is Action.TranslateLayer -> {
                                    check(info.maskEditing == action.maskEditing) {
                                        "图层编辑目标已变化，请重新移动"
                                    }
                                    check(
                                        !action.maskEditing || info.activeMaskId == action.maskId
                                    ) {
                                        "蒙版编辑目标已变化，请重新移动"
                                    }
                                    check(info.revision == action.revision) { "图层已变化，请重新移动" }
                                    info =
                                        command(
                                            jsonCommand("translate_layer") {
                                                put("id", action.id)
                                                put("dx", action.offset.x)
                                                put("dy", action.offset.y)
                                                action.maskId?.let { put("mask_id", it) }
                                            }
                                        )
                                    publishFrame()
                                    withContext(Dispatchers.Main) { layerMove = null }
                                }
                                is Action.TransformLayer -> {
                                    check(info.maskEditing == action.maskEditing) {
                                        "图层编辑目标已变化，请重新变换"
                                    }
                                    check(
                                        !action.maskEditing || info.activeMaskId == action.maskId
                                    ) {
                                        "蒙版编辑目标已变化，请重新变换"
                                    }
                                    info =
                                        command(
                                            jsonCommand("transform_layer") {
                                                put("id", action.id)
                                                put("revision", action.revision)
                                                put(
                                                    "transform",
                                                    Json.encodeToJsonElement(action.value),
                                                )
                                                action.maskId?.let { put("mask_id", it) }
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
                                                        jsonCommand(
                                                            if (destination.palette == null) "new"
                                                            else "new_indexed"
                                                        ) {
                                                            put("width", destination.width)
                                                            put("height", destination.height)
                                                            destination.palette?.let {
                                                                put(
                                                                    "palette",
                                                                    Json.encodeToJsonElement(it),
                                                                )
                                                            }
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
                                        val pickedResult =
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
                                                .jsonObject
                                        val picked = pickedResult.getValue("color").jsonArray
                                        val pickedIndex =
                                            pickedResult["index"]?.jsonPrimitive?.intOrNull
                                        val color =
                                            0xFF000000L or
                                                (picked[0].jsonPrimitive.long shl 16) or
                                                (picked[1].jsonPrimitive.long shl 8) or
                                                picked[2].jsonPrimitive.long
                                        withContext(Dispatchers.Main) {
                                            if (
                                                !document.maskEditing &&
                                                    document.indexedPalette != null
                                            )
                                                selectIndexedColor(
                                                    pickedIndex
                                                        ?: nearestPaletteIndex(
                                                            document.indexedPalette!!,
                                                            color,
                                                        )
                                                )
                                            else brush = brush.copy(color = color)
                                            tool = Tool.Brush
                                        }
                                    } else {
                                        if (
                                            type in
                                                setOf(
                                                    "fill",
                                                    "fill_lasso",
                                                    "add_mask",
                                                    "invert_mask",
                                                    "apply_mask",
                                                    "select_shape",
                                                    "combine_selection",
                                                    "invert_selection",
                                                    "tone",
                                                    "blur",
                                                    "merge_visible",
                                                    "resize_canvas",
                                                    "resize_image",
                                                    "fill_indexed",
                                                    "modify_selection",
                                                    "convert_color_mode",
                                                    "set_palette_color",
                                                    "add_palette_color",
                                                    "remove_palette_color",
                                                    "reorder_palette",
                                                )
                                        )
                                            withContext(Dispatchers.Main) { busy = true }
                                        val request =
                                            if (type == "begin" && action.json["assistant"] != null)
                                                JsonObject(
                                                    action.json +
                                                        ("assistant" to
                                                            buildJsonObject {
                                                                put(
                                                                    "id",
                                                                    action.json
                                                                        .getValue("assistant")
                                                                        .jsonObject
                                                                        .getValue("id"),
                                                                )
                                                                put("revision", info.revision)
                                                            })
                                                )
                                            else action.json
                                        val previousInfo = info
                                        info =
                                            command(
                                                if (type in setOf("begin", "end", "cancel"))
                                                    request.rebindAnimationStroke(info)
                                                else request
                                            )
                                        val assistantMetadata =
                                            type in
                                                setOf(
                                                    "add_assistant",
                                                    "set_assistant",
                                                    "delete_assistant",
                                                    "set_assistant_snap",
                                                ) ||
                                                (type in setOf("undo", "redo") &&
                                                    previousInfo.assistants != info.assistants &&
                                                    previousInfo.width == info.width &&
                                                    previousInfo.height == info.height &&
                                                    previousInfo.layers == info.layers &&
                                                    previousInfo.indexedPalette ==
                                                        info.indexedPalette &&
                                                    previousInfo.active == info.active &&
                                                    previousInfo.activeMaskId ==
                                                        info.activeMaskId &&
                                                    previousInfo.selection == info.selection &&
                                                    previousInfo.maskEditing == info.maskEditing)
                                        if (
                                            assistantMetadata &&
                                                previewRevision == previousInfo.revision
                                        ) {
                                            previewRevision = info.revision
                                            withContext(Dispatchers.Main) {
                                                previews = previews.copy(revision = info.revision)
                                            }
                                        }
                                        if (type == "add_assistant")
                                            withContext(Dispatchers.Main) {
                                                selectedAssistantId =
                                                    info.assistants.items.lastOrNull()?.id
                                            }
                                        if (type == "create_vector")
                                            withContext(Dispatchers.Main) {
                                                selectedVectorObject = null
                                                vectorTool = StudioDefaults.vectorTool
                                                tool = Tool.Vector
                                            }
                                        if (type == "remove_palette_color")
                                            withContext(Dispatchers.Main) {
                                                val removed =
                                                    action.json.getValue("index").jsonPrimitive.int
                                                val replacement =
                                                    action.json
                                                        .getValue("replacement")
                                                        .jsonPrimitive
                                                        .int
                                                val selected =
                                                    if (indexedColorIndex == removed) replacement
                                                    else indexedColorIndex
                                                indexedColorIndex =
                                                    if (selected > removed) selected - 1
                                                    else selected
                                            }
                                        if (
                                            type in
                                                setOf(
                                                    "select_shape",
                                                    "combine_selection",
                                                    "invert_selection",
                                                )
                                        ) {
                                            withContext(Dispatchers.Main) {
                                                status =
                                                    if (info.selection?.empty == true) "选区为空"
                                                    else "选区已更新"
                                            }
                                        }
                                        if (type == "begin") {
                                            drawing = true
                                            drawingEpoch = action.strokeEpoch ?: drawingEpoch
                                        }
                                        if (type == "end" || type == "cancel") {
                                            drawing = false
                                            withContext(Dispatchers.Main) {
                                                if (brushInputEpoch == drawingEpoch)
                                                    drawingInput = false
                                            }
                                        }
                                        if (type == "new") {
                                            resetCanvas(null)
                                        }
                                        if (type != "begin") publishFrame()
                                    }
                                }
                                Action.Frame -> refreshPreviews()
                                is Action.Clipboard -> {
                                    val clipboard = files.clipboard ?: error("当前平台暂不支持图片剪贴板")
                                    finishDrawing()
                                    withContext(Dispatchers.Main) { busy = true }
                                    if (action.kind == ClipboardAction.Paste) {
                                        val image = clipboard.read() ?: error("剪贴板中没有图片")
                                        info =
                                            parser.decodeFromString(
                                                engine
                                                    .call(
                                                        EngineOperation.PASTE_IMAGE,
                                                        image.toNativePacket(),
                                                    )
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
                                                ClipboardAction.Copy,
                                                ClipboardAction.CopyVisible -> "已复制图片"
                                                ClipboardAction.Cut -> "已剪切，可撤销"
                                                ClipboardAction.Paste -> "已粘贴到新图层"
                                            }
                                    }
                                }
                                is Action.AnimationExport -> {
                                    require(!drawing && info.revision == action.revision) {
                                        "动画已改变，请重新导出"
                                    }
                                    val issue = action.options.validation(info)
                                    require(issue == null) { issue?.label.orEmpty() }
                                    withContext(Dispatchers.Main) { busy = true }
                                    val bytes =
                                        engine.call(
                                            EngineOperation.ANIMATION_EXPORT,
                                            action.options
                                                .requestJson(action.revision)
                                                .toString()
                                                .encodeToByteArray(),
                                        )
                                    if (files.exportAnimation(bytes, action.options.format))
                                        withContext(Dispatchers.Main) { status = "动画已导出" }
                                }
                                is Action.AsepriteExport -> {
                                    require(!drawing && info.revision == action.revision) {
                                        "工程已改变，请重新导出 Aseprite 副本"
                                    }
                                    val capability = info.asepriteExport
                                    require(
                                        capability?.available == true &&
                                            files.supportsAsepriteProjects
                                    ) {
                                        "当前平台不支持 Aseprite 工程导出"
                                    }
                                    require(capability.blockingIssues.isEmpty()) {
                                        "当前工程包含暂不支持的 Aseprite 播放或工程数据"
                                    }
                                    require(
                                        action.options.bakeLayers ||
                                            capability.editableIssues.isEmpty()
                                    ) {
                                        "请明确选择导出合成帧副本"
                                    }
                                    withContext(Dispatchers.Main) { busy = true }
                                    val bytes =
                                        engine.call(
                                            EngineOperation.ASEPRITE_EXPORT,
                                            action.options
                                                .requestJson(action.revision)
                                                .toString()
                                                .encodeToByteArray(),
                                        )
                                    if (files.exportAseprite(bytes))
                                        withContext(Dispatchers.Main) {
                                            status = "Aseprite 工程副本已导出"
                                        }
                                }
                                is Action.Export -> {
                                    finishDrawing()
                                    withContext(Dispatchers.Main) { busy = true }
                                    val bytes =
                                        if (action.options.format == ExportFormat.Svg)
                                            engine.call(
                                                EngineOperation.VECTOR_SVG,
                                                action.vectorTarget
                                                    .rebindAnimationStroke(info)
                                                    .let { target ->
                                                        buildJsonObject {
                                                            target.forEach { (key, value) ->
                                                                if (key != "revision")
                                                                    put(key, value)
                                                            }
                                                            put("revision", info.revision)
                                                        }
                                                    }
                                                    .toString()
                                                    .encodeToByteArray(),
                                            )
                                        else
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
                                                            preferences
                                                                .copy(
                                                                    plugins =
                                                                        preferences.plugins
                                                                            .filterNot {
                                                                                it.id == pack.id
                                                                            } + pack
                                                                )
                                                                .withAvailableBrushFavorites()
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
                                        FileAction.ImportPalette -> {
                                            check(files.supportsPaletteFiles) { "当前平台暂不支持色板文件" }
                                            files.openPalette()?.let { bytes ->
                                                val text =
                                                    if (
                                                        bytes
                                                            .take(4)
                                                            .toByteArray()
                                                            .contentEquals(
                                                                "ASEF".encodeToByteArray()
                                                            )
                                                    )
                                                        null
                                                    else TextPaletteCodec.decode(bytes)
                                                val imported =
                                                    text?.palette ?: AsePaletteCodec.decode(bytes)
                                                val current =
                                                    withContext(Dispatchers.Main) {
                                                        preferences.asePalette
                                                            ?: paletteFromColors(
                                                                preferences.palette
                                                            )
                                                    }
                                                val offset = current.swatches.size
                                                val merged =
                                                    AsePalette(
                                                        current.swatches + imported.swatches,
                                                        current.groups +
                                                            imported.groups.map {
                                                                it.copy(
                                                                    start = it.start + offset,
                                                                    end = it.end + offset,
                                                                )
                                                            },
                                                    )
                                                require(merged.valid()) { "色卡空间不足，请先移除一些颜色" }
                                                val updated =
                                                    withContext(Dispatchers.Main) {
                                                        preferences
                                                            .copy(
                                                                palette =
                                                                    merged.swatches
                                                                        .map { it.color }
                                                                        .distinct(),
                                                                asePalette = merged,
                                                                paletteFileMetadata =
                                                                    if (
                                                                        text != null &&
                                                                            current.swatches
                                                                                .isEmpty()
                                                                    )
                                                                        PaletteFileMetadata(
                                                                            text.name,
                                                                            text.columns,
                                                                        )
                                                                    else
                                                                        preferences
                                                                            .paletteFileMetadata,
                                                            )
                                                            .also {
                                                                check(it.valid()) { "设置参数无效" }
                                                                preferences = it
                                                                status = "色板已导入"
                                                            }
                                                    }
                                                files.writePreferences(
                                                    parser
                                                        .encodeToString(updated)
                                                        .encodeToByteArray()
                                                )
                                            }
                                        }
                                        FileAction.ExportPalette -> {
                                            check(action.paletteFormat in files.paletteFormats) {
                                                "当前平台暂不支持色板文件"
                                            }
                                            val snapshot =
                                                withContext(Dispatchers.Main) {
                                                    preferences
                                                }
                                            val palette =
                                                snapshot.asePalette
                                                    ?: paletteFromColors(snapshot.palette)
                                            val bytes =
                                                if (action.paletteFormat == PaletteFileFormat.Ase)
                                                    AsePaletteCodec.encode(palette)
                                                else
                                                    TextPaletteCodec.encode(
                                                        TextPaletteFile(
                                                            palette,
                                                            textPaletteFormat(action.paletteFormat),
                                                            snapshot.paletteFileMetadata.name,
                                                            snapshot.paletteFileMetadata.columns,
                                                        ),
                                                        action.flattenPalette,
                                                    )
                                            if (files.savePalette(bytes, action.paletteFormat))
                                                withContext(Dispatchers.Main) {
                                                    status = "色板已导出"
                                                }
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
                            if (action !is Action.AsepriteExport) {
                                pending.clear()
                                if (drawing) {
                                    info = command(jsonCommand("cancel"))
                                    drawing = false
                                    publishFrame()
                                }
                            }
                            withContext(Dispatchers.Main) {
                                if (action is Action.PrepareAdjustment) {
                                    adjustmentPreview = null
                                    currentTool = action.previousTool
                                }
                                if (action is Action.ApplyAdjustment)
                                    adjustmentPreview?.committing = false
                                if (action == Action.Frame) {
                                    adjustmentPreview?.let { currentTool = it.previousTool }
                                    adjustmentPreview = null
                                }
                                if (action is Action.ApplyGradient)
                                    gradientPreview?.committing = false
                                if (action is Action.ApplyVector) vectorPreview?.committing = false
                                if (action is Action.ApplyAssistant)
                                    assistantPreview?.committing = false
                                if (action is Action.ApplyLineGenerator)
                                    lineGeneratorPreview?.committing = false
                                if (action == Action.Frame && assistantPreview?.updating == true)
                                    cancelAssistant()
                                if (action == Action.Frame && (animationPlaying || onionEnabled)) {
                                    stopAnimation()
                                    onionEnabled = false
                                    onionPrevious = null
                                    onionNext = null
                                }
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
                                if (
                                    action is Action.Command &&
                                        action.json["type"]?.jsonPrimitive?.content == "begin" &&
                                        action.strokeEpoch == brushInputEpoch
                                )
                                    drawingInput = false
                                error = exception.message ?: "操作失败，请重试"
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                if (action is Action.AnimationCommand) animationTransition = false
                                if (action == Action.ExtractPalette) extractingPalette = false
                                if (action is Action.PrepareLayerMove) preparingLayerMove = false
                                if (action == Action.PrepareGradient) preparingGradient = false
                                if (action is Action.PrepareAdjustment) preparingAdjustment = false
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

    fun toggleAnimationTimeline() {
        if (!ready || busy || drawingInput || animationTransition || previewPending()) return
        if (document.animation == null) {
            animationTimelineVisible = true
            animationCommand("enable_animation") {
                put("duration_ms", StudioDefaults.animationFrameDuration)
            }
        } else animationTimelineVisible = !animationTimelineVisible
    }

    fun hideAnimationTimeline() {
        if (drawingInput || animationTransition) return
        animationTimelineVisible = false
    }

    fun addAnimationFrame() {
        val animation = document.animation ?: return
        if (animation.frames.size >= animation.maxFrames) return
        val index = animation.frames.indexOfFirst { it.id == animation.activeFrameId }
        animationCommand("add_frame") {
            put("index", index + 1)
            put("duration_ms", animation.frames[index].durationMs)
        }
    }

    fun selectAdjacentAnimationFrame(offset: Int) {
        val animation = document.animation ?: return
        val index =
            animation.frames.indexOfFirst {
                it.id == (animationDisplayFrameId ?: animation.activeFrameId)
            }
        val next = animation.frames.getOrNull(index + offset) ?: return
        stopAnimation()
        animationCommand("select_frame") { put("frame_id", next.id) }
    }

    fun reorderAnimationFrame(id: Int, index: Int) {
        val frames = document.animation?.frames ?: return
        if (index !in frames.indices || frames.none { it.id == id }) return
        val order = frames.map { it.id }.toMutableList()
        order.remove(id)
        order.add(index, id)
        animationCommand("reorder_frames") {
            putJsonArray("ids") { order.forEach { add(it) } }
        }
    }

    fun requestAnimationThumbnails(ids: List<Int>) {
        val validIds = document.animation?.frames?.map { it.id }?.toSet().orEmpty()
        animationThumbnailRequests =
            ids.distinct().filter { it in validIds }.take(document.maxFrameThumbnails)
    }

    fun animationCommand(
        type: String,
        layerId: Int? = null,
        values: JsonObjectBuilder.() -> Unit = {},
    ) {
        if (
            !ready ||
                busy ||
                drawingInput ||
                animationTransition ||
                document.maxAnimationFrames == 0 ||
                previewPending()
        )
            return
        val request =
            jsonCommand(type) {
                put("revision", document.revision)
                values()
            }
        animationTransition = true
        scope.launch { actions.send(Action.AnimationCommand(request, layerId)) }
    }

    fun startAnimation(
        tagId: Int? = animationTagId,
        direction: AnimationDirection = animationDirection,
        repeat: Int = 0,
    ) {
        if (!ready || busy || drawingInput || animationTransition || previewPending()) return
        val animation = document.animation ?: return
        val plan = AnimationPlaybackPlan.create(animation, tagId, direction, repeat) ?: return
        animationPlayback =
            AnimationPlaybackSession(plan, document.contentId, TimeSource.Monotonic.markNow())
        animationDisplayFrame = null
        animationDisplayFrameId = null
    }

    fun stopAnimation() {
        animationPlayback = null
        animationDisplayFrame = null
        animationDisplayFrameId = null
    }

    fun command(type: String, values: JsonObjectBuilder.() -> Unit = {}) {
        if (previewPending()) return
        if (ready && !busy) {
            val request =
                jsonCommand(type, values).withAnimationTarget(document, includeRevision = false)
            scope.launch { actions.send(Action.Command(request)) }
        }
    }

    private fun previewPending(): Boolean {
        if (animationPlaying || animationTransition) {
            error = "请先停止动画播放或等待帧切换完成"
            return true
        }
        if (lineGeneratorPreview != null) {
            error = "请先确认或取消线条生成"
            return true
        }
        if (assistantPreview != null) {
            error = "请先确认或取消助手编辑"
            return true
        }
        if (vectorPreview != null || vectorPendingPath != null) {
            error = "请先确认或取消矢量编辑"
            return true
        }
        if (preparingAdjustment || adjustmentPreview != null) {
            error = "请先确认或取消调整"
            return true
        }
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
        if (document.maskEditing) {
            error = "请先切换到图层像素再使用渐变"
            return
        }
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (active.kind != LayerKind.Raster) {
            error = "请选择组内图层"
            return
        }
        if (!active.effectiveVisible || active.effectiveLocked) return
        preparingGradient = true
        busy = true
        scope.launch { actions.send(Action.PrepareGradient) }
    }

    fun prepareAdjustment(kind: AdjustmentKind) {
        if (!ready || busy || previewPending()) return
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        val nodeEditing = active.kind == LayerKind.Adjustment && kind != AdjustmentKind.LayerBlend
        if (
            kind != AdjustmentKind.LayerBlend &&
                (active.kind == LayerKind.Group ||
                    active.kind == LayerKind.Vector ||
                    (nodeEditing && active.adjustment?.kind != kind))
        ) {
            error = "请选择组内图层"
            return
        }
        if (document.maskEditing && kind != AdjustmentKind.LayerBlend) {
            error = "请先切换到图层像素再使用调整"
            return
        }
        if (
            document.colorMode == DocumentColorMode.Indexed &&
                !document.maskEditing &&
                kind != AdjustmentKind.LayerBlend &&
                !nodeEditing
        ) {
            error = "索引色模式暂不支持此调整"
            return
        }
        if (
            kind != AdjustmentKind.LayerBlend &&
                (active.effectiveLocked || (!active.effectiveVisible && !nodeEditing))
        )
            return
        val previousTool = tool
        cancelGradient()
        layerMove = null
        preparingAdjustment = true
        busy = true
        currentTool = Tool.Hand
        scope.launch { actions.send(Action.PrepareAdjustment(kind, previousTool)) }
    }

    fun updateAdjustment(settings: AdjustmentSettings) {
        val preview = adjustmentPreview ?: return
        if (!preview.committing && settings.kind == preview.settings.kind && settings.valid())
            preview.settings = settings
    }

    fun cancelAdjustment() {
        val preview = adjustmentPreview ?: return
        if (preview.committing) return
        adjustmentPreview = null
        currentTool = preview.previousTool
    }

    fun commitAdjustment() {
        val preview = adjustmentPreview ?: return
        if (
            !ready ||
                busy ||
                preview.updating ||
                !preview.changed ||
                preview.committing ||
                !preview.inputValid
        )
            return
        preview.committing = true
        busy = true
        scope.launch { actions.send(Action.ApplyAdjustment(preview, preview.settings)) }
    }

    private fun adjustmentRequest(preview: AdjustmentPreview, settings: AdjustmentSettings) =
        buildJsonObject {
            put("id", preview.layerId)
            put("revision", preview.revision)
            put("selection_id", preview.selectionId)
            if (preview.nodeEditing) {
                put("mask_editing", false)
                putJsonObject("action") {
                    put("kind", "adjustment")
                    put("settings", AdjustmentLayerSettings.from(settings).request())
                }
            } else put("settings", Json.encodeToJsonElement(settings))
        }

    private fun canonicalLayerAction(): Pair<LayerActionPreview, JsonObject>? {
        lineGeneratorPreview
            ?.takeIf { !it.committing && it.settings.valid(document.maxGeneratedLines) }
            ?.let {
                return it.canonical to it.request()
            }
        vectorPreview
            ?.takeUnless { it.committing }
            ?.let {
                if (it.value.valid()) return it.canonical to it.request()
            }
        val move = layerMove?.takeUnless { it.committing }
        if (move?.canonical != null) {
            val action = buildJsonObject {
                val transform = move.transform
                if (transform != null) {
                    put("kind", "transform")
                    put("transform", Json.encodeToJsonElement(transform))
                } else {
                    put("kind", "translate")
                    put("dx", move.offset.x)
                    put("dy", move.offset.y)
                }
            }
            return move.canonical to
                buildJsonObject {
                    put("id", move.layerId)
                    put("revision", move.revision)
                    put("selection_id", move.selectionId)
                    put("mask_editing", move.maskEditing)
                    move.maskId?.let { put("mask_id", it) }
                    put("action", action)
                }
        }
        val preview = gradientPreview?.takeUnless { it.committing } ?: return null
        val canonical = preview.canonical ?: return null
        val line = preview.line?.takeIf { it.valid() } ?: return null
        return canonical to
            buildJsonObject {
                put("id", preview.layerId)
                put("revision", preview.revision)
                put("selection_id", preview.selectionId)
                put("mask_editing", false)
                putJsonObject("action") {
                    put("kind", "gradient")
                    put("settings", gradientEngineSettings(line, gradient))
                }
            }
    }

    private fun gradientEngineSettings(line: GradientLine, settings: GradientSettings) =
        buildJsonObject {
            putJsonArray("start") {
                add(line.start.x)
                add(line.start.y)
            }
            putJsonArray("end") {
                add(line.end.x)
                add(line.end.y)
            }
            fun color(name: String, value: Long) {
                putJsonArray(name) {
                    add((value shr 16 and 255).toInt())
                    add((value shr 8 and 255).toInt())
                    add((value and 255).toInt())
                    add((value shr 24 and 255).toInt())
                }
            }
            color("from", settings.startColor)
            color("to", settings.endColor)
            put("opacity", settings.opacity)
            put("shape", settings.shape.name.lowercase())
        }

    fun previewGradient(line: GradientLine?) {
        val preview = gradientPreview ?: return
        if (!preview.committing) {
            preview.line = line
            if (line?.valid() != true)
                preview.canonical?.let {
                    it.frame = it.original
                    it.renderedAction = null
                }
        }
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
                (tool != Tool.MoveLayer &&
                    tool != Tool.TransformLayer &&
                    !(tool == Tool.Select && document.selection != null))
        )
            return
        val transform = tool == Tool.TransformLayer
        if (
            document.colorMode == DocumentColorMode.Indexed &&
                !document.maskEditing &&
                document.selection != null
        ) {
            error = "索引色选区移动尚未实现"
            return
        }
        layerMove?.let {
            if (
                (it.sourceBounds != null) == transform &&
                    it.selection == document.selection &&
                    it.maskEditing == document.maskEditing &&
                    (!it.maskEditing || it.maskId == document.activeMaskId) &&
                    it.revision == document.revision &&
                    it.layerId == document.active
            )
                return
            layerMove = null
        }
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (
            !active.effectiveVisible ||
                active.effectiveLocked ||
                (active.kind == LayerKind.Adjustment && !document.maskEditing) ||
                (active.alphaLocked && !document.maskEditing) ||
                (transform && document.selection != null)
        )
            return
        preparingLayerMove = true
        busy = true
        scope.launch { actions.send(Action.PrepareLayerMove(transform)) }
    }

    fun previewLayerTransform(value: LayerTransform) {
        val preview = layerMove ?: return
        if (preview.sourceBounds != null && !preview.committing && value.valid())
            preview.transform =
                if (!document.maskEditing && document.colorMode == DocumentColorMode.Indexed)
                    value.copy(filter = ResampleFilter.Nearest)
                else value
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
            actions.send(
                Action.TransformLayer(
                    preview.layerId,
                    preview.revision,
                    value,
                    preview.maskEditing,
                    preview.maskId,
                )
            )
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
            actions.send(
                Action.TranslateLayer(
                    preview.layerId,
                    preview.revision,
                    preview.offset,
                    preview.maskEditing,
                    preview.maskId,
                )
            )
        }
    }

    fun cancelLayerMove(exit: Boolean = false) {
        if (layerMove?.committing == true) return
        layerMove?.offset = IntOffset.Zero
        layerMove?.canonical?.let {
            it.frame = it.original
            it.renderedAction = null
        }
        if (exit) layerMove = null
    }

    suspend fun begin(point: Offset, pressure: Float, stylusEraser: Boolean = false) {
        if (
            !ready ||
                busy ||
                animationPlaying ||
                animationTransition ||
                assistantPreview != null ||
                lineGeneratorPreview != null
        )
            return
        val settings = brush
        brushInputEpoch++
        val strokeEpoch = brushInputEpoch
        drawingInput = true
        val smudge = tool == Tool.Smudge && !stylusEraser
        val symmetry =
            if (tool == Tool.Brush || tool == Tool.Eraser) symmetry else SymmetrySettings()
        actions.send(
            Action.Command(
                jsonCommand("begin") {
                        if (!smudge && (tool == Tool.Brush || tool == Tool.Eraser || stylusEraser))
                            document.assistants.snapId?.let { id ->
                                putJsonObject("assistant") {
                                    put("id", id)
                                    put("revision", document.revision)
                                }
                            }
                        put(
                            "brush",
                            engineBrushJson(
                                settings.copy(
                                    opacity = if (smudge) smudgeStrength else settings.opacity
                                ),
                                smudge = smudge,
                                eraser = tool == Tool.Eraser || stylusEraser,
                                symmetry = symmetry,
                                index =
                                    if (document.maskEditing) null
                                    else document.indexedPalette?.let { indexedColorIndex },
                            ),
                        )
                    }
                    .withAnimationTarget(document, includeRevision = false),
                strokeEpoch,
            )
        )
        points(listOf(Triple(point.x, point.y, pressure)))
    }

    fun addAssistant(preset: AssistantPreset) {
        if (document.assistants.items.size >= document.maxDrawingAssistants) return
        val value = preset.spec(document)
        command("add_assistant") {
            put("assistant", value.request())
            put("revision", document.revision)
        }
    }

    fun prepareLineGenerator() {
        if (!ready || busy || previewPending()) return
        if (
            document.colorMode != DocumentColorMode.Rgba ||
                document.selection != null ||
                document.maskEditing ||
                document.maxGeneratedLines == 0 ||
                document.drawableLayerCount >= document.maxLayers ||
                document.layers.size >= document.maxLayerNodes
        )
            return
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        val parentId = if (active.kind == LayerKind.Group) active.id else active.parentId
        val parent = document.layers.firstOrNull { it.id == parentId }
        if (parent?.effectiveLocked == true || parent?.effectiveVisible == false) return
        val siblings = document.siblings(parentId)
        var index =
            if (active.kind == LayerKind.Group) siblings.size
            else siblings.indexOfFirst { it.id == active.id } + 1
        while (index < siblings.size && siblings[index].clipping) index++
        lineGeneratorPreview =
            LineGeneratorPreview(
                active.id,
                document.revision,
                document.selectionId,
                parentId,
                index,
                defaultLineGenerator(document, brush, LineGeneratorKind.Concentration),
                frame,
            )
        currentTool = Tool.LineGenerator
    }

    fun updateLineGenerator(settings: LineGeneratorSettings) {
        val preview = lineGeneratorPreview ?: return
        if (preview.committing) return
        preview.settings = settings
        preview.error = if (settings.valid(document.maxGeneratedLines)) null else "线条参数无效"
    }

    fun changeLineGeneratorKind(kind: LineGeneratorKind) {
        updateLineGenerator(defaultLineGenerator(document, brush, kind))
    }

    fun commitLineGenerator() {
        val preview = lineGeneratorPreview ?: return
        if (
            preview.committing ||
                preview.updating ||
                preview.error != null ||
                !preview.settings.valid(document.maxGeneratedLines)
        )
            return
        preview.committing = true
        scope.launch { actions.send(Action.ApplyLineGenerator(preview, preview.settings)) }
    }

    fun cancelLineGenerator() {
        if (lineGeneratorPreview?.committing == true) return
        lineGeneratorPreview = null
        if (currentTool == Tool.LineGenerator) currentTool = Tool.Brush
    }

    fun selectAssistant(id: Int) {
        if (!busy && !previewPending() && document.assistants.items.any { it.id == id })
            selectedAssistantId = id
    }

    fun beginAssistantEdit(id: Int): Boolean {
        if (!ready || busy || assistantPreview != null || previewPending()) return false
        val value = document.assistants.items.firstOrNull { it.id == id } ?: return false
        selectedAssistantId = id
        assistantPreview = AssistantEditPreview(id, document.revision, value.spec())
        return true
    }

    fun previewAssistant(value: DrawingAssistantSpec) {
        val preview = assistantPreview ?: return
        if (preview.committing) return
        preview.value = value
        preview.updating = value.valid(document.maxAssistantCoordinate)
    }

    fun commitAssistant() {
        val preview = assistantPreview ?: return
        if (preview.committing) return
        if (
            preview.value == preview.initial ||
                !preview.value.valid(document.maxAssistantCoordinate)
        ) {
            cancelAssistant()
            return
        }
        preview.committing = true
        scope.launch { actions.send(Action.ApplyAssistant(preview, preview.value)) }
    }

    fun cancelAssistant() {
        if (assistantPreview?.committing == true) return
        assistantPreview = null
        assistantCancellation++
    }

    suspend fun points(values: List<Triple<Float, Float, Float>>) {
        actions.send(Action.Points(values))
    }

    suspend fun end(cancel: Boolean = false) {
        actions.send(
            Action.Command(
                jsonCommand(if (cancel) "cancel" else "end")
                    .withAnimationTarget(document, includeRevision = false)
            )
        )
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

    fun selectLayer(id: Int, mask: Boolean = false, maskId: Int? = null) =
        command("set_mask_editing") {
            put("id", id)
            put("enabled", mask)
            (maskId ?: document.layers.firstOrNull { it.id == id }?.mask?.id)
                ?.takeIf { mask && it > 0 }
                ?.let { put("mask_id", it) }
        }

    fun addLayerMask(mode: String) =
        command("add_mask") {
            put("mode", mode)
            put("revision", document.revision)
            put("selection_id", document.selectionId)
        }

    fun setLayerMask(
        id: Int,
        enabled: Boolean? = null,
        linked: Boolean? = null,
        maskId: Int? = null,
        name: String? = null,
    ) =
        command("set_mask") {
            put("id", id)
            (maskId ?: document.layers.firstOrNull { it.id == id }?.mask?.id)
                ?.takeIf { it > 0 }
                ?.let { put("mask_id", it) }
            put("revision", document.revision)
            enabled?.let { put("enabled", it) }
            linked?.let { put("linked", it) }
            name?.let { put("name", it) }
        }

    fun maskCommand(type: String, layerId: Int, maskId: Int) =
        command(type) {
            put("id", layerId)
            if (maskId > 0) put("mask_id", maskId)
            put("revision", document.revision)
        }

    fun reorderMask(layerId: Int, maskId: Int, index: Int) =
        command("reorder_mask") {
            put("id", layerId)
            put("mask_id", maskId)
            put("index", index)
            put("revision", document.revision)
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
        if (
            document.colorMode == DocumentColorMode.Indexed &&
                !document.maskEditing &&
                preset.raster == BrushRaster.Antialiased
        ) {
            error = "索引色模式请使用像素画笔"
            return
        }
        brush = brush.copy(preset = preset, size = preset.size, opacity = preset.opacity)
        if (preset.raster != BrushRaster.Antialiased || document.maskEditing) {
            tool = Tool.Brush
            return
        }
        if (preset.mix > 0f) {
            tool = Tool.Smudge
            smudgeStrength = preset.mix
            return
        }
        if (tool != Tool.Smudge) tool = Tool.Brush
    }

    fun setLayerBlend(id: Int, mode: LayerBlendMode) =
        command("set_blend") {
            put("id", id)
            put("mode", parser.encodeToJsonElement(mode))
        }

    fun setLayerClipping(id: Int, clipping: Boolean) {
        val revision = document.revision
        command("set_clipping") {
            put("id", id)
            put("clipping", clipping)
            put("revision", revision)
        }
    }

    fun createLayerGroup() {
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        val parent = if (active.kind == LayerKind.Group) active.id else active.parentId
        val siblings = document.siblings(parent)
        var index =
            if (parent == active.id) siblings.size
            else siblings.indexOfFirst { it.id == active.id } + 1
        while (siblings.getOrNull(index)?.clipping == true) index++
        val revision = document.revision
        command("create_group") {
            put("name", "图层组")
            put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("index", index)
            put("revision", revision)
        }
    }

    fun createVectorLayer() {
        if (document.colorMode != DocumentColorMode.Rgba || document.maskEditing) return
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (active.effectiveLocked || document.drawableLayerCount >= document.maxLayers) return
        val parent = if (active.kind == LayerKind.Group) active.id else active.parentId
        val siblings = document.siblings(parent)
        var index = if (parent == active.id) siblings.size else siblings.indexOf(active) + 1
        while (siblings.getOrNull(index)?.clipping == true) index++
        command("create_vector") {
            put("name", "矢量图层")
            put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("index", index)
            put("revision", document.revision)
        }
    }

    fun selectVectorObject(objectId: Int?) {
        if (!ready || busy || previewPending()) return
        vectorQueryVersion++
        val action =
            Action.SelectVectorObject(
                document.active,
                document.revision,
                objectId,
                vectorQueryVersion,
            )
        scope.launch { actions.send(action) }
    }

    fun pickVectorObject(point: Offset, tolerance: Float) {
        if (!ready || busy || previewPending()) return
        vectorQueryVersion++
        val action =
            Action.PickVectorObject(
                document.active,
                document.revision,
                point,
                tolerance.coerceIn(0f, 16f),
                vectorQueryVersion,
            )
        scope.launch { actions.send(action) }
    }

    fun beginVectorEdit(value: VectorObjectSpec, objectId: Int? = null): Boolean {
        if (!ready || busy || vectorPreview != null || !value.valid()) return false
        val active = document.layers.firstOrNull { it.id == document.active } ?: return false
        if (
            active.kind != LayerKind.Vector ||
                active.effectiveLocked ||
                !active.effectiveVisible ||
                document.maskEditing ||
                document.selection != null ||
                previewPending()
        )
            return false
        val initial =
            if (objectId == null) null
            else {
                selectedVectorObject
                    ?.takeIf {
                        it.id == active.id &&
                            it.objectId == objectId &&
                            it.revision == document.revision
                    }
                    ?.`object` ?: return false
            }
        vectorPreview =
            VectorEditPreview(
                active.id,
                document.revision,
                document.selectionId,
                objectId,
                initial,
                value,
                frame,
            )
        return true
    }

    fun previewVector(value: VectorObjectSpec) {
        vectorPreview
            ?.takeUnless { it.committing }
            ?.let {
                it.value = value
                if (!value.valid()) {
                    it.canonical.frame = it.canonical.original
                    it.canonical.renderedAction = null
                }
            }
    }

    fun commitVector() {
        val preview = vectorPreview ?: return
        if (!ready || busy || preview.committing) return
        if (!preview.value.valid()) {
            vectorPreview = null
            return
        }
        if (preview.value == preview.initial) {
            vectorPreview = null
            return
        }
        preview.committing = true
        busy = true
        val action = Action.ApplyVector(preview, preview.value)
        scope.launch { actions.send(action) }
    }

    fun cancelVector() {
        if (vectorPreview?.committing != true) {
            vectorPreview = null
            vectorPendingPath = null
            vectorCancellation++
        }
    }

    fun setVectorObject(value: VectorObjectSpec) {
        val selected = selectedVectorObject ?: return
        if (beginVectorEdit(value, selected.objectId)) commitVector()
    }

    fun vectorObjectCommand(type: String, index: Int? = null) {
        val selected = selectedVectorObject ?: return
        command(type) {
            put("id", selected.id)
            put("object_id", selected.objectId)
            put("revision", selected.revision)
            index?.let { put("index", it) }
        }
    }

    fun createAdjustmentLayer(kind: AdjustmentKind) {
        val settings = AdjustmentLayerSettings(kind)
        if (!settings.valid() || !ready || busy || previewPending() || document.maskEditing) return
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        if (active.effectiveLocked || document.layers.size >= document.maxLayerNodes) return
        val parent = if (active.kind == LayerKind.Group) active.id else active.parentId
        val siblings = document.siblings(parent)
        var index = if (parent == active.id) siblings.size else siblings.indexOf(active) + 1
        while (siblings.getOrNull(index)?.clipping == true) index++
        val revision = document.revision
        val selectionId = document.selectionId
        command("create_adjustment") {
            put("name", kind.label)
            put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("index", index)
            put("settings", settings.request())
            put("revision", revision)
            put("selection_id", selectionId)
        }
    }

    fun editAdjustmentLayer() {
        val active = document.layers.firstOrNull { it.id == document.active } ?: return
        active.adjustment?.let { prepareAdjustment(it.kind) }
    }

    fun groupLayers(ids: Set<Int>) {
        val selected = document.layers.filter { it.id in ids }
        if (selected.isEmpty()) return
        val parent = selected.first().parentId
        val siblings = document.siblings(parent)
        val indices = selected.map { siblings.indexOf(it) }
        if (
            selected.any { it.parentId != parent } ||
                indices.zipWithNext().any { (a, b) -> b != a + 1 }
        ) {
            error = "请选择同一层级的相邻图层"
            return
        }
        val revision = document.revision
        command("group_layers") {
            put("ids", JsonArray(selected.map { JsonPrimitive(it.id) }))
            put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("index", indices.first())
            put("name", "图层组")
            put("revision", revision)
        }
    }

    fun moveLayerNode(id: Int, parentId: Int?, index: Int, revision: Long = document.revision) {
        command("move_node") {
            put("id", id)
            put("parent_id", parentId?.let(::JsonPrimitive) ?: JsonNull)
            put("index", index)
            put("revision", revision)
        }
    }

    fun closeLayerGroup(layer: LayerInfo) {
        val revision = document.revision
        command("set_group_closed") {
            put("id", layer.id)
            put("closed", !layer.closed)
            put("revision", revision)
        }
    }

    fun updatePreferences(value: Preferences) {
        val updated = value.withAvailableBrushFavorites()
        if (!updated.valid()) {
            error = "设置参数无效"
            return
        }
        preferences = updated
        scope.launch { actions.send(Action.Settings(updated)) }
    }

    fun changeCanvasGrid(settings: CanvasGridSettings) {
        updatePreferences(preferences.copy(canvasGrid = settings))
    }

    fun addPaletteColors(colors: List<Long>): Boolean {
        val palette = (preferences.palette + colors).distinct()
        if (palette == preferences.palette) return true
        val added = palette.filter { it !in preferences.palette }
        val library =
            preferences.asePalette?.let {
                it.copy(swatches = it.swatches + paletteFromColors(added).swatches)
            }
        if (palette.size > StudioDefaults.maxPaletteColors || library?.valid() == false) {
            error = "色卡空间不足，请先移除一些颜色"
            return false
        }
        updatePreferences(preferences.copy(palette = palette, asePalette = library))
        return preferences.palette == palette
    }

    fun removePaletteColor(color: Long) {
        val library =
            preferences.asePalette?.let {
                val positions = IntArray(it.swatches.size + 1)
                for (i in it.swatches.indices) positions[i + 1] =
                    positions[i] + if (it.swatches[i].color == color) 0 else 1
                it.copy(
                    swatches = it.swatches.filter { swatch -> swatch.color != color },
                    groups =
                        it.groups.map { group ->
                            group.copy(start = positions[group.start], end = positions[group.end])
                        },
                )
            }
        updatePreferences(
            preferences.copy(palette = preferences.palette - color, asePalette = library)
        )
    }

    val supportsPaletteFiles: Boolean
        get() = files.supportsPaletteFiles

    val paletteFormats: List<PaletteFileFormat>
        get() = files.paletteFormats

    fun paletteExportLosesMetadata(format: PaletteFileFormat): Boolean =
        format != PaletteFileFormat.Ase &&
            TextPaletteCodec.requiresFlatten(
                TextPaletteFile(
                    preferences.asePalette ?: paletteFromColors(preferences.palette),
                    textPaletteFormat(format),
                    preferences.paletteFileMetadata.name,
                    preferences.paletteFileMetadata.columns,
                )
            )

    fun exportPalette(format: PaletteFileFormat, flattenMetadata: Boolean = false) {
        if (!ready || busy || previewPending() || format !in paletteFormats) return
        scope.launch {
            actions.send(Action.File(FileAction.ExportPalette, format, flattenMetadata))
        }
    }

    private fun textPaletteFormat(format: PaletteFileFormat): TextPaletteFormat =
        when (format) {
            PaletteFileFormat.Gpl -> TextPaletteFormat.Gpl
            PaletteFileFormat.JascPal -> TextPaletteFormat.JascPal
            PaletteFileFormat.Ase -> error("ASE 色板不能使用文本格式")
        }

    private fun paletteFromColors(colors: List<Long>) =
        AsePalette(
            colors.map {
                AseSwatch("#" + (it and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase(), it)
            }
        )

    fun extractPalette() {
        if (!ready || busy || extractingPalette || previewPending()) return
        extractingPalette = true
        scope.launch { actions.send(Action.ExtractPalette) }
    }

    fun toggleBrushFavorite(id: String) {
        if (brushes.none { it.id == id }) return
        val favorites = preferences.favoriteBrushes
        updatePreferences(
            preferences.copy(
                favoriteBrushes = if (id in favorites) favorites - id else favorites + id
            )
        )
    }

    fun saveBrush(name: String, replace: Boolean = false) {
        if (name.isBlank() || previewPending()) return
        val existing = preferences.brushes.firstOrNull { it.id == brush.preset.id }
        if (replace && existing == null) return
        if (!replace && preferences.brushes.size >= StudioDefaults.maxCustomBrushes) return
        val id =
            if (replace) existing!!.id
            else
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
        if (!saved.valid()) return
        val updated =
            if (replace) preferences.brushes.map { if (it.id == id) saved else it }
            else preferences.brushes + saved
        updatePreferences(preferences.copy(brushes = updated))
        selectPreset(saved)
    }

    fun deleteBrush(id: String) {
        if (preferences.brushes.none { it.id == id } || previewPending()) return
        updatePreferences(
            preferences.copy(
                brushes = preferences.brushes.filterNot { it.id == id },
                favoriteBrushes = preferences.favoriteBrushes - id,
            )
        )
        if (brush.preset.id == id) selectPreset(BrushPreset.Ink)
    }

    fun reportError(message: String) {
        error = message
    }

    fun export(options: ExportOptions) {
        if (previewPending()) return
        if (!ready || busy) return
        require(options.format in exportFormats)
        val captured = options.copy(frameId = document.animation?.activeFrameId)
        val vectorTarget = buildJsonObject {
            put("id", document.active)
        }
            .withAnimationTarget(document)
        scope.launch { actions.send(Action.Export(captured, vectorTarget)) }
    }

    fun exportAnimation(options: AnimationExportOptions) {
        if (
            !ready ||
                busy ||
                drawingInput ||
                animationTransition ||
                animationPlaying ||
                previewPending()
        )
            return
        if (options.format !in animationExportFormats) return
        options.validation(document)?.let {
            error = it.label
            return
        }
        val revision = document.revision
        scope.launch { actions.send(Action.AnimationExport(options, revision)) }
    }

    fun exportAseprite(options: AsepriteExportOptions = AsepriteExportOptions()) {
        if (
            !ready ||
                busy ||
                drawingInput ||
                animationTransition ||
                animationPlaying ||
                previewPending()
        )
            return
        if (!asepriteExportAvailable) return
        val capability = document.asepriteExport ?: return
        if (capability.blockingIssues.isNotEmpty()) {
            error = "当前工程包含暂不支持的 Aseprite 播放或工程数据"
            return
        }
        if (!options.bakeLayers && capability.editableIssues.isNotEmpty()) {
            error = "请明确选择导出合成帧副本"
            return
        }
        val revision = document.revision
        scope.launch { actions.send(Action.AsepriteExport(options, revision)) }
    }

    fun select(start: Offset, end: Offset) {
        if (selectionKind == SelectionKind.MagicWand) {
            selectColor(start)
            return
        }
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
        if (selection == null) {
            if (selectionMode == SelectionMode.Replace) clearSelection()
        } else
            command("combine_selection") {
                put("selection", Json.encodeToJsonElement(selection))
                put("mode", Json.encodeToJsonElement(selectionMode))
            }
    }

    fun selectColor(point: Offset) {
        if (
            !point.x.isFinite() ||
                !point.y.isFinite() ||
                point.x < 0 ||
                point.y < 0 ||
                point.x >= document.width ||
                point.y >= document.height ||
                !selectionTolerance.isFinite() ||
                selectionTolerance !in 0f..255f
        )
            return
        command("select_color") {
            putJsonObject("settings") {
                put("x", point.x.toInt())
                put("y", point.y.toInt())
                put("tolerance", selectionTolerance.toInt())
                put("contiguous", selectionContiguous)
                put("merged", selectionMerged)
            }
            put("mode", Json.encodeToJsonElement(selectionMode))
        }
    }

    fun changeSelectionMode(mode: SelectionMode) {
        cancelSelectionGesture()
        selectionMode = mode
    }

    fun invertSelection() {
        if (document.selection == null) return
        cancelSelectionGesture()
        command("invert_selection")
    }

    fun refineSelection(kind: SelectionRefinement, radius: Int) {
        if (document.selection == null || radius !in 0..StudioDefaults.maxDimension) return
        cancelSelectionGesture()
        val snapshot = document
        command("modify_selection") {
            put("kind", Json.encodeToJsonElement(kind))
            put("radius", radius)
            put("revision", snapshot.revision)
            put("selection_id", snapshot.selectionId)
        }
    }

    fun cancelSelectionGesture() {
        selectionCancellation++
    }

    fun clearSelection() {
        cancelSelectionGesture()
        selectionMode = StudioDefaults.selectionMode
        command("select") { put("rect", JsonNull) }
    }

    fun fillLasso(points: List<SelectionPoint>, eraser: Boolean = false) {
        if (
            points.size !in 3..StudioDefaults.maxSelectionPoints ||
                points.any { !it.x.isFinite() || !it.y.isFinite() }
        )
            return
        val settings = brush
        command("fill_lasso") {
            put("points", Json.encodeToJsonElement(points))
            put("opacity", settings.opacity)
            put("eraser", eraser)
            putJsonArray("color") {
                add((settings.color shr 16 and 255).toInt())
                add((settings.color shr 8 and 255).toInt())
                add((settings.color and 255).toInt())
            }
        }
    }

    fun fill(point: Offset) {
        val settings = brush
        val indexed = document.colorMode == DocumentColorMode.Indexed && !document.maskEditing
        command(if (indexed) "fill_indexed" else "fill") {
            if (indexed) put("index", indexedColorIndex)
            if (indexed) put("opacity", settings.opacity)
            put("x", point.x.toInt())
            put("y", point.y.toInt())
            put("tolerance", fillTolerance.toInt())
            put("contiguous", fillContiguous)
            put("merged", fillMerged && !document.maskEditing)
            putJsonArray("color") {
                add((settings.color shr 16 and 255).toInt())
                add((settings.color shr 8 and 255).toInt())
                add((settings.color and 255).toInt())
                add((settings.opacity * 255).toInt())
            }
        }
    }

    fun dismissError() {
        error = null
    }

    fun selectIndexedColor(index: Int) {
        val palette = document.indexedPalette ?: return
        if (index !in palette.colors.indices) return
        indexedColorIndex = index
        brush = brush.copy(color = palette.argb(index))
    }

    fun convertColorMode(mode: DocumentColorMode) {
        if (mode == document.colorMode) return
        val revision = document.revision
        command("convert_color_mode") {
            put("mode", Json.encodeToJsonElement(mode))
            put("revision", revision)
            if (mode == DocumentColorMode.Indexed)
                put("palette", Json.encodeToJsonElement(IndexedPalette.defaults()))
        }
    }

    fun setIndexedColor(index: Int, color: Long) {
        if (index !in (document.indexedPalette?.colors?.indices ?: return)) return
        val revision = document.revision
        command("set_palette_color") {
            put("index", index)
            put("revision", revision)
            put("color", Json.encodeToJsonElement(indexedColorChannels(color)))
        }
    }

    fun addIndexedColor(color: Long) {
        val palette = document.indexedPalette ?: return
        if (palette.colors.size >= StudioDefaults.maxIndexedColors) return
        val revision = document.revision
        command("add_palette_color") {
            put("revision", revision)
            put("color", Json.encodeToJsonElement(indexedColorChannels(color)))
        }
    }

    fun reorderIndexedColor(index: Int, direction: Int) {
        val palette = document.indexedPalette ?: return
        val from = palette.order.indexOf(index)
        val to = from + direction
        if (from < 0 || to !in palette.order.indices) return
        val order = palette.order.toMutableList().apply { add(to, removeAt(from)) }
        val revision = document.revision
        command("reorder_palette") {
            put("revision", revision)
            put("order", Json.encodeToJsonElement(order))
        }
    }

    fun removeIndexedColor(index: Int, replacement: Int) {
        val palette = document.indexedPalette ?: return
        if (
            palette.colors.size <= 2 ||
                index !in palette.colors.indices ||
                replacement !in palette.colors.indices ||
                index == replacement
        )
            return
        val revision = document.revision
        command("remove_palette_color") {
            put("revision", revision)
            put("index", index)
            put("replacement", replacement)
        }
    }

    private fun indexedColorChannels(color: Long) =
        listOf(
            (color shr 16 and 255).toInt(),
            (color shr 8 and 255).toInt(),
            (color and 255).toInt(),
            (color shr 24 and 255).toInt(),
        )

    private fun nearestPaletteIndex(palette: IndexedPalette, color: Long): Int =
        palette.colors.indices
            .filter { palette.colors[it][3] != 0 }
            .minByOrNull { index ->
                val target = indexedColorChannels(color)
                palette.colors[index].zip(target).sumOf { (a, b) -> (a - b) * (a - b) }
            } ?: palette.transparent

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
