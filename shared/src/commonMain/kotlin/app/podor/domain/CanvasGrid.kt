package app.podor.domain

import kotlinx.serialization.Serializable

@Serializable
data class CanvasGridSettings(
    val pixels: Boolean = false,
    val tiles: Boolean = false,
    val tileWidth: Int = StudioDefaults.gridTileSize,
    val tileHeight: Int = StudioDefaults.gridTileSize,
) {
    fun valid() =
        tileWidth in 1..StudioDefaults.maxDimension &&
            tileHeight in 1..StudioDefaults.maxDimension
}
