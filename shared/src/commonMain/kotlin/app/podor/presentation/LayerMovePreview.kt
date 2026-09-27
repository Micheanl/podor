package app.podor.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import app.podor.domain.LayerInfo

data class LayerFrame(val layer: LayerInfo, val tiles: List<TileImage>)

class LayerMovePreview(val layerId: Int, val revision: Long, val layers: List<LayerFrame>) {
    var offset by mutableStateOf(IntOffset.Zero)
        internal set

    var committing by mutableStateOf(false)
        internal set
}
