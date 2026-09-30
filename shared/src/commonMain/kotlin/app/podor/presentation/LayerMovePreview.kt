package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import app.podor.domain.LayerInfo
import app.podor.domain.LayerTransform
import kotlinx.serialization.json.JsonObject

data class LayerFrame(
    val layer: LayerInfo,
    val tiles: List<TileImage>,
    val stationary: List<TileImage> = emptyList(),
    val mask: LayerMaskFrame? = null,
)

data class LayerMaskFrame(
    val bounds: Rect,
    val default: Int,
    val enabled: Boolean,
    val linked: Boolean,
    val tiles: List<TileImage>,
    val split: Boolean = false,
    val selectedTiles: List<TileImage> = emptyList(),
)

class LayerMovePreview(
    val layerId: Int,
    val revision: Long,
    val layers: List<LayerFrame>,
    val sourceBounds: Rect? = null,
    val selection: app.podor.domain.Selection? = null,
    val maskEditing: Boolean = false,
    val selectionId: Long = 0,
    val canonical: LayerActionPreview? = null,
    val maskId: Int? = null,
) {
    var transform by
        mutableStateOf(sourceBounds?.let { LayerTransform(it.width.toInt(), it.height.toInt()) })
        internal set

    var proportional by mutableStateOf(true)

    val transformChanged: Boolean
        get() {
            val value = transform ?: return false
            val source = sourceBounds ?: return false
            return value.width != source.width.toInt() ||
                value.height != source.height.toInt() ||
                value.dx != 0f ||
                value.dy != 0f ||
                value.angle != 0f ||
                value.flipX ||
                value.flipY
        }

    var offset by mutableStateOf(IntOffset.Zero)
        internal set

    var committing by mutableStateOf(false)
        internal set
}

class LayerActionPreview(val original: RenderFrame) {
    var frame by mutableStateOf(original)
        internal set

    var renderedAction: JsonObject? = null
        internal set
}
