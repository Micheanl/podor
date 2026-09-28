package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
data class LayerInfo(
    val id: Int,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: LayerBlendMode = LayerBlendMode.Normal,
    val alphaLocked: Boolean = false,
    val locked: Boolean = false,
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
)

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
            opacityPressure in 0f..1f

    companion object {
        val Ink = BrushPreset("ink", "墨水笔", 0.9f, 1f, 12f)
        val Marker = BrushPreset("marker", "马克笔", 0.7f, 0.45f, 36f)
        val Soft = BrushPreset("soft", "柔边笔", 0f, 0.12f, 80f)
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
                ),
                Marker,
                Soft,
                BrushPreset("pencil", "铅笔", 0.8f, 0.7f, 5f, grain = 0.8f, spacing = 0.04f),
                BrushPreset("charcoal", "炭笔", 0.55f, 0.5f, 44f, aspect = 0.6f, grain = 0.95f),
                BrushPreset("airbrush", "喷枪", 0f, 0.035f, 160f, spacing = 0.03f),
                BrushPreset("watercolor", "水彩", 0.15f, 0.08f, 90f, grain = 0.45f, spacing = 0.06f),
                BrushPreset("chisel", "斜头笔", 0.95f, 0.7f, 38f, BrushTip.Flat, 0.25f, -35f),
                BrushPreset("flat", "平刷", 0.8f, 0.5f, 60f, BrushTip.Flat, 0.45f, grain = 0.3f),
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
                    0.75f,
                    0.55f,
                    56f,
                    BrushTip.Flat,
                    0.25f,
                    90f,
                    grain = 0.9f,
                    spacing = 0.06f,
                    followDirection = true,
                ),
                BrushPreset(
                    "rake",
                    "排线笔",
                    1f,
                    1f,
                    30f,
                    BrushTip.Flat,
                    0.1f,
                    90f,
                    spacing = 0.5f,
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
                    0.2f,
                    0.12f,
                    100f,
                    spacing = 0.06f,
                    sizePressure = 0.2f,
                    opacityPressure = 1f,
                ),
            )
    }
}

@Serializable
enum class BrushTip {
    Round,
    Flat,
}

data class BrushSettings(
    val preset: BrushPreset = BrushPreset.Ink,
    val size: Float = BrushPreset.Ink.size,
    val opacity: Float = BrushPreset.Ink.opacity,
    val color: Long = StudioDefaults.brushColor,
)

data class CanvasPreset(val label: String, val width: Int, val height: Int)

object StudioDefaults {
    const val layerOpacity = 1f
    const val maxCustomBrushes = 64
    const val brushSearchLength = 60
    val brushCollection = BrushCollection.All
    const val toneAmount = 0f
    const val blurSigma = 4f
    const val minBlurSigma = 0.5f
    const val maxBlurSigma = 32f
    const val extractedPaletteSize = 12
    const val maxPaletteColors = 24
    const val paletteColumns = 6
    val symmetryMode = SymmetryMode.Off
    const val symmetryAxis = 0.5f
    const val symmetryGuides = true
    const val maxClipboardBytes = 65 * 1024 * 1024
    val selectionKind = SelectionKind.Rectangle
    val selectionMode = SelectionMode.Replace
    const val selectionTolerance = 24f
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
    const val maxBatchSamples = 256
    const val minZoom = 0.1f
    const val maxZoom = 8f
    const val rotationStep = 15f
}
