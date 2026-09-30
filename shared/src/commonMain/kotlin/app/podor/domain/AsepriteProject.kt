package app.podor.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AsepriteCompanionPalette(
    val colors: List<List<Int>>,
    val names: List<String?>,
)

@Serializable data class AsepriteGrid(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class AsepriteMetadata(
    val companionPalette: AsepriteCompanionPalette? = null,
    val indexedPaletteNames: List<String?> = emptyList(),
    val grid: AsepriteGrid,
    val srgb: Boolean = false,
)

@Serializable data class AsepriteExportIssue(val kind: String, val count: Int)

@Serializable
data class AsepriteExportCapabilities(
    val operation: Int,
    val maxFrames: Int,
    val maxCels: Int,
    val maxLayers: Int,
    val maxLayerNodes: Int,
    val maxGroupDepth: Int,
    val maxPaletteColors: Int,
    val maxInputBytes: Long,
    val maxOutputBytes: Long,
    val maxDecodedBytes: Long,
    val maxScratchBytes: Long,
    val fullFormatSupport: Boolean,
    val editableIssues: List<AsepriteExportIssue> = emptyList(),
    val blockingIssues: List<AsepriteExportIssue> = emptyList(),
) {
    val available: Boolean
        get() =
            operation == 27 &&
                maxFrames > 0 &&
                maxCels > 0 &&
                maxLayers > 0 &&
                maxLayerNodes > 0 &&
                maxGroupDepth > 0 &&
                maxPaletteColors > 0 &&
                maxInputBytes > 0 &&
                maxOutputBytes > 0 &&
                maxDecodedBytes > 0 &&
                maxScratchBytes > 0
}

data class AsepriteExportOptions(val bakeLayers: Boolean = false) {
    fun requestJson(revision: Long): JsonObject = buildJsonObject {
        put("revision", revision)
        put("bake_layers", bakeLayers)
    }
}
