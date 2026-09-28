package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import app.podor.domain.LayerInfo
import app.podor.domain.LayerTransform

data class LayerFrame(val layer: LayerInfo, val tiles: List<TileImage>)

class LayerMovePreview(
    val layerId: Int,
    val revision: Long,
    val layers: List<LayerFrame>,
    val sourceBounds: Rect? = null,
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
