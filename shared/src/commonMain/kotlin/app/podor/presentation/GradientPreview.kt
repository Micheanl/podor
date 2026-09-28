package app.podor.presentation

import androidx.compose.runtime.*
import app.podor.domain.GradientLine
import app.podor.domain.Selection

class GradientPreview(
    val layerId: Int,
    val revision: Long,
    val selection: Selection?,
    val layers: List<LayerFrame>,
    val mask: List<TileImage>,
) {
    var line by mutableStateOf<GradientLine?>(null)
        internal set

    var committing by mutableStateOf(false)
        internal set
}
