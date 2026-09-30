package app.podor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LayerKind {
    @SerialName("raster") Raster,
    @SerialName("group") Group,
    @SerialName("adjustment") Adjustment,
    @SerialName("vector") Vector,
}

@Serializable
enum class GroupIsolation(val label: String) {
    @SerialName("isolated") Isolated("独立合成"),
    @SerialName("pass_through") PassThrough("穿透"),
}

@Serializable
data class LayerInfo(
    val id: Int,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: LayerBlendMode = LayerBlendMode.Normal,
    val alphaLocked: Boolean = false,
    val locked: Boolean = false,
    val mask: LayerMaskInfo? = null,
    val clipping: Boolean = false,
    val clippingBase: Int? = null,
    val kind: LayerKind = LayerKind.Raster,
    val parentId: Int? = null,
    val depth: Int = 0,
    val childCount: Int = 0,
    val isolation: GroupIsolation? = null,
    val closed: Boolean = false,
    val effectiveVisible: Boolean = visible,
    val effectiveLocked: Boolean = locked,
    val adjustment: AdjustmentLayerSettings? = null,
    val masks: List<LayerMaskInfo> = emptyList(),
    val vector: VectorLayerInfo? = null,
    val celId: Int? = null,
    val hasCel: Boolean = false,
    val maskScope: LayerMaskScope = LayerMaskScope.Global,
) {
    val maskEntries: List<LayerMaskInfo>
        get() = masks.ifEmpty { listOfNotNull(mask) }
}

@Serializable
data class LayerMaskInfo(
    val enabled: Boolean = true,
    val linked: Boolean = true,
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class DocumentInfo(
    val width: Int = 1600,
    val height: Int = 1200,
    val active: Int = 1,
    val revision: Long = 0,
    val contentId: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val maxLayers: Int = 0,
    val layers: List<LayerInfo> = emptyList(),
    val selection: Selection? = null,
    val maskEditing: Boolean = false,
    val selectionId: Long = 0,
    val colorMode: DocumentColorMode = DocumentColorMode.Rgba,
    val indexedPalette: IndexedPalette? = null,
    val uiOrder: List<Int> = emptyList(),
    val maxLayerNodes: Int = 0,
    val maxGroupDepth: Int = 0,
    val activeMaskId: Int? = null,
    val maxLayerMasks: Int = 0,
    val maxVectorObjects: Int = 0,
    val maxVectorSegments: Int = 0,
    val assistants: DrawingAssistantSet = DrawingAssistantSet(),
    val maxDrawingAssistants: Int = 0,
    val maxAssistantCoordinate: Float = 0f,
    val maxAssistantStrokeCoordinate: Float = 0f,
    val maxGeneratedLines: Int = 0,
    val animation: AnimationInfo? = null,
    val maxAnimationFrames: Int = 0,
    val maxAnimationCels: Int = 0,
    val maxAnimationTags: Int = 0,
    val maxFrameThumbnails: Int = 0,
    val animationExport: AnimationExportCapabilities? = null,
    val asepriteExport: AsepriteExportCapabilities? = null,
    val asepriteMetadata: AsepriteMetadata? = null,
) {
    val rasterLayerCount: Int
        get() = layers.count { it.kind == LayerKind.Raster }

    val drawableLayerCount: Int
        get() = layers.count { it.kind == LayerKind.Raster || it.kind == LayerKind.Vector }

    fun siblings(parentId: Int?): List<LayerInfo> = layers.filter { it.parentId == parentId }

    fun ancestorIds(id: Int): List<Int> {
        val byId = layers.associateBy { it.id }
        val result = mutableListOf<Int>()
        var parent = byId[id]?.parentId
        repeat(layers.size) {
            val current = parent ?: return result
            result.add(current)
            parent = byId[current]?.parentId
        }
        return result
    }

    fun layerRows(): List<LayerInfo> {
        if (uiOrder.isEmpty()) return layers.asReversed()
        val byId = layers.associateBy { it.id }
        return uiOrder.mapNotNull(byId::get).filter { layer ->
            ancestorIds(layer.id).none { byId[it]?.closed == true }
        }
    }
}

enum class Tool(val label: String) {
    Brush("画笔"),
    Eraser("橡皮"),
    Picker("取色"),
    Hand("平移画布"),
    Select("选区"),
    Fill("填充"),
    MoveLayer("移动图层"),
    TransformLayer("变换图层"),
    Gradient("渐变"),
    Smudge("涂抹"),
    LassoFill("柳叶笔"),
    Vector("矢量工具"),
    Assistant("绘画助手"),
    LineGenerator("漫画线条"),
}

@Serializable
data class BrushPreset(
    val id: String,
    val label: String,
    val hardness: Float,
    val opacity: Float,
    val size: Float,
    val tip: BrushTip = BrushTip.Round,
    val aspect: Float = 1f,
    val angle: Float = 0f,
    val grain: Float = 0f,
    val spacing: Float = 0.08f,
    val stabilization: Float = StudioDefaults.stabilization,
    val followDirection: Boolean = false,
    val pressureCurve: Float = StudioDefaults.pressureCurve,
    val sizePressure: Float = StudioDefaults.sizePressure,
    val opacityPressure: Float = StudioDefaults.opacityPressure,
    val mix: Float = 0f,
    val paper: Float = 0f,
    val texture: BrushTexture = BrushTexture.Smooth,
    val raster: BrushRaster = BrushRaster.Antialiased,
) {
    fun valid(): Boolean =
        id.matches(Regex("[a-zA-Z0-9._-]{1,64}")) &&
            label.isNotBlank() &&
            label.length <= 60 &&
            hardness.isFinite() &&
            hardness in 0f..1f &&
            opacity.isFinite() &&
            opacity in 0f..1f &&
            size.isFinite() &&
            size in 1f..256f &&
            aspect.isFinite() &&
            aspect in 0.1f..1f &&
            angle.isFinite() &&
            angle in -180f..180f &&
            grain.isFinite() &&
            grain in 0f..1f &&
            spacing.isFinite() &&
            spacing in 0.02f..1f &&
            stabilization.isFinite() &&
            stabilization in 0f..1f &&
            pressureCurve.isFinite() &&
            pressureCurve in -1f..1f &&
            sizePressure.isFinite() &&
            sizePressure in 0f..1f &&
            opacityPressure.isFinite() &&
            opacityPressure in 0f..1f &&
            mix.isFinite() &&
            mix in 0f..1f &&
            paper.isFinite() &&
            paper in 0f..1f

    companion object {
        val Ink = BrushPreset("ink", "墨水笔", 0.9f, 1f, 12f)
        val Marker = BrushPreset("marker", "马克笔", 0.85f, 0.45f, 36f, BrushTip.Flat, 0.65f, -25f)
        val Soft = BrushPreset("soft", "柔边笔", 0f, 0.12f, 80f)
        val PixelPencil =
            BrushPreset(
                "pixel-pencil",
                "像素铅笔",
                1f,
                1f,
                1f,
                stabilization = 0f,
                sizePressure = 0f,
                raster = BrushRaster.Pixel,
            )
        val PixelPerfect =
            PixelPencil.copy(
                id = "pixel-perfect",
                label = "像素完美铅笔",
                raster = BrushRaster.PixelPerfect,
            )
        val entries =
            listOf(
                Ink,
                BrushPreset(
                    "liner",
                    "勾线笔",
                    1f,
                    1f,
                    8f,
                    stabilization = StudioDefaults.lineStabilization,
                    sizePressure = 0.15f,
                ),
                Marker,
                Soft,
                BrushPreset(
                    "pencil",
                    "铅笔",
                    0.85f,
                    0.7f,
                    7f,
                    grain = 0.32f,
                    spacing = 0.04f,
                    texture = BrushTexture.Graphite,
                ),
                BrushPreset(
                    "charcoal",
                    "炭笔",
                    0.7f,
                    0.65f,
                    44f,
                    aspect = 0.6f,
                    grain = 0.35f,
                    texture = BrushTexture.Charcoal,
                ),
                BrushPreset("airbrush", "喷枪", 0f, 0.035f, 160f, spacing = 0.03f),
                BrushPreset(
                    "watercolor",
                    "水彩",
                    0.65f,
                    0.12f,
                    90f,
                    grain = 0.12f,
                    spacing = 0.06f,
                    paper = 0.25f,
                    texture = BrushTexture.Wash,
                ),
                BrushPreset(
                    "chisel",
                    "斜头笔",
                    0.95f,
                    0.7f,
                    38f,
                    BrushTip.Flat,
                    0.25f,
                    -35f,
                    texture = BrushTexture.Bristle,
                ),
                BrushPreset(
                    "flat",
                    "平刷",
                    0.85f,
                    0.5f,
                    60f,
                    BrushTip.Flat,
                    0.45f,
                    90f,
                    grain = 0.08f,
                    followDirection = true,
                    texture = BrushTexture.Bristle,
                ),
                BrushPreset(
                    "ribbon",
                    "缎带",
                    0.95f,
                    1f,
                    42f,
                    BrushTip.Flat,
                    0.2f,
                    90f,
                    spacing = 0.04f,
                    stabilization = StudioDefaults.lineStabilization,
                    followDirection = true,
                ),
                BrushPreset(
                    "dry-flat",
                    "干刷",
                    0.9f,
                    0.55f,
                    56f,
                    BrushTip.Flat,
                    0.25f,
                    90f,
                    grain = 0.2f,
                    spacing = 0.08f,
                    followDirection = true,
                    texture = BrushTexture.DryBristle,
                ),
                BrushPreset(
                    "rake",
                    "排线笔",
                    1f,
                    1f,
                    34f,
                    BrushTip.Comb,
                    0.12f,
                    0f,
                    spacing = 0.06f,
                    stabilization = StudioDefaults.lineStabilization,
                    followDirection = true,
                ),
                BrushPreset("stipple", "点描", 1f, 1f, 5f, spacing = 0.95f),
                BrushPreset(
                    "pressure-ink",
                    "压感墨笔",
                    0.95f,
                    1f,
                    32f,
                    stabilization = StudioDefaults.lineStabilization,
                    pressureCurve = 0.5f,
                    opacityPressure = 0.25f,
                ),
                BrushPreset(
                    "glaze",
                    "薄涂笔",
                    0.35f,
                    0.12f,
                    100f,
                    spacing = 0.06f,
                    sizePressure = 0.2f,
                    opacityPressure = 1f,
                    texture = BrushTexture.Pigment,
                ),
                BrushPreset(
                    "lance",
                    "尖锋笔",
                    0.95f,
                    1f,
                    34f,
                    BrushTip.Leaf,
                    0.35f,
                    spacing = 0.05f,
                    stabilization = StudioDefaults.lineStabilization,
                    followDirection = true,
                    pressureCurve = 0.25f,
                ),
                BrushPreset(
                    "willow",
                    "柳条笔",
                    0.9f,
                    0.85f,
                    52f,
                    BrushTip.Leaf,
                    0.22f,
                    spacing = 0.04f,
                    stabilization = StudioDefaults.lineStabilization,
                    followDirection = true,
                    opacityPressure = 0.3f,
                    texture = BrushTexture.Bristle,
                ),
                BrushPreset(
                    "mixing",
                    "混合笔",
                    0.6f,
                    0.8f,
                    56f,
                    spacing = 0.05f,
                    stabilization = 0.2f,
                    mix = 0.8f,
                    texture = BrushTexture.Bristle,
                ),
                BrushPreset(
                    "oily",
                    "油彩笔",
                    0.65f,
                    0.75f,
                    64f,
                    BrushTip.Flat,
                    0.5f,
                    90f,
                    grain = 0.08f,
                    spacing = 0.07f,
                    stabilization = 0.2f,
                    followDirection = true,
                    paper = 0.15f,
                    texture = BrushTexture.Pigment,
                ),
                BrushPreset(
                    "rough-paper",
                    "纸纹笔",
                    0.75f,
                    0.7f,
                    72f,
                    grain = 0.15f,
                    paper = 0.7f,
                    texture = BrushTexture.Canvas,
                ),
                BrushPreset(
                    "watercolor-paper",
                    "水彩纸",
                    0.55f,
                    0.1f,
                    110f,
                    grain = 0.1f,
                    spacing = 0.05f,
                    paper = 0.7f,
                    texture = BrushTexture.Wash,
                ),
                PixelPencil,
                PixelPerfect,
            )
    }
}

@Serializable
enum class BrushTip {
    Round,
    Flat,
    Leaf,
    Comb,
}

@Serializable
enum class BrushRaster(val label: String, val engineName: String) {
    Antialiased("平滑笔触", "antialiased"),
    Pixel("像素笔触", "pixel"),
    PixelPerfect("像素完美", "pixel_perfect"),
}

@Serializable
enum class BrushTexture(val label: String, val engineName: String) {
    Smooth("光滑", "smooth"),
    Graphite("石墨", "graphite"),
    Charcoal("炭粉", "charcoal"),
    Bristle("刷毛", "bristle"),
    DryBristle("干燥刷毛", "dry_bristle"),
    Pigment("颜料", "pigment"),
    Canvas("纤维", "canvas"),
    Wash("水彩湿边", "wash"),
}

data class BrushSettings(
    val preset: BrushPreset = BrushPreset.Ink,
    val size: Float = BrushPreset.Ink.size,
    val opacity: Float = BrushPreset.Ink.opacity,
    val color: Long = StudioDefaults.brushColor,
)

data class CanvasPreset(val label: String, val width: Int, val height: Int)

object StudioDefaults {
    val toolDockPosition = ToolDockPosition.Left
    val inspectorPosition = InspectorPosition.Right
    val interfaceDensity = InterfaceDensity.Standard
    const val interfaceScale = 1f
    const val reducedMotion = false
    val workspaceToolOrder =
        listOf(
            "Brush",
            "Eraser",
            "Select",
            "Fill",
            "Picker",
            "Hand",
            "MoveLayer",
            "TransformLayer",
            "Gradient",
            "Smudge",
            "LassoFill",
            "Vector",
            "Assistant",
            "LineGenerator",
        )
    const val animationFrameDuration = 100
    const val animationCacheBytes = 32L * 1024 * 1024
    const val animationThumbnailBatchSize = 8
    val animationExportFormat = AnimationExportFormat.Gif
    val animationExportColorPolicy = GifColorPolicy.Quantize
    val animationExportTiming = GifTimingPolicy.Round
    const val animationExportAlphaThreshold = 128
    const val animationExportColumns = 0
    const val animationExportPadding = 0
    const val onionOpacity = 0.25f
    const val concentrationCount = 64
    const val speedLineCount = 32
    const val lineGeneratorSeed = 42L
    const val lineGeneratorOpacity = 1f
    const val lineGeneratorRandomness = 0.25f
    const val maxReferenceImages = 8
    const val maxReferenceEdge = 2048
    const val referenceInitialFraction = 0.42f
    const val referenceMinZoom = 0.25f
    const val referenceMaxZoom = 8f
    const val maxCurvePoints = 16
    const val maxGradientMapStops = 16
    const val maxIndexedColors = 256
    val canvasBackground = CanvasBackground.White
    const val layerOpacity = 1f
    const val maxCustomBrushes = 64
    const val brushSearchLength = 60
    const val brushPreviewWidth = 320
    const val brushPreviewHeight = 96
    const val brushPreviewCacheSize = 96
    const val brushPreviewDebounceMillis = 60L
    const val gridTileSize = 16
    const val gridMinimumSpacing = 8f
    const val fillTolerance = 24f
    const val fillContiguous = true
    const val fillMerged = false
    val brushCollection = BrushCollection.All
    val vectorTool = VectorEditorTool.Rectangle
    const val vectorMiterLimit = 4f
    const val toneAmount = 0f
    const val blurSigma = 4f
    const val minBlurSigma = 0.5f
    const val maxBlurSigma = 32f
    const val extractedPaletteSize = 12
    const val maxPaletteColors = 256
    const val maxPaletteFileBytes = 1024 * 1024
    const val maxPaletteGroups = 1024
    const val maxPaletteGroupDepth = 32
    const val maxPaletteNameUnits = 1024
    const val paletteColumns = 6
    val symmetryMode = SymmetryMode.Off
    const val symmetryAxis = 0.5f
    const val symmetryGuides = true
    const val maxClipboardBytes = 65 * 1024 * 1024
    val selectionKind = SelectionKind.Rectangle
    val selectionMode = SelectionMode.Replace
    const val selectionTolerance = 24f
    const val selectionRefinementRadius = 4f
    const val maxSelectionRefinementRadius = 64f
    const val selectionContiguous = true
    const val selectionMerged = false
    const val maxSelectionPoints = 4096
    const val selectionSampleDistance = 0.75f
    val resampleFilter = ResampleFilter.Lanczos3
    const val imageSizeLocked = true
    val imageScalePresets = listOf(50, 100, 200)
    val canvasAnchor = CanvasAnchor.Center
    const val brushColor = 0xFF000000L
    const val smudgeStrength = 0.8f
    const val stabilization = 0f
    const val lineStabilization = 0.5f
    const val pressureCurve = 0f
    const val sizePressure = 1f
    const val opacityPressure = 0f
    const val exportQuality = 90
    const val maxDimension = 8192
    const val maxTransformOffset = maxDimension * 2f
    const val transformNudge = 1f
    const val transformFastNudge = 10f
    const val gradientEndColor = 0xFFFFFFFFL
    const val gradientOpacity = 1f
    const val maxGradientCoordinate = maxDimension * 2f
    const val gradientMinLength = 0.001f
    const val maxCanvasPixels = 16_777_216L
    val canvasPresets =
        listOf(
            CanvasPreset("横向画布", 1600, 1200),
            CanvasPreset("方形画布", 1600, 1600),
            CanvasPreset("竖向画布", 1200, 1600),
            CanvasPreset("高清画布", 2560, 1440),
        )
    val palette =
        listOf(
            0xFF8B2942,
            0xFF8E78E7,
            0xFFE8AFA1,
            0xFFEEC878,
            0xFF83B5A3,
            0xFF6E9CB8,
            0xFF25262B,
            0xFFFFFFFF,
            0xFFDB6570,
            0xFFE29756,
            0xFF8DA367,
            0xFFB7AEC9,
        )
    const val frameMillis = 16L
    const val inputQueueCapacity = 64
    const val nativeInputDispatchLimit = 8
    const val nativeTouchContacts = 32
    const val nativeTabletDevices = 64
    const val maxBatchSamples = 256
    const val zoomStep = 1.2f
    const val brushSizeStep = 1.12f
    const val minBrushSize = 1f
    const val maxBrushSize = 256f
    const val minZoom = 0.1f
    const val maxZoom = 8f
    const val rotationStep = 15f
}
